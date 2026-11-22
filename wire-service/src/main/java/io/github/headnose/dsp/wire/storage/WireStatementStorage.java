package io.github.headnose.dsp.wire.storage;

import io.github.headnose.dsp.wire.config.StorageProperties;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Uploads a submission's generated wire statement so BlastMail can attach it (D23, D24).
 *
 * <p>Key = {submissionId}/{loanId}.xml. Uploading again overwrites the same key with the same
 * bytes (content never changes after generation), so a retried send is safe.
 *
 * <p>All SDK failures (store down, timeout, auth, missing bucket) become
 * WireStatementStorageException, so callers never depend on AWS types.
 */
@Component
public class WireStatementStorage {

    static final String CONTENT_TYPE = "application/xml; charset=UTF-8";

    private final S3Client s3Client;
    private final String bucket;

    public WireStatementStorage(S3Client s3Client, StorageProperties properties) {
        this.s3Client = s3Client;
        this.bucket = properties.bucket();
    }

    /**
     * @return the attachment path for EmailDeliveryEvent, e.g.
     *         s3://wire-statements/SUB-.../LN-1002.xml
     */
    public String upload(String submissionId, String loanId, String content) {
        String key = submissionId + "/" + loanId + ".xml";
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(CONTENT_TYPE)
                .build();
        try {
            s3Client.putObject(request, RequestBody.fromString(content, StandardCharsets.UTF_8));
        } catch (SdkException e) {
            throw new WireStatementStorageException(
                    "Failed to upload wire statement s3://" + bucket + "/" + key, e);
        }
        return "s3://" + bucket + "/" + key;
    }
}