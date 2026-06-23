package com.eventledger.gateway.service;

import com.eventledger.gateway.api.dto.BalanceResponse;

/**
 * Port for the synchronous calls to Account Service. Kept as an interface so the
 * REST implementation can be swapped (e.g. for a resiliency-wrapped one) and so
 * callers can be tested without a live downstream.
 *
 * <p>Per the write-order rule (CLAUDE.md): the Gateway applies first and only
 * persists the event on success. {@link #apply} therefore throws
 * {@link AccountUnavailableException} when Account is unreachable so the caller
 * can return 503 and skip the write.
 */
public interface AccountClient {

    /**
     * Applies the event's transaction to its account.
     *
     * @throws AccountUnavailableException if Account is unreachable / failing
     *         (connectivity, timeout, or 5xx) — caller returns 503, no persist
     * @throws AccountRejectedException if Account returns a 4xx (the request was
     *         understood but rejected; not retryable, not persisted)
     */
    void apply(ApplyTransactionCommand command);

    /**
     * Reads an account's current balance (proxied to clients).
     *
     * @throws AccountNotFoundException if Account returns 404
     * @throws AccountUnavailableException if Account is unreachable / failing
     */
    BalanceResponse getBalance(String accountId);
}
