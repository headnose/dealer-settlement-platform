package io.github.headnose.dsp.settlement.controller.dto;

import io.github.headnose.dsp.settlement.document.LoanSettlementDocument;
import io.github.headnose.dsp.settlement.model.SettlementInfo;
import io.github.headnose.dsp.settlement.model.SettlementStatus;

import java.math.BigDecimal;

public record SettlementDto(
        String settlementId,
        BigDecimal grossAmount,
        BigDecimal totalAdjustment,
        BigDecimal netSettlement,
        SettlementStatus status
) {
    public static SettlementDto from(LoanSettlementDocument doc) {
        SettlementInfo s = doc.getSettlement();
        return new SettlementDto(
                s.settlementId(),
                s.grossAmount(),
                s.totalAdjustment(),
                s.netSettlement(),
                doc.getStatus());
    }
}