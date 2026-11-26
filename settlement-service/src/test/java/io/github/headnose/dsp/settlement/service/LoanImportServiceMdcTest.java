package io.github.headnose.dsp.settlement.service;

import io.github.headnose.dsp.settlement.cache.SettlementDetailCache;
import io.github.headnose.dsp.settlement.client.LoanServiceClient;
import io.github.headnose.dsp.settlement.logging.MdcKeys;
import io.github.headnose.dsp.settlement.repository.LoanSettlementRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/** O3: each loan of a batch is imported with its own loanId in the MDC, and nothing is left behind. */
@ExtendWith(MockitoExtension.class)
class LoanImportServiceMdcTest {

    @Mock
    LoanServiceClient loanServiceClient;
    @Mock
    LoanSettlementRepository repository;
    @Mock
    SettlementDetailCache cache;

    @AfterEach
    void cleanUp() {
        MDC.clear();
    }

    @Test
    void eachLoanIsImportedWithItsOwnLoanIdInTheMdc() {
        LoanImportService service = new LoanImportService(loanServiceClient, repository, cache, Runnable::run);
        List<String> seenByTheClient = new ArrayList<>();
        when(loanServiceClient.getLoan(anyString())).thenAnswer(invocation -> {
            seenByTheClient.add(MDC.get(MdcKeys.LOAN_ID));
            return Optional.empty();
        });

        List<ImportResult> results = service.importLoans(List.of("LN-1", "LN-2"));

        assertThat(seenByTheClient).containsExactly("LN-1", "LN-2");
        assertThat(results).extracting(ImportResult::outcome).containsOnly(ImportOutcome.NOT_FOUND);
        assertThat(MDC.get(MdcKeys.LOAN_ID)).isNull();
    }
}