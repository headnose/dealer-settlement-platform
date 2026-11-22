package io.github.headnose.dsp.notification.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Business time zone (same as LSC and PWT). Injected so tests can fix the time. */
@Configuration
public class ClockConfig {

    public static final ZoneId BUSINESS_ZONE = ZoneId.of("America/New_York");

    @Bean
    public Clock clock() {
        return Clock.system(BUSINESS_ZONE);
    }
}