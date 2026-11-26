package io.github.headnose.dsp.notification.messaging;

import io.github.headnose.dsp.notification.config.KafkaTopics;
import io.github.headnose.dsp.notification.logging.MdcKeys;
import io.github.headnose.dsp.notification.service.EmailDeliveryService;
import io.github.headnose.dsp.notification.service.InvalidEmailDeliveryEventException;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * BlastMail's consumer of PWT's email requests (Workflow 4). All instances share group "blastmail",
 * so each event is handled by one instance. Thin adapter: parse, then hand over.
 * O3: submissionId + loanId from the event go into the MDC while it is delivered (Redis claim, S3 download,
 * SMTP, result publish all run on this listener thread). The error handler logs after this method returned,
 * so retry/DLQ lines have the traceId but not these ids (known limitation, same as wire-service's job).
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
        try (MDC.MDCCloseable submission = putIfPresent(MdcKeys.SUBMISSION_ID, event.submissionId());
             MDC.MDCCloseable loan = putIfPresent(MdcKeys.LOAN_ID, event.loanId())) {
            emailDeliveryService.deliver(event);
        }
    }

    /** null = nothing to put; try-with-resources skips a null resource. */
    private static MDC.MDCCloseable putIfPresent(String key, String value) {
        return value == null ? null : MDC.putCloseable(key, value);
    }
}