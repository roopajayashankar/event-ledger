package com.eventledger.account.api.dto;

import com.eventledger.account.domain.TransactionType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Body of {@code GET /accounts/{id}}: account details plus its most recent
 * transactions (newest first).
 */
public record AccountResponse(
        String accountId,
        String currency,
        BigDecimal balance,
        long transactionCount,
        List<TransactionView> recentTransactions) {

    /** A single applied transaction in the account-detail listing. */
    public record TransactionView(
            String eventId,
            TransactionType type,
            BigDecimal amount,
            String currency,
            Instant appliedAt) {
    }
}
