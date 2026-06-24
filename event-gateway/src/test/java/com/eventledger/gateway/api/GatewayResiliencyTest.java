package com.eventledger.gateway.api;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Resilience4j behaviour on the Gateway -> Account calls: retry transient
 * failures (not 4xx), open the breaker under sustained failure and then fast-fail
 * with 503, and — critically — keep local {@code GET /events} working while
 * Account is down.
 */
// Distinct property -> distinct cached context, so this class's
// @DynamicPropertySource (its own WireMock port) isn't shadowed by another
// test class's shared context.
@SpringBootTest(properties = "test.suite=gateway-resiliency")
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GatewayResiliencyTest {

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

    @BeforeEach
    void resetState() {
        account.resetAll();
        circuitBreakerRegistry.circuitBreaker("accountService").reset();
    }

    private String event(String eventId, String accountId) {
        return """
                {"eventId":"%s","accountId":"%s","type":"CREDIT","amount":10.00,"currency":"USD","eventTimestamp":"2026-06-01T10:00:00Z"}
                """.formatted(eventId, accountId);
    }

    private void submit(String eventId, String accountId, int expectedStatus) throws Exception {
        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(event(eventId, accountId)))
                .andExpect(status().is(expectedStatus));
    }

    private void stubApply(int statusCode) {
        account.stubFor(WireMock.post(urlPathMatching("/accounts/.*/transactions"))
                .willReturn(aResponse().withStatus(statusCode)
                        .withHeader("Content-Type", "application/json").withBody("{}")));
    }

    @Test
    @Order(0)
    void retriesThreeTimesWithBackoffOnSustainedServerError() throws Exception {
        stubApply(500);

        long startNanos = System.nanoTime();
        submit("evt-backoff", "acc-bk", 503);
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

        // Three attempts were made (initial + 2 retries) — retry is active.
        account.verify(3, postRequestedFor(urlPathMatching("/accounts/acc-bk/transactions")));
        // And they were spaced by exponential backoff (~100ms + ~200ms, jittered),
        // not fired instantly — so the elapsed time reflects real waits.
        assertThat(elapsedMs).isGreaterThanOrEqualTo(150L);
    }

    @Test
    @Order(1)
    void retriesTransientServerErrorThenSucceeds() throws Exception {
        account.stubFor(WireMock.post(urlPathMatching("/accounts/.*/transactions"))
                .inScenario("flaky").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(500))
                .willSetStateTo("recovered"));
        account.stubFor(WireMock.post(urlPathMatching("/accounts/.*/transactions"))
                .inScenario("flaky").whenScenarioStateIs("recovered")
                .willReturn(aResponse().withStatus(201)
                        .withHeader("Content-Type", "application/json").withBody("{}")));

        submit("evt-flaky", "acc-f", 201);

        // Two downstream attempts: the 500, then the retried call that succeeds.
        account.verify(2, postRequestedFor(urlPathMatching("/accounts/acc-f/transactions")));
    }

    @Test
    @Order(2)
    void doesNotRetryClientErrorAndReturns502() throws Exception {
        account.stubFor(WireMock.post(urlPathMatching("/accounts/.*/transactions"))
                .willReturn(aResponse().withStatus(422)
                        .withHeader("Content-Type", "application/problem+json")
                        .withBody("{\"title\":\"Currency mismatch\"}")));

        submit("evt-4xx", "acc-4", 502);

        // Exactly one attempt — a 4xx is not retried.
        account.verify(1, postRequestedFor(urlPathMatching("/accounts/acc-4/transactions")));
        // And nothing was persisted.
        mockMvc.perform(get("/events/{id}", "evt-4xx")).andExpect(status().isNotFound());
    }

    @Test
    @Order(99)
    void breakerOpensAndFastFailsWithoutCallingAccount() throws Exception {
        stubApply(500);

        // Drive sustained failures to trip the breaker.
        for (int i = 0; i < 4; i++) {
            submit("evt-open-" + i, "acc-o", 503);
        }

        int before = account.findAll(postRequestedFor(urlPathMatching("/accounts/.*/transactions"))).size();
        // With the breaker open, the next submit fast-fails and never reaches Account.
        submit("evt-open-final", "acc-o", 503);
        int after = account.findAll(postRequestedFor(urlPathMatching("/accounts/.*/transactions"))).size();

        assertThat(after).isEqualTo(before);
        assertThat(circuitBreakerRegistry.circuitBreaker("accountService").getState().name())
                .isIn("OPEN", "HALF_OPEN");
    }

    @Test
    @Order(3)
    void localEventReadsKeepWorkingWhenAccountIsDown() throws Exception {
        // Persist an event while Account is healthy.
        stubApply(201);
        submit("evt-up", "acc-down", 201);

        // Account goes down.
        account.resetAll();
        stubApply(500);

        // Writes degrade to 503...
        submit("evt-while-down", "acc-down", 503);

        // ...but local reads still work, regardless of Account health.
        mockMvc.perform(get("/events/{id}", "evt-up"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value("evt-up"));
        mockMvc.perform(get("/events").param("account", "acc-down"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].eventId").value("evt-up"));
    }
}
