package io.github.headnose.dsp.wire.messaging;

import io.github.headnose.dsp.wire.config.KafkaTopics;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class EmailDeliveryPublisher {

    /** G3 (blueprint "Request Tracing" example): business ids as Kafka headers, next to traceparent. */
    static final String SUBMISSION_ID_HEADER = "submissionId";
    static final String LOAN_ID_HEADER = "loanId";

    // Safety net above the producer's own delivery.timeout.ms (10s), so normally
    // the producer reports the failure first and this never fires.
    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(15);

    private final KafkaTemplate<String, EmailDeliveryEvent> kafkaTemplate;

    public EmailDeliveryPublisher(KafkaTemplate<String, EmailDeliveryEvent> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * Sends all events (key = submissionId, so one submission stays on one partition)
     * and returns only when the broker has acknowledged every one of them.
     */
    public void publishAll(List<EmailDeliveryEvent> events) {
        try {
            CompletableFuture<?>[] sends = events.stream()
                    .map(event -> kafkaTemplate.send(toRecord(event)))
                    .toArray(CompletableFuture[]::new);
            CompletableFuture.allOf(sends).get(SEND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EmailDeliveryPublishException("Interrupted while publishing email-delivery events", e);
        } catch (ExecutionException | TimeoutException | KafkaException
                 | org.springframework.kafka.KafkaException e) {
            throw new EmailDeliveryPublishException("Kafka did not confirm email-delivery events", e);
        }
    }

    /**
     * G3: the ids are copied into headers for tools that read headers only (console consumer, log shippers,
     * a broker UI). The payload stays the contract: no consumer reads these headers, so they cannot drift
     * into a second source of truth. The traceparent header is added by the template's observation.
     */
    static ProducerRecord<String, EmailDeliveryEvent> toRecord(EmailDeliveryEvent event) {
        ProducerRecord<String, EmailDeliveryEvent> record =
                new ProducerRecord<>(KafkaTopics.EMAIL_DELIVERY, event.submissionId(), event);
        record.headers().add(SUBMISSION_ID_HEADER, event.submissionId().getBytes(StandardCharsets.UTF_8));
        record.headers().add(LOAN_ID_HEADER, event.loanId().getBytes(StandardCharsets.UTF_8));
        return record;
    }
}