package io.github.headnose.dsp.settlement.model;

import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

import java.math.BigDecimal;

public record SettlementInfo(
        String settlementId,
        @Field(targetType = FieldType.DECIMAL128) BigDecimal grossAmount,
        @Field(targetType = FieldType.DECIMAL128) BigDecimal totalAdjustment,
        @Field(targetType = FieldType.DECIMAL128) BigDecimal netSettlement
) {
    public SettlementInfo withSettlementId(String id) {
        return new SettlementInfo(id, grossAmount, totalAdjustment, netSettlement);
    }
}