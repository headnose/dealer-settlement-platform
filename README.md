# Dealer Settlement Platform

A personal learning project: Java/Spring Boot microservices that model a dealer loan settlement
and wire-notification workflow. Built to practice microservice design, concurrency, MongoDB/MySQL,
Kafka, Redis, and resilience patterns.

**Status:** 🚧 In progress

## Architecture

| Service | Port | Database | Role |
|---|---|---|---|
| loan-service | 8081 | — (in-memory sample data) | Mock upstream system that returns closed loans |
| settlement-service | 8082 | MongoDB | Imports loans concurrently and calculates settlements |
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
Docker Compose · JUnit 6 · Mockito

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

### Calculate a settlement

`PATCH /api/v1/loans/{loanId}/settlements` calculates (or recalculates) the settlement for an
imported loan. The gross amount always comes from the imported principal; the request only
supplies payment details and adjustments. Each call **replaces** all adjustments, so repeating
a request gives the same result.

```bash
curl -X PATCH http://localhost:8082/api/v1/loans/LN-1001/settlements \
  -H "Content-Type: application/json" \
  -d '{"payment": {"paymentMethod": "WIRE", "currency": "USD"},
       "adjustments": [{"adjustmentType": "DEALER_HOLDBACK", "amount": 500.00},
                       {"adjustmentType": "DOC_FEE", "amount": 150.00}]}'
```

```json
{"loanId": "LN-1001", "status": "CALCULATED", "grossAmount": 28500.00, "totalAdjustment": 650.00, "netSettlement": 27850.00}
```

Business rules:

- net = gross − sum(adjustments); net may be 0 but never negative
- adjustment amounts must be > 0 with at most 2 decimal places
- payment currency must match the loan currency (no FX conversion)
- at most 20 adjustments; the same type may appear more than once
- beneficiary bank is copied from the dealer's bank; value date is the next business day
  (weekends skipped) in the America/New_York business time zone

Errors use RFC 9457 Problem Details (`application/problem+json`). Example 400:

```json
{
  "title": "Validation failed",
  "status": 400,
  "detail": "Invalid request content.",
  "instance": "/api/v1/loans/LN-1001/settlements",
  "errors": [
    {"field": "payment.currency", "message": "must be a 3-letter ISO 4217 code, e.g. USD"},
    {"field": "adjustments[0].amount", "message": "must be greater than 0"}
  ]
}
```

| Status | When |
|---|---|
| 200 | Settlement calculated |
| 400 | Malformed JSON or invalid fields (`errors` lists each field and message) |
| 404 | Loan has not been imported |
| 409 | Concurrent update detected (optimistic locking); reload and retry |
| 422 | Business rule violated (currency mismatch, negative net) |
| 500 | Unexpected error (details are logged, not returned) |

## What works so far

- [x] Multi-module Maven project and Docker Compose infrastructure
- [x] loan-service: `GET /api/v1/loans/{loanId}` with `@WebMvcTest` tests
- [x] settlement-service: MongoDB document model (one document per loan, Decimal128 money,
      optimistic locking with `@Version`, audit timestamps) with `@DataMongoTest` tests
- [x] settlement-service: concurrent loan import (`CompletableFuture` on a dedicated thread pool)
      with per-loan outcomes and idempotent re-import
- [x] settlement-service: settlement calculation (`PATCH`) with BigDecimal math, Bean Validation,
      business-rule checks, injected `Clock` for value dates, and RFC 9457 error responses

## Roadmap

- [ ] wire-service: wire templates, submissions, recipients (MySQL + Flyway)
- [ ] Kafka: email-delivery and delivery-status topics with dead-letter topics
- [ ] notification-service: email via Mailpit, Redis idempotency keys
- [ ] Redis cache-aside for loan data; Resilience4j circuit breaker
- [ ] Load test wire generation: sequential vs parallel calls
- [ ] Observability: Prometheus, Grafana, OpenTelemetry

## Known limitations

- No HTTP timeout on the loan-service call; a hung upstream can tie up import threads
- `LocalDate` fields (`closingDate`, `valueDate`) are stored as local midnight in MongoDB and
  can shift a day across timezones
- FAILED import messages expose internal details (exception class, internal URL)
- Two simultaneous imports of the same loan report FAILED instead of ALREADY_IMPORTED
  (data stays safe because `_id` is unique)
- Value dates skip weekends only; there is no holiday calendar (e.g. a value date can fall on
  New Year's Day)
- The business time zone (America/New_York) is hard-coded in `ClockConfig`, not configurable
- The API rejects amounts with more than 2 decimal places as written (`10.500` → 400), although
  the calculator itself would accept it
- Integration tests use the same local `settlement_db` as manual testing and can leave documents
  behind; clear the collection before manual testing
