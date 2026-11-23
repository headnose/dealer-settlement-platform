package io.github.headnose.dsp.wire.client;

import io.github.headnose.dsp.wire.client.dto.LscProblem;
import io.github.headnose.dsp.wire.client.dto.SettlementDetailDto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Calls settlement-service (blueprint: LSC) for the settlement detail of a loan.
 * Built from Boot's auto-configured RestClient.Builder so every call is observed
 * (metric http.client.requests; traces later). Timeouts are still our own (2s/5s).
 */
@Component
public class SettlementServiceClient {

    // Must match the ProblemDetail titles in settlement-service's GlobalExceptionHandler (decision D2).
    static final String LOAN_NOT_IMPORTED_TITLE = "Loan not imported";
    static final String SETTLEMENT_NOT_CALCULATED_TITLE = "Settlement not calculated";

    private final RestClient restClient;

    /** Boot's builder is prototype-scoped: this client gets its own copy, so setting the factory affects no one else. */
    @Autowired
    public SettlementServiceClient(
            RestClient.Builder builder,
            @Value("${clients.settlement-service.base-url}") String baseUrl,
            @Value("${clients.settlement-service.connect-timeout}") Duration connectTimeout,
            @Value("${clients.settlement-service.read-timeout}") Duration readTimeout) {
        this(builder.requestFactory(requestFactory(connectTimeout, readTimeout)), baseUrl);
    }

    /** For tests: the builder may already be bound to a MockRestServiceServer. */
    SettlementServiceClient(RestClient.Builder builder, String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    private static SimpleClientHttpRequestFactory requestFactory(Duration connectTimeout, Duration readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        return factory;
    }

    /**
     * Returns the calculated settlement detail for the loan.
     *
     * @throws LoanNotImportedException             settlement-service: 404 "Loan not imported"
     * @throws SettlementNotCalculatedException     settlement-service: 404 "Settlement not calculated"
     * @throws SettlementServiceUnavailableException 5xx, timeout, connection refused, empty body, or another 404
     */
    public SettlementDetailDto getSettlementDetail(String loanId) {
        SettlementDetailDto detail;
        try {
            detail = restClient.get()
                    .uri("/api/v1/loans/{loanId}/settlements", loanId)
                    .retrieve()
                    .body(SettlementDetailDto.class);
        } catch (HttpClientErrorException.NotFound e) {
            throw translateNotFound(loanId, e);
        } catch (HttpServerErrorException | ResourceAccessException e) {
            throw new SettlementServiceUnavailableException(
                    "settlement-service did not answer for loan " + loanId, e);
        }
        if (detail == null) {
            throw new SettlementServiceUnavailableException(
                    "settlement-service returned an empty body for loan " + loanId, null);
        }
        return detail;
    }

    private RuntimeException translateNotFound(String loanId, HttpClientErrorException.NotFound e) {
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
    private static String problemTitle(HttpClientErrorException e) {
        try {
            LscProblem problem = e.getResponseBodyAs(LscProblem.class);
            return problem == null ? null : problem.title();
        } catch (RuntimeException unreadable) {
            return null;
        }
    }
}