package com.eventledger.gateway.api;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Integration tests for the real Account Service wiring, with Account stubbed by
 * WireMock. The Account base URL is overridden at runtime via
 * {@link DynamicPropertySource} to point at the WireMock port — proving the URL
 * is configurable.
 *
 * <p>Verifies the consistency model end to end: on success the event is applied
 * downstream first and then persisted (readable locally); on Account failure the
 * Gateway returns 503 and does <em>not</em> persist.
 */
// Distinct property -> distinct cached context so this class's
// @DynamicPropertySource (its own WireMock port) is not shared with another
// test class that points at a different/stopped WireMock.
@SpringBootTest(properties = "test.suite=gateway-account-integration")
@AutoConfigureMockMvc
class GatewayAccountIntegrationTest {

    private static WireMockServer account;

    @BeforeAll
    static void startAccountStub() {
        account = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        account.start();
    }

    @AfterAll
    static void stopAccountStub() {
        account.stop();
    }

    @DynamicPropertySource
    static void accountServiceProperties(DynamicPropertyRegistry registry) {
        registry.add("account-service.base-url", () -> "http://localhost:" + account.port());
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired
    private ObservationRegistry observationRegistry;

    @BeforeEach
    void resetState() {
        account.resetAll();
        // The breaker is shared across the cached context — reset so each test
        // starts CLOSED and they stay independent.
        circuitBreakerRegistry.circuitBreaker("accountService").reset();
    }

    private String event(String eventId, String accountId, String amount) {
        return """
                {"eventId":"%s","accountId":"%s","type":"CREDIT","amount":%s,"currency":"USD","eventTimestamp":"2026-06-01T10:00:00Z"}
                """.formatted(eventId, accountId, amount);
    }

    @Test
    void onSuccessAppliesToAccountFirstThenPersistsLocally() throws Exception {
        account.stubFor(WireMock.post(urlPathMatching("/accounts/.*/transactions"))
                .willReturn(aResponse().withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{}")));

        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(event("evt-ok", "acc-ok", "100.00")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.eventId").value("evt-ok"));

        // Account was actually called, with the forwarded transaction body.
        account.verify(postRequestedFor(urlPathMatching("/accounts/acc-ok/transactions"))
                .withRequestBody(matchingJsonPath("$.eventId", equalTo("evt-ok")))
                .withRequestBody(matchingJsonPath("$.type", equalTo("CREDIT"))));

        // And the event was persisted locally (readable afterwards).
        mockMvc.perform(get("/events/{id}", "evt-ok"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value("evt-ok"));
    }

    @Test
    void propagatesW3cTraceparentHeaderToAccount() throws Exception {
        account.stubFor(WireMock.post(urlPathMatching("/accounts/.*/transactions"))
                .willReturn(aResponse().withStatus(201)
                        .withHeader("Content-Type", "application/json").withBody("{}")));

        // Run the request inside an active trace (as a real inbound request would
        // be). The instrumented RestClient then injects a W3C traceparent so
        // Account joins the same trace — one request, one trace across both.
        // Run the request inside an active trace (as a real inbound request would
        // be). The instrumented RestClient then injects a W3C traceparent so
        // Account joins the same trace — one request, one trace across both.
        Observation request = Observation.start("test.inbound", observationRegistry);
        try (Observation.Scope scope = request.openScope()) {
            mockMvc.perform(post("/events")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(event("evt-trace", "acc-trace", "10.00")))
                    .andExpect(status().isCreated());
        } finally {
            request.stop();
        }

        account.verify(postRequestedFor(urlPathMatching("/accounts/acc-trace/transactions"))
                .withHeader("traceparent",
                        matching("^[0-9a-f]{2}-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$")));
    }

    @Test
    void onAccountFailureReturns503AndDoesNotPersist() throws Exception {
        account.stubFor(WireMock.post(urlPathMatching("/accounts/.*/transactions"))
                .willReturn(aResponse().withStatus(500)));

        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(event("evt-fail", "acc-fail", "100.00")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.title").value("Account service unavailable"));

        // Critical: nothing was persisted, so the log never diverges from balances.
        mockMvc.perform(get("/events/{id}", "evt-fail"))
                .andExpect(status().isNotFound());
    }

    @Test
    void balanceProxyForwardsToAccount() throws Exception {
        account.stubFor(WireMock.get(urlPathMatching("/accounts/acc-1/balance"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"accountId\":\"acc-1\",\"balance\":150.50,\"currency\":\"USD\"}")));

        mockMvc.perform(get("/accounts/{id}/balance", "acc-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value("acc-1"))
                .andExpect(jsonPath("$.balance").value(150.50))
                .andExpect(jsonPath("$.currency").value("USD"));
    }

    @Test
    void balanceProxyReturns503WhenAccountDown() throws Exception {
        account.stubFor(WireMock.get(urlPathMatching("/accounts/.*/balance"))
                .willReturn(aResponse().withStatus(500)));

        mockMvc.perform(get("/accounts/{id}/balance", "acc-x"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.title").value("Account service unavailable"));
    }

    @Test
    void balanceProxyReturns404WhenAccountUnknown() throws Exception {
        account.stubFor(WireMock.get(urlPathMatching("/accounts/.*/balance"))
                .willReturn(aResponse().withStatus(404)
                        .withHeader("Content-Type", "application/problem+json")
                        .withBody("{\"title\":\"Account not found\",\"status\":404}")));

        mockMvc.perform(get("/accounts/{id}/balance", "ghost"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Account not found"));
    }
}
