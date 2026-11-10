package io.github.headnose.dsp.settlement.service;

/**
 * Input was well-formed but breaks a settlement business rule
 * (e.g. net would be negative, currency mismatch). Maps to HTTP 422.
 */
public class SettlementRuleViolationException extends RuntimeException {
    public SettlementRuleViolationException(String message) {
        super(message);
    }
}