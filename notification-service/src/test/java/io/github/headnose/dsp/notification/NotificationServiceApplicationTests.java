package io.github.headnose.dsp.notification;

import io.github.headnose.dsp.notification.config.KafkaTopics;
import org.apache.kafka.clients.admin.TopicDescription;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaAdmin;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Import({KafkaTestContainerConfig.class, RedisTestContainerConfig.class, GreenMailTestConfig.class,
		SeaweedFsTestContainerConfig.class})
@SpringBootTest
class NotificationServiceApplicationTests {

	@Autowired
	private KafkaAdmin kafkaAdmin;

	@Test
	void contextLoads() {
	}

	@Test
	void createsProducedTopicsWithConfiguredPartitionCount() {
		Map<String, TopicDescription> topics = kafkaAdmin.describeTopics(
				KafkaTopics.DELIVERY_STATUS, KafkaTopics.EMAIL_DELIVERY_DLQ);

		assertThat(topics).hasSize(2);
		assertThat(topics.values()).allSatisfy(topic -> assertThat(topic.partitions()).hasSize(6));
	}
}