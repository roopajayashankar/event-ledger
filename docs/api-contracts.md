# API Contracts

All errors use **RFC 7807 `ProblemDetail`** (`application/problem+json`) — native
in Spring Boot 3 — for consistent, machine-readable error bodies. Timestamps are
ISO-8601; money is a decimal number.

- [Event Gateway (public, `:8080`)](#event-gateway-public-8080)
- [Account Service (internal, `:8081`)](#account-service-internal-8081)
- [Error shape](#error-shape-rfc-7807)

---

## Event Gateway (public, `:8080`)

| Method | Path | Purpose | Status codes |
|---|---|---|---|
| POST | `/events` | Submit an event | `201` created · `200` duplicate · `400` invalid · `503` account down · `502` account rejected |
| GET | `/events/{id}` | Read one event (local) | `200` · `404` unknown |
| GET | `/events?account={id}` | List an account's events, sorted by `eventTimestamp` asc | `200` |
| GET | `/accounts/{id}/balance` | Balance proxy → Account Service | `200` · `404` unknown · `503` account down |
| GET | `/health` | Liveness + DB check | `200` · `503` DB down |

### POST `/events`

Idempotent by `eventId`. A new event is applied to Account Service **first**, then
persisted locally (`201`). A duplicate `eventId` returns the stored event with
`200` and does no work. If Account Service is unavailable the event is **not**
persisted and the response is `503`.

**Request**

```json
{
  "eventId": "evt-1",
  "accountId": "acc-1",
  "type": "CREDIT",
  "amount": 100.00,
  "currency": "USD",
  "eventTimestamp": "2026-06-01T10:00:00Z"
}
```

**Validation** (`400` on failure, with field-level detail): `eventId`,
`accountId`, `currency` non-blank; `type` ∈ `{CREDIT, DEBIT}`; `amount` > 0;
`eventTimestamp` present and ISO-8601.

**Response** `201 Created` (new) / `200 OK` (duplicate)

```json
{
  "eventId": "evt-1",
  "accountId": "acc-1",
  "type": "CREDIT",
  "amount": 100.00,
  "currency": "USD",
  "eventTimestamp": "2026-06-01T10:00:00Z",
  "receivedAt": "2026-06-01T10:00:00.123Z"
}
```

`receivedAt` is the Gateway's arrival time (audit only; never used for ordering).

### GET `/events/{id}`

Served from the local store, so it works regardless of Account Service health.
`200` with the event body above, or `404` if the `eventId` is unknown.

### GET `/events?account={id}`

Served locally. Returns a JSON **array** of events for the account, sorted by
`eventTimestamp` **ascending** (sorted at query time, not insertion order):

```json
[
  { "eventId": "evt-1", "accountId": "acc-1", "type": "CREDIT", "amount": 100.00, "currency": "USD", "eventTimestamp": "2026-06-01T10:00:00Z", "receivedAt": "..." },
  { "eventId": "evt-2", "accountId": "acc-1", "type": "DEBIT",  "amount":  30.00, "currency": "USD", "eventTimestamp": "2026-06-02T10:00:00Z", "receivedAt": "..." }
]
```

An unknown account yields an empty array (`200`, `[]`).

### GET `/accounts/{id}/balance`

Thin proxy to Account Service under the resiliency wrapper.

**Response** `200 OK`

```json
{ "accountId": "acc-1", "balance": 70.00, "currency": "USD" }
```

`404` if the account is unknown; `503` if Account Service is unreachable.

---

## Account Service (internal, `:8081`)

Never exposed to external clients — only the Gateway calls it.

| Method | Path | Purpose | Status codes |
|---|---|---|---|
| POST | `/accounts/{id}/transactions` | Apply a transaction (idempotent by `eventId`) | `201` applied · `200` duplicate · `400` invalid · `422` currency mismatch |
| GET | `/accounts/{id}/balance` | Current net balance | `200` · `404` unknown |
| GET | `/accounts/{id}` | Account detail + recent transactions | `200` · `404` unknown |
| GET | `/health` | Liveness + DB check | `200` · `503` DB down |

### POST `/accounts/{id}/transactions`

`accountId` comes from the path. Idempotent by `eventId`: a replay is a no-op that
returns the stored result and the current balance.

**Request**

```json
{ "eventId": "evt-1", "type": "CREDIT", "amount": 100.00, "currency": "USD" }
```

**Validation** (`400`): `eventId`, `currency` non-blank; `type` ∈ `{CREDIT,
DEBIT}`; `amount` > 0. A currency that differs from the account's established
currency is rejected with `422` (single-currency assumption).

**Response** `201 Created` (applied) / `200 OK` (duplicate)

```json
{
  "accountId": "acc-1",
  "eventId": "evt-1",
  "type": "CREDIT",
  "amount": 100.00,
  "currency": "USD",
  "balance": 100.00,
  "status": "applied"
}
```

`status` is `"applied"` for a freshly applied event or `"duplicate"` for a replay.
A `DEBIT` may drive `balance` negative (no overdraft protection).

### GET `/accounts/{id}/balance`

```json
{ "accountId": "acc-1", "balance": 70.00, "currency": "USD" }
```

`404` if the account has no transactions.

### GET `/accounts/{id}`

```json
{
  "accountId": "acc-1",
  "currency": "USD",
  "balance": 70.00,
  "transactionCount": 2,
  "recentTransactions": [
    { "eventId": "evt-2", "type": "DEBIT",  "amount": 30.00, "currency": "USD", "appliedAt": "2026-06-02T10:00:01Z" },
    { "eventId": "evt-1", "type": "CREDIT", "amount": 100.00, "currency": "USD", "appliedAt": "2026-06-01T10:00:01Z" }
  ]
}
```

`recentTransactions` is newest-first (up to the most recent 20). `404` if unknown.

---

## Health (both services)

`GET /health` actively probes H2 connectivity (not a blind `200`):

```json
{ "status": "UP", "service": "event-gateway", "db": "UP", "timestamp": "2026-06-01T10:00:00Z" }
```

Returns `503` with `status`/`db` = `DOWN` if the database check fails.

---

## Error shape (RFC 7807)

Every error is a `ProblemDetail` (`Content-Type: application/problem+json`).
Validation failures add a non-standard `errors` array with field-level detail:

```json
{
  "type": "about:blank",
  "title": "Invalid request",
  "status": 400,
  "detail": "One or more fields are invalid",
  "instance": "/events",
  "errors": [
    { "field": "amount", "message": "must be greater than 0" }
  ]
}
```

Representative titles: `Invalid request` (`400`), `Event not found` /
`Account not found` (`404`), `Currency mismatch` (`422`),
`Account service rejected the request` (`502`),
`Account service unavailable` (`503`).
