package io.github.delucagit.dockline;

import java.time.Duration;

/**
 * Waits for a while; how backend code that must hold back (such as the Hostinger request cap) passes time.
 * The app waits for real ({@link ClockConfig}); tests use one that moves their clock forward instead, so they
 * never sleep.
 */
@FunctionalInterface
public interface Waiter {

    /** Waits about {@code duration}; returns early (with the thread's interrupt flag set) if interrupted. */
    void waitFor(Duration duration);
}
