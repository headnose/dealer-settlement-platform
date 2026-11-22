package io.github.headnose.dsp.notification.config;

/** Topic names used by BlastMail (blueprint Kafka section). */
public final class KafkaTopics {

    /** Consumed: PWT's email requests (declared by PWT). */
    public static final String EMAIL_DELIVERY = "email-delivery";
    /** Produced: one result per recipient, consumed by PWT. */
    public static final String DELIVERY_STATUS = "delivery-status";
    /** Produced: email-delivery events BlastMail cannot process. */
    public static final String EMAIL_DELIVERY_DLQ = "email-delivery-dlq";

    private KafkaTopics() {
    }
}