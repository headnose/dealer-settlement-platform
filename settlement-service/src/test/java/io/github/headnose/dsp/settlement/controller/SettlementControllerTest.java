package io.github.headnose.dsp.settlement.controller;

import io.github.headnose.dsp.settlement.cache.CachedSettlementDetail;
import io.github.headnose.dsp.settlement.controller.dto.SettlementRequestDto;
import io.github.headnose.dsp.settlement.document.LoanSettlementDocument;
import io.github.headnose.dsp.settlement.model.AdjustmentType;
import io.github.headnose.dsp.settlement.security.AlbTestSecurityConfig;
import io.github.headnose.dsp.settlement.security.AlbTestTokens;
import io.github.headnose.dsp.settlement.service.LoanNotImportedException;
import io.github.headnose.dsp.settlement.service.SettlementNotCalculatedException;
import io.github.headnose.dsp.settlement.service.SettlementRuleViolationException;
import io.github.headnose.dsp.settlement.service.SettlementService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.util.List;

import static io.github.headnose.dsp.settlement.SettlementTestData.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SettlementController.class)
@Import(AlbTestSecurityConfig.class)
class SettlementControllerTest {

    private static final String URL = "/api/v1/loans/{loanId}/settlements";

    private static final String VALID_BODY = """
            {
              "payment": { "paymentMethod": "WIRE", "currency": "USD" },
              "adjustments": [
                { "adjustmentType": "DEALER_HOLDBACK", "amount": 500.00 },
                { "adjustmentType": "DOC_FEE", "amount": 150.00 }
              ]
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SettlementService settlementService;

    private ResultActions patchSettlement(String body) throws Exception {
        return patchSettlementAs(AlbTestTokens.forGroups("FinanceOps"), body);
    }

    private ResultActions patchSettlementAs(String token, String body) throws Exception {
        return mockMvc.perform(patch(URL, LOAN_ID)
                .header(AlbTestTokens.HEADER, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions getSettlement() throws Exception {
        return getSettlementAs(AlbTestTokens.forGroups("FinanceOps"));
    }

    private ResultActions getSettlementAs(String token) throws Exception {
        return mockMvc.perform(get(URL, LOAN_ID).header(AlbTestTokens.HEADER, token));
    }

    /** wire-service's client-credentials token as the resource server sees it after verification. */
    private static RequestPostProcessor wireServiceToken() {
        return jwt()
                .jwt(token -> token.subject("service-account-wire-service").claim("azp", "wire-service"))
                .authorities(new SimpleGrantedAuthority("SCOPE_settlement.read"));
    }

    private static LoanSettlementDocument calculatedDocument() {
        LoanSettlementDocument doc = LoanSettlementDocument.imported(closedLoan());
        doc.applySettlement(wirePayment(),
                List.of(adj(AdjustmentType.DEALER_HOLDBACK, "500.00"),
                        adj(AdjustmentType.DOC_FEE, "150.00")),
                settlement("28500.00", "650.00", "27850.00"));
        return doc;
    }

    // ---------- 200 ----------

    @Test
    void returns200WithCalculatedSettlement() throws Exception {
        when(settlementService.calculate(eq(LOAN_ID), any(SettlementRequestDto.class)))
                .thenReturn(calculatedDocument());

        patchSettlement(VALID_BODY)
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.settlementId").value(startsWith("SET-")))
                .andExpect(jsonPath("$.loanId").doesNotExist())
                .andExpect(jsonPath("$.status").value("CALCULATED"))
                // check the raw JSON text so we know the scale (".00") survived
                .andExpect(content().string(containsString("\"netSettlement\":27850.00")))
                .andExpect(content().string(containsString("\"totalAdjustment\":650.00")));
    }

    @Test
    void passesParsedRequestToService() throws Exception {
        when(settlementService.calculate(eq(LOAN_ID), any(SettlementRequestDto.class)))
                .thenReturn(calculatedDocument());

        patchSettlement(VALID_BODY).andExpect(status().isOk());

        ArgumentCaptor<SettlementRequestDto> captor = ArgumentCaptor.forClass(SettlementRequestDto.class);
        verify(settlementService).calculate(eq(LOAN_ID), captor.capture());
        SettlementRequestDto sent = captor.getValue();
        assertThat(sent.payment().currency()).isEqualTo("USD");
        assertThat(sent.adjustments()).hasSize(2);
        assertThat(sent.adjustments().get(0).adjustmentType()).isEqualTo(AdjustmentType.DEALER_HOLDBACK);
        assertThat(sent.adjustments().get(0).amount()).isEqualByComparingTo(new BigDecimal("500.00"));
    }

    // ---------- 400 ----------

    @Test
    void returns400WithFieldErrorsWhenRequestIsInvalid() throws Exception {
        String invalid = """
                {
                  "payment": { "paymentMethod": "WIRE", "currency": "usd" },
                  "adjustments": [ { "adjustmentType": "DOC_FEE", "amount": -5.00 } ]
                }
                """;

        patchSettlement(invalid)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.errors[*].field",
                        containsInAnyOrder("payment.currency", "adjustments[0].amount")));

        verifyNoInteractions(settlementService);
    }

    @Test
    void returns400WhenJsonIsMalformed() throws Exception {
        patchSettlement("{ this is not json")
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));

        verifyNoInteractions(settlementService);
    }

    // ---------- 404 / 422 / 409 ----------

    @Test
    void returns404WhenLoanNotImported() throws Exception {
        when(settlementService.calculate(eq(LOAN_ID), any(SettlementRequestDto.class)))
                .thenThrow(new LoanNotImportedException(LOAN_ID));

        patchSettlement(VALID_BODY)
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Loan not imported"))
                .andExpect(jsonPath("$.loanId").value(LOAN_ID));
    }

    @Test
    void returns422WhenBusinessRuleIsViolated() throws Exception {
        when(settlementService.calculate(eq(LOAN_ID), any(SettlementRequestDto.class)))
                .thenThrow(new SettlementRuleViolationException(
                        "Payment currency CAD does not match loan currency USD"));

        patchSettlement(VALID_BODY)
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.title").value("Settlement rule violated"))
                .andExpect(jsonPath("$.detail").value("Payment currency CAD does not match loan currency USD"));
    }

    @Test
    void returns409OnConcurrentUpdate() throws Exception {
        when(settlementService.calculate(eq(LOAN_ID), any(SettlementRequestDto.class)))
                .thenThrow(new OptimisticLockingFailureException("version mismatch"));

        patchSettlement(VALID_BODY)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Concurrent update"));
    }

    // ---------- 500 ----------

    @Test
    void returns500WithoutLeakingInternals() throws Exception {
        when(settlementService.calculate(eq(LOAN_ID), any(SettlementRequestDto.class)))
                .thenThrow(new IllegalStateException("secret internal detail"));

        patchSettlement(VALID_BODY)
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred."))
                .andExpect(content().string(not(containsString("secret"))))
                .andExpect(content().string(not(containsString("IllegalStateException"))));
    }

    // ---------- GET ----------

    @Test
    void getReturns200WithSettlementDetailInBlueprintShape() throws Exception {
        when(settlementService.getSettlementDetail(LOAN_ID))
                .thenReturn(CachedSettlementDetail.from(calculatedDocument()));

        getSettlement()
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.settlement.settlementId").value(startsWith("SET-")))
                .andExpect(jsonPath("$.settlement.status").value("CALCULATED"))
                .andExpect(jsonPath("$.payment.paymentMethod").value("WIRE"))
                .andExpect(jsonPath("$.payment.currency").value("USD"))
                .andExpect(jsonPath("$.payment.beneficiaryBank").value("Sunrise Auto Group Bank"))
                .andExpect(jsonPath("$.payment.valueDate").value("2026-05-11"))
                .andExpect(jsonPath("$.adjustments", hasSize(2)))
                .andExpect(jsonPath("$.adjustments[0].adjustmentType").value("DEALER_HOLDBACK"))
                // raw JSON text, so we know the money scale (".00") survived
                .andExpect(content().string(containsString("\"netSettlement\":27850.00")))
                .andExpect(jsonPath("$.loan.loanId").value(LOAN_ID))
                .andExpect(jsonPath("$.loan.dealer.dealerName").value("Sunrise Auto Group"))
                .andExpect(jsonPath("$.loan.dealer.dealerBankName").value("Sunrise Auto Group Bank"))
                .andExpect(jsonPath("$.loan.dealer.dealerContactEmail").value("dealer@example.com"))
                .andExpect(jsonPath("$.loan.dealer.dealerBankContactEmail").value("wireops@example.com"))
                .andExpect(jsonPath("$.loan.closingDate").value("2026-05-10"))
                .andExpect(content().string(containsString("\"principalAmount\":28500.00")))
                .andExpect(content().string(containsString("\"amount\":500.00")));
    }

    @Test
    void getReturns404WhenLoanNotImported() throws Exception {
        when(settlementService.getSettlementDetail(LOAN_ID))
                .thenThrow(new LoanNotImportedException(LOAN_ID));

        getSettlement()
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Loan not imported"))
                .andExpect(jsonPath("$.loanId").value(LOAN_ID));
    }

    @Test
    void getReturns404WhenSettlementNotCalculated() throws Exception {
        when(settlementService.getSettlementDetail(LOAN_ID))
                .thenThrow(new SettlementNotCalculatedException(LOAN_ID));

        getSettlement()
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Settlement not calculated"))
                .andExpect(jsonPath("$.loanId").value(LOAN_ID));
    }

    // ---------- security: users (ALB claims) ----------

    @Test
    void getWithoutTokenIs401ProblemDetail() throws Exception {
        mockMvc.perform(get(URL, LOAN_ID))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Unauthorized"));

        verifyNoInteractions(settlementService);
    }

    @Test
    void tamperedTokenIs401() throws Exception {
        String financeOps = AlbTestTokens.forGroups("FinanceOps");
        String admin = AlbTestTokens.forGroups("SettlementAdmin");
        String forged = admin.substring(0, admin.lastIndexOf('.')) + financeOps.substring(financeOps.lastIndexOf('.'));

        getSettlementAs(forged).andExpect(status().isUnauthorized());

        verifyNoInteractions(settlementService);
    }

    /** Maker-checker: the approver role cannot calculate. Also proves a @PreAuthorize denial is 403, not 500. */
    @Test
    void settlementAdminCannotCalculate() throws Exception {
        patchSettlementAs(AlbTestTokens.forGroups("SettlementAdmin"), VALID_BODY)
                .andExpect(status().isForbidden())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Forbidden"));

        verifyNoInteractions(settlementService);
    }

    @Test
    void settlementAdminCanReadSettlement() throws Exception {
        when(settlementService.getSettlementDetail(LOAN_ID))
                .thenReturn(CachedSettlementDetail.from(calculatedDocument()));

        getSettlementAs(AlbTestTokens.forGroups("SettlementAdmin")).andExpect(status().isOk());
    }

    @Test
    void userWithoutGroupsIs403() throws Exception {
        getSettlementAs(AlbTestTokens.forGroups())
                .andExpect(status().isForbidden());

        verifyNoInteractions(settlementService);
    }

    // ---------- security: services (blueprint step 5, client credentials) ----------

    @Test
    void wireServiceTokenCanReadSettlement() throws Exception {
        when(settlementService.getSettlementDetail(LOAN_ID))
                .thenReturn(CachedSettlementDetail.from(calculatedDocument()));

        mockMvc.perform(get(URL, LOAN_ID).with(wireServiceToken()))
                .andExpect(status().isOk());
    }

    /** Least privilege: settlement.read opens the GET only. A leaked service token cannot change money. */
    @Test
    void wireServiceTokenCannotCalculate() throws Exception {
        mockMvc.perform(patch(URL, LOAN_ID)
                        .with(wireServiceToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("Forbidden"));

        verifyNoInteractions(settlementService);
    }

    /** A Bearer token that fails verification goes through OUR entry point: ProblemDetail, not Spring's empty 401. */
    @Test
    void invalidBearerTokenIs401ProblemDetail() throws Exception {
        mockMvc.perform(get(URL, LOAN_ID).header(HttpHeaders.AUTHORIZATION, "Bearer not-a-real-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Unauthorized"));

        verifyNoInteractions(settlementService);
    }
}