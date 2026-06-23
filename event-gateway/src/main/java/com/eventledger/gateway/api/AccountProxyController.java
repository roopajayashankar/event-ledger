package com.eventledger.gateway.api;

import com.eventledger.gateway.api.dto.BalanceResponse;
import com.eventledger.gateway.service.AccountClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thin client-facing proxy for the balance read.
 *
 * <p>The spec lists the balance endpoint only on the internal Account Service,
 * but requirement 6 calls for client-facing degradation of balance queries and
 * clients cannot reach the internal service directly — so the Gateway forwards
 * under the same client. Account down → 503; unknown account → 404 (CLAUDE.md).
 */
@RestController
@RequestMapping("/accounts")
public class AccountProxyController {

    private final AccountClient accountClient;

    public AccountProxyController(AccountClient accountClient) {
        this.accountClient = accountClient;
    }

    @GetMapping("/{accountId}/balance")
    public BalanceResponse balance(@PathVariable String accountId) {
        return accountClient.getBalance(accountId);
    }
}
