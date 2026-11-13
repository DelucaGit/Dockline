package io.github.delucagit.dockline.github;

import io.github.delucagit.dockline.ExternalApiProperties;
import io.github.delucagit.dockline.log.LogLevel;
import io.github.delucagit.dockline.log.LogSource;
import io.github.delucagit.dockline.log.ProblemLog;
import io.github.delucagit.dockline.status.ConnectionState;
import io.github.delucagit.dockline.status.SourceStatus;
import io.github.delucagit.dockline.status.SourceStatusProvider;
import io.github.delucagit.dockline.token.Source;
import io.github.delucagit.dockline.token.TokenStore;
import io.github.delucagit.dockline.token.TokenStoreUnavailableException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * The GitHub status dot: checks the stored token with {@code GET /user}.
 * <ul>
 *   <li>no token: NOT_CONNECTED (GitHub is not called); message "client ID is not configured" when that is so
 *   <li>200: CONNECTED, message "Connected as LOGIN"
 *   <li>401: ERROR "revoked or expired, reconnect", written to the Log section
 *   <li>403 / 429 (rate limit or refused): LIMITED, written to the Log section as a warning
 *   <li>GitHub unreachable or failing (5xx): LIMITED; the token may well be fine, so no "Reconnect"
 * </ul>
 * The result is cached for {@link #CACHE_TTL} so the sidebar's polling does not call GitHub every time;
 * connect, disconnect and {@link #recheck()} drop the cache. Problems are logged once per change, not on every check.
 */
@Component
class GithubStatusProvider implements SourceStatusProvider {

    static final Duration CACHE_TTL = Duration.ofSeconds(60);
    static final String REVOKED = "GitHub no longer accepts the saved token (revoked or expired). Reconnect to GitHub.";

    private final ExternalApiProperties.Github github;
    private final TokenStore tokenStore;
    private final GithubClient client;
    private final ProblemLog problemLog;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper();

    /** Guarded by this. */
    private Cached cached;

    private record Cached(String token, SourceStatus status, Instant checkedAt) {}

    GithubStatusProvider(ExternalApiProperties apis, TokenStore tokenStore, GithubClient client, ProblemLog problemLog, Clock clock) {
        this.github = apis.github();
        this.tokenStore = tokenStore;
        this.client = client;
        this.problemLog = problemLog;
        this.clock = clock;
    }

    @Override
    public Source source() {
        return Source.GITHUB;
    }

    @Override
    public synchronized SourceStatus status() {
        Optional<String> token;
        try {
            token = tokenStore.read(Source.GITHUB);
        } catch (TokenStoreUnavailableException e) {
            return new SourceStatus(Source.GITHUB, ConnectionState.ERROR, e.getMessage());
        }
        if (token.isEmpty()) {
            cached = null;
            return new SourceStatus(Source.GITHUB, ConnectionState.NOT_CONNECTED,
                    github.hasClientId() ? null : GithubDeviceFlow.NOT_CONFIGURED);
        }
        Instant now = clock.instant();
        if (cached != null && cached.token().equals(token.get())
                && now.isBefore(cached.checkedAt().plus(CACHE_TTL))) {
            return cached.status();
        }
        SourceStatus previous = cached != null && cached.token().equals(token.get()) ? cached.status() : null;
        SourceStatus checked = check(token.get(), previous);
        cached = new Cached(token.get(), checked, now);
        return checked;
    }

    /** Forgets the cached result, so the next {@link #status()} asks GitHub. */
    synchronized void invalidate() {
        cached = null;
    }

    /**
     * Takes the outcome of another GitHub call (e.g. listing repositories) as the current status, so
     * {@code GET /api/status} shows a revoked token or a rate limit at once. The caller logs the problem itself.
     */
    synchronized void observed(String token, SourceStatus status) {
        cached = new Cached(token, status, clock.instant());
    }

    static SourceStatus revoked() {
        return new SourceStatus(Source.GITHUB, ConnectionState.ERROR, REVOKED);
    }

    static SourceStatus rateLimited() {
        return new SourceStatus(Source.GITHUB, ConnectionState.LIMITED,
                "GitHub's rate limit is reached; Dockline will work again when it resets.");
    }

    static SourceStatus refused(int code) {
        return new SourceStatus(Source.GITHUB, ConnectionState.LIMITED, "GitHub refused access (HTTP " + code + ").");
    }

    static SourceStatus failing(int code) {
        return new SourceStatus(Source.GITHUB, ConnectionState.LIMITED,
                "GitHub answered with an error (HTTP " + code + "). Try again later.");
    }

    static SourceStatus unreachable(RestClientException e) {
        return new SourceStatus(Source.GITHUB, ConnectionState.LIMITED, "Could not reach GitHub: " + e.getMessage());
    }

    /** 429, or 403 with no requests left or a Retry-After (GitHub's primary and secondary rate limits). */
    static boolean isRateLimit(GithubClient.Reply reply) {
        return reply.status() == 429 || (reply.status() == 403
                && ("0".equals(reply.headers().getFirst("x-ratelimit-remaining"))
                        || reply.headers().getFirst("retry-after") != null));
    }

    /** Asks GitHub now, ignoring the cache (for "Reconnect" / "Check again"). */
    synchronized SourceStatus recheck() {
        // Keep the old result for "log only on change", but make it stale.
        if (cached != null) {
            cached = new Cached(cached.token(), cached.status(), Instant.MIN);
        }
        return status();
    }

    private SourceStatus check(String token, SourceStatus previous) {
        String action = "GET /user";
        String request = "GET " + client.userUrl() + " (with the saved token)";
        GithubClient.Reply reply;
        try {
            reply = client.getUser(token);
        } catch (RestClientException e) {
            return logOnChange(previous, LogLevel.WARN, null, request, null, e, unreachable(e));
        }
        int code = reply.status();
        if (reply.ok()) {
            return new SourceStatus(Source.GITHUB, ConnectionState.CONNECTED, login(reply.body())
                    .map(login -> "Connected as " + login).orElse(null));
        }
        if (code == 401) {
            return logOnChange(previous, LogLevel.ERROR, code, request, reply.body(), null, revoked());
        }
        if (code == 403 || code == 429) {
            return logOnChange(previous, LogLevel.WARN, code, request, reply.body(), null,
                    isRateLimit(reply) ? rateLimited() : refused(code));
        }
        return logOnChange(previous, LogLevel.WARN, code, request, reply.body(), null, failing(code));
    }

    private SourceStatus logOnChange(SourceStatus previous, LogLevel level, Integer code, String request,
            String response, Throwable error, SourceStatus result) {
        if (!Objects.equals(previous, result)) {
            problemLog.report(level, LogSource.GITHUB, "GET /user", code, result.message(), request, response, error);
        }
        return result;
    }

    private Optional<String> login(String body) {
        try {
            return Optional.ofNullable(json.readTree(body).path("login").asString(null));
        } catch (JacksonException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
