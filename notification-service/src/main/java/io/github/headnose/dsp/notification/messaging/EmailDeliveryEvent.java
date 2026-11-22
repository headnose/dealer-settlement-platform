package io.github.headnose.dsp.notification.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.LocalDateTime;

/**
 * Email request for one recipient, consumed from email-delivery. BlastMail's own copy (D10):
 * field names exactly as the blueprint's example event. Key = submissionId.
 * recipientId is the idempotency key (B3).
 *
 * wireType is a String here (PWT uses an enum): BlastMail only prints it in the email body,
 * so a new wire type added in PWT must not make BlastMail reject the event (tolerant reader).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EmailDeliveryEvent(
        String eventId,
        String eventType,
        String submissionId,
        String loanId,
        String recipientId,
        String recipientName,
        String email,
        String wireType,
        String subject,
        String attachmentPath,   // null until the MinIO step (D15)
        LocalDateTime createdTime) {
}