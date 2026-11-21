package io.github.headnose.dsp.wire.client;

import io.github.headnose.dsp.wire.client.dto.SettlementDetailDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class SettlementServiceClientTest {

    private static final String BASE_URL = "http://settlement-service";
    private static final String LOAN_ID = "LN-1001";
    private static final String URL = BASE_URL + "/api/v1/loans/" + LOAN_ID + "/settlements";

    /** A real settlement-service response, copied from the README. */
    private static final String DETAIL_JSON = """
            {
              "loan": {
                "loanId": "LN-1001",
                "dealer": {"dealerId": "D-100", "dealerName": "Riverbend Motors",
                           "dealerBankName": "Riverbend Community Bank",
                           "dealerContactEmail": "accounts@riverbend-motors.example.com",
                           "dealerBankContactEmail": "wires@riverbend-bank.example.com"},
                "status": "CLOSED", "currency": "USD", "principalAmount": 28500.00, "closingDate": "2026-10-15"
              },
              "settlement": {"settlementId": "SET-123", "grossAmount": 28500.00, "totalAdjustment": 650.00,
                             "netSettlement": 27850.00, "status": "CALCULATED"},
              "payment": {"paymentMethod": "WIRE", "currency": "USD",
                          "beneficiaryBank": "Riverbend Community Bank", "valueDate": "2026-11-23"},
              "adjustments": [
                {"adjustmentType": "DEALER_HOLDBACK", "amount": 500.00},
                {"adjustmentType": "DOC_FEE", "amount": 150.00}
              ]
            }
            """;

    private MockRestServiceServer server;
    private SettlementServiceClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new SettlementServiceClient(builder, BASE_URL);
    }

    @AfterEach
    void verifyExpectedCallWasMade() {
        server.verify();
    }

    @Test
    void returnsSettlementDetail() {
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(DETAIL_JSON, MediaType.APPLICATION_JSON));

        SettlementDetailDto detail = client.getSettlementDetail(LOAN_ID);

        assertThat(detail.loan().dealer().dealerContactEmail()).isEqualTo("accounts@riverbend-motors.example.com");
        assertThat(detail.loan().dealer().dealerBankContactEmail()).isEqualTo("wires@riverbend-bank.example.com");
        assertThat(detail.settlement().netSettlement()).isEqualTo(new BigDecimal("27850.00")); // scale kept
        assertThat(detail.payment().valueDate()).isEqualTo(LocalDate.of(2026, 11, 23));
        assertThat(detail.adjustments()).hasSize(2);
    }

    @Test
    void throwsLoanNotImportedOn404WithThatTitle() {
        server.expect(requestTo(URL))
                .andRespond(notFound("Loan not imported"));

        assertThatThrownBy(() -> client.getSettlementDetail(LOAN_ID))
                .isInstanceOf(LoanNotImportedException.class);
    }

    @Test
    void throwsSettlementNotCalculatedOn404WithThatTitle() {
        server.expect(requestTo(URL))
                .andRespond(notFound("Settlement not calculated"));

        assertThatThrownBy(() -> client.getSettlementDetail(LOAN_ID))
                .isInstanceOf(SettlementNotCalculatedException.class);
    }

    @Test
    void throwsUnavailableOnServerError() {
        server.expect(requestTo(URL))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.getSettlementDetail(LOAN_ID))
                .isInstanceOf(SettlementServiceUnavailableException.class);
    }

    /** A 404 shaped like settlement-service's ProblemDetail. */
    private static org.springframework.test.web.client.ResponseCreator notFound(String title) {
        return withStatus(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body("""
                        {"title": "%s", "status": 404, "loanId": "%s"}
                        """.formatted(title, LOAN_ID));
    }
}