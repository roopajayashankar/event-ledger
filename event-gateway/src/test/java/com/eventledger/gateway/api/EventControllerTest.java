package com.eventledger.gateway.api;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.eventledger.gateway.service.AccountClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Full-stack tests over the real H2 store. The apply step is replaced with a
 * mock {@link AccountClient} so the local flow is exercised in isolation (the
 * real downstream call is wired later) and so we can assert that a duplicate
 * submission does no work — no apply call.
 *
 * <p>Each test uses distinct ids so methods stay independent despite sharing the
 * application context (and therefore the in-memory DB).
 */
@SpringBootTest
@AutoConfigureMockMvc
class EventControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AccountClient accountClient;

    private String event(String eventId, String accountId, String type,
                         String amount, String currency, String timestamp) {
        return """
                {"eventId":"%s","accountId":"%s","type":"%s","amount":%s,"currency":"%s","eventTimestamp":"%s"}
                """.formatted(eventId, accountId, type, amount, currency, timestamp);
    }

    private void submit(String body) throws Exception {
        mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void submitStoresEventReturns201AndIsReadableById() throws Exception {
        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(event("evt-1", "acc-1", "CREDIT", "100.00", "USD", "2026-06-01T10:00:00Z")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.eventId").value("evt-1"))
                .andExpect(jsonPath("$.accountId").value("acc-1"));

        mockMvc.perform(get("/events/{id}", "evt-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value("evt-1"))
                .andExpect(jsonPath("$.amount").value(100.00));

        // The apply step ran exactly once for the new event.
        verify(accountClient, times(1)).apply(any());
    }

    @Test
    void duplicateEventIdReturns200WithStoredEventAndDoesNoWork() throws Exception {
        submit(event("evt-dup", "acc-2", "CREDIT", "50.00", "USD", "2026-06-01T10:00:00Z"));

        // Re-submit the same eventId with a different amount: it must be ignored
        // and the original returned. 200, not 201.
        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(event("evt-dup", "acc-2", "CREDIT", "999.00", "USD", "2026-06-02T10:00:00Z")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value("evt-dup"))
                .andExpect(jsonPath("$.amount").value(50.00));

        // Apply was called once (the first submit) and never for the duplicate.
        verify(accountClient, times(1)).apply(any());
    }

    @Test
    void listByAccountIsSortedByEventTimestampAscendingNotInsertionOrder() throws Exception {
        String account = "acc-sorted";
        // Insert deliberately out of business-time order.
        submit(event("evt-late", account, "CREDIT", "10.00", "USD", "2026-06-03T00:00:00Z"));
        submit(event("evt-early", account, "CREDIT", "10.00", "USD", "2026-06-01T00:00:00Z"));
        submit(event("evt-mid", account, "DEBIT", "4.00", "USD", "2026-06-02T00:00:00Z"));

        mockMvc.perform(get("/events").param("account", account))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].eventId").value("evt-early"))
                .andExpect(jsonPath("$[1].eventId").value("evt-mid"))
                .andExpect(jsonPath("$[2].eventId").value("evt-late"));
    }

    @Test
    void listByUnknownAccountReturnsEmptyArray() throws Exception {
        mockMvc.perform(get("/events").param("account", "nobody"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void unknownEventReturns404ProblemDetail() throws Exception {
        mockMvc.perform(get("/events/{id}", "missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Event not found"))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void invalidAmountReturns400ProblemDetailWithFieldError() throws Exception {
        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(event("evt-bad", "acc-x", "CREDIT", "0", "USD", "2026-06-01T10:00:00Z")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid request"))
                .andExpect(jsonPath("$.errors[0].field").value("amount"));

        verify(accountClient, never()).apply(any());
    }

    @Test
    void unknownTypeReturns400() throws Exception {
        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(event("evt-badtype", "acc-x", "TRANSFER", "10.00", "USD", "2026-06-01T10:00:00Z")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingRequiredFieldsReturns400WithFieldErrors() throws Exception {
        // Missing eventId, accountId, currency and eventTimestamp.
        String body = """
                {"type":"CREDIT","amount":10.00}
                """;
        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid request"))
                .andExpect(jsonPath("$.errors[*].field",
                        containsInAnyOrder("eventId", "accountId", "currency", "eventTimestamp")));
    }
}
