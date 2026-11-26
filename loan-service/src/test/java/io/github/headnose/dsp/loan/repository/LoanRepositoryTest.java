package io.github.headnose.dsp.loan.repository;

import io.github.headnose.dsp.loan.dto.LoanDto;
import io.github.headnose.dsp.loan.dto.LoanStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class LoanRepositoryTest {

    private final LoanRepository repository = new LoanRepository();

    @Test
    void namedSampleLoansAreUnchanged() {
        assertThat(repository.findById("LN-1001")).get()
                .extracting(LoanDto::status, LoanDto::principalAmount)
                .containsExactly(LoanStatus.CLOSED, new BigDecimal("28500.00"));
        assertThat(repository.findById("LN-1003")).get().extracting(LoanDto::status).isEqualTo(LoanStatus.ACTIVE);
        assertThat(repository.findById("LN-2002")).get().extracting(LoanDto::status).isEqualTo(LoanStatus.CANCELLED);
    }

    @Test
    void demoRangeIsClosedFromFirstToLast() {
        LoanDto first = repository.findById("LN-5001").orElseThrow();
        LoanDto last = repository.findById("LN-5200").orElseThrow();

        assertThat(first.status()).isEqualTo(LoanStatus.CLOSED);
        assertThat(first.principalAmount()).isEqualTo(new BigDecimal("15000.00"));
        assertThat(first.closingDate()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(first.dealer().dealerId()).isEqualTo("D-100");

        assertThat(last.status()).isEqualTo(LoanStatus.CLOSED);
        assertThat(last.principalAmount()).isEqualTo(new BigDecimal("42312.75"));
        assertThat(last.dealer().dealerId()).isEqualTo("D-200");
    }

    @Test
    void idsOutsideTheDemoRangeAreNotFound() {
        assertThat(repository.findById("LN-5000")).isEmpty();
        assertThat(repository.findById("LN-5201")).isEmpty();
    }

    @Test
    void sameIdAlwaysReturnsTheSameLoan() {
        assertThat(new LoanRepository().findById("LN-5123")).isEqualTo(repository.findById("LN-5123"));
    }
}
