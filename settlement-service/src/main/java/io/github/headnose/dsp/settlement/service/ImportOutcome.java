package io.github.headnose.dsp.settlement.service;

public enum ImportOutcome {
    IMPORTED,          // fetched from loan-service and saved
    ALREADY_IMPORTED,  // a settlement document already exists for this loan
    NOT_FOUND,         // loan-service returned 404
    NOT_CLOSED,        // loan exists but isn't CLOSED, so it can't be settled
    FAILED             // unexpected error (loan-service down, DB error, ...)
}