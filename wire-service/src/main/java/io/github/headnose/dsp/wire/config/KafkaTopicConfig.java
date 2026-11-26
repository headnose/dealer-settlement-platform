package io.github.headnose.dsp.wire.config;
import java.time.Duration;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declares the topics PWT produces to. Spring's KafkaAdmin creates them at startup
 * if they don't exist (it never deletes or shrinks an existing topic).
 */
@Configuration
public class KafkaTopicConfig {

    static final Duration MAIN_TOPIC_RETENTION = Duration.ofDays(7);
    static final Duration DLQ_RETENTION = Duration.ofDays(30);

    private final int partitions;
    private final int replicationFactor;
    private final int minInsyncReplicas;

    public KafkaTopicConfig(@Value("${topics.partitions}") int partitions,
                            @Value("${topics.replication-factor}") int replicationFactor,
                            @Value("${topics.min-insync-replicas}") int minInsyncReplicas) {
        this.partitions = partitions;
        this.replicationFactor = replicationFactor;
        this.minInsyncReplicas = minInsyncReplicas;
    }

    @Bean
    NewTopic emailDeliveryTopic() {
        return topic(KafkaTopics.EMAIL_DELIVERY, MAIN_TOPIC_RETENTION);
    }

    // Same partition count as delivery-status: the dead-letter recoverer writes a failed
    // record to the SAME partition number it came from, so the DLQ needs at least as many.
    @Bean
    NewTopic deliveryStatusDlqTopic() {
        return topic(KafkaTopics.DELIVERY_STATUS_DLQ, DLQ_RETENTION);
    }

    private NewTopic topic(String name, Duration retention) {
        return TopicBuilder.name(name)
                .partitions(partitions)
                .replicas(replicationFactor)
                .config(TopicConfig.RETENTION_MS_CONFIG, String.valueOf(retention.toMillis()))
                // acks=all only waits for the replicas currently in sync. With min ISR 2 (of 3), a write
                // is acknowledged only once 2 copies have it; with fewer in sync the producer gets an error.
                .config(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, String.valueOf(minInsyncReplicas))
                .build();
    }
}