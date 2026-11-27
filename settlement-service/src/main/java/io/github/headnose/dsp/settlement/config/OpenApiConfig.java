package io.github.headnose.dsp.settlement.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * API docs: springdoc builds the spec from the controllers; this adds the title and the two ways in, so
 * Swagger UI shows an "Authorize" button for each. A request needs one of them, not both (two security
 * requirements = OR in OpenAPI).
 * - albClaims: the header the ALB adds after login (locally: a token from dev-tools/AlbDevToken.java).
 * - serviceToken: a Bearer token from the IdP, used by wire-service (client credentials).
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    static final String ALB_SCHEME = "albClaims";
    static final String SERVICE_SCHEME = "serviceToken";

    @Bean
    OpenAPI settlementServiceOpenApi(@Value("${security.alb.claims-header}") String claimsHeader) {
        return new OpenAPI()
                .info(new Info()
                        .title("settlement-service API")
                        .version("v1")
                        .description("Imports closed loans from loan-service and calculates dealer settlements. "
                                + "Errors are RFC 9457 ProblemDetail JSON."))
                .components(new Components()
                        .addSecuritySchemes(ALB_SCHEME, new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name(claimsHeader)
                                .description("Signed user claims added by the load balancer. Locally: "
                                        + "java dev-tools/AlbDevToken.java dev-finops FinanceOps 60"))
                        .addSecuritySchemes(SERVICE_SCHEME, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Service-to-service token from Keycloak (client credentials). "
                                        + "Only GET settlement detail accepts it.")))
                .addSecurityItem(new SecurityRequirement().addList(ALB_SCHEME))
                .addSecurityItem(new SecurityRequirement().addList(SERVICE_SCHEME));
    }
}
