# Dealer Settlement Platform

A personal learning project: Java/Spring Boot microservices that model a dealer loan settlement
and wire-notification workflow. Built to practice microservice design, concurrency, MongoDB/MySQL,
Kafka, Redis, S3-compatible object storage, resilience patterns, Spring Security (user
authentication behind a load balancer, role-based access, service-to-service OAuth2 tokens), and
observability (metrics and distributed tracing).

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

Users reach settlement-service and wire-service through an AWS Application Load Balancer that has
already logged them in (OIDC) and passes a signed claims header; both services verify that header
themselves. wire-service gets its own OAuth2 token (client credentials, Keycloak locally) for the
settlement-service call. See [Authentication and authorization](#authentication-and-authorization).

## Tech stack

Java 17 · Spring Boot 4 · Maven (multi-module) · MongoDB · MySQL · Flyway · Kafka · Redis ·
Spring Security (OAuth2 resource server and client) · Keycloak ·
Spring Mail · WebClient (Reactor Netty) · AWS SDK for Java v2 (S3) · SeaweedFS · Resilience4j ·
Spring Boot Actuator + Micrometer · Prometheus · Grafana · OpenTelemetry · Jaeger · Docker Compose · JUnit 6 · Mockito · Testcontainers · Awaitility ·
GreenMail · k6 · Toxiproxy

## Running locally

Prerequisites: Java 17, Maven, Docker Desktop.

```bash
docker compose up -d          # MySQL, MongoDB, Redis (x2), Kafka, Mailpit, SeaweedFS, Keycloak
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
| `keycloak` | 8180 (admin console: admin / admin, local only) | issues wire-service's token for settlement-service (realm `dsp`, imported from `keycloak/dsp-realm.json`) |
| `toxiproxy` | 8474 (API), 13306, 18082, 18081 | load tests only (profile `load-test`, not started by `up -d`; see [Load test](#load-test-wire-generation) and [Import under load](#import-under-load-the-loan-service-call)) |
| `prometheus` | 9090 | scrapes the services' metrics (profile `observability`, not started by `up -d`; see [Observability](#observability-part-1-metrics-prometheus--grafana)) |
| `grafana` | 3000 (admin / admin, local only) | the DSP Overview dashboard (profile `observability`) |
| `jaeger` | 16686 (UI), 4318 (OTLP over HTTP) | receives and shows the services' traces (profile `observability`; see [Tracing](#observability-part-2-tracing-opentelemetry--jaeger)) |

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

settlement-service and wire-service answer `401` without a user token. Locally there is no load
balancer, so a dev tool signs the header the ALB would send. One-time setup of a dev key pair (the
private key stays in `.local-keys/`, which is git-ignored), then start both services with its
public key, and mint one token per role:

```bash
mkdir -p .local-keys
openssl ecparam -name prime256v1 -genkey -noout -out .local-keys/alb-dev-ec.pem
openssl pkcs8 -topk8 -nocrypt -in .local-keys/alb-dev-ec.pem -out .local-keys/alb-dev-private.pem
openssl ec -in .local-keys/alb-dev-ec.pem -pubout -out .local-keys/alb-dev-public.pem

# start settlement-service and wire-service with the matching public key
# (from the IDE: set the same environment variable in both run configurations)
SECURITY_ALB_PUBLICKEY="$(cat .local-keys/alb-dev-public.pem)" mvn -pl settlement-service spring-boot:run
SECURITY_ALB_PUBLICKEY="$(cat .local-keys/alb-dev-public.pem)" mvn -pl wire-service spring-boot:run

# tokens (arguments: subject, groups, minutes)
FINOPS=$(java dev-tools/AlbDevToken.java dev-finops FinanceOps 60)
ADMIN=$(java dev-tools/AlbDevToken.java dev-admin SettlementAdmin 60)
```

The tokens are local test credentials: do not paste them into websites that decode JWTs.

```bash
# Fetch a sample loan from the mock upstream (no security: it stands in for another team's system)
curl http://localhost:8081/api/v1/loans/LN-1001

# Import several loans concurrently into settlement-service
curl -X POST http://localhost:8082/api/v1/imports \
  -H "x-amzn-oidc-data: $FINOPS" \
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
| `IMPORT_CAPACITY_EXCEEDED` | settlement-service's own import pool was full (10 threads + 50 queued): nothing was started for this loan, so trying again later is safe; see [Rejection handling](#rejection-handling-no-500-no-orphans) |
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
- **Slow calls count too:** a call that takes longer than 2 s is "slow" even when it succeeds, and
  50% slow calls open the breaker as well (`slow-call-duration-threshold` / `slow-call-rate-threshold`).
  Without this, a loan-service that answers in 4 s (below the 5 s read timeout) never opened the
  breaker and filled the import pool; see [Import under load](#import-under-load-the-loan-service-call).
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
curl -s -X POST localhost:8082/api/v1/imports -H "x-amzn-oidc-data: $FINOPS" -H 'Content-Type: application/json' \
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
outcomes above; `LoanServiceClientFallbackTest` checks that the three fallback methods stay
`public` (why: [a race in Resilience4j](#fallback-race-in-resilience4j)).

### Calculate a settlement

`PATCH /api/v1/loans/{loanId}/settlements` calculates (or recalculates) the settlement for an
imported loan. The gross amount always comes from the imported principal; the request only
supplies payment details and adjustments. Each call **replaces** all adjustments, so repeating
a request gives the same result.

```bash
curl -X PATCH http://localhost:8082/api/v1/loans/LN-1001/settlements \
  -H "x-amzn-oidc-data: $FINOPS" \
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
| 401 / 403 | No valid user token / not `FinanceOps` (see [roles](#roles-maker-checker)) |
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
curl http://localhost:8082/api/v1/loans/LN-1001/settlements -H "x-amzn-oidc-data: $FINOPS"
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
| 401 / 403 | No valid token / not `FinanceOps`, `SettlementAdmin` or a service token with `settlement.read` |
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
  -H "x-amzn-oidc-data: $FINOPS" \
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
| 401 / 403 | No valid user token / not `FinanceOps` |
| 422 `Loan not imported` | settlement-service has not imported the loan |
| 422 `Settlement not calculated` | The loan is imported but its settlement is not calculated yet |
| 502 | settlement-service is down, timed out (connect 2s, read 5s), returned 5xx, or its circuit breaker is open |
| 503 `Service authentication unavailable` | wire-service could not get its token from the identity provider; nothing was created, retry later |
| 500 | Unexpected error, e.g. missing reference data (details are logged, not returned) |

Example 422:

```json
{"title": "Settlement not calculated", "status": 422, "detail": "Settlement for loan LN-1002 has not been calculated yet", "instance": "/api/v1/wires", "loanId": "LN-1002"}
```

settlement-service answers 404 for these two cases; wire-service answers 422 because its own
request is valid but names a loan that cannot be wired yet. The titles are the same in both
services.

The settlement-service call is started first (non-blocking, WebClient), and the template and
distribution reads (blocking JDBC) run on the request thread while it is in flight; the save comes
after, because it needs the settlement detail. If the reference data is missing, the call that is
already running is cancelled and the request fails with the same 500 as before. Why it is built this
way, and what it changed, is measured in the [load test](#load-test-wire-generation). A circuit
breaker on the call answers `502` at once while settlement-service keeps failing; see
[Timeouts under load](#timeouts-under-load-the-settlement-service-call).

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
curl -X POST http://localhost:8083/api/v1/wires/SUB-…/send -H "x-amzn-oidc-data: $ADMIN"
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
| 401 / 403 | No valid user token / not `SettlementAdmin` (the maker cannot send) |
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
curl -i -X POST http://localhost:8083/api/v1/wires/SUB-…/send -H "x-amzn-oidc-data: $ADMIN"
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
its own Redis (`blastmail-redis`, port 6380), separate from settlement-service's cache Redis, so
one service's evictions or flushes cannot break the other's guarantees.
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
curl -X POST http://localhost:8083/api/v1/wires/SUB-…/send -H "x-amzn-oidc-data: $ADMIN"   # still 202: delivery is asynchronous
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

## Authentication and authorization

The blueprint puts the user login at the load balancer: an AWS ALB authenticates the user with
OIDC and forwards the request with signed headers. The services do not trust the network for
this; each one verifies the header itself, so a request that reaches a service without going
through the ALB (or with a forged header) gets `401`.

### User requests: the ALB claims header

The ALB sends `x-amzn-oidc-data`, a JWT signed by the ALB (ES256). Both services check it with a
pre-authentication filter (`AlbHeaderAuthenticationFilter` → `AlbClaimsVerifier`):

- **Algorithm is ES256,** and nothing else, so a token that claims another algorithm (for example
  `none` or HMAC) is rejected before any key is used
- **`signer`** in the token header must be this load balancer's ARN (`security.alb.signer`)
- **`kid`** must name the configured key, and the **signature** must verify with it
- **Expiry** (`exp`) with 60 s clock skew, and a non-blank `sub`
- **Groups** from the `groups` claim become roles with the `ROLE_` prefix (`FinanceOps` →
  `ROLE_FinanceOps`)

The services are stateless: no session and no cookie on their side; every request is checked
again. A rejected header logs one WARN line with the reason (`Rejected ALB claims: …`), never the
token.

Settings (`security.alb:` in both `application.yaml` files): `claims-header`, `signer`, `key-id`,
`groups-claim`, `clock-skew`, `public-key` (PEM). Missing values stop the app at startup.

### Roles (maker-checker)

Two layers. The URL rules deny by default: `/api/v1/**` needs an authenticated caller, Actuator
`health`, `metrics` and `prometheus` are open, everything else is denied. Each endpoint then names its roles
with `@PreAuthorize`:

| Endpoint | Allowed |
|---|---|
| `POST /api/v1/imports` | `FinanceOps` |
| `PATCH /api/v1/loans/{loanId}/settlements` | `FinanceOps` |
| `GET /api/v1/loans/{loanId}/settlements` | `FinanceOps`, `SettlementAdmin`, or a service token with scope `settlement.read` |
| `POST /api/v1/wires` | `FinanceOps` |
| `POST /api/v1/wires/{submissionId}/send` | `SettlementAdmin` |

Generating a wire and sending it are split between two roles (maker-checker): the user who
prepares a wire cannot release it. Authorization runs before the lookup, so a user without the
role gets `403` even for a loan or submission that does not exist (no information about which IDs
exist).

Errors use the same Problem Details format as the rest of the API:

```json
{"title": "Unauthorized", "status": 401, "detail": "Authentication required.", "instance": "/api/v1/wires"}
{"title": "Forbidden", "status": 403, "detail": "You do not have permission for this operation.", "instance": "/api/v1/wires/SUB-…/send"}
```

### Service to service: wire-service → settlement-service

wire-service calls settlement-service on its own behalf, not with the user's token. It uses the
OAuth2 client credentials grant against Keycloak (locally; realm `dsp` in
[`keycloak/dsp-realm.json`](keycloak/dsp-realm.json)):

- **Client** `wire-service`, scope `settlement.read`, audience `settlement-service`, tokens valid
  5 minutes, signed RS256
- **wire-service** (`spring-boot-starter-security-oauth2-client`) gets the token through
  `ServiceTokenFilter` on its WebClient. The token is cached and reused until it expires; a `401`
  from settlement-service drops it so the next call gets a fresh one. The token call is blocking,
  so it runs on Reactor's `boundedElastic` scheduler, not on a Netty I/O thread
- **settlement-service** is an OAuth2 resource server: it checks the issuer, the audience and the
  signature (keys fetched from Keycloak on first use, so it starts without Keycloak), and the
  `settlement.read` scope allows only the `GET`. A service token on `PATCH` gets `403`
- Both kinds of token work side by side in settlement-service: the ALB header for users, a bearer
  token for services. A bad bearer token gets the same `401` Problem Details as a bad ALB header

Try it by hand (local client secret, never used outside this machine):

```bash
curl -s -u wire-service:wire-service-local-secret -d grant_type=client_credentials \
  http://localhost:8180/realms/dsp/protocol/openid-connect/token
```

**Identity provider down.** With Keycloak stopped, generating a wire answers `503`:

```bash
docker compose stop keycloak
curl -i -X POST http://localhost:8083/api/v1/wires -H "x-amzn-oidc-data: $FINOPS" \
  -H "Content-Type: application/json" -d '{"loanId": "LN-1001", "wireType": "SETTLEMENT"}'
```

```json
{"title": "Service authentication unavailable", "status": 503, "detail": "Could not authenticate to settlement-service. No wire was created; try again later.", "instance": "/api/v1/wires"}
```

In that run settlement-service received no call, the number of `DRAFT`s for LN-1001 was the same
before and after, and the circuit breaker recorded the call as ignored
(`resilience4j.circuitbreaker.calls` with `kind:ignored` = 1.0): a missing token says nothing
about settlement-service's health, so it must not open the breaker. After
`docker compose start keycloak`, the same request answered `201` without restarting wire-service.
If settlement-service itself rejects the token (`401` / `403`), that is a configuration error, so
wire-service answers `500` and logs it.

Tests: `AlbClaimsVerifierTest` signs real tokens with a test key pair (valid, wrong signature,
wrong algorithm, wrong signer, unknown key ID, expired, missing subject); the controller tests send
signed headers through the real `SecurityConfig` and check 401, 403 and the role matrix, plus
service tokens (`jwt()` from spring-security-test); `ServiceTokenFilterTest` and
`SettlementServiceClientTest` check that the bearer token is sent, that a 401 drops the cached
token, and that an identity provider failure becomes the 503 exception without calling
settlement-service.

## Load test: wire generation

The blueprint's "Challenge" says wire generation was slow because wire-service loaded the template
from MySQL and *then* called settlement-service, two independent steps one after the other, and that
running them in parallel with WebClient fixed it. This section measures that claim on this project
instead of repeating it. All numbers below come from my own runs; none come from the blueprint.

The load tests were run before Spring Security was added. The scripts now send a user token
(`-e TOKEN=...`, minted as in [Try it](#try-it)); the numbers below were not re-measured with it.

### Setup

- **Tool:** k6 v2.4.0, script [`load-test/wire-generation.js`](load-test/wire-generation.js).
- **Open model:** a fixed arrival rate (`constant-arrival-rate`), so every version gets exactly the
  same load and only latency can differ. A closed model (a fixed number of looping users) would send
  more requests to whichever version is faster.
- **Load:** 20 requests/s for 60 s (about 1,200 requests per run), after an unreported warm-up of
  5 requests/s for 20 s (JIT, Hibernate query plans, pool connections). 20 req/s is a test input,
  taken from the low end of the blueprint's "20-50 requests per second for short bursts".
- **Pass criteria per run:** every response `201` with a `SUB-` id, and zero dropped iterations
  (k6 really delivered the requested rate). Every run below passed both.
- **Server-side split:** wire-service exposes Actuator (`health`, `metrics` only). The script reads
  `http.server.requests`, `http.client.requests` (the settlement-service call),
  `spring.data.repository.invocations` (per repository method) and `hikaricp.connections.acquire`
  before and after each run and prints this run's means.
- **Same start for every measured service:** `java -jar` on the packaged jar. IntelliJ's run
  configuration adds `-XX:TieredStopAtLevel=1` (no optimizing JIT), which would skew the numbers.
- **One loan:** LN-1001, its settlement detail already in settlement-service's Redis cache. Each
  request creates a `DRAFT` submission with four recipients in local `wire_db` (cleanup below).

**Injected latency (Toxiproxy).** On one laptop, MySQL and settlement-service answer in a few
milliseconds, so the blueprint's slow network does not exist locally. Toxiproxy
(`ghcr.io/shopify/toxiproxy:2.12.0`, profile `load-test`, proxies in
[`load-test/toxiproxy.json`](load-test/toxiproxy.json)) sits between wire-service and both
dependencies. "Injected" runs add **+200 ms to every settlement-service response and +20 ms to
every MySQL round trip** (`latency` toxic, `downstream`, no jitter). These are round example values,
not measurements of any real network, and were not chosen to reproduce the blueprint's "4 seconds".
Every run except run 1 goes through the proxy, with or without the toxics, so the proxy's own cost
is measured too.

```bash
docker compose up -d toxiproxy
java -jar wire-service/target/wire-service-0.1.0-SNAPSHOT.jar \
  --spring.datasource.url=jdbc:mysql://localhost:13306/wire_db \
  --clients.settlement-service.base-url=http://localhost:18082

# latency on (off again: curl -X DELETE localhost:8474/proxies/<proxy>/toxics/<name>)
curl -X POST localhost:8474/proxies/settlement-service/toxics -H "Content-Type: application/json" \
  -d '{"name":"lsc_latency","type":"latency","stream":"downstream","attributes":{"latency":200,"jitter":0}}'
curl -X POST localhost:8474/proxies/mysql/toxics -H "Content-Type: application/json" \
  -d '{"name":"mysql_latency","type":"latency","stream":"downstream","attributes":{"latency":20,"jitter":0}}'

FINOPS=$(java dev-tools/AlbDevToken.java dev-finops FinanceOps 120)
k6 run -e TOKEN="$FINOPS" -e RATE=5 -e DURATION=20s load-test/wire-generation.js     # warm-up, not reported
k6 run -e TOKEN="$FINOPS" -e RATE=20 -e DURATION=60s load-test/wire-generation.js    # measured
```

### Results

20 requests/s for 60 s, 1,200-1,201 requests per run, 100% `201`, 0 dropped iterations. MacBook,
local Docker Desktop, runs of 2026-11-23 (run 1) and 2026-11-24 (runs 2-7).

| Run | Version | Network | Server mean | k6 median | k6 p95 | k6 p99 |
|---|---|---|---|---|---|---|
| 1 | sequential | direct | 18.7 ms | 19.79 ms | 25.31 ms | 27.23 ms |
| 2 | sequential | proxy | 20.3 ms | 20.98 ms | 28.72 ms | 33.25 ms |
| 4 | P1: parallel | proxy | 17.9 ms | 18.68 ms | 26.61 ms | 29.46 ms |
| 6 | P1 + P6: batching | proxy | **14.5 ms** | **15.31 ms** | **17.88 ms** | 28.63 ms |
| 3 | sequential | injected | 528.5 ms | 528.35 ms | 533.81 ms | 556.11 ms |
| 5 | P1: parallel | injected | 378.6 ms | 378.75 ms | 383.49 ms | 404.04 ms |
| 7 | P1 + P6: batching | injected | **314.3 ms** | **314.83 ms** | **317.89 ms** | **326.69 ms** |

Run 7 was repeated once by accident and gave 314.4 ms, so the setup repeats well. With injected
latency the two changes together took the mean from 528.5 ms to 314.3 ms (-41%); through the proxy
without latency, from 20.3 ms to 14.5 ms (-29%).

Server-side split (means per request):

| Run | LSC call | Template | `findAll` | Connection acquire |
|---|---|---|---|---|
| 1 | 3.1 ms | 2.0 ms | 1.7 ms | 0.0 ms (3 per request) |
| 3 | 203.6 ms | 22.2 ms | 22.2 ms | 0.1 ms |
| 5 | 203.9 ms | 22.5 ms | 22.3 ms | 0.1 ms |
| 7 | 203.4 ms | 23.1 ms | 22.0 ms | 0.4 ms |

What the runs show:

- **The connection pool and threads were never the problem** (the blueprint's first theory):
  getting a connection took about 0 ms in every run.
- **Locally, parallelizing is not worth it for speed.** It can save at most the shorter of the two
  sides, about 3 ms here, and saved 2.4 ms (run 2 → 4).
- **The proxy costs about 1-2 ms** per request (run 1 → 2), mostly on the many MySQL round trips.
- **Little's law checks out:** requests in flight = rate × response time = 20/s × 0.53 s ≈ 10.6 in
  run 3; k6 showed 10-11 busy users, 7-9 in run 5 and 6 in run 7. Faster requests also mean fewer
  threads and connections held at once.

### What the database really receives (MySQL general log)

Before P1 I predicted it could save at most 44 ms with latency (the shorter side: template +
`findAll` = 44.4 ms). It saved 150 ms. The prediction was wrong, so I counted what MySQL actually
receives for one request, with the general log switched on for a single request:

```bash
docker compose exec mysql mysql -uroot -proot -e "SET GLOBAL log_output='TABLE'; SET GLOBAL general_log='ON';"
# one POST /api/v1/wires
docker compose exec mysql mysql -uroot -proot -e "SET GLOBAL general_log='OFF'; SELECT thread_id, CONVERT(LEFT(argument, 110) USING utf8mb4) AS stmt FROM mysql.general_log WHERE command_type IN ('Query','Execute') ORDER BY event_time; TRUNCATE mysql.general_log;"
```

| # | Statement (before P6) | Part of |
|---|---|---|
| 1 | `select ... from wire_template ...` | template lookup (no transaction) |
| 2 | `SET SESSION TRANSACTION READ ONLY` | `findAll` transaction |
| 3 | `SET autocommit=0` | `findAll` transaction |
| 4 | `select ... from notification_distribution` | `findAll` |
| 5 | `COMMIT` | `findAll` transaction |
| 6 | `SET autocommit=1` | `findAll` transaction |
| 7 | `SET SESSION TRANSACTION READ WRITE` | `findAll` transaction |
| 8 | `SET autocommit=0` | save transaction |
| 9-13 | `insert into wire_submission`, then 4 separate `insert into wire_recipient` | save |
| 14 | `COMMIT` | save transaction |
| 15 | `SET autocommit=1` | save transaction |

15 round trips, and the repository timers only cover the SQL itself, not the transaction
statements around it. `findAll()` comes from Spring Data's `SimpleJpaRepository`, which runs it in
a `@Transactional(readOnly = true)` transaction: five extra round trips. The query method declared
in the repository interface (`findFirstByWireTypeOrderByVersionDesc`) gets no transaction by
default and runs in autocommit. With 20 ms per round trip:

- reads: 1 + 6 = 7 round trips ≈ 140 ms (the timers showed 44 ms)
- save: 8 round trips ≈ 160 ms
- sequential: 200 (settlement-service) + 140 + 160 = 500 ms, plus framework → **528.5 ms measured**
- parallel: max(200, 140) + 160 = 360 ms, plus framework → **378.6 ms measured**

So P1 hides the whole read cost, about 140 ms, behind the settlement-service call (150 ms
measured). Under network latency, **the number of round trips decides the response time**, more
than the SQL itself. That is what led to P6.

### P1: start the settlement-service call first (blueprint's fix)

The blueprint uses WebClient for both steps, but the template comes from MySQL through JPA/JDBC,
and JDBC always blocks a thread; WebClient makes only HTTP calls non-blocking. So:

- `SettlementServiceClient` uses WebClient on Reactor Netty (`spring-boot-starter-webclient`, not
  the WebFlux starter: the app stays a Tomcat app). `getSettlementDetailAsync` returns a
  `CompletableFuture`; `toFuture()` subscribes at once, because a `Mono` on its own is lazy and would
  send nothing. Timeouts (2 s connect, 5 s response) and the error mapping (the two 404 titles,
  5xx, timeouts, empty body) are unchanged, so the API answers exactly as before.
- `WireGenerationService` starts the call, reads the template and distributions on the request
  thread while it is in flight, then waits, merges and saves.
- Rejected alternatives: two `CompletableFuture` tasks on an executor (two blocked threads per
  request); caching the template (about 2 ms locally; the blueprint rejects it too).
- The client is built from Boot's `WebClient.Builder` (it was the static `RestClient.builder()`
  before), so the call is measured (`http.client.requests`) and traced (see
  [Tracing](#observability-part-2-tracing-opentelemetry--jaeger)).

### P6: batch the INSERTs (beyond the blueprint)

Three settings in wire-service's `application.yaml`, no code:

- `spring.jpa.properties.hibernate.jdbc.batch_size: 20` and `order_inserts: true`: Hibernate sends
  same-table INSERTs as one JDBC batch. This only works because the IDs are assigned in Java
  (`SUB-`/`REC-` + UUID); with `IDENTITY` (auto-increment) Hibernate cannot batch inserts.
- `rewriteBatchedStatements: true` (MySQL Connector/J): without it, the driver still sends one
  statement per row, so batching alone changes nothing. With it, the four recipients become one
  multi-row `INSERT`.
- `useLocalSessionState: true`: with only the first two changes, the general log showed a new
  `SELECT @@session.transaction_read_only` before **each** batch, so the save went from 8 round trips
  to only 7. This option lets the driver trust its own record of autocommit/read-only (kept up to
  date through the JDBC API) instead of asking the server. Safe here because nothing changes session
  state with raw `SET` statements.
- The driver options are set under `spring.datasource.hikari.data-source-properties`, not in the
  JDBC URL, because the load-test runs override the URL and would silently drop them.

Result: 15 → 12 statements per wire, the save path from 8 to 5 round trips. Predicted -60 ms with
latency, measured -64 ms (run 5 → 7). Critical path in run 7: 314.3 - 203.4 (settlement-service) =
111 ms, of which 5 round trips × 20 ms = 100 ms.

Rejected: `readOnlyPropagatesToServer=false` would remove the two `SET SESSION TRANSACTION ...`
statements (2 round trips), but then MySQL no longer enforces read-only transactions; giving up that
guarantee on wire data is not worth 40 ms.

### Predictions vs measurements

| Change | Predicted | Measured |
|---|---|---|
| P1, through the proxy (run 2 → 4) | at most ~3 ms | -2.4 ms |
| P1, injected latency (run 3 → 5) | at most 44 ms | **-150 ms** (the ceiling used timers that leave out transaction round trips; the general log explains the real saving) |
| P6, injected latency (run 5 → 7) | ≈ -60 ms (3 round trips) | -64 ms |
| P6, through the proxy (run 4 → 6) | a few ms | -3.4 ms |

### Caveats

- k6, all services, MySQL, MongoDB and Redis share one laptop's CPU: absolute numbers are
  "this machine" numbers; the comparison is fair because every run used the same machine and start.
- The injected latencies are example values. In a real network the round-trip cost (and so the
  savings) would be different, but the round-trip counts would not.
- One loan with a warm settlement-service cache; 60-second runs; only run 7 repeated. Whether the
  cache itself makes `GET` faster is still not measured.

### Clean up local test data

Every request creates a `DRAFT` (local `wire_db` only). Delete the load-test drafts for the loan,
counting first (`created_time` = when the first run started):

```bash
docker compose exec mysql mysql -udsp -pdsp wire_db -e "SELECT COUNT(*) FROM wire_submission WHERE loan_id='LN-1001' AND status='DRAFT' AND created_time >= '2026-11-23 15:30:00';"
docker compose exec mysql mysql -udsp -pdsp wire_db -e "DELETE r FROM wire_recipient r JOIN wire_submission s ON s.submission_id = r.submission_id WHERE s.loan_id='LN-1001' AND s.status='DRAFT' AND s.created_time >= '2026-11-23 15:30:00'; DELETE FROM wire_submission WHERE loan_id='LN-1001' AND status='DRAFT' AND created_time >= '2026-11-23 15:30:00';"
```

## Timeouts under load: the settlement-service call

The previous load test made wire generation faster when everything works. This one asks what happens
when settlement-service is **slow**: does the 5 s response timeout really fire under load, and what
else runs out before it does? The timeout values (connect 2 s, response 5 s) were starting values;
the blueprint gives none. As before, every number comes from my own runs (2026-11-24, same MacBook).

### Setup

- Same k6 script, extended for these runs: `EXPECT_STATUS=502` for runs where every request is meant
  to fail (so a stray 201 or 500 still fails the run), `PRE_VUS` / `MAX_VUS` so k6 can hold hundreds
  of slow requests open, and a second scenario that samples gauges once a second:
  `tomcat.threads.busy` (needs `server.tomcat.mbeanregistry.enabled: true`),
  `hikaricp.connections.active` / `pending`, and later the circuit breaker state.
- Only settlement-service goes through Toxiproxy; MySQL is direct, so only this one timeout is tested.
  A `latency` toxic of 4 s (just under the timeout) or 6 s (just over it) delays every response.
- Tomcat has its default 200 request threads.

```bash
docker compose up -d toxiproxy
java -jar wire-service/target/wire-service-0.1.0-SNAPSHOT.jar --clients.settlement-service.base-url=http://localhost:18082
curl -X POST localhost:8474/proxies/settlement-service/toxics -H "Content-Type: application/json" \
  -d '{"name":"lsc_latency","type":"latency","stream":"downstream","attributes":{"latency":6000,"jitter":0}}'
k6 run -e TOKEN="$FINOPS" -e RATE=50 -e DURATION=60s -e EXPECT_STATUS=502 -e PRE_VUS=900 -e MAX_VUS=1200 load-test/wire-generation.js
```

### Results

| Run | Added latency | Rate | Breaker | Responses | k6 median | k6 p95 | k6 max | Server mean | Tomcat busy (peak) |
|---|---|---|---|---|---|---|---|---|---|
| T1 | 4 s | 20/s | — | 1,200 × `201` | 4.01 s | 4.02 s | 4.14 s | 4,014.5 ms | 84 |
| T2 | 6 s | 20/s | — | 1,201 × `502` | 5.00 s | 5.00 s | 5.22 s | 5,006.3 ms | 102 |
| T3 | 6 s | ~46.7/s (50 asked) | — | 2,800 × `502` | 10.97 s | 14.48 s | 14.58 s | 5,004.9 ms | 200 |
| T3b | 6 s | 50/s | — | 3,001 × `502` | 12.03 s | 19.14 s | 20.14 s | 5,005.6 ms | 200 |
| T4 | 6 s | 50/s | yes | 3,000 × `502` | **6.97 ms** | 5.00 s | **6.07 s** | 357.6 ms | 200 (median 2) |
| T5 | none | 5/s | yes, open at start | 101 × `201` | 18.7 ms | 22.81 ms | 24.28 ms | 17.6 ms | 2 |

Hikari never had more than 4 active connections or any pending request in any run: no DB connection
is held while the settlement-service call is in flight (D9: remote calls outside any transaction,
`open-in-view: false`).

**T1 and T2: the timeout works.** At 4 s every request succeeded in about 4 s; at 6 s every request
got a `502` after 5.00 s, the response timeout, not the 6 s answer. Little's law held both times:
20/s × 4 s = 80 requests in flight (Tomcat showed 82 busy, plus the sampler's own calls), 20/s × 5 s =
100 (102 busy). T1 also settled a question the documentation could not: I could not tell from it
whether WebClient's default connection pool allows 16 or 500 connections. With 16, most T1 requests
would have failed fast or waited far longer than 4 s; all 1,200 succeeded at about 4 s, so the pool was
not the limit at 80 concurrent calls.

**T1 also shows what P1 does not do.** A Tomcat thread is busy for the whole 4 s of every request. The
settlement-service call itself is non-blocking (Netty), but the request thread then waits in `join()`
for the answer. The blueprint says WebClient "frees the thread while waiting"; here that is true for
Netty's I/O threads, not for the request thread. Freeing that one too would need an asynchronous
controller or WebFlux, a design change rather than a setting (see Known limitations).

**T3: more requests in flight than threads.** At 50 req/s a 5 s timeout needs 50 × 5 = 250 requests in
flight, more than Tomcat's 200 threads. So at most 200 / 5 s = 40 requests per second can be served;
the other 10 per second wait for a free thread, and that queue grows for the whole run:

- **Model:** after 60 s about 600 requests are waiting; at 40 per second the last one waits about
  15 s for a thread, then 5 s for the timeout: about **20 s**.
- **T3b measured 20.14 s** (median 12.03 s). The first T3 run reached only 14.58 s because k6 had too
  few virtual users ready and really sent about 46.7 req/s (about 200 requests dropped); with that rate the
  same model gives about 15 s. Pre-allocating 900 VUs (T3b) fixed the rate.
- **The server's own metrics did not see it.** `http.server.requests` said 5.0 s in both runs while
  users waited up to 20 s: the timer starts only when a thread picks the request up, so the time spent
  waiting for a thread is invisible on the server. Only the client (or a load balancer's target
  response time) shows it.
- **Actuator queued too.** It runs on the same port and thread pool, so the gauge sampler could not get
  its answers in time (k6: "Insufficient VUs … scenario=observe"); see Known limitations.

So a 5 s timeout protects the request threads only while rate × timeout stays below the thread count:
at 20 req/s it does (100 < 200), at 50 req/s it does not (250 > 200).

### Circuit breaker on the settlement-service call (beyond the blueprint)

The blueprint puts a circuit breaker only on the loan-service call (settlement-service, see above). T3
is the reason this project adds one to wire-service's settlement-service call too: a timeout limits how
long **one** request waits; a circuit breaker limits how **many** requests pay that wait.

- **B1, inside the WebClient chain.** A Resilience4j `CircuitBreakerOperator`
  (`resilience4j-reactor`) is applied to the `Mono`, not the `@CircuitBreaker` annotation used in
  settlement-service. When the template is missing, P1 cancels the call that is already running; the
  operator treats a cancellation as "release the permit", while the annotation on a
  `CompletableFuture` method could count it as a settlement-service failure, so a missing template
  could open the breaker. No Spring proxy is involved, and the registry and YAML settings still come
  from `resilience4j-spring-boot4`.
- **B2, only "unavailable" is a failure:** timeouts, connection errors, 5xx and an empty body
  (`SettlementServiceUnavailableException`, the only entry in `record-exceptions`). The two business
  404s count as successful calls.
- **B3, the API does not change.** While the breaker is open the call is never made and the client
  throws the same `SettlementServiceUnavailableException`, so the answer is the same `502`, in
  milliseconds instead of 5 s.
- **B4, settings:** the same starting values as settlement-service's breaker (last 10 calls, at least
  5, opens at 50% failures, open for 10 s, 2 trial calls when half-open), under
  `resilience4j.circuitbreaker.instances.settlementService`.
- **B5, no retry and no fallback.** A retry adds load to a struggling dependency, and wire-service has
  no settlement data of its own to fall back on; stale money figures would be worse than a `502`.

Five requests in a row with the 6 s toxic, then more:

```
502 5.024492s
502 5.016904s
502 5.018480s
502 5.022604s      (plus a fifth 5 s call; Actuator counted 5 failed calls, 25.28 s in total)
502 0.015601s
502 0.011488s
502 0.011935s
```

The breaker metrics (`resilience4j.circuitbreaker.calls`, `.not.permitted.calls`, `.state`) appear in
Actuator; the k6 script samples the open state and prints the number of calls that failed fast.

**T4 (T3b's load with the breaker):** the median went from 12.03 s to **6.97 ms** and the maximum from
20.14 s to 6.07 s. Only **210** of the 3,000 requests reached settlement-service; the other 2,790
failed fast. 210 is about Tomcat's 200 threads plus 2 trial calls per half-open cycle: the breaker only
learns from calls that have **finished**, and the first ones finish after 5 s, so the first wave (as
many as there are threads) still waits the full timeout. After that, the breaker is open about 70% of
the time (0.69 of the samples), every 10 s or so two trial calls fail after 5 s and it opens again,
Tomcat's busy threads drop to a median of 2, and Actuator answers again (no dropped samples).

**T5 (recovery):** with the toxic removed, the first request after the open period moved the breaker
to half-open, both trial calls succeeded, and it closed: 101 of 101 requests `201`, no call refused,
final state `closed`.

### Predictions vs measurements

| Run | Predicted | Measured |
|---|---|---|
| T1 | all `201` at about 4.0 s; ~80 threads busy; Hikari low | 4.01 s median; 82-84 busy; Hikari ≤ 4 |
| T1 | WebClient pool might limit at 16 connections | not the limit (all `201` at ~4 s) |
| T2 | all `502` at about 5.0 s; ~100 threads busy | 5.00 s; 102 busy |
| T2 | a stack trace per failed request in the log | **wrong**: two one-line WARNs per request |
| T3b | worst wait about 20 s, median 12-13 s | 20.14 s, 12.03 s |
| T4 | ~250 slow calls + ~8 trial calls | **210**: only as many as there are threads (200), not every request of the first 5 s |
| T4 | median a few ms, max about 6-7 s | 6.97 ms, 6.07 s |
| T5 | all `201`, breaker closed | 101 × `201`, `closed` |

### Caveats

- One laptop, injected example latencies, one loan, 60-second runs; the thread and queue numbers are
  for Tomcat's default 200 threads.
- The loan-service calls during import are covered in the next section. The S3 and Kafka producer
  timeouts on the send path are not covered yet: load-testing send needs many unique `CLOSED` loans,
  because each loan can be sent only once (see Roadmap).
- Each `201` creates a `DRAFT`; this round left 1,504 submissions (6,016 recipients), deleted with the
  commands above using `created_time >= '2026-11-24 16:10:00'`.

## Import under load: the loan-service call

The blueprint's debugging scenario: during a busy closing window, loan-service ran out of connections,
became unresponsive, and imports stopped working. Its fix is a timeout, a circuit breaker and a
fallback, all built in settlement-service (see
[Loan-service calls](#loan-service-calls-timeouts-and-circuit-breaker)). This section checks under
load what those pieces, together with the import thread pool (core 4, max 10, queue 50), really do
when loan-service is slow. Every number comes from my own runs (2026-11-24, same MacBook).

### Setup

- **Script:** [`load-test/loan-import.js`](load-test/loan-import.js), k6, open model.
- **Load:** 2 requests/s for 60 s, each importing 6 loans, so **12 loan-service calls per second**
  arrive at the import pool. 6 loans matches the blueprint's "a couple to a dozen" per dealer.
- **Loan IDs that loan-service does not know** (`LN-LT-01` … `LN-LT-06`). Each one still makes a real
  call (loan-service answers 404, outcome `NOT_FOUND`; there is no negative caching), nothing is written
  to MongoDB, and runs can be repeated without cleanup. A 404 is a successful call for the breaker, so
  only the injected latency decides what fails.
- **Toxiproxy** gets a third proxy, `loan-service` (18081 → 8081 on the Mac); settlement-service runs
  from the jar with `--clients.loan-service.base-url=http://localhost:18081`. A `latency` toxic of 4 s
  (below the 5 s read timeout) or 6 s (above it) delays every loan-service answer.
- **settlement-service now has Actuator** (`health`, `metrics`, same rules as wire-service). Without
  any code, Boot instruments the `loanImportExecutor` bean (`executor.active`, `executor.queued`,
  `executor.pool.size`, `executor.completed`), Resilience4j registers the breaker metrics, and the
  Tomcat metrics are on. At the time the loan-service client was built without Boot's builder, so it
  had no `http.client.requests`; the breaker's `resilience4j.circuitbreaker.calls` timer counts every
  call that really went out, with its duration. (Since tracing, part 2, it uses Boot's builder.)
- **Mixed per-loan outcomes are expected.** The script counts statuses (`status_200`, `status_500`) and
  per-loan outcomes (`outcome_not_found`, …), splits latency by status, and fails on a malformed `200`
  body or dropped iterations. Runs I1-I2b were made before the rejection fix, when a full pool answered
  `500`, so the script then accepted `200` or `500`; since the fix it accepts only `200` (a `500` would
  be a bug) and also counts requests that were partly or fully rejected. The gauges are
  sampled 250 ms after the import arrivals; sampling at the same instant showed 4 active / 2 queued in
  every sample of an idle run, which only says when the sampler looked.

```bash
docker compose up -d toxiproxy
java -jar settlement-service/target/settlement-service-0.1.0-SNAPSHOT.jar --clients.loan-service.base-url=http://localhost:18081
k6 run -e TOKEN="$FINOPS" -e RATE=2 -e DURATION=20s load-test/loan-import.js     # warm-up, no toxic, not reported
curl -s -X POST localhost:8474/proxies/loan-service/toxics -H 'Content-Type: application/json' \
  -d '{"name":"loan_latency","type":"latency","stream":"downstream","attributes":{"latency":4000,"jitter":0}}'
k6 run -e TOKEN="$FINOPS" -e RATE=2 -e DURATION=60s load-test/loan-import.js     # measured
curl -s -X DELETE localhost:8474/proxies/loan-service/toxics/loan_latency
```

### How the import pool grows

`ThreadPoolExecutor` (behind Spring's `ThreadPoolTaskExecutor`) handles a new task in this order:
fewer than 4 threads → start one; else room in the queue → queue it; else fewer than 10 threads → start
one; else **reject** (default `AbortPolicy`). So threads 5-10 start only once **50 tasks are already
waiting**, and the pool holds at most 60 tasks. A rejection throws `TaskRejectedException` from
`CompletableFuture.supplyAsync` on the request thread, before any per-loan error handling. Until the
fix in [Rejection handling](#rejection-handling-no-500-no-orphans), that made the whole request answer
`500`, while the tasks it had already submitted kept running.

### Results

121 requests per run (726 loans), 0 dropped iterations, every response `200` or `500`. These runs
were made before the rejection fix; the run after it (I5) is in
[Rejection handling](#rejection-handling-no-500-no-orphans).

| Run | Latency | Slow-call rule | `200` | `500` | Outcomes | loan-service calls | Breaker open | `200` median | `200` max |
|---|---|---|---|---|---|---|---|---|---|
| I1 | none | — | 121 | 0 | 726 `NOT_FOUND` | 726, mean 3.2 ms | 0% | 15.04 ms | 21.32 ms |
| I2 | 4 s | no | 10 | **111** | 60 `NOT_FOUND` | 138, all ~4 s, all "successful" | **0%** | 18.29 s | **24.54 s** |
| I3 | 6 s | no | 111 | 10 | 666 `UNAVAILABLE` | 23 failed (5 s timeout), 659 not permitted | 59% | 6.88 ms | 14.01 s |
| I4 | 6 s, removed at ~15 s | no | 110 | 10 | 482 `NOT_FOUND`, 178 `UNAVAILABLE` | 14 failed, 482 ok, 172 not permitted | 16% | 17.86 ms | 10.01 s |
| I2b | 4 s | **yes** | 113 | **7** | 16 `NOT_FOUND`, 662 `UNAVAILABLE` | **20**, all ~4 s; 674 not permitted | 67% | 7.67 ms | **8.02 s** |

Every `500` came back in milliseconds (median 6-8 ms): the rejected request never waits.

**I1, baseline.** All `200`, the pool never grew past 4 threads: a batch of 6 is 4 running + 2 queued,
never a fifth thread, which is the growth rule above.

**I2, slow but successful: the breaker never opened.** Every call took 4 s and counted as a success
(Resilience4j's slow-call threshold defaults to 60 s). With 4 threads at 4 s per call the pool finishes
1 task per second while 12 arrive, so after 10 requests (60 tasks = 10 threads + 50 queued, at about
4.5 s) every later request was rejected: 111 × `500`. The 10 requests that got in waited up to 24.5 s.
Worse, **78 of the 138 loan-service calls were orphans**: tasks a request had submitted before its own
rejection, which then held a thread for 4 s and returned a result nobody read. They kept the queue full,
which kept rejecting new requests. With real `CLOSED` loans those orphans would **import loans whose
user got a `500`**. This is the blueprint's own incident shape (an exhausted upstream is slow, not
down), and the failure-only breaker does not see it.

**I3, slow and failing: the breaker works, after the first wave.** Each call failed at the 5 s read
timeout. The settlement-service log shows the timeline:

```
19:56:56  4 calls failed   <- the 4 core threads' first calls
19:57:00  6 calls failed   <- 5th failure: breaker OPEN at 19:57:00.660, 17 ms after the last 500
19:57:01  4 calls failed
19:57:05  3 calls failed   <- started in the ~0.6 s before it opened
19:57:16  2 calls failed   <- half-open trial calls, then open again
19:57:31  2 calls failed
19:57:47  2 calls failed   (23 in total)
```

Once open, queued tasks finished in milliseconds (`LOAN_SERVICE_UNAVAILABLE`), the queue emptied, and
nothing more was rejected: fast failures are easy to absorb, slow successes are not.

**I4, recovery.** The toxic was removed at 20:21:32 while the breaker was open; the next half-open trial
got two fast 404s and closed it, and the rest of the run looked like I1. Only the first wave failed
(14 calls), and `FAILED` stayed at 0 (see the next part for why that was checked).

### Fallback race in Resilience4j

A first I4 attempt logged one import as `FAILED` ("Import failed unexpectedly") while the breaker was
simply open, after about 1,350 fallback calls that had worked:

```
ERROR LoanImportService : Import of loan LN-LT-01 failed unexpectedly
java.lang.reflect.UndeclaredThrowableException
    at LoanServiceClient$$SpringCGLIB$$0.getLoan(<generated>)
Caused by: java.lang.IllegalAccessException: class io.github.resilience4j.spring6.fallback.FallbackMethod
    cannot access a member of class ...LoanServiceClient with modifiers "private"
```

The cause is in Resilience4j 2.4.0's `FallbackMethod.invoke`: it caches one `java.lang.reflect.Method`
per fallback, shared by all threads, and for a non-public method it does
`accessible = isAccessible()` → `setAccessible(true)` → `invoke` → `finally setAccessible(false)`.
With 10 import threads in a fallback at once, one thread can reset the flag between another thread's
check and its `invoke`, and that call fails. It needs many threads in the fallback at the same moment,
which unit tests and the manual check never produced.

Fix: the three `loanServiceUnavailable` fallbacks are now `public`. A public method needs no
accessibility change, so there is nothing to race on (package-private would still race). Being public,
Resilience4j calls them through the Spring proxy; they carry no annotation, so the proxy just forwards.
`LoanServiceClientFallbackTest` checks that all three stay public, because the race itself is too rare
to reproduce reliably in a test. The clean I4 run had 0 `FAILED`, but with about 186 fallback calls
that alone proves little; the library source and the test are the evidence.

### Slow calls open the breaker (beyond the blueprint)

The blueprint's breaker exists to "fail fast when the service is unhealthy" and stop LSC's threads from
piling up. I2 shows that a breaker counting only failures cannot do that when the upstream is slow
rather than down. So `loanService` now also treats calls over **2 s** as slow, and opens at **50%** slow
calls (same window: last 10 calls, at least 5). Normal calls took 3-5 ms, so 2 s means loan-service is
in trouble; both are starting values.

I2b (I2 again with the rule): the breaker opened at about 8 s (the 5th slow call finished then; the
first four at 4 s were below the minimum of 5), and each later cycle was 10 s open plus 4 s half-open
(two slow trials, 100% slow, open again).

| | I2 | I2b |
|---|---|---|
| `500` | 111 | 7 |
| loan-service calls in 60 s | 138 | 20 (-86%) |
| Orphaned calls | 78 | 4 |
| Slowest `200` | 24.54 s | 8.02 s |

The second row matters as much as the first: a breaker protects the struggling upstream too, and an
exhausted connection pool needs fewer calls, not 12 a second.

What it does not fix: the first wave still pays (the breaker learns only from finished calls, so the
first 7 requests still got a `500`), and a rejection still turned a whole request into a `500` with
orphaned work behind it. That is fixed next.

### Rejection handling: no 500, no orphans

I2 showed two problems with a full import pool: the whole request answered `500` although some of its
loans were already queued, and those queued tasks (78 of 138 calls) kept running for a request that had
already been told it failed. With real `CLOSED` loans they would import loans the user was told were
not imported.

- **A rejection is a per-loan outcome now.** `importLoans` catches the `RejectedExecutionException`
  (Spring's `TaskRejectedException` is a subclass) that `supplyAsync` throws on the request thread. The
  loan that was rejected and every loan after it get `IMPORT_CAPACITY_EXCEEDED` ("Import not started:
  too many imports in progress, try again later") inside the normal `200`. It stops at the first
  rejection, because the pool is full at that moment and trying the rest would only produce more
  rejections.
- **Its own outcome, not `LOAN_SERVICE_UNAVAILABLE`.** For the user both mean "try again later", but
  for whoever is on call they point at different places: one at loan-service, the other at
  settlement-service's own pool and load. The blueprint defines no outcomes; this one is my choice.
- **Already submitted tasks are waited for, not cancelled.** The plan was to cancel them, but
  `cancel()` on a `CompletableFuture` only completes the future; a task that is already running on a
  thread keeps going and saves the loan, while the user would be told "not started". So the request
  waits (`join()`) for everything it started and only refuses what it never started: every task that
  runs reports its real outcome, and nothing runs after the response.
- **One WARN line per rejected request**, with counts and no stack trace (it used to be a 74-line stack
  trace from the `500` handler):

```
WARN ... LoanImportService : Import pool full: 2 of 6 loans not started
WARN ... LoanImportService : Import pool full: 6 of 6 loans not started
```

`LoanImportServiceTest` fakes a full pool with a tiny executor that runs the first task and then throws
`TaskRejectedException`: the result is one `NOT_FOUND` and two `IMPORT_CAPACITY_EXCEEDED` in input order,
and the rejected loans never reach MongoDB or loan-service.

**I5: I2 again with the fix** (2026-11-25, same machine and load: 4 s toxic, 2 requests/s × 6 loans,
60 s). The slow-call rule was pushed out of the way for this run only
(`--resilience4j.circuitbreaker.instances.loanService.slow-call-duration-threshold=10s`), so the 4 s
calls count as normal, as in I2; with the rule on, the breaker would hide the pool behaviour (I2b).

| | I2 (before) | Predicted | I5 (after) |
|---|---|---|---|
| Requests `200` / `500` | 10 / 111 | 121 / 0 | **121 / 0** |
| Loans that got a slot (`NOT_FOUND`) | 60 reported | ≈ 210 | **194** |
| Loans `IMPORT_CAPACITY_EXCEEDED` | — | ≈ 515 | **532** (194 + 532 = 726 = 121 × 6) |
| Requests fully accepted / partly / fully rejected | — | 10 / ≈ 110 / ≈ 0 | **10 / 40 / 71** |
| `200` median / max | 18.29 s / 24.54 s | ≈ 20 s / ≈ 24 s | **5.33 ms / 24.57 s** |
| Executor queued / active right after the run | orphans still running | 0 / 0 | **0 / 0** |
| Breaker open | 0% | 0% | 0% |

- **No orphans, and it can be counted:** loan-service calls = executor tasks completed = `NOT_FOUND`
  outcomes = **194**. Every call that went out was reported to a user, and the pool was empty the moment
  k6 finished.
- **The slowest request matches the model:** a full queue of 50 at 10 threads / 4 s = 2.5 tasks/s is
  20 s, plus the 4 s call itself: about 24 s; measured 24.57 s.
- **Little's law:** 2 requests/s × 9.1 s server mean ≈ 18 requests in flight; Tomcat showed a median of
  18 busy threads (max 19).

Two predictions missed, and why:

- **194, not ≈ 210 slots:** nothing finishes in the first 4 s, so slots are freed only for about 56 of
  the 60 s: 60 + 2.5/s × 56 s ≈ 200, a little less because threads 5-10 start late.
- **Fully rejected requests, and the fast median:** I assumed slots free up evenly at 2.5/s. With
  `jitter: 0` every call takes exactly 4.0 s (max 4,014.5 ms), so the 10 threads run in lockstep and
  10 slots free up together every 4 s. The log shows the same pattern in every cycle: `2 of 6`,
  `4 of 6`, `2 of 6` not started (the three requests that share the 10 freed slots), then five times
  `6 of 6`. Most requests are therefore rejected entirely, in milliseconds, and the median `200` is one
  of them. With real jitter the slots would free up more evenly and more requests would get one slot
  and wait.

**What it does not fix:** waiting time. A request that gets even one slot still waits behind the whole
queue, up to about 24 s here. Keeping that short is the job of the slow-call rule (I2b) and, if needed,
a smaller queue; a queue of 50 is deep when every task takes seconds (see Known limitations). It also
does not reduce the load on loan-service: the pool already caps it at 10 calls at a time.

### Predictions vs measurements

Written down before each run.

| Run | Predicted | Measured |
|---|---|---|
| I1 | all `200`, tens of ms, pool 4, queue ~0 | 15.04 ms median, pool 4; queue "2" in every sample was the sampler's timing, not load |
| I2 | queue full at ~4.5 s, then mostly fast `500`s (`TaskRejectedException`) | 111 × `500` from ~4.5 s, median 6.16 ms; exception as predicted |
| I2 | breaker never opens | 0% open, 0 failed calls |
| I2 | early requests wait up to ~24 s | 24.54 s |
| I2 | orphaned tasks keep calling loan-service | yes; 78 of 138 calls (not predicted: how many) |
| I3 | ~10 `500`s, breaker open at ~9.5 s | 10; open at about 9 s |
| I3 | slowest `200` up to ~10 s | **14.01 s**: 3 more calls started before the 5th failure arrived |
| I3 | ~20 loan-service calls | 23 |
| I3 | breaker open 75-80% of samples | **59%**: half-open (5 s per cycle, waiting for the trials) is not "open"; 10/15 of the time after opening ≈ 56% |
| I4 | closed within ~15 s of removing the toxic; `FAILED` 0 | closed at the first trial after removal; `FAILED` 0 |
| — | — | **not predicted:** the fallback race (1 in ~1,350) |
| I2b | open at ~8 s; ~8 `500`s; ~20 calls; slowest `200` ~8.5 s | 7 `500`s; 20 calls; 8.02 s |
| I2b | open ~60% of samples; ~20 `NOT_FOUND` | 67%; 16 (4 results went to orphans) |
| I5 | 0 × `500`; pool empty right after the run | 0 × `500`; queued 0, active 0 |
| I5 | ≈ 210 loans get a slot | **194**: no slot frees up in the first 4 s |
| I5 | slowest `200` ≈ 24 s | 24.57 s |
| I5 | most requests partly rejected, median `200` ≈ 20 s | **wrong**: 71 fully rejected, median 5.33 ms; with no jitter the threads finish in lockstep, 10 slots every 4 s |

### Caveats

- One laptop, injected example latencies, unknown loan IDs (no MongoDB writes), 60-second runs.
- 2 requests/s is a test input, not a measured production rate.
- The breaker settings (including the new 2 s / 50%) and the pool size are still starting values.
- The latency toxic has no jitter, so every slow call takes exactly the same time; I5 shows this changes
  how freed slots are shared between requests.

## Observability, part 1: metrics (Prometheus + Grafana)

The blueprint's "Monitoring" lists Prometheus for metrics, Grafana for dashboards and Spring Boot
Actuator for the application side, and names what to watch: request latency (p50/p95/p99), error
rates, JVM, thread pools and Kafka consumer lag. This part builds that locally. Tracing
(OpenTelemetry) is [part 2](#observability-part-2-tracing-opentelemetry--jaeger); structured logs
come next (see Roadmap).

### What was built

- **Every service with an API or a consumer publishes metrics** at `/actuator/prometheus`
  (`micrometer-registry-prometheus`, version from Boot): settlement-service, wire-service, and
  notification-service, which gets Actuator for the first time. notification-service has no REST API,
  but it owns the Kafka consumer whose lag the blueprint wants watched, and lag is measured by the
  consumer itself (the Kafka client reports how far it is behind the end of each partition).
  loan-service is a mock of another team's system and is not scraped.
- **Prometheus pulls.** Every 5 s it GETs each service's `/actuator/prometheus`; the services do not
  know Prometheus exists. A service that stops answering shows up as `up == 0`. Prometheus runs in
  Docker and the services on the Mac, so the targets are `host.docker.internal:8082/8083/8084`, the
  same route Toxiproxy uses ([`observability/prometheus/prometheus.yml`](observability/prometheus/prometheus.yml)).
- **Percentiles come from histogram buckets.** `management.metrics.distribution.percentiles-histogram`
  is on for `http.server.requests` (and `http.client.requests` in wire-service, the
  settlement-service call), so each service publishes counts per latency bucket and Prometheus
  computes p50/p95/p99 with `histogram_quantile`. Percentiles calculated inside the app cannot be
  combined across instances or time windows (two p95s cannot be averaged); bucket counts can be added
  up. The price: a percentile is an estimate within a bucket, not an exact value.
- **Grafana is configured from files in git** ([`observability/grafana/provisioning`](observability/grafana/provisioning)):
  the Prometheus data source (`http://prometheus:9090`, the compose service name, with a fixed `uid`
  and `timeInterval: 5s` to match the scrape interval) and the dashboard JSON. A fresh container needs
  no clicking; edits in the UI cannot be saved (`allowUiUpdates: false`), the JSON file is the source
  of truth.
- **Both run behind the compose profile `observability`**, like Toxiproxy's `load-test`, so a plain
  `docker compose up -d` stays as light as before. Images: `prom/prometheus:v3.13.5` (the 3.13
  long-term-support line) and `grafana/grafana:13.3.1` (the `grafana-oss` repository is no longer
  updated).
- **Security:** `/actuator/prometheus` is added to the `permitAll` rule next to `health` and
  `metrics` in both services, because the scraper sends no user token; every other Actuator path is
  still denied (see Known limitations).

### The DSP Overview dashboard

16 panels in six rows ([`dsp-overview.json`](observability/grafana/provisioning/dashboards/dsp-overview.json)).
Every query uses metric names checked against the services' real `/actuator/prometheus` output
first (a wrong name gives an empty panel, not an error); the panel descriptions (the ⓘ icon) explain
what each one means in this project.

| Row | Panels |
|---|---|
| Overview | services up, request rate, 5xx error ratio (API paths only, without `/actuator`) |
| Latency | p50/p95/p99 per service, p95 per endpoint, wire-service → settlement-service call p95, Kafka listener time |
| Threads and pools | Tomcat busy vs max, `loanImportExecutor` active/queued/pool size, Hikari active/pending |
| Resilience | circuit breakers not closed, calls rejected by an open breaker |
| Kafka | consumer lag per topic (both consumers: notification-service on `email-delivery`, wire-service on `delivery-status`) |
| JVM | heap used, time in GC pauses, process CPU |

The 5xx panel first showed "No data" instead of 0%: a status that never occurred has no series at
all, so 5xx / total returns nothing. The query is now `(5xx rate or total rate * 0) / total rate`,
which fills in a zero series per service, and its axis is fixed at 0-100% (with all-zero data
Grafana picked a scale of up to 10,000%).

### Try it

```bash
docker compose --profile observability up -d     # everything plus prometheus and grafana
# or, if the rest is already running:
docker compose up -d prometheus grafana

curl -s 'localhost:9090/api/v1/query?query=up'   # one result per service, value "1"
```

- Prometheus targets: http://localhost:9090/targets
- Dashboard: http://localhost:3000/d/dsp-overview (admin / admin, local only)

Neither container has a volume: `docker compose down` deletes the collected metrics.

### Evidence: an outage the error rate does not show

Three k6 runs on 2026-11-25 (services started with `mvn spring-boot:run`, not the measured `java -jar`
setup of the load tests, so the latencies are not comparable with those tables):

| Time | Run | Result | Dashboard |
|---|---|---|---|
| 14:57 | import, 2 req/s × 6 unknown loans, **loan-service not running** | 121 × `200`, all 726 loans `LOAN_SERVICE_UNAVAILABLE`; 16 calls went out and failed (mean 3.4 ms), 720 were rejected by the open breaker | 5xx ratio **0%**; breaker `loanService open`; ~12 rejected calls/s |
| 14:59 | wire generation, 5 req/s for 60 s | 301 × `201`, server mean 32.9 ms (settlement-service call 14.3 ms), Hikari pending 0 | request rate 5/s on both services; the 914 ms first request shows as a p99 spike |
| 15:02 | import again, loan-service running, settlement-service **not** restarted | 722 `NOT_FOUND`, 4 `LOAN_SERVICE_UNAVAILABLE`, 0 failed calls (mean 2.1 ms), executor queued/active 0 afterwards | breaker closed again; small blip of rejected calls |

- **The error rate stayed at 0% during a complete import outage.** The import answers `200` with a
  per-loan outcome, so the failure was only visible in the Resilience row. One global error number
  would have missed it; the right signal depends on the endpoint.
- **The breaker stayed open from about 14:57 to 15:01**, long after the outage run ended at 14:58:
  automatic transition is off, so it moves to half-open only when the next call arrives. That call
  was the first batch of the 15:02 run: 2 trial calls went through (`NOT_FOUND`, a success), the other
  4 loans of that batch were rejected, and the breaker closed. The same pattern as the manual check in
  [Loan-service calls](#loan-service-calls-timeouts-and-circuit-breaker).
- The wire run left 301 drafts (1,204 recipients) in local `wire_db`, deleted afterwards (count first,
  4:1 ratio checked).

### Tests

The rule that matters is in `SecurityConfig`, so it is tested where the real `SecurityConfig` already
runs: the `@WebMvcTest` controller tests of both services (`AlbTestSecurityConfig`). `GET
/actuator/prometheus` without a token is neither `401` nor `403` (the web slice has no Actuator, so
the request ends after security), and `GET /actuator/env` without a token is a `401` Problem Details.
Removing the path from `permitAll` makes the first test fail with `401`, checked once by hand.
Not a `@SpringBootTest`: an extra annotation would start a second context with its own MySQL, Kafka
and SeaweedFS containers. (This section first also said that Boot's test support turns metrics export
off in a `@SpringBootTest`. Part 2 found that in Boot 4 such test defaults live in separate test
starters, and the metrics one is not on the classpath, so that reason is unverified.)

## Observability, part 2: tracing (OpenTelemetry + Jaeger)

The blueprint's "Request Tracing": OpenTelemetry creates a trace when a request enters a service,
the trace context travels in HTTP headers to the next service and in Kafka headers to the consumer
(its example headers: `traceparent`, `submissionId`, `loanId`), and every service adds its own spans
to the same trace. This part builds that for the HTTP calls and both Kafka hops. Every number below
comes from my own runs on 2026-11-25 (services started with `mvn spring-boot:run`, so latencies are
not comparable with the load-test tables).

### What was built

- **Boot 4's own starter** `spring-boot-starter-opentelemetry` in settlement-service, wire-service and
  notification-service: the OpenTelemetry SDK, the Micrometer Tracing bridge (every Micrometer
  observation that Spring already makes becomes a span) and the OTLP exporter. Spans are **pushed**
  over OTLP/HTTP to `management.opentelemetry.tracing.export.otlp.endpoint`
  (`http://localhost:4318/v1/traces`; Boot 4.1 has no default). The service name in Jaeger is
  `spring.application.name`.
- **Metrics stay pulled.** The starter also brings Micrometer's OTLP *metrics* registry, which is on by
  default and would push metrics to `localhost:4318` every minute, Jaeger's port. Prometheus already
  pulls them, so `management.otlp.metrics.export.enabled: false`.
- **Sampling 1.0 locally** (`management.tracing.sampling.probability`; Boot's default is 0.1, which
  would leave 9 of 10 test requests without a trace). A real deployment would sample less.
- **Jaeger v2** (`cr.jaegertracing.io/jaegertracing/jaeger:2.23.0`), profile `observability` like
  Prometheus and Grafana, in-memory storage, UI on 16686, OTLP/HTTP on 4318. Jaeger v2 is one binary
  built on the OpenTelemetry Collector; `jaegertracing/all-in-one` is the v1 image, end of life since
  31 Dec 2025.
- **Where the trace context crosses a boundary, and what carries it:**

| Boundary | How the context travels | Change needed |
|---|---|---|
| wire-service → settlement-service (HTTP) | `traceparent` header | none: the WebClient was already built from Boot's `WebClient.Builder` (P1) |
| settlement-service → loan-service (HTTP) | `traceparent` header | **G1:** `LoanServiceClient` now uses Boot's `RestClient.Builder` (`spring-boot-starter-restclient`) instead of the static `RestClient.builder()`, same request factory and 2 s / 5 s timeouts |
| request thread → `loanImportExecutor` thread | snapshot at submit, restored in the worker | `ContextPropagatingTaskDecorator` on the pool |
| wire-service → `email-delivery` → notification-service → `delivery-status` → wire-service (Kafka) | `traceparent` record header | **G2:** `spring.kafka.template.observation-enabled` and `spring.kafka.listener.observation-enabled` in both services |

- **G3, the blueprint's Kafka headers:** both producers add `submissionId` and `loanId` as record
  headers (`toRecord` in `EmailDeliveryPublisher` / `DeliveryStatusPublisher`). They are for tools that
  read headers only (console consumer, a broker UI); no consumer reads them, so the payload stays the
  one contract.
- **Dead-letter templates are not observed.** They are built by hand inside the error handlers (a
  second `KafkaTemplate` bean would make Boot back off from its own), so publishing to a DLQ makes no
  span. The dead-letter recoverer copies the original record's headers, so a DLQ record is expected to
  keep the original `traceparent`; that is not verified yet (see Known limitations).
- **Less noise.** Without filtering, Prometheus's scrape every 5 s produced 5 traces per service per
  30 s, and Spring Security added 4-7 spans to every request:
  - an `ObservationPredicate` (`config/ObservationConfig`, one copy per service) drops every
    observation for `/actuator` requests. It checks twice: the server observation itself, and any
    observation made while the current thread serves an `/actuator` request (`RequestContextHolder`,
    filled by Boot's request-context filter before Spring Security runs). Dropping only the first one
    would turn the authorization span inside a scrape into a trace of its own.
  - `SecurityObservationSettings.withDefaults()` keeps Spring Security's authentication and
    authorization spans (who was let in, and by which expression) and drops the filter-chain spans
    (`security filterchain before/after`, `secured request`).
  - Result: 5 scrape traces in 30 s → **0**, and **0** orphan traces; the trace of one
    `POST /api/v1/wires` went from 13 to **9** spans.
- **Log correlation came for free:** every log line now has a `[traceId-spanId]` column (Boot's default
  correlation pattern once Micrometer Tracing is active). Structured JSON logs are part 3.

### Try it

```bash
docker compose up -d jaeger                       # or: docker compose --profile observability up -d
curl -s localhost:16686/api/v3/services           # {"services":["jaeger","settlement-service","wire-service",...]}
```

UI: http://localhost:16686 → Service `wire-service` → Find Traces. (`jaeger` in the list is Jaeger
tracing its own API. The old `/api/services` path answers 404 in 2.23; the UI and `/api/v3` work.)

One trace as JSON, filtered to service, traceId and span name (here: the last send of a wire):

```bash
curl -s -G localhost:16686/api/v3/traces \
  --data-urlencode "query.service_name=wire-service" \
  --data-urlencode "query.operation_name=http post /api/v1/wires/{submissionId}/send" \
  --data-urlencode "query.start_time_min=$(date -u -v-120S +%Y-%m-%dT%H:%M:%SZ)" \
  --data-urlencode "query.start_time_max=$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  | grep -oE '"stringValue":"[a-z]+-service"|"traceId":"[0-9a-f]{8}|"name":"[^"]*"' | grep -v org.springframework.boot
```

(`date -v` is the macOS form; on Linux use `date -u -d '-120 seconds' ...`.)

### Evidence

**1. Wire generation, wire-service → settlement-service.** One trace, `976502d9…`, first request after
a wire-service restart (17:20:30):

| Span | Service | Duration | Parent |
|---|---|---|---|
| `http post /api/v1/wires` | wire | 547 ms | (root) |
| └ `authorize method` (`hasRole('FinanceOps')`) | wire | 14 ms | |
| └ `http get` (WebClient) | wire | 382 ms | |
| &nbsp;&nbsp;└ `http get /api/v1/loans/{loanId}/settlements` | settlement | 53 ms | the WebClient span (remote parent) |
| &nbsp;&nbsp;&nbsp;&nbsp;└ `authenticate bearertoken` → `JwtAuthenticationToken` | settlement | 19 ms | |
| &nbsp;&nbsp;&nbsp;&nbsp;└ `get` (Redis, cache hit) | settlement | 5 ms | |

The bearer token (service-to-service auth) and `traceparent` arrive together: settlement-service
authenticated the token inside the same trace. No MySQL spans (JDBC is not instrumented) and no Mongo
span (on a later cache miss, a Redis `set` followed the `get`, but the Mongo read in between had no span).

**2. A gap with no span.** The client span is 382 ms, but settlement-service received the request only
310 ms after it started. The same request, repeated:

| Request | Gap before the request left wire-service | Whole POST | Explanation |
|---|---|---|---|
| 17:20:30, first after restart | 310 ms | 547 ms | token fetched from Keycloak + Netty DNS setup + cold JIT |
| 17:24:31, 4 min later | 74 ms | 116 ms | token fetched **again** (see below) |
| 17:27:37 | 7.3 ms | 93 ms | token reused; probably a new TCP connection after 3 idle minutes |
| 17:27:42, 5 s later | **2.7 ms** | **25 ms** | everything warm |

- The token fetch (`ServiceTokenFilter`) has no span: Spring Security's OAuth2 client uses its own HTTP
  client, not Boot's builder. It shows up only as a hole in the client span.
- Keycloak's tokens live 5 minutes, but Spring Security treats a token as expired **60 s early** (its
  default clock skew), so it is reused for 4 minutes. 17:24:31 was 1 s past that point.
- At 17:27:37 settlement-service's `authenticate bearertoken` took 53 ms instead of about 2 ms. Most
  likely the same pattern on the other side (its cached copy of Keycloak's public keys was refreshed,
  more than 5 minutes after the last fetch); not proven.

**3. Import: the thread pool broke the trace.** Importing `LN-TRACE-01` before the task decorator gave
two traces: the request (`91811c91…`) and, separately, the loan-service call (`5bc4a8c7…`). The trace
context is a `ThreadLocal` of the request thread, and the call runs on a `loan-import-` thread. After
`ContextPropagatingTaskDecorator`, a two-loan import was one trace (`16ea1d0d…`) with both
`http get` spans. Each loan-service 404 shows as an `exception` event on its client span: `RestClient`
throws `HttpClientErrorException.NotFound`, and `getLoan` turns it into `NOT_FOUND` afterwards.

**4. Workflow 4, send → BlastMail → delivery-status → PWT.** One send of LN-1001 (20:29): 27 spans,
all with traceId `5235146a…`:

| Service | Spans |
|---|---|
| wire-service | `http post /api/v1/wires/{submissionId}/send`, `authorize request`, `authorize method`, 4 × `email-delivery send`, 4 × `delivery-status process` |
| notification-service | 4 × `email-delivery process`, 8 × `set` (Redis), 4 × `delivery-status send` |

The 8 Redis spans are the idempotency design (B3) seen from the outside: per recipient one
`SET … NX` (claim) and one `SET` to `SENT`, no `GET`. The S3 download and SMTP have no spans. One
`email-delivery` record, from the console consumer (`--property print.headers=true`):

```
submissionId:SUB-40fb7208-…,loanId:LN-1001,traceparent:00-5235146afa719aaadf7f2c53ade13202-69a8942accc6b9d4-03   {"eventId":"EVT-…", ...}
```

The traceId in the record header is the traceId in Jaeger. The last field, `03`, is the trace-flags
bit field: `01` sampled plus `02` random trace-id (W3C Trace Context Level 2).

### What went wrong on the way

- **Tests tried to export spans.** The first build logged `Failed to export spans ... localhost:4318`
  in a notification-service `@SpringBootTest`. Boot 4 keeps the "no reporting tracing components in
  `@SpringBootTest`" default in a test module, so each service now has
  `spring-boot-starter-opentelemetry-test` (1 → 0 such lines in the notification-service build).
- **`management.tracing.export.enabled=false` switches tracing off completely**, not only the sending:
  with it the G1 test got no `traceparent` at all, and the log correlation column disappeared. The test
  uses `@AutoConfigureTracing` with only `management.tracing.export.otlp.enabled=false`.
- **My first regex for `traceparent` expected flags `00` or `01`**; OpenTelemetry sends `03` (above).
- **Kafka consumer spans are called `process`,** not `receive` (OpenTelemetry's messaging naming).

### Tests

- `LoanServiceCircuitBreakerTest.clientFromSpringSendsW3cTraceContext` (G1): the Spring-built client
  sends a W3C `traceparent` to the fake loan-service. Written first: it failed (`null`) with the static
  builder, then passed after the change. `@AutoConfigureTracing` is free here, because the class's
  `@DynamicPropertySource` already gives it its own context.
- `EmailDeliveryPublisherTest`, `DeliveryStatusPublisherTest` (G3): topic, key = `submissionId`, value,
  and both headers, with the header names written out (not the constants) so a rename fails the test.
- Not built: an automated test that `traceparent` and `Authorization` reach settlement-service together
  (G5). The live trace above shows it, the G1 test covers the rule that matters (the client must come
  from Boot's builder), and a wire-service test would need a new Spring context with its own containers.

### Predictions vs measurements

| Check | Predicted | Measured |
|---|---|---|
| PWT → LSC | one trace, settlement span a child of the WebClient span | yes (remote parent) |
| PWT → LSC | no span for the Keycloak token call | none; visible only as a 310 ms gap |
| Second request within 5 min | gap of a few ms | **74 ms**: 60 s clock skew, token fetched again; 2.7 ms once really reused |
| Import before the decorator | loan-service call in a separate trace | yes (`91811c91…` / `5bc4a8c7…`) |
| Import after the decorator | one trace | yes, both calls of a 2-loan import |
| Noise filter | 0 scrape traces, no orphans; 13 → 9 spans per POST | 0, 0, 9 |
| Workflow 4 | one trace over both Kafka hops, 4 branches, no S3/SMTP/MySQL spans | 27 spans, one traceId; consumer spans named `process`, not `receive` |
| G1 test with `export.enabled=false` | header present | **wrong**: no tracer at all (no header) |

### Caveats

- 100% sampling and local runs only; tracing adds work per request (span creation, export batches).
  The load-test numbers in the sections above were measured without tracing and were not re-measured.
- Durations are from single requests, not from a load test.

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
- [x] wire-service: settlement-service client (non-blocking `WebClient` on Reactor Netty with
      connect/response timeouts) that turns the two 404 titles, 5xx and I/O failures into distinct
      exceptions; tested against a JDK `HttpServer`
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
- [x] Load test of wire generation (k6, open model, Toxiproxy for injected latency, Actuator timers,
      MySQL general log): settlement-service call started before the reference-data reads (P1) and
      batched INSERTs (P6); 528.5 → 314.3 ms with injected latency, 20.3 → 14.5 ms without
- [x] Timeouts under load for the settlement-service call (k6 + Toxiproxy, Tomcat thread gauge): the
      5 s timeout fires on time, but at 50 req/s requests queued for threads for up to 20 s; a
      Resilience4j circuit breaker on the call (reactive operator, same 502) cut the median to
      6.97 ms and only 210 of 3,000 requests waited; verified recovery (half-open → closed)
- [x] Import under load (k6 + Toxiproxy on loan-service, Actuator in settlement-service with executor
      and breaker metrics): found that slow-but-successful answers never opened the breaker, filled
      the import pool and turned requests into `500`s; the breaker now also opens on slow calls
      (111 → 7 `500`s, 138 → 20 calls to the slow upstream); found and fixed a concurrency race in
      Resilience4j's private-fallback handling
- [x] Import rejection handling: a full import pool gives the affected loans `IMPORT_CAPACITY_EXCEEDED`
      inside a normal `200` instead of a `500` for the whole request, and the request waits for the
      tasks it started instead of leaving them as orphans; I2 re-run as I5: 111 → 0 `500`s, every
      loan-service call reported to a user (194 = 194)
- [x] Spring Security in settlement-service and wire-service: ALB claims header verified in the
      service (ES256 signature, signer ARN, key ID, expiry), groups mapped to roles, deny-by-default
      URL rules plus `@PreAuthorize` per endpoint, maker-checker (`FinanceOps` generates,
      `SettlementAdmin` sends), 401/403 as Problem Details; verified end to end (valid token, forged
      signature, wrong role)
- [x] Service-to-service auth: wire-service gets a client-credentials token from Keycloak (cached,
      dropped on a 401) and settlement-service accepts it as a resource server (issuer, audience,
      `settlement.read` for `GET` only); identity provider down → 503 with no draft created and the
      breaker untouched; verified end to end (Workflow 3 with two identities)
- [x] Observability, part 1: `/actuator/prometheus` in settlement-service, wire-service and
      notification-service, latency histograms, Prometheus (5 s pull) and Grafana provisioned from git
      (profile `observability`) with a 16-panel dashboard; verified with an import outage that the
      error rate does not show and the breaker panels do
- [x] Observability, part 2: OpenTelemetry tracing (Boot 4 starter, OTLP to Jaeger v2) across HTTP
      (wire → settlement → loan-service), the import thread pool and both Kafka hops, with the
      blueprint's `submissionId` / `loanId` Kafka headers; scrape and Spring Security filter-chain noise
      removed; verified with one 27-span trace for a whole send (Workflow 4)

## Roadmap

- [x] Wire statement attachment (S3, SeaweedFS locally)
- [x] Resilience4j circuit breaker and HTTP timeouts on the loan-service call
- [x] Redis cache-aside for the settlement detail
- [x] Load test wire generation: sequential vs parallel calls, plus JDBC batching
- [x] Timeouts under load: settlement-service call (plus a circuit breaker on it)
- [x] Timeouts under load: loan-service calls during import (plus slow-call detection on the breaker)
- [x] Import: a rejected task no longer turns the whole request into a `500`; the affected loans get
      `IMPORT_CAPACITY_EXCEEDED`, and tasks already started are waited for rather than cancelled
      (cancelling would not stop a running task), so no orphaned imports run
- [x] Spring Security: ALB user authentication, roles and maker-checker, OAuth2 client credentials
      between wire-service and settlement-service
- [ ] Logging: one line per breaker state change instead of one WARN per loan while it is open
- [ ] Timeouts under load: S3 upload and Kafka producer on the send path (needs many unique
      `CLOSED` loans or seeded drafts, because each loan can be sent only once)
- [x] Observability, part 1: metrics (Prometheus, Grafana, dashboard)
- [x] Observability, part 2: tracing with OpenTelemetry across HTTP and Kafka
- [ ] Observability, part 3: structured JSON logs with trace IDs and business fields (ELK optional) (next)
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
- Each instance has its own circuit breaker state (settlement-service and wire-service alike); with
  several instances, each one has to see the failures itself before it opens
- Circuit breaker state changes are not logged in either service; the state is only visible in
  Actuator metrics (`resilience4j.circuitbreaker.state`)
- settlement-service's Actuator also runs on the API port and shares its request threads (same as
  wire-service)
- When the import pool is full (10 threads + 50 queued), loans get `IMPORT_CAPACITY_EXCEEDED` and
  must be imported again; a request that got even one slot waits behind the whole queue (up to about
  24 s with a 4 s loan-service, I5). A queue of 50 is deep when each task takes seconds; its size is a
  starting value
- The import pool grows past 4 threads only after 50 tasks are queued (how `ThreadPoolExecutor` works),
  so under a slow loan-service most of the "max 10" capacity arrives late
- The loan-service breaker learns only from finished calls: when loan-service becomes slow or
  unresponsive, the first wave still waits (4-5 s) and the first few requests can still get `500`s
- While the loan-service breaker is open, every loan logs one WARN line (about 12 per second at
  2 imports/s); a request rejected by the full import pool logs one WARN line with counts
- The loan-service fallback methods must stay `public` to avoid a race in Resilience4j 2.4.0's
  handling of non-public fallbacks (a test guards it)
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
- Load-test numbers come from one laptop (load generator, services and databases share the CPU),
  one loan with a warm cache, and injected example latencies, not a production-like environment
- Repository timers (`spring.data.repository.invocations`) leave out transaction round trips
  (autocommit, `COMMIT`, read-only flag); read them together with the MySQL general log
- `findAll()` on the distributions runs in a read-only transaction that costs five extra round trips;
  kept, because dropping server-side read-only enforcement is not worth it
- Wire generation now starts the settlement-service call before checking the reference data: with a
  missing template the call is made and then cancelled
- If the distributions are missing and settlement-service is down at the same time, the answer is
  now 500 instead of 502 (both are failures with nothing saved)
- `useLocalSessionState` assumes session state is only changed through the JDBC API; a raw
  `SET autocommit` statement would make the driver's view wrong
- Reactor Netty logs an ERROR on macOS about a missing native DNS resolver
  (`netty-resolver-dns-native-macos`) and falls back to the JDK resolver; harmless here, not added
  because it is a platform-specific dependency
- Actuator runs on the API port (`health`, `metrics`, `prometheus` only); in a real deployment it must not be
  reachable through the public load balancer. It also shares the request threads: when they were all
  busy (T3), Actuator calls queued with everything else (a separate `management.server.port` would
  give it its own)
- Each load-test request leaves a `DRAFT` submission in local `wire_db` (cleanup commands above)
- The request thread waits (`join()`) while the settlement-service call is in flight: WebClient frees
  Netty's I/O threads, not Tomcat's; fully non-blocking would need an asynchronous controller or WebFlux
- A 5 s timeout protects the 200 request threads only while rate × timeout stays below 200; above
  that (e.g. 50 req/s) requests queue for a thread, and `http.server.requests` does not show that wait
- wire-service's circuit breaker only learns from finished calls: when settlement-service becomes
  slow, the first wave (up to 200 requests, one per thread) still waits the full 5 s before it opens
- After its 10 s open period the breaker moves to half-open only when the next request arrives
  (automatic transition is off); while half-open, requests beyond the 2 trial calls get an immediate `502`
- Each timed-out settlement-service call logs two WARN lines (Netty's `ReadTimeoutException` and the
  handler's message): about 2 lines per request during an outage
- CSRF protection is disabled. The services are stateless and read no cookies themselves, but the
  ALB login is cookie-based, so a real deployment needs CSRF protection or `SameSite` cookies at
  the ALB
- The services check the ALB header against one configured public key. In AWS the ALB publishes
  its keys by key ID (`https://public-keys.auth.elb.<region>.amazonaws.com/<kid>`) and rotates them;
  that lookup is not built
- Maker-checker is enforced by role only: a user with both `FinanceOps` and `SettlementAdmin` could
  generate and send the same wire (there is no "created by" check)
- Actuator `health`, `metrics` and `prometheus` are open without a token (the load tests and the
  Prometheus scraper read them); they must not be reachable through the public load balancer
- settlement-service's calls to loan-service carry no service token (loan-service is a mock of
  another team's system)
- wire-service caches its token in memory per instance; each instance gets its own token
- The committed `security.alb.public-key` is a dev key whose private half is not in the repository;
  each developer generates a pair and passes the public key at startup (see [Try it](#try-it))
- The Keycloak client secret and admin password have local-only defaults (`wire-service-local-secret`,
  admin / admin); in AWS they would come from Secrets Manager or environment variables
- Prometheus and Grafana have no volumes: `docker compose down` deletes the collected metrics; Grafana
  admin / admin is local only; dashboard changes must be made in the JSON file, not in the UI
- Percentiles on the dashboard are estimates within a histogram bucket, not exact values
- No alerting rules yet: the dashboard shows problems, nothing notifies anyone (the blueprint uses
  CloudWatch for alerting)
- loan-service is not scraped (it is a mock of another team's system and has no Actuator)
- The 5 s scrape interval is a local choice for short load-test runs; 15-30 s is more usual, because
  every scrape costs storage
- Tracing samples 100% locally; a real deployment would sample less. The load-test numbers above were
  measured before tracing and not re-measured with it
- Not traced, so only visible as gaps in a trace: MySQL (JDBC), MongoDB, S3 upload/download, SMTP,
  wire-service's token fetch from Keycloak and settlement-service's fetch of Keycloak's public keys
- loan-service has no tracing (mock): settlement-service's client span is the last hop
- A business 404 from loan-service is recorded as an `exception` on the client span, so `NOT_FOUND`
  imports look like errors in Jaeger
- Log lines written on Reactor threads (e.g. `boundedElastic`, where wire-service fetches its token)
  have no traceId: the trace context is not carried onto Reactor threads (part 3)
- `/actuator` requests produce neither spans nor `http.server.requests` metrics any more (one
  predicate drops the observation, and metrics and spans come from it); the dashboard excluded them
  anyway
- Dead-letter publishing has no span, and that a DLQ record keeps the original `traceparent` is
  expected (the recoverer copies headers) but not verified
- The Kafka headers `submissionId` / `loanId` are informational copies of payload fields; nothing
  checks that they match the payload
- Jaeger keeps traces in memory: a restart or `docker compose down` deletes them
- wire-service reuses a service token for 4 of its 5 minutes (Spring Security's 60 s clock skew); the
  first call after that pays the token fetch (about 74 ms warm, more when cold)
