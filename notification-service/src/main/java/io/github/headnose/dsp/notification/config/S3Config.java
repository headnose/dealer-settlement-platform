package io.github.headnose.dsp.notification.config;

import java.net.URI;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

/**
 * Builds the one S3Client used by notification-service to download statements (D25). Own copy
 * of wire-service's config (D10: services share JSON contracts, not code). Works against SeaweedFS
 * locally (endpoint override + path-style) and real S3 in AWS (no endpoint, IAM role).
 *
 * <p>Creating the client does not connect to anything, so the app (and tests that do not touch
 * storage) start even when SeaweedFS is not running. Spring calls close() on shutdown because
 * S3Client is AutoCloseable.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(StorageProperties.class)
public class S3Config {

    @Bean
    S3Client s3Client(StorageProperties properties) {
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(properties.region()))
                .credentialsProvider(credentialsProvider(properties))
                // SeaweedFS has no per-bucket DNS names, so the bucket goes in the URL path.
                .forcePathStyle(properties.pathStyleAccess())
                // Newer SDKs add checksum headers to every upload by default; some
                // S3-compatible stores reject them. WHEN_REQUIRED works everywhere.
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .overrideConfiguration(override -> override
                        .apiCallAttemptTimeout(properties.apiCallAttemptTimeout())
                        .apiCallTimeout(properties.apiCallTimeout()));

        if (StringUtils.hasText(properties.endpoint())) {
            builder.endpointOverride(URI.create(properties.endpoint()));
        }
        return builder.build();
    }

    private static AwsCredentialsProvider credentialsProvider(StorageProperties properties) {
        if (StringUtils.hasText(properties.accessKey())) {
            return StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(properties.accessKey(), properties.secretKey()));
        }
        // Env vars, ~/.aws, or the ECS task role: no secrets in config in AWS.
        return DefaultCredentialsProvider.builder().build();
    }
}