package io.github.delucagit.dockline;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The clock every time-dependent piece of the backend uses (device-flow polling interval, status cache).
 * Tests replace it with a clock they move forward by hand, so they never have to sleep.
 */
@Configuration
class ClockConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
