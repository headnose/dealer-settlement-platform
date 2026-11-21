package io.github.headnose.dsp.wire.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/** One Clock for the service, in the business time zone, so tests can replace it with a fixed one. */
@Configuration
public class ClockConfig {

    public static final ZoneId BUSINESS_ZONE = ZoneId.of("America/New_York");

    @Bean
    public Clock clock() {
        return Clock.system(BUSINESS_ZONE);
    }
}