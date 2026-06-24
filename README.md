# Event Ledger

Two **independent** Spring Boot microservices that ingest financial transaction
events and maintain account balances, built to behave correctly under adversarial
conditions: **duplicate delivery, out-of-order arrival, and partial failure.**

- **event-gateway** (public, `:8080`) — the entry point. Validates input, enforces
  idempotency at the API boundary, owns the local event log, calls Account Service
  to apply transactions, and proxies balance reads.
- **account-service** (internal, `:8081`) — owns account state. Applies
  transactions **idempotently**, computes balances, returns account detail. Never
  exposed to external clients; only the Gateway calls it.

> Design rationale lives in [`CLAUDE.md`](CLAUDE.md); this README and
> [`docs/`](docs) restate it so each decision reads as intentional.
> See [`docs/decisions.md`](docs/decisions.md) for ADRs and
> [`docs/api-contracts.md`](docs/api-contracts.md) for the full API.

## Architecture

```
                         ┌───────────────────────── trace (W3C traceparent) ─────────────────────────┐
                         │                                                                            │
   client ──HTTP──▶  event-gateway  ──RestClient (circuit breaker + retry + timeout)──▶  account-service
   (POST /events)     :8080                                                                :8081
                      │  owns the event log (H2)                                            │ owns account state (H2)
                      │  idempotent by eventId                                              │ idempotent by eventId
                      │                                                                     │ balance = Σcredit − Σdebit
                      └── GET /events, GET /events/{id}, GET /accounts/{id}/balance (proxy) ┘
                                            │                         │
                                  /actuator/prometheus        spans over OTLP
                                            ▼                         ▼
                                       Prometheus :9090          Jaeger :16686
```

**Request flow for `POST /events`:**

1. Gateway validates the payload (Jakarta Bean Validation).
2. If the `eventId` already exists locally → return the stored event with `200`,
   **no downstream call** (boundary idempotency).
3. Otherwise the Gateway **calls Account Service first** to apply the transaction.
4. On success → persist the event locally → `201`. On failure / open circuit →
   `503`, and **nothing is persisted** (the client retries later).

Reads (`GET /events`, `GET /events/{id}`) are served entirely from the Gateway's
local store, so they keep working even when Account Service is down.

### Stack

Java 21 · Spring Boot 3.5 · Spring MVC + `RestClient` · Spring Data JPA + H2
(one in-memory DB per service, never shared) · Resilience4j · Micrometer +
Prometheus · Micrometer Tracing + OpenTelemetry → Jaeger (OTLP) · JUnit 5 +
MockMvc + WireMock.

## Prerequisites

- **Java 21** (the build targets 21; a 17 JVM will fail).
- **Maven 3.9+**.
- **Docker** + Docker Compose (for the full stack).

## Quick start — Docker Compose

Brings up both services plus Jaeger and Prometheus:

```bash
docker compose up --build
```

| Service | URL |
|---|---|
| Event Gateway | http://localhost:8080 |
| Account Service | http://localhost:8081 |
| Jaeger UI | http://localhost:16686 |
| Prometheus | http://localhost:9090 |

The Gateway waits for Account Service to report **healthy** before it starts
(`depends_on … service_healthy`). Both run as a non-root user in slim-JRE images.

Then exercise it end to end:

```bash
./scripts/smoke-test.sh
```

The smoke test submits a `CREDIT` to `POST /events` and reads it back through the
Gateway's balance proxy, asserting the balance reflects the credit.

Tear down with `docker compose down`.

## Running locally (without Docker)

Dependencies resolve from **Maven Central** via the project-local
[`settings.xml`](settings.xml); pass it with `-s` so the build is independent of
any host-global Maven mirror.

Start Account Service first (the Gateway calls it), in two terminals:

```bash
mvn -s settings.xml -pl account-service spring-boot:run   # :8081
mvn -s settings.xml -pl event-gateway   spring-boot:run   # :8080
```

By default the Gateway targets `http://localhost:8081`; override with
`ACCOUNT_SERVICE_URL` if needed.

Health checks:

```bash
curl http://localhost:8081/health   # account-service
curl http://localhost:8080/health   # event-gateway
```

## Running the tests

```bash
mvn -s settings.xml test
```

Covers the headline behaviours: dual-layer idempotency, order-independent
balance / out-of-order listing, RFC 7807 validation errors, and the full
resiliency story — retry with backoff, circuit-breaker open → fast `503`, `4xx`
never retried — with Account stubbed by WireMock. The `integration-tests` module
boots the **real** Account Service on a random port and drives
`POST /events → balance` end to end across both services.

## Observability

- **Logs** — structured JSON, one object per line, with `service`, `traceId`,
  and `spanId`.
- **Metrics** — `/actuator/prometheus` on both services. Gateway custom metrics:
  `events_submitted_total{type,outcome}` and a timer around the Account call
  (`gateway_account_apply_seconds`). Resilience4j circuit-breaker metrics are
  exported automatically.
- **Tracing** — one client request produces a single trace spanning both
  services (W3C `traceparent` propagated by the instrumented `RestClient`),
  viewable in Jaeger.
- **Health** — `GET /health` on each service actively probes H2 connectivity
  (not a blind `200`).

## Resiliency — pattern and rationale

**Primary pattern: a Circuit Breaker, composed with Timeout + Retry (exponential
backoff with jitter)** on every Gateway → Account call. This is what powers
graceful degradation.

- **Timeout** — connect/read timeouts on the HTTP client request factory
  (~1s / ~2s). Bounds latency so a slow Account Service can't tie up Gateway
  request threads. (For a blocking `RestClient` the timeout belongs on the client
  itself, not Resilience4j's `TimeLimiter`, which targets async calls.)
- **Retry** — transient failures only (IO errors, read timeouts, 5xx): 3
  attempts with exponential backoff **and jitter** to avoid retry storms. **`4xx`
  is never retried** (a client error won't fix itself), and an **open breaker
  (`CallNotPermittedException`) is never retried** — it should fast-fail.
- **Circuit Breaker** — trips on a sustained failure rate; while open it
  fast-fails immediately with `503` instead of hammering a down dependency; the
  half-open state probes for recovery.
- **Bulkhead** — considered, not made primary. There is a single downstream
  dependency and timeouts + fast-fail already protect the thread pool; trivial to
  add via Resilience4j if needed.

When the call ultimately fails or the breaker is open, the Gateway returns a
clear `503` ProblemDetail and does **not** persist the event — so the local event
log never diverges from balances.

## Documented assumptions

- **Single currency per account.** `currency` is stored and validated, never
  converted; a mismatching currency is rejected (`422`).
- **No overdraft protection.** A `DEBIT` may drive a balance negative — this is a
  ledger, not an authorization system.
- **`eventTimestamp` is authoritative** for ordering and is the business time;
  arrival time is never used for balance or ordering.

See [`docs/decisions.md`](docs/decisions.md) for the reasoning behind each.

## Repository layout

```
event-ledger/
├── event-gateway/      # public service (:8080)
├── account-service/    # internal service (:8081)
├── integration-tests/  # cross-service end-to-end tests
├── docs/               # decisions.md, api-contracts.md
├── scripts/            # smoke-test.sh
├── docker-compose.yml  # both services + Jaeger + Prometheus
├── prometheus.yml      # scrape config
└── settings.xml        # Maven Central mirror for the build
```
