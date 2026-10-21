# Design

This document records how the system was designed: requirements → core entities → API → high-level design → deep
dives into the non-functional requirements. Setup, operations and tradeoffs are in the [README](../README.md).

## 1. Requirements

### Functional

| # | Requirement | Where |
|---|---|---|
| F1 | Merchant logs in and receives an access token | `POST /api/v1/auth/login` |
| F2 | Create a payment (amount, currency, reference, card token); authorized synchronously | `POST /api/v1/payments` |
| F3 | Retrieve a payment with its status history | `GET /api/v1/payments/{id}` |
| F4 | Capture an authorized payment | `POST /api/v1/payments/{id}/capture` |
| F5 | Refund a captured or settled payment (in full) | `POST /api/v1/payments/{id}/refund` |
| F6 | List and search own payments: status, date range, amount range, id/reference; paginated | `GET /api/v1/payments` |
| F7 | Payment APIs usable only by a logged-in merchant, scoped to that merchant | Spring Security + merchant-scoped queries |
| F8 | Lifecycle `PENDING → AUTHORIZED → CAPTURED → SETTLED`, with `FAILED` and `REFUNDED` | `PaymentStatus` |
| F9 | At least one asynchronous workflow | Settlement via transactional outbox |
| F10 | Dashboard: login, list, filter/search, detail with statuses | `frontend/` |

### Non-functional

| Quality | Target and approach |
|---|---|
| Correctness | No double charge or refund: Idempotency-Key, state machine, optimistic locking, unique constraints |
| Security | JWT with expiry, bcrypt, tenant isolation (404 for other merchants), no PAN or CVV stored or logged |
| Auditability | Every transition recorded with actor, reason, correlation ID, time, in the same transaction |
| Reliability | Async work cannot be lost (outbox); retry with backoff; dead-letter; crash recovery |
| Observability | JSON logs with correlation and trace IDs; RED, business and queue metrics; trace across async boundary; health probes |
| Scalability | Stateless API; horizontally scalable workers (`SKIP LOCKED`); indexes match query patterns |
| Latency | Target p99 < 300 ms for API calls excluding processor time (histograms exported to verify) |
| Maintainability | Feature packages, single source of truth for transitions, consistent errors, focused tests |

## 2. Core entities

```
merchants 1 ──── * payments 1 ──── * payment_events      (append-only audit trail)
     │                  1
     │                  └──── * outbox_jobs              (async work, e.g. SETTLE_PAYMENT)
     └──── * idempotency_keys                            (stored responses, 24 h)
```

| Entity | Key fields | Notes |
|---|---|---|
| **Merchant** | id, name, email (unique), password_hash | bcrypt hash only |
| **Payment** | id, merchant_id, amount_minor, currency, status, merchant_reference, card_brand, card_last4, processor_reference, failure_reason, version, timestamps | `amount_minor` is a `BIGINT` (never floating point). `version` for optimistic locking. CHECK constraint on status |
| **PaymentEvent** | payment_id, from_status, to_status, actor_type, actor_id, reason, correlation_id, created_at | Immutable; `from_status` null for creation |
| **IdempotencyRecord** | merchant_id + key (unique), request_hash, status, response_status, response_body (jsonb), expires_at | Replays the original response |
| **OutboxJob** | job_type, payment_id, status, attempts, next_attempt_at, locked_at, last_error, correlation_id, traceparent | Partial index on due PENDING jobs |

Indexes lead with `merchant_id` because every query is merchant-scoped: `(merchant_id, created_at desc)` for the
default listing, `(merchant_id, status)` and `(merchant_id, merchant_reference)` for filters.

### State machine

| From \ To | AUTHORIZED | CAPTURED | SETTLED | FAILED | REFUNDED | Triggered by |
|---|---|---|---|---|---|---|
| PENDING | ✓ | | | ✓ | | processor response at creation |
| AUTHORIZED | | ✓ | | ✓ | | merchant capture |
| CAPTURED | | | ✓ | | ✓ | settlement worker / merchant refund |
| SETTLED | | | | | ✓ | merchant refund |
| FAILED, REFUNDED | | | | | | terminal |

## 3. API design principles

- Resource-oriented paths, with actions as sub-resources for state transitions (`/capture`, `/refund`), since they
  are not idempotent updates of a field.
- Money as `{amount: <minor units>, currency: "USD"}`.
- `Idempotency-Key` required on every money-moving POST; retries replay the stored response with
  `Idempotent-Replayed: true`.
- `X-Request-Id` accepted (validated) and always returned; it becomes the correlation ID.
- One error shape (RFC 7807) with a stable `code`; field-level `errors[]` for validation.
- 404, not 403, for other merchants' resources (no existence leak).
- Create returns `201` + `Location`; a declined card is still a created resource (`status: FAILED`).

