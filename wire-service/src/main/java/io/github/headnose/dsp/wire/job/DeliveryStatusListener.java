package io.github.headnose.dsp.wire.job;

import io.github.headnose.dsp.wire.config.KafkaTopics;
import io.github.headnose.dsp.wire.logging.MdcKeys;
import io.github.headnose.dsp.wire.messaging.DeliveryStatusEvent;
import io.github.headnose.dsp.wire.service.DeliveryStatusService;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Blueprint "PWT Job Module": consumes BlastMail's results from delivery-status (Workflow 4).
 * Thin adapter: parse, then hand over. All rules live in DeliveryStatusService.
 * O3: submissionId + loanId from the event go into the MDC while it is applied (blueprint example log:
 * "Wire submission status updated to SENT" with both ids). The error handler logs after this method
 * returned, so retry/DLQ lines have the traceId but not these ids (known limitation).
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
        try (MDC.MDCCloseable submission = putIfPresent(MdcKeys.SUBMISSION_ID,
                event == null ? null : event.submissionId());
             MDC.MDCCloseable loan = putIfPresent(MdcKeys.LOAN_ID, event == null ? null : event.loanId())) {
            deliveryStatusService.apply(event);
        }
    }

    /** null = nothing to put; try-with-resources skips a null resource. */
    private static MDC.MDCCloseable putIfPresent(String key, String value) {
        return value == null ? null : MDC.putCloseable(key, value);
    }
}