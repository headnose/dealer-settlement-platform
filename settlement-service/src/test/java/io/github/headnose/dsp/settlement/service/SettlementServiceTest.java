package io.github.headnose.dsp.settlement.service;

import io.github.headnose.dsp.settlement.cache.CachedSettlementDetail;
import io.github.headnose.dsp.settlement.cache.SettlementDetailCache;
import io.github.headnose.dsp.settlement.controller.dto.AdjustmentDto;
import io.github.headnose.dsp.settlement.controller.dto.PaymentDto;
import io.github.headnose.dsp.settlement.controller.dto.SettlementRequestDto;
import io.github.headnose.dsp.settlement.document.LoanSettlementDocument;
import io.github.headnose.dsp.settlement.model.AdjustmentType;
import io.github.headnose.dsp.settlement.model.PaymentInfo;
import io.github.headnose.dsp.settlement.model.PaymentMethod;
import io.github.headnose.dsp.settlement.model.SettlementStatus;
import io.github.headnose.dsp.settlement.repository.LoanSettlementRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
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
import static org.mockito.Mockito.inOrder;
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

    @Mock
    private SettlementDetailCache cache;

    private SettlementService service;

    @BeforeEach
    void setUp() {
        service = new SettlementService(repository, new SettlementCalculator(), FRIDAY_MORNING_NY, cache);
    }

    // ---------- helpers ----------

    private static SettlementRequestDto request(String currency, AdjustmentDto... adjustments) {
        return new SettlementRequestDto(new PaymentDto(PaymentMethod.WIRE, currency, null, null), List.of(adjustments));
    }

    private static AdjustmentDto adjReq(AdjustmentType type, String amount) {
        return new AdjustmentDto(type, new BigDecimal(amount));
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
        assertThat(result.getSettlement())
                .usingRecursiveComparison().ignoringFields("settlementId")
                .isEqualTo(settlement("28500.00", "650.00", "27850.00"));
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
        service = new SettlementService(repository, new SettlementCalculator(), thursdayNightNy, cache);
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
        verify(cache, never()).evict(any());
    }

    // ---------- cache (C6) ----------

    private static LoanSettlementDocument calculatedDocument() {
        LoanSettlementDocument doc = LoanSettlementDocument.imported(closedLoan());
        doc.applySettlement(wirePayment(), List.of(), settlement("28500.00", "0.00", "28500.00"));
        return doc;
    }

    @Test
    void calculateEvictsCacheAfterSave() {
        givenImportedLoan();
        givenSaveReturnsDocument();

        service.calculate(LOAN_ID, request("USD"));

        // Order matters: evicting before the save could let a GET refill the cache with the old value
        InOrder order = inOrder(repository, cache);
        order.verify(repository).save(any(LoanSettlementDocument.class));
        order.verify(cache).evict(LOAN_ID);
    }

    @Test
    void getSettlementDetailHitDoesNotReadMongo() {
        CachedSettlementDetail cached = CachedSettlementDetail.from(calculatedDocument());
        when(cache.get(LOAN_ID)).thenReturn(Optional.of(cached));

        assertThat(service.getSettlementDetail(LOAN_ID)).isSameAs(cached);
        verify(repository, never()).findById(any());
        verify(cache, never()).put(any());
    }

    @Test
    void getSettlementDetailMissReadsMongoAndFillsCache() {
        LoanSettlementDocument doc = calculatedDocument();
        when(cache.get(LOAN_ID)).thenReturn(Optional.empty());
        when(repository.findById(LOAN_ID)).thenReturn(Optional.of(doc));

        CachedSettlementDetail result = service.getSettlementDetail(LOAN_ID);

        assertThat(result).isEqualTo(CachedSettlementDetail.from(doc));
        verify(cache).put(result);
    }

    @Test
    void getSettlementDetailNotCalculatedIsNotCached() {
        when(cache.get(LOAN_ID)).thenReturn(Optional.empty());
        givenImportedLoan();

        assertThatThrownBy(() -> service.getSettlementDetail(LOAN_ID))
                .isInstanceOf(SettlementNotCalculatedException.class);
        verify(cache, never()).put(any());
    }

    // ---------- getCalculatedSettlement ----------

    @Test
    void getCalculatedSettlementReturnsCalculatedDocument() {
        LoanSettlementDocument calculated = LoanSettlementDocument.imported(closedLoan());
        calculated.applySettlement(wirePayment(), List.of(), settlement("28500.00", "0.00", "28500.00"));
        when(repository.findById(LOAN_ID)).thenReturn(Optional.of(calculated));

        LoanSettlementDocument result = service.getCalculatedSettlement(LOAN_ID);

        assertThat(result).isSameAs(calculated);
        verify(repository, never()).save(any());
    }

    @Test
    void getCalculatedSettlementThrowsWhenLoanNotImported() {
        when(repository.findById(LOAN_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getCalculatedSettlement(LOAN_ID))
                .isInstanceOf(LoanNotImportedException.class)
                .hasMessageContaining(LOAN_ID);
    }

    @Test
    void getCalculatedSettlementThrowsWhenNotCalculatedYet() {
        givenImportedLoan();

        assertThatThrownBy(() -> service.getCalculatedSettlement(LOAN_ID))
                .isInstanceOf(SettlementNotCalculatedException.class)
                .hasMessageContaining(LOAN_ID);
    }
}