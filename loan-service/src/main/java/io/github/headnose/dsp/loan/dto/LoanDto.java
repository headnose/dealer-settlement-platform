package io.github.headnose.dsp.loan.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record LoanDto(
        String loanId,
        DealerDto dealer,
        LoanStatus status,
        String currency,
        BigDecimal principalAmount,
        LocalDate closingDate
) {}