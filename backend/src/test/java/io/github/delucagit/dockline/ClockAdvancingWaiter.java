package io.github.delucagit.dockline;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** A {@link Waiter} for tests: instead of sleeping it moves the {@link MutableClock} forward, and remembers each wait. */
public class ClockAdvancingWaiter implements Waiter {

    private final MutableClock clock;
    private final List<Duration> waits = new CopyOnWriteArrayList<>();

    public ClockAdvancingWaiter(MutableClock clock) {
        this.clock = clock;
    }

    @Override
    public void waitFor(Duration duration) {
        waits.add(duration);
        clock.advance(duration);
    }

    /** Every wait so far, oldest first. */
    public List<Duration> waits() {
        return List.copyOf(waits);
    }

    public void forget() {
        waits.clear();
    }
}
