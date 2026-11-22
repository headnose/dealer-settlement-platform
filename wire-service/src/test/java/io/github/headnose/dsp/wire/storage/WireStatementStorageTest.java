package io.github.headnose.dsp.wire.storage;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.headnose.dsp.wire.config.StorageProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Failure path without a container: any SDK failure must come out as our own exception, with
 * the original as the cause (so the 503 handler can log it).
 */
class WireStatementStorageTest {

    @Test
    void sdkFailureIsWrappedInWireStatementStorageException() {
        S3Client s3Client = mock(S3Client.class);
        SdkClientException sdkFailure = SdkClientException.create("Unable to execute HTTP request");
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(sdkFailure);
        StorageProperties properties = new StorageProperties(
                "http://localhost:8333", "us-east-1", "key", "secret", "wire-statements",
                true, Duration.ofSeconds(2), Duration.ofSeconds(5));
        WireStatementStorage storage = new WireStatementStorage(s3Client, properties);

        assertThatThrownBy(() -> storage.upload("SUB-1", "LN-1002", "<wireStatement/>"))
                .isInstanceOf(WireStatementStorageException.class)
                .hasMessageContaining("s3://wire-statements/SUB-1/LN-1002.xml")
                .hasCause(sdkFailure);
    }
}