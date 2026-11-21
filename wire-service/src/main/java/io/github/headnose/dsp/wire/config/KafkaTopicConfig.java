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

    public KafkaTopicConfig(@Value("${topics.partitions}") int partitions,
                            @Value("${topics.replication-factor}") int replicationFactor) {
        this.partitions = partitions;
        this.replicationFactor = replicationFactor;
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
                .build();
    }
}