package io.github.headnose.dsp.wire.config;

import io.github.headnose.dsp.wire.client.ServiceTokenFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;

/**
 * Blueprint step 5: wire-service's service credential (client registration "settlement-service" in YAML).
 * AuthorizedClientService* = the manager meant for calls outside a user request (no HttpServletRequest needed).
 */
@Configuration
public class ServiceAuthConfig {

    /** Registration id under spring.security.oauth2.client.registration (application.yaml). */
    public static final String SETTLEMENT_SERVICE_REGISTRATION = "settlement-service";

    /** Token cache, in memory: per instance; a restart just fetches a new token. */
    @Bean
    OAuth2AuthorizedClientService authorizedClientService(ClientRegistrationRepository clientRegistrations) {
        return new InMemoryOAuth2AuthorizedClientService(clientRegistrations);
    }

    @Bean
    OAuth2AuthorizedClientManager serviceAuthorizedClientManager(ClientRegistrationRepository clientRegistrations,
                                                                 OAuth2AuthorizedClientService authorizedClientService) {
        AuthorizedClientServiceOAuth2AuthorizedClientManager manager =
                new AuthorizedClientServiceOAuth2AuthorizedClientManager(clientRegistrations, authorizedClientService);
        manager.setAuthorizedClientProvider(OAuth2AuthorizedClientProviderBuilder.builder()
                .clientCredentials()   // re-fetches when the cached token is within 60s of expiry (default skew)
                .build());
        return manager;
    }

    @Bean
    ExchangeFilterFunction settlementServiceTokenFilter(OAuth2AuthorizedClientManager serviceAuthorizedClientManager,
                                                        OAuth2AuthorizedClientService authorizedClientService) {
        return new ServiceTokenFilter(serviceAuthorizedClientManager, authorizedClientService,
                SETTLEMENT_SERVICE_REGISTRATION);
    }
}