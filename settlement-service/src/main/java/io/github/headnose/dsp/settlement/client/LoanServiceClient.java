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

    /** Name of the three fallback overloads below (the test checks they are all public). */
    static final String FALLBACK_METHOD = "loanServiceUnavailable";

    private static final Logger log = LoggerFactory.getLogger(LoanServiceClient.class);

    private final RestClient restClient;

    /**
     * G1: built from Boot's RestClient.Builder (not the static RestClient.builder()), so every call is
     * observed: http.client.requests metric, a client span, and the W3C traceparent header for loan-service.
     * Boot's builder bean is prototype-scoped: each injection gets its own copy, so baseUrl and request
     * factory set here do not leak into other clients. Our request factory replaces Boot's default on
     * purpose: it keeps the L1 timeouts exactly as before (read timeout = max gap between bytes).
     */
    public LoanServiceClient(RestClient.Builder restClientBuilder,
                             @Value("${clients.loan-service.base-url}") String baseUrl,
                             @Value("${clients.loan-service.connect-timeout}") Duration connectTimeout,
                             @Value("${clients.loan-service.read-timeout}") Duration readTimeout) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);
        this.restClient = restClientBuilder
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }

    /**
     * Returns the loan, or empty ONLY if loan-service answers 404.
     * Timeouts / connection errors / 5xx / open circuit -> LoanServiceUnavailableException.
     * An empty 200 body -> IllegalStateException (a broken answer, not "unavailable", not "not found").
     */
    @CircuitBreaker(name = CIRCUIT_BREAKER, fallbackMethod = FALLBACK_METHOD)
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
    //
    // PUBLIC ON PURPOSE, do not make them private: Resilience4j 2.4.0 (FallbackMethod.invoke) caches ONE
    // java.lang.reflect.Method per fallback and, for a non-public method, sets it accessible before each
    // call and back to inaccessible after it. Two threads in a fallback at once can race: one resets the
    // flag while the other is about to invoke -> IllegalAccessException -> the import became FAILED instead
    // of LOAN_SERVICE_UNAVAILABLE (seen once in ~1,350 fallback calls in the import load test, I4).
    // Public methods need no accessibility change, so there is nothing to race on. Being public, they are
    // invoked through the Spring proxy; they carry no annotation, so the proxy just forwards the call.
    // Not part of the client's API: callers use getLoan only.

    /**
     * Circuit is open: no HTTP call was made at all (fail fast). DEBUG, not WARN: the breaker opening is
     * logged once by CircuitBreakerStateLogger; a WARN here was one line per loan (~12/s at 2 imports/s).
     */
    public Optional<LoanDto> loanServiceUnavailable(String loanId, CallNotPermittedException e) {
        log.debug("loan-service circuit open, not calling it for loan {}", loanId);
        throw new LoanServiceUnavailableException(loanId, e);
    }

    /** Timeout or connection error. */
    public Optional<LoanDto> loanServiceUnavailable(String loanId, ResourceAccessException e) {
        log.warn("loan-service call failed for loan {}: {}", loanId, e.toString());
        throw new LoanServiceUnavailableException(loanId, e);
    }

    /** loan-service answered 5xx. */
    public Optional<LoanDto> loanServiceUnavailable(String loanId, HttpServerErrorException e) {
        log.warn("loan-service answered {} for loan {}", e.getStatusCode(), loanId);
        throw new LoanServiceUnavailableException(loanId, e);
    }
}