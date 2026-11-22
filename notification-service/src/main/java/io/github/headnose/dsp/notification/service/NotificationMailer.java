package io.github.headnose.dsp.notification.service;

import io.github.headnose.dsp.notification.messaging.EmailDeliveryEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/** Builds and sends the notification email for one recipient over SMTP (B2). */
@Component
public class NotificationMailer {

    private final JavaMailSender mailSender;
    private final String from;

    public NotificationMailer(JavaMailSender mailSender, @Value("${blastmail.from}") String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    /** Throws Spring's MailException (unchecked) if the SMTP server rejects or is unreachable. */
    public void send(EmailDeliveryEvent event) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(event.email());
        message.setSubject(event.subject());
        message.setText(body(event));
        mailSender.send(message);
    }

    // Blueprint gap: it does not define the body. attachmentPath is null until MinIO (D15).
    static String body(EmailDeliveryEvent event) {
        return """
                Hello %s,

                A %s wire statement has been issued for loan %s.
                Submission ID: %s

                The wire statement document is not attached in this version.

                This is an automated message from the Dealer Settlement Platform. Please do not reply.
                """.formatted(event.recipientName(), event.wireType(), event.loanId(), event.submissionId());
    }
}