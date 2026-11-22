package io.github.headnose.dsp.notification;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * Throwaway SeaweedFS (S3-compatible) for tests, same image and env vars as docker compose.
 *
 * <p>S3 has no @ServiceConnection, so a DynamicPropertyRegistrar points storage.* at the
 * container's random port (same trick as GreenMail in notification-service). The test keys differ
 * from application.yaml on purpose: if the override did not work, every upload would fail auth.
 *
 * <p>The container creates the bucket itself (infra owns buckets, D24); we wait for that exact
 * log line, which SeaweedFS prints after the S3 API is up.
 */
@TestConfiguration(proxyBeanMethods = false)
public class SeaweedFsTestContainerConfig {

    private static final String ACCESS_KEY = "test-access";
    private static final String SECRET_KEY = "test-secret";
    private static final String BUCKET = "wire-statements";
    private static final int S3_PORT = 8333;

    @Bean
    GenericContainer<?> seaweedFsContainer() {
        return new GenericContainer<>("chrislusf/seaweedfs:4.47")
                .withEnv("AWS_ACCESS_KEY_ID", ACCESS_KEY)
                .withEnv("AWS_SECRET_ACCESS_KEY", SECRET_KEY)
                .withEnv("S3_BUCKET", BUCKET)
                .withExposedPorts(S3_PORT)
                .waitingFor(Wait.forLogMessage(".*created bucket " + BUCKET + ".*\\n", 1));
    }

    // @Qualifier: Redis and Kafka are GenericContainers too, so pick by bean name.
    @Bean
    DynamicPropertyRegistrar seaweedFsProperties(
            @Qualifier("seaweedFsContainer") GenericContainer<?> seaweedFsContainer) {
        return registry -> {
            registry.add("storage.endpoint", () -> "http://" + seaweedFsContainer.getHost()
                    + ":" + seaweedFsContainer.getMappedPort(S3_PORT));
            registry.add("storage.access-key", () -> ACCESS_KEY);
            registry.add("storage.secret-key", () -> SECRET_KEY);
            registry.add("storage.bucket", () -> BUCKET);
        };
    }
}