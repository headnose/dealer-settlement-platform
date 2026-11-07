package io.github.headnose.dsp.settlement.controller;

import io.github.headnose.dsp.settlement.controller.dto.ImportRequest;
import io.github.headnose.dsp.settlement.service.ImportResult;
import io.github.headnose.dsp.settlement.service.LoanImportService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/imports")
public class ImportController {

    private final LoanImportService loanImportService;

    public ImportController(LoanImportService loanImportService) {
        this.loanImportService = loanImportService;
    }

    @PostMapping
    public List<ImportResult> importLoans(@Valid @RequestBody ImportRequest request) {
        return loanImportService.importLoans(request.loanIds());
    }
}