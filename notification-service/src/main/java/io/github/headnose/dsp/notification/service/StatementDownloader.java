package io.github.headnose.dsp.notification.service;

import io.github.headnose.dsp.notification.config.StorageProperties;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

/**
 * Fetches the wire statement an EmailDeliveryEvent points at (D25, D26).
 *
 * <p>The path comes from a Kafka event, so it is checked first: only s3:// paths in the configured
 * bucket are accepted. Otherwise a bad or malicious event could make BlastMail fetch any object
 * its credentials can read and email it out.
 *
 * <p>Statements are small XML files, so the whole object is read into memory.
 */
@Component
public class StatementDownloader {

    private static final String SCHEME = "s3://";
    private static final String DEFAULT_CONTENT_TYPE = "application/xml";

    private final S3Client s3Client;
    private final String bucket;

    public StatementDownloader(S3Client s3Client, StorageProperties properties) {
        this.s3Client = s3Client;
        this.bucket = properties.bucket();
    }

    /**
     * Validates an event's attachmentPath and returns the object key.
     * Called BEFORE claiming the Redis key: a bad path is an invalid event (not retryable, DLQ).
     */
    public String keyOf(String attachmentPath) {
        if (attachmentPath == null || !attachmentPath.startsWith(SCHEME)) {
            throw new InvalidEmailDeliveryEventException("attachmentPath must be an s3:// path, was: " + attachmentPath);
        }
        String prefix = SCHEME + bucket + "/";
        if (!attachmentPath.startsWith(prefix) || attachmentPath.length() == prefix.length()) {
            throw new InvalidEmailDeliveryEventException(
                    "attachmentPath must point into bucket " + bucket + ", was: " + attachmentPath);
        }
        return attachmentPath.substring(prefix.length());
    }

    /** Downloads the object; the file name is the last part of the key (e.g. LN-1002.xml). */
    public StatementAttachment download(String key) {
        String path = SCHEME + bucket + "/" + key;
        ResponseBytes<GetObjectResponse> object;
        try {
            object = s3Client.getObjectAsBytes(request -> request.bucket(bucket).key(key));
        } catch (NoSuchKeyException e) {
            throw new AttachmentNotFoundException(path, e);
        } catch (SdkException e) {
            throw new AttachmentUnavailableException(path, e);
        }
        String contentType = object.response().contentType() != null
                ? object.response().contentType()
                : DEFAULT_CONTENT_TYPE;
        return new StatementAttachment(key.substring(key.lastIndexOf('/') + 1), object.asByteArray(), contentType);
    }
}