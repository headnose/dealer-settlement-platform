package io.github.headnose.dsp.notification;

import io.github.headnose.dsp.notification.config.KafkaTopics;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SMTP unreachable (nothing listens on port 1): retries -> FAILED status -> DLQ (B5).
 * Separate context on purpose: no GreenMail, so this class gets its own containers.
 */
@Import({KafkaTestContainerConfig.class, RedisTestContainerConfig.class})
@SpringBootTest(properties = {"spring.mail.host=127.0.0.1", "spring.mail.port=1"})
class EmailDeliveryFailureIntegrationTest {

    @Autowired private KafkaContainer kafka;
    @Autowired private StringRedisTemplate redis;
    @Autowired private JsonMapper jsonMapper;

    @Test
    void smtpDown_publishesFailed_deadLettersEvent_andReleasesRedisKey() throws Exception {
        String submissionId = "SUB-" + UUID.randomUUID();
        String recipientId = "REC-" + UUID.randomUUID();
        String event = KafkaTestSupport.emailDeliveryJson(submissionId, recipientId,
                "rcpt-" + UUID.randomUUID() + "@riverbend-motors.example.com");

        KafkaTestSupport.send(kafka.getBootstrapServers(), KafkaTopics.EMAIL_DELIVERY, submissionId, event);

        ConsumerRecord<String, String> status = KafkaTestSupport.awaitRecords(
                kafka.getBootstrapServers(), KafkaTopics.DELIVERY_STATUS, submissionId, 1).get(0);
        Map<String, Object> json = parse(status.value());
        assertThat(json)
                .containsEntry("eventType", "EMAIL_DELIVERY_FAILED")
                .containsEntry("recipientId", recipientId)
                .containsEntry("deliveryStatus", "FAILED");
        assertThat((String) json.get("errorMessage")).isNotBlank();

        ConsumerRecord<String, String> dead = KafkaTestSupport.awaitRecords(
                kafka.getBootstrapServers(), KafkaTopics.EMAIL_DELIVERY_DLQ, submissionId, 1).get(0);
        assertThat(dead.value()).isEqualTo(event);   // original text, ready for replay

        // Released after the last failed attempt, so a DLQ replay can send
        assertThat(redis.opsForValue().get("blastmail:delivery:" + recipientId)).isNull();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(String json) {
        return jsonMapper.readValue(json, Map.class);
    }
}