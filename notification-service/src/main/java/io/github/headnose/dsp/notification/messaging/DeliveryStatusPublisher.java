package io.github.headnose.dsp.notification.messaging;

import io.github.headnose.dsp.notification.config.KafkaTopics;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public DeliveryStatusPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publish(DeliveryStatusEvent event) {
        try {
            kafkaTemplate.send(KafkaTopics.DELIVERY_STATUS, event.submissionId(), event)
                    .get(ACK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DeliveryStatusPublishException(event.recipientId(), e);
        } catch (ExecutionException | TimeoutException e) {
            throw new DeliveryStatusPublishException(event.recipientId(), e);
        }
    }
}