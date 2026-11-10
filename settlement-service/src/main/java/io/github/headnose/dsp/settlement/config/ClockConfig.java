package io.github.headnose.dsp.settlement.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

@Configuration
public class ClockConfig {

    /** Time zone that defines the business date for value dates. */
    public static final ZoneId BUSINESS_ZONE = ZoneId.of("America/New_York");

    @Bean
    public Clock clock() {
        return Clock.system(BUSINESS_ZONE);
    }
}