package io.github.headnose.dsp.settlement.service;

import io.github.headnose.dsp.settlement.cache.SettlementDetailCache;
import io.github.headnose.dsp.settlement.client.LoanServiceClient;
import io.github.headnose.dsp.settlement.client.LoanServiceUnavailableException;
import io.github.headnose.dsp.settlement.client.dto.DealerDto;
import io.github.headnose.dsp.settlement.client.dto.LoanDto;
import io.github.headnose.dsp.settlement.document.LoanSettlementDocument;
import io.github.headnose.dsp.settlement.model.LoanStatus;
import io.github.headnose.dsp.settlement.repository.LoanSettlementRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LoanImportServiceTest {

    @Mock
    private LoanServiceClient loanServiceClient;

    @Mock
    private LoanSettlementRepository repository;

    @Mock
    private SettlementDetailCache cache;

    private LoanImportService service;

    @BeforeEach
    void setUp() {
        // Runnable::run = run each task on the calling thread: deterministic, no real thread pool
        service = new LoanImportService(loanServiceClient, repository, cache, Runnable::run);
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
        verify(cache, never()).evict(any());
    }

    @Test
    void importedLoanEvictsCacheAfterSave() {
        LoanDto closed = new LoanDto("LN-1",
                new DealerDto("D-100", "Riverbend Motors", "Riverbend Community Bank",
                        "accounts@riverbend-motors.example.com", "wires@riverbend-bank.example.com"),
                LoanStatus.CLOSED, "USD", new BigDecimal("28500.00"), LocalDate.of(2026, 10, 15));
        when(repository.existsById("LN-1")).thenReturn(false);
        when(loanServiceClient.getLoan("LN-1")).thenReturn(Optional.of(closed));

        List<ImportResult> results = service.importLoans(List.of("LN-1"));

        assertThat(results).extracting(ImportResult::outcome).containsExactly(ImportOutcome.IMPORTED);
        InOrder order = inOrder(repository, cache);
        order.verify(repository).save(any(LoanSettlementDocument.class));
        order.verify(cache).evict("LN-1");
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