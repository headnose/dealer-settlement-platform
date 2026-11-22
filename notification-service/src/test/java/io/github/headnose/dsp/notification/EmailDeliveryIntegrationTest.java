package io.github.headnose.dsp.notification;

import com.icegreen.greenmail.util.GreenMail;
import io.github.headnose.dsp.notification.config.KafkaTopics;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.kafka.KafkaContainer;
import software.amazon.awssdk.services.s3.S3Client;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Workflow 4, BlastMail: real Kafka, real Redis, real SMTP (GreenMail), real S3 (SeaweedFS). */
@Import({KafkaTestContainerConfig.class, RedisTestContainerConfig.class, GreenMailTestConfig.class,
        SeaweedFsTestContainerConfig.class})
@SpringBootTest
class EmailDeliveryIntegrationTest {

    @Autowired private KafkaContainer kafka;
    @Autowired private GreenMail greenMail;
    @Autowired private StringRedisTemplate redis;
    @Autowired private JsonMapper jsonMapper;
    @Autowired private S3Client s3Client;

    @Test
    void event_sendsOneEmailWithStatementAttached_publishesSent_andMarksRedis() throws Exception {
        String submissionId = "SUB-" + UUID.randomUUID();
        String recipientId = "REC-" + UUID.randomUUID();
        String email = uniqueEmail();
        String statement = "<settlementWire><loanId>LN-1001</loanId><submissionId>" + submissionId
                + "</submissionId></settlementWire>";
        String path = StorageTestSupport.putStatement(s3Client, submissionId, statement);

        KafkaTestSupport.send(kafka.getBootstrapServers(), KafkaTopics.EMAIL_DELIVERY, submissionId,
                KafkaTestSupport.emailDeliveryJson(submissionId, recipientId, email, path));

        MimeMessage message = awaitEmails(email, 1)[0];
        assertThat(message.getSubject()).isEqualTo("Settlement Wire Statement - LN-1001");
        assertThat(message.getFrom()[0].toString()).isEqualTo("blastmail@example.com");
        assertThat(StorageTestSupport.plainText(message))
                .contains("Submission ID: " + submissionId)
                .contains("The wire statement is attached (LN-1001.xml).");

        // D25: the statement PWT stored is attached, byte for byte
        Part attachment = StorageTestSupport.findPart(message, "LN-1001.xml");
        assertThat(attachment).isNotNull();
        assertThat(Part.ATTACHMENT).isEqualToIgnoringCase(attachment.getDisposition());
        assertThat(new String(attachment.getInputStream().readAllBytes(), StandardCharsets.UTF_8))
                .isEqualTo(statement);

        ConsumerRecord<String, String> status = KafkaTestSupport.awaitRecords(
                kafka.getBootstrapServers(), KafkaTopics.DELIVERY_STATUS, submissionId, 1).get(0);
        assertThat(status.headers().lastHeader("__TypeId__")).isNull();   // JSON is the contract, not our class names

        Map<String, Object> json = parse(status.value());
        assertThat(json).containsOnlyKeys("eventId", "eventType", "submissionId", "loanId", "recipientId",
                "deliveryStatus", "errorMessage", "processedTime");
        assertThat(json)
                .containsEntry("eventType", "EMAIL_DELIVERY_COMPLETED")
                .containsEntry("submissionId", submissionId)
                .containsEntry("loanId", "LN-1001")
                .containsEntry("recipientId", recipientId)
                .containsEntry("deliveryStatus", "SENT")
                .containsEntry("errorMessage", null);
        assertThat((String) json.get("processedTime")).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}");

