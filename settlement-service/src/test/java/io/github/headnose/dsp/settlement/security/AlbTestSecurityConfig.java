package io.github.headnose.dsp.settlement.security;

import io.github.headnose.dsp.settlement.config.ClockConfig;
import io.github.headnose.dsp.settlement.config.SecurityConfig;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistrar;

/**
 * For @WebMvcTest: the slice does not load our @Configuration classes, so import the real security setup
 * and point it at the test key pair (tests never need the developer's .local-keys).
 */
@TestConfiguration(proxyBeanMethods = false)
@Import({SecurityConfig.class, AlbAuthenticationConfig.class, ClockConfig.class})
public class AlbTestSecurityConfig {

    @Bean
    DynamicPropertyRegistrar albTestKeyProperties() {
        return registry -> {
            registry.add("security.alb.public-key", AlbTestTokens::publicKeyPem);
            registry.add("security.alb.signer", () -> AlbTestTokens.SIGNER);
            registry.add("security.alb.key-id", () -> AlbTestTokens.KEY_ID);
        };
    }

    /**
     * No IdP in tests: a real Bearer token that reaches the decoder is rejected (401). Service-token tests use
     * spring-security-test's jwt() instead, which skips decoding. Also stops Boot from building the
     * Keycloak-backed decoder in web tests.
     */
    @Bean
    JwtDecoder rejectingJwtDecoder() {
        return token -> {
            throw new BadJwtException("Test JwtDecoder: Bearer tokens are not decoded in tests");
        };
    }
}