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
| wire-service | 8083 | MySQL | Generates wire statements, sends them through Kafka, and tracks delivery status |
| notification-service | 8084 | Redis | (planned) Consumes Kafka events and sends emails via Mailpit |

```
Finance Ops ──► settlement-service ──► loan-service (mock upstream)
                  │       ▲
                  ▼       │ GET settlement detail
               MongoDB    │
Finance Ops ──► wire-service ──► Kafka: email-delivery ──► notification-service (planned) ──► Mailpit
                  │    ▲                                              │
                  ▼    └──────────── Kafka: delivery-status ◄─────────┘
                MySQL
```

## Tech stack

Java 17 · Spring Boot 4 · Maven (multi-module) · MongoDB · MySQL · Flyway · Kafka · Redis ·
Docker Compose · JUnit 6 · Mockito · Testcontainers · Awaitility

## Running locally

Prerequisites: Java 17, Maven, Docker Desktop.

```bash
docker compose up -d          # MySQL, MongoDB, Redis, Kafka, Mailpit
mvn clean install             # build all modules and run tests
```
Integration tests start their own throwaway containers with Testcontainers (MongoDB for
settlement-service; MySQL 8.4 and Kafka 3.9.1 for wire-service), so they need Docker running but
never touch the local `settlement_db`, `wire_db`, or Kafka topics.

Then run `LoanServiceApplication`, `SettlementServiceApplication` and `WireServiceApplication`
from your IDE. On first start, wire-service's Flyway migrations create the tables and seed data in
the local `wire_db`, and wire-service creates the Kafka topics it produces to.

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
{"settlementId": "SET-…", "grossAmount": 28500.00, "totalAdjustment": 650.00, "netSettlement": 27850.00, "status": "CALCULATED"}
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

### Get settlement detail

`GET /api/v1/loans/{loanId}/settlements` returns the calculated settlement for downstream
services such as wire-service: the imported loan (including the dealer's contact emails, which
wire-service uses to build notification recipients), the settlement amounts, the payment
(including the beneficiary bank and value date filled in by settlement-service), and the
adjustments.

```bash
curl http://localhost:8082/api/v1/loans/LN-1001/settlements
```

```json
{
  "loan": {
    "loanId": "LN-1001",
    "dealer": {"dealerId": "D-100", "dealerName": "Riverbend Motors", "dealerBankName": "Riverbend Community Bank",
               "dealerContactEmail": "accounts@riverbend-motors.example.com", "dealerBankContactEmail": "wires@riverbend-bank.example.com"},
    "status": "CLOSED", "currency": "USD", "principalAmount": 28500.00, "closingDate": "2026-10-15"
  },
  "settlement": {"settlementId": "SET-…", "grossAmount": 28500.00, "totalAdjustment": 650.00, "netSettlement": 27850.00, "status": "CALCULATED"},
  "payment": {"paymentMethod": "WIRE", "currency": "USD", "beneficiaryBank": "Riverbend Community Bank", "valueDate": "2026-11-23"},
  "adjustments": [
    {"adjustmentType": "DEALER_HOLDBACK", "amount": 500.00},
    {"adjustmentType": "DOC_FEE", "amount": 150.00}
  ]
}
```

| Status | When |
|---|---|
| 200 | Settlement is calculated |
| 404 `Loan not imported` | The loan has not been imported |
| 404 `Settlement not calculated` | The loan is imported but not calculated yet; retry after calculation |

Both 404 cases return the same status, so clients tell them apart by the Problem Details `title`.

The `loan` object is not in the blueprint's `SettlementDetailDto` tree, but the blueprint's
example settlement document (described as the data returned by `SettlementDetailDto`) includes
it. It is included so wire-service gets the dealer's name and emails in the same call.

### Generate a wire

`POST /api/v1/wires` generates a settlement wire statement for a calculated loan. wire-service
loads the latest template for the wire type (MySQL), gets the settlement detail from
settlement-service, merges the two, and saves a `DRAFT` submission with four recipients: the
dealer and the dealer's bank contact from the loan's dealer record, plus the two standing internal
distributions.

```bash
curl -X POST http://localhost:8083/api/v1/wires \
  -H "Content-Type: application/json" \
  -d '{"loanId": "LN-1001", "wireType": "SETTLEMENT"}'
```

