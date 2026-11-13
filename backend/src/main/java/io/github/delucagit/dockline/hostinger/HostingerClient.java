package io.github.delucagit.dockline.hostinger;

import io.github.delucagit.dockline.ExternalApiProperties;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
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
    /** The largest page Hostinger allows for the website list. */
    static final int WEBSITES_PER_PAGE = 100;

    private final ExternalApiProperties.Hostinger hostinger;
    private final RestClient http;
    private final HostingerRateLimiter limiter;
    private final HostingerResponseCache cache;
    private final Clock clock;

    HostingerClient(ExternalApiProperties apis, HostingerRateLimiter limiter, HostingerResponseCache cache,
            Clock clock) {
        this.hostinger = apis.hostinger();
        this.limiter = limiter;
        this.cache = cache;
        this.clock = clock;
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
     */
    Reply getSubscriptions(String token) {
        return send(subscriptionsUrl(), token);
    }

    /**
     * One page (1-based) of the website list, {@value #WEBSITES_PER_PAGE} per page. The address is always built
     * from the configured base URL, never taken from Hostinger's answer, so the token cannot be sent elsewhere.
     * A successful answer younger than 60 seconds is reused; {@code refresh} skips it and saves the new answer.
     */
    Reply getWebsites(int page, String token, boolean refresh) {
        return cachedGet(websitesUrl(page), token, refresh);
    }

    /** Forgets every saved answer; called when the token is saved or removed. */
    void clearCache() {
        cache.clear();
    }

    String websitesUrl(int page) {
        return hostinger.apiUrl() + WEBSITES_PATH + "?page=" + page + "&per_page=" + WEBSITES_PER_PAGE;
    }

    private Reply cachedGet(String url, String token, boolean refresh) {
        if (!refresh) {
            Optional<Reply> saved = cache.get("GET", url, token);
            if (saved.isPresent()) {
                return saved.get();
            }
        }
        Reply reply = send(url, token);
        cache.put("GET", url, token, reply);
        return reply;
    }

    /**
     * The one place a request leaves for Hostinger: takes a slot from the shared cap (waiting if needed), then
     * sends. Handling Hostinger's own 429 / Retry-After belongs here too, so every feature gets it.
     */
    private Reply send(String url, String token) {
        limiter.acquire();
        return http.get().uri(url)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .accept(MediaType.APPLICATION_JSON)
                .exchange((request, response) -> new Reply(response.getStatusCode().value(), response.getHeaders(),
                        StreamUtils.copyToString(response.getBody(), StandardCharsets.UTF_8), clock.instant()));
    }
}
