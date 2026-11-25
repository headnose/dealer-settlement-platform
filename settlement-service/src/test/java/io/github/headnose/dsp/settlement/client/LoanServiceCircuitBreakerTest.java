package io.github.headnose.dsp.settlement.client;

import com.sun.net.httpserver.HttpServer;
import io.github.headnose.dsp.settlement.MongoTestContainerConfig;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.HttpServerErrorException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the circuit breaker is really wired (pom + aspect + YAML): the client comes from Spring,
 * so calls go through the Resilience4j proxy. LoanServiceClientTest uses `new` and has no breaker.
 * Also proves the Spring-built client propagates the trace context (G1).
 */
// Real tracer (propagation) for the traceparent test; only the OTLP exporter is off, so nothing is sent to
// localhost:4318 (management.tracing.export.enabled=false switched tracing off completely: no header).
// Free here: @DynamicPropertySource already gives this class its own context.
@AutoConfigureTracing
@SpringBootTest(properties = "management.tracing.export.otlp.enabled=false")
@Import(MongoTestContainerConfig.class)
class LoanServiceCircuitBreakerTest {

    /** Status the fake loan-service answers with (no body). */
    private static final AtomicInteger RESPONSE_STATUS = new AtomicInteger(500);
    /** How many HTTP requests actually reached the fake loan-service. */
    private static final AtomicInteger REQUEST_COUNT = new AtomicInteger();
    /** traceparent header of the last request that reached the fake loan-service (null = none sent). */
    private static final AtomicReference<String> LAST_TRACEPARENT = new AtomicReference<>();

    private static final HttpServer FAKE_LOAN_SERVICE = startFakeLoanService();

    @DynamicPropertySource
    static void pointClientAtFakeLoanService(DynamicPropertyRegistry registry) {
        registry.add("clients.loan-service.base-url",
                () -> "http://localhost:" + FAKE_LOAN_SERVICE.getAddress().getPort());
    }

    @Autowired
    private LoanServiceClient client;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    private CircuitBreaker breaker;

    @BeforeEach
    void resetBreakerAndCounter() {
        // the breaker lives as long as the (cached) Spring context, so every test starts it CLOSED
        breaker = circuitBreakerRegistry.circuitBreaker(LoanServiceClient.CIRCUIT_BREAKER);
        breaker.reset();
        REQUEST_COUNT.set(0);
        LAST_TRACEPARENT.set(null);
    }

    @AfterAll
    static void stopFakeLoanService() {
        FAKE_LOAN_SERVICE.stop(0);
    }

    @Test
    void clientFromSpringIsWrappedByTheCircuitBreaker() {
        assertThat(AopUtils.isAopProxy(client)).isTrue();
        // proves the YAML instance config was picked up (default would be 100)
        assertThat(breaker.getCircuitBreakerConfig().getMinimumNumberOfCalls()).isEqualTo(5);
    }

    @Test
    void fiveServerErrorsOpenTheCircuitAndTheSixthCallFailsFastWithoutHttp() {
        RESPONSE_STATUS.set(500);

        for (int i = 1; i <= 5; i++) {
            assertThatThrownBy(() -> client.getLoan("LN-CB-1"))
                    .isInstanceOf(LoanServiceUnavailableException.class)
                    .hasCauseInstanceOf(HttpServerErrorException.class);
        }
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        assertThatThrownBy(() -> client.getLoan("LN-CB-1"))
                .isInstanceOf(LoanServiceUnavailableException.class)
                .hasCauseInstanceOf(CallNotPermittedException.class);
        assertThat(REQUEST_COUNT.get()).isEqualTo(5);   // the 6th call never reached the network
    }

    @Test
    void notFoundIsASuccessfulCallAndNeverOpensTheCircuit() {
        RESPONSE_STATUS.set(404);

        for (int i = 1; i <= 10; i++) {
            assertThat(client.getLoan("LN-CB-404")).isEmpty();
        }

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    void emptyBodyIsRethrownUnchangedNotReportedAsUnavailable() {
        RESPONSE_STATUS.set(200);

        assertThatThrownBy(() -> client.getLoan("LN-CB-EMPTY"))
                .isExactlyInstanceOf(IllegalStateException.class);
        assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    void clientFromSpringSendsW3cTraceContext() {
        RESPONSE_STATUS.set(404);

        client.getLoan("LN-CB-TRACE");

        // G1: only a client built from Boot's RestClient.Builder is instrumented (client span + traceparent).
        // Format: version-traceId(32 hex)-spanId(16 hex)-flags(2 hex). Flags are a bit field: 01 = sampled,
        // 02 = random trace-id (W3C Trace Context Level 2), so OpenTelemetry sends 03.
        assertThat(LAST_TRACEPARENT.get()).matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}");
    }

    private static HttpServer startFakeLoanService() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/api/v1/loans/", exchange -> {
                REQUEST_COUNT.incrementAndGet();
                LAST_TRACEPARENT.set(exchange.getRequestHeaders().getFirst("traceparent"));
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(RESPONSE_STATUS.get(), -1);   // -1 = no body
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}