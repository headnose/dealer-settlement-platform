package io.github.headnose.dsp.settlement.service;

import io.github.headnose.dsp.settlement.controller.dto.AdjustmentRequest;
import io.github.headnose.dsp.settlement.controller.dto.PaymentRequest;
import io.github.headnose.dsp.settlement.controller.dto.SettlementRequest;
import io.github.headnose.dsp.settlement.document.LoanSettlementDocument;
import io.github.headnose.dsp.settlement.model.AdjustmentType;
import io.github.headnose.dsp.settlement.model.PaymentInfo;
import io.github.headnose.dsp.settlement.model.PaymentMethod;
import io.github.headnose.dsp.settlement.model.SettlementStatus;
import io.github.headnose.dsp.settlement.repository.LoanSettlementRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static io.github.headnose.dsp.settlement.SettlementTestData.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SettlementServiceTest {

    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    /** Friday 2026-05-15, 10:00 in New York, so the value date is Monday 2026-05-18. */
    private static final Clock FRIDAY_MORNING_NY =
            Clock.fixed(Instant.parse("2026-05-15T14:00:00Z"), NEW_YORK);

    @Mock
    private LoanSettlementRepository repository;

    private SettlementService service;

    @BeforeEach
    void setUp() {
        service = new SettlementService(repository, new SettlementCalculator(), FRIDAY_MORNING_NY);
    }

    // ---------- helpers ----------

    private static SettlementRequest request(String currency, AdjustmentRequest... adjustments) {
        return new SettlementRequest(new PaymentRequest(PaymentMethod.WIRE, currency), List.of(adjustments));
    }

    private static AdjustmentRequest adjReq(AdjustmentType type, String amount) {
        return new AdjustmentRequest(type, new BigDecimal(amount));
    }

    private void givenImportedLoan() {
        when(repository.findById(LOAN_ID))
                .thenReturn(Optional.of(LoanSettlementDocument.imported(closedLoan())));
    }

    private void givenSaveReturnsDocument() {
        when(repository.save(any(LoanSettlementDocument.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    // ---------- happy path ----------

    @Test
    void calculatesSettlementAndSaves() {
        givenImportedLoan();
        givenSaveReturnsDocument();

        LoanSettlementDocument result = service.calculate(LOAN_ID, request("USD",
                adjReq(AdjustmentType.DEALER_HOLDBACK, "500.00"),
                adjReq(AdjustmentType.DOC_FEE, "150.00")));

        assertThat(result.getStatus()).isEqualTo(SettlementStatus.CALCULATED);
        assertThat(result.getSettlement()).isEqualTo(settlement("28500.00", "650.00", "27850.00"));
        assertThat(result.getAdjustments()).containsExactly(
                adj(AdjustmentType.DEALER_HOLDBACK, "500.00"),
                adj(AdjustmentType.DOC_FEE, "150.00"));
        assertThat(result.getPayment()).isEqualTo(new PaymentInfo(
                PaymentMethod.WIRE, "USD", "Sunrise Auto Group Bank", LocalDate.of(2026, 5, 18)));
        verify(repository).save(result);
    }

    @Test
    void valueDateUsesBusinessTimeZoneNotUtc() {
        // 02:00 UTC on Friday is still 22:00 on THURSDAY in New York,
        // so the value date is Friday 05-15. A UTC "today" would give Monday 05-18.
        Clock thursdayNightNy = Clock.fixed(Instant.parse("2026-05-15T02:00:00Z"), NEW_YORK);
        service = new SettlementService(repository, new SettlementCalculator(), thursdayNightNy);
        givenImportedLoan();
        givenSaveReturnsDocument();

        LoanSettlementDocument result = service.calculate(LOAN_ID, request("USD"));

        assertThat(result.getPayment().valueDate()).isEqualTo(LocalDate.of(2026, 5, 15));
    }

    // ---------- rejections: nothing is saved ----------

    @Test
    void throwsWhenLoanNotImported() {
        when(repository.findById(LOAN_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.calculate(LOAN_ID, request("USD")))
                .isInstanceOf(LoanNotImportedException.class)
                .hasMessageContaining(LOAN_ID);
        verify(repository, never()).save(any());
    }

    @Test
    void rejectsCurrencyDifferentFromLoan() {
        givenImportedLoan();

        assertThatThrownBy(() -> service.calculate(LOAN_ID, request("CAD")))
                .isInstanceOf(SettlementRuleViolationException.class)
                .hasMessageContaining("CAD")
                .hasMessageContaining("USD");
        verify(repository, never()).save(any());
    }

    @Test
    void rejectsAdjustmentsExceedingGross() {
        givenImportedLoan();

        assertThatThrownBy(() -> service.calculate(LOAN_ID, request("USD",
                adjReq(AdjustmentType.OTHER, "28500.01"))))
                .isInstanceOf(SettlementRuleViolationException.class);
        verify(repository, never()).save(any());
    }

    // ---------- concurrency ----------

    @Test
    void optimisticLockFailureIsNotSwallowed() {
        givenImportedLoan();
        when(repository.save(any(LoanSettlementDocument.class)))
                .thenThrow(new OptimisticLockingFailureException("version changed"));

        assertThatThrownBy(() -> service.calculate(LOAN_ID, request("USD")))
                .isInstanceOf(OptimisticLockingFailureException.class);
    }
}