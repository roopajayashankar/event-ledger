package com.eventledger.gateway.service;

/**
 * Port for the "apply the transaction" step — the downstream call to Account
 * Service. Kept as an interface so the local event-handling flow can be built
 * and tested now against a stub, then have the real RestClient + resiliency
 * implementation wired in behind the same seam without touching callers.
 *
 * <p>Per the write-order rule (CLAUDE.md): the Gateway applies first and only
 * persists the event on success. The real implementation will therefore signal
 * failure (so the caller can return 503 and not persist); the current stub
 * always succeeds.
 */
public interface AccountClient {

    /** Applies the event's transaction to its account. */
    void apply(ApplyTransactionCommand command);
}
