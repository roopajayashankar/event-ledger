package com.eventledger.account.service;

/**
 * Raised when a transaction's currency differs from the one the account was
 * opened with. Enforces the documented single-currency-per-account assumption
 * (CLAUDE.md): we store and validate currency, we never convert.
 */
public class CurrencyMismatchException extends RuntimeException {

    public CurrencyMismatchException(String accountId, String expected, String actual) {
        super("Account " + accountId + " uses currency " + expected
                + ", but the transaction specified " + actual);
    }
}
