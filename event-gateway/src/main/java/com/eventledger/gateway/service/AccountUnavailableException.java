package com.eventledger.gateway.service;

/**
 * Account Service could not be reached or failed transiently (connectivity,
 * timeout, or 5xx). Maps to {@code 503 Service Unavailable}; the Gateway does
 * not persist the event, so the client can safely retry later (CLAUDE.md
 * graceful degradation).
 */
public class AccountUnavailableException extends RuntimeException {

    public AccountUnavailableException(Throwable cause) {
        super("Account service is unavailable", cause);
    }
}
