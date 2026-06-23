package com.eventledger.account.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * An applied transaction, keyed by {@code eventId}.
 *
 * <p>{@code eventId} is the primary key, so idempotency is enforced at the
 * persistence layer: a second insert with the same {@code eventId} fails the
 * uniqueness constraint and is treated as a no-op replay. This is the crux of
 * the exercise — duplicate delivery cannot double-apply a transaction.
 */
@Entity
@Table(
        name = "ledger_transactions",
        indexes = @Index(name = "idx_txn_account", columnList = "accountId"))
public class LedgerTransaction {

    /** Idempotency key. Globally unique across accounts; uniqueness = the PK. */
    @Id
    private String eventId;

    @Column(nullable = false)
    private String accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionType type;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false)
    private String currency;

    /** Server time the transaction was applied; used to order the listing. */
    @Column(nullable = false)
    private Instant appliedAt;

    protected LedgerTransaction() {
        // for JPA
    }

    public LedgerTransaction(
            String eventId,
            String accountId,
            TransactionType type,
            BigDecimal amount,
            String currency,
            Instant appliedAt) {
        this.eventId = eventId;
        this.accountId = accountId;
        this.type = type;
        this.amount = amount;
        this.currency = currency;
        this.appliedAt = appliedAt;
    }

    public String getEventId() {
        return eventId;
    }

    public String getAccountId() {
        return accountId;
    }

    public TransactionType getType() {
        return type;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public Instant getAppliedAt() {
        return appliedAt;
    }
}
