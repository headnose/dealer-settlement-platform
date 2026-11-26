package io.github.headnose.dsp.wire.messaging;

import io.github.headnose.dsp.wire.config.KafkaTopics;
import io.github.headnose.dsp.wire.model.WireType;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EmailDeliveryPublisherTest {

    @Test
    void recordIsKeyedBySubmissionIdAndCarriesTheIdsAsHeaders() {
        EmailDeliveryEvent event = new EmailDeliveryEvent("EVT-1", EmailDeliveryEvent.EMAIL_DELIVERY_REQUESTED,
                "SUB-1", "LN-1001", "REC-1", "Riverbend Motors", "accounts@riverbend-motors.example.com",
                WireType.SETTLEMENT, "Settlement Wire Statement - LN-1001",
                "s3://wire-statements/SUB-1/LN-1001.xml", LocalDateTime.of(2026, 11, 25, 18, 0));

        ProducerRecord<String, EmailDeliveryEvent> record = EmailDeliveryPublisher.toRecord(event);

        assertThat(record.topic()).isEqualTo(KafkaTopics.EMAIL_DELIVERY);
        assertThat(record.key()).isEqualTo("SUB-1");
        assertThat(record.value()).isSameAs(event);
        // G3: header names exactly as in the blueprint's example (written out, not the constants)
        assertThat(header(record, "submissionId")).isEqualTo("SUB-1");
        assertThat(header(record, "loanId")).isEqualTo("LN-1001");
    }

    private static String header(ProducerRecord<?, ?> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}