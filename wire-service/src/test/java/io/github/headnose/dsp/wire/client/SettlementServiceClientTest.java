package io.github.headnose.dsp.wire.client;

import com.sun.net.httpserver.HttpServer;
import io.github.headnose.dsp.wire.client.dto.SettlementDetailDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real HTTP against a JDK HttpServer on port 0 (same pattern as settlement-service's LoanServiceClientTest). */
class SettlementServiceClientTest {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration READ_TIMEOUT = Duration.ofMillis(300);
    private static final String LOAN_ID = "LN-1001";

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

    private final AtomicReference<String> requestedPath = new AtomicReference<>();

    private HttpServer server;
    private SettlementServiceClient client;

    @BeforeEach
    void startFakeSettlementService() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0); // port 0 = any free port
        server.start();
        String baseUrl = "http://localhost:" + server.getAddress().getPort();
        client = new SettlementServiceClient(WebClient.builder(), baseUrl, CONNECT_TIMEOUT, READ_TIMEOUT);
    }

    @AfterEach
    void stopFakeSettlementService() {
        server.stop(0);
    }

    @Test
    void returnsSettlementDetailOn200() {
        stub(200, "application/json", DETAIL_JSON, Duration.ZERO);

        SettlementDetailDto detail = client.getSettlementDetailAsync(LOAN_ID).join();

        assertThat(requestedPath.get()).isEqualTo("/api/v1/loans/LN-1001/settlements");
        assertThat(detail.loan().dealer().dealerContactEmail()).isEqualTo("accounts@riverbend-motors.example.com");
        assertThat(detail.loan().dealer().dealerBankContactEmail()).isEqualTo("wires@riverbend-bank.example.com");
        assertThat(detail.settlement().netSettlement()).isEqualTo(new BigDecimal("27850.00")); // scale kept
        assertThat(detail.payment().valueDate()).isEqualTo(LocalDate.of(2026, 11, 23));
        assertThat(detail.adjustments()).hasSize(2);
    }

    @Test
    void throwsLoanNotImportedOn404WithThatTitle() {
        stub(404, "application/problem+json", problem("Loan not imported"), Duration.ZERO);

        assertFailsWith(LoanNotImportedException.class);
    }

    @Test
    void throwsSettlementNotCalculatedOn404WithThatTitle() {
        stub(404, "application/problem+json", problem("Settlement not calculated"), Duration.ZERO);

        assertFailsWith(SettlementNotCalculatedException.class);
    }

    @Test
    void unknown404IsUnavailable() {
        stub(404, "application/json", "", Duration.ZERO);

        assertFailsWith(SettlementServiceUnavailableException.class);
    }

    @Test
    void serverErrorIsUnavailable() {
        stub(500, "application/json", "", Duration.ZERO);

        assertFailsWith(SettlementServiceUnavailableException.class);
    }

    @Test
    void emptyBodyOn200IsUnavailable() {
        stub(200, "application/json", "", Duration.ZERO);

        assertFailsWith(SettlementServiceUnavailableException.class);
    }

    @Test
    void slowSettlementServiceTimesOutInsteadOfHanging() {
        // answers after 1s, but the client only waits 300ms between reads
        stub(200, "application/json", DETAIL_JSON, Duration.ofSeconds(1));

        assertFailsWith(SettlementServiceUnavailableException.class);
    }

    /** join() wraps the failure: the real exception is the cause. */
    private void assertFailsWith(Class<? extends Throwable> type) {
        assertThatThrownBy(() -> client.getSettlementDetailAsync(LOAN_ID).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(type);
    }

    /** A 404 body shaped like settlement-service's ProblemDetail. */
    private static String problem(String title) {
        return """
                {"title": "%s", "status": 404, "loanId": "%s"}
                """.formatted(title, LOAN_ID);
    }

    /** Makes the fake settlement-service answer every /api/v1/loans/... request with this status and body. */
    private void stub(int status, String contentType, String body, Duration delay) {
        server.createContext("/api/v1/loans/", exchange -> {
            requestedPath.set(exchange.getRequestURI().getPath());
            try {
                Thread.sleep(delay.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", contentType);
            if (bytes.length == 0) {
                exchange.sendResponseHeaders(status, -1); // -1 = no body
            } else {
                exchange.sendResponseHeaders(status, bytes.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            }
            exchange.close();
        });
    }
}