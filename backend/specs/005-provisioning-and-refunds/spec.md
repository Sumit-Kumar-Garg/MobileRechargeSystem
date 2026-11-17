# 005 — Provisioning, retries and refunds

**Status:** Implemented · **Owner:** provisioning, payment · **Constitution:** I.1, I.3, III.3–4, V.1

## Summary
After payment, the plan is activated on the number by the fulfilment system (OCS) of the plan's provider.
Unknown outcomes are retried with backoff; a definitive rejection or exhausted retries lead to a refund, but only
after the activation reference has been cancelled, so the customer never gets both the plan and the money back,
and never neither.

## User stories
- **US-1** As a customer who paid, I want my plan active within seconds.
- **US-2** As a customer whose recharge could not be delivered, I want my money back automatically.
- **US-3** As the operator, I want a failed activation never to be given away on top of a refund.

## Functional requirements
| Id | Requirement |
|---|---|
| FR-005-1 | When a payment commits, the system shall start one activation attempt immediately, after the commit, on a bounded thread pool (default 2 to 8 threads, queue 200). |
| FR-005-2 | A worker shall, every 15 s by default and on one instance at a time, pick up: PAID orders older than 3 s, PROVISIONING_FAILED orders whose backoff has passed, PROVISIONING orders stale for more than 2 min, and REFUND_INITIATED orders older than 10 s. |
| FR-005-3 | An attempt shall lock the order, increment its attempt counter, move it to PROVISIONING, and commit before calling the OCS. |
| FR-005-4 | The system shall call the OCS of the plan's provider with no transaction open, using the order id as the idempotent reference and the decrypted number. |
| FR-005-5 | When the OCS succeeds, the system shall move the order to COMPLETED (emitting `OrderCompleted`). |
| FR-005-6 | If the OCS is unavailable (timeout, 5xx, open circuit), then the system shall move the order to PROVISIONING_FAILED with code `ocs_unavailable` for a retry. |
| FR-005-7 | If the OCS rejects the activation, then the system shall record the failure code and move the order to PROVISIONING_FAILED, then REFUND_INITIATED, without further attempts. |
| FR-005-8 | Retries shall wait the base backoff (default 5 s) × 2^attempts (capped at 64×), counted from the last update. |
| FR-005-9 | When the attempts reach the maximum (default 3), the system shall move the order to REFUND_INITIATED. |
| FR-005-10 | A PROVISIONING order not updated for longer than the stale limit (default 2 min) shall be moved to PROVISIONING_FAILED so it can be retried. |
| FR-005-11 | Compensation shall first cancel the reference at the OCS; if the cancel cannot be confirmed, the system shall still refund and count `cancel_unconfirmed` for manual review. |
| FR-005-12 | A refund shall be created once per order, for the full amount of the latest successful payment, and sent to the provider that took the money, with the refund id as idempotent reference. |
| FR-005-13 | When the provider confirms the refund, the system shall mark it SUCCEEDED and move the order to REFUNDED (emitting `OrderRefunded`). If the provider is unavailable or declines, then the order shall stay REFUND_INITIATED for the worker to retry. |
| FR-005-14 | If no fulfilment system serves the plan's provider, then activation shall be a definitive rejection (`fulfilment_provider_unknown`). |

## Non-functional requirements
| Id | Requirement |
|---|---|
| NFR-005-1 | Each OCS shall have its own bulkhead (default 30), timeout (default 10 s) and circuit breaker. |
| NFR-005-2 | Startup shall fail if no fulfilment system is configured. |
| NFR-005-3 | An OCS adapter shall implement `provisioning.spi.OcsClient` and pass `FulfilmentProviderContract`: idempotent per reference; a cancelled reference is never activated; cancel reverses an earlier activation. |

## Acceptance scenarios
### AS-1 Transient outage recovers
- **Given** a number ending `0001` (OCS fails twice, then works)
- **When** the order is paid
- **Then** the order ends COMPLETED after retries
- **Test:** `RechargeFlowIT#transientOcsFailure_recoversOnRetry`, `ProvisioningServiceTest#retrySucceedsAfterTransientFailure`

### AS-2 Permanent outage refunds
- **Given** a number ending `0000` (OCS always down)
- **When** the order is paid
- **Then** 3 attempts, then REFUND_INITIATED, then REFUNDED; `OrderRefunded` is emitted, `OrderCompleted` is not
- **Test:** `RechargeFlowIT#ocsOutage_exhaustsRetries_thenRefundsTheCustomer`, `ProvisioningServiceTest#exhaustedAttemptsLeadStraightToARefund`

### AS-3 Rejection skips pointless retries
- **Given** a number ending `9999` (OCS rejects it)
- **When** the order is paid
- **Then** exactly 1 attempt, failure code `number_not_eligible`, order REFUNDED
- **Test:** `RechargeFlowIT#numberRejectedByTheBillingSystem_isRefundedWithoutPointlessRetries`, `ProvisioningServiceTest#aDefinitiveRejectionGoesStraightToARefundWithoutRetries`

### AS-4 Cancel before refund
- **Given** an order to compensate
- **When** compensation runs
- **Then** the OCS cancel is called before the refund; if the cancel fails the customer is still refunded
- **Test:** `ProvisioningServiceTest#theReferenceIsCancelledAtTheBillingSystemBeforeRefunding`, `#theCustomerIsRefundedEvenWhenTheCancellationCannotBeConfirmed`

### AS-5 Refund is safe to repeat
- **Given** a refund where the PSP was down the first time
- **When** the worker retries
- **Then** the same refund record and reference are reused; a declined refund does not mark the order refunded
- **Test:** `RefundServiceTest#retryReusesTheSameRefundRecordSoThePspCallStaysIdempotent`, `#pspOutageKeepsTheRefundPendingForTheWorker`, `#declinedRefundIsNotMarkedAsRefunded`

## Edge cases and errors
| Situation | Result |
|---|---|
| App crashes mid-attempt | Order stuck in PROVISIONING becomes stale, is marked failed and retried |
| Immediate-path task rejected (pool full) | The worker picks the order up within 15 s |
| PSP declines the refund | Order stays REFUND_INITIATED; logged as error; stuck-orders alert fires |

## Out of scope
- Partial delivery or partial refunds.

## Traceability
| Requirement | Code | Tests |
|---|---|---|
| FR-005-1 | `PaidEventListener`, `AppConfig#provisioningExecutor` | `RechargeFlowIT#happyPath_endsCompleted_withEventsJourneyAndNotifications` |
| FR-005-2, 8 | `ProvisioningWorker` | `ProvisioningWorkerTest` |
| FR-005-3..11, 14 | `ProvisioningService`, `OcsGateway` | `ProvisioningServiceTest`, `OcsGatewayTest` |
| FR-005-12..13 | `RefundService`, `Refund` | `RefundServiceTest` |
| NFR-005-1..3 | `OcsGateway`, `provisioning.spi.*`, `MockOcsClient`, `TravelSimFulfilment` | `OcsGatewayTest`, `FulfilmentProviderContract`, `MockOcsClientTest` |
