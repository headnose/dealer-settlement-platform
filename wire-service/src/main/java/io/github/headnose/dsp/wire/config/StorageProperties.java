package io.github.headnose.dsp.wire.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Settings for the S3-compatible store that holds wire statements (D21-D24), bound from the
 * top-level {@code storage:} block in application.yaml.
 *
 * <p>endpoint, accessKey and secretKey may be empty: then the client talks to real AWS S3 and
 * uses the default credentials chain (e.g. the ECS task's IAM role).
 *
 * <p>{@code @Validated} makes the app fail at startup if a required value is missing, instead
 * of failing on the first send.
 */
@Validated
@ConfigurationProperties("storage")
public record StorageProperties(
        String endpoint,
        @NotBlank String region,
        String accessKey,
        String secretKey,
        @NotBlank String bucket,
        boolean pathStyleAccess,
        @NotNull Duration apiCallAttemptTimeout,
        @NotNull Duration apiCallTimeout) {
}