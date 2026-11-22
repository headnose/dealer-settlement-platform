package io.github.headnose.dsp.notification.messaging;

import io.github.headnose.dsp.notification.config.KafkaTopics;
import io.github.headnose.dsp.notification.service.EmailDeliveryService;
import io.github.headnose.dsp.notification.service.InvalidEmailDeliveryEventException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * BlastMail's consumer of PWT's email requests (Workflow 4). All instances share group "blastmail",
 * so each event is handled by one instance. Thin adapter: parse, then hand over.
 */
@Component
public class EmailDeliveryListener {

    private final JsonMapper jsonMapper;
    private final EmailDeliveryService emailDeliveryService;

    public EmailDeliveryListener(JsonMapper jsonMapper, EmailDeliveryService emailDeliveryService) {
        this.jsonMapper = jsonMapper;
        this.emailDeliveryService = emailDeliveryService;
    }

    @KafkaListener(topics = KafkaTopics.EMAIL_DELIVERY, groupId = "blastmail")
    public void onEmailDelivery(String payload) {
        // Bad JSON -> JacksonException -> not retryable -> DLQ (B5)
        EmailDeliveryEvent event = jsonMapper.readValue(payload, EmailDeliveryEvent.class);
        if (event == null) {
            // the JSON text "null" parses to null; without this it would become a retried NullPointerException
            throw new InvalidEmailDeliveryEventException("Empty event");
        }
        emailDeliveryService.deliver(event);
    }
}