package io.github.headnose.dsp.wire.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

class CircuitBreakerStateLoggerTest {

    private final Logger logger = (Logger) LoggerFactory.getLogger(CircuitBreakerStateLogger.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void attachAppender() {
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
    }

    @Test
    void logsOneLinePerStateChange() {
        CircuitBreakerRegistry registry =
                CircuitBreakerRegistry.of(CircuitBreakerConfig.ofDefaults(), new CircuitBreakerStateLogger());
        CircuitBreaker breaker = registry.circuitBreaker("dependency");

        breaker.transitionToOpenState();
        breaker.transitionToHalfOpenState();
        breaker.transitionToClosedState();

        assertThat(appender.list)
                .extracting(ILoggingEvent::getLevel, ILoggingEvent::getFormattedMessage)
                .containsExactly(
                        tuple(Level.WARN,
                                "Circuit breaker dependency CLOSED -> OPEN: calls are rejected without being made"),
                        tuple(Level.INFO, "Circuit breaker dependency OPEN -> HALF_OPEN"),
                        tuple(Level.INFO, "Circuit breaker dependency HALF_OPEN -> CLOSED"));
    }

    @Test
    void rejectedCallsWhileOpenLogNothing() {
        CircuitBreakerRegistry registry =
                CircuitBreakerRegistry.of(CircuitBreakerConfig.ofDefaults(), new CircuitBreakerStateLogger());
        CircuitBreaker breaker = registry.circuitBreaker("dependency");
        breaker.transitionToOpenState();
        appender.list.clear();

        for (int i = 0; i < 5; i++) {
            assertThat(breaker.tryAcquirePermission()).isFalse();
        }

        assertThat(appender.list).isEmpty();
    }
}
