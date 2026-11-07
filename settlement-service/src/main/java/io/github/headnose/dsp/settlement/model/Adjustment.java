package io.github.headnose.dsp.settlement.model;

import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

import java.math.BigDecimal;

public record Adjustment(
        AdjustmentType type,
        @Field(targetType = FieldType.DECIMAL128) BigDecimal amount
) {}