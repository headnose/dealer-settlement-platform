package io.github.headnose.dsp.settlement.model;

import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

import java.math.BigDecimal;
import java.time.LocalDate;

public record LoanInfo(
        String loanId,
        DealerInfo dealer,
        LoanStatus status,
        String currency,
        @Field(targetType = FieldType.DECIMAL128) BigDecimal principalAmount,
        LocalDate closingDate
) {}