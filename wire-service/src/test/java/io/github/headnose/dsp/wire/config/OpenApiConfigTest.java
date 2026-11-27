package io.github.headnose.dsp.wire.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiConfigTest {

    private final OpenAPI openApi = new OpenApiConfig().wireServiceOpenApi("x-amzn-oidc-data");

    @Test
    void albSchemeIsTheClaimsHeader() {
        SecurityScheme alb = openApi.getComponents().getSecuritySchemes().get(OpenApiConfig.ALB_SCHEME);

        assertThat(alb.getType()).isEqualTo(SecurityScheme.Type.APIKEY);
        assertThat(alb.getIn()).isEqualTo(SecurityScheme.In.HEADER);
        assertThat(alb.getName()).isEqualTo("x-amzn-oidc-data");
    }

    @Test
    void everyEndpointNeedsTheAlbHeader() {
        assertThat(openApi.getSecurity())
                .extracting(SecurityRequirement::keySet)
                .containsExactly(Set.of(OpenApiConfig.ALB_SCHEME));
    }
}
