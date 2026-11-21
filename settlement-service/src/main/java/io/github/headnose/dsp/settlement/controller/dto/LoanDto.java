package io.github.headnose.dsp.settlement.controller.dto;

import io.github.headnose.dsp.settlement.model.LoanInfo;
import io.github.headnose.dsp.settlement.model.LoanStatus;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Loan as LSC stored it at import (blueprint: LoanDto). Not the upstream client.dto.LoanDto. */
public record LoanDto(
        String loanId,
        DealerDto dealer,
        LoanStatus status,
        String currency,
        BigDecimal principalAmount,
        LocalDate closingDate
) {
    public static LoanDto from(LoanInfo loan) {
        return new LoanDto(
                loan.loanId(),
                DealerDto.from(loan.dealer()),
                loan.status(),
                loan.currency(),
                loan.principalAmount(),
                loan.closingDate());
    }
}