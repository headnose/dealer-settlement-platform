package io.github.headnose.dsp.wire.client;

import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Blueprint step 5: adds wire-service's OWN access token (client credentials) to every settlement-service call.
 * The principal is always the service, never the logged-in user: the user's identity stays in wire-service.
 * Tokens are cached by the OAuth2AuthorizedClientService and fetched again shortly before they expire.
 */
public class ServiceTokenFilter implements ExchangeFilterFunction {

    /** Who the token is for (cache key). A fixed service name, NOT the current request's user. */
    static final String SERVICE_PRINCIPAL = "wire-service";

    private final OAuth2AuthorizedClientManager authorizedClientManager;
    private final OAuth2AuthorizedClientService authorizedClientService;
    private final String clientRegistrationId;

    public ServiceTokenFilter(OAuth2AuthorizedClientManager authorizedClientManager,
                              OAuth2AuthorizedClientService authorizedClientService,
                              String clientRegistrationId) {
        this.authorizedClientManager = authorizedClientManager;
        this.authorizedClientService = authorizedClientService;
        this.clientRegistrationId = clientRegistrationId;
    }

    @Override
    public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
        return Mono.fromCallable(this::accessToken)
                // The manager blocks (HTTP call to the IdP when no valid token is cached):
                // keep it off Reactor Netty's event-loop threads.
                .subscribeOn(Schedulers.boundedElastic())
                .map(token -> ClientRequest.from(request).headers(headers -> headers.setBearerAuth(token)).build())
                .flatMap(next::exchange)
                .doOnNext(response -> {
                    if (response.statusCode().isSameCodeAs(HttpStatus.UNAUTHORIZED)) {
                        // Token rejected (revoked, keys rotated): forget it so the next call gets a fresh one.
                        authorizedClientService.removeAuthorizedClient(clientRegistrationId, SERVICE_PRINCIPAL);
                    }
                });
    }

    /** @throws org.springframework.security.oauth2.core.OAuth2AuthorizationException IdP down / credentials refused */
    private String accessToken() {
        OAuth2AuthorizeRequest authorizeRequest = OAuth2AuthorizeRequest
                .withClientRegistrationId(clientRegistrationId)
                .principal(SERVICE_PRINCIPAL)
                .build();
        OAuth2AuthorizedClient client = authorizedClientManager.authorize(authorizeRequest);
        if (client == null) {
            throw new IllegalStateException("No access token for client registration " + clientRegistrationId);
        }
        return client.getAccessToken().getTokenValue();
    }
}