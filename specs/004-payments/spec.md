# 004 — Payments and provider routing

**Status:** Implemented · **Owner:** payment · **Constitution:** I.1–3, III.3–4, V.1, VI.2

## Summary
The customer pays an order with a payment token from the app. The server chooses a payment provider (PSP),
charges exactly once, and treats an unknown outcome as unknown: the order waits for reconciliation instead of
being declined or charged again.

## User stories
- **US-1** As a customer, I want to pay for my order and immediately see whether it worked.
- **US-2** As a customer, I want to retry with another card after a decline without being charged twice.
- **US-3** As the operator, I want several PSPs with a priority order and automatic failover, so that one PSP
  outage does not stop sales.

## Functional requirements
| Id | Requirement |
|---|---|
| FR-004-1 | When a user calls `POST /v1/orders/{id}/pay` with `paymentToken` (≤ 64 chars) and optional `method` (`[a-z_]{2,30}`, default `card`), the system shall attempt payment and return the order as it stands afterwards. |
| FR-004-2 | If the order is not the caller's, then the system shall respond 404 `RESOURCE_NOT_FOUND`. |
| FR-004-3 | If the order is PAYMENT_PENDING, PAID, PROVISIONING, COMPLETED or in a refund state, then the system shall return it unchanged and shall not charge. |
| FR-004-4 | If the order has expired, then the system shall respond 409 `ORDER_STATE_INVALID` (and move a payable order to EXPIRED). |
| FR-004-5 | If the user is locked out after repeated declines (spec 011), then the system shall respond 429 `RATE_LIMITED` before any charge. |
| FR-004-6 | If the order already has the maximum attempts (default 3), then the system shall respond 429 `PAYMENT_LIMIT_EXCEEDED`. |
| FR-004-7 | The system shall choose the provider before writing anything: the providers that support the order's currency and the method, in the configured priority order (unlisted last), skipping providers whose circuit is open; if all are open, the preferred one. If none supports it, the system shall respond 422 `PAYMENT_METHOD_UNAVAILABLE` with nothing written. |
| FR-004-8 | The system shall commit PAYMENT_PENDING and a payment attempt (number = previous attempts + 1, server-side amount, chosen provider) under the order's row lock; of parallel requests exactly one shall proceed to charge. |
| FR-004-9 | The system shall call the PSP with no transaction open, using the payment id as the idempotent merchant reference. |
| FR-004-10 | When the PSP approves, the system shall mark the payment SUCCEEDED, move the order to PAID and, after commit, start provisioning (spec 005). |
| FR-004-11 | When the PSP declines, the system shall mark the payment FAILED, store the failure code on the order, move it to PAYMENT_FAILED and count the decline for the lockout. |
| FR-004-12 | If the outcome is unknown (timeout, bulkhead full, circuit open, provider not configured), then the system shall leave the order PAYMENT_PENDING for reconciliation (spec 006). |
| FR-004-13 | Applying an outcome shall be idempotent: lock order then payment, and do nothing if the payment is no longer PENDING. |
| FR-004-14 | Every follow-up (lookup, cancel, refund) shall go to the provider that handled the original attempt. |
| FR-004-15 | `GET /v1/payment-methods?currency=XXX` shall list the methods any provider supports for the currency, without naming providers. |

## Non-functional requirements
| Id | Requirement |
|---|---|
| NFR-004-1 | Each PSP shall have its own bulkhead (default 50 concurrent, no queueing), hard timeout (default 10 s) and circuit breaker (50% failures over the last 20 calls, open 20 s). |
| NFR-004-2 | Startup shall fail if no payment provider is configured. |
| NFR-004-3 | A PSP adapter shall implement `payment.spi.PspClient` and pass `PaymentProviderContract`: idempotent charge/refund/cancel, unknown outcome thrown as `PspUnavailableException`, a cancelled reference can never be charged later. |

## Acceptance scenarios
### AS-1 Successful payment
- **Given** a CREATED order and `tok_success`
- **When** the user pays
- **Then** the order is PAID, then PROVISIONING, then COMPLETED; one payment row; `OrderPaid` and `OrderCompleted` events
- **Test:** `RechargeFlowIT#happyPath_endsCompleted_withEventsJourneyAndNotifications`, `PaymentServiceTest#successfulPaymentMovesOrderToPaidAndTriggersProvisioning`

### AS-2 Decline, then retry with another card
- **Given** a CREATED order
- **When** the user pays with `tok_decline`, then `tok_success`
- **Then** PAYMENT_FAILED with a failure code, then PAID; the third failed attempt on an order is blocked
- **Test:** `RechargeFlowIT#declinedCard_canBeRetriedWithAnotherCard`, `#thirdFailedAttemptOnOneOrderIsBlocked`

### AS-3 Unknown outcome is not a decline
- **Given** a PSP that approves but whose answer is lost (`tok_timeout`)
- **When** the user pays
- **Then** the order stays PAYMENT_PENDING, a second pay call does not charge, and reconciliation later moves it to PAID
- **Test:** `RechargeFlowIT#pspTimeout_staysPending_thenReconciliationCompletesIt`, `PaymentServiceTest#pendingOrderIsNeverChargedTwice`

### AS-4 Parallel pay requests
- **Given** a CREATED order
- **When** two pay requests arrive at the same time
- **Then** exactly one charge reaches the PSP
- **Test:** `RechargeFlowIT#twoPayRequestsAtTheSameTimeProduceOneCharge`, `PaymentServiceTest#ofTwoParallelPayRequestsTheOneThatLosesTheOrderLockDoesNotCharge`

### AS-5 Routing and failover
- **Given** priority `mockpay,altpay`
- **When** paying by card in EUR, by wallet, and by card while mockpay's circuit is open
- **Then** mockpay, altpay, and altpay respectively; an unsupported method is 422 with nothing pending
- **Test:** `PaymentRouterTest`, `ProvidersIT#cardGoesToTheFirstProviderInThePriorityList_walletToTheOnlyProviderThatOffersIt`, `#anUnsupportedMethodIsRejectedAndNothingIsLeftPending`

## Edge cases and errors
| Situation | Result |
|---|---|
| Late decline from reconciliation after a success was applied | Ignored (FR-004-13) |
| Provider removed from deployment while payments reference it | Unknown outcome, never a decline |
| Demo tokens | `tok_success` approve, `tok_decline` decline, `tok_timeout` approve-but-lost; others declined as invalid |

## Out of scope
- Card data handling (tokens come from the PSP SDK in the app), 3-D Secure flows, partial refunds.

## Traceability
| Requirement | Code | Tests |
|---|---|---|
| FR-004-1..13 | `PaymentService`, `OrderController#pay`, `Payment`, `PaymentRepository` | `PaymentServiceTest`, `RechargeFlowIT` |
| FR-004-7, 15 | `PaymentRouter`, `PaymentMethodController` | `PaymentRouterTest`, `ProvidersIT` |
| FR-004-14, NFR-004-1..2 | `PspGateway`, `OutboundGuard`, `CircuitBreakers` | `PspGatewayTest`, `OutboundGuardTest` |
| NFR-004-3 | `payment.spi.*`, `MockPspClient`, `AltPayClient` | `PaymentProviderContract`, `ProviderContractTests`, `MockPspClientTest` |
