# Stubbed payment processor (WireMock)

The application talks to the card processor through one interface, `PaymentProcessorClient`, with four
operations: `authorize`, `capture`, `refund`, `settle`. Nothing else in the system knows the processor exists.

There are two implementations:

| `app.processor.mode` | Implementation | Network? |
|---|---|---|
| `stub` (default) | `StubPaymentProcessor` | no - plain Java method calls |
| `http` | `HttpPaymentProcessorClient` | yes - real HTTP to `app.processor.base-url` |

In `http` mode the calls land on WireMock, which matches each request against the JSON files in
`stubs/mappings/` and replies with whatever the matching file says. No code changes, no rebuild: edit a
mapping, and the next request behaves differently.

## Running it

The stub runs **inside the application** on port 8089. No Docker, no second service to start.

```
.\run-local.cmd                                             # Windows
./run-local.sh                                              # macOS / Linux
./mvnw spring-boot:run -Dspring-boot.run.profiles=local      # the same thing, written out
```

The `local` profile turns on `app.processor.stub-server.enabled`, which starts a real HTTP server on 8089 and
points `app.processor.base-url` at it. It is a genuine network call between the two, not an in-process
shortcut - which is the whole point, because timeouts and dropped connections only exist over a socket.

This needs a locally installed PostgreSQL. One-time setup:

```
psql -U postgres -f ..\scripts\create-local-db.sql
```

Without the `local` profile nothing changes: `StubPaymentProcessor` is still the default, no stub server
starts, and existing runs and tests behave exactly as before.

(`compose.yaml` also has a `wiremock` service, for running the stub as a container instead. It is an
alternative, not a requirement - use it only if you prefer that.)

## What the stub does

`authorize` is selected by the payment method token (and by amount, for the limit rule). The token also
decides the shape of the `processorReference` it hands back, which is how the later `capture` and `settle`
calls know how to behave.

| Token | Authorization | Later behaviour |
|---|---|---|
| `tok_visa`, `tok_success` | approved, VISA 4242 | normal |
| `tok_mastercard` | approved, MASTERCARD 4444 | normal |
| `tok_decline` | declined, `insufficient_funds` | - |
| `tok_processor_error` | connection reset mid-response | - |
| `tok_timeout` | 8s delay (read timeout is 3s) | - |
| `tok_processor_500` | HTTP 500 | - |
| `tok_capture_once` | approved | first capture is slow and times out; the retry gets `409 already_captured` |
| `tok_capture_expired` | approved | capture returns `409 authorization_expired` |
| `tok_capture_error` | approved | capture drops the connection |
| `tok_settle_flaky` | approved | settlement fails twice, then succeeds |
| `tok_settle_fail` | approved | settlement always fails, job dead-letters |
| anything else | declined, `invalid_payment_method` | - |

Plus one amount rule, which applies to any otherwise-approvable token:

**`amountMinor > 100000`** (over $1,000.00) is declined with `exceeds_limit`. The threshold sits well above
the amounts used in the existing tests so it cannot fire by accident.

## What each outcome does to a payment

| Processor answer | Client behaviour | Payment |
|---|---|---|
| 2xx, `approved: true` | returns approved | `AUTHORIZED`, API answers 201 |
| 2xx, `approved: false` | returns declined | `FAILED` with the reason, API still answers **201** |
| `409 already_captured` / `already_settled` / `already_refunded` | treated as **success** | proceeds normally |
| other 4xx | `ProcessorRejectedException` | API answers 422 `PROCESSOR_REJECTED` |
| 5xx, timeout, connection reset | `ProcessorUnavailableException` | transaction rolls back **including the idempotency key**; API answers 503 with `Retry-After` |

That last row is the one that matters: because the idempotency key rolls back with everything else, a client
retrying with the same key runs fresh rather than replaying a failure.

## Things worth trying

**A decline that is not an error.** Create a payment with `tok_decline`. You get `201 Created` with status
`FAILED` and reason `insufficient_funds`. A declined card is a business outcome, not a broken request.

**The limit rule.** Create a payment for `150000` with `tok_visa`: declined, `exceeds_limit`.

**A slow processor exhausting the connection pool.** Fire ten concurrent payments with `tok_timeout`. Each
holds a database connection for the full read timeout, and the pool is 10. Watch unrelated endpoints start
failing. Then raise `app.processor.read-timeout` to `30s` and try again to see how much worse it gets - this
is the failure an in-process stub cannot reproduce, because there is no socket to be slow.

**Double capture on retry.** Authorize with `tok_capture_once`, then capture. The first capture times out and
the whole transaction rolls back, idempotency key included, so the API answers 503. Retry the capture with the
*same* `Idempotency-Key`: the key is gone, the operation runs fresh, and the processor answers
`409 already_captured` - proof that a previous attempt did go through.

Today the client treats that `409` as success, which keeps the payment correct. The real fix is to send an
idempotency key *to* the processor, derived from something stable such as `<paymentId>:capture`, so the
processor recognises the retry itself. Our idempotency protection currently points inward only.

**Retry with backoff, and dead-lettering.** `tok_settle_flaky` settles on the third attempt; `tok_settle_fail`
never settles and the job ends up `DEAD`. Watch `attempts`, `next_attempt_at` and `last_error` in
`outbox_jobs`, and the counts on `/actuator/health`.

## Inspecting and resetting

```bash
curl http://localhost:8089/__admin/requests          # every call the app made, with bodies
curl http://localhost:8089/__admin/mappings          # the loaded mappings
curl -X POST http://localhost:8089/__admin/scenarios/reset   # rewind capture-once and settle-flaky
curl -X POST http://localhost:8089/__admin/mappings/reset    # reload the JSON files after editing
```

The two stateful scenarios (`capture-once`, `settle-flaky`) remember what has already happened, so reset them
between runs or they stay in their final state.

## Adding a case

Add an object to the `mappings` array in the relevant file. Matching is by priority, **lowest number wins**,
so a new specific rule needs a lower number than the catch-all it should beat:

| Priority | Used for |
|---|---|
| 1 | transport failures (reset, delay, 500) |
| 2 | explicit token behaviour |
| 3 | the amount limit rule |
| 4 | plain approvals |
| 10 | catch-alls |
