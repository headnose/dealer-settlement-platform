package io.github.headnose.dsp.wire.client;

import io.github.headnose.dsp.wire.client.dto.LscProblem;
import io.github.headnose.dsp.wire.client.dto.SettlementDetailDto;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.netty.channel.ChannelOption;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Calls settlement-service (blueprint: LSC) for the settlement detail of a loan.
 * Non-blocking (WebClient on Reactor Netty), so the caller can do its blocking JDBC reads while the
 * HTTP call is in flight (P1). Built from Boot's autoconfigured WebClient.Builder, so every call is
 * observed (metric http.client.requests). Timeouts are still our own (2s/5s).
 * <p>
 * Circuit breaker "settlementService" (B1-B5): when settlement-service keeps timing out, the breaker opens
 * and calls fail at once instead of holding a Tomcat thread for the full read timeout. The load test
 * showed why: at 50 req/s a 5s timeout needs 250 threads, more than Tomcat's 200, so requests queued for
 * up to 20s.
 */
@Component
public class SettlementServiceClient {

    // Must match the ProblemDetail titles in settlement-service's GlobalExceptionHandler (decision D2).
    static final String LOAN_NOT_IMPORTED_TITLE = "Loan not imported";
    static final String SETTLEMENT_NOT_CALCULATED_TITLE = "Settlement not calculated";

    /** Instance name in resilience4j.circuitbreaker.instances (application.yaml). */
    static final String CIRCUIT_BREAKER = "settlementService";

    private final WebClient webClient;
    private final CircuitBreaker circuitBreaker;

    /** Boot's builder is prototype-scoped: this client gets its own copy, so the connector affects no one else. */
    public SettlementServiceClient(
            WebClient.Builder builder,
            CircuitBreakerRegistry circuitBreakerRegistry,
            @Value("${clients.settlement-service.base-url}") String baseUrl,
            @Value("${clients.settlement-service.connect-timeout}") Duration connectTimeout,
            @Value("${clients.settlement-service.read-timeout}") Duration readTimeout) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Math.toIntExact(connectTimeout.toMillis()))
                // Max gap between network reads of the response (same meaning as the old read timeout).
                .responseTimeout(readTimeout);
        this.webClient = builder
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .baseUrl(baseUrl)
                .build();
        // Returns the instance configured in YAML (created by Resilience4j's Boot auto-configuration).
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(CIRCUIT_BREAKER);
    }

    /**
     * Starts the call and returns at once. toFuture() subscribes immediately: a Mono alone is lazy and
     * would send nothing. The future fails with:
     *
     * @throws LoanNotImportedException             settlement-service: 404 "Loan not imported"
     * @throws SettlementNotCalculatedException     settlement-service: 404 "Settlement not calculated"
     * @throws SettlementServiceUnavailableException 5xx, timeout, connection refused, empty body, another 404,
     *                                              or the circuit breaker is open (no call is made)
     */
    public CompletableFuture<SettlementDetailDto> getSettlementDetailAsync(String loanId) {
        return webClient.get()
                .uri("/api/v1/loans/{loanId}/settlements", loanId)
                .retrieve()
                .bodyToMono(SettlementDetailDto.class)
                .switchIfEmpty(Mono.error(() -> new SettlementServiceUnavailableException(
                        "settlement-service returned an empty body for loan " + loanId, null)))
                .onErrorMap(WebClientResponseException.NotFound.class, e -> translateNotFound(loanId, e))
                .onErrorMap(SettlementServiceClient::isUnavailable, e -> new SettlementServiceUnavailableException(
                        "settlement-service did not answer for loan " + loanId, e))
                // After the error mapping, so the breaker sees our exception types (B2: only
                // SettlementServiceUnavailableException is recorded as a failure). transformDeferred: the
                // permit is checked per subscription; when OPEN, the HTTP call above is never subscribed.
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                // B3: open breaker = settlement-service unavailable -> the existing 502 (API unchanged, D3).
                .onErrorMap(CallNotPermittedException.class, e -> new SettlementServiceUnavailableException(
                        "settlement-service circuit breaker is open; not called for loan " + loanId, e))
                .toFuture();
    }

    /** Same cases as the old RestClient version: 5xx, connect/timeout/IO errors. Other 4xx stay as they are. */
    private static boolean isUnavailable(Throwable e) {
        return e instanceof WebClientRequestException
                || e instanceof IOException
                || e instanceof io.netty.handler.timeout.TimeoutException
                || (e instanceof WebClientResponseException response && response.getStatusCode().is5xxServerError());
    }

    private RuntimeException translateNotFound(String loanId, WebClientResponseException e) {
        String title = problemTitle(e);
        if (LOAN_NOT_IMPORTED_TITLE.equals(title)) {
            return new LoanNotImportedException(loanId);
        }
        if (SETTLEMENT_NOT_CALCULATED_TITLE.equals(title)) {
            return new SettlementNotCalculatedException(loanId);
        }
        return new SettlementServiceUnavailableException(
                "Unexpected 404 from settlement-service for loan " + loanId, e);
    }

    /** The ProblemDetail title, or null if the body is missing or not a ProblemDetail. */
    private static String problemTitle(WebClientResponseException e) {
        try {
            LscProblem problem = e.getResponseBodyAs(LscProblem.class);
            return problem == null ? null : problem.title();
        } catch (RuntimeException unreadable) {
            return null;
        }
    }
}