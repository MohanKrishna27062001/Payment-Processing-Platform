# Observability and operations

What is instrumented, where it lives, and what production would add. README section 7 is the operator-facing
summary; this is the requirement-by-requirement account.

The guiding idea: **a payment's journey must be reconstructable after the fact, from the outside.** Every
mechanism below exists to answer one of three questions — what happened to this payment, is the system healthy,
and what do I do when it is not.

---

## 1. Logging

| Requirement | Implementation |
|---|---|
| Structured, queryable | `logging.structured.format.console: ecs` — Elastic Common Schema JSON on stdout, via Log4j2 |
| Correlation ID across API **and** async | `CorrelationIdFilter` → MDC → `outbox_jobs.correlation_id` → restored by `OutboxWorker` |
| Deliberate levels | see table below |
| No sensitive data | PAN rejection, masked `toString()`, errors never echo request values |

### The correlation story

`CorrelationIdFilter` runs at `HIGHEST_PRECEDENCE`. It takes `X-Request-Id` when the caller sends a well-formed
one — merchants can pass their own id and find their request in our logs — and generates a UUID otherwise. The
value is put in the MDC, echoed on the response, and **copied onto any outbox job created during that request**.

That last step is what makes the trail survive the thread boundary. When the settlement worker picks the job up
seconds later on a different thread, it restores the originating `correlationId` into the MDC before doing
anything. One search returns the HTTP request and the background work it caused:

```
correlationId:"a7f81f0d-…"
  → POST /api/v1/payments/…/capture  (http-nio-8080-exec-9)
  → Payment … AUTHORIZED -> CAPTURED (http-nio-8080-exec-9)
  → Enqueued settlement job           (http-nio-8080-exec-9)
  → Payment … CAPTURED -> SETTLED     (scheduling-3, jobId=4)
```

The MDC carries `correlationId`, `traceId`, `spanId`, and where known `merchantId` (added by `MerchantMdcFilter`
from the verified JWT), `paymentId` and `jobId`. The filter clears them in a `finally` block, which matters on a
pooled thread — a leaked MDC value attributes one merchant's log line to another.

### Levels

| Level | Used for |
|---|---|
| `ERROR` | Dead-lettered jobs, unhandled exceptions. Every ERROR is intended to be alertable |
| `WARN` | Retries, failed logins, optimistic-lock conflicts, processor unavailable, operator replays |
| `INFO` | State transitions, logins, access log, idempotent replays, purges |
| `DEBUG` | Processor calls |

The test profile sets `root: WARN` so test output stays readable.

### Keeping card data out

- Tokens matching a PAN shape are rejected before the processor is called, and **the value is never echoed** —
  not in the error, not in a log line
- `LoginRequest`, `PaymentMethod`, `CreatePaymentCommand` and `AuthorizeRequest` override `toString()` to mask
  secrets, because records print every field by default and one careless `log.debug("{}", request)` would leak
- Only card brand and last four digits are ever stored
- Failed logins log *why* (`unknown_email` / `wrong_password`) but not the email
- `GlobalExceptionHandler` never reflects request values into a response

---

## 2. Metrics

Exposed at `/actuator/prometheus`, and pushed over OTLP to an OpenTelemetry Collector when
`OTLP_METRICS_ENABLED=true`. The same meters feed both — Micrometer is the facade, the registry is a deployment
choice rather than a code change.

| Signal | Metric |
|---|---|
| **R**ate, **E**rrors, **D**uration | `http_server_requests_seconds{uri,method,status,outcome}` with percentile histograms |
| Payments by lifecycle transition | `payments_transitions_total{from,to}` |
| Payments currently in each state | `payments_current{status}` |
| Logins | `auth_logins_total{result}` |
| Queue depth | `outbox_queue_depth{status=PENDING\|PROCESSING\|DEAD}` |
| Processing lag | `outbox_lag_seconds` |
| Retries and failures | `outbox_jobs_processed_total{type,result=success\|skipped\|retry\|dead}` |
| Async latency | `outbox_job_duration_seconds`, `outbox_job_end_to_end_seconds` |

One counter, `payments_transitions_total{from,to}`, yields every business rate: creations are `from=NONE`,
authorizations are `from=PENDING,to=AUTHORIZED`, declines are `to=FAILED`, and so on. Adding a lifecycle state
adds a label value rather than a metric.

Gauges are refreshed on a schedule (`app.metrics.refresh-interval`, 30s) rather than computed per scrape, so a
Prometheus scrape never issues a `COUNT(*)`. A scrape should never be able to hurt the system it measures.

`outbox_lag_seconds` is the single most useful number here: the age of the oldest job that is due and still
unprocessed. It rises when workers are down, wedged, or simply outnumbered, and it is the leading indicator for
settlement falling behind.

### Suggested alerts

| Alert | Condition | Why |
|---|---|---|
| Settlement stuck | `outbox_queue_depth{status="DEAD"} > 0` | Money captured that will never reach the merchant |
| Settlement lagging | `outbox_lag_seconds > 120` for 5m | Workers not keeping up |
| Error budget | 5xx ratio > 1% for 5m | |
| Latency | p99 `http_server_requests_seconds` > 500ms | |
| Authorization failures | `rate(payments_transitions_total{to="FAILED"})` spike | Usually the processor, not the cards |

---

## 3. Tracing

Micrometer Tracing with the Brave bridge and W3C propagation. Every request gets a span; `traceId` and `spanId`
appear in every log line.

The part worth attention is the async hop. When a job is enqueued, the current `traceparent` is captured and
stored on the row:

```java
OutboxJob.create(..., tracing.currentTraceparent(), now)
```

