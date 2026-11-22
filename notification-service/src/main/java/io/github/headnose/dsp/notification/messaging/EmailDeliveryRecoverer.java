package io.github.headnose.dsp.notification.messaging;

import io.github.headnose.dsp.notification.config.KafkaTopics;
import io.github.headnose.dsp.notification.service.DeliveryFailedException;
import io.github.headnose.dsp.notification.service.EmailDeliveryService;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Last stop for an email-delivery record that failed every attempt (B5, D26).
 * Known "no email went out" failure (DeliveryFailedException: SMTP, statement missing, storage
 * down): publish FAILED so PWT shows it (D19), THEN dead-letter the original event so
 * Support / Retry Job can replay it (its Redis key was released, so a replay sends).
 * Anything else: dead-letter only (no trustworthy result to report).
 */
@Component
public class EmailDeliveryRecoverer implements ConsumerRecordRecoverer {

    private static final Logger log = LoggerFactory.getLogger(EmailDeliveryRecoverer.class);

    private final DeadLetterPublishingRecoverer deadLetterPublisher;
    private final JsonMapper jsonMapper;
    private final EmailDeliveryService emailDeliveryService;

    public EmailDeliveryRecoverer(ProducerFactory<?, ?> producerFactory, JsonMapper jsonMapper,
                                  EmailDeliveryService emailDeliveryService) {
        // The DLQ gets the original JSON text, so it needs a String value serializer instead of
        // the app's JSON serializer. Built here, NOT as a @Bean: a second KafkaTemplate bean
        // would make Boot back off from creating the main one (same as PWT, D20).
        Map<String, Object> config = new HashMap<>(producerFactory.getConfigurationProperties());
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        KafkaTemplate<String, String> dlqTemplate = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(config));

        // Same partition number as the failed record (why the DLQ has 6 partitions too)
        this.deadLetterPublisher = new DeadLetterPublishingRecoverer(dlqTemplate,
                (record, exception) -> new TopicPartition(KafkaTopics.EMAIL_DELIVERY_DLQ, record.partition()));
        this.jsonMapper = jsonMapper;
        this.emailDeliveryService = emailDeliveryService;
    }

    @Override
    public void accept(ConsumerRecord<?, ?> record, Exception exception) {
        DeliveryFailedException deliveryFailure = findCause(exception, DeliveryFailedException.class);
        if (deliveryFailure != null) {
            // Only reached after deliver() parsed and validated the event, so this parse succeeds
            EmailDeliveryEvent event = jsonMapper.readValue((String) record.value(), EmailDeliveryEvent.class);
            emailDeliveryService.reportFailure(event, deliveryFailure);
        }
        log.warn("Dead-lettering email-delivery record: key={} partition={} offset={}",
                record.key(), record.partition(), record.offset(), exception);
        deadLetterPublisher.accept(record, exception);
    }

    /** The container wraps our exception (ListenerExecutionFailedException), so walk the cause chain. */
    static <T extends Throwable> T findCause(Throwable throwable, Class<T> type) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
        }
        return null;
    }
}