Response (`201 Created`):

```json
{
  "submissionId": "SUB-…",
  "status": "DRAFT",
  "createdTime": "2026-11-21T17:33:10",
  "notificationRecipients": [
    {"recipientType": "DEALER", "name": "Riverbend Motors", "email": "accounts@riverbend-motors.example.com"},
    {"recipientType": "DEALER_BANK_CONTACT", "name": "Riverbend Community Bank", "email": "wires@riverbend-bank.example.com"},
    {"recipientType": "AUDIT_DISTRIBUTION", "name": "Compliance Audit Team", "email": "compliance-audit@example.com"},
    {"recipientType": "INTERNAL_OPS_DISTRIBUTION", "name": "Treasury Operations", "email": "treasury-ops@example.com"}
  ]
}
```

The merged wire statement is stored in `wire_submission.content`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<settlementWire>
    <loanId>LN-1001</loanId>
    <settlementId>SET-…</settlementId>
    <dealerName>Riverbend Motors</dealerName>
    <beneficiaryBank>Riverbend Community Bank</beneficiaryBank>
    <paymentMethod>WIRE</paymentMethod>
    <currency>USD</currency>
    <grossAmount>28500.00</grossAmount>
    <totalAdjustment>650.00</totalAdjustment>
    <netSettlement>27850.00</netSettlement>
    <valueDate>2026-11-23</valueDate>
