package io.github.headnose.dsp.settlement.service;

/** The loan has not been imported into settlement-service yet. Maps to HTTP 404. */
public class LoanNotImportedException extends RuntimeException {

    private final String loanId;

    public LoanNotImportedException(String loanId) {
        super("Loan " + loanId + " has not been imported");
        this.loanId = loanId;
    }

    public String getLoanId() {
        return loanId;
    }
}