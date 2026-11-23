package io.github.headnose.dsp.settlement.client;

/** loan-service could not answer (timeout, connection error, 5xx) or the circuit breaker is open. */
public class LoanServiceUnavailableException extends RuntimeException {

    public LoanServiceUnavailableException(String loanId, Throwable cause) {
        super("Loan service temporarily unavailable (loan " + loanId + ")", cause);
    }
}