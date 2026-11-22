package io.github.headnose.dsp.wire;

import io.github.headnose.dsp.wire.config.KafkaTopics;
import org.apache.kafka.clients.admin.TopicDescription;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaAdmin;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Import({MySqlTestContainerConfig.class, KafkaTestContainerConfig.class})
@SpringBootTest
class WireServiceApplicationTests {

	@Test
	void contextLoads() {
	}

	@Autowired
	private KafkaAdmin kafkaAdmin;

	@Test
	void createsProducedTopicsWithConfiguredPartitionCount() {
		Map<String, TopicDescription> topics = kafkaAdmin.describeTopics(
				KafkaTopics.EMAIL_DELIVERY, KafkaTopics.DELIVERY_STATUS_DLQ);

		assertThat(topics).hasSize(2);
		assertThat(topics.values()).allSatisfy(topic -> assertThat(topic.partitions()).hasSize(6));
	}
}