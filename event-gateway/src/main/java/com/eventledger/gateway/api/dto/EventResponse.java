package com.eventledger.gateway.api.dto;

import com.eventledger.gateway.domain.EventType;
import com.eventledger.gateway.domain.StoredEvent;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Representation of a stored event. Returned for reads and for both submit
 * outcomes — the HTTP status (201 new / 200 duplicate) signals which.
 */
public record EventResponse(
        String eventId,
        String accountId,
        EventType type,
        BigDecimal amount,
        String currency,
        Instant eventTimestamp,
        Instant receivedAt) {

    public static EventResponse from(StoredEvent event) {
        return new EventResponse(
                event.getEventId(),
                event.getAccountId(),
                event.getType(),
                event.getAmount(),
                event.getCurrency(),
                event.getEventTimestamp(),
                event.getReceivedAt());
    }
}
