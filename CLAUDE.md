# Event Ledger — Project Context (CLAUDE.md)

> This file is the single source of truth for every session working on this repo.
> Read it fully before writing code. Decisions here are deliberate; do not silently
> change them. If something is genuinely wrong, flag it rather than reworking quietly.

## Stack (pinned decisions)

- **Java 21**, **Spring Boot 3.x** — use the current GA from start.spring.io;
  verify the version at scaffold time, do not hard-code a remembered one.
- **Maven multi-module monorepo** (parent pom + two service modules).
- **Spring MVC (blocking)** + **RestClient** for service-to-service calls.
  (Reactive/WebFlux is overkill here and harder to defend in 4 hours.)
- **H2 in-memory**, one per service, **never shared**. Spring Data JPA.
- **Resilience4j** (`resilience4j-spring-boot3`) for circuit breaker + retry.
- **Micrometer** + `micrometer-registry-prometheus` → `/actuator/prometheus`.
- **Micrometer Tracing** + `micrometer-tracing-bridge-otel` +
  `opentelemetry-exporter-otlp` → **Jaeger** (OTLP).
- **Structured JSON logging**: Spring Boot 3.4+ built-in
  (`logging.structured.format.console=logstash`), which includes MDC `traceId`/`spanId`.
  Fallback if on <3.4: `logstash-logback-encoder`.
- **Tests**: JUnit 5, MockMvc, WireMock (stub Account + inject failures).
- **Docker**: multi-stage Dockerfile per service; `docker-compose.yml` runs both
  services + Jaeger + Prometheus.

## Repo layout

```
event-ledger/
├── pom.xml                      # parent / dependency management
├── docker-compose.yml
├── prometheus.yml
├── README.md
├── docs/
│   ├── architecture.md
│   ├── decisions.md             # ADR-style; mirror the "decisions" sections below
│   └── api-contracts.md
├── scripts/
│   └── smoke-test.sh            # curl-based end-to-end sanity check
├── event-gateway/               # public-facing
│   ├── pom.xml
│   ├── Dockerfile
│   └── src/{main,test}/...
└── account-service/             # internal
    ├── pom.xml
    ├── Dockerfile
    └── src/{main,test}/...
```

Package convention: `com.eventledger.gateway` / `com.eventledger.account`.
Layers per service: `api` (controllers + DTOs), `domain`, `service`, `repository`, `config`.

## Service responsibilities

- **Event Gateway (public, :8080)** — entry point. Validates input, enforces
  idempotency at the API boundary, stores event records locally, calls Account
  Service to apply transactions, proxies balance reads. Owns the event log.
- **Account Service (internal, :8081)** — owns account state. Applies transactions
  **idempotently**, computes balances, returns account detail. Never exposed to
  external clients; only the Gateway calls it.

## THE consistency model  ← most important section, expect to be quizzed

1. **Idempotency is enforced at BOTH layers, keyed by `eventId`.**
   - *Gateway*: on `POST /events`, if `eventId` already exists, return the stored
     event with `200` and make **no** downstream call.
   - *Account Service*: applying a transaction is idempotent by `eventId` — replaying
     the same `eventId` is a no-op that returns the same resulting balance.
   - **Why both?** The Gateway retries (at-least-once semantics). Without Account-side
     dedupe, a retry would apply the same transaction twice and corrupt the balance.
     This dual-layer guarantee is important — make it explicit in code
     and tests.

2. **Write order: call Account first, persist the event locally only on success.**
   - On success → persist event in Gateway store → return `201`.
   - On Account failure / open circuit → return `503`, **do not** persist (client
     retries later). The Gateway store therefore only ever reflects *applied* events,
     so the local event log and the balance never disagree.
   - *Alternative considered:* persist as `PENDING` then reconcile asynchronously
     (this is the "async fallback" bonus). Not implemented; recorded as future work in
     `decisions.md`.

3. **Balance is order-independent.** Net balance = Σ(CREDIT) − Σ(DEBIT) is a
   commutative aggregation, so out-of-order *arrival* cannot corrupt it. Ordering
   only matters for the event **listing**, which we sort by `eventTimestamp` at query
   time — not by insertion order. 

## Resiliency decision  ← be ready to defend the choice

**Primary pattern: Circuit Breaker, composed with Timeout + Retry (exponential
backoff + jitter)** on every Gateway → Account call.

- **Timeout** — set on the HTTP client request factory (e.g. connect ~1s, read ~2s).
  Bounds latency so a slow Account Service can't tie up Gateway request threads.
  *Note:* Resilience4j `TimeLimiter` targets async/`CompletableFuture` calls; for a
  blocking `RestClient` the correct place for the timeout is the client itself. Don't
  claim TimeLimiter on a blocking call.
