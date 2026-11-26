package io.github.headnose.dsp.notification.logging;

/**
 * O3: names of our own MDC entries. Boot's ECS log file writes every MDC entry as a top-level JSON key,
 * so these names are what Kibana searches (blueprint "Centralized Logging": submissionId, loanId next to
 * traceId/spanId, which the tracer puts there itself). BlastMail has no users, so no userId. Own copy (D10).
 */
public final class MdcKeys {

    public static final String LOAN_ID = "loanId";
    public static final String SUBMISSION_ID = "submissionId";

    private MdcKeys() {
    }
}