        assertThat(redis.opsForValue().get("blastmail:delivery:" + recipientId)).isEqualTo("SENT");
    }

    @Test
    void sameEventTwice_sendsOneEmail_butReportsSentBothTimes() throws Exception {
        String submissionId = "SUB-" + UUID.randomUUID();
        String email = uniqueEmail();
        String path = StorageTestSupport.putStatement(s3Client, submissionId, "<settlementWire/>");
        String event = KafkaTestSupport.emailDeliveryJson(submissionId, "REC-" + UUID.randomUUID(), email, path);

        KafkaTestSupport.send(kafka.getBootstrapServers(), KafkaTopics.EMAIL_DELIVERY, submissionId, event);
        KafkaTestSupport.send(kafka.getBootstrapServers(), KafkaTopics.EMAIL_DELIVERY, submissionId, event);

        // Two results means both events were fully processed, so the email count below is final
        List<ConsumerRecord<String, String>> statuses = KafkaTestSupport.awaitRecords(
                kafka.getBootstrapServers(), KafkaTopics.DELIVERY_STATUS, submissionId, 2);
        assertThat(statuses).allSatisfy(record ->
                assertThat(parse(record.value())).containsEntry("deliveryStatus", "SENT"));
        assertThat(greenMail.getReceivedMessagesForDomain(email)).hasSize(1);
    }

    @Test
    void missingStatement_publishesFailed_deadLettersEvent_sendsNoEmail_andReleasesRedisKey() throws Exception {
        // Valid path, but nothing was uploaded: NoSuchKey -> not retryable -> FAILED + DLQ (D26)
        String submissionId = "SUB-" + UUID.randomUUID();
        String recipientId = "REC-" + UUID.randomUUID();
        String email = uniqueEmail();
        String event = KafkaTestSupport.emailDeliveryJson(submissionId, recipientId, email,
                KafkaTestSupport.attachmentPath(submissionId));

        KafkaTestSupport.send(kafka.getBootstrapServers(), KafkaTopics.EMAIL_DELIVERY, submissionId, event);

        Map<String, Object> status = parse(KafkaTestSupport.awaitRecords(
                kafka.getBootstrapServers(), KafkaTopics.DELIVERY_STATUS, submissionId, 1).get(0).value());
        assertThat(status)
                .containsEntry("eventType", "EMAIL_DELIVERY_FAILED")
                .containsEntry("recipientId", recipientId)
                .containsEntry("deliveryStatus", "FAILED");
        assertThat((String) status.get("errorMessage")).isNotBlank();

        ConsumerRecord<String, String> dead = KafkaTestSupport.awaitRecords(
                kafka.getBootstrapServers(), KafkaTopics.EMAIL_DELIVERY_DLQ, submissionId, 1).get(0);
        assertThat(dead.value()).isEqualTo(event);   // original text: upload the statement, then replay

        assertThat(greenMail.getReceivedMessagesForDomain(email)).isEmpty();
        assertThat(redis.opsForValue().get("blastmail:delivery:" + recipientId)).isNull();
    }

    @Test
    void badJson_goesToDlqUnchanged_withoutRetry() throws Exception {
        String key = "SUB-bad-" + UUID.randomUUID();

        KafkaTestSupport.send(kafka.getBootstrapServers(), KafkaTopics.EMAIL_DELIVERY, key, "this is not json");

        ConsumerRecord<String, String> dead = KafkaTestSupport.awaitRecords(
                kafka.getBootstrapServers(), KafkaTopics.EMAIL_DELIVERY_DLQ, key, 1).get(0);
        assertThat(dead.value()).isEqualTo("this is not json");
        assertThat(dead.headers().lastHeader("kafka_dlt-exception-fqcn")).isNotNull();
    }

    private MimeMessage[] awaitEmails(String email, int count) {
        await().atMost(Duration.ofSeconds(30))
                .until(() -> greenMail.getReceivedMessagesForDomain(email).length >= count);
        return greenMail.getReceivedMessagesForDomain(email);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(String json) {
        return jsonMapper.readValue(json, Map.class);
    }

    private static String uniqueEmail() {
        return "rcpt-" + UUID.randomUUID() + "@riverbend-motors.example.com";
    }
}