- **Retry** — transient failures only (IO errors, read timeouts, 5xx). 3 attempts,
  exponential backoff with jitter to avoid retry storms / thundering herd. Do **not**
  retry `4xx` (client error, won't fix itself) or `CallNotPermittedException` (open
  breaker should fast-fail, not be retried).
- **Circuit Breaker** — trips on a sustained failure rate; while open, calls fast-fail
  immediately with `503` instead of hammering a down dependency; half-open state probes
  recovery. This is what powers graceful degradation.
- **Bulkhead** — considered, not made primary. There's a single downstream dependency,
  and timeouts + fast-fail already protect the thread pool.

Effective behavior to verify in tests: retry on transient 5xx/timeout with growing,
jittered waits; breaker opens under sustained failure; an open breaker returns `503`
immediately; `4xx` is never retried.

## Graceful degradation (requirement 6)

- `POST /events` while Account is down → `503` + clear body. Never `500`, never hang.
- `GET /events/{id}` and `GET /events?account=...` → served from the Gateway's local
  store; **work regardless** of Account health.
- Balance query → `503` with a clear "account service unreachable" message.

**Interpretation note (document this):** the spec lists the balance endpoint only on
the internal Account Service, but requirement 6 specifies *client-facing* degradation
for balance queries, and clients cannot reach the internal service directly. So the
Gateway exposes a thin proxy `GET /accounts/{accountId}/balance` that forwards to
Account under the same resiliency wrapper. This is a reasoned reading of an ambiguous
spec — call it out in the README so it reads as intentional, not as a misread.

## API contracts

**Gateway (public)**
| Method | Path | Notes |
|---|---|---|
| POST | `/events` | submit event; `201` new, `200` duplicate, `400` invalid, `503` account down |
| GET | `/events/{id}` | local read; `404` if unknown |
| GET | `/events?account={id}` | local read, sorted by `eventTimestamp` asc |
| GET | `/accounts/{id}/balance` | proxy to Account; `503` if down |
| GET | `/health` | JSON status incl. DB connectivity |

**Account Service (internal)**
| Method | Path | Notes |
|---|---|---|
| POST | `/accounts/{id}/transactions` | idempotent by `eventId`; applies CREDIT/DEBIT |
| GET | `/accounts/{id}/balance` | current net balance |
| GET | `/accounts/{id}` | details + recent transactions |
| GET | `/health` | JSON status incl. DB connectivity |

- Errors use **RFC 7807 `ProblemDetail`** (native in Spring Boot 3) — consistent,
  machine-readable error bodies.
- Validation (Jakarta Bean Validation on the request DTO): `eventId`, `accountId`,
  `currency` non-blank; `type` ∈ {CREDIT, DEBIT}; `amount` > 0; `eventTimestamp`
  present and ISO-8601. Invalid → `400` with field-level detail.

## Observability

- **`/health`** — custom controller returning `{status, service, db, timestamp}`;
  actively checks H2 connectivity (don't just return 200 blindly).
- **`/actuator/prometheus`** exposed. Custom metrics:
  - `events_submitted_total{type, outcome}` counter (outcome = created|duplicate|rejected|degraded)
  - a timer around the Account call (latency histogram)
  - Resilience4j circuit-breaker metrics are auto-exposed via Micrometer.
- **Logs** — JSON, one object per line, fields: `timestamp`, `level`, `service`,
  `traceId`, `spanId`, `message`.
- **Tracing** — W3C `traceparent` is auto-propagated Gateway → Account via the
  instrumented `RestClient` (build it from the autoconfigured `RestClient.Builder`).
  Both services export spans over OTLP to Jaeger. One client request = one trace
  spanning both services, viewable in the Jaeger UI.

## Documented assumptions

- **Single currency per account.** `currency` is stored and validated as present, not
  converted. Multi-currency bucketing/FX = future work.
- **No overdraft protection.** A DEBIT may drive a balance negative — this is a ledger,
  not an authorization system. A guard is a trivial add if required.
- **`eventTimestamp` is authoritative** for ordering and is the business time; arrival
  time is not used for balance or ordering.

## Conventions

- **Conventional Commits** (`feat:`, `test:`, `chore:`, `docs:`, `feat(scope):` …).
  One logical change per commit. **Never** squash. The history is graded.
- Every behavior ships with a test in the same commit (or the immediately following
  `test:` commit).
- Keep controllers thin; business logic in `service`; persistence in `repository`.

## Build / run quick reference

- Tests: `mvn test` (from repo root runs both modules).
- Run all: `docker compose up --build`.
- Ports: Gateway `:8080`, Account `:8081`, Jaeger UI `:16686`, Prometheus `:9090`.
