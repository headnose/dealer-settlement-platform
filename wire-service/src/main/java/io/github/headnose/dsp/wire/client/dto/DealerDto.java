package io.github.headnose.dsp.wire.client.dto;

public record DealerDto(
        String dealerId,
        String dealerName,
        String dealerBankName,
        String dealerContactEmail,
        String dealerBankContactEmail
) {}