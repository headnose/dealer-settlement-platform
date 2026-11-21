package io.github.headnose.dsp.wire.messaging;

/** Kafka did not confirm the email-delivery events in time. Mapped to 503 (D13). */
public class EmailDeliveryPublishException extends RuntimeException {

    public EmailDeliveryPublishException(String message, Throwable cause) {
        super(message, cause);
    }
}