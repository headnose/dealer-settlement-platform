package io.github.headnose.dsp.wire.security;

import io.github.headnose.dsp.wire.config.ClockConfig;
import io.github.headnose.dsp.wire.config.SecurityConfig;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
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
}