package de.roamingthings.configuration;

import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.core.annotation.Introspected;
import jakarta.inject.Singleton;

import java.time.Clock;

@Factory
@Introspected
public class SystemClockFactory {

    @Bean
    @Singleton
    public Clock systemClock() {
        return Clock.systemDefaultZone();
    }
}
