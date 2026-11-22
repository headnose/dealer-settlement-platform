package io.github.headnose.dsp.notification.service;

/**
 * The statement object does not exist (S3 NoSuchKey). PWT uploads before it publishes, so a
 * retry a second later will not find it either: NOT retryable -> FAILED + DLQ at once (D26).
 */
public class AttachmentNotFoundException extends DeliveryFailedException {

    public AttachmentNotFoundException(String attachmentPath, Throwable cause) {
        super("Wire statement not found: " + attachmentPath, cause);
    }
}