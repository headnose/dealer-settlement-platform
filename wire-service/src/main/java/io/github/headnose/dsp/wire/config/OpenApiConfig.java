package io.github.headnose.dsp.wire.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * API docs: springdoc builds the spec from the controllers; this adds the title and the ALB header, so
 * Swagger UI shows an "Authorize" button where a user token is pasted (locally: a token from
 * dev-tools/AlbDevToken.java; FinanceOps generates wires, SettlementAdmin sends them).
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    static final String ALB_SCHEME = "albClaims";

    @Bean
    OpenAPI wireServiceOpenApi(@Value("${security.alb.claims-header}") String claimsHeader) {
        return new OpenAPI()
                .info(new Info()
                        .title("wire-service API")
                        .version("v1")
                        .description("Generates wire statements and sends them to the dealer and bank by email "
                                + "(through Kafka). Errors are RFC 9457 ProblemDetail JSON."))
                .components(new Components()
                        .addSecuritySchemes(ALB_SCHEME, new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name(claimsHeader)
                                .description("Signed user claims added by the load balancer. Locally: "
                                        + "java dev-tools/AlbDevToken.java dev-finops FinanceOps 60")))
                .addSecurityItem(new SecurityRequirement().addList(ALB_SCHEME));
    }
}
