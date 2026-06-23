package com.eventledger.account.api.dto;

import com.eventledger.account.domain.TransactionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

/**
 * Body of {@code POST /accounts/{id}/transactions}. {@code accountId} comes
 * from the path. Bean Validation rejects malformed input with a 400 before any
 * persistence happens; an unknown {@code type} value fails JSON binding and is
 * also mapped to a 400 ProblemDetail.
 */
public record TransactionRequest(
        @NotBlank String eventId,
        @NotNull TransactionType type,
        @NotNull @Positive BigDecimal amount,
        @NotBlank String currency) {
}
