package io.github.headnose.dsp.wire.job;

import io.github.headnose.dsp.wire.config.KafkaTopics;
import io.github.headnose.dsp.wire.service.InvalidDeliveryStatusEventException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Blueprint "Support / Retry Job" for delivery-status-dlq: sends dead-lettered events back to the topic they
 * came from, after a delay, so a failure that fixes itself (SMTP or storage back up, database back)
 * is retried without a person. Only retryable failures; at most maxRedrives rounds per event; anything
 * else is parked (left in the DLQ, logged) for the manual replay. Rules: DeadLetterRedrivePolicy.
 * <p>
 * Own consumer group, so it does not take records away from anyone reading the DLQ by hand.
 * A NEW group starts at the end of the DLQ (auto.offset.reset=latest): turning the job on does not
 * resend 30 days of old dead letters. Manual acks: the offset is committed only after the event was
 * sent back (or parked); while a record is not due yet, nack(pause) pauses and re-reads it.
 */
@Component
public class DeadLetterRedriveJob {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterRedriveJob.class);

    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(10);
    private static final String DLT_HEADER_PREFIX = "kafka_dlt-";

    private final KafkaTemplate<String, String> template;
    private final DeadLetterRedrivePolicy policy;
    private final Clock clock;
    private final int maxRedrives;

    public DeadLetterRedriveJob(ProducerFactory<?, ?> producerFactory, Clock clock,
                                @Value("${dlq-redrive.delay}") Duration delay,
                                @Value("${dlq-redrive.max-redrives}") int maxRedrives) {
        // The DLQ holds the original JSON text: send it back as text, not through the app's JSON serializer.
        // Built here, NOT as a @Bean: a second KafkaTemplate bean would make Boot back off from the main one.
        Map<String, Object> config = new HashMap<>(producerFactory.getConfigurationProperties());
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        this.template = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(config));
        // Same as the main listener's not-retryable list (KafkaConsumerConfig): retrying cannot fix these
        this.policy = new DeadLetterRedrivePolicy(delay, maxRedrives, List.of(
                InvalidDeliveryStatusEventException.class, JacksonException.class));
        this.clock = clock;
        this.maxRedrives = maxRedrives;
    }

    @KafkaListener(id = "delivery-status-dlq-redrive", topics = KafkaTopics.DELIVERY_STATUS_DLQ, groupId = "pwt-dlq-redrive",
            containerFactory = "deadLetterRedriveContainerFactory",
            autoStartup = "${dlq-redrive.enabled}",
            properties = "auto.offset.reset=latest")
    public void onDeadLetter(ConsumerRecord<String, String> record, Acknowledgment ack) throws Exception {
        DeadLetterRedrivePolicy.Decision decision = policy.decide(record, clock.instant());
        switch (decision.action()) {
            case WAIT -> ack.nack(decision.pause());
            case PARK -> {
                log.warn("Parking dead-lettered record key={} partition={} offset={}: {}",
                        record.key(), record.partition(), record.offset(), decision.reason());
                ack.acknowledge();
            }
            case REDRIVE -> {
                redrive(record);
                ack.acknowledge();
            }
        }
    }

    private void redrive(ConsumerRecord<String, String> record) throws Exception {
        String target = DeadLetterRedrivePolicy.headerValue(record, KafkaHeaders.DLT_ORIGINAL_TOPIC);
        if (target == null) {
            target = KafkaTopics.DELIVERY_STATUS;
        }
        int round = DeadLetterRedrivePolicy.redriveCount(record) + 1;

        // Keep the event's own headers (traceparent: the retry continues the original trace) and drop
        // Spring's DLT headers: the next failure, if any, adds fresh ones.
        RecordHeaders headers = new RecordHeaders();
        for (Header header : record.headers()) {
            if (!header.key().startsWith(DLT_HEADER_PREFIX)
                    && !header.key().equals(DeadLetterRedrivePolicy.REDRIVE_COUNT_HEADER)) {
                headers.add(header);
            }
        }
        headers.add(DeadLetterRedrivePolicy.REDRIVE_COUNT_HEADER,
                Integer.toString(round).getBytes(StandardCharsets.UTF_8));

        // Same key -> same partition as the original event (keyed by submissionId), so ordering per wire holds.
        // Wait for the broker: the DLQ offset is committed only after the event is safely back.
        template.send(new ProducerRecord<>(target, null, record.key(), record.value(), headers))
                .get(SEND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        log.info("Redrove dead-lettered record key={} from partition={} offset={} to {} (round {} of {})",
                record.key(), record.partition(), record.offset(), target, round, maxRedrives);
    }
}
