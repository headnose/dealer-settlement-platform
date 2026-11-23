# Dealer Settlement Platform

A personal learning project: Java/Spring Boot microservices that model a dealer loan settlement
and wire-notification workflow. Built to practice microservice design, concurrency, MongoDB/MySQL,
Kafka, Redis, S3-compatible object storage, and resilience patterns.

**Status:** 🚧 In progress

## Architecture

| Service | Port | Database | Role |
|---|---|---|---|
| loan-service | 8081 | — (in-memory sample data) | Mock upstream system that returns closed loans |
| settlement-service | 8082 | MongoDB, Redis (cache) | Imports loans concurrently, calculates settlements, and caches the settlement detail |
| wire-service | 8083 | MySQL, S3 (SeaweedFS) | Generates wire statements, stores them in S3, sends them through Kafka, and tracks delivery status |
| notification-service | 8084 | Redis (its own instance), S3 (read) | Consumes email requests from Kafka, downloads the statement, emails it as an attachment over SMTP, and reports each result back |

```
Finance Ops ──► settlement-service ──► loan-service (mock upstream)
                  │       ▲
                  ▼       │ GET settlement detail
       MongoDB + Redis    │
Finance Ops ──► wire-service ──► Kafka: email-delivery ──► notification-service ──► SMTP (Mailpit)
                  │  │  ▲                                     │  │    │
                  │  │  └────────── Kafka: delivery-status ◄──┘  │    ▼
                  ▼  │ upload statement                download │  Redis
                MySQL└──────────────► S3 (SeaweedFS) ◄──────────┘
```

## Tech stack

Java 17 · Spring Boot 4 · Maven (multi-module) · MongoDB · MySQL · Flyway · Kafka · Redis ·
Spring Mail · AWS SDK for Java v2 (S3) · SeaweedFS · Resilience4j · Docker Compose · JUnit 6 · Mockito ·
Testcontainers · Awaitility · GreenMail

## Running locally

Prerequisites: Java 17, Maven, Docker Desktop.

```bash
docker compose up -d          # MySQL, MongoDB, Redis (x2), Kafka, Mailpit, SeaweedFS
mvn clean install             # build all modules and run tests
```

| Container | Port | Used by |
|---|---|---|
| `mysql` | 3306 | wire-service |
| `mongodb` | 27017 | settlement-service |
| `redis` | 6379 | settlement-service cache (settlement detail) |
| `blastmail-redis` | 6380 | notification-service idempotency keys |
| `kafka` | 9092 | wire-service, notification-service |
| `mailpit` | 1025 (SMTP), 8025 (web UI) | notification-service |
| `seaweedfs` | 8333 (S3 API), 23646 (admin UI) | wire-service (upload), notification-service (download) |

Integration tests start their own throwaway containers with Testcontainers (MongoDB and Redis 7 for
settlement-service; MySQL 8.4, Kafka 3.9.1 and SeaweedFS for wire-service; Kafka 3.9.1, Redis 7
and SeaweedFS for notification-service, plus GreenMail as an in-process SMTP server), so they need
Docker running but never touch the local databases, Kafka topics, bucket or Mailpit.

Then run the four applications from your IDE, or with `mvn -pl <module> spring-boot:run`:
`LoanServiceApplication`, `SettlementServiceApplication`, `NotificationServiceApplication`, then
`WireServiceApplication`. On first start, wire-service's Flyway migrations create the tables and
seed data in the local `wire_db`, and each service creates the Kafka topics it produces to.

On a fresh broker, start notification-service before wire-service: wire-service consumes
`delivery-status`, and if it starts first the broker auto-creates that topic with 1 partition
and no retention setting instead of notification-service's 6 partitions and 7 days.

Sent emails can be read at http://localhost:8025 (Mailpit). The compose SeaweedFS creates the
`wire-statements` bucket itself on startup; it has no volume, so `docker compose down` empties it
(`stop` / `start` keep the data).

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

The request itself answers `200` even when some loans could not be imported: each loan gets its
own outcome.

| Outcome | When |
|---|---|
| `IMPORTED` | Fetched from loan-service and saved |
| `ALREADY_IMPORTED` | A settlement document already exists for this loan (no call to loan-service) |
| `NOT_FOUND` | loan-service answered 404, and only then |
| `NOT_CLOSED` | The loan exists but is not `CLOSED`, so it cannot be settled |
| `LOAN_SERVICE_UNAVAILABLE` | loan-service timed out, refused the connection, answered 5xx, or the circuit breaker is open; try again later |
| `FAILED` | Unexpected error (e.g. a database error or an empty answer from loan-service); the message is always `Import failed unexpectedly` and the cause is logged, not returned |

### Loan-service calls: timeouts and circuit breaker

The import calls loan-service once per loan, on a pool of at most 10 threads. The blueprint's
debugging scenario describes what goes wrong without protection: the upstream ran out of
connections during a busy closing window, and the calling code treated the unresponsive service
as an empty result instead of a failure. Here:

- **Timeouts:** connect 2s, read 5s (`clients.loan-service.connect-timeout` / `read-timeout`),
  starting values to check in the load test. A hung loan-service now fails a call after 5s instead
  of holding an import thread forever. The read timeout limits the wait between bytes, not the
  whole response.
