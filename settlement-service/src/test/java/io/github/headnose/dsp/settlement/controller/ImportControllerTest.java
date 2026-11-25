package io.github.headnose.dsp.settlement.controller;

import io.github.headnose.dsp.settlement.security.AlbTestSecurityConfig;
import io.github.headnose.dsp.settlement.security.AlbTestTokens;
import io.github.headnose.dsp.settlement.service.LoanImportService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Who may import (Workflow 1). Import behaviour itself is covered by LoanImportServiceTest. */
@WebMvcTest(ImportController.class)
@Import(AlbTestSecurityConfig.class)
class ImportControllerTest {

    private static final String URL = "/api/v1/imports";
    private static final String BODY = """
            { "loanIds": ["LN-1001"] }
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LoanImportService loanImportService;

    @Test
    void financeOpsCanImport() throws Exception {
        mockMvc.perform(post(URL)
                        .header(AlbTestTokens.HEADER, AlbTestTokens.forGroups("FinanceOps"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isOk());

        verify(loanImportService).importLoans(List.of("LN-1001"));
    }

    @Test
    void settlementAdminCannotImport() throws Exception {
        mockMvc.perform(post(URL)
                        .header(AlbTestTokens.HEADER, AlbTestTokens.forGroups("SettlementAdmin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("Forbidden"));

        verifyNoInteractions(loanImportService);
    }

    @Test
    void importWithoutTokenIs401() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(loanImportService);
    }
}