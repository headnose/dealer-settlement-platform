package io.github.headnose.dsp.notification;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

import static org.awaitility.Awaitility.await;

/** Raw String Kafka helpers: tests send and read exact JSON text, like another service would. */
final class KafkaTestSupport {

    private KafkaTestSupport() {
    }

    static void send(String bootstrapServers, String topic, String key, String value) throws Exception {
        Map<String, Object> config = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(config)) {
            producer.send(new ProducerRecord<>(topic, key, value)).get(10, TimeUnit.SECONDS);
        }
    }

    /** Reads the topic from the beginning until `count` records with this key arrive (the broker is shared). */
    static List<ConsumerRecord<String, String>> awaitRecords(String bootstrapServers, String topic,
                                                             String key, int count) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
            consumer.subscribe(List.of(topic));
            await().atMost(Duration.ofSeconds(30)).pollInSameThread().until(() -> {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (key.equals(record.key())) {
                        found.add(record);
                    }
                }
                return found.size() >= count;
            });
        }
        return found;
    }

    /** An email-delivery event exactly as PWT publishes it (blueprint field set, ISO time, null attachment). */
    static String emailDeliveryJson(String submissionId, String recipientId, String email) {
        return """
                {"eventId":"EVT-%s","eventType":"EMAIL_DELIVERY_REQUESTED","submissionId":"%s",\
                "loanId":"LN-1001","recipientId":"%s","recipientName":"Riverbend Motors","email":"%s",\
                "wireType":"SETTLEMENT","subject":"Settlement Wire Statement - LN-1001",\
                "attachmentPath":null,"createdTime":"2026-11-21T20:29:00"}"""
                .formatted(UUID.randomUUID(), submissionId, recipientId, email);
    }
}