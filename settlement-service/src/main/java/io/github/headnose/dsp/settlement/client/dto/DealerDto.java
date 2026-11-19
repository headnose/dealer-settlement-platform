package io.github.headnose.dsp.settlement.client.dto;

public record DealerDto(
        String dealerId,
        String dealerName,
        String dealerBankName,
        String dealerContactEmail,
        String dealerBankContactEmail
) {}