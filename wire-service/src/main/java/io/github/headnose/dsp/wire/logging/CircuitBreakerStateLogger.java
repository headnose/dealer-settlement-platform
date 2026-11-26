package io.github.headnose.dsp.wire.logging;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.event.CircuitBreakerOnStateTransitionEvent;
import io.github.resilience4j.core.registry.EntryAddedEvent;
import io.github.resilience4j.core.registry.EntryRemovedEvent;
import io.github.resilience4j.core.registry.EntryReplacedEvent;
import io.github.resilience4j.core.registry.RegistryEventConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * One log line per circuit breaker state change (CLOSED -> OPEN -> HALF_OPEN -> CLOSED), instead of one
 * WARN per call while the breaker is open. Opening is WARN (a dependency is in trouble), every other
 * transition is INFO. Resilience4j's Spring Boot starter calls this for every breaker it creates
 * (RegistryEventConsumer bean), so each instance in resilience4j.circuitbreaker.instances is covered.
 * The line is logged on the thread that caused the transition, so it carries that request's MDC
 * (traceId, loanId): the request that tripped the breaker can be found.
 */
@Component
public class CircuitBreakerStateLogger implements RegistryEventConsumer<CircuitBreaker> {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreakerStateLogger.class);

    @Override
    public void onEntryAddedEvent(EntryAddedEvent<CircuitBreaker> event) {
        subscribe(event.getAddedEntry());
    }

    @Override
    public void onEntryRemovedEvent(EntryRemovedEvent<CircuitBreaker> event) {
        // nothing to clean up: the removed breaker's event publisher goes away with it
    }

    @Override
    public void onEntryReplacedEvent(EntryReplacedEvent<CircuitBreaker> event) {
        subscribe(event.getNewEntry());
    }

    private void subscribe(CircuitBreaker breaker) {
        breaker.getEventPublisher().onStateTransition(CircuitBreakerStateLogger::logTransition);
    }

    static void logTransition(CircuitBreakerOnStateTransitionEvent event) {
        CircuitBreaker.State to = event.getStateTransition().getToState();
        CircuitBreaker.State from = event.getStateTransition().getFromState();
        if (to == CircuitBreaker.State.OPEN || to == CircuitBreaker.State.FORCED_OPEN) {
            log.warn("Circuit breaker {} {} -> {}: calls are rejected without being made",
                    event.getCircuitBreakerName(), from, to);
        } else {
            log.info("Circuit breaker {} {} -> {}", event.getCircuitBreakerName(), from, to);
        }
    }
}
