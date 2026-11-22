package io.github.headnose.dsp.notification.service;

/**
 * Parent of every failure where we KNOW no email went out for this recipient (SMTP rejected it,
 * or the statement could not be downloaded, which happens before SMTP). When retries run out,
 * EmailDeliveryRecoverer publishes FAILED for any of these (B5, D26). Failures where the outcome
 * is unknown (Redis/Kafka down, another attempt in progress) must NOT extend this class.
 */
public abstract class DeliveryFailedException extends RuntimeException {

    protected DeliveryFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
