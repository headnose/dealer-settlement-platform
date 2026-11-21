package io.github.headnose.dsp.wire.controller.dto;

import io.github.headnose.dsp.wire.model.WireType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** POST /api/v1/wires request (blueprint: WireGenerationRequestDto). */
public record WireGenerationRequestDto(
        @NotBlank String loanId,
        @NotNull WireType wireType
) {}