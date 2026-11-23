package io.github.headnose.dsp.settlement.service;

import io.github.headnose.dsp.settlement.client.LoanServiceClient;
import io.github.headnose.dsp.settlement.client.LoanServiceUnavailableException;
import io.github.headnose.dsp.settlement.repository.LoanSettlementRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LoanImportServiceTest {

    @Mock
    private LoanServiceClient loanServiceClient;

    @Mock
    private LoanSettlementRepository repository;

    private LoanImportService service;

    @BeforeEach
    void setUp() {
        // Runnable::run = run each task on the calling thread: deterministic, no real thread pool
        service = new LoanImportService(loanServiceClient, repository, Runnable::run);
    }

    @Test
    void unavailableLoanServiceIsItsOwnOutcomeNotNotFound() {
        when(repository.existsById("LN-1")).thenReturn(false);
        when(loanServiceClient.getLoan("LN-1"))
                .thenThrow(new LoanServiceUnavailableException("LN-1", new RuntimeException("timeout")));

        List<ImportResult> results = service.importLoans(List.of("LN-1"));

        assertThat(results).containsExactly(new ImportResult("LN-1",
                ImportOutcome.LOAN_SERVICE_UNAVAILABLE, LoanImportService.UNAVAILABLE_MESSAGE));
        verify(repository, never()).save(any());
    }

    @Test
    void notFoundOnlyWhenLoanServiceSaysSo() {
        when(repository.existsById("LN-404")).thenReturn(false);
        when(loanServiceClient.getLoan("LN-404")).thenReturn(Optional.empty());

        List<ImportResult> results = service.importLoans(List.of("LN-404"));

        assertThat(results).extracting(ImportResult::outcome).containsExactly(ImportOutcome.NOT_FOUND);
    }

    @Test
    void unexpectedErrorIsFailedWithFixedMessageAndNoInternals() {
        when(repository.existsById("LN-1")).thenReturn(false);
        when(loanServiceClient.getLoan("LN-1"))
                .thenThrow(new IllegalStateException("loan-service returned an empty body for loan LN-1"));

        List<ImportResult> results = service.importLoans(List.of("LN-1"));

        assertThat(results).containsExactly(new ImportResult("LN-1",
                ImportOutcome.FAILED, LoanImportService.FAILED_MESSAGE));
    }
}