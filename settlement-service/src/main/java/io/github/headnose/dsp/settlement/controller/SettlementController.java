package io.github.headnose.dsp.settlement.controller;

import io.github.headnose.dsp.settlement.controller.dto.SettlementRequest;
import io.github.headnose.dsp.settlement.controller.dto.SettlementResponse;
import io.github.headnose.dsp.settlement.service.SettlementService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/loans/{loanId}/settlements")
public class SettlementController {

    private final SettlementService settlementService;

    public SettlementController(SettlementService settlementService) {
        this.settlementService = settlementService;
    }

    /** Calculates (or recalculates) the settlement. Replaces all adjustments. */
    @PatchMapping
    public SettlementResponse calculate(@PathVariable String loanId,
                                        @Valid @RequestBody SettlementRequest request) {
        return SettlementResponse.from(settlementService.calculate(loanId, request));
    }
}