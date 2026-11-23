package io.github.headnose.dsp.settlement.client;

import io.github.headnose.dsp.settlement.client.dto.LoanDto;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Optional;

@Component
public class LoanServiceClient {

    /** Circuit breaker instance name; its settings live under resilience4j.circuitbreaker.instances. */
    static final String CIRCUIT_BREAKER = "loanService";

    private static final Logger log = LoggerFactory.getLogger(LoanServiceClient.class);

    private final RestClient restClient;

    public LoanServiceClient(@Value("${clients.loan-service.base-url}") String baseUrl,
                             @Value("${clients.loan-service.connect-timeout}") Duration connectTimeout,
                             @Value("${clients.loan-service.read-timeout}") Duration readTimeout) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }

    /**
     * Returns the loan, or empty ONLY if loan-service answers 404.
     * Timeouts / connection errors / 5xx / open circuit -> LoanServiceUnavailableException.
     * An empty 200 body -> IllegalStateException (a broken answer, not "unavailable", not "not found").
     */
    @CircuitBreaker(name = CIRCUIT_BREAKER, fallbackMethod = "loanServiceUnavailable")
    public Optional<LoanDto> getLoan(String loanId) {
        try {
            LoanDto loan = restClient.get()
                    .uri("/api/v1/loans/{loanId}", loanId)
                    .retrieve()
                    .body(LoanDto.class);
            if (loan == null) {
                throw new IllegalStateException("loan-service returned an empty body for loan " + loanId);
            }
            return Optional.of(loan);
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        }
    }

    // Fallbacks: Resilience4j picks the one whose exception type matches best.
    // Any other exception (e.g. IllegalStateException) has no fallback and is rethrown unchanged.

    /** Circuit is open: no HTTP call was made at all (fail fast). */
    private Optional<LoanDto> loanServiceUnavailable(String loanId, CallNotPermittedException e) {
        log.warn("loan-service circuit open, not calling it for loan {}", loanId);
        throw new LoanServiceUnavailableException(loanId, e);
    }

    /** Timeout or connection error. */
    private Optional<LoanDto> loanServiceUnavailable(String loanId, ResourceAccessException e) {
        log.warn("loan-service call failed for loan {}: {}", loanId, e.toString());
        throw new LoanServiceUnavailableException(loanId, e);
    }

    /** loan-service answered 5xx. */
    private Optional<LoanDto> loanServiceUnavailable(String loanId, HttpServerErrorException e) {
        log.warn("loan-service answered {} for loan {}", e.getStatusCode(), loanId);
        throw new LoanServiceUnavailableException(loanId, e);
    }
}