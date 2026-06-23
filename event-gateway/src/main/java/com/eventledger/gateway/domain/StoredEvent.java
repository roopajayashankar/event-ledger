package com.eventledger.gateway.domain;

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
 * A row in the Gateway's local event log.
 *
 * <p>{@code eventId} is the primary key, so idempotency at the API boundary is
 * enforced by the persistence layer: a duplicate submission cannot create a
 * second row. The log only ever holds events that were successfully applied
 * (we persist after the apply step succeeds).
 *
 * <p>{@code eventTimestamp} is the authoritative business time used to order the
 * listing; {@code receivedAt} (arrival time) is recorded for audit only and is
 * deliberately never used for ordering or balance (documented assumption).
 */
@Entity
@Table(
        name = "events",
        indexes = @Index(name = "idx_event_account", columnList = "accountId"))
public class StoredEvent {

    @Id
    private String eventId;

    @Column(nullable = false)
    private String accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EventType type;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false)
    private String currency;

    @Column(nullable = false)
    private Instant eventTimestamp;

    @Column(nullable = false)
    private Instant receivedAt;

    protected StoredEvent() {
        // for JPA
    }

    public StoredEvent(
            String eventId,
            String accountId,
            EventType type,
            BigDecimal amount,
            String currency,
            Instant eventTimestamp,
            Instant receivedAt) {
        this.eventId = eventId;
        this.accountId = accountId;
        this.type = type;
        this.amount = amount;
        this.currency = currency;
        this.eventTimestamp = eventTimestamp;
        this.receivedAt = receivedAt;
    }

    public String getEventId() {
        return eventId;
    }

    public String getAccountId() {
        return accountId;
    }

    public EventType getType() {
        return type;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public Instant getEventTimestamp() {
        return eventTimestamp;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }
}
