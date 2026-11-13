package io.github.delucagit.dockline.hostinger;

import io.github.delucagit.dockline.ExternalApiProperties;
import io.github.delucagit.dockline.log.LogLevel;
import io.github.delucagit.dockline.log.LogSource;
import io.github.delucagit.dockline.log.ProblemLog;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * The raw HTTP calls to the Hostinger public API (bearer token, see developers.hostinger.com).
 * Every Hostinger call goes through {@link #send}, so every feature shares one request cap
 * ({@link HostingerRateLimiter}: 50 a minute, extra requests wait) and data reads share one 60-second cache
 * ({@link HostingerResponseCache}). Every answer comes back as a {@link Reply}, whatever its status; only network
 * failures (no answer at all, including timeouts, or the cap staying full) throw {@link RestClientException}.
 *
 * <p>Hostinger's own "too many requests" (429) is handled here for every feature: the wait from its
 * {@code Retry-After} header (seconds or an HTTP date; missing or unreadable means
 * {@code dockline.hostinger.default-retry-after}, 30 s; kept between {@link #MIN_RETRY_AFTER} and
 * {@link #MAX_RETRY_AFTER}) blocks every Hostinger request ({@link HostingerRateLimiter#blockFor}), the 429 is
 * written to the Log section as a warning, and the request is sent again after the wait, at most
 * {@link #MAX_ATTEMPTS} times in all. A caller that may wait does so only for waits up to
 * {@link #MAX_INLINE_WAIT}; a longer wait, or a 429 on the last attempt, throws
 * {@link HostingerRateLimitedException} with the time left. A 429 is never returned as a {@link Reply}.
 */
@Component
class HostingerClient {

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(10);
    /**
     * Used to check a token: a cheap read-only list that every account has (an account may have no domains,
     * websites or VPS, but always has a billing context). Answers 200 with a JSON array, 401 for a bad token.
     */
    static final String SUBSCRIPTIONS_PATH = "/api/billing/v1/subscriptions";
    /** The websites in the user's hosting accounts (and ones shared with them), paged with page and per_page. */
    static final String WEBSITES_PATH = "/api/hosting/v1/websites";
    /** The domain portfolio: all domains of the user, one unpaged array. */
    static final String DOMAINS_PATH = "/api/domains/v1/portfolio";
    /** The largest page Hostinger allows for the website list. */
    static final int WEBSITES_PER_PAGE = 100;
    /** The first request plus up to two retries after Hostinger's 429. */
    static final int MAX_ATTEMPTS = 3;
    /** The longest Retry-After wait done inside an HTTP request to Dockline; longer ones answer 429 at once. */
    static final Duration MAX_INLINE_WAIT = Duration.ofSeconds(10);
    static final Duration MIN_RETRY_AFTER = Duration.ofSeconds(1);
    static final Duration MAX_RETRY_AFTER = Duration.ofSeconds(120);

    private final ExternalApiProperties.Hostinger hostinger;
    private final RestClient http;
    private final HostingerRateLimiter limiter;
    private final HostingerResponseCache cache;
    private final Clock clock;
    private final ProblemLog problemLog;
    private final Duration defaultRetryAfter;

    HostingerClient(ExternalApiProperties apis, HostingerRateLimiter limiter, HostingerResponseCache cache,
            Clock clock, ProblemLog problemLog,
            @Value("${dockline.hostinger.default-retry-after:30s}") Duration defaultRetryAfter) {
        this.hostinger = apis.hostinger();
        this.limiter = limiter;
        this.cache = cache;
        this.clock = clock;
        this.problemLog = problemLog;
        this.defaultRetryAfter = defaultRetryAfter;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
        factory.setReadTimeout(READ_TIMEOUT);
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    /** An HTTP answer from Hostinger, and when it arrived (kept when the answer is reused from the cache). */
    record Reply(int status, HttpHeaders headers, String body, Instant fetchedAt) {
        boolean ok() {
            return status >= 200 && status < 300;
        }

        /** 401 (what Hostinger documents for a missing, expired or revoked token) or 403. */
        boolean tokenRejected() {
            return status == 401 || status == 403;
        }
    }

    String subscriptionsUrl() {
        return hostinger.apiUrl() + SUBSCRIPTIONS_PATH;
    }

    /**
     * Lists the account's subscriptions; used to check that a token works. Never cached: checking a new token
     * and "Check again" must really ask Hostinger (the status dot keeps its own 60-second result).
     * {@code mayWait} false (the status dot) never waits out a 429: it throws {@link HostingerRateLimitedException}
     * at once.
     */
    Reply getSubscriptions(String token, boolean mayWait) {
        return send(subscriptionsUrl(), SUBSCRIPTIONS_PATH, token, mayWait);
    }

    /** How long Hostinger's Retry-After block still lasts; zero when Hostinger may be asked. */
    Duration blockedFor() {
        return limiter.blockedFor();
    }

    /**
     * One page (1-based) of the website list, {@value #WEBSITES_PER_PAGE} per page. The address is always built
     * from the configured base URL, never taken from Hostinger's answer, so the token cannot be sent elsewhere.
     * A successful answer younger than 60 seconds is reused; {@code refresh} skips it and saves the new answer.
     */
    Reply getWebsites(int page, String token, boolean refresh) {
        return cachedGet(websitesUrl(page), WEBSITES_PATH, token, refresh);
    }

    /**
     * The user's domain portfolio (one JSON array, no paging), cached for 60 seconds like the websites;
     * {@code refresh} skips the saved answer.
     */
    Reply getDomains(String token, boolean refresh) {
        return cachedGet(domainsUrl(), DOMAINS_PATH, token, refresh);
    }

    String domainsUrl() {
        return hostinger.apiUrl() + DOMAINS_PATH;
    }

    /** Forgets every saved answer; called when the token is saved or removed. */
    void clearCache() {
        cache.clear();
    }

    String websitesUrl(int page) {
        return hostinger.apiUrl() + WEBSITES_PATH + "?page=" + page + "&per_page=" + WEBSITES_PER_PAGE;
    }

    private Reply cachedGet(String url, String path, String token, boolean refresh) {
        if (!refresh) {
            Optional<Reply> saved = cache.get("GET", url, token);
            if (saved.isPresent()) {
                return saved.get();
            }
        }
        Reply reply = send(url, path, token, true);
        cache.put("GET", url, token, reply);
        return reply;
    }

    /**
     * The one place a request leaves for Hostinger: waits out Hostinger's Retry-After block, takes a slot from
     * the shared cap (waiting if needed), then sends; on a 429 it blocks all Hostinger requests, logs it and
     * tries again (see the class comment).
     */
    private Reply send(String url, String path, String token, boolean mayWait) {
        Duration maxWait = mayWait ? MAX_INLINE_WAIT : Duration.ZERO;
        for (int attempt = 1; ; attempt++) {
            limiter.acquire(maxWait);
            Reply reply = exchange(url, token);
            if (reply.status() != 429) {
                return reply;
            }
            Duration wait = retryAfter(reply.headers());
            limiter.blockFor(wait);
            problemLog.report(LogLevel.WARN, LogSource.HOSTINGER, "GET " + path, 429,
                    "Hostinger asked Dockline to slow down (too many requests); no Hostinger request is sent for "
                            + HostingerRateLimitedException.seconds(wait) + " s.",
                    "GET " + url + " (with the Hostinger token)", reply.body(), null);
            if (attempt >= MAX_ATTEMPTS || wait.compareTo(maxWait) > 0) {
                Duration left = limiter.blockedFor();
                throw new HostingerRateLimitedException(left.isPositive() ? left : wait);
            }
        }
    }

    private Reply exchange(String url, String token) {
        return http.get().uri(url)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .accept(MediaType.APPLICATION_JSON)
                .exchange((request, response) -> new Reply(response.getStatusCode().value(), response.getHeaders(),
                        StreamUtils.copyToString(response.getBody(), StandardCharsets.UTF_8), clock.instant()));
    }

    /** Hostinger's Retry-After (seconds or an HTTP date), kept within bounds; the default when missing or unreadable. */
    Duration retryAfter(HttpHeaders headers) {
        String value = headers.getFirst(HttpHeaders.RETRY_AFTER);
        Duration wait = defaultRetryAfter;
        if (value != null && value.strip().matches("\\d{1,9}")) {
            wait = Duration.ofSeconds(Long.parseLong(value.strip()));
        } else if (value != null) {
            try {
                Instant at = ZonedDateTime.parse(value.strip(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                wait = Duration.between(clock.instant(), at);
            } catch (DateTimeParseException e) {
                // unreadable: keep the default
            }
        }
        if (wait.compareTo(MIN_RETRY_AFTER) < 0) {
            return MIN_RETRY_AFTER;
        }
        return wait.compareTo(MAX_RETRY_AFTER) > 0 ? MAX_RETRY_AFTER : wait;
    }
}
