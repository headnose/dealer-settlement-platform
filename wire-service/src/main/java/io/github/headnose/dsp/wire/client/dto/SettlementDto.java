package io.github.headnose.dsp.wire.client.dto;

import java.math.BigDecimal;

public record SettlementDto(
        String settlementId,
        BigDecimal grossAmount,
        BigDecimal totalAdjustment,
        BigDecimal netSettlement,
        String status
) {}