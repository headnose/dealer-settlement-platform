package io.github.headnose.dsp.settlement.config;

import io.micrometer.observation.ObservationPredicate;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.security.config.observation.SecurityObservationSettings;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * O2: keep traces about the API, not about scraping or Spring Security's plumbing.
 */
@Configuration(proxyBeanMethods = false)
public class ObservationConfig {

    static final String ACTUATOR_PREFIX = "/actuator";

    /**
     * No observations (spans AND metrics) for /actuator requests: Prometheus scrapes every 5 s, which made
     * almost every trace a scrape. Two checks, because skipping only the server observation would turn the
     * observations made inside that request (e.g. Spring Security's authorization) into traces of their own.
     * RequestContextHolder is filled by Boot's request context filter before Spring Security runs; on
     * threads that serve no request (Kafka, schedulers) it is empty and the observation is kept.
     */
    @Bean
    ObservationPredicate noActuatorObservations() {
        return (name, context) -> {
            if (context instanceof ServerRequestObservationContext serverContext) {
                return !isActuator(serverContext.getCarrier());
            }
            RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
            if (attributes instanceof ServletRequestAttributes servletAttributes) {
                return !isActuator(servletAttributes.getRequest());
            }
            return true;
        };
    }

    /**
     * Spring Security's defaults: authentications and authorizations are observed (they show who was let in
     * and why, e.g. the expression behind a 403), the per-request filter chain spans are not. Without this
     * bean Spring Security observes everything (4-7 extra spans per request).
     */
    @Bean
    SecurityObservationSettings securityObservationSettings() {
        return SecurityObservationSettings.withDefaults().build();
    }

    static boolean isActuator(HttpServletRequest request) {
        return request.getRequestURI().startsWith(ACTUATOR_PREFIX);
    }
}