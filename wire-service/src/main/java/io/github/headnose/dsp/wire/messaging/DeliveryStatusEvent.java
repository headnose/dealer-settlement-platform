package io.github.headnose.dsp.wire.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.headnose.dsp.wire.model.DeliveryStatus;
import java.time.LocalDateTime;

/**
 * BlastMail's result for one recipient, consumed from delivery-status.
 * Field names exactly as the blueprint's example. PWT's own copy (D10).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DeliveryStatusEvent(
        String eventId,
        String eventType,
        String submissionId,
        String loanId,
        String recipientId,
        DeliveryStatus deliveryStatus,
        String errorMessage,
        LocalDateTime processedTime) {
}