package io.github.headnose.dsp.settlement.model;

public record DealerInfo(
        String dealerId,
        String dealerName,
        String dealerBankName,
        String dealerContactEmail,
        String dealerBankContactEmail
) {}