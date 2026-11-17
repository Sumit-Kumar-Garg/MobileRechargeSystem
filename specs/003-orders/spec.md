# 003 — Orders: idempotent creation and state machine

**Status:** Implemented · **Owner:** order · **Constitution:** I.4, II.1–3, IV.4, V.1

## Summary
An order is the customer's intent to buy one plan for one of their numbers. It is created idempotently with a
server-side price snapshot and then moves through a fixed state machine until it is completed, refunded or
expired.

## User stories
- **US-1** As a customer, I want to order a plan for my number and retry safely on a bad connection, so that a
  retry never creates a second order.
- **US-2** As a customer, I want to see my order's status and my recent orders.
- **US-3** As support, I want every status change recorded with a reason, so that I can explain any order.

## Functional requirements
| Id | Requirement |
|---|---|
| FR-003-1 | When an authenticated user calls `POST /v1/orders` with `numberId`, `planId` and an `Idempotency-Key` header, the system shall create an order in `CREATED` and respond 201. |
| FR-003-2 | The `Idempotency-Key` shall be 16 to 64 characters of `[A-Za-z0-9_-]`; otherwise, or if `numberId` or `planId` is missing, the system shall respond 400 `REQUEST_INVALID` with an `errors` list. |
| FR-003-3 | When the same user repeats the same key with the same number and plan, the system shall return the original order, also for concurrent duplicates. |
| FR-003-4 | If the same user repeats a key with a different number or plan, then the system shall respond 422 `IDEMPOTENCY_KEY_REUSED`. |
| FR-003-5 | The system shall copy the plan's provider, code, name, price, currency and validity onto the order from the live catalog; later catalog changes never alter an existing order. |
| FR-003-6 | If the plan is not active, is for another market than the number, or its provider has no fulfilment system, then the system shall respond 422 `PLAN_UNAVAILABLE`. |
| FR-003-7 | If the number does not belong to the caller, then the system shall respond 404 `RESOURCE_NOT_FOUND`. |
| FR-003-8 | An order shall expire a configured time after creation (default 15 min) if not paid. |
| FR-003-9 | `GET /v1/orders/{id}` shall return one of the caller's orders (404 for others'); `GET /v1/orders` shall return the caller's 20 newest orders. |
| FR-003-10 | The order view shall include status, plan snapshot, masked number, failure code, `expiresAt`, `createdAt`, `completedAt` and `validUntil` (= completion + validity days, null until completed). |
| FR-003-11 | The system shall allow only these transitions: CREATED → PAYMENT_PENDING, EXPIRED · PAYMENT_PENDING → PAID, PAYMENT_FAILED · PAYMENT_FAILED → PAYMENT_PENDING, EXPIRED · PAID → PROVISIONING · PROVISIONING → COMPLETED, PROVISIONING_FAILED · PROVISIONING_FAILED → PROVISIONING, REFUND_INITIATED · REFUND_INITIATED → REFUNDED. Any other transition shall be rejected with 409 `ORDER_STATE_INVALID` and leave the order unchanged. |
| FR-003-12 | COMPLETED, REFUNDED and EXPIRED shall be terminal. |
| FR-003-13 | On every transition, in the same transaction, the system shall write an audit row (from, to, reason ≤ 200 chars), a domain event to the outbox for CREATED, PAID, COMPLETED, PAYMENT_FAILED and REFUNDED, a server journey event for the customer-visible steps, and a metric. |
| FR-003-14 | Reaching COMPLETED shall set `completedAt` and clear any earlier failure code. |

## Non-functional requirements
| Id | Requirement |
|---|---|
| NFR-003-1 | Concurrent updates of one order shall be serialized (row lock for writers, optimistic version check). |
| NFR-003-2 | Domain events shall carry only a masked number: no contact data (constitution IV.2). |

## Acceptance scenarios
### AS-1 Create with a server-side price
- **Given** a linked DE number and the DE plan M-28 at 15.00 EUR
- **When** the user creates an order with a fresh key
- **Then** 201, status CREATED, amount 1500 EUR, expiry 15 min later
- **Test:** `OrderServiceTest#createsOrderWithServerSidePrice`, `RechargeOrderTest#snapshotsPlanAndSetsExpiry`

### AS-2 Replay and key reuse
- **Given** an order created with key K
- **When** the same request is sent again with K; then a different plan with K
- **Then** the original order is returned; then 422 `IDEMPOTENCY_KEY_REUSED`
- **Test:** `OrderServiceTest#replayWithSameKeyAndSameRequestReturnsOriginal`, `#sameKeyWithDifferentRequestIsRejected`, `#concurrentDuplicateReturnsTheWinner`

### AS-3 Plans that cannot be sold
- **Given** a PL plan and a DE number, or a plan whose provider has no fulfilment system
- **When** an order is created
- **Then** 422 `PLAN_UNAVAILABLE`, nothing stored
- **Test:** `OrderServiceTest#planFromAnotherMarketIsRejected`, `#aPlanWhoseProviderHasNoFulfilmentSystemIsNotSold`, `ProvidersIT#aPlanFromAProviderWithoutFulfilmentCannotBeOrdered`

### AS-4 State machine
- **Given** any order
- **When** a transition outside FR-003-11 is attempted (e.g. CREATED → PAID)
- **Then** it is rejected and the state is unchanged; terminal states have no exit
- **Test:** `OrderStatusTest#dangerousShortcutsAreRejected`, `#terminalStatesHaveNoExit`, `RechargeOrderTest#illegalTransitionIsRejectedAndStateIsUnchanged`

### AS-5 Validation
- **Given** an authenticated user
- **When** the key is `short`, or the body is `{}`
- **Then** 400; for `{}` the `errors` list has two entries
- **Test:** `RechargeFlowIT#orderValidation_rejectsBadIdempotencyKeyAndMissingFields`

## Edge cases and errors
| Situation | Result |
|---|---|
| Two identical requests at the same moment | Unique (user, key) constraint; one order, both get it |
| Plan price changes after ordering | Order keeps its snapshot price |
| Another user's order id | 404, never 403 |

## Out of scope
- Cancelling an order by the customer.

## Traceability
| Requirement | Code | Tests |
|---|---|---|
| FR-003-1..10 | `OrderController`, `OrderService`, `OrderCreator`, `OrderView` | `OrderServiceTest`, `RechargeFlowIT` |
| FR-003-11..14 | `OrderStatus`, `RechargeOrder`, `OrderTransitions` | `OrderStatusTest`, `RechargeOrderTest` |
| NFR-003-1 | `OrderRepository#findByIdForUpdate`, `@Version` | `PaymentServiceTest#theOutcomeIsAppliedUnderTheOrderLockAndThePaymentLockInThatOrder` |
