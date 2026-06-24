package com.eventledger.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.eventledger.account.AccountServiceApplication;
import com.eventledger.gateway.EventGatewayApplication;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Full end-to-end flow across BOTH real services (CLAUDE.md requirement 8).
 *
 * <p>Boots the real Account Service on a random port in this JVM, points the
 * Gateway (the {@code @SpringBootTest} subject) at it, and drives
 * {@code POST /events} → balance through real HTTP. This exercises the actual
 * RestClient call, the write-order rule, and the dual-layer idempotency
 * guarantee against the genuine Account Service — not a stub.
 */
@SpringBootTest(classes = EventGatewayApplication.class)
@AutoConfigureMockMvc
class FullFlowIntegrationTest {

    private static ConfigurableApplicationContext accountApp;

    @DynamicPropertySource
    static void startRealAccountServiceAndWireGateway(DynamicPropertyRegistry registry) {
        if (accountApp == null) {
            accountApp = new SpringApplicationBuilder(AccountServiceApplication.class)
                    .properties(
                            "server.port=0",
                            "spring.datasource.url=jdbc:h2:mem:account-it;DB_CLOSE_DELAY=-1",
                            "management.tracing.enabled=false")
                    .run();
        }
        registry.add("account-service.base-url",
                () -> "http://localhost:" + accountApp.getEnvironment().getProperty("local.server.port"));
    }

    @AfterAll
    static void stopAccountService() {
        if (accountApp != null) {
            accountApp.close();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    private String event(String eventId, String type, String amount, String timestamp) {
        return """
                {"eventId":"%s","accountId":"acct-1","type":"%s","amount":%s,"currency":"USD","eventTimestamp":"%s"}
                """.formatted(eventId, type, amount, timestamp);
    }

    @Test
    void postEventsThenBalanceFlowEndToEndAcrossBothServices() throws Exception {
        // CREDIT 100 then DEBIT 30, applied through the real Account Service.
        mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON)
                        .content(event("evt-it-1", "CREDIT", "100.00", "2026-06-01T10:00:00Z")))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON)
                        .content(event("evt-it-2", "DEBIT", "30.00", "2026-06-02T10:00:00Z")))
                .andExpect(status().isCreated());

        // Balance proxied through the Gateway to the real Account Service: 100 - 30.
        mockMvc.perform(get("/accounts/{id}/balance", "acct-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value("acct-1"))
                .andExpect(jsonPath("$.balance").value(70.00));

        // Dual-layer idempotency end to end: replaying evt-it-1 is a no-op (200),
        // and the balance is unchanged because Account also dedupes by eventId.
        mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON)
                        .content(event("evt-it-1", "CREDIT", "100.00", "2026-06-01T10:00:00Z")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/accounts/{id}/balance", "acct-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(70.00));

        // The Gateway's local log lists both events in eventTimestamp order.
        mockMvc.perform(get("/events").param("account", "acct-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].eventId").value("evt-it-1"))
                .andExpect(jsonPath("$[1].eventId").value("evt-it-2"));
    }
}
