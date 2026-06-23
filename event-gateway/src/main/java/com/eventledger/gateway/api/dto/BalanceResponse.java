package com.eventledger.gateway.api.dto;

import java.math.BigDecimal;

/**
 * Balance as returned to clients by the proxy {@code GET /accounts/{id}/balance}.
 * Field names mirror Account Service's balance contract so the response
 * deserializes straight through.
 */
public record BalanceResponse(String accountId, BigDecimal balance, String currency) {
}
