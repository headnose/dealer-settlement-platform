package io.github.headnose.dsp.notification.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.junit.jupiter.api.Test;

class KafkaTopicConfigTest {

    private final KafkaTopicConfig config = new KafkaTopicConfig(6, 3, 2);

    @Test
    void deliveryStatusTopicRequiresTwoInSyncReplicas() {
        NewTopic topic = config.deliveryStatusTopic();

        assertThat(topic.numPartitions()).isEqualTo(6);
        assertThat(topic.replicationFactor()).isEqualTo((short) 3);
        assertThat(topic.configs()).containsEntry(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, "2");
    }

    @Test
    void emailDeliveryDlqTopicRequiresTwoInSyncReplicas() {
        assertThat(config.emailDeliveryDlqTopic().configs())
                .containsEntry(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, "2");
    }
}
