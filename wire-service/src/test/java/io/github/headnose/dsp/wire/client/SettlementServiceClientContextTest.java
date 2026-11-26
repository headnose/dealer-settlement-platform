package io.github.headnose.dsp.wire.client;

import com.sun.net.httpserver.HttpServer;
import io.github.headnose.dsp.wire.config.MdcPropagationConfig;
import io.github.headnose.dsp.wire.logging.MdcKeys;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.context.ContextRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Hooks;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O3 (LG4): the caller's MDC (userId, loanId) reaches the Reactor Netty thread that handles the response.
 * Set up like the running app: automatic context propagation on (what Boot does for
 * spring.reactor.context-propagation=auto) and our keys registered (MdcPropagationConfig).
 * Relies on contextCapture() in SettlementServiceClient: without it only the trace arrives (seen live).
 */
class SettlementServiceClientContextTest {

    /** A real settlement-service response (same as SettlementServiceClientTest). */
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

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0); // port 0 = any free port
        server.createContext("/api/v1/loans/", exchange -> {
            byte[] body = DETAIL_JSON.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
            exchange.close();
        });
        server.start();
        baseUrl = "http://localhost:" + server.getAddress().getPort();
        MdcPropagationConfig.register(ContextRegistry.getInstance());
        Hooks.enableAutomaticContextPropagation();
    }

    @AfterEach
    void tearDown() {
        // Global switch: turn it off again so this test does not change how later tests behave
        Hooks.disableAutomaticContextPropagation();
        MDC.clear();
        server.stop(0);
    }

    @Test
    void callerMdcIsVisibleOnTheReactorThreadThatHandlesTheResponse() {
        AtomicReference<String> responseThread = new AtomicReference<>();
        AtomicReference<String> userId = new AtomicReference<>();
        AtomicReference<String> loanId = new AtomicReference<>();
        // Stands in for ServiceTokenFilter: records what the MDC holds when the response arrives
        ExchangeFilterFunction recordingFilter = (request, next) -> next.exchange(request)
                .doOnNext(response -> {
                    responseThread.set(Thread.currentThread().getName());
                    userId.set(MDC.get(MdcKeys.USER_ID));
                    loanId.set(MDC.get(MdcKeys.LOAN_ID));
                });
        SettlementServiceClient client = new SettlementServiceClient(WebClient.builder(),
                CircuitBreakerRegistry.ofDefaults(), recordingFilter, baseUrl,
                Duration.ofSeconds(1), Duration.ofSeconds(1));
        MDC.put(MdcKeys.USER_ID, "dev-finops");
        MDC.put(MdcKeys.LOAN_ID, "LN-1001");

        client.getSettlementDetailAsync("LN-1001").join();

        assertThat(responseThread.get()).startsWith("reactor-http");   // really another thread
        assertThat(userId.get()).isEqualTo("dev-finops");
        assertThat(loanId.get()).isEqualTo("LN-1001");
    }
}