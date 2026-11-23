package io.github.headnose.dsp.settlement.service;

public enum ImportOutcome {
    IMPORTED,                  // fetched from loan-service and saved
    ALREADY_IMPORTED,          // a settlement document already exists for this loan
    NOT_FOUND,                 // loan-service returned 404 (and only then)
    NOT_CLOSED,                // loan exists but isn't CLOSED, so it can't be settled
    LOAN_SERVICE_UNAVAILABLE,  // timeout, connection error, 5xx or circuit breaker open: try again later
    FAILED                     // unexpected error (DB error, broken answer, bug): details only in the log
}