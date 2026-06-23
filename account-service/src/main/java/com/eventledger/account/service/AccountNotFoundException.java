package com.eventledger.account.service;

/** Raised when a balance/detail read targets an account with no transactions. */
public class AccountNotFoundException extends RuntimeException {

    public AccountNotFoundException(String accountId) {
        super("Account not found: " + accountId);
    }
}
