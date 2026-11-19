package io.github.headnose.dsp.settlement.controller.dto;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;

import io.github.headnose.dsp.settlement.model.PaymentMethod;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record PaymentDto(
        @NotNull PaymentMethod paymentMethod,
        @NotNull @Pattern(regexp = "[A-Z]{3}", message = "must be a 3-letter ISO 4217 code, e.g. USD")
        String currency,
        // Filled by settlement-service from the loan's dealerBankName, never by the client
        @JsonProperty(access = JsonProperty.Access.READ_ONLY) String beneficiaryBank,
        // Filled by settlement-service (next business day), never by the client
        @JsonProperty(access = JsonProperty.Access.READ_ONLY) LocalDate valueDate
) {}