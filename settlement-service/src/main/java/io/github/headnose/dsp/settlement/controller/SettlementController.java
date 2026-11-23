package io.github.headnose.dsp.settlement.controller;

import io.github.headnose.dsp.settlement.controller.dto.SettlementDetailDto;
import io.github.headnose.dsp.settlement.controller.dto.SettlementRequestDto;
import io.github.headnose.dsp.settlement.controller.dto.SettlementDto;
import io.github.headnose.dsp.settlement.service.SettlementService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

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

    /** Settlement detail for downstream services such as wire-service (blueprint: SettlementDetailDto). */
    @GetMapping
    public SettlementDetailDto getSettlement(@PathVariable String loanId) {
        return SettlementDetailDto.from(settlementService.getSettlementDetail(loanId));
    }
}