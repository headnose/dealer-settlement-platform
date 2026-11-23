package io.github.headnose.dsp.wire.storage;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.headnose.dsp.wire.KafkaTestContainerConfig;
import io.github.headnose.dsp.wire.MySqlTestContainerConfig;
import io.github.headnose.dsp.wire.SeaweedFsTestContainerConfig;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

/**
 * Real upload against SeaweedFS: proves the S3Client config (endpoint, path-style, signing,
 * checksums) works and that the object/path layout matches D24.
 *
 * <p>Same @Import list as every other @SpringBootTest, so the cached context and containers are
 * shared. Unique submissionId per test because the container is shared.
 */
@SpringBootTest
@Import({MySqlTestContainerConfig.class, KafkaTestContainerConfig.class,
        SeaweedFsTestContainerConfig.class})
class WireStatementStorageIntegrationTest {

    private static final String BUCKET = "wire-statements";

    @Autowired
    private WireStatementStorage storage;

    @Autowired
    private S3Client s3Client;

    @Test
    void uploadStoresStatementAndReturnsS3Path() {
        String submissionId = "SUB-" + UUID.randomUUID();
        // Non-ASCII character proves the bytes are written and read back as UTF-8.
        String content = "<wireStatement><loanId>LN-1002</loanId><dealer>Café Motors</dealer></wireStatement>";

        String path = storage.upload(submissionId, "LN-1002", content);

        assertThat(path).isEqualTo("s3://" + BUCKET + "/" + submissionId + "/LN-1002.xml");
        ResponseBytes<GetObjectResponse> stored = s3Client.getObjectAsBytes(request -> request
                .bucket(BUCKET)
                .key(submissionId + "/LN-1002.xml"));
        assertThat(stored.asUtf8String()).isEqualTo(content);
        assertThat(stored.response().contentType()).isEqualTo(WireStatementStorage.CONTENT_TYPE);
    }

    @Test
    void uploadingAgainOverwritesTheSameObject() {
        String submissionId = "SUB-" + UUID.randomUUID();
        String content = "<wireStatement><loanId>LN-2001</loanId></wireStatement>";

        String first = storage.upload(submissionId, "LN-2001", content);
        String second = storage.upload(submissionId, "LN-2001", content);

        // D23: a retried send re-uploads; it must land on the same key, not create a second object.
        assertThat(second).isEqualTo(first);
        assertThat(s3Client.listObjectsV2(request -> request
                        .bucket(BUCKET)
                        .prefix(submissionId + "/"))
                .keyCount()).isEqualTo(1);
    }
}