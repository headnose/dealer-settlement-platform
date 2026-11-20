package io.github.headnose.dsp.settlement.controller.dto;

import io.github.headnose.dsp.settlement.document.LoanSettlementDocument;
import io.github.headnose.dsp.settlement.model.AdjustmentType;
import io.github.headnose.dsp.settlement.model.PaymentMethod;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static io.github.headnose.dsp.settlement.SettlementTestData.adj;
import static io.github.headnose.dsp.settlement.SettlementTestData.closedLoan;
import static io.github.headnose.dsp.settlement.SettlementTestData.money;
import static io.github.headnose.dsp.settlement.SettlementTestData.settlement;
import static io.github.headnose.dsp.settlement.SettlementTestData.wirePayment;
import static org.assertj.core.api.Assertions.assertThat;

class SettlementDetailDtoTest {

    @Test
    void mapsCalculatedDocumentToBlueprintShape() {
        LoanSettlementDocument doc = LoanSettlementDocument.imported(closedLoan());
        doc.applySettlement(wirePayment(),
                List.of(adj(AdjustmentType.DEALER_HOLDBACK, "500.00"),
                        adj(AdjustmentType.DOC_FEE, "150.00")),
                settlement("28500.00", "650.00", "27850.00"));

        SettlementDetailDto dto = SettlementDetailDto.from(doc);

        assertThat(dto.settlement().settlementId()).startsWith("SET-");
        assertThat(dto.settlement().netSettlement()).isEqualByComparingTo("27850.00");
        assertThat(dto.payment()).isEqualTo(new PaymentDto(
                PaymentMethod.WIRE, "USD", "Sunrise Auto Group Bank", LocalDate.of(2026, 5, 11)));
        assertThat(dto.adjustments()).containsExactly(
                new AdjustmentDto(AdjustmentType.DEALER_HOLDBACK, money("500.00")),
                new AdjustmentDto(AdjustmentType.DOC_FEE, money("150.00")));
    }
}