</settlementWire>
```

| Status | When |
|---|---|
| 201 | Draft wire created |
| 400 | Malformed JSON, missing fields, or unknown `wireType` |
| 422 `Loan not imported` | settlement-service has not imported the loan |
| 422 `Settlement not calculated` | The loan is imported but its settlement is not calculated yet |
| 502 | settlement-service is down, timed out (connect 2s, read 5s), or returned 5xx |
| 500 | Unexpected error, e.g. missing reference data (details are logged, not returned) |

Example 422:

```json
{"title": "Settlement not calculated", "status": 422, "detail": "Settlement for loan LN-1002 has not been calculated yet", "instance": "/api/v1/wires", "loanId": "LN-1002"}
```

settlement-service answers 404 for these two cases; wire-service answers 422 because its own
request is valid but names a loan that cannot be wired yet. The titles are the same in both
services. The template and settlement-service calls run one after the other; a parallel version
is planned as a load-test comparison.

### Send a wire

`POST /api/v1/wires/{submissionId}/send` queues a `DRAFT` wire for email delivery. wire-service
publishes one `EmailDeliveryEvent` per recipient to the `email-delivery` topic and moves the
submission to `QUEUED`. The emails themselves are sent asynchronously, so the answer is
`202 Accepted`, not `200`.

```bash
curl -X POST http://localhost:8083/api/v1/wires/SUB-…/send
```

Response (`202 Accepted`):

```json
{"submissionId": "SUB-…", "status": "QUEUED"}
```

One of the four events on `email-delivery` (key = `submissionId`, no Java type headers):

```json
{
  "eventId": "EVT-…",
  "eventType": "EMAIL_DELIVERY_REQUESTED",
  "submissionId": "SUB-…",
  "loanId": "LN-1001",
  "recipientId": "REC-…",
  "recipientName": "Riverbend Motors",
  "email": "accounts@riverbend-motors.example.com",
  "wireType": "SETTLEMENT",
  "subject": "Settlement Wire Statement - LN-1001",
  "attachmentPath": null,
  "createdTime": "2026-11-21T20:18:41"
}
```

How sending stays safe:

- **Only a `DRAFT` can be sent.** The status change is one atomic
  `UPDATE … SET status = 'QUEUED' WHERE status = 'DRAFT'`, so if two users click Send at the same
  time, exactly one wins and the other gets 409.
- **Claim first, then publish.** `QUEUED` is committed before any event exists, so a delivery
  result can never arrive for a submission that is still a draft.
- **A failed publish is undone.** If Kafka does not confirm all four events (producer timeouts
  5s / 10s), the submission goes back to `DRAFT` and the API answers 503. Retrying is safe even
  after a partial publish, because the email consumer de-duplicates on `recipientId`.
- **Only one wire per loan can be sent.** Generating twice still creates two drafts, but once one
  of them is `QUEUED` or `SENT`, sending another draft for the same loan and wire type returns 409.

| Status | When |
|---|---|
| 202 | Events published, submission is `QUEUED` |
| 404 `Submission not found` | No submission with this ID |
| 409 `Submission not sendable` | The submission is already `QUEUED` or `SENT`, or another request is sending it |
| 409 `Wire already sent` | Another submission for the same loan and wire type was already queued or sent |
| 503 `Email delivery unavailable` | Kafka did not confirm the events; the submission is still a `DRAFT`, so retry later |
| 500 | Unexpected error (details are logged, not returned) |

Example 409:

```json
{"title": "Wire already sent", "status": 409, "detail": "A SETTLEMENT wire for loan LN-1001 has already been sent", "instance": "/api/v1/wires/SUB-…/send"}
```

### Delivery status

wire-service listens on `delivery-status` (consumer group `pwt-delivery-status`) for the result
of each email. For each `DeliveryStatusEvent` it updates that recipient's `delivery_status` and
sets `last_updated` to the event's `processedTime`. When all four recipients are `SENT`, the
submission moves from `QUEUED` to `SENT`. The recipient update and the submission update happen
in one transaction.

```json
{
  "eventId": "EVT-…",
  "eventType": "EMAIL_DELIVERY_COMPLETED",
  "submissionId": "SUB-…",
  "loanId": "LN-1001",
  "recipientId": "REC-…",
  "deliveryStatus": "SENT",
  "errorMessage": null,
  "processedTime": "2026-11-21T20:30:00"
}
```

MySQL after four `SENT` results:

```
+-----------+--------+
| submission_id | status |
+-----------+--------+
| SUB-…     | SENT   |
+-----------+--------+
+-----------+---------------------------+-----------------+---------------------+
| recipient_id | recipient_type         | delivery_status | last_updated        |
+-----------+---------------------------+-----------------+---------------------+
| REC-…     | AUDIT_DISTRIBUTION        | SENT            | 2026-11-21 20:30:00 |
| REC-…     | DEALER                    | SENT            | 2026-11-21 20:30:00 |
| REC-…     | DEALER_BANK_CONTACT       | SENT            | 2026-11-21 20:30:00 |
| REC-…     | INTERNAL_OPS_DISTRIBUTION | SENT            | 2026-11-21 20:30:00 |
+-----------+---------------------------+-----------------+---------------------+
```

Why this is safe:

- Status events are keyed by `submissionId`, so all four results for one wire land on the same
  partition and are handled one after another. The "were all four SENT?" check cannot race.
- Applying the same event twice gives the same result, so Kafka's at-least-once redelivery is
  harmless. Offsets are committed only after an event is processed (or dead-lettered).
- A `FAILED` result marks that recipient `FAILED`; the submission stays `QUEUED` (the blueprint
  defines no failed submission status) and the error message is logged.

Errors:

| Problem | Retried? | Then |
|---|---|---|
| Invalid JSON or unknown `deliveryStatus` value | No | `delivery-status-dlq` |
| Unknown recipient, recipient not on that submission, or a `PENDING` result | No | `delivery-status-dlq` |
| Anything else (e.g. MySQL briefly unavailable) | 2 retries, 1 s apart | `delivery-status-dlq` |

The dead-letter topic keeps the original message text unchanged, with Spring's exception headers,
so it can be inspected and replayed.

### Kafka topics

| Topic | Producer | Consumer | Partitions | Retention |
|---|---|---|---|---|
| `email-delivery` | wire-service | notification-service (planned) | 6 | 7 days |
| `delivery-status` | notification-service (planned) | wire-service | — (see limitations) | — |
| `delivery-status-dlq` | wire-service | support / replay | 6 | 30 days |
| `email-delivery-dlq` | notification-service (planned) | support / replay | — | — |

Each service declares the topics it produces to. The producer uses `acks=all` with idempotence
enabled. Replication factor is a setting (`topics.replication-factor`): 1 on the single local
broker, 3 or more in a real cluster.

### Try the Kafka flow by hand

With all three services running and a calculated loan, generate a wire, then send it as shown
above. To watch the events:

```bash
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic email-delivery --from-beginning --property