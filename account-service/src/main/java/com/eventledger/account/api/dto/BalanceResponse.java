package com.eventledger.account.api.dto;

import java.math.BigDecimal;

/** Body of {@code GET /accounts/{id}/balance}: the current net balance. */
public record BalanceResponse(String accountId, BigDecimal balance, String currency) {
}
