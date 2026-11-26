package io.github.headnose.dsp.settlement.client;

import com.sun.net.httpserver.HttpServer;
import io.github.headnose.dsp.settlement.client.dto.LoanDto;
import io.github.headnose.dsp.settlement.model.LoanStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoanServiceClientTest {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration READ_TIMEOUT = Duration.ofMillis(300);

    private static final String LOAN_JSON = """
            {
              "loanId": "LN-1001",
              "dealer": {
                "dealerId": "D-100",
                "dealerName": "Riverbend Motors",
                "dealerBankName": "Riverbend Community Bank",
                "dealerContactEmail": "accounts@riverbend-motors.example.com",
                "dealerBankContactEmail": "wires@riverbend-bank.example.com"
              },
              "status": "CLOSED",
              "currency": "USD",
              "principalAmount": 28500.00,
              "closingDate": "2026-10-15"
            }
            """;

    private final AtomicReference<String> requestedPath = new AtomicReference<>();

    private HttpServer server;
    private LoanServiceClient client;

    @BeforeEach
    void startFakeLoanService() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0); // port 0 = any free port
        server.start();
        String baseUrl = "http://localhost:" + server.getAddress().getPort();
        // plain builder: these tests check HTTP behaviour, not observations
        client = new LoanServiceClient(RestClient.builder(), baseUrl, CONNECT_TIMEOUT, READ_TIMEOUT);
    }

    @AfterEach
    void stopFakeLoanService() {
        server.stop(0);
    }

    @Test
    void returnsLoanOn200() {
        stub(200, LOAN_JSON, Duration.ZERO);

        Optional<LoanDto> loan = client.getLoan("LN-1001");

        assertThat(requestedPath.get()).isEqualTo("/api/v1/loans/LN-1001");
        assertThat(loan).isPresent();
        assertThat(loan.get().loanId()).isEqualTo("LN-1001");
        assertThat(loan.get().status()).isEqualTo(LoanStatus.CLOSED);
        assertThat(loan.get().principalAmount()).isEqualByComparingTo("28500.00");
    }

    @Test
    void returnsEmptyOnlyOn404() {
        stub(404, "", Duration.ZERO);

        assertThat(client.getLoan("LN-404")).isEmpty();
    }

    @Test
    void emptyBodyOn200IsAnErrorNotNotFound() {
        stub(200, "", Duration.ZERO);

        assertThatThrownBy(() -> client.getLoan("LN-1001"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LN-1001");
    }

    @Test
    void serverErrorIsThrown() {
        stub(500, "", Duration.ZERO);

        assertThatThrownBy(() -> client.getLoan("LN-1001"))
                .isInstanceOf(HttpServerErrorException.class);
    }

    @Test
    void slowLoanServiceTimesOutInsteadOfHanging() {
        // answers after 1s, but the client only waits 300ms
        stub(200, LOAN_JSON, Duration.ofSeconds(1));

        assertThatThrownBy(() -> client.getLoan("LN-1001"))
                .isInstanceOf(ResourceAccessException.class)
                .hasCauseInstanceOf(SocketTimeoutException.class);
    }

    /** Makes the fake loan-service answer every /api/v1/loans/... request with this status and body. */
    private void stub(int status, String body, Duration delay) {
        server.createContext("/api/v1/loans/", exchange -> {
            requestedPath.set(exchange.getRequestURI().getPath());
            try {
                Thread.sleep(delay.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
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