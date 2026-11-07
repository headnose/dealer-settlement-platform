package io.github.headnose.dsp.settlement.client.dto;

import io.github.headnose.dsp.settlement.model.DealerInfo;
import io.github.headnose.dsp.settlement.model.LoanInfo;
import io.github.headnose.dsp.settlement.model.LoanStatus;

import java.math.BigDecimal;
import java.time.LocalDate;

public record LoanResponse(
        String loanId,
        DealerResponse dealer,
        LoanStatus status,
        String currency,
        BigDecimal principalAmount,
        LocalDate closingDate
) {
    /** Converts loan-service's response into settlement-service's own model. */
    public LoanInfo toLoanInfo() {
        DealerInfo dealerInfo = new DealerInfo(
                dealer.dealerId(),
                dealer.dealerName(),
                dealer.dealerBankName(),
                dealer.dealerContactEmail(),
                dealer.dealerBankContactEmail());
        return new LoanInfo(loanId, dealerInfo, status, currency, principalAmount, closingDate);
    }
}