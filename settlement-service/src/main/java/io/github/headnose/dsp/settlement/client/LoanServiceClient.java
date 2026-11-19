package io.github.headnose.dsp.settlement.client;

import io.github.headnose.dsp.settlement.client.dto.LoanDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.Optional;

@Component
public class LoanServiceClient {

    private final RestClient restClient;

    public LoanServiceClient(@Value("${clients.loan-service.base-url}") String baseUrl) {
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .build();
    }

    /** Returns the loan, or empty if loan-service answers 404. Other errors are thrown. */
    public Optional<LoanDto> getLoan(String loanId) {
        try {
            LoanDto loan = restClient.get()
                    .uri("/api/v1/loans/{loanId}", loanId)
                    .retrieve()
                    .body(LoanDto.class);
            return Optional.ofNullable(loan);
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        }
    }
}