package io.github.headnose.dsp.notification;

import io.github.headnose.dsp.notification.config.KafkaTopics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.TopicBuilder;
import org.testcontainers.kafka.KafkaContainer;

@TestConfiguration(proxyBeanMethods = false)
public class KafkaTestContainerConfig {

    // Same image as docker-compose.yml. @ServiceConnection sets
    // spring.kafka.bootstrap-servers to the container's random port.
    @Bean
    @ServiceConnection
    KafkaContainer kafkaContainer() {
        return new KafkaContainer("apache/kafka:3.9.1");
    }

    // PWT owns email-delivery in production (D16). PWT is not running in these tests,
    // so declare it here with the real partition count instead of letting the broker auto-create it.
    @Bean
    NewTopic emailDeliveryTopic() {
        return TopicBuilder.name(KafkaTopics.EMAIL_DELIVERY).partitions(6).replicas(1).build();
    }
}