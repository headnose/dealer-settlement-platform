package io.github.headnose.dsp.wire.client;

/** settlement-service answered 404 "Settlement not calculated". */
public class SettlementNotCalculatedException extends RuntimeException {

    private final String loanId;

    public SettlementNotCalculatedException(String loanId) {
        super("Settlement for loan " + loanId + " has not been calculated yet");
        this.loanId = loanId;
    }

    public String getLoanId() { return loanId; }
}