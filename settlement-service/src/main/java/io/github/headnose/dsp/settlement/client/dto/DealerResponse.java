package io.github.headnose.dsp.settlement.client.dto;

public record DealerResponse(
        String dealerId,
        String dealerName,
        String dealerBankName,
        String dealerContactEmail,
        String dealerBankContactEmail
) {}