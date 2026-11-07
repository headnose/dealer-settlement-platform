package io.github.headnose.dsp.settlement.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record ImportRequest(
        @NotEmpty(message = "loanIds must not be empty")
        @Size(max = 50, message = "at most 50 loans per import")
        List<@NotBlank String> loanIds
) {}