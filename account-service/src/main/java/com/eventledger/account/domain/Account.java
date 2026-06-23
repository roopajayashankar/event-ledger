package com.eventledger.account.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Account aggregate. State is intentionally minimal: the balance is never
 * stored here — it is computed by aggregating {@link LedgerTransaction} rows so
 * it cannot drift and stays correct under out-of-order / replayed delivery.
 *
 * <p>The account's {@code currency} is established by its first transaction.
 * Per the documented single-currency assumption (CLAUDE.md), later transactions
 * must use the same currency; we store and validate it, we never convert.
 */
@Entity
@Table(name = "accounts")
public class Account {

    @Id
    private String accountId;

    @Column(nullable = false)
    private String currency;

    @Column(nullable = false)
    private Instant createdAt;

    protected Account() {
        // for JPA
    }

    public Account(String accountId, String currency, Instant createdAt) {
        this.accountId = accountId;
        this.currency = currency;
        this.createdAt = createdAt;
    }

    public String getAccountId() {
        return accountId;
    }

    public String getCurrency() {
        return currency;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