- **Circuit breaker** (Resilience4j, instance `loanService`, settings under
  `resilience4j.circuitbreaker.instances.loanService`): it looks at the last 10 calls and, once at
  least 5 were made, opens when 50% or more failed. While open, calls fail at once without any HTTP
  request. After 10s it lets 2 trial calls through (half-open); if they succeed it closes again,
  otherwise it opens again.
- **Only an unhealthy upstream counts as a failure:** timeouts, connection errors and 5xx. A 404 is
  a normal answer (`NOT_FOUND`), so a batch of unknown loan IDs can never open the breaker.
- **Unavailable is never reported as not found.** Only a 404 means `NOT_FOUND`; an unavailable
  loan-service gives `LOAN_SERVICE_UNAVAILABLE`, and an empty `200` body gives `FAILED`.
- **No retry and no cached fallback.** Retrying would add load to an upstream that is already
  struggling, and a loan that was never imported has no cached data to fall back on (the blueprint
  says the same).

A real run with loan-service stopped, importing six unknown loan IDs (so each one reaches the
client):

```bash
curl -s -X POST localhost:8082/api/v1/imports -H 'Content-Type: application/json' \
  -d '{"loanIds":["LN-9001","LN-9002","LN-9003","LN-9004","LN-9005","LN-9006"]}'
```

The same batch twice in a row returned six of these each time (no exception text):

```json
{"loanId":"LN-9001","outcome":"LOAN_SERVICE_UNAVAILABLE","message":"Loan service temporarily unavailable"}
```

The six refused connections of the first batch opened the breaker. loan-service was then started
(19:59:03); its own log shows the first request arriving at 19:59:53, so none of the earlier
calls reached it. The next batch, at 19:59:53, met the breaker in its half-open state: exactly two
trial calls went through and got a real 404, and the other four were rejected without an HTTP call:

```json
[{"loanId":"LN-9001","outcome":"LOAN_SERVICE_UNAVAILABLE","message":"Loan service temporarily unavailable"},
 {"loanId":"LN-9002","outcome":"LOAN_SERVICE_UNAVAILABLE","message":"Loan service temporarily unavailable"},
 {"loanId":"LN-9003","outcome":"NOT_FOUND","message":"Loan not found in loan-service"},
 {"loanId":"LN-9004","outcome":"NOT_FOUND","message":"Loan not found in loan-service"},
 {"loanId":"LN-9005","outcome":"LOAN_SERVICE_UNAVAILABLE","message":"Loan service temporarily unavailable"},
 {"loanId":"LN-9006","outcome":"LOAN_SERVICE_UNAVAILABLE","message":"Loan service temporarily unavailable"}]
```

The two successful trials closed the breaker. The same batch again returned `NOT_FOUND` for all
six, and `LN-1003` / `LN-2002` returned `NOT_CLOSED` (`Loan status is ACTIVE, expected CLOSED` /
`... CANCELLED ...`).

Tests: `LoanServiceClientTest` runs the client against a JDK `HttpServer` (200, 404, empty body,
500, and a slow answer that times out); `LoanServiceCircuitBreakerTest` gets the client from Spring
so the breaker proxy is active, and checks that five 500s open it and that the sixth call fails
without reaching the server, while ten 404s leave it closed; `LoanImportServiceTest` checks the
outcomes above.

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

### Settlement detail cache (Redis)

The blueprint caches "loan metadata" in Redis with cache-aside: read Redis first, on a miss read
MongoDB and fill the cache, invalidate on write. In this project the loan, settlement, payment and
adjustments live in **one** MongoDB document per loan, and each endpoint reads that document once
by `_id`. Caching only the loan part would save no reads at all (GET still needs the settlement,
PATCH still needs the version for optimistic locking). So the cache holds what GET returns:

- **What is cached:** the settlement detail of a `CALCULATED` loan, under `lsc:loan:{loanId}`, for
  30 minutes (`settlement-cache.ttl`, the low end of the blueprint's 30-60 minutes, because stale
  settlement data is a compliance risk). The two 404 cases are never cached.
- **Cache format is not the API DTO.** `PaymentDto` marks `beneficiaryBank` and `valueDate` as
  read-only so clients cannot set them, which also means JSON read back into it loses them. A
  cached DTO would have served wire statements without the bank or value date, and only on cache
  hits. The cache stores a snapshot of the stored model (`CachedSettlementDetail`) instead, and
  both hit and miss build the response through the same mapping.
- **Invalidation:** `PATCH` deletes the key after the save succeeded; a failed save (409) deletes
  nothing. Import deletes it too, defensively: a document removed directly in MongoDB and imported
  again must not bring back the old cached settlement. Writes never put values into Redis; only
  reads fill it, so two concurrent writes cannot leave the older value cached.
- **Redis down never fails a request:** every Redis error is logged at WARN and treated as a miss
  (read) or ignored (write, delete). Lettuce timeouts are lowered to 500ms (`spring.data.redis.timeout`
  / `connect-timeout`; the defaults would make a dead cache very slow). Starting values.
