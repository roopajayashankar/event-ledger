package com.eventledger.gateway.api;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
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
 * A slow Account Service (responses past the read timeout) is treated as a
 * transient failure: the Gateway times out, retries, and ultimately returns 503.
 * The read timeout is shortened so the test stays fast.
 */
@SpringBootTest(properties = {
        "account-service.read-timeout=300ms",
        "test.suite=gateway-read-timeout"
})
@AutoConfigureMockMvc
class GatewayReadTimeoutTest {

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

    @Test
    void slowAccountTimesOutIsRetriedThenReturns503() throws Exception {
        // Each response is delayed well past the 300ms read timeout.
        account.stubFor(WireMock.post(urlPathMatching("/accounts/.*/transactions"))
                .willReturn(aResponse().withFixedDelay(900).withStatus(201)
                        .withHeader("Content-Type", "application/json").withBody("{}")));

        mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventId":"evt-timeout","accountId":"acc-timeout","type":"CREDIT","amount":10.00,"currency":"USD","eventTimestamp":"2026-06-01T10:00:00Z"}
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.title").value("Account service unavailable"));

        // A read timeout is transient, so all three attempts were made.
        account.verify(3, postRequestedFor(urlPathMatching("/accounts/acc-timeout/transactions")));
    }
}
