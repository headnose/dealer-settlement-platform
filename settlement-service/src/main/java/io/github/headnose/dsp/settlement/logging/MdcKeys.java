package io.github.headnose.dsp.settlement.logging;

/**
 * O3: names of our own MDC entries. Boot's ECS log file writes every MDC entry as a top-level JSON key,
 * so these names are what Kibana searches (blueprint "Centralized Logging": userId, loanId next to
 * traceId/spanId, which the tracer puts there itself).
 */
public final class MdcKeys {

    public static final String USER_ID = "userId";
    public static final String LOAN_ID = "loanId";

    private MdcKeys() {
    }
}