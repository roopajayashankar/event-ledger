package com.eventledger.account.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.eventledger.account.api.dto.BalanceResponse;
import com.eventledger.account.api.dto.TransactionResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Full-stack tests over the real H2 store. The headline behaviour — idempotent
 * application by {@code eventId} — is verified against actual persistence, not
 * a mock, because the uniqueness guarantee lives there.
 *
 * <p>Each test uses distinct account/event ids so methods stay independent
 * despite sharing the application context (and therefore the in-memory DB).
 */
@SpringBootTest
@AutoConfigureMockMvc
class AccountControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String tx(String eventId, String type, String amount, String currency) {
        return """
                {"eventId":"%s","type":"%s","amount":%s,"currency":"%s"}
                """.formatted(eventId, type, amount, currency);
    }

    private MvcResult postTx(String accountId, String body) throws Exception {
        return mockMvc.perform(post("/accounts/{id}/transactions", accountId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    @Test
    void appliesCreditCreatesAccountAndReturns201WithBalance() throws Exception {
        mockMvc.perform(post("/accounts/{id}/transactions", "acc-credit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tx("evt-1", "CREDIT", "100.00", "USD")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accountId").value("acc-credit"))
                .andExpect(jsonPath("$.status").value("applied"));

        BalanceResponse balance = getBalance("acc-credit");
        assertThat(balance.balance()).isEqualByComparingTo("100.00");
        assertThat(balance.currency()).isEqualTo("USD");
    }

    @Test
    void replayingSameEventIdIsNoOpAndReturnsSameBalance() throws Exception {
        String accountId = "acc-replay";
        MvcResult first = postTx(accountId, tx("evt-dup", "CREDIT", "50.00", "USD"));
        assertThat(first.getResponse().getStatus()).isEqualTo(201);

        // Replay the very same eventId — even with a different amount, it must
        // be ignored. Idempotency keys off eventId, not the payload.
        MvcResult replay = postTx(accountId, tx("evt-dup", "CREDIT", "999.00", "USD"));
        TransactionResponse replayBody =
                objectMapper.readValue(replay.getResponse().getContentAsString(), TransactionResponse.class);

        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replayBody.status()).isEqualTo("duplicate");
        // Balance reflects the original 50.00, not the replayed 999.00.
        assertThat(replayBody.balance()).isEqualByComparingTo("50.00");

        // And exactly one transaction was ever stored.
        mockMvc.perform(get("/accounts/{id}", accountId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionCount").value(1));
    }

    @Test
    void balanceIsNetOfCreditsAndDebits() throws Exception {
        String accountId = "acc-net";
        postTx(accountId, tx("evt-c1", "CREDIT", "200.00", "USD"));
        postTx(accountId, tx("evt-d1", "DEBIT", "75.00", "USD"));
        postTx(accountId, tx("evt-c2", "CREDIT", "25.50", "USD"));

        // 200 - 75 + 25.50 = 150.50
        assertThat(getBalance(accountId).balance()).isEqualByComparingTo("150.50");
    }

    @Test
    void debitMayDriveBalanceNegativeWithoutOverdraftGuard() throws Exception {
        String accountId = "acc-overdraft";
        postTx(accountId, tx("evt-od", "DEBIT", "40.00", "USD"));

        assertThat(getBalance(accountId).balance()).isEqualByComparingTo("-40.00");
    }

    @Test
    void getAccountReturnsDetailsAndRecentTransactions() throws Exception {
        String accountId = "acc-detail";
        postTx(accountId, tx("evt-a", "CREDIT", "10.00", "USD"));
        postTx(accountId, tx("evt-b", "DEBIT", "4.00", "USD"));

        mockMvc.perform(get("/accounts/{id}", accountId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(accountId))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.balance").value(6.00))
                .andExpect(jsonPath("$.transactionCount").value(2))
                .andExpect(jsonPath("$.recentTransactions.length()").value(2));
    }

    @Test
    void unknownAccountBalanceReturns404ProblemDetail() throws Exception {
        mockMvc.perform(get("/accounts/{id}/balance", "nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Account not found"))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void invalidAmountReturns400ProblemDetailWithFieldErrors() throws Exception {
        mockMvc.perform(post("/accounts/{id}/transactions", "acc-bad")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tx("evt-bad", "CREDIT", "0", "USD")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid request"))
                .andExpect(jsonPath("$.errors[0].field").value("amount"));
    }

    @Test
    void missingRequiredFieldsReturns400ProblemDetail() throws Exception {
        // No eventId and no currency — both are @NotBlank. Field-level detail
        // names exactly which fields failed.
        String body = """
                {"type":"CREDIT","amount":10.00}
                """;
        mockMvc.perform(post("/accounts/{id}/transactions", "acc-missing")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid request"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errors.length()").value(2))
                .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("eventId", "currency")));
    }

    @Test
    void unknownTransactionTypeReturns400() throws Exception {
        mockMvc.perform(post("/accounts/{id}/transactions", "acc-badtype")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tx("evt-badtype", "TRANSFER", "10.00", "USD")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void mismatchedCurrencyReturns422() throws Exception {
        String accountId = "acc-currency";
        postTx(accountId, tx("evt-usd", "CREDIT", "10.00", "USD"));

        mockMvc.perform(post("/accounts/{id}/transactions", accountId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tx("evt-eur", "CREDIT", "10.00", "EUR")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.title").value("Currency mismatch"));
    }

    private BalanceResponse getBalance(String accountId) throws Exception {
        MvcResult result = mockMvc.perform(get("/accounts/{id}/balance", accountId))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), BalanceResponse.class);
    }
}
