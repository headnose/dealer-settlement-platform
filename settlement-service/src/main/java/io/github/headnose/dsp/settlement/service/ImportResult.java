package io.github.headnose.dsp.settlement.service;

public record ImportResult(String loanId, ImportOutcome outcome, String message) {}