package io.github.headnose.dsp.notification.service;

/** The event can never be processed (missing IDs, wrong type). Not retryable: straight to the DLQ. */
public class InvalidEmailDeliveryEventException extends RuntimeException {

    public InvalidEmailDeliveryEventException(String message) {
        super(message);
    }
}