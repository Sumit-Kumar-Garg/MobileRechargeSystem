# Constitution

Principles that every spec, design and change must keep. A change that breaks one needs an explicit amendment
here first, reviewed by the owners of the money path.

## I. Money safety comes first
1. A customer is never charged without either a completed recharge or a refund.
2. A payment is never submitted twice for one order.
3. Nothing is concluded from silence: an unknown outcome (timeout, 5xx, open circuit) is never treated as a
   failure. Before declining an unknown payment or refunding an undelivered recharge, the other side is
   cancelled first.
4. Prices are taken from the server-side catalog and snapshotted on the order; the client never sends a price.

## II. State is explicit and audited
1. Every order status change goes through one state machine and one component (`OrderTransitions`).
2. Every change is recorded in an append-only audit trail.
3. Domain events exist if and only if the business change committed (transactional outbox).

## III. The purchase path stays independent
1. The money path depends only on the orders, payments and outbox tables.
2. Notifications, analytics and recommendations are fed asynchronously and can fail without failing a recharge.
3. No database transaction is held open during a call to an external system.
4. Every external call has a bulkhead, a hard timeout and a circuit breaker per provider.

## IV. Privacy by default
1. Phone numbers, emails and push tokens are encrypted at rest; lookups use keyed hashes.
2. PII never appears in Kafka events, logs, metrics or analytics; only masked or pseudonymous values do.
3. Personalization and analytics are opt-in; absence of consent means "no".
4. A user can only see or act on their own numbers and orders, and "not yours" is indistinguishable from "does
   not exist".

## V. Idempotent everywhere
1. Every write that a client or a broker may repeat (order creation, payment, number linking, device
   registration, event delivery, provider calls) is safe to repeat.

## VI. Modules have one owner
1. Each table has one owning package; other packages use its public API, never its repository.
2. Provider adapters depend only on `spi` contracts and are switched on by configuration.
3. These rules are enforced by `ArchitectureTest`; the build fails when they are broken.

## VII. Operable at any scale
1. Any number of identical instances may run; background jobs run on one instance at a time (database lease).
2. Every limit and timing is configurable with a safe default; invalid configuration stops startup.
3. Every failure mode has a metric or alert; the stuck-orders alert is the most important one.

## VIII. Specified, then tested, then built
1. Behaviour is specified in `specs/` before it is implemented.
2. Every requirement has at least one automated test named in its spec.
