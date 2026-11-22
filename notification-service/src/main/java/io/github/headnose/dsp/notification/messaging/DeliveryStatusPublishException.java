package io.github.headnose.dsp.notification.messaging;

/** delivery-status publish failed or timed out. Retryable. */
public class DeliveryStatusPublishException extends RuntimeException {

    public DeliveryStatusPublishException(String recipientId, Throwable cause) {
        super("Could not publish delivery status for recipient " + recipientId, cause);
    }
}