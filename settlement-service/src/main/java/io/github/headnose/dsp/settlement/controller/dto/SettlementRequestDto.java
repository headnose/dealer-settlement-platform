package io.github.headnose.dsp.settlement.controller.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record SettlementRequestDto(
        @NotNull @Valid PaymentDto payment,
        @NotNull @Size(max = 20) List<@NotNull @Valid AdjustmentDto> adjustments
) {}