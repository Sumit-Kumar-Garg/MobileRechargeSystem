# Architecture

## Shape
One deployable (modular monolith), one package per domain, talking to each other through public services and DTOs.
Kafka decouples everything that must not slow down or fail a recharge (notifications, analytics).

```
Android ──HTTPS──> API (filters: correlation id, size limit, Server-Timing, security, rate limit)
                     │
   numbers │ catalog │ orders │ payment │ provisioning │ recommendation │ consent │ telemetry │ notification
                     │
        PostgreSQL (state, outbox, audit, journey events, rollups, job leases)
        Redis (shared rate-limit counters: required with more than one instance)
                     │
        outbox publisher ──> Kafka: recharge.order.events.v1 ──> notification consumer ──> push / SMS / email
        client telemetry ──> Kafka: recharge.client.telemetry.v1 ──> telemetry consumer ──> journey_events, rollups
```

## The money path (why it is shaped this way)
1. `POST /v1/orders`: idempotent (unique `(user, Idempotency-Key)`), price snapshotted server-side, `OrderCreated` written to the outbox in the same transaction.
2. `POST /v1/orders/{id}/pay`: three short transactions around the PSP call (pending, PSP call, outcome). The database is never held open during a network call.
3. Payment success publishes an in-process event that fires after commit and provisions asynchronously. A worker is the safety net for anything missed.
4. Unknown PSP outcome (timeout, full bulkhead, open circuit) leaves the order `PAYMENT_PENDING`. Reconciliation asks the PSP what happened. If the PSP has no record it waits 10 minutes, then voids the reference before declining, so a late charge cannot follow a retry. Unpaid orders expire; pending ones never do.
5. Provisioning retries unknown outcomes with exponential backoff; a definitive rejection is not retried. The saga then cancels the reference at the billing system and refunds. `recharge.orders.stuck` alerts if anything sits in a money state too long.

## Invariants
- A customer is never charged without either a completed recharge or a refund.
- A payment is never submitted twice for one order (state check, unique attempt number, optimistic lock, idempotent PSP reference).
- Every order state change goes through `OrderTransitions` and the `OrderStatus` state machine.
- Domain events exist if and only if the business change committed (transactional outbox).
- A user can only touch numbers and orders they own; "not yours" is indistinguishable from "does not exist".
- PII never appears in Kafka, logs, metrics or analytics; phone numbers and emails are encrypted at rest (key id in the ciphertext, so keys can be rotated).
- Nothing is concluded from silence: before declining an unknown payment or refunding an undelivered recharge, the other side is cancelled first.
- The money path depends only on the orders, payments and outbox tables. Analytics and notifications are fed from the outbox.

## Failure behavior
| Failure | Result |
|---|---|
| PSP timeout | order stays PAYMENT_PENDING, reconciliation resolves it, customer is never double charged |
| PSP down | circuit opens, payments report "pending/unavailable", nothing is declined by mistake |
| OCS down | retries with backoff, then refund; circuit breaker protects threads |
| Kafka down | outbox keeps events; publisher resumes; recharges unaffected |
| Analytics storage down | journey events wait in the outbox; recharges unaffected |
| Redis down | rate limiting fails open |
| Several instances | one outbox publisher, reconciler, catalog sync, retention run and provisioning worker at a time (`job_leases`, judged by the database clock); rate limits shared in Redis; each instance's plans cache cleared within ~10 s of a catalog change |
| Instance dies | its leases expire (at most a few minutes) and another instance takes the jobs over; in-flight orders resume from the database |
| SMS/push provider down | Kafka retry topics (5s, 30s, 3min), then DLT with alert; recharges unaffected |
| App killed mid-payment | order resumes from `orderId`; server state is the source of truth |
| Duplicate request / event | idempotency keys and unique constraints make repeats harmless |
| Telemetry flood | size limits, rate limits, bounded dimensions, consent gate |
