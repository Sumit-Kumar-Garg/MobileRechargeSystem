# 006 — Reconciliation, order expiry and data retention

**Status:** Implemented · **Owner:** reliability · **Constitution:** I.1–3, IV.2, VII.1

## Summary
Background jobs close the gaps that timeouts and crashes leave: payments whose outcome was never learned are
resolved with their provider, unpaid orders expire, and old analytics and outbox rows are deleted.

## User stories
- **US-1** As a customer whose payment timed out, I want my order to resolve on its own without paying twice.
- **US-2** As the operator, I want abandoned orders closed and old personal analytics data removed on schedule.

## Functional requirements
| Id | Requirement |
|---|---|
| FR-006-1 | A reconciliation job shall run every 60 s by default, on one instance at a time (lease 5 min). |
| FR-006-2 | For each PENDING payment older than the minimum age (default 60 s; at most 50 per run), the system shall look up the merchant reference at the provider that handled it. |
| FR-006-3 | When the provider knows the reference, the system shall apply its result exactly as the pay flow would (spec 004, FR-004-10..13). |
| FR-006-4 | While the provider has no record and the payment is younger than the grace period (default 10 min), the system shall leave it pending (the charge may still be in flight). |
| FR-006-5 | After the grace period, the system shall cancel (void) the reference at the provider and apply the returned final state: approved if the charge landed just before the void, otherwise declined. |
| FR-006-6 | If the provider is unavailable, then the system shall postpone that payment to the next run; one failing payment shall not stop the others. |
| FR-006-7 | The job shall expire CREATED and PAYMENT_FAILED orders whose expiry has passed (at most 100 per run), re-checking the status under the row lock. |
| FR-006-8 | PAYMENT_PENDING orders shall never expire. |
| FR-006-9 | A retention job shall run daily (default cron `0 30 3 * * *`, i.e. 03:30 in the server's time zone), on one instance, and delete journey events and telemetry rollups older than 13 months and published outbox rows older than 7 days. |

## Non-functional requirements
| Id | Requirement |
|---|---|
| NFR-006-1 | Shortening the grace period increases the double-charge risk; the default shall not be lowered without PSP evidence. |

## Acceptance scenarios
### AS-1 A timed-out payment completes
- **Given** an order PAYMENT_PENDING after `tok_timeout`
- **When** reconciliation runs
- **Then** the approval is found and the order moves to PAID and on to COMPLETED
- **Test:** `RechargeFlowIT#pspTimeout_staysPending_thenReconciliationCompletesIt`, `ReconciliationJobTest#appliesWhatThePspSaysHappened`

### AS-2 Unknown to the provider: wait, then void
- **Given** a pending payment the PSP has no record of
- **When** reconciliation runs before and after the grace period
- **Then** before: left pending; after: voided at the PSP, then declined, so the customer may retry safely; if the charge landed just before the void, the order is PAID instead
- **Test:** `ReconciliationJobTest#aChargeThePspDoesNotKnowYetIsLeftPendingBecauseItMayStillBeInFlight`, `#afterTheGracePeriodTheReferenceIsVoidedAtThePspBeforeTheCustomerMayRetry`, `#ifTheChargeLandedJustBeforeTheVoidTheOrderIsPaidNotDeclined`

### AS-3 Expiry
- **Given** an unpaid order past its expiry, and one that moved on meanwhile
- **When** the job runs
- **Then** the first is EXPIRED, the second untouched
- **Test:** `ReconciliationJobTest#expiresUnpaidOrdersPastTheirDeadline`, `#doesNotExpireAnOrderThatMovedOnInTheMeantime`

### AS-4 One instance only
- **Given** another instance holds the lease
- **When** the scheduled run fires
- **Then** nothing is done
- **Test:** `ReconciliationJobTest#theScheduledRunDoesNothingWhenAnotherInstanceHoldsTheLease`

## Edge cases and errors
| Situation | Result |
|---|---|
| PSP down during reconciliation | Postponed, warning logged |
| One payment throws | Logged; the rest of the batch continues |

## Out of scope
- Manual reconciliation tooling and finance reports.

## Traceability
| Requirement | Code | Tests |
|---|---|---|
| FR-006-1..8 | `ReconciliationJob`, `ReconciliationProperties`, `PaymentService#applyOutcome` | `ReconciliationJobTest`, `RechargeFlowIT` |
| FR-006-9 | `DataRetentionJob` | **Gap:** no automated test yet (constitution VIII.2); add an integration test |
