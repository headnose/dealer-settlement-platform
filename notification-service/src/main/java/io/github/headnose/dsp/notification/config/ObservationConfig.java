package io.github.headnose.dsp.notification.config;

import io.micrometer.observation.ObservationPredicate;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * O2: no traces for Prometheus scrapes (same predicate as wire- and settlement-service). BlastMail has no
 * Spring Security, so no SecurityObservationSettings here.
 */
@Configuration(proxyBeanMethods = false)
public class ObservationConfig {

    static final String ACTUATOR_PREFIX = "/actuator";

    /**
     * No observations (spans AND metrics) for /actuator requests. The second check covers observations made
     * while such a request is being served; on Kafka listener threads no request is bound, so they are kept.
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

    static boolean isActuator(HttpServletRequest request) {
        return request.getRequestURI().startsWith(ACTUATOR_PREFIX);
    }
}