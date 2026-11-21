package io.github.headnose.dsp.wire.client.dto;

import java.util.List;

/** settlement-service GET /api/v1/loans/{loanId}/settlements response (blueprint: SettlementDetailDto). */
public record SettlementDetailDto(
        LoanDto loan,
        SettlementDto settlement,
        PaymentDto payment,
        List<AdjustmentDto> adjustments
) {}