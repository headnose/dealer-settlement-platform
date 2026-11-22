package io.github.headnose.dsp.notification.service;

/** SMTP send failed. Retryable; when retries run out, a FAILED status is published (B5). */
public class EmailSendException extends DeliveryFailedException {

    public EmailSendException(String recipientId, Throwable cause) {
        super("Email send failed for recipient " + recipientId, cause);
    }
}
