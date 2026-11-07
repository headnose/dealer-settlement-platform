package io.github.headnose.dsp.settlement.model;

public enum SettlementStatus {
    IMPORTED,    // loan data copied from loan-service, nothing calculated yet
    CALCULATED   // net settlement computed, ready for wire generation
}