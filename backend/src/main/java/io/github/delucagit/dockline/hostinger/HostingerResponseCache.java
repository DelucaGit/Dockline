package io.github.delucagit.dockline.hostinger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Successful Hostinger answers, kept in memory for {@link #TTL} (or longer when the reader asks for it, see
 * {@link #get(String, String, String, Duration)}) so repeated views reuse them instead of asking Hostinger again. Keyed by method, URL and a SHA-256 hash of the token (the token itself is never kept here),
 * so another token never sees these answers; saving or removing the token also clears everything. Holds at most
 * {@link #MAX_ENTRIES} answers (oldest dropped first). Errors are never stored (except a "no Git here" answer, see {@link #putAny}). Thread-safe.
 */
@Component
class HostingerResponseCache {

    static final Duration TTL = Duration.ofSeconds(60);
    static final int MAX_ENTRIES = 500;

    private final Clock clock;

    /** Guarded by this; insertion order, so the eldest entry is the oldest answer. */
    private final Map<String, HostingerClient.Reply> replies = new LinkedHashMap<>(16, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, HostingerClient.Reply> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    HostingerResponseCache(Clock clock) {
        this.clock = clock;
    }

    /** The saved answer for this request, if there is one younger than {@link #TTL}. */
    synchronized Optional<HostingerClient.Reply> get(String method, String url, String token) {
        return get(method, url, token, TTL);
    }

    /** The saved answer for this request, if there is one younger than {@code ttl} (for data that changes rarely). */
    synchronized Optional<HostingerClient.Reply> get(String method, String url, String token, Duration ttl) {
        String key = key(method, url, token);
        HostingerClient.Reply reply = replies.get(key);
        if (reply == null) {
            return Optional.empty();
        }
        if (!clock.instant().isBefore(reply.fetchedAt().plus(ttl))) {
            replies.remove(key);
            return Optional.empty();
        }
        return Optional.of(reply);
    }

    /** Saves a successful answer (replacing an older one for the same request); anything else is ignored. */
    synchronized void put(String method, String url, String token, HostingerClient.Reply reply) {
        if (reply.ok()) {
            putAny(method, url, token, reply);
        }
    }

    /**
     * Saves an answer whatever its status. Only for answers that are a fact rather than a failure, e.g. Hostinger's
     * 404/422 "this website has no Git", so a website without Git is not asked about again on every view.
     */
    synchronized void putAny(String method, String url, String token, HostingerClient.Reply reply) {
        String key = key(method, url, token);
        replies.remove(key);
        replies.put(key, reply);
    }

    synchronized void clear() {
        replies.clear();
    }

    private static String key(String method, String url, String token) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return method + " " + url + " " + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
