package io.github.headnose.dsp.settlement.controller.dto;

import io.github.headnose.dsp.settlement.document.LoanSettlementDocument;
import io.github.headnose.dsp.settlement.model.SettlementInfo;
import io.github.headnose.dsp.settlement.model.SettlementStatus;

import java.math.BigDecimal;

public record SettlementResponse(
        String loanId,
        SettlementStatus status,
        BigDecimal grossAmount,
        BigDecimal totalAdjustment,
        BigDecimal netSettlement
) {
    public static SettlementResponse from(LoanSettlementDocument doc) {
        SettlementInfo s = doc.getSettlement();
        return new SettlementResponse(
                doc.getLoanId(),
                doc.getStatus(),
                s.grossAmount(),
                s.totalAdjustment(),
                s.netSettlement());
    }
}