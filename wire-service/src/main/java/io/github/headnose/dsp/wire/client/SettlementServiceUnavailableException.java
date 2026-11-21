package io.github.headnose.dsp.wire.client;

/** settlement-service could not give an answer: down, timed out, 5xx, or an unexpected 404. */
public class SettlementServiceUnavailableException extends RuntimeException {

    public SettlementServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}