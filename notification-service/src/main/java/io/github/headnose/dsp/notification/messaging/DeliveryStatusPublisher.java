package io.github.headnose.dsp.notification.messaging;

import io.github.headnose.dsp.notification.config.KafkaTopics;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes one result to delivery-status, keyed by submissionId (same partition as the
 * submission's other events), and waits for the broker ack so the listener only returns,
 * and the offset is only committed, once the result is safely stored.
 */
@Component
public class DeliveryStatusPublisher {

    static final long ACK_TIMEOUT_SECONDS = 15;

    /** G3 (blueprint "Request Tracing" example): same header names as PWT's email-delivery records. */
    static final String SUBMISSION_ID_HEADER = "submissionId";
    static final String LOAN_ID_HEADER = "loanId";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public DeliveryStatusPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publish(DeliveryStatusEvent event) {
        try {
            kafkaTemplate.send(toRecord(event)).get(ACK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DeliveryStatusPublishException(event.recipientId(), e);
        } catch (ExecutionException | TimeoutException e) {
            throw new DeliveryStatusPublishException(event.recipientId(), e);
        }
    }

    /**
     * G3: ids copied into headers for header-only tools; PWT reads the payload only, so the headers are
     * informational. The traceparent header is added by the template's observation.
     */
    static ProducerRecord<String, Object> toRecord(DeliveryStatusEvent event) {
        ProducerRecord<String, Object> record =
                new ProducerRecord<>(KafkaTopics.DELIVERY_STATUS, event.submissionId(), event);
        record.headers().add(SUBMISSION_ID_HEADER, event.submissionId().getBytes(StandardCharsets.UTF_8));
        record.headers().add(LOAN_ID_HEADER, event.loanId().getBytes(StandardCharsets.UTF_8));
        return record;
    }
}