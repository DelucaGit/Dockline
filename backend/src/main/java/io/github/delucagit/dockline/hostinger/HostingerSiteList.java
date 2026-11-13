package io.github.delucagit.dockline.hostinger;

import io.github.delucagit.dockline.ApiProblem;
import io.github.delucagit.dockline.hostinger.HostingerApi.Site;
import io.github.delucagit.dockline.hostinger.HostingerApi.Sites;
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
 * Lists the user's Hostinger websites with {@code GET /api/hosting/v1/websites}, asking for page 1, 2, ... until
 * {@code meta.total} websites are read or a page comes back short (up to {@link #MAX_PAGES}). Every page goes
 * through {@link HostingerClient}, which applies the shared request cap and reuses a page fetched in the last
 * 60 seconds (unless {@code refresh}); {@code fetchedAt} is when the oldest page was really fetched. The
 * page addresses are built from the configured base URL, never taken from Hostinger's answer.
 *
 * <p>Failures become an {@link ApiProblem} with a stable {@code code}; each one is written to the Log section
 * and fed into {@link HostingerStatusProvider}, so the Hostinger dot changes at once:
 * <ul>
 *   <li>no token: 409 {@value #NOT_CONNECTED} (Hostinger is not called, nothing logged)
 *   <li>401 / 403: 409 {@value #TOKEN_REJECTED}, status ERROR
 *   <li>Hostinger's 429: {@link HostingerClient} waits out a Retry-After of up to 10 s and retries (logging
 *       each 429); a longer wait, or retries running out, is 429 {@value #RATE_LIMITED} at once with
 *       {@code Retry-After} and {@code retryAfterSeconds} = seconds left, status LIMITED with the same countdown.
 *       While that wait lasts, Hostinger is not called at all.
 *   <li>5xx or anything unexpected, unreachable, timeout or unreadable answer: 502 {@value #UNAVAILABLE},
 *       status LIMITED
 * </ul>
 */
@Component
class HostingerSiteList {

    /** 10 pages of 100: 1,000 websites (a Business plan allows far fewer). */
    static final int MAX_PAGES = 10;
    static final String NOT_CONNECTED = "HOSTINGER_NOT_CONNECTED";
    static final String TOKEN_REJECTED = "HOSTINGER_TOKEN_REJECTED";
    static final String RATE_LIMITED = "HOSTINGER_RATE_LIMITED";
    static final String UNAVAILABLE = "HOSTINGER_UNAVAILABLE";
    static final String ACTION = "GET " + HostingerClient.WEBSITES_PATH;

    private static final Comparator<Site> BY_DOMAIN = Comparator
            .comparing(Site::domain, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
            .thenComparing(Site::username, Comparator.nullsLast(Comparator.naturalOrder()));

    private final TokenStore tokenStore;
    private final HostingerClient client;
    private final HostingerStatusProvider status;
    private final ProblemLog problemLog;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper();

    HostingerSiteList(TokenStore tokenStore, HostingerClient client, HostingerStatusProvider status,
            ProblemLog problemLog, Clock clock) {
        this.tokenStore = tokenStore;
        this.client = client;
        this.status = status;
        this.problemLog = problemLog;
        this.clock = clock;
    }

    Sites list(boolean refresh) {
        String token = tokenStore.read(Source.HOSTINGER).orElseThrow(() -> new ApiProblem(HttpStatus.CONFLICT,
                NOT_CONNECTED, "Hostinger is not connected",
                "Connect to Hostinger in Settings to see your websites."));
        Instant fetchedAt = null;
        List<Site> sites = new ArrayList<>();
        boolean truncated = false;
        for (int page = 1; ; page++) {
            if (page > MAX_PAGES) {
                truncated = true;
                problemLog.report(LogLevel.WARN, LogSource.HOSTINGER, ACTION, null, "Only the first " + MAX_PAGES
                        + " pages of websites were fetched; the list is incomplete.", request(page), null, null);
                break;
            }
            HostingerClient.Reply reply = fetch(page, token, refresh);
            if (!reply.ok()) {
                throw failed(reply, page, token);
            }
            if (fetchedAt == null || reply.fetchedAt().isBefore(fetchedAt)) {
                fetchedAt = reply.fetchedAt();
            }
            if (!readPage(reply, page, token, sites)) {
                break;
            }
        }
        sites.sort(BY_DOMAIN);
        return new Sites(List.copyOf(sites), fetchedAt != null ? fetchedAt : clock.instant(), truncated);
    }

    private HostingerClient.Reply fetch(int page, String token, boolean refresh) {
        try {
            return client.getWebsites(page, token, refresh);
        } catch (HostingerRateLimitedException e) {
            // The client already logged the 429; the status dot reads the same wait from the limiter.
            long seconds = e.retryAfterSeconds();
            throw new ApiProblem(HttpStatus.TOO_MANY_REQUESTS, RATE_LIMITED, "Hostinger rate limit reached",
                    HostingerStatusProvider.rateLimited(e.retryAfter()).message(), Long.toString(seconds));
        } catch (RestClientException e) {
            SourceStatus unreachable = HostingerStatusProvider.unreachable(e);
            status.observed(token, unreachable);
            problemLog.report(LogLevel.WARN, LogSource.HOSTINGER, ACTION, null, unreachable.message(), request(page),
                    null, e);
            throw new ApiProblem(HttpStatus.BAD_GATEWAY, UNAVAILABLE, "Hostinger is unavailable",
                    "Could not reach Hostinger. Check your internet connection and try again.");
        }
    }

    private ApiProblem failed(HostingerClient.Reply reply, int page, String token) {
        int code = reply.status();
        if (reply.tokenRejected()) {
            status.observed(token, HostingerStatusProvider.revoked());
            problemLog.report(LogLevel.ERROR, LogSource.HOSTINGER, ACTION, code, HostingerStatusProvider.REVOKED,
                    request(page), reply.body(), null);
            return new ApiProblem(HttpStatus.CONFLICT, TOKEN_REJECTED, "Hostinger rejected the token",
                    HostingerStatusProvider.REVOKED);
        }
        SourceStatus failing = HostingerStatusProvider.failing(code);
        return unavailable(token, code, page, reply.body(), failing.message(), failing);
    }

    private ApiProblem unavailable(String token, Integer code, int page, String response, String message,
            SourceStatus newStatus) {
        status.observed(token, newStatus);
        problemLog.report(LogLevel.WARN, LogSource.HOSTINGER, ACTION, code, message, request(page), response, null);
        return new ApiProblem(HttpStatus.BAD_GATEWAY, UNAVAILABLE, "Hostinger is unavailable",
                message + " The websites could not be loaded; try again later.");
    }

    /** Adds the page's websites; true when another page should be fetched. */
    private boolean readPage(HostingerClient.Reply reply, int page, String token, List<Site> into) {
        try {
            JsonNode body = json.readTree(reply.body());
            JsonNode items = body.path("data");
            if (!items.isArray()) {
                throw new IllegalArgumentException("no data list");
            }
            for (JsonNode item : items) {
                if (!item.isObject()) {
                    throw new IllegalArgumentException("a website is not an object");
                }
                JsonNode orderId = item.path("order_id");
                into.add(new Site(
                        text(item, "domain"),
                        text(item, "username"),
                        orderId.isIntegralNumber() ? orderId.asLong() : null,
                        text(item, "website_type"),
                        text(item, "root_directory"),
                        item.path("is_enabled").asBoolean(true),
                        instant(item.path("created_at"))));
            }
            JsonNode meta = body.path("meta");
            int perPage = meta.path("per_page").isIntegralNumber() ? meta.path("per_page").asInt()
                    : HostingerClient.WEBSITES_PER_PAGE;
            if (items.isEmpty() || items.size() < perPage) {
                return false;
            }
            return !meta.path("total").isIntegralNumber() || into.size() < meta.path("total").asLong();
        } catch (JacksonException | IllegalArgumentException | DateTimeParseException e) {
            throw unavailable(token, reply.status(), page, reply.body(),
                    "Hostinger's list of websites could not be read (" + e.getMessage() + ").",
                    new SourceStatus(Source.HOSTINGER, ConnectionState.LIMITED,
                            "Hostinger sent an answer Dockline could not read."));
        }
    }

    private static String text(JsonNode item, String field) {
        String value = item.path(field).asString(null);
        return value == null || value.isBlank() ? null : value;
    }

    /** Hostinger sends e.g. {@code 2024-01-15T10:30:00+00:00}; {@code ...Z} works too. */
    private static Instant instant(JsonNode value) {
        return value.isString() ? OffsetDateTime.parse(value.asString()).toInstant() : null;
    }

    private String request(int page) {
        return "GET " + client.websitesUrl(page) + " (with the saved token)";
    }
}
