package com.eventledger.gateway.service;

import java.math.BigDecimal;

/**
 * Outbound body for {@code POST /accounts/{id}/transactions} on Account Service.
 * {@code type} is sent as the plain enum name (CREDIT/DEBIT). {@code eventId}
 * carries the idempotency key through to Account's own dedupe (dual-layer
 * idempotency, CLAUDE.md).
 */
public record AccountTransactionRequest(
        String eventId,
        String type,
        BigDecimal amount,
        String currency) {
}