- **Eviction and persistence are infrastructure:** the compose Redis runs with `--maxmemory 64mb
  --maxmemory-policy allkeys-lru` (TTL for staleness, LRU for memory pressure) and `--save ""`
  (no snapshots: the cache is rebuilt from MongoDB). In AWS this would be an ElastiCache parameter
  group.
- **Hand-written cache-aside**, not `@Cacheable`: a Redis outage would throw through `@Cacheable`
  without extra error handling, and the explicit code makes the order (save, then delete) visible
  and testable.
- **Not measured yet:** a single indexed MongoDB read is already fast; whether the cache helps is
  for the load test to show.

A real run (`redis-cli MONITOR` in one tab, LN-1001 already imported):

| Time | Request | Redis commands | Result |
|---|---|---|---|
| 13:40:11 | `PATCH` (holdback 500, doc fee 150) | `DEL lsc:loan:LN-1001` | net 27850.00 |
| 13:41:05 | `GET` | `GET`, then `SET … EX 1800` (miss) | |
| 13:43:01 | `GET` | `GET` only (hit) | response identical to the miss; `TTL` 1673 |
| 13:44:19 | `PATCH` (doc fee 150) | `DEL` | net 28350.00 |
| 13:44:26 | `GET` | `GET`, then `SET` (miss) | net 28350.00 |

The cached value contains the read-only fields and the money scale:

```
"payment":{"paymentMethod":"WIRE","currency":"USD","beneficiaryBank":"Riverbend Community Bank","valueDate":"2026-11-24"}
```

With `docker compose stop redis`, the GET still answered from MongoDB (`200` in 0.008s, one local
run where the stopped container refuses connections at once; a network partition would be bounded
by the 500ms timeout instead), and settlement-service logged:

```
WARN ... SettlementDetailCache : Settlement cache read failed for loan LN-1001, reading MongoDB: Redis exception
WARN ... SettlementDetailCache : Settlement cache write failed for loan LN-1001: Redis exception
```

A `PATCH` while Redis was down (holdback 1000, net 27500.00) succeeded and logged:

```
WARN ... SettlementDetailCache : Settlement cache evict failed, stale entry possible until TTL: key lsc:loan:LN-1001: Redis exception
```

That run still used Redis's default snapshots. After `docker compose start redis`, Lettuce
reconnected by itself and the GET returned **28350.00** from the restored cache while MongoDB held
27500.00: the stale entry the WARN line warns about. `redis-cli DEL lsc:loan:LN-1001` (the key
from the log line) fixed it. Turning snapshots off (`--save ""`) means a Redis restart now empties
the cache, but a short network problem without a restart can still leave such an entry until its
TTL (see Known limitations).

Tests: `SettlementDetailCacheIntegrationTest` runs against Testcontainers Redis (round trip keeps
every field including `beneficiaryBank`, `valueDate` and the `BigDecimal` scale; key prefix and the
configured TTL; miss; evict; an unreadable entry is a miss and is deleted);
`SettlementDetailCacheRedisDownTest` points at a port where nothing listens and checks that get,
put and evict all fail open; `SettlementServiceTest` and `LoanImportServiceTest` check that a hit
never reads MongoDB, a miss fills the cache, a not-calculated loan is not cached, and the delete
comes after the save.

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

### wire-service data model

Flyway owns the MySQL schema (`db/migration`); Hibernate runs with `ddl-auto: validate`, so the
app refuses to start if an entity and its table disagree.

| Table | Holds |
|---|---|
| `wire_template` | Wire templates, one row per revision; the highest `version` per wire type is used |
| `notification_distribution` | Standing internal recipients added to every wire (audit, treasury ops) |
| `wire_submission` | One row per generated wire: loan, wire type, status (`DRAFT` → `QUEUED` → `SENT`), merged content |
| `wire_recipient` | The four recipients of each wire with their delivery status (`PENDING` → `SENT` or `FAILED`) and `last_updated`; one per type per wire (unique constraint) |

Reference data (the settlement template `TMP-001` and the distributions `AUDIT-001` and `OPS-001`,
with `example.com` addresses) is seeded by migration `V2`, so every environment gets it.

### Send a wire

`POST /api/v1/wires/{submissionId}/send` queues a `DRAFT` wire for email delivery. wire-service
stores the wire statement in S3, publishes one `EmailDeliveryEvent` per recipient to the
`email-delivery` topic, and moves the submission to `QUEUED`. The emails themselves are sent
asynchronously, so the answer is `202 Accepted`, not `200`.

```bash
curl -X POST http://localhost:8083/api/v1/wires/SUB-…/send
```

Response (`202 Accepted`):

```json
{"submissionId": "SUB-…", "status": "QUEUED"}
```

One of the four events on `email-delivery` (key = `submissionId`, no Java type headers), from a
real run:

```json
{
  "eventId": "EVT-4e97951f-…",
  "eventType": "EMAIL_DELIVERY_REQUESTED",
  "submissionId": "SUB-d7202795-77c1-430f-8083-a19dc06856d6",
  "loanId": "LN-1001",
  "recipientId": "REC-686accba-…",
  "recipientName": "Compliance Audit Team",
  "email": "compliance-audit@example.com",
  "wireType": "SETTLEMENT",
  "subject": "Settlement Wire Statement - LN-1001",
  "attachmentPath": "s3://wire-statements/SUB-d7202795-77c1-430f-8083-a19dc06856d6/LN-1001.xml",
  "createdTime": "2026-11-22T18:22:59"
}
```

