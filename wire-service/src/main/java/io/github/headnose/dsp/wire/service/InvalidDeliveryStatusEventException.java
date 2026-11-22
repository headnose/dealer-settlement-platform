package io.github.headnose.dsp.wire.service;

/**
 * The event can never succeed (unknown recipient, wrong submission, unexpected status).
 * Retrying is pointless: the listener sends it straight to delivery-status-dlq (D20).
 */
public class InvalidDeliveryStatusEventException extends RuntimeException {

    public InvalidDeliveryStatusEventException(String message) {
        super(message);
    }
}