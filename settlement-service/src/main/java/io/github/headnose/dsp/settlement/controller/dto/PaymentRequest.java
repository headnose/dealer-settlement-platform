package io.github.headnose.dsp.settlement.controller.dto;

import io.github.headnose.dsp.settlement.model.PaymentMethod;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record PaymentRequest(
        @NotNull PaymentMethod paymentMethod,
        @NotNull @Pattern(regexp = "[A-Z]{3}", message = "must be a 3-letter ISO 4217 code, e.g. USD")
        String currency
) {}