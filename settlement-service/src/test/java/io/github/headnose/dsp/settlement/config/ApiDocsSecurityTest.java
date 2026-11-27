package io.github.headnose.dsp.settlement.config;

import io.github.headnose.dsp.settlement.controller.SettlementController;
import io.github.headnose.dsp.settlement.security.AlbTestSecurityConfig;
import io.github.headnose.dsp.settlement.service.SettlementService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * The URL rules for the API docs. The web slice does not load springdoc itself, so a docs path answers
 * 404 here (200 in the running app): what matters is that security lets it through (no 401/403), while
 * the API and every unlisted path still need a token.
 */
@WebMvcTest(SettlementController.class)
@Import(AlbTestSecurityConfig.class)
class ApiDocsSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SettlementService settlementService;

    @ParameterizedTest
    @ValueSource(strings = {"/v3/api-docs", "/v3/api-docs/swagger-config", "/swagger-ui.html",
            "/swagger-ui/index.html"})
    void docsNeedNoToken(String path) throws Exception {
        int status = mockMvc.perform(get(path)).andReturn().getResponse().getStatus();

        assertThat(status).isNotIn(401, 403);
    }

    @Test
    void apiStillNeedsAToken() throws Exception {
        int status = mockMvc.perform(get("/api/v1/loans/LN-1001/settlements")).andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(401);
    }

    @Test
    void unlistedPathsAreStillDenied() throws Exception {
        int status = mockMvc.perform(get("/actuator/env")).andReturn().getResponse().getStatus();

        assertThat(status).isIn(401, 403);
    }
}
