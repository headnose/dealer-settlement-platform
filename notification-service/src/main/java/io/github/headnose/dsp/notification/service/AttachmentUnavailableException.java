package io.github.headnose.dsp.notification.service;

/**
 * The statement could not be downloaded (storage down, timeout, 5xx, auth). Might be temporary:
 * retryable (2 x 1 s); if it still fails -> FAILED + DLQ (D26).
 */
public class AttachmentUnavailableException extends DeliveryFailedException {

    public AttachmentUnavailableException(String attachmentPath, Throwable cause) {
        super("Could not download wire statement: " + attachmentPath, cause);
    }
}