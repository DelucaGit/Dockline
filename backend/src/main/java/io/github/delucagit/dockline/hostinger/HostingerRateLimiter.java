package io.github.delucagit.dockline.hostinger;

import io.github.delucagit.dockline.Waiter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

/**
 * Dockline's own cap on Hostinger traffic: at most {@link #MAX_REQUESTS} requests in any rolling
 * {@link #WINDOW}, counted across every feature (Hostinger allows about 90 a minute and can block the IP address
 * after repeated violations). A request over the cap waits until the oldest one in the window is a minute old,
 * instead of failing; after {@link #MAX_WAIT} of waiting it gives up with {@link CapReachedException}.
 *
 * <p>It also keeps Hostinger's own "slow down": after a 429 answer, {@link #blockFor} stops every Hostinger request
 * (from any feature or thread) until the Retry-After time has passed. A request that may wait
 * ({@link #acquire(Duration)}) waits out a short block; a longer one fails at once with
 * {@link HostingerRateLimitedException}, so no HTTP request to Dockline hangs for minutes.
 * Time comes from the {@link Clock} and waiting goes through the {@link Waiter}, so tests drive both. Thread-safe.
 */
@Component
class HostingerRateLimiter {

    static final int MAX_REQUESTS = 50;
    static final Duration WINDOW = Duration.ofSeconds(60);
    static final Duration MAX_WAIT = Duration.ofSeconds(60);

    private final Clock clock;
    private final Waiter waiter;

    /** When the requests in the current window were let through, oldest first. Guarded by this. */
    private final Deque<Instant> sent = new ArrayDeque<>();

    /** No Hostinger request before this (Hostinger's Retry-After after a 429); EPOCH when never blocked. Guarded by this. */
    private Instant blockedUntil = Instant.EPOCH;

    HostingerRateLimiter(Clock clock, Waiter waiter) {
        this.clock = clock;
        this.waiter = waiter;
    }

    /**
     * Takes a slot for one request to Hostinger, waiting for one to free up if all are taken. While Hostinger's
     * Retry-After block is in effect it first waits for the block to end, if that is at most {@code maxBlockedWait};
     * otherwise it throws {@link HostingerRateLimitedException} with the time left.
     */
    void acquire(Duration maxBlockedWait) {
        Duration waited = Duration.ZERO;
        while (true) {
            Duration blocked = blockedFor();
            if (blocked.isPositive()) {
                if (blocked.compareTo(maxBlockedWait) > 0 || Thread.currentThread().isInterrupted()) {
                    throw new HostingerRateLimitedException(blocked);
                }
                waiter.waitFor(blocked);
                continue;
            }
            Duration wait = tryAcquire();
            if (wait.isZero()) {
                return;
            }
            if (waited.compareTo(MAX_WAIT) >= 0 || Thread.currentThread().isInterrupted()) {
                throw new CapReachedException();
            }
            Duration step = wait.compareTo(MAX_WAIT.minus(waited)) < 0 ? wait : MAX_WAIT.minus(waited);
            waiter.waitFor(step);
            waited = waited.plus(step);
        }
    }

    /** Blocks every Hostinger request for {@code wait} from now (never shortens a block already in effect). */
    synchronized void blockFor(Duration wait) {
        Instant until = clock.instant().plus(wait);
        if (until.isAfter(blockedUntil)) {
            blockedUntil = until;
        }
    }

    /** How long Hostinger's Retry-After block still lasts; zero when there is none. */
    synchronized Duration blockedFor() {
        Duration left = Duration.between(clock.instant(), blockedUntil);
        return left.isNegative() ? Duration.ZERO : left;
    }

    /** Zero when a slot was taken; otherwise how long until the oldest request leaves the window. */
    private synchronized Duration tryAcquire() {
        Instant now = clock.instant();
        while (!sent.isEmpty() && !now.isBefore(sent.peekFirst().plus(WINDOW))) {
            sent.removeFirst();
        }
        if (sent.size() < MAX_REQUESTS) {
            sent.addLast(now);
            return Duration.ZERO;
        }
        Duration wait = Duration.between(now, sent.peekFirst().plus(WINDOW));
        return wait.isPositive() ? wait : Duration.ofMillis(1);
    }

    /**
     * The cap stayed full for {@link #MAX_WAIT}. A {@link RestClientException} like "no answer", so every caller
     * already turns it into its "Hostinger is unavailable" error and a yellow (LIMITED) dot.
     */
    static final class CapReachedException extends RestClientException {
        CapReachedException() {
            super("Dockline's own limit of " + MAX_REQUESTS + " Hostinger requests per minute stayed full for "
                    + MAX_WAIT.toSeconds() + " seconds; try again shortly");
        }
    }
}
