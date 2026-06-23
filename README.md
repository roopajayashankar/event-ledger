# Event Ledger

Two independent Spring Boot microservices that ingest financial transaction
events and maintain account balances:

- **event-gateway** (public, `:8080`) — entry point; validates input, enforces
  idempotency, stores the event log, calls Account to apply transactions.
- **account-service** (internal, `:8081`) — owns account state; applies
  transactions idempotently and computes balances.

See [CLAUDE.md](CLAUDE.md) for the full design — consistency model, resiliency
strategy, API contracts, and documented assumptions.

## Requirements

- **Java 21**
- **Maven 3.9+**

## Build & test

Dependencies resolve from **Maven Central** using the project-local
[`settings.xml`](settings.xml). Always pass it with `-s` so the build is
independent of any host-global Maven mirror (e.g. a corporate Artifactory in
`~/.m2/settings.xml`):

```bash
mvn -s settings.xml clean test
```

## Run

Both services together via Docker Compose:

```bash
docker compose up --build
```

Or run a single module locally (start account-service first):

```bash
mvn -s settings.xml -pl account-service spring-boot:run   # :8081
mvn -s settings.xml -pl event-gateway   spring-boot:run   # :8080
```

Health checks:

```bash
curl http://localhost:8081/health   # account-service
curl http://localhost:8080/health   # event-gateway
```
