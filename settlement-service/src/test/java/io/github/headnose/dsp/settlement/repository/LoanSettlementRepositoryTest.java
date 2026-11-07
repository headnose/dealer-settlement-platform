package io.github.headnose.dsp.settlement.repository;

import io.github.headnose.dsp.settlement.config.MongoConfig;
import io.github.headnose.dsp.settlement.document.LoanSettlementDocument;
import io.github.headnose.dsp.settlement.model.DealerInfo;
import io.github.headnose.dsp.settlement.model.LoanInfo;
import io.github.headnose.dsp.settlement.model.LoanStatus;
import io.github.headnose.dsp.settlement.model.SettlementStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.mongodb.test.autoconfigure.DataMongoTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataMongoTest
@Import(MongoConfig.class)
class LoanSettlementRepositoryTest {

    private static final String TEST_LOAN_ID = "TEST-LN-1";

    @Autowired
    private LoanSettlementRepository repository;

    @AfterEach
    void cleanUp() {
        repository.deleteById(TEST_LOAN_ID);
    }

    private LoanInfo sampleLoan() {
        DealerInfo dealer = new DealerInfo("D-TEST", "Test Dealer", "Test Bank",
                "dealer@test.example.com", "bank@test.example.com");
        return new LoanInfo(TEST_LOAN_ID, dealer, LoanStatus.CLOSED, "USD",
                new BigDecimal("28500.00"), LocalDate.of(2026, 10, 15));
    }

    @Test
    void save_thenFindById_returnsSameDataWithAuditFields() {
        repository.save(LoanSettlementDocument.imported(sampleLoan()));

        LoanSettlementDocument found = repository.findById(TEST_LOAN_ID).orElseThrow();

        assertThat(found.getStatus()).isEqualTo(SettlementStatus.IMPORTED);
        assertThat(found.getLoan().dealer().dealerName()).isEqualTo("Test Dealer");
        assertThat(found.getLoan().principalAmount()).isEqualByComparingTo("28500.00");
        assertThat(found.getLoan().closingDate()).isEqualTo(LocalDate.of(2026, 10, 15));
        assertThat(found.getVersion()).isEqualTo(0L);
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getUpdatedAt()).isNotNull();
    }

    @Test
    void findByLoanDealerDealerId_findsLoansForThatDealer() {
        repository.save(LoanSettlementDocument.imported(sampleLoan()));

        assertThat(repository.findByLoanDealerDealerId("D-TEST"))
                .extracting(LoanSettlementDocument::getLoanId)
                .contains(TEST_LOAN_ID);
    }

    @Test
    void save_withStaleVersion_throwsOptimisticLockingFailure() {
        repository.save(LoanSettlementDocument.imported(sampleLoan()));

        // Two "users" load the same document
        LoanSettlementDocument userA = repository.findById(TEST_LOAN_ID).orElseThrow();
        LoanSettlementDocument userB = repository.findById(TEST_LOAN_ID).orElseThrow();

        repository.save(userA);   // version 0 -> 1

        // userB still holds version 0, so saving must fail instead of overwriting
        assertThatThrownBy(() -> repository.save(userB))
                .isInstanceOf(OptimisticLockingFailureException.class);
    }
}