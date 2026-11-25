package io.github.headnose.dsp.wire.client;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ServiceTokenFilterTest {

    private static final String REGISTRATION_ID = "settlement-service";
    private static final ClientRegistration REGISTRATION = ClientRegistration.withRegistrationId(REGISTRATION_ID)
            .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
            .clientId("wire-service")
            .tokenUri("http://idp.example.com/token")
            .build();

    private final OAuth2AuthorizedClientManager manager = mock(OAuth2AuthorizedClientManager.class);
    private final OAuth2AuthorizedClientService clientService = mock(OAuth2AuthorizedClientService.class);
    private final ServiceTokenFilter filter = new ServiceTokenFilter(manager, clientService, REGISTRATION_ID);
    private final ClientRequest request = ClientRequest
            .create(HttpMethod.GET, URI.create("http://lsc.example.com/api/v1/loans/LN-1001/settlements"))
            .build();

    @Test
    void sendsTheServiceTokenAsBearerForTheServiceNotTheUser() {
        when(manager.authorize(any())).thenReturn(authorizedClient("token-123"));
        AtomicReference<ClientRequest> sent = new AtomicReference<>();

        filter.filter(request, outgoing -> {
            sent.set(outgoing);
            return Mono.just(ClientResponse.create(HttpStatus.OK).build());
        }).block();

        assertThat(sent.get().headers().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer token-123");
        ArgumentCaptor<OAuth2AuthorizeRequest> asked = ArgumentCaptor.forClass(OAuth2AuthorizeRequest.class);
        verify(manager).authorize(asked.capture());
        assertThat(asked.getValue().getPrincipal().getName()).isEqualTo(ServiceTokenFilter.SERVICE_PRINCIPAL);
    }

    @Test
    void unauthorizedResponseDropsTheCachedToken() {
        when(manager.authorize(any())).thenReturn(authorizedClient("revoked-token"));

        filter.filter(request, outgoing -> Mono.just(ClientResponse.create(HttpStatus.UNAUTHORIZED).build())).block();

        verify(clientService).removeAuthorizedClient(REGISTRATION_ID, ServiceTokenFilter.SERVICE_PRINCIPAL);
    }

    private static OAuth2AuthorizedClient authorizedClient(String tokenValue) {
        OAuth2AccessToken token = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, tokenValue,
                Instant.now(), Instant.now().plusSeconds(300));
        return new OAuth2AuthorizedClient(REGISTRATION, ServiceTokenFilter.SERVICE_PRINCIPAL, token);
    }
}