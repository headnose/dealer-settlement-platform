package io.github.headnose.dsp.wire;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.headnose.dsp.wire.config.KafkaTopics;
import io.github.headnose.dsp.wire.entity.WireRecipient;
import io.github.headnose.dsp.wire.entity.WireSubmission;
import io.github.headnose.dsp.wire.model.DeliveryStatus;
import io.github.headnose.dsp.wire.model.RecipientType;
import io.github.headnose.dsp.wire.model.SubmissionStatus;
import io.github.headnose.dsp.wire.model.WireType;
import io.github.headnose.dsp.wire.repository.WireSubmissionRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.support.KafkaHeaders;
import org.testcontainers.kafka.KafkaContainer;

/** BlastMail's side is simulated: we publish delivery-status JSON and watch MySQL change. */
@SpringBootTest
@Import({MySqlTestContainerConfig.class, KafkaTestContainerConfig.class})
class DeliveryStatusIntegrationTest {

    private static final LocalDateTime PROCESSED = LocalDateTime.of(2026, 11, 21, 14, 35, 41);

    @Autowired
    private WireSubmissionRepository submissionRepository;

    @Autowired
    private KafkaContainer kafka;

    @Test
    void fourSentResultsMarkEveryRecipientAndTheSubmissionSent() throws Exception {
        WireSubmission submission = savedQueuedSubmission();
        String submissionId = submission.getSubmissionId();

        List<String> payloads = submission.getRecipients().stream()
                .map(r -> statusJson(submissionId, r.getRecipientId(), "SENT"))
                .toList();
        publish(KafkaTopics.DELIVERY_STATUS, submissionId, payloads);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            WireSubmission reloaded = submissionRepository.findWithRecipientsBySubmissionId(submissionId).orElseThrow();
            assertThat(reloaded.getStatus()).isEqualTo(SubmissionStatus.SENT);
            assertThat(reloaded.getRecipients()).allSatisfy(r -> {
                assertThat(r.getDeliveryStatus()).isEqualTo(DeliveryStatus.SENT);
                assertThat(r.getLastUpdated()).isEqualTo(PROCESSED);
            });
        });
    }

    @Test
    void unprocessableEventsGoToTheDlqUnchanged() throws Exception {
        String key = "SUB-DLQ-" + UUID.randomUUID();
        String unknownRecipient = statusJson(key, "REC-DOES-NOT-EXIST", "SENT");
        String notJson = "this is not json";
        publish(KafkaTopics.DELIVERY_STATUS, key, List.of(unknownRecipient, notJson));

        List<ConsumerRecord<String, String>> dead = readRecords(KafkaTopics.DELIVERY_STATUS_DLQ, key, 2);

        // Exact original text, so support can inspect and replay it
        assertThat(dead).extracting(ConsumerRecord::value).containsExactlyInAnyOrder(unknownRecipient, notJson);
        // Spring records why it failed in headers
        assertThat(dead).allSatisfy(r ->
                assertThat(r.headers().lastHeader(KafkaHeaders.DLT_EXCEPTION_FQCN)).isNotNull());
    }

    // --- helpers ---

    private WireSubmission savedQueuedSubmission() {
        WireSubmission draft = WireSubmission.draft("LN-1001", WireType.SETTLEMENT,
                "<settlementWire/>", LocalDateTime.of(2026, 11, 21, 14, 30, 25));
        draft.addRecipient(RecipientType.DEALER, "Riverbend Motors", "accounts@riverbend-motors.example.com");
        draft.addRecipient(RecipientType.DEALER_BANK_CONTACT, "Riverbend Community Bank", "wires@riverbend-bank.example.com");
        draft.addRecipient(RecipientType.AUDIT_DISTRIBUTION, "Compliance Audit Team", "compliance-audit@example.com");
        draft.addRecipient(RecipientType.INTERNAL_OPS_DISTRIBUTION, "Treasury Operations", "treasury-ops@example.com");
        WireSubmission saved = submissionRepository.save(draft);
        submissionRepository.transitionStatus(saved.getSubmissionId(), SubmissionStatus.DRAFT, SubmissionStatus.QUEUED);
        return saved;
    }

    private static String statusJson(String submissionId, String recipientId, String deliveryStatus) {
        return """
                {"eventId":"EVT-%s","eventType":"EMAIL_DELIVERY_COMPLETED","submissionId":"%s",\
                "loanId":"LN-1001","recipientId":"%s","deliveryStatus":"%s","errorMessage":null,\
                "processedTime":"2026-11-21T14:35:41"}"""
                .formatted(UUID.randomUUID(), submissionId, recipientId, deliveryStatus);
    }

    /** Publishes like BlastMail will: key = submissionId, value = JSON text. */
    private void publish(String topic, String key, List<String> values) throws Exception {
        Map<String, Object> props = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            for (String value : values) {
                producer.send(new ProducerRecord<>(topic, key, value)).get();
            }
        }
    }

    private List<ConsumerRecord<String, String>> readRecords(String topic, String key, int expected) {
        Map<String, Object> props = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "it-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(topic));
            long deadline = System.currentTimeMillis() + 20_000;
            while (found.size() < expected && System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (key.equals(record.key())) {
                        found.add(record);
                    }
                }
            }
        }
        return found;
    }
}