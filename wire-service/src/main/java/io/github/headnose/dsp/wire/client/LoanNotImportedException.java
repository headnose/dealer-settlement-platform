package io.github.headnose.dsp.wire.client;

/** settlement-service answered 404 "Loan not imported". */
public class LoanNotImportedException extends RuntimeException {

    private final String loanId;

    public LoanNotImportedException(String loanId) {
        super("Loan " + loanId + " has not been imported into settlement-service");
        this.loanId = loanId;
    }

    public String getLoanId() { return loanId; }
}