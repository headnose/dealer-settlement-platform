package io.github.headnose.dsp.wire.config;

import io.github.headnose.dsp.wire.logging.MdcKeys;
import io.micrometer.context.ContextRegistry;
import io.micrometer.context.integration.Slf4jThreadLocalAccessor;
import org.springframework.context.annotation.Configuration;

/**
 * O3: our own MDC entries follow the work onto other threads, like traceId already does. Everything built on
 * Micrometer's global ContextRegistry reads this accessor: ContextPropagatingTaskDecorator, and Reactor's
 * automatic context propagation (spring.reactor.context-propagation, LG4) for the WebClient call to
 * settlement-service. Only OUR keys: traceId/spanId belong to the tracer, and copying the whole MDC would
 * fight it.
 */
@Configuration(proxyBeanMethods = false)
public class MdcPropagationConfig {

    public MdcPropagationConfig() {
        register(ContextRegistry.getInstance());
    }

    /**
     * The registry is one per JVM; every Spring context (several in a test run) calls this, so register once.
     */
    public static void register(ContextRegistry registry) {
        boolean alreadyRegistered = registry.getThreadLocalAccessors().stream()
                .anyMatch(accessor -> Slf4jThreadLocalAccessor.KEY.equals(accessor.key()));
        if (!alreadyRegistered) {
            registry.registerThreadLocalAccessor(
                    new Slf4jThreadLocalAccessor(MdcKeys.USER_ID, MdcKeys.LOAN_ID, MdcKeys.SUBMISSION_ID));
        }
    }
}