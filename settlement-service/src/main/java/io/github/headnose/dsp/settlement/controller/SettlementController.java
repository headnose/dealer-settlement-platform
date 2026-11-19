package io.github.headnose.dsp.settlement.controller;

import io.github.headnose.dsp.settlement.controller.dto.SettlementRequestDto;
import io.github.headnose.dsp.settlement.controller.dto.SettlementDto;
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
    public SettlementDto calculate(@PathVariable String loanId,
                                   @Valid @RequestBody SettlementRequestDto request) {
        return SettlementDto.from(settlementService.calculate(loanId, request));
    }
}