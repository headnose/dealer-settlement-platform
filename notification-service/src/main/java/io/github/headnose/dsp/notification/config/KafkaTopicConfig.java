package io.github.headnose.dsp.notification.config;

import java.time.Duration;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declares the topics BlastMail PRODUCES to (D16): Boot's KafkaAdmin creates them on startup
 * if they are missing. Main topic 7 days retention, DLQ 30 days (blueprint).
 */
@Configuration
public class KafkaTopicConfig {

    private static final String SEVEN_DAYS_MS = String.valueOf(Duration.ofDays(7).toMillis());
    private static final String THIRTY_DAYS_MS = String.valueOf(Duration.ofDays(30).toMillis());

    private final int partitions;
    private final int replicationFactor;

    public KafkaTopicConfig(@Value("${topics.partitions}") int partitions,
                            @Value("${topics.replication-factor}") int replicationFactor) {
        this.partitions = partitions;
        this.replicationFactor = replicationFactor;
    }

    @Bean
    public NewTopic deliveryStatusTopic() {
        return TopicBuilder.name(KafkaTopics.DELIVERY_STATUS)
                .partitions(partitions)
                .replicas(replicationFactor)
                .config(TopicConfig.RETENTION_MS_CONFIG, SEVEN_DAYS_MS)
                .build();
    }

    @Bean
    public NewTopic emailDeliveryDlqTopic() {
        return TopicBuilder.name(KafkaTopics.EMAIL_DELIVERY_DLQ)
                .partitions(partitions)
                .replicas(replicationFactor)
                .config(TopicConfig.RETENTION_MS_CONFIG, THIRTY_DAYS_MS)
                .build();
    }
}