package io.github.headnose.dsp.wire.job;

import io.github.headnose.dsp.wire.config.KafkaTopics;
import io.github.headnose.dsp.wire.messaging.DeliveryStatusEvent;
import io.github.headnose.dsp.wire.service.DeliveryStatusService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Blueprint "PWT Job Module": consumes BlastMail's results from delivery-status (Workflow 4).
 * Thin adapter: parse, then hand over. All rules live in DeliveryStatusService.
 */
@Component
public class DeliveryStatusListener {

    private final JsonMapper jsonMapper;
    private final DeliveryStatusService deliveryStatusService;

    public DeliveryStatusListener(JsonMapper jsonMapper, DeliveryStatusService deliveryStatusService) {
        this.jsonMapper = jsonMapper;
        this.deliveryStatusService = deliveryStatusService;
    }

    @KafkaListener(topics = KafkaTopics.DELIVERY_STATUS, groupId = "pwt-delivery-status")
    public void onDeliveryStatus(String payload) {
        // Bad JSON / unknown enum -> JacksonException -> not retryable -> DLQ (D20)
        DeliveryStatusEvent event = jsonMapper.readValue(payload, DeliveryStatusEvent.class);
        deliveryStatusService.apply(event);
    }
}