package com.eventledger.gateway.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * Custom Gateway metrics (CLAUDE.md observability):
 *
 * <ul>
 *   <li>{@code events_submitted_total{type, outcome}} — a counter of submissions
 *       tagged by event type (CREDIT/DEBIT) and outcome (created / duplicate /
 *       rejected / degraded).</li>
 *   <li>a timer around the Account apply call ({@code gateway_account_apply})
 *       capturing downstream latency, including retries.</li>
 * </ul>
 *
 * <p>Resilience4j circuit-breaker metrics are exported automatically via
 * Micrometer; nothing extra is needed for those.
 */
@Component
public class EventMetrics {

    public static final String OUTCOME_CREATED = "created";
    public static final String OUTCOME_DUPLICATE = "duplicate";
    public static final String OUTCOME_REJECTED = "rejected";
    public static final String OUTCOME_DEGRADED = "degraded";

    private final MeterRegistry registry;
    private final Timer accountApplyTimer;

    public EventMetrics(MeterRegistry registry) {
        this.registry = registry;
        // Micrometer appends the base-unit/_total suffixes for Prometheus, so
        // "events.submitted" is exposed as events_submitted_total and
        // "gateway.account.apply" as gateway_account_apply_seconds_*.
        this.accountApplyTimer = Timer.builder("gateway.account.apply")
                .description("Latency of the Gateway's apply call to Account Service")
                .publishPercentileHistogram()
                .register(registry);
    }

    /** Increments {@code events_submitted_total} for the given type and outcome. */
    public void recordOutcome(String type, String outcome) {
        registry.counter("events.submitted", "type", type, "outcome", outcome).increment();
    }

    /** Times the Account apply call (records duration even if it throws). */
    public void timeAccountApply(Runnable call) {
        accountApplyTimer.record(call);
    }
}
