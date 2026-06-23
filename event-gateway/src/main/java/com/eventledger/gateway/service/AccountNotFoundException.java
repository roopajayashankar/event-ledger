package com.eventledger.gateway.service;

/** Account Service returned 404 for a balance read. Maps to {@code 404}. */
public class AccountNotFoundException extends RuntimeException {

    public AccountNotFoundException(String accountId) {
        super("Account not found: " + accountId);
    }
}
