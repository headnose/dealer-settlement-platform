package io.github.headnose.dsp.loan.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** API docs title. loan-service has no security: it stands in for another team's system. */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    @Bean
    OpenAPI loanServiceOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("loan-service API (mock upstream)")
                        .version("v1")
                        .description("Returns sample loans by ID. LN-1001, LN-1002, LN-2001 and LN-5001 to "
                                + "LN-5200 are CLOSED; LN-1003 is ACTIVE; LN-2002 is CANCELLED."));
    }
}
