package com.eventledger.gateway.service;

import com.eventledger.gateway.domain.EventType;
import java.math.BigDecimal;

/**
 * Inputs to the apply step. Mirrors the body Account Service expects on
 * {@code POST /accounts/{accountId}/transactions} ({@code eventId} carries the
 * idempotency guarantee through to the downstream dedupe).
 */
public record ApplyTransactionCommand(
        String accountId,
        String eventId,
        EventType type,
        BigDecimal amount,
        String currency) {
}
