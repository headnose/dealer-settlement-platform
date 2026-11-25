package io.github.headnose.dsp.wire.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationProvider;

import java.security.interfaces.ECPublicKey;
import java.time.Clock;

/** Builds the ALB claims verifier from security.alb.* (key parsed once, at startup: bad config fails fast). */
@Configuration
@EnableConfigurationProperties(AlbSecurityProperties.class)
public class AlbAuthenticationConfig {

    @Bean
    AlbPublicKeys albPublicKeys(AlbSecurityProperties properties) {
        ECPublicKey key = PemKeys.ecPublicKey(properties.publicKey());
        String keyId = properties.keyId();
        return requestedKeyId -> keyId.equals(requestedKeyId) ? key : null;
    }

    @Bean
    AlbClaimsVerifier albClaimsVerifier(AlbPublicKeys albPublicKeys, AlbSecurityProperties properties, Clock clock) {
        return new AlbClaimsVerifier(albPublicKeys, properties.signer(), properties.groupsClaim(),
                properties.clockSkew(), clock);
    }

    /** Our only authentication mechanism (so Boot no longer creates its fallback user with a generated password). */
    @Bean
    PreAuthenticatedAuthenticationProvider albAuthenticationProvider(AlbClaimsVerifier albClaimsVerifier) {
        PreAuthenticatedAuthenticationProvider provider = new PreAuthenticatedAuthenticationProvider();
        provider.setPreAuthenticatedUserDetailsService(new AlbUserDetailsService(albClaimsVerifier));
        return provider;
    }
}