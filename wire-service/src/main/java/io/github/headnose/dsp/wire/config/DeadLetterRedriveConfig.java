package io.github.headnose.dsp.wire.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Listener container for the DLQ redrive job only. Its own factory (not Boot's), because:
 * manual acks (commit after the event was sent back), and NOT the main listener's error handler,
 * which would dead-letter the redrive job's own failures into the DLQ it is reading.
 */
@Configuration
public class DeadLetterRedriveConfig {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterRedriveConfig.class);

    @Bean
    ConcurrentKafkaListenerContainerFactory<Object, Object> deadLetterRedriveContainerFactory(
            ConsumerFactory<Object, Object> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        factory.getContainerProperties().setObservationEnabled(true);
        // Sending back failed (Kafka unavailable): try again every 10 s, forever. Nothing is skipped; the job
        // just stops moving until Kafka answers. The recoverer is never reached with UNLIMITED_ATTEMPTS.
        factory.setCommonErrorHandler(new DefaultErrorHandler(
                (record, e) -> log.error("DLQ redrive gave up on record at offset {}", record.offset(), e),
                new FixedBackOff(10_000L, FixedBackOff.UNLIMITED_ATTEMPTS)));
        return factory;
    }
}