and the worker opens a **child span of that stored context** rather than starting a fresh trace:

```java
tracing.inSpan(job.getTraceparent(), "outbox " + job.getType(), () -> { … })
```

So the API request and the settlement that happened twelve seconds later on another thread share one `traceId`.
Without this they would be two unrelated traces and "why did this payment take so long to settle" would be
unanswerable.

`TraceContextSupport` degrades to `Tracer.NOOP` when no tracer bean is present, so tracing can be removed from
the dependency tree without breaking anything.

**Production:** add an OTLP or Zipkin exporter and point it at a collector (→ Tempo or Jaeger). No code changes —
the instrumentation is already there, only the shipping is not. Sampling is 100% locally and would be roughly 10%
in production, with errors sampled at 100%.

---

## 4. Health and lifecycle

| Endpoint | Used by | Fails when |
|---|---|---|
| `/actuator/health/liveness` | orchestrator restart decisions | the process is broken — no dependencies checked |
| `/actuator/health/readiness` | load balancer | the database is unreachable |
| `/actuator/health` | humans | full view, including `outbox` |

The distinction matters: a database outage should take instances *out of rotation*, not restart them. Restarting
a process because its database is down turns a dependency outage into a crash loop.

`OutboxHealthIndicator` reports `DEGRADED` at HTTP 200 once lag exceeds `app.outbox.lag-warning` (2 min), and is
deliberately **not** in the readiness group. A settlement backlog does not stop the API serving traffic, and
failing readiness would pull healthy instances out of the load balancer — making the backlog worse.

### Audit trail

`payment_events` is append-only, one row per transition, written in the **same transaction** as the change:

| Column | Answers |
|---|---|
| `from_status`, `to_status` | what changed |
| `actor_type`, `actor_id` | who — `MERCHANT:<id>` or `SYSTEM:settlement-worker` / `SYSTEM:payment-processor` |
| `reason` | why — `processor_approved`, `captured_by_merchant`, `settled` |
| `correlation_id` | which request or job |
| `created_at` | when |

Nothing updates or deletes these rows, and the only path that writes one is `Payment.transitionTo`, so a state
change without an audit row is not reachable. It is served by `GET /api/v1/payments/{id}` and shown as the
dashboard's status history.

---

## 5. Error handling and resilience

### API errors

RFC 7807 problem details, with two additions on every response: a stable `code` for clients to branch on, and the
`correlationId` so a user reporting a problem hands you the exact log search. Request values are never echoed
back, so an error cannot become a data leak.

### Idempotency

`Idempotency-Key` is required on create, capture and refund. The key row, the operation and the stored response
commit in **one transaction**, so:

- a retry with the same key replays the stored response (`Idempotent-Replayed: true`)
- the same key with a different request body is `422` — the fingerprint is `SHA-256(operation + body)`
- a failed attempt rolls the key back with everything else, so the retry runs fresh rather than replaying a failure
- two simultaneous requests with one key: the unique constraint on `(merchant_id, idempotency_key)` makes the
  second wait, then replay. The database arbitrates, so this holds across instances

### Timeouts

The processor client sets an explicit connect (1s) and read (3s) timeout. This is not incidental: the call happens
inside a database transaction holding one of ten pooled connections, so an unbounded read timeout means one slow
processor can exhaust the pool and take down endpoints that have nothing to do with payments.

### Async failure handling

| Mechanism | Setting |
|---|---|
| Retry with backoff | `2s · 2^(n-1)`, capped at 5 min, jittered 50–100% |
| Attempt limit | 5, then `DEAD` |
| Non-retryable failures | dead-lettered on the first attempt — a payment in an impossible state will not fix itself |
| Crashed-worker recovery | `PROCESSING` jobs idle for 5 min return to `PENDING` |
| Retention | `DONE` jobs purged after `app.outbox.retention` (7d). `DEAD` jobs are **never** purged |

Jitter is not decoration. When an outage fails fifty jobs in the same second, undithered backoff retries all fifty
in the same instant, repeatedly, exactly as the downstream tries to recover.

### Dead letters

`GET /actuator/outbox` returns queue depth, lag, and the most recent dead letters with their last error and
correlation ID. `POST /actuator/outbox/{jobId}` requeues one once the cause is fixed: back to `PENDING`, due now,
attempts reset. Replaying is safe because handlers are idempotent — a job whose payment already settled completes
as `SKIPPED` rather than settling twice.

This exists because a dead-lettered settlement is money a merchant was told had been captured. Alerting on it
without giving operators a way to act leaves the only remedy as hand-written UPDATE statements against production.

---

## What production would add

| Gap | Why it is not here | What it needs |
|---|---|---|
| Log shipping | No stack to ship to | stdout is already ECS JSON; a sidecar or agent to Elasticsearch/Loki, no code change |
| Trace export | Same | one exporter dependency + a collector endpoint |
| Metric storage | Same | the Prometheus endpoint exists; OTLP push is wired but disabled |
| Dashboards and alerts | Out of scope | the metrics and thresholds above are the inputs |
| `/actuator` isolation | Local runs would need two ports | set `MANAGEMENT_PORT`. **Until then `/actuator/outbox` is reachable by any authenticated merchant** — on the same port the API security chain applies, and this system has no operator role. A separate management port is the intended fix |
| Log retention and PII review | Out of scope | no card data is logged today; a periodic audit of new log statements would keep it that way |

The deliberate position: **instrumentation is code and belongs in the repository; collection is deployment and does
not.** Everything above is wired through the application's own seams, so adopting a stack means adding an exporter
and pointing it somewhere, not revisiting the code.
