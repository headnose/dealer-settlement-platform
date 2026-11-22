package io.github.headnose.dsp.wire.messaging;

import io.github.headnose.dsp.wire.model.WireType;
import java.time.LocalDateTime;

/**
 * One email request per recipient, published to email-delivery (blueprint Kafka section).
 * Field names match the blueprint's example event exactly. Key = submissionId.
 * recipientId is BlastMail's idempotency key and PWT's lookup key for the status update.
 */
public record EmailDeliveryEvent(
        String eventId,
        String eventType,
        String submissionId,
        String loanId,
        String recipientId,
        String recipientName,
        String email,
        WireType wireType,
        String subject,
        String attachmentPath,   // null until the MinIO step (D15)
        LocalDateTime createdTime) {

    public static final String EMAIL_DELIVERY_REQUESTED = "EMAIL_DELIVERY_REQUESTED";
}