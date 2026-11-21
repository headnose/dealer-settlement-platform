package io.github.headnose.dsp.wire.client.dto;

import java.math.BigDecimal;

public record AdjustmentDto(
        String adjustmentType,
        BigDecimal amount
) {}