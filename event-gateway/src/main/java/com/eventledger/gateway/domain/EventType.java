package com.eventledger.gateway.domain;

/**
 * Type of a financial event. The Gateway keeps its own enum rather than sharing
 * one with Account Service — the two services are independent and must be able
 * to evolve their contracts separately (CLAUDE.md).
 */
public enum EventType {
    CREDIT,
    DEBIT
}
