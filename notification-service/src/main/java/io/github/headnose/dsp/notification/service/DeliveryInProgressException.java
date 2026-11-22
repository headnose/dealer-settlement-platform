package io.github.headnose.dsp.notification.service;

/** Another attempt holds the PROCESSING key for this recipient. Retryable. */
public class DeliveryInProgressException extends RuntimeException {

    public DeliveryInProgressException(String recipientId) {
        super("Delivery already in progress for recipient " + recipientId);
    }
}