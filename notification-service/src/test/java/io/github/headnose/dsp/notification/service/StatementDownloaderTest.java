package io.github.headnose.dsp.notification.service;

import io.github.headnose.dsp.notification.config.StorageProperties;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Path validation (the security check) and S3 error mapping, without a container. */
class StatementDownloaderTest {

    private S3Client s3Client;
    private StatementDownloader downloader;

    @BeforeEach
    void setUp() {
        s3Client = mock(S3Client.class);
        StorageProperties properties = new StorageProperties(
                "http://localhost:8333", "us-east-1", "key", "secret", "wire-statements",
                true, Duration.ofSeconds(2), Duration.ofSeconds(5));
        downloader = new StatementDownloader(s3Client, properties);
    }

    @Test
    void keyOf_acceptsPathInConfiguredBucket() {
        assertThat(downloader.keyOf("s3://wire-statements/SUB-1/LN-1001.xml")).isEqualTo("SUB-1/LN-1001.xml");
    }

    @Test
    void keyOf_rejectsMissingOrForeignPaths() {
        // null (old events before attachments), wrong scheme, another bucket, bucket with no key
        for (String bad : new String[] {null, "https://wire-statements/SUB-1/LN-1001.xml",
                "s3://other-bucket/SUB-1/LN-1001.xml", "s3://wire-statements-evil/x.xml", "s3://wire-statements/"}) {
            assertThatThrownBy(() -> downloader.keyOf(bad))
                    .as("path %s", bad)
                    .isInstanceOf(InvalidEmailDeliveryEventException.class);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void download_returnsBytesAndFileName() {
        byte[] content = "<settlementWire/>".getBytes(StandardCharsets.UTF_8);
        when(s3Client.getObjectAsBytes(any(Consumer.class))).thenReturn(ResponseBytes.fromByteArray(
                GetObjectResponse.builder().contentType("application/xml").build(), content));

        StatementAttachment attachment = downloader.download("SUB-1/LN-1001.xml");

        assertThat(attachment.fileName()).isEqualTo("LN-1001.xml");
        assertThat(attachment.content()).isEqualTo(content);
        assertThat(attachment.contentType()).isEqualTo("application/xml");
    }

    @Test
    @SuppressWarnings("unchecked")
    void download_mapsNoSuchKeyToNotFound_andOtherSdkErrorsToUnavailable() {
        when(s3Client.getObjectAsBytes(any(Consumer.class)))
                .thenThrow(NoSuchKeyException.builder().message("The specified key does not exist.").build())
                .thenThrow(SdkClientException.create("Unable to execute HTTP request"));

        assertThatThrownBy(() -> downloader.download("SUB-1/LN-1001.xml"))
                .isInstanceOf(AttachmentNotFoundException.class)
                .hasMessageContaining("s3://wire-statements/SUB-1/LN-1001.xml");
        assertThatThrownBy(() -> downloader.download("SUB-1/LN-1001.xml"))
                .isInstanceOf(AttachmentUnavailableException.class);
    }
}