How sending stays safe:

- **The statement is stored first.** The upload happens before the claim, so if storage is down
  nothing has changed yet: the API answers 503 and the submission is still a `DRAFT`. Uploading
  again (on a retry, or by a request that then loses the claim race) writes the same bytes to the
  same key, so it is harmless.
- **Only a `DRAFT` can be sent.** The status change is one atomic
  `UPDATE … SET status = 'QUEUED' WHERE status = 'DRAFT'`, so if two users click Send at the same
  time, exactly one wins and the other gets 409.
- **Claim first, then publish.** `QUEUED` is committed before any event exists, so a delivery
  result can never arrive for a submission that is still a draft.
- **A failed publish is undone.** If Kafka does not confirm all four events (producer timeouts
  5s / 10s), the submission goes back to `DRAFT` and the API answers 503. Retrying is safe even
  after a partial publish, because notification-service de-duplicates on `recipientId`.
- **Only one wire per loan can be sent.** Generating twice still creates two drafts, but once one
  of them is `QUEUED` or `SENT`, sending another draft for the same loan and wire type returns 409.

| Status | When |
|---|---|
| 202 | Events published, submission is `QUEUED` |
| 404 `Submission not found` | No submission with this ID |
| 409 `Submission not sendable` | The submission is already `QUEUED` or `SENT`, or another request is sending it |
| 409 `Wire already sent` | Another submission for the same loan and wire type was already queued or sent |
| 503 `Wire statement storage unavailable` | The statement could not be stored (S3 down, timeout 2s per attempt / 5s total); still a `DRAFT`, retry later |
| 503 `Email delivery unavailable` | Kafka did not confirm the events; the submission is still a `DRAFT`, so retry later |
| 500 | Unexpected error (details are logged, not returned) |

Example 409:

```json
{"title": "Wire already sent", "status": 409, "detail": "A SETTLEMENT wire for loan LN-1001 has already been sent", "instance": "/api/v1/wires/SUB-…/send"}
```

### Wire statement storage (S3)

The blueprint's event carries an `attachmentPath` such as `s3://wire-statements/…`. The statement
XML is stored in an S3 bucket at send time and downloaded by notification-service to attach it.

- **Object layout:** bucket `wire-statements`, key `{submissionId}/{loanId}.xml`, content type
  `application/xml; charset=UTF-8`, bytes = `wire_submission.content`. The blueprint example uses
  the dealer ID as the file name; this project uses the loan ID because `wire_submission` has no
  dealer column.
- **Local store: SeaweedFS, not MinIO.** MinIO was the original plan, but MinIO stopped publishing
  its community Docker images in October 2025 and later put the open-source repository into
  maintenance mode. SeaweedFS (Apache 2.0) runs as a single container (`weed mini`) and creates the
  bucket from an environment variable.
- **Client: AWS SDK for Java v2 `S3Client`,** not a product-specific client. Locally it uses an
  endpoint override and path-style URLs (`localhost:8333/wire-statements/key`); against real AWS S3
  the endpoint is left empty and credentials come from the default chain (an IAM role). Checksums
  are sent only when required, because recent SDK versions add checksum headers by default that
  some S3-compatible stores reject.
- **The bucket belongs to the infrastructure,** not the apps: compose and the test containers
  create it; in AWS it would be Terraform. (Kafka topics are different: the apps declare those.)
- **notification-service only reads from the configured bucket.** The path arrives in a Kafka
  event, so anything that is not `s3://wire-statements/<key>` is rejected as an invalid event;
  otherwise a bad event could make it fetch and email any object its credentials can read.
- **Each service has its own copy** of the storage settings and `S3Config` (no shared module), the
  same rule as for the event classes.

Settings (`storage:` in each `application.yaml`): `endpoint`, `region`, `access-key`, `secret-key`,
`bucket`, `path-style-access`, `api-call-attempt-timeout` (2s), `api-call-timeout` (5s). Missing
required values stop the app at startup (`@Validated`).

Read a stored statement with curl (it signs the request itself, no AWS CLI needed):

```bash
curl --aws-sigv4 "aws:amz:us-east-1:s3" --user "dsp-local:dsp-local-secret" \
  localhost:8333/wire-statements/SUB-…/LN-1001.xml
```

**Storage down at send time.** With SeaweedFS stopped, sending a new LN-1001 draft:

```bash
docker compose stop seaweedfs
curl -i -X POST http://localhost:8083/api/v1/wires/SUB-…/send
```

```
HTTP/1.1 503
{"detail":"Could not store the wire statement. It is still a DRAFT; try again later.","instance":"/api/v1/wires/SUB-0d44ed47-fdd2-4504-abfc-d1c27a12f3b5/send","status":503,"title":"Wire statement storage unavailable"}
```

MySQL still shows `DRAFT` and no events were published. After `docker compose start seaweedfs`,
the same request answered `202 QUEUED` and the submission ended `SENT`.

