package io.github.headnose.dsp.settlement.controller.dto;

import io.github.headnose.dsp.settlement.model.Adjustment;
import io.github.headnose.dsp.settlement.model.AdjustmentType;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record AdjustmentRequest(
        @NotNull AdjustmentType adjustmentType,
        @NotNull @Positive @Digits(integer = 13, fraction = 2) BigDecimal amount
) {
    public Adjustment toAdjustment() {
        return new Adjustment(adjustmentType, amount);
    }
}