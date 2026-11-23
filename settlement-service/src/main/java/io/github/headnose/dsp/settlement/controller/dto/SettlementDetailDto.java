package io.github.headnose.dsp.settlement.controller.dto;

import io.github.headnose.dsp.settlement.cache.CachedSettlementDetail;
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
        return from(CachedSettlementDetail.from(doc));
    }

    /** One mapping path for cache hit and cache miss (C6). */
    public static SettlementDetailDto from(CachedSettlementDetail detail) {
        return new SettlementDetailDto(
                LoanDto.from(detail.loan()),
                SettlementDto.of(detail.settlement(), detail.status()),
                PaymentDto.from(detail.payment()),
                detail.adjustments().stream()
                        .map(AdjustmentDto::from)
                        .toList());
    }
}