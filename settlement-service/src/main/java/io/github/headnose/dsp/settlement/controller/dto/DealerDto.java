package io.github.headnose.dsp.settlement.controller.dto;

import io.github.headnose.dsp.settlement.model.DealerInfo;

/** Dealer as LSC stored it at import (blueprint: DealerDto). Not the upstream client.dto.DealerDto. */
public record DealerDto(
        String dealerId,
        String dealerName,
        String dealerBankName,
        String dealerContactEmail,
        String dealerBankContactEmail
) {
    public static DealerDto from(DealerInfo dealer) {
        return new DealerDto(
                dealer.dealerId(),
                dealer.dealerName(),
                dealer.dealerBankName(),
                dealer.dealerContactEmail(),
                dealer.dealerBankContactEmail());
    }
}