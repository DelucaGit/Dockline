package io.github.delucagit.dockline.hostinger;

import io.github.delucagit.dockline.ApiProblem;
import io.github.delucagit.dockline.hostinger.HostingerApi.Domain;
import io.github.delucagit.dockline.hostinger.HostingerApi.Domains;
import io.github.delucagit.dockline.log.LogLevel;
import io.github.delucagit.dockline.log.LogSource;
import io.github.delucagit.dockline.log.ProblemLog;
import io.github.delucagit.dockline.status.ConnectionState;
import io.github.delucagit.dockline.status.SourceStatus;
import io.github.delucagit.dockline.token.Source;
import io.github.delucagit.dockline.token.TokenStore;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Lists the user's Hostinger domains with {@code GET /api/domains/v1/portfolio} (one JSON array, no paging),
 * sorted by earliest expiry (no expiry last), then by name. The call goes through {@link HostingerClient}
 * (shared request cap, 60-second cache, 429 handling). Failures use the same codes, Log entries and status
 * updates as {@link HostingerSiteList}.
 *
 * <p>The portfolio can hold the same domain name twice (a domain claimed for free with a hosting plan: one
 * {@code free_domain} row without a date and one {@code domain} row with it). Each name is listed once, see
 * {@link #oncePerName}; rows without a name (a free domain not claimed yet) all stay.
 *
 * <p>The portfolio has no {@code expires_at} for some domains (a free domain that came with a hosting plan).
 * For each named domain without a date, {@code GET /api/domains/v1/portfolio/{domain}/renewal} is asked and its
 * {@code expires_at} used. That extra call never fails the list: when it gives no date the domain simply stays
 * without one, and a real failure (anything but a plain 404) is written to the Log section as a warning.
 */
@Component
class HostingerDomainList {

    static final String ACTION = "GET " + HostingerClient.DOMAINS_PATH;

    private static final Comparator<Domain> BY_EXPIRY = Comparator
            .comparing(Domain::expiresAt, Comparator.nullsLast(Comparator.<Instant>naturalOrder()))
            .thenComparing(Domain::domain, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));

    private final TokenStore tokenStore;
    private final HostingerClient client;
    private final HostingerStatusProvider status;
    private final ProblemLog problemLog;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper();

    HostingerDomainList(TokenStore tokenStore, HostingerClient client, HostingerStatusProvider status,
            ProblemLog problemLog, Clock clock) {
        this.tokenStore = tokenStore;
        this.client = client;
        this.status = status;
        this.problemLog = problemLog;
        this.clock = clock;
    }

    Domains list(boolean refresh) {
        String token = tokenStore.read(Source.HOSTINGER).orElseThrow(() -> new ApiProblem(HttpStatus.CONFLICT,
                HostingerSiteList.NOT_CONNECTED, "Hostinger is not connected",
                "Connect to Hostinger in Settings to see your domains."));
        HostingerClient.Reply reply = fetch(token, refresh);
        if (!reply.ok()) {
            throw failed(reply, token);
        }
        List<Domain> domains = oncePerName(read(reply, token));
        fillMissingExpiry(domains, token, refresh);
        domains.sort(BY_EXPIRY);
        return new Domains(List.copyOf(domains), reply.fetchedAt() != null ? reply.fetchedAt() : clock.instant());
    }

    private HostingerClient.Reply fetch(String token, boolean refresh) {
        try {
            return client.getDomains(token, refresh);
        } catch (HostingerRateLimitedException e) {
            // The client already logged the 429; the status dot reads the same wait from the limiter.
            throw new ApiProblem(HttpStatus.TOO_MANY_REQUESTS, HostingerSiteList.RATE_LIMITED,
                    "Hostinger rate limit reached", HostingerStatusProvider.rateLimited(e.retryAfter()).message(),
                    Long.toString(e.retryAfterSeconds()));
        } catch (RestClientException e) {
            SourceStatus unreachable = HostingerStatusProvider.unreachable(e);
            status.observed(token, unreachable);
            problemLog.report(LogLevel.WARN, LogSource.HOSTINGER, ACTION, null, unreachable.message(), request(),
                    null, e);
            throw new ApiProblem(HttpStatus.BAD_GATEWAY, HostingerSiteList.UNAVAILABLE, "Hostinger is unavailable",
                    HostingerStatusProvider.noAnswerMessage(e));
        }
    }

    private ApiProblem failed(HostingerClient.Reply reply, String token) {
        int code = reply.status();
        if (reply.tokenRejected()) {
            status.observed(token, HostingerStatusProvider.revoked());
            problemLog.report(LogLevel.ERROR, LogSource.HOSTINGER, ACTION, code, HostingerStatusProvider.REVOKED,
                    request(), reply.body(), null);
            return new ApiProblem(HttpStatus.CONFLICT, HostingerSiteList.TOKEN_REJECTED,
                    "Hostinger rejected the token", HostingerStatusProvider.REVOKED);
        }
        SourceStatus failing = HostingerStatusProvider.failing(code);
        return unavailable(token, code, reply.body(), failing.message(), failing);
    }

    private ApiProblem unavailable(String token, Integer code, String response, String message,
            SourceStatus newStatus) {
        status.observed(token, newStatus);
        problemLog.report(LogLevel.WARN, LogSource.HOSTINGER, ACTION, code, message, request(), response, null);
        return new ApiProblem(HttpStatus.BAD_GATEWAY, HostingerSiteList.UNAVAILABLE, "Hostinger is unavailable",
                message + " The domains could not be loaded; try again later.");
    }

    private List<Domain> read(HostingerClient.Reply reply, String token) {
        try {
            JsonNode body = json.readTree(reply.body());
            if (!body.isArray()) {
                throw new IllegalArgumentException("no list of domains");
            }
            List<Domain> domains = new ArrayList<>();
            for (JsonNode item : body) {
                if (!item.isObject()) {
                    throw new IllegalArgumentException("a domain is not an object");
                }
                domains.add(new Domain(text(item, "domain"), instant(item.path("expires_at")),
                        text(item, "status"), text(item, "type"), instant(item.path("created_at"))));
            }
            return domains;
        } catch (JacksonException | IllegalArgumentException | DateTimeParseException e) {
            throw unavailable(token, reply.status(), reply.body(),
                    "Hostinger's list of domains could not be read (" + e.getMessage() + ").",
                    new SourceStatus(Source.HOSTINGER, ConnectionState.LIMITED,
                            "Hostinger sent an answer Dockline could not read."));
        }
    }

    /**
     * Keeps one row per domain name (compared ignoring case), at the place of the name's first row. Of several
     * rows the kept one is, unchanged: a row with an expiry date before one without; then a registered domain
     * before a free-domain entitlement; then the first. Rows without a name are all kept.
     */
    private static List<Domain> oncePerName(List<Domain> rows) {
        List<Domain> domains = new ArrayList<>();
        Map<String, Integer> placeByName = new HashMap<>();
        for (Domain row : rows) {
            if (row.domain() == null) {
                domains.add(row);
                continue;
            }
            Integer place = placeByName.putIfAbsent(row.domain().toLowerCase(Locale.ROOT), domains.size());
            if (place == null) {
                domains.add(row);
            } else if (saysMore(row, domains.get(place))) {
                domains.set(place, row);
            }
        }
        return domains;
    }

    /** Whether {@code row} is strictly the better one to show than {@code kept}, an earlier row of the same name. */
    private static boolean saysMore(Domain row, Domain kept) {
        if ((row.expiresAt() != null) != (kept.expiresAt() != null)) {
            return row.expiresAt() != null;
        }
        return isRegistered(row) && !isRegistered(kept);
    }

    private static boolean isRegistered(Domain row) {
        return "domain".equals(row.type()) || "domain_transfer".equals(row.type());
    }

    /** Replaces each named domain that has no expiry date with one carrying the date from its renewal information. */
    private void fillMissingExpiry(List<Domain> domains, String token, boolean refresh) {
        for (int i = 0; i < domains.size(); i++) {
            Domain domain = domains.get(i);
            if (domain.domain() == null || domain.expiresAt() != null) {
                continue;
            }
            Instant expiresAt;
            try {
                expiresAt = renewalExpiry(domain.domain(), token, refresh);
            } catch (HostingerRateLimitedException e) {
                // The client already logged the 429; nothing more can be asked for now.
                return;
            } catch (RestClientException e) {
                problemLog.report(LogLevel.WARN, LogSource.HOSTINGER, renewalAction(domain.domain()), null,
                        "The expiry date of " + domain.domain() + " could not be read: "
                                + HostingerStatusProvider.unreachable(e).message(),
                        renewalRequest(domain.domain()), null, e);
                // Hostinger did not answer: do not make the user wait for every other domain too.
                return;
            }
            if (expiresAt != null) {
                domains.set(i, new Domain(domain.domain(), expiresAt, domain.status(), domain.type(),
                        domain.createdAt()));
            }
        }
    }

    /** The domain's expiry date from its renewal information; null (and a Log entry, except for a 404) when there is none. */
    private Instant renewalExpiry(String domain, String token, boolean refresh) {
        HostingerClient.Reply reply = client.getDomainRenewal(domain, token, refresh);
        if (reply.status() == 404) {
            return null;
        }
        String problem;
        if (reply.tokenRejected()) {
            problem = "Hostinger rejected the token for this request (HTTP " + reply.status() + ").";
        } else if (!reply.ok()) {
            problem = HostingerStatusProvider.failing(reply.status()).message();
        } else {
            try {
                Instant expiresAt = instant(json.readTree(reply.body()).path("expires_at"));
                if (expiresAt != null) {
                    return expiresAt;
                }
                problem = "Hostinger's renewal information has no expiry date.";
            } catch (JacksonException | DateTimeParseException e) {
                problem = "Hostinger's renewal information could not be read (" + e.getMessage() + ").";
            }
        }
        problemLog.report(LogLevel.WARN, LogSource.HOSTINGER, renewalAction(domain), reply.status(),
                "The expiry date of " + domain + " could not be read: " + problem, renewalRequest(domain),
                reply.body(), null);
        return null;
    }

    private static String renewalAction(String domain) {
        return "GET " + HostingerClient.domainRenewalPath(domain);
    }

    private String renewalRequest(String domain) {
        return "GET " + client.domainRenewalUrl(domain) + " (with the saved token)";
    }

    private static String text(JsonNode item, String field) {
        String value = item.path(field).asString(null);
        return value == null || value.isBlank() ? null : value;
    }

    private static Instant instant(JsonNode value) {
        return value.isString() ? OffsetDateTime.parse(value.asString()).toInstant() : null;
    }

    private String request() {
        return "GET " + client.domainsUrl() + " (with the saved token)";
    }
}
