package io.github.headnose.dsp.settlement.controller.dto;

import io.github.headnose.dsp.settlement.document.LoanSettlementDocument;

import java.util.List;

/** Settlement detail for downstream services such as wire-service (blueprint: SettlementDetailDto). */
public record SettlementDetailDto(
        SettlementDto settlement,
        PaymentDto payment,
        List<AdjustmentDto> adjustments
) {
    /** Only valid for a CALCULATED document; the service checks this before calling. */
    public static SettlementDetailDto from(LoanSettlementDocument doc) {
        return new SettlementDetailDto(
                SettlementDto.from(doc),
                PaymentDto.from(doc.getPayment()),
                doc.getAdjustments().stream()
                        .map(AdjustmentDto::from)
                        .toList());
    }
}