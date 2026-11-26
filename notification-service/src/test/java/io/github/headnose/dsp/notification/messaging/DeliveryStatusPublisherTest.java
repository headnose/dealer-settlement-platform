package io.github.headnose.dsp.notification.messaging;

import io.github.headnose.dsp.notification.config.KafkaTopics;
import io.github.headnose.dsp.notification.model.DeliveryStatus;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DeliveryStatusPublisherTest {

    @Test
    void recordIsKeyedBySubmissionIdAndCarriesTheIdsAsHeaders() {
        DeliveryStatusEvent event = new DeliveryStatusEvent("EVT-2", DeliveryStatusEvent.EMAIL_DELIVERY_COMPLETED,
                "SUB-1", "LN-1001", "REC-1", DeliveryStatus.SENT, null, LocalDateTime.of(2026, 11, 25, 18, 0));

        ProducerRecord<String, Object> record = DeliveryStatusPublisher.toRecord(event);

        assertThat(record.topic()).isEqualTo(KafkaTopics.DELIVERY_STATUS);
        assertThat(record.key()).isEqualTo("SUB-1");
        assertThat(record.value()).isSameAs(event);
        // G3: same header names as PWT's email-delivery records (blueprint example)
        assertThat(header(record, "submissionId")).isEqualTo("SUB-1");
        assertThat(header(record, "loanId")).isEqualTo("LN-1001");
    }

    private static String header(ProducerRecord<?, ?> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}