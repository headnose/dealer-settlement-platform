package io.github.headnose.dsp.wire.controller;

import io.github.headnose.dsp.wire.client.LoanNotImportedException;
import io.github.headnose.dsp.wire.client.SettlementNotCalculatedException;
import io.github.headnose.dsp.wire.client.SettlementServiceUnavailableException;
import io.github.headnose.dsp.wire.controller.dto.WireGenerationRequestDto;
import io.github.headnose.dsp.wire.entity.WireSubmission;
import io.github.headnose.dsp.wire.model.RecipientType;
import io.github.headnose.dsp.wire.model.WireType;
import io.github.headnose.dsp.wire.service.WireGenerationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDateTime;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WireController.class)
class WireControllerTest {

    private static final String URL = "/api/v1/wires";
    private static final String LOAN_ID = "LN-1001";
    private static final String VALID_BODY = """
            {"loanId": "LN-1001", "wireType": "SETTLEMENT"}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WireGenerationService wireGenerationService;

    private ResultActions postWire(String body) throws Exception {
        return mockMvc.perform(post(URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static WireSubmission draftWithRecipients() {
        WireSubmission submission = WireSubmission.draft(LOAN_ID, WireType.SETTLEMENT,
                "<settlementWire/>", LocalDateTime.of(2026, 11, 21, 14, 30, 25));
        submission.addRecipient(RecipientType.DEALER,
                "Riverbend Motors", "accounts@riverbend-motors.example.com");
        submission.addRecipient(RecipientType.DEALER_BANK_CONTACT,
                "Riverbend Community Bank", "wires@riverbend-bank.example.com");
        submission.addRecipient(RecipientType.AUDIT_DISTRIBUTION,
                "Compliance Audit Team", "compliance-audit@example.com");
        submission.addRecipient(RecipientType.INTERNAL_OPS_DISTRIBUTION,
                "Treasury Operations", "treasury-ops@example.com");
        return submission;
    }

    // ---------- 201 ----------

    @Test
    void returns201WithWireSubmissionInBlueprintShape() throws Exception {
        when(wireGenerationService.generate(any(WireGenerationRequestDto.class)))
                .thenReturn(draftWithRecipients());

        postWire(VALID_BODY)
                .andExpect(status().isCreated())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.submissionId").value(startsWith("SUB-")))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.createdTime").value("2026-11-21T14:30:25"))
                .andExpect(jsonPath("$.notificationRecipients", hasSize(4)))
                .andExpect(jsonPath("$.notificationRecipients[0].recipientType").value("DEALER"))
                .andExpect(jsonPath("$.notificationRecipients[0].email")
                        .value("accounts@riverbend-motors.example.com"))
                .andExpect(jsonPath("$.notificationRecipients[3].recipientType")
                        .value("INTERNAL_OPS_DISTRIBUTION"))
                // not part of the blueprint response
                .andExpect(jsonPath("$.content").doesNotExist())
                .andExpect(jsonPath("$.notificationRecipients[0].recipientId").doesNotExist());
    }

    // ---------- 400 ----------

    @Test
    void returns400WithFieldErrorsWhenFieldsAreMissing() throws Exception {
        postWire("{}")
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("loanId", "wireType")));

        verifyNoInteractions(wireGenerationService);
    }

    @Test
    void returns400ForUnknownWireType() throws Exception {
        postWire("""
                {"loanId": "LN-1001", "wireType": "BANANA"}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));

        verifyNoInteractions(wireGenerationService);
    }

    // ---------- 422 ----------

    @Test
    void returns422WhenLoanNotImported() throws Exception {
        when(wireGenerationService.generate(any(WireGenerationRequestDto.class)))
                .thenThrow(new LoanNotImportedException(LOAN_ID));

        postWire(VALID_BODY)
                .andExpect(status().is(422))
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Loan not imported"))
                .andExpect(jsonPath("$.loanId").value(LOAN_ID));
    }

    @Test
    void returns422WhenSettlementNotCalculated() throws Exception {
        when(wireGenerationService.generate(any(WireGenerationRequestDto.class)))
                .thenThrow(new SettlementNotCalculatedException(LOAN_ID));

        postWire(VALID_BODY)
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.title").value("Settlement not calculated"))
                .andExpect(jsonPath("$.loanId").value(LOAN_ID));
    }

    // ---------- 502 ----------

    @Test
    void returns502WithoutInternalsWhenSettlementServiceIsUnavailable() throws Exception {
        when(wireGenerationService.generate(any(WireGenerationRequestDto.class)))
                .thenThrow(new SettlementServiceUnavailableException(
                        "settlement-service did not answer for loan LN-1001 at http://internal-host:8082", null));

        postWire(VALID_BODY)
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.title").value("Settlement service unavailable"))
                .andExpect(content().string(not(containsString("internal-host"))));
    }

    // ---------- 500 ----------

    @Test
    void returns500WithoutLeakingInternals() throws Exception {
        when(wireGenerationService.generate(any(WireGenerationRequestDto.class)))
                .thenThrow(new IllegalStateException("No wire template configured for wire type SETTLEMENT"));

        postWire(VALID_BODY)
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred."))
                .andExpect(content().string(not(containsString("template"))))
                .andExpect(content().string(not(containsString("IllegalStateException"))));
    }
}