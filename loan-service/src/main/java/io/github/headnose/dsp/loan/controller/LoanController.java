package io.github.headnose.dsp.loan.controller;

import io.github.headnose.dsp.loan.dto.LoanDto;
import io.github.headnose.dsp.loan.repository.LoanRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/loans")
public class LoanController {

    private final LoanRepository loanRepository;

    public LoanController(LoanRepository loanRepository) {
        this.loanRepository = loanRepository;
    }

    @GetMapping("/{loanId}")
    public ResponseEntity<LoanDto> getLoan(@PathVariable String loanId) {
        return ResponseEntity.of(loanRepository.findById(loanId));
    }
}