package io.github.headnose.dsp.wire.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** security.alb.*: everything needed to verify the ALB's signed claims. Missing values = app refuses to start. */
@ConfigurationProperties("security.alb")
@Validated
public record AlbSecurityProperties(
        @NotBlank String claimsHeader,
        @NotBlank String signer,
        @NotBlank String keyId,
        @NotBlank String publicKey,
        @NotBlank String groupsClaim,
        @NotNull Duration clockSkew) {
}