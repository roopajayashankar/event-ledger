package com.eventledger.gateway.api.dto;

import com.eventledger.gateway.domain.EventType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Body of {@code POST /events}. Bean Validation rejects malformed input with a
 * 400 ProblemDetail before any work happens. {@code eventTimestamp} must be a
 * present, ISO-8601 instant (a malformed value fails JSON binding and is also
 * mapped to 400); it is the authoritative business time used for ordering.
 */
public record EventRequest(
        @NotBlank String eventId,
        @NotBlank String accountId,
        @NotNull EventType type,
        @NotNull @Positive BigDecimal amount,
        @NotBlank String currency,
        @NotNull Instant eventTimestamp) {
}
