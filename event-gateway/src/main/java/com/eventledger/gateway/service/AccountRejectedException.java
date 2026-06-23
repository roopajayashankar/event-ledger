package com.eventledger.gateway.service;

/**
 * Account Service returned a 4xx — the request was understood but rejected (e.g.
 * a business rule like currency mismatch). Not a degradation and not retryable;
 * the event is not persisted. Maps to {@code 502 Bad Gateway}.
 */
public class AccountRejectedException extends RuntimeException {

    private final int downstreamStatus;

    public AccountRejectedException(int downstreamStatus, Throwable cause) {
        super("Account service rejected the transaction (status " + downstreamStatus + ")", cause);
        this.downstreamStatus = downstreamStatus;
    }

    public int getDownstreamStatus() {
        return downstreamStatus;
    }
}
