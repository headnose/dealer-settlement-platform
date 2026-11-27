package io.github.headnose.dsp.settlement.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiConfigTest {

    private final OpenAPI openApi = new OpenApiConfig().settlementServiceOpenApi("x-amzn-oidc-data");

    @Test
    void albSchemeIsTheClaimsHeader() {
        SecurityScheme alb = openApi.getComponents().getSecuritySchemes().get(OpenApiConfig.ALB_SCHEME);

        assertThat(alb.getType()).isEqualTo(SecurityScheme.Type.APIKEY);
        assertThat(alb.getIn()).isEqualTo(SecurityScheme.In.HEADER);
        assertThat(alb.getName()).isEqualTo("x-amzn-oidc-data");
    }

    @Test
    void serviceSchemeIsABearerJwt() {
        SecurityScheme service = openApi.getComponents().getSecuritySchemes().get(OpenApiConfig.SERVICE_SCHEME);

        assertThat(service.getType()).isEqualTo(SecurityScheme.Type.HTTP);
        assertThat(service.getScheme()).isEqualTo("bearer");
        assertThat(service.getBearerFormat()).isEqualTo("JWT");
    }

    @Test
    void eitherSchemeIsEnough() {
        // Two separate requirements = OR in OpenAPI (one requirement with both names would mean AND).
        assertThat(openApi.getSecurity())
                .extracting(SecurityRequirement::keySet)
                .containsExactly(Set.of(OpenApiConfig.ALB_SCHEME), Set.of(OpenApiConfig.SERVICE_SCHEME));
    }
}
