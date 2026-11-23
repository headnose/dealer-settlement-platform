package io.github.headnose.dsp.settlement.client;

import io.github.headnose.dsp.settlement.client.dto.LoanDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Optional;

@Component
public class LoanServiceClient {

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
     * Timeouts / connection errors (ResourceAccessException), 5xx (HttpServerErrorException)
     * and an empty 200 body are thrown, never turned into "not found".
     */
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
}