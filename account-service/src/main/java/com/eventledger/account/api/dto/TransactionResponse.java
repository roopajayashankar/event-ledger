package com.eventledger.account.api.dto;

import com.eventledger.account.domain.TransactionType;
import java.math.BigDecimal;

/**
 * Result of applying a transaction. {@code status} is {@code "applied"} for a
 * freshly applied event (HTTP 201) or {@code "duplicate"} for an idempotent
 * replay (HTTP 200); {@code balance} is the account's net balance either way.
 */
public record TransactionResponse(
        String accountId,
        String eventId,
        TransactionType type,
        BigDecimal amount,
        String currency,
        BigDecimal balance,
        String status) {
}