### Email delivery (notification-service)

notification-service (the blueprint's "BlastMail") consumes `email-delivery` in consumer group
`blastmail`. Every instance joins the same group, so each event is handled by exactly one
instance. For each event it downloads the wire statement from S3, sends one email over SMTP
(Mailpit locally) with the statement attached, and publishes one `DeliveryStatusEvent` to
`delivery-status`, keyed by `submissionId` like the request.

The email sent to the treasury distribution for loan LN-1001 (as shown in Mailpit):

```
From:    blastmail@example.com
To:      treasury-ops@example.com
Subject: Settlement Wire Statement - LN-1001
Attachment: LN-1001.xml (631 B, application/xml)

Hello Treasury Operations,

A SETTLEMENT wire statement has been issued for loan LN-1001.
Submission ID: SUB-0d44ed47-fdd2-4504-abfc-d1c27a12f3b5

The wire statement is attached (LN-1001.xml).

This is an automated message from the Dealer Settlement Platform. Please do not reply.
```

The blueprint does not define the email body or sender; the body is plain text built from the
event, and the message is multipart (text + XML attachment). The order per event is: validate
(including the attachment path) → claim the Redis key → download → send. A duplicate event that
finds `SENT` in Redis never downloads anything.

**Idempotency (Redis).** `recipientId` is the de-duplication key. notification-service keeps
its own Redis (`blastmail-redis`, port 6380), separate from the cache Redis planned for
settlement-service, so one service's evictions or flushes cannot break the other's guarantees.
Key: `blastmail:delivery:{recipientId}`.

| Key state | Set when | TTL | What a new event for that recipient does |
|---|---|---|---|
| (no key) | — | — | Claims the key (`SET … PROCESSING NX`) and sends the email |
| `PROCESSING` | Just before sending | 5 minutes | Retried later (another attempt is sending, or crashed) |
| `SENT` | After the SMTP server accepted the email | 30 days (= DLQ retention) | No email; publishes `SENT` again |
| deleted | When sending failed | — | A retry or a DLQ replay can send |

The order is send → mark `SENT` → publish the result, so every crash point has a safe outcome:

- Crash before sending: the event is redelivered; once the `PROCESSING` key expires it is sent.
- Crash after sending but before marking `SENT`: the email can go out twice (see Known
  limitations). Delivery is at-least-once: a duplicate notice is better than a lost one.
- Crash after marking `SENT` but before publishing: the redelivered event finds `SENT` and only
  republishes the result, so wire-service still learns about it.

The `delivery-status` result for one recipient (no Java type headers):

```json
{
  "eventId": "EVT-…",
  "eventType": "EMAIL_DELIVERY_COMPLETED",
  "submissionId": "SUB-…",
  "loanId": "LN-1002",
  "recipientId": "REC-…",
  "deliveryStatus": "SENT",
  "errorMessage": null,
  "processedTime": "2026-11-22T14:29:59"
}
```

A failed email produces `"eventType": "EMAIL_DELIVERY_FAILED"`, `"deliveryStatus": "FAILED"` and
the SMTP error in `errorMessage` (e.g. `Mail server connection failed. …`, capped at 500
characters). The blueprint only shows the success event; the failure event type is this project's
choice.

Errors:

| Problem | Retried? | Then |
|---|---|---|
| Invalid JSON, wrong `eventType`, missing `submissionId` / `recipientId` / `email`, `attachmentPath` missing or outside the `wire-statements` bucket | No | `email-delivery-dlq` |
| Statement not found in S3 (`NoSuchKey`) | No | `FAILED` result to `delivery-status`, then `email-delivery-dlq` |
| Statement download failed (S3 down, timeout, 5xx) | 2 retries, 1 s apart | `FAILED` result to `delivery-status`, then `email-delivery-dlq` |
| SMTP failure (server down, rejected) | 2 retries, 1 s apart | `FAILED` result to `delivery-status`, then `email-delivery-dlq` |
| Anything else (recipient being processed by another attempt, Redis or Kafka unavailable) | 2 retries, 1 s apart | `email-delivery-dlq` only |

`FAILED` is reported only when the email is known not to have gone out: an SMTP failure, or a
statement that could not be downloaded (the download happens before SMTP). In code these share one
parent exception, `DeliveryFailedException`, which is what the recoverer checks for. A missing
statement is not retried because wire-service uploads before it publishes, so it will not appear a
second later. The dead-letter topic keeps the original event text unchanged, so after the cause is
fixed it can be replayed (see the replay command below); the recipient's Redis key was deleted, so
the replay sends.

### Delivery status

wire-service listens on `delivery-status` (consumer group `pwt-delivery-status`) for the result
of each email. For each `DeliveryStatusEvent` it updates that recipient's `delivery_status` and
sets `last_updated` to the event's `processedTime`. When all four recipients are `SENT`, the
submission moves from `QUEUED` to `SENT`. The recipient update and the submission update happen
in one transaction.

MySQL after notification-service delivered all four emails for LN-1002 (before attachments were
added; the LN-1001 run with attachments ended the same way, all four `SENT` at 18:23:00 for events
created at 18:22:59):

```
+---------------+---------+--------+
| submission_id | loan_id | status |
+---------------+---------+--------+
| SUB-…         | LN-1002 | SENT   |
+---------------+---------+--------+
+---------------------------+---------------------------------------+-----------------+---------------------+
| recipient_type            | email                                 | delivery_status | last_updated        |
+---------------------------+---------------------------------------+-----------------+---------------------+
| AUDIT_DISTRIBUTION        | compliance-audit@example.com          | SENT            | 2026-11-22 14:29:59 |
| DEALER                    | accounts@riverbend-motors.example.com | SENT            | 2026-11-22 14:29:59 |
| DEALER_BANK_CONTACT       | wires@riverbend-bank.example.com      | SENT            | 2026-11-22 14:29:59 |
| INTERNAL_OPS_DISTRIBUTION | treasury-ops@example.com              | SENT            | 2026-11-22 14:29:59 |
+---------------------------+---------------------------------------+-----------------+---------------------+
```

Why this is safe:

- Status events are keyed by `submissionId`, so all four results for one wire land on the same
  partition and are handled one after another. The "were all four SENT?" check cannot race.
- Applying the same event twice gives the same result, so Kafka's at-least-once redelivery is
  harmless. Offsets are committed only after an event is processed (or dead-lettered).
- A `FAILED` result marks that recipient `FAILED`; the submission stays `QUEUED` (the blueprint
  defines no failed submission status) and the error message is logged. A later `SENT` result for
  the same recipient (e.g. after a replay) replaces `FAILED`.

Errors:

| Problem | Retried? | Then |
|---|---|---|
| Invalid JSON or unknown `deliveryStatus` value | No | `delivery-status-dlq` |
| Unknown recipient, recipient not on that submission, or a `PENDING` result | No | `delivery-status-dlq` |
| Anything else (e.g. MySQL briefly unavailable) | 2 retries, 1 s apart | `delivery-status-dlq` |

The dead-letter topic keeps the original message text unchanged, with Spring's exception headers,
so it can be inspected and replayed.

### Kafka topics

| Topic | Producer | Consumer (group) | Partitions | Retention |
|---|---|---|---|---|
| `email-delivery` | wire-service | notification-service (`blastmail`) | 6 | 7 days |
| `delivery-status` | notification-service | wire-service (`pwt-delivery-status`) | 6 | 7 days |
| `email-delivery-dlq` | notification-service | support / replay | 6 | 30 days |
| `delivery-status-dlq` | wire-service | support / replay | 6 | 30 days |

Each service declares the topics it produces to, so every topic has one owner that sets its
partitions and retention. Producers use `acks=all` with idempotence enabled. Replication factor
is a setting (`topics.replication-factor`): 1 on the single local broker, 3 or more in a real
cluster. A dead-letter record goes to the same partition number as the original, which is why the
DLQs have 6 partitions too.

`delivery-status` on the local broker, before notification-service existed (auto-created by the
broker) and after notification-service declared it:

```
Topic: delivery-status  PartitionCount: 1  ReplicationFactor: 1  Configs:
Topic: delivery-status  PartitionCount: 6  ReplicationFactor: 1  Configs: retention.ms=604800000
```

### Try the Kafka flow by hand

With all four services running and a calculated loan, generate a wire, then send it as shown
above. The four emails appear in Mailpit (http://localhost:8025). To watch the events (stop with
Ctrl+C; Docker then prints a "Debug this Compose error" hint, which can be ignored):

```bash
# Requests published by wire-service
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic email-delivery --from-beginning --property print.key=true --property print.headers=true

# Results published by notification-service
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic delivery-status --from-beginning --property print.key=true --property print.headers=true
```

Each record prints as `NO_HEADERS  SUB-…  {json}`: no type headers, keyed by `submissionId`.

Check the result in MySQL and the idempotency keys in Redis:

```bash
docker compose exec mysql mysql -udsp -pdsp wire_db \
  -e "SELECT recipient_type, delivery_status, last_updated FROM wire_recipient WHERE submission_id='SUB-…';"

docker compose exec blastmail-redis redis-cli --scan --pattern 'blastmail:delivery:*'
docker compose exec blastmail-redis redis-cli TTL blastmail:delivery:REC-…    # about 2592000 s = 30 days
```

**Failure and replay.** Stopping Mailpit before sending shows the failure path. This run used loan
LN-2001:

```bash
docker compose stop mailpit
curl -X POST http://localhost:8083/api/v1/wires/SUB-…/send      # still 202: delivery is asynchronous
```

notification-service tries each email three times, publishes `FAILED`, and dead-letters the
event. Retries hold up the rest of that partition, so the four recipients fail one after another:

```
+---------------------------+-----------------+---------------------+
| recipient_type            | delivery_status | last_updated        |
+---------------------------+-----------------+---------------------+
| AUDIT_DISTRIBUTION        | FAILED          | 2026-11-22 14:38:54 |
| DEALER                    | FAILED          | 2026-11-22 14:38:56 |
| DEALER_BANK_CONTACT       | FAILED          | 2026-11-22 14:38:59 |
| INTERNAL_OPS_DISTRIBUTION | FAILED          | 2026-11-22 14:39:02 |
+---------------------------+-----------------+---------------------+
```

The submission stays `QUEUED`, and `email-delivery-dlq` holds the four original events with
headers such as `kafka_dlt-exception-fqcn` (Spring's `ListenerExecutionFailedException`),
`kafka_dlt-exception-cause-fqcn` (`EmailSendException`) and `kafka_dlt-original-topic`.

To replay, bring SMTP back and copy the dead-lettered events, with their keys, back to
`email-delivery`. This is a manual stand-in for the blueprint's "Support / Retry Job":

```bash
docker compose start mailpit
docker compose exec -T kafka sh -c '/opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic email-delivery-dlq --from-beginning --timeout-ms 5000 --property print.key=true --property key.separator="|" | /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 --topic email-delivery --property parse.key=true --property key.separator="|"'
```

The console consumer ends by logging `ERROR ... TimeoutException` followed by
`Processed a total of 4 messages`; that is how `--timeout-ms` stops it, not a failure. All four
emails are then sent, each recipient goes from `FAILED` to `SENT`, and the submission becomes
`SENT`:

```
+--------+
| status |
+--------+
| SENT   |
+--------+
+---------------------------+-----------------+---------------------+
| recipient_type            | delivery_status | last_updated        |
+---------------------------+-----------------+---------------------+
| AUDIT_DISTRIBUTION        | SENT            | 2026-11-22 14:42:50 |
| DEALER                    | SENT            | 2026-11-22 14:42:50 |
| DEALER_BANK_CONTACT       | SENT            | 2026-11-22 14:42:50 |
| INTERNAL_OPS_DISTRIBUTION | SENT            | 2026-11-22 14:42:50 |
+---------------------------+-----------------+---------------------+
```

Replaying the whole DLQ is safe even if some of its events were already delivered: those find
`SENT` in Redis and only republish the result.

When a new consumer group is added to a topic that already has history, start it at the end of the
topic so old events are not processed again:

```bash
docker compose exec kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --group blastmail --topic email-delivery --reset-offsets --to-latest --execute
```

## What works so far

- [x] Multi-module Maven project and Docker Compose infrastructure
- [x] loan-service: `GET /api/v1/loans/{loanId}` with `@WebMvcTest` tests
- [x] settlement-service: MongoDB document model (one document per loan, Decimal128 money,
      optimistic locking with `@Version`, audit timestamps) with `@DataMongoTest` tests
- [x] settlement-service: concurrent loan import (`CompletableFuture` on a dedicated thread pool)
      with per-loan outcomes and idempotent re-import
- [x] settlement-service: settlement calculation (`PATCH`) with BigDecimal math, Bean Validation,
      business-rule checks, injected `Clock` for value dates, and RFC 9457 error responses
- [x] settlement-service: settlement detail (`GET`) for wire-service, including the imported
      loan and dealer contacts, with distinct 404s for "not imported" and "not calculated"
- [x] settlement-service: MongoDB integration tests run against Testcontainers (fresh `mongo:7`
      per run, wired with `@ServiceConnection`), isolated from local manual testing
- [x] wire-service: MySQL schema via Flyway (templates, distributions, submissions, recipients)
      with seed data; JPA entities checked by `ddl-auto: validate` against Testcontainers MySQL
- [x] wire-service: settlement-service client (`RestClient` with connect/read timeouts) that turns
      the two 404 titles, 5xx and I/O failures into distinct exceptions; tested with
      `MockRestServiceServer`
- [x] wire-service: template merger that fills `{{placeholder}}` values in one regex pass,
      XML-escapes them, and fails fast on a placeholder with no value
- [x] wire-service: wire generation (`POST /api/v1/wires`): template + settlement detail merged
      into a `DRAFT` submission with four recipients in one transaction, remote call kept outside
      the transaction, RFC 9457 errors (400 / 422 / 502 / 500); verified end to end with all
      three services
- [x] wire-service: wire sending (`POST /api/v1/wires/{submissionId}/send`): atomic
      `DRAFT` → `QUEUED` claim, four events published to Kafka with broker acknowledgement,
      revert to `DRAFT` and 503 if Kafka does not confirm, duplicate-send guard per loan
- [x] wire-service: `delivery-status` consumer that updates recipients and completes the
      submission, with retries and a dead-letter topic; Kafka + MySQL integration tests
- [x] notification-service: `email-delivery` consumer that sends one email per recipient over SMTP,
      de-duplicates on `recipientId` in its own Redis, and publishes `SENT` / `FAILED` results
- [x] notification-service: retries, `FAILED` result and `email-delivery-dlq` for SMTP failures;
      unit tests plus integration tests against Testcontainers Kafka and Redis and a GreenMail SMTP
      server (happy path, duplicate event, invalid JSON, SMTP down)
- [x] Workflow 4 verified end to end with all four services: send → four emails in Mailpit →
      submission `SENT`; and SMTP down → `FAILED` + DLQ → replay → `SENT`
- [x] Wire statement attachment: wire-service uploads the statement to S3 (SeaweedFS locally,
      AWS SDK v2) before claiming the send, with 503 and no state change if storage is down;
      notification-service validates the path, downloads the statement and sends it as an email
      attachment, with `FAILED` + DLQ for a missing or unreachable statement; integration tests
      against Testcontainers SeaweedFS check the attachment byte for byte; verified end to end
      (attachment in Mailpit, and storage down → 503 → still `DRAFT` → retry → `SENT`)
- [x] settlement-service: connect/read timeouts and a Resilience4j circuit breaker on the
      loan-service call, a separate `LOAN_SERVICE_UNAVAILABLE` import outcome, and `FAILED`
      messages without internal details; verified end to end (loan-service down → breaker open →
      half-open trial calls → closed)
- [x] settlement-service: Redis cache-aside for the settlement detail (snapshot of the stored data,
      delete after every write, fail-open when Redis is down, TTL + LRU); integration tests against
      Testcontainers Redis; verified end to end with `redis-cli MONITOR` (miss, hit, delete on
      PATCH, Redis down, and a stale entry after a failed delete)

## Roadmap

- [x] Wire statement attachment (S3, SeaweedFS locally)
- [x] Resilience4j circuit breaker and HTTP timeouts on the loan-service call
- [x] Redis cache-aside for the settlement detail
- [ ] Load test wire generation: sequential vs parallel calls (and check the HTTP and Kafka
      producer timeouts)
- [ ] Observability: Prometheus, Grafana, OpenTelemetry
- [ ] Automated retry job for the dead-letter topics
- [ ] Maybe: a short-lived AWS demo deployment
- [ ] Small: more CLOSED sample loans (or a script to reset local demo data); every end-to-end run
      uses one up, because each loan can be sent only once

## Known limitations

- `LocalDate` fields (`closingDate`, `valueDate`) are stored as local midnight in MongoDB and
  can shift a day across timezones
- Two simultaneous imports of the same loan report FAILED instead of ALREADY_IMPORTED
  (data stays safe because `_id` is unique)
- While the circuit breaker is half-open, only 2 trial calls get through; in a concurrent batch
  the other loans come back `LOAN_SERVICE_UNAVAILABLE` even though loan-service has already
  recovered, and have to be imported again (all breaker settings are starting values to tune)
- Each settlement-service instance has its own circuit breaker state; with several instances,
  each one has to see the failures itself before it opens
- Circuit breaker state changes are not logged or exposed as metrics yet (planned with
  observability)
- The cache holds the whole settlement detail, not only loan metadata as the blueprint says,
  because loan and settlement share one MongoDB document (caching only the loan saves no read)
- If deleting a cache entry fails (Redis unreachable during a `PATCH`), the old settlement can be
  served until the 30-minute TTL ends; the WARN line names the key to delete by hand (reproduced
  locally, see above)
- Cache-aside race: a slow `GET` that read MongoDB just before a `PATCH` can put the old value back
  after the `PATCH` deleted it; the TTL limits how long it stays
- No stampede protection: when a popular entry expires, concurrent `GET`s all read MongoDB
- Whether the cache makes `GET` faster has not been measured yet (load test)
- Lettuce logs each failed reconnect with a full stack trace at WARN while Redis is down (noisy)
- Value dates skip weekends only; there is no holiday calendar (e.g. a value date can fall on
  New Year's Day)
- The business time zone (America/New_York) is hard-coded in `ClockConfig`, not configurable
- The API rejects amounts with more than 2 decimal places as written (`10.500` → 400), although
  the calculator itself would accept it
- The settlement wire template shows only the total adjustment, not each adjustment line (simple
  `{{placeholder}}` templates cannot repeat a block per item)
- Generating a wire twice for the same loan creates two `DRAFT` submissions; only one of them can
  be sent
- The duplicate-send guard is check-then-act: two different drafts for the same loan sent at the
  same instant could both pass it
- No transactional outbox: if wire-service crashes between claiming `QUEUED` and publishing the
  events, the submission stays `QUEUED` with no events
- Replication factor is 1 locally (the blueprint asks for at least 3)
- A `FAILED` delivery leaves the submission `QUEUED`; the error message is logged by wire-service,
  not stored (the blueprint's recipient table has no column for it)
- There is no endpoint to read a submission's status (the blueprint defines none)
- Email delivery is at-least-once: a crash after the SMTP server accepted an email but before its
  Redis key is marked `SENT` can send that email twice
- If notification-service crashes while sending, a redelivered event finds the `PROCESSING` key,
  exhausts its retries within seconds, and is dead-lettered without a `FAILED` result; the
  recipient stays `PENDING` until the event is replayed (after the 5-minute key expiry)
- Retries block their partition: while one event is retried, later events on the same partition
  wait (non-blocking retry topics would avoid this)
- Dead-letter replay is a manual command; there is no automated retry job yet
- Every dead-lettered record logs a full stack trace at WARN, which is noisy
- Both services use the same local S3 credentials; a real setup would give wire-service write
  access and notification-service read-only access to the bucket
- Events published before attachments were added (`attachmentPath: null`) are now rejected as
  invalid, so such events still in a dead-letter topic cannot be replayed
- Stored statements are kept forever (no lifecycle rule), and nothing deletes the object of a
  draft that is never sent again after a failed send
- notification-service reads the whole statement into memory; fine for small XML files, not for
  large documents
