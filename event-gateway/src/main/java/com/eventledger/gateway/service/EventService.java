package com.eventledger.gateway.service;

import com.eventledger.gateway.api.dto.EventRequest;
import com.eventledger.gateway.api.dto.EventResponse;
import com.eventledger.gateway.domain.StoredEvent;
import com.eventledger.gateway.repository.EventRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the Gateway's event log: boundary idempotency by {@code eventId}, the
 * (currently stubbed) apply step, and local persistence/reads.
 */
@Service
public class EventService {

    private static final Logger log = LoggerFactory.getLogger(EventService.class);

    private final EventRepository events;
    private final AccountClient accountClient;

    public EventService(EventRepository events, AccountClient accountClient) {
        this.events = events;
        this.accountClient = accountClient;
    }

    /**
     * Submits an event. Idempotent at the API boundary: a known {@code eventId}
     * returns the stored event and does <em>no</em> work — no apply, no write.
     * Otherwise the transaction is applied first and the event persisted only on
     * success, so the log never holds an event that was not applied (CLAUDE.md).
     */
    @Transactional
    public SubmitResult submit(EventRequest request) {
        Optional<StoredEvent> existing = events.findById(request.eventId());
        if (existing.isPresent()) {
            log.debug("Duplicate eventId={} at gateway boundary — returning stored event, no work",
                    request.eventId());
            return new SubmitResult(existing.get(), false);
        }

        // Write order: apply first (stubbed for now), persist only on success.
        accountClient.apply(new ApplyTransactionCommand(
                request.accountId(), request.eventId(), request.type(),
                request.amount(), request.currency()));

        StoredEvent toStore = new StoredEvent(
                request.eventId(), request.accountId(), request.type(),
                request.amount(), request.currency(), request.eventTimestamp(), Instant.now());
        try {
            StoredEvent stored = events.save(toStore);
            log.info("Stored event eventId={} account={} {} {} {}",
                    stored.getEventId(), stored.getAccountId(), stored.getType(),
                    stored.getAmount(), stored.getCurrency());
            return new SubmitResult(stored, true);
        } catch (DataIntegrityViolationException race) {
            // A concurrent submission of the same eventId won the race. Idempotent
            // outcome: treat ours as a duplicate and return the stored event.
            StoredEvent stored = events.findById(request.eventId()).orElseThrow(() -> race);
            log.debug("Concurrent duplicate eventId={} — returning stored event", request.eventId());
            return new SubmitResult(stored, false);
        }
    }

    @Transactional(readOnly = true)
    public EventResponse getEvent(String eventId) {
        return events.findById(eventId)
                .map(EventResponse::from)
                .orElseThrow(() -> new EventNotFoundException(eventId));
    }

    @Transactional(readOnly = true)
    public List<EventResponse> getEventsByAccount(String accountId) {
        return events.findByAccountIdOrderByEventTimestampAsc(accountId).stream()
                .map(EventResponse::from)
                .toList();
    }

    /** Outcome of {@link #submit}: the stored event and whether this call
     * created it ({@code true}) or hit an idempotent duplicate ({@code false}). */
    public record SubmitResult(StoredEvent event, boolean created) {
    }
}
