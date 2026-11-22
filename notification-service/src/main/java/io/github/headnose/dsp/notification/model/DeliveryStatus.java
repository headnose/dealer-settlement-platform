package io.github.headnose.dsp.notification.model;

/**
 * Outcome BlastMail reports for one recipient on delivery-status.
 * PWT's enum also has PENDING, but that is PWT's starting state; BlastMail only ever reports these two.
 */
public enum DeliveryStatus {
    SENT,
    FAILED
}