package io.github.headnose.dsp.wire.config;

import io.github.headnose.dsp.wire.service.InvalidDeliveryStatusEventException;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.core.JacksonException;

/** Retry-then-DLQ for the delivery-status listener (D20). Boot plugs this handler into the listener container. */
@Configuration
public class KafkaConsumerConfig {

    @Bean
    DefaultErrorHandler deliveryStatusErrorHandler(ProducerFactory<?, ?> producerFactory) {
        // The DLQ gets the original JSON text, so it needs a String value serializer instead of
        // the app's JSON serializer. Created here, NOT as a @Bean: a second KafkaTemplate bean
        // would make Boot back off from creating the main one.
        Map<String, Object> config = new HashMap<>(producerFactory.getConfigurationProperties());
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        KafkaTemplate<String, String> dlqTemplate = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(config));

        // Same partition number as the failed record (why the DLQ has 6 partitions too)
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(dlqTemplate,
                (record, exception) -> new TopicPartition(KafkaTopics.DELIVERY_STATUS_DLQ, record.partition()));

        // 2 retries, 1 s apart (3 attempts total), then the DLQ
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 2L));

        // Retrying can't fix these: straight to the DLQ
        handler.addNotRetryableExceptions(InvalidDeliveryStatusEventException.class, JacksonException.class);
        return handler;
    }
}