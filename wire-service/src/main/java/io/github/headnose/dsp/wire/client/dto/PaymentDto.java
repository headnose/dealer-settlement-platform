package io.github.headnose.dsp.wire.client.dto;

import java.time.LocalDate;

public record PaymentDto(
        String paymentMethod,
        String currency,
        String beneficiaryBank,
        LocalDate valueDate
) {}