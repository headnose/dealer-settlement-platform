package io.github.headnose.dsp.notification.messaging;

import io.github.headnose.dsp.notification.model.DeliveryStatus;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * BlastMail's result for one recipient, published to delivery-status keyed by submissionId.
 * Field names exactly as the blueprint's example event.
 */
public record DeliveryStatusEvent(
        String eventId,
        String eventType,
        String submissionId,
        String loanId,
        String recipientId,
        DeliveryStatus deliveryStatus,
        String errorMessage,
        LocalDateTime processedTime) {

    public static final String EMAIL_DELIVERY_COMPLETED = "EMAIL_DELIVERY_COMPLETED";
    /** Not in the blueprint (it only shows the success example); our D19 choice. */
    public static final String EMAIL_DELIVERY_FAILED = "EMAIL_DELIVERY_FAILED";

    public static DeliveryStatusEvent sent(EmailDeliveryEvent request, LocalDateTime processedTime) {
        return new DeliveryStatusEvent(newEventId(), EMAIL_DELIVERY_COMPLETED,
                request.submissionId(), request.loanId(), request.recipientId(),
                DeliveryStatus.SENT, null, processedTime);
    }

    public static DeliveryStatusEvent failed(EmailDeliveryEvent request, String errorMessage,
                                             LocalDateTime processedTime) {
        return new DeliveryStatusEvent(newEventId(), EMAIL_DELIVERY_FAILED,
                request.submissionId(), request.loanId(), request.recipientId(),
                DeliveryStatus.FAILED, errorMessage, processedTime);
    }

    private static String newEventId() {
        return "EVT-" + UUID.randomUUID();
    }
}