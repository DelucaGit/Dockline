package io.github.delucagit.dockline.hostinger;

import java.time.Duration;

/**
 * Hostinger asked Dockline to slow down (HTTP 429) and the wait is longer than the caller may wait, or the
 * retries ran out. Every Hostinger request is held back until {@link #retryAfter()} has passed; the 429 itself
 * is already written to the Log section.
 */
class HostingerRateLimitedException extends RuntimeException {

    private final Duration retryAfter;

    HostingerRateLimitedException(Duration retryAfter) {
        super("Hostinger asked Dockline to slow down; retrying in " + seconds(retryAfter) + " s.");
        this.retryAfter = retryAfter;
    }

    Duration retryAfter() {
        return retryAfter;
    }

    /** The wait in whole seconds, rounded up, at least 1. */
    long retryAfterSeconds() {
        return seconds(retryAfter);
    }

    static long seconds(Duration wait) {
        long seconds = wait.toSeconds() + (wait.toNanosPart() > 0 ? 1 : 0);
        return Math.max(1, seconds);
    }
}
