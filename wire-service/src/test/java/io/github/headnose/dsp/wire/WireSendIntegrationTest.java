package io.github.headnose.dsp.wire;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.headnose.dsp.wire.config.KafkaTopics;
import io.github.headnose.dsp.wire.entity.WireRecipient;
import io.github.headnose.dsp.wire.entity.WireSubmission;
import io.github.headnose.dsp.wire.model.RecipientType;
import io.github.headnose.dsp.wire.model.SubmissionStatus;
import io.github.headnose.dsp.wire.model.WireType;
import io.github.headnose.dsp.wire.repository.WireSubmissionRepository;
import io.github.headnose.dsp.wire.service.WireSendService;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.kafka.KafkaContainer;
import software.amazon.awssdk.services.s3.S3Client;
import tools.jackson.databind.json.JsonMapper;

/**
 * Send a stored DRAFT through the real service, real MySQL and real Kafka, then read
 * email-delivery as raw JSON, exactly as BlastMail will. Contract test for the event format.
 * Also checks the statement really landed in SeaweedFS at the attachmentPath (D23, D24).
 *
 * <p>Unique loanId per run: the MySQL container is shared with other @SpringBootTest classes
 * (e.g. DeliveryStatusIntegrationTest), and the duplicate guard (D12) rejects a second send for
 * a loan that already has a QUEUED/SENT submission, so a fixed loanId depends on test order.
 */
@SpringBootTest
@Import({MySqlTestContainerConfig.class, KafkaTestContainerConfig.class, SeaweedFsTestContainerConfig.class})
class WireSendIntegrationTest {

    @Autowired
    private WireSendService wireSendService;

    @Autowired
    private WireSubmissionRepository submissionRepository;

    @Autowired
    private KafkaContainer kafka;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private S3Client s3Client;

    @Test
    void sendPublishesOneBlueprintShapedEventPerRecipientAndQueuesTheSubmission() {
        String loanId = "LN-IT-" + UUID.randomUUID().toString().substring(0, 8);
        WireSubmission draft = savedDraftWithFourRecipients(loanId);
        String submissionId = draft.getSubmissionId();
        List<String> recipientIds = draft.getRecipients().stream()
                .map(WireRecipient::getRecipientId)
                .toList();

        wireSendService.send(submissionId);

        List<ConsumerRecord<String, String>> records = readEmailDeliveryRecords(submissionId, 4);
        assertThat(records).hasSize(4);

        // Key = submissionId, so all four land on the same partition (ordering per submission)
        assertThat(records).allSatisfy(r -> assertThat(r.key()).isEqualTo(submissionId));
        assertThat(records).extracting(ConsumerRecord::partition).containsOnly(records.get(0).partition());

        // No Java class names in headers: the JSON is the contract
        assertThat(records).allSatisfy(r -> assertThat(r.headers().lastHeader("__TypeId__")).isNull());

        List<Map<String, Object>> events = records.stream().map(r -> toMap(r.value())).toList();

        // Exactly the blueprint's field names, nothing more, nothing less
        assertThat(events).allSatisfy(event -> assertThat(event.keySet()).containsExactlyInAnyOrder(
                "eventId", "eventType", "submissionId", "loanId", "recipientId", "recipientName",
                "email", "wireType", "subject", "attachmentPath", "createdTime"));

        assertThat(events).extracting(e -> e.get("recipientId"))
                .containsExactlyInAnyOrderElementsOf(recipientIds);
        assertThat(events).allSatisfy(event -> {
            assertThat(event.get("eventType")).isEqualTo("EMAIL_DELIVERY_REQUESTED");
            assertThat(event.get("submissionId")).isEqualTo(submissionId);
            assertThat(event.get("loanId")).isEqualTo(loanId);
            assertThat(event.get("wireType")).isEqualTo("SETTLEMENT");
            assertThat(event.get("subject")).isEqualTo("Settlement Wire Statement - " + loanId);
            assertThat(event.get("attachmentPath"))
                    .isEqualTo("s3://wire-statements/" + submissionId + "/" + loanId + ".xml");
            assertThat((String) event.get("createdTime")).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}");
        });

        // The statement BlastMail will attach is really in the bucket, with the submission's content
        String stored = s3Client.getObjectAsBytes(request -> request
                        .bucket("wire-statements")
                        .key(submissionId + "/" + loanId + ".xml"))
                .asUtf8String();
        assertThat(stored).isEqualTo("<settlementWire/>");

        // The claim really committed in MySQL
        assertThat(submissionRepository.findById(submissionId))
                .get()
                .extracting(WireSubmission::getStatus)
                .isEqualTo(SubmissionStatus.QUEUED);
    }

    private WireSubmission savedDraftWithFourRecipients(String loanId) {
        WireSubmission draft = WireSubmission.draft(loanId, WireType.SETTLEMENT,
                "<settlementWire/>", LocalDateTime.of(2026, 11, 21, 14, 30, 25));
        draft.addRecipient(RecipientType.DEALER,
                "Riverbend Motors", "accounts@riverbend-motors.example.com");
        draft.addRecipient(RecipientType.DEALER_BANK_CONTACT,
                "Riverbend Community Bank", "wires@riverbend-bank.example.com");
        draft.addRecipient(RecipientType.AUDIT_DISTRIBUTION,
                "Compliance Audit Team", "compliance-audit@example.com");
        draft.addRecipient(RecipientType.INTERNAL_OPS_DISTRIBUTION,
                "Treasury Operations", "treasury-ops@example.com");
        return submissionRepository.save(draft);
    }

    /** Polls email-delivery from the beginning until `expected` records for this submission arrive (max 10 s). */
    private List<ConsumerRecord<String, String>> readEmailDeliveryRecords(String submissionId, int expected) {
        Map<String, Object> props = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "it-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(KafkaTopics.EMAIL_DELIVERY));
            long deadline = System.currentTimeMillis() + 10_000;
            while (found.size() < expected && System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (submissionId.equals(record.key())) {
                        found.add(record);
                    }
                }
            }
        }
        return found;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(String json) {
        return jsonMapper.readValue(json, Map.class);
    }
}