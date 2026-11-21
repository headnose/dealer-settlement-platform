package io.github.headnose.dsp.wire.client.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record LoanDto(
        String loanId,
        DealerDto dealer,
        String status,
        String currency,
        BigDecimal principalAmount,
        LocalDate closingDate
) {}