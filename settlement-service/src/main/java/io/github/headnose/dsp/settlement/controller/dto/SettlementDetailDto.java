package io.github.headnose.dsp.settlement.controller.dto;

import io.github.headnose.dsp.settlement.document.LoanSettlementDocument;

import java.util.List;

/**
 * Settlement detail for downstream services such as wire-service (blueprint: SettlementDetailDto).
 * Includes the loan, per the blueprint's example settlement document, so wire-service can build
 * DEALER and DEALER_BANK_CONTACT recipients from the loan's dealer record.
 */
public record SettlementDetailDto(
        LoanDto loan,
        SettlementDto settlement,
        PaymentDto payment,
        List<AdjustmentDto> adjustments
) {
    /** Only valid for a CALCULATED document; the service checks this before calling. */
    public static SettlementDetailDto from(LoanSettlementDocument doc) {
        return new SettlementDetailDto(
                LoanDto.from(doc.getLoan()),
                SettlementDto.from(doc),
                PaymentDto.from(doc.getPayment()),
                doc.getAdjustments().stream()
                        .map(AdjustmentDto::from)
                        .toList());
    }
}