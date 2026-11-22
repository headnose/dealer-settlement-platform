package io.github.headnose.dsp.notification.service;

import io.github.headnose.dsp.notification.messaging.EmailDeliveryEvent;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/** Builds and sends the notification email for one recipient over SMTP, with the statement attached (B2, D25). */
@Component
public class NotificationMailer {

    private final JavaMailSender mailSender;
    private final String from;

    public NotificationMailer(JavaMailSender mailSender, @Value("${blastmail.from}") String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    /**
     * Throws Spring's MailException (unchecked) if the SMTP server rejects or is unreachable.
     * The lambda is a MimeMessagePreparator: if building the message throws (checked
     * MessagingException), Spring wraps it in MailPreparationException, which is a MailException too.
     */
    public void send(EmailDeliveryEvent event, StatementAttachment attachment) {
        mailSender.send(mimeMessage -> {
            // multipart = true: text body + attachment
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, StandardCharsets.UTF_8.name());
            helper.setFrom(from);
            helper.setTo(event.email());
            helper.setSubject(event.subject());
            helper.setText(body(event, attachment.fileName()));
            helper.addAttachment(attachment.fileName(),
                    new ByteArrayResource(attachment.content()), attachment.contentType());
        });
    }

    // Blueprint gap: it does not define the body.
    static String body(EmailDeliveryEvent event, String fileName) {
        return """
                Hello %s,

                A %s wire statement has been issued for loan %s.
                Submission ID: %s

                The wire statement is attached (%s).

                This is an automated message from the Dealer Settlement Platform. Please do not reply.
                """.formatted(event.recipientName(), event.wireType(), event.loanId(), event.submissionId(), fileName);
    }
}