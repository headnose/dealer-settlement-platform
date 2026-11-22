package io.github.headnose.dsp.notification.service;

import io.github.headnose.dsp.notification.messaging.DeliveryStatusEvent;
import io.github.headnose.dsp.notification.messaging.DeliveryStatusPublisher;
import io.github.headnose.dsp.notification.messaging.EmailDeliveryEvent;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.stereotype.Service;

/**
 * Workflow 4, BlastMail: one email per EmailDeliveryEvent, de-duplicated on recipientId (B3),
 * result published to delivery-status (B4). At-least-once: a crash after SMTP accepted the
 * email but before markSent can send it twice; a lost notice would be worse.
 */
@Service
public class EmailDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(EmailDeliveryService.class);

    static final String EMAIL_DELIVERY_REQUESTED = "EMAIL_DELIVERY_REQUESTED";
    static final int MAX_ERROR_MESSAGE_LENGTH = 500;

    private final DeliveryIdempotencyStore idempotencyStore;
    private final NotificationMailer mailer;
    private final DeliveryStatusPublisher publisher;
    private final Clock clock;

    public EmailDeliveryService(DeliveryIdempotencyStore idempotencyStore, NotificationMailer mailer,
                                DeliveryStatusPublisher publisher, Clock clock) {
        this.idempotencyStore = idempotencyStore;
        this.mailer = mailer;
        this.publisher = publisher;
        this.clock = clock;
    }

    public void deliver(EmailDeliveryEvent event) {
        validate(event);
        String recipientId = event.recipientId();

        switch (idempotencyStore.claim(recipientId)) {
            case CLAIMED -> sendAndReport(event);
            case ALREADY_SENT -> {
                // Email went out earlier; the status publish may not have. Report SENT again (PWT is idempotent, D18).
                log.info("Email already sent, republishing SENT: submissionId={} recipientId={}",
                        event.submissionId(), recipientId);
                publisher.publish(DeliveryStatusEvent.sent(event, now()));
            }
            case IN_PROGRESS -> throw new DeliveryInProgressException(recipientId);
        }
    }

    /** Called once retries are exhausted for an EmailSendException: tell PWT the recipient FAILED (D19). */
    public void reportFailure(EmailDeliveryEvent event, Throwable failure) {
        publisher.publish(DeliveryStatusEvent.failed(event, errorMessage(failure), now()));
        log.warn("Reported FAILED: submissionId={} recipientId={}", event.submissionId(), event.recipientId());
    }

    private void sendAndReport(EmailDeliveryEvent event) {
        String recipientId = event.recipientId();
        try {
            mailer.send(event);
        } catch (MailException e) {
            EmailSendException failure = new EmailSendException(recipientId, e);
            try {
                idempotencyStore.release(recipientId);   // let the retry send
            } catch (RuntimeException releaseFailure) {
                failure.addSuppressed(releaseFailure);    // key expires after processing-ttl anyway
            }
            throw failure;
        }
        idempotencyStore.markSent(recipientId);
        publisher.publish(DeliveryStatusEvent.sent(event, now()));
        log.info("Email sent: submissionId={} recipientId={}", event.submissionId(), recipientId);
    }

    private static void validate(EmailDeliveryEvent event) {
        if (!EMAIL_DELIVERY_REQUESTED.equals(event.eventType())) {
            throw new InvalidEmailDeliveryEventException("Unexpected eventType: " + event.eventType());
        }
        requireText(event.submissionId(), "submissionId");
        requireText(event.recipientId(), "recipientId");
        requireText(event.email(), "email");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new InvalidEmailDeliveryEventException("Missing " + field);
        }
    }

    /** The SMTP error, not our wrapper's text; capped so a huge server reply cannot bloat the event. */
    static String errorMessage(Throwable failure) {
        Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
        String message = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
        return message.length() > MAX_ERROR_MESSAGE_LENGTH ? message.substring(0, MAX_ERROR_MESSAGE_LENGTH) : message;
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
    }
}