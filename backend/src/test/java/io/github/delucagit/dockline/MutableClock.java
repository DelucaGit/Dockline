package io.github.delucagit.dockline;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A clock tests move forward by hand, so time-dependent code can be tested without sleeping. */
public class MutableClock extends Clock {

    private volatile Instant now = Instant.parse("2026-01-01T12:00:00Z");

    public void advance(Duration duration) {
        now = now.plus(duration);
    }

    public void advanceSeconds(long seconds) {
        advance(Duration.ofSeconds(seconds));
    }

    @Override
    public Instant instant() {
        return now;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }
}
