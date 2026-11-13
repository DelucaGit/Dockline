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
import java.util.List;
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
        List<Domain> domains = read(reply, token);
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
                    "Could not reach Hostinger. Check your internet connection and try again.");
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
