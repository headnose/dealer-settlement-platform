package io.github.headnose.dsp.loan.dto;

public record DealerDto(
        String dealerId,
        String dealerName,
        String dealerBankName,
        String dealerContactEmail,
        String dealerBankContactEmail
) {}