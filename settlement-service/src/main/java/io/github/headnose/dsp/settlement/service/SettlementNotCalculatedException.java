package io.github.headnose.dsp.settlement.service;

/** The loan is imported but its settlement has not been calculated yet. Maps to HTTP 404. */
public class SettlementNotCalculatedException extends RuntimeException {

    private final String loanId;

    public SettlementNotCalculatedException(String loanId) {
        super("Settlement for loan " + loanId + " has not been calculated yet");
        this.loanId = loanId;
    }

    public String getLoanId() {
        return loanId;
    }
}