The full endpoint table and error codes are in the [README](../README.md#3-api).

## 4. High-level design

```
 Browser ──► React SPA ──/api──► Spring Boot ──► PostgreSQL
                                     │  ▲
                                     │  └── OutboxWorker (scheduled, same deployable, separable)
                                     └──► PaymentProcessorClient (stub; real processor in production)
```

**Create payment**

```
Client          API                         DB                        Processor
  │ POST /payments (Idempotency-Key)          │                            │
  │──────────────►│ BEGIN                     │                            │
  │               │ insert idempotency key ──►│ (unique: merchant, key)    │
  │               │ insert payment PENDING ──►│ + event (→PENDING)         │
  │               │ authorize ─────────────────────────────────────────────►│
  │               │◄──────────────────────────────────── approved/declined │
  │               │ AUTHORIZED or FAILED ────►│ + event                    │
  │               │ store response on key ───►│                            │
  │               │ COMMIT                    │                            │
  │◄── 201 ───────│                           │                            │
```

A processor outage throws → everything rolls back (including the key) → 503 → the client retries with the same key.

**Capture and settle**

```
Client     API                              DB                      Worker            Processor
  │ POST /capture                            │                        │                  │
  │──────────►│ BEGIN; AUTHORIZED→CAPTURED ─►│ + event + outbox job   │                  │
  │           │ COMMIT                       │   (correlationId,      │                  │
  │◄── 200 ───│                              │    traceparent)        │                  │
  │           │                              │◄── claim (SKIP LOCKED) │                  │
  │           │                              │                        │── settle ───────►│
  │           │                              │                        │◄── ok / error ───│
  │           │                              │◄── SETTLED + event, job DONE              │
  │           │                              │    or attempts++, next_attempt_at (backoff) / DEAD
```

## 5. Deep dives

### 5.1 Idempotency: why one transaction

The key insert, the business operation and the stored response share one database transaction.

- **Crash anywhere** → full rollback, no orphaned "in progress" key, and a retry simply runs again.
- **Concurrent duplicates** → PostgreSQL makes the second `INSERT` of the same `(merchant_id, key)` wait on the
  first transaction's uncommitted row; when the first commits, the second fails with a unique violation, and the
  service then reads and replays the committed response. Exactly one operation executes. Verified by an
  integration test firing 8 identical requests concurrently.
- **Misuse** → the request fingerprint (SHA-256 of operation + body) must match; the same key with a different body
  is a client bug → 422.
- **Defence in depth** → even without the key, the state machine plus `@Version` make a second capture fail with 409.

### 5.2 Consistency and concurrency

- `@Version` on `Payment`: two concurrent captures with different keys → one commits, the other gets an optimistic
  lock failure (or sees `CAPTURED` and fails the transition) → `409`. Verified by test.
- Status CHECK constraints in the schema back the Java state machine.
- The transactional outbox removes the dual-write problem (DB plus message broker) entirely.

### 5.3 Async reliability

- At-least-once processing with idempotent handlers: the handler checks the current payment state, so a re-run
  after a crash is harmless.
- `SKIP LOCKED` claiming, then each job in its own transaction; one failing job never blocks the batch.
- Backoff `min(initial · 2^(n-1), max)` with 50–100% jitter; after `maxAttempts` → `DEAD`, alerting via metric and
  health. Non-retryable errors dead-letter immediately.
- Stuck-job recovery for jobs left `PROCESSING` by a crashed worker.
- Lag (`outbox_lag_seconds`) and depth (`outbox_queue_depth`) answer "are workers keeping up?"; scale workers when
  lag grows.

### 5.4 Observability seams

| Seam | Implementation |
|---|---|
| Correlation ID | `CorrelationIdFilter` → MDC → response header → audit rows → outbox job → worker MDC |
| Trace context | Micrometer Tracing (W3C) → `traceparent` stored on the job → worker continues the trace (`TraceContextSupport`) |
| Business events | `PaymentService.record` is the single place that persists audit, increments metrics and logs each transition |
| Error visibility | `GlobalExceptionHandler` logs expected errors at INFO with their code, unexpected ones at ERROR with stack trace |
| Health | Liveness (process), readiness (+ database), outbox indicator (backlog, DEGRADED on lag) |

### 5.5 Security and PCI mindset

- No PAN or CVV ever enters the system: a token is required, and inputs that look like card numbers are rejected
  before any processing or logging. Only brand + last4 are stored.
- Passwords are bcrypt-hashed; login responses do not reveal whether an account exists; timing is equalised.
- Short-lived signed tokens; merchant identity comes only from the verified token.
- Errors never echo request values; DTOs with secrets mask `toString()`.

### 5.6 Scaling path

1. More API replicas (stateless) behind a load balancer; tune the Hikari pool against PostgreSQL connection limits.
2. Separate worker deployment (`app.outbox.worker.enabled=false` on API pods); more workers as lag grows.
3. Read replica for listing/search; cursor pagination.
4. Partition `payment_events` and `outbox_jobs` by month; archive completed jobs.
5. Beyond PostgreSQL polling throughput: CDC from the outbox table to Kafka, consumers per workflow.
