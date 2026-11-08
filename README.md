# Dealer Settlement Platform

A personal learning project: Java/Spring Boot microservices that model a dealer loan settlement
and wire-notification workflow. Built to practice microservice design, concurrency, MongoDB/MySQL,
Kafka, Redis, and resilience patterns.

**Status:** 🚧 In progress

## Architecture

| Service | Port | Database | Role |
|---|---|---|---|
| loan-service | 8081 | — (in-memory sample data) | Mock upstream system that returns closed loans |
| settlement-service | 8082 | MongoDB | Imports loans concurrently and (planned) calculates settlements |
| wire-service | 8083 | MySQL | (planned) Generates wire statements and tracks delivery |
| notification-service | 8084 | Redis | (planned) Consumes Kafka events and sends emails via Mailpit |

```
Finance Ops ──► settlement-service ──► loan-service (mock upstream)
                      │
                      ▼
                   MongoDB

(planned)  wire-service ──► Kafka ──► notification-service ──► Mailpit
              │                 ▲               │
              ▼                 └───────────────┘ delivery status
            MySQL
```

## Tech stack

Java 17 · Spring Boot 4 · Maven (multi-module) · MongoDB · MySQL · Flyway · Kafka · Redis ·
Docker Compose · JUnit 5 · Mockito

## Running locally

Prerequisites: Java 17, Maven, Docker Desktop.

```bash
docker compose up -d          # MySQL, MongoDB, Redis, Kafka, Mailpit
mvn clean install             # build all modules and run tests
```

Then run `LoanServiceApplication` and `SettlementServiceApplication` from your IDE.

### Try it

```bash
# Fetch a sample loan from the mock upstream
curl http://localhost:8081/api/v1/loans/LN-1001

# Import several loans concurrently into settlement-service
curl -X POST http://localhost:8082/api/v1/imports \
  -H "Content-Type: application/json" \
  -d '{"loanIds": ["LN-1001", "LN-1002", "LN-1003", "NOPE"]}'
```

Example response (one result per loan):

```json
[
  {"loanId": "LN-1001", "outcome": "IMPORTED",   "message": "Imported"},
  {"loanId": "LN-1002", "outcome": "IMPORTED",   "message": "Imported"},
  {"loanId": "LN-1003", "outcome": "NOT_CLOSED", "message": "Loan status is ACTIVE, expected CLOSED"},
  {"loanId": "NOPE",    "outcome": "NOT_FOUND",  "message": "Loan not found in loan-service"}
]
```

## What works so far

- [x] Multi-module Maven project and Docker Compose infrastructure
- [x] loan-service: `GET /api/v1/loans/{loanId}` with `@WebMvcTest` tests
- [x] settlement-service: MongoDB document model (one document per loan, Decimal128 money,
      optimistic locking with `@Version`, audit timestamps) with `@DataMongoTest` tests
- [x] settlement-service: concurrent loan import (`CompletableFuture` on a dedicated thread pool)
      with per-loan outcomes and idempotent re-import

## Roadmap

- [ ] Settlement calculation (BigDecimal, adjustments)
- [ ] wire-service: wire templates, submissions, recipients (MySQL + Flyway)
- [ ] Kafka: email-delivery and delivery-status topics with dead-letter topics
- [ ] notification-service: email via Mailpit, Redis idempotency keys
- [ ] Redis cache-aside for loan data; Resilience4j circuit breaker
- [ ] Load test wire generation: sequential vs parallel calls
- [ ] Observability: Prometheus, Grafana, OpenTelemetry

## Known limitations

- No HTTP timeout on the loan-service call; a hung upstream can tie up import threads
- `LocalDate` is stored as local midnight in MongoDB and can shift a day across timezones
- Validation errors return 400 without saying which field failed
- FAILED import messages expose internal details (exception class, internal URL)
- Two simultaneous imports of the same loan report FAILED instead of ALREADY_IMPORTED
  (data stays safe because `_id` is unique)
