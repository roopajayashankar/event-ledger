package com.eventledger.account.api;

import com.eventledger.account.api.dto.AccountResponse;
import com.eventledger.account.api.dto.BalanceResponse;
import com.eventledger.account.api.dto.TransactionRequest;
import com.eventledger.account.api.dto.TransactionResponse;
import com.eventledger.account.domain.LedgerTransaction;
import com.eventledger.account.service.AccountService;
import com.eventledger.account.service.AccountService.ApplyResult;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal account API. Thin: validation is declarative on the DTO and all
 * business logic lives in {@link AccountService}.
 */
@RestController
@RequestMapping("/accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    /**
     * Applies a transaction idempotently by {@code eventId}.
     * {@code 201 Created} when newly applied, {@code 200 OK} for a replay.
     */
    @PostMapping("/{accountId}/transactions")
    public ResponseEntity<TransactionResponse> applyTransaction(
            @PathVariable String accountId,
            @Valid @RequestBody TransactionRequest request) {

        ApplyResult result = accountService.apply(
                accountId, request.eventId(), request.type(), request.amount(), request.currency());

        LedgerTransaction tx = result.transaction();
        TransactionResponse body = new TransactionResponse(
                tx.getAccountId(),
                tx.getEventId(),
                tx.getType(),
                tx.getAmount(),
                tx.getCurrency(),
                result.balance(),
                result.applied() ? "applied" : "duplicate");

        HttpStatus status = result.applied() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(body);
    }

    @GetMapping("/{accountId}/balance")
    public BalanceResponse balance(@PathVariable String accountId) {
        return accountService.getBalance(accountId);
    }

    @GetMapping("/{accountId}")
    public AccountResponse account(@PathVariable String accountId) {
        return accountService.getAccount(accountId);
    }
}
