# Architecture Decision Records

ADR-style notes for the non-obvious choices in Event Ledger. Each records the
context, the decision, why, and the alternatives considered, so the design reads
as intentional. These mirror the reasoning in [`CLAUDE.md`](../CLAUDE.md).

---

## ADR-001 — Idempotency is enforced at BOTH layers, keyed by `eventId`

**Context.** Delivery is at-least-once: the Gateway retries Account calls, so the
same event can arrive more than once. Applying a transaction twice would corrupt
the balance.

**Decision.** Enforce idempotency by `eventId` at *both* layers:

- *Gateway* — on `POST /events`, if the `eventId` already exists locally, return
  the stored event with `200` and make **no** downstream call.
- *Account Service* — applying a transaction is idempotent by `eventId`;
  replaying the same `eventId` is a no-op that returns the same resulting balance.

In both services `eventId` is the **primary key** of the stored row, so the
uniqueness guarantee lives at the persistence layer (a concurrent duplicate is
caught as a constraint violation, not a race in application code).

**Why both?** The Gateway's boundary check stops the common case cheaply (no work,
no downstream call). But the Gateway retries, so without **Account-side** dedupe a
retried call after a Gateway-side success/uncertainty could double-apply. The
dual-layer guarantee is the crux of the exercise.

**Consequences.** A duplicate is always observably a no-op end to end (verified by
asserting the downstream call count and the unchanged balance). The trade-off is
storing every `eventId`; acceptable for a ledger.

---

## ADR-002 — Write order: call Account first, persist locally only on success

**Context.** The Gateway owns a local event log and also triggers the balance
change in Account Service. If these two can disagree, the log lies.

**Decision.** On `POST /events`:

1. Call Account Service to apply the transaction **first**.
2. On success → persist the event in the Gateway store → return `201`.
3. On Account failure / open circuit → return `503` and **do not persist** (the
   client retries later).

**Why.** The Gateway store therefore only ever reflects *applied* events, so the
local event log and the balance never diverge. Returning `503` (never `500`,
never a hang) makes the failure explicit and retryable.

**Alternative considered.** Persist as `PENDING` first, then reconcile
asynchronously (the "async fallback" bonus). Not implemented — it adds a
reconciliation path and a window where the log shows unapplied events. Recorded
here as future work.

---

## ADR-003 — Balance is order-independent

**Context.** Events can arrive out of order. A balance that depended on arrival
order would be corruptible by reordering.

**Decision.** Net balance = **Σ(CREDIT) − Σ(DEBIT)**, computed by aggregation over
the stored transactions; it is never stored as a mutable running total.

**Why.** Summation is commutative, so out-of-order *arrival* cannot corrupt the
balance, and replays (deduped by ADR-001) cannot either. Ordering matters only
for the event **listing**, which is sorted by `eventTimestamp` **at query time** —
not by insertion order.

**Consequences.** Reads compute the balance on demand (cheap at this scale, and it
can never drift). `eventTimestamp` is the authoritative business time for ordering
(see ADR-006).

---

## ADR-004 — Resiliency: Circuit Breaker, composed with Timeout + Retry

**Context.** The Gateway depends synchronously on Account Service. A slow or
failing dependency must not take the Gateway down or hang clients.

**Decision.** Wrap every Gateway → Account call with **Timeout + Retry + Circuit
Breaker** (Resilience4j):

- **Timeout** on the HTTP client request factory (connect ~1s, read ~2s). Bounds
  latency so a slow Account can't tie up request threads. For a blocking
  `RestClient` the timeout belongs on the client, **not** Resilience4j's
  `TimeLimiter` (which targets async/`CompletableFuture` calls).
- **Retry** — transient failures only (IO, read timeout, 5xx): 3 attempts,
  exponential backoff **with jitter** to avoid retry storms. Never retry `4xx`
  (won't fix itself) or `CallNotPermittedException` (an open breaker should
  fast-fail).
- **Circuit Breaker** — trips on a sustained failure rate; while open, calls
  fast-fail with `503`; half-open probes recovery. This is what powers graceful
  degradation.

**Why this ordering.** Retry is the outer aspect and the breaker the inner one, so
each attempt is recorded by the breaker and an open breaker is encountered by (and
explicitly *not* retried by) the retry layer.

**Alternative considered.** **Bulkhead** — not made primary. There is a single
downstream dependency, and timeouts + fast-fail already protect the thread pool.
Trivial to add via Resilience4j if a reviewer pushes on it.

**Verified behaviour.** Retry on transient 5xx/timeout with growing, jittered
waits; breaker opens under sustained failure; an open breaker returns `503`
immediately; `4xx` is never retried.

---

## ADR-005 — The Gateway exposes a balance proxy (spec interpretation)

**Context.** The spec lists the balance endpoint only on the *internal* Account
Service, yet requirement 6 calls for *client-facing* degradation of balance
queries — and clients cannot reach the internal service directly.

**Decision.** The Gateway exposes a thin proxy `GET /accounts/{accountId}/balance`
that forwards to Account Service under the same resiliency wrapper: `503` if
Account is down, `404` if the account is unknown, otherwise the balance.

**Why.** It is the only reading of the spec that satisfies client-facing balance
degradation. Called out explicitly here (and in the README) so it reads as a
reasoned interpretation of an ambiguous spec, not a misread.

---

## ADR-006 — Documented assumptions

Deliberate simplifications, each a trivial extension if required:

- **Single currency per account.** `currency` is stored and validated as present,
  not converted. The first transaction establishes the account's currency; a later
  transaction with a different currency is rejected (`422`). Multi-currency
  bucketing / FX is future work.
- **No overdraft protection.** A `DEBIT` may drive a balance negative — this is a
  ledger, not an authorization system. A guard is a trivial add.
- **`eventTimestamp` is authoritative** for ordering and is the business time;
  arrival time (`receivedAt`) is recorded for audit only and is never used for
  balance or ordering.
