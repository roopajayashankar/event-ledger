---
name: observability-conventions
description: Use this skill whenever implementing or touching logging, metrics, tracing, or health checks in the Event Ledger services. Apply it any time the work involves structured logs, trace IDs, OpenTelemetry/OTLP, Jaeger, Micrometer, Prometheus, the /actuator endpoints, or the /health endpoint — even if observability isn't the main task, so the two services stay consistent across sessions.
---

# Observability Conventions

Both services (event-gateway, account-service) must be observable in the same way so a
single request is traceable end to end. Keep these consistent across every session.

## Structured logging

- Emit **JSON, one object per line**. Prefer Spring Boot 3.4+ built-in structured
  logging: `logging.structured.format.console=logstash`. (If on <3.4, use
  `logstash-logback-encoder` with a `LogstashEncoder`.)
- Every line includes: `timestamp`, `level`, `service` (set per app), `traceId`,
  `spanId`, `message`. `traceId`/`spanId` come from the MDC, populated automatically by
  Micrometer Tracing — do not set them by hand.
- Set the service name once (e.g. `spring.application.name`) and include it as a static
  field so logs are filterable by service.

## Tracing (Gateway → Account)

- Use **Micrometer Tracing** with `micrometer-tracing-bridge-otel` +
  `opentelemetry-exporter-otlp`. Export to Jaeger over OTLP
  (`management.otlp.tracing.endpoint`).
- Build the Gateway's `RestClient` from the **autoconfigured `RestClient.Builder`** so
  the client is instrumented and the **W3C `traceparent`** header propagates
  automatically. Do not hand-roll a plain client — that breaks propagation.
- Goal to verify: one inbound request produces **one trace** with spans in *both*
  services, visible in the Jaeger UI (`:16686`).

## Metrics

- Expose `/actuator/prometheus` (`management.endpoints.web.exposure.include` must list
  `prometheus` and `health`).
- Register at least these custom metrics on the Gateway:
  - `events_submitted_total` counter, tagged `type` (CREDIT/DEBIT) and `outcome`
    (created / duplicate / rejected / degraded).
  - A timer around the Account Service call (latency).
- Resilience4j circuit-breaker metrics are exported through Micrometer automatically —
  ensure the registry is wired so they appear.
- Metric naming: snake_case, base unit suffixes, low-cardinality tags only (never put
  raw IDs in tags).

## Health

- `GET /health` is a **custom controller** (separate from `/actuator/health`) returning
  `{status, service, db, timestamp}`.
- It must **actively check H2 connectivity** (a trivial query or DataSource validation),
  not just return 200 unconditionally. `status` is `DOWN` if the DB check fails.

## Consistency checklist (apply to both services)

- [ ] JSON logs with traceId/spanId from MDC
- [ ] OTLP export to Jaeger configured
- [ ] `/actuator/prometheus` exposed
- [ ] custom metrics registered (Gateway)
- [ ] custom `/health` with real DB check
- [ ] `spring.application.name` set distinctly per service
