package io.github.headnose.dsp.settlement.controller;

import io.github.headnose.dsp.settlement.controller.dto.SettlementRequest;
import io.github.headnose.dsp.settlement.document.LoanSettlementDocument;
import io.github.headnose.dsp.settlement.model.AdjustmentType;
import io.github.headnose.dsp.settlement.service.LoanNotImportedException;
import io.github.headnose.dsp.settlement.service.SettlementRuleViolationException;
import io.github.headnose.dsp.settlement.service.SettlementService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
// ⬇️ copy the @WebMvcTest import line from your loan-service test
// import org.springframework.boot. ... .WebMvcTest;

import java.math.BigDecimal;
import java.util.List;

import static io.github.headnose.dsp.settlement.SettlementTestData.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SettlementController.class)
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
        return mockMvc.perform(patch(URL, LOAN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
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
        when(settlementService.calculate(eq(LOAN_ID), any(SettlementRequest.class)))
                .thenReturn(calculatedDocument());

        patchSettlement(VALID_BODY)
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.loanId").value(LOAN_ID))
                .andExpect(jsonPath("$.status").value("CALCULATED"))
                // check the raw JSON text so we know the scale (".00") survived
                .andExpect(content().string(containsString("\"netSettlement\":27850.00")))
                .andExpect(content().string(containsString("\"totalAdjustment\":650.00")));
    }

    @Test
    void passesParsedRequestToService() throws Exception {
        when(settlementService.calculate(eq(LOAN_ID), any(SettlementRequest.class)))
                .thenReturn(calculatedDocument());

        patchSettlement(VALID_BODY).andExpect(status().isOk());

        ArgumentCaptor<SettlementRequest> captor = ArgumentCaptor.forClass(SettlementRequest.class);
        verify(settlementService).calculate(eq(LOAN_ID), captor.capture());
        SettlementRequest sent = captor.getValue();
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
        when(settlementService.calculate(eq(LOAN_ID), any(SettlementRequest.class)))
                .thenThrow(new LoanNotImportedException(LOAN_ID));

        patchSettlement(VALID_BODY)
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Loan not imported"))
                .andExpect(jsonPath("$.loanId").value(LOAN_ID));
    }

    @Test
    void returns422WhenBusinessRuleIsViolated() throws Exception {
        when(settlementService.calculate(eq(LOAN_ID), any(SettlementRequest.class)))
                .thenThrow(new SettlementRuleViolationException(
                        "Payment currency CAD does not match loan currency USD"));

        patchSettlement(VALID_BODY)
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.title").value("Settlement rule violated"))
                .andExpect(jsonPath("$.detail").value("Payment currency CAD does not match loan currency USD"));
    }

    @Test
    void returns409OnConcurrentUpdate() throws Exception {
        when(settlementService.calculate(eq(LOAN_ID), any(SettlementRequest.class)))
                .thenThrow(new OptimisticLockingFailureException("version mismatch"));

        patchSettlement(VALID_BODY)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Concurrent update"));
    }

    // ---------- 500 ----------

    @Test
    void returns500WithoutLeakingInternals() throws Exception {
        when(settlementService.calculate(eq(LOAN_ID), any(SettlementRequest.class)))
                .thenThrow(new IllegalStateException("secret internal detail"));

        patchSettlement(VALID_BODY)
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred."))
                .andExpect(content().string(not(containsString("secret"))))
                .andExpect(content().string(not(containsString("IllegalStateException"))));
    }
}