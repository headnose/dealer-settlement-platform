package io.github.headnose.dsp.wire.config;
/**
 * Kafka topic names from the blueprint (Kafka section). One place, used by the
 * topic declarations, the producer and the delivery-status listener.
 */
public final class KafkaTopics {

    public static final String EMAIL_DELIVERY = "email-delivery";
    public static final String DELIVERY_STATUS = "delivery-status";
    public static final String DELIVERY_STATUS_DLQ = "delivery-status-dlq";

    private KafkaTopics() {
    }
}