package io.github.delucagit.dockline.hostinger;

import io.github.delucagit.dockline.ExternalApiProperties;
import io.github.delucagit.dockline.log.LogLevel;
import io.github.delucagit.dockline.log.LogSource;
import io.github.delucagit.dockline.log.ProblemLog;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriUtils;

/**
 * The raw HTTP calls to the Hostinger public API (bearer token, see developers.hostinger.com).
 * Every Hostinger call goes through {@link #send}, so every feature shares one request cap
 * ({@link HostingerRateLimiter}: 50 a minute, extra requests wait) and data reads share one cache
 * ({@link HostingerResponseCache}: 60 seconds, {@link #GIT_TTL} for a website's Git connection,
 * {@link #BUILDS_TTL} for a website's Node.js builds,
 * {@link #RENEWAL_TTL} for a domain's renewal information). Every answer comes back as a {@link Reply}, whatever its status; only network
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
    /** The Git provider accounts (GitHub, GitLab ...) the customer connected to Hostinger; one unpaged array. */
    static final String GIT_INSTALLATIONS_PATH = "/api/hosting/v1/git/installations";
    /**
     * How long an answer about a website's Git connection is reused: it changes only when the user changes the
     * Git settings in hPanel, and asking costs up to two requests per website.
     */
    static final Duration GIT_TTL = Duration.ofMinutes(5);
    /**
     * How long an answer about a domain's renewal (its expiry date) is reused: the date changes only when the
     * domain is renewed, and asking costs one request per domain.
     */
    static final Duration RENEWAL_TTL = Duration.ofMinutes(5);
    /**
     * How long an answer about a website's Node.js builds is reused: short, so the Sites view's automatic refresh
     * (every 30 seconds) shows a build that started or finished.
     */
    static final Duration BUILDS_TTL = Duration.ofSeconds(20);
    /** The largest page Hostinger allows for a website's builds. */
    static final int BUILDS_PER_PAGE = 100;
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
    private final AtomicLong sent = new AtomicLong();

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

        /** 404 or 422 on a Git path: Hostinger saying the website has no Git at all. */
        boolean noGit() {
            return status == 404 || status == 422;
        }

        /** 404 or 422 on the builds path: Hostinger saying the website has no Node.js builds. */
        boolean noBuilds() {
            return status == 404 || status == 422;
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

    /** The renewal information of one domain (expiry date, grace and redemption periods); the name is URL-encoded. */
    static String domainRenewalPath(String domain) {
        return DOMAINS_PATH + "/" + UriUtils.encodePathSegment(domain, StandardCharsets.UTF_8) + "/renewal";
    }

    /**
     * The renewal information of one domain, read for its {@code expires_at} when the portfolio has none
     * (a free domain). A successful answer younger than {@link #RENEWAL_TTL} is reused; {@code refresh} skips it
     * and saves the new answer. A 404 ("no renewal information for this domain") is kept the same way.
     */
    Reply getDomainRenewal(String domain, String token, boolean refresh) {
        String path = domainRenewalPath(domain);
        Reply reply = cachedGet(hostinger.apiUrl() + path, path, token, refresh, RENEWAL_TTL);
        if (reply.status() == 404) {
            cache.putAny("GET", hostinger.apiUrl() + path, token, reply);
        }
        return reply;
    }

    String domainRenewalUrl(String domain) {
        return hostinger.apiUrl() + domainRenewalPath(domain);
    }

    /** The Git repositories linked to directories of the website (hPanel's Git section); a JSON array. */
    static String gitRepositoriesPath(String username, String domain) {
        return "/api/hosting/v1/accounts/" + username + "/websites/" + domain + "/git/repositories";
    }

    /** The website's Git auto-deployment (through Hostinger's GitHub/GitLab app); every field null when not set up. */
    static String gitAutoDeploymentPath(String username, String domain) {
        return "/api/hosting/v1/accounts/" + username + "/websites/" + domain + "/git/auto-deployments/settings";
    }

    /**
     * A read of one of the Git paths above ({@link #gitRepositoriesPath}, {@link #gitAutoDeploymentPath},
     * {@link #GIT_INSTALLATIONS_PATH}). A successful answer younger than {@link #GIT_TTL} is reused;
     * {@code refresh} skips it and saves the new answer. The caller makes sure the path holds no characters
     * that need encoding.
     */
    Reply getGit(String path, String token, boolean refresh) {
        Reply reply = cachedGet(hostinger.apiUrl() + path, path, token, refresh, GIT_TTL);
        if (reply.noGit()) {
            // "No Git on this website" is an answer, not a failure: keep it like a successful one.
            cache.putAny("GET", hostinger.apiUrl() + path, token, reply);
        }
        return reply;
    }

    /** The saved answer {@link #getGit} would reuse, if there is one; Hostinger is never asked. */
    Optional<Reply> savedGit(String path, String token) {
        return cache.get("GET", hostinger.apiUrl() + path, token, GIT_TTL);
    }

    /** The builds (deploys) of a Node.js website, without the paging query. */
    static String nodejsBuildsPath(String username, String domain) {
        return "/api/hosting/v1/accounts/" + username + "/websites/" + domain + "/nodejs/builds";
    }

    String nodejsBuildsUrl(String username, String domain, int page) {
        return hostinger.apiUrl() + nodejsBuildsPath(username, domain) + "?page=" + page + "&per_page="
                + BUILDS_PER_PAGE;
    }

    /**
     * One page (1-based) of a Node.js website's builds, {@value #BUILDS_PER_PAGE} per page. A successful answer
     * younger than {@link #BUILDS_TTL} is reused; {@code refresh} skips it and saves the new answer. A 404 or 422
     * ("no builds on this website") is kept the same way. The caller makes sure the username and domain hold no
     * characters that need encoding.
     */
    Reply getNodejsBuilds(String username, String domain, int page, String token, boolean refresh) {
        String url = nodejsBuildsUrl(username, domain, page);
        Reply reply = cachedGet(url, nodejsBuildsPath(username, domain), token, refresh, BUILDS_TTL);
        if (reply.noBuilds()) {
            cache.putAny("GET", url, token, reply);
        }
        return reply;
    }

    /** The saved answer {@link #getNodejsBuilds} would reuse, if there is one; Hostinger is never asked. */
    Optional<Reply> savedNodejsBuilds(String username, String domain, int page, String token) {
        return cache.get("GET", nodejsBuildsUrl(username, domain, page), token, BUILDS_TTL);
    }

    /** How many requests have left for Hostinger since the app started (answers reused from the cache do not count). */
    long sentCount() {
        return sent.get();
    }

    /** Forgets every saved answer; called when the token is saved or removed. */
    void clearCache() {
        cache.clear();
    }

    String websitesUrl(int page) {
        return hostinger.apiUrl() + WEBSITES_PATH + "?page=" + page + "&per_page=" + WEBSITES_PER_PAGE;
    }

    private Reply cachedGet(String url, String path, String token, boolean refresh) {
        return cachedGet(url, path, token, refresh, HostingerResponseCache.TTL);
    }

    private Reply cachedGet(String url, String path, String token, boolean refresh, Duration ttl) {
        if (!refresh) {
            Optional<Reply> saved = cache.get("GET", url, token, ttl);
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
        sent.incrementAndGet();
        // A ready-made URI: the address is sent as written, so an encoded domain name is not encoded twice.
        return http.get().uri(URI.create(url))
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
