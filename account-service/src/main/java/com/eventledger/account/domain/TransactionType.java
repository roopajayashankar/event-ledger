package com.eventledger.account.domain;

/**
 * Direction of a ledger transaction. The net balance is
 * {@code sum(CREDIT) - sum(DEBIT)} — a commutative aggregation, so arrival
 * order does not affect it (see CLAUDE.md, "Balance is order-independent").
 */
public enum TransactionType {
    CREDIT,
    DEBIT
}
