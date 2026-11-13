package io.github.delucagit.dockline.hostinger;

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

/**
 * The Hostinger status dot: checks the stored token with {@code GET /api/billing/v1/subscriptions}.
 * <ul>
 *   <li>no token: NOT_CONNECTED (Hostinger is not called)
 *   <li>200: CONNECTED
 *   <li>401 / 403: ERROR "revoked or expired, replace it", written to the Log section
 *   <li>429 (rate limit): LIMITED with {@code retryAfterSeconds}, written to the Log section as a warning by
 *       {@link HostingerClient}. While Hostinger's Retry-After wait is in effect (from any feature), the status
 *       is LIMITED with the seconds left and Hostinger is not called; afterwards the token is checked again.
 *   <li>Hostinger unreachable or failing (5xx, timeout): LIMITED; the token may well be fine
 * </ul>
 * The result is cached for {@link #CACHE_TTL} so the sidebar's polling does not call Hostinger every time;
 * saving, removing and {@link #recheck()} drop the cache. Problems are logged once per change, not on every check.
 */
@Component
class HostingerStatusProvider implements SourceStatusProvider {

    static final Duration CACHE_TTL = Duration.ofSeconds(60);
    static final String REVOKED =
            "Hostinger no longer accepts the saved token (revoked or expired). Replace the token in Settings.";

    private final TokenStore tokenStore;
    private final HostingerClient client;
    private final ProblemLog problemLog;
    private final Clock clock;

    /** Guarded by this. */
    private Cached cached;

    private record Cached(String token, SourceStatus status, Instant checkedAt) {}

    HostingerStatusProvider(TokenStore tokenStore, HostingerClient client, ProblemLog problemLog, Clock clock) {
        this.tokenStore = tokenStore;
        this.client = client;
        this.problemLog = problemLog;
        this.clock = clock;
    }

    @Override
    public Source source() {
        return Source.HOSTINGER;
    }

    @Override
    public synchronized SourceStatus status() {
        Optional<String> token;
        try {
            token = tokenStore.read(Source.HOSTINGER);
        } catch (TokenStoreUnavailableException e) {
            return new SourceStatus(Source.HOSTINGER, ConnectionState.ERROR, e.getMessage());
        }
        if (token.isEmpty()) {
            cached = null;
            return SourceStatus.of(Source.HOSTINGER, ConnectionState.NOT_CONNECTED);
        }
        Duration blocked = client.blockedFor();
        if (blocked.isPositive()) {
            // Ask Hostinger again (once) when the wait is over, instead of trusting a result from before it.
            cached = cached != null && cached.token().equals(token.get())
                    ? new Cached(cached.token(), cached.status(), Instant.MIN) : null;
            return rateLimited(blocked);
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

    /** A token that was just checked and saved: remember it as CONNECTED without asking Hostinger again. */
    synchronized SourceStatus connected(String token) {
        SourceStatus status = SourceStatus.of(Source.HOSTINGER, ConnectionState.CONNECTED);
        cached = new Cached(token, status, clock.instant());
        return status;
    }

    /**
     * What a data call (such as the website list) just learned about the token; shown at once instead of
     * waiting for the next check. Not logged here: the caller writes its own Log entry.
     */
    synchronized void observed(String token, SourceStatus status) {
        cached = new Cached(token, status, clock.instant());
    }

    static SourceStatus revoked() {
        return new SourceStatus(Source.HOSTINGER, ConnectionState.ERROR, REVOKED);
    }

    /** Hostinger asked Dockline to wait {@code left} (429 with Retry-After). */
    static SourceStatus rateLimited(Duration left) {
        long seconds = HostingerRateLimitedException.seconds(left);
        return new SourceStatus(Source.HOSTINGER, ConnectionState.LIMITED,
                "Hostinger asked us to slow down; retrying in " + seconds + " s.", Math.toIntExact(seconds));
    }

    static SourceStatus failing(int code) {
        return new SourceStatus(Source.HOSTINGER, ConnectionState.LIMITED,
                "Hostinger answered with an error (HTTP " + code + "). Try again later.");
    }

    static SourceStatus unreachable(RestClientException e) {
        return new SourceStatus(Source.HOSTINGER, ConnectionState.LIMITED, "Could not reach Hostinger: " + e.getMessage());
    }

    /** Forgets the cached result, so the next {@link #status()} asks Hostinger. */
    synchronized void invalidate() {
        cached = null;
    }

    /** Asks Hostinger now, ignoring the cache (for "Check again"). */
    synchronized SourceStatus recheck() {
        // Keep the old result for "log only on change", but make it stale.
        if (cached != null) {
            cached = new Cached(cached.token(), cached.status(), Instant.MIN);
        }
        return status();
    }

    private SourceStatus check(String token, SourceStatus previous) {
        String request = "GET " + client.subscriptionsUrl() + " (with the saved token)";
        HostingerClient.Reply reply;
        try {
            reply = client.getSubscriptions(token, false);
        } catch (HostingerRateLimitedException e) {
            return rateLimited(e.retryAfter()); // already logged by the client
        } catch (RestClientException e) {
            return logOnChange(previous, LogLevel.WARN, null, request, null, e, unreachable(e));
        }
        int code = reply.status();
        if (reply.ok()) {
            return SourceStatus.of(Source.HOSTINGER, ConnectionState.CONNECTED);
        }
        if (reply.tokenRejected()) {
            return logOnChange(previous, LogLevel.ERROR, code, request, reply.body(), null, revoked());
        }
        return logOnChange(previous, LogLevel.WARN, code, request, reply.body(), null, failing(code));
    }

    private SourceStatus logOnChange(SourceStatus previous, LogLevel level, Integer code, String request,
            String response, Throwable error, SourceStatus result) {
        if (!Objects.equals(previous, result)) {
            problemLog.report(level, LogSource.HOSTINGER, "GET " + HostingerClient.SUBSCRIPTIONS_PATH, code,
                    result.message(), request, response, error);
        }
        return result;
    }
}
