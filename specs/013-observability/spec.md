# 013 — Observability and the error contract

**Status:** Implemented · **Owner:** platform · **Constitution:** VII.3, IV.2

## Summary
Operators can see every failure mode through metrics, traces and correlated logs; clients get one predictable
error format with a stable code and a correlation id they can quote to support.

## User stories
- **US-1** As on-call, I want to be paged when a customer paid but did not get their recharge.
- **US-2** As a support agent, I want to find every log line of a customer's failed request from one id.
- **US-3** As an app developer, I want every error in one format with a stable machine-readable code.

## Functional requirements
| Id | Requirement |
|---|---|
| FR-013-1 | Every error response shall be RFC 7807 `application/problem+json` with `type`, `title`, `status`, `detail`, a stable `code` and, when known, `correlationId` — including framework errors (missing parameter, 404 route, 405) and security errors. |
| FR-013-2 | Codes and statuses shall be: `REQUEST_INVALID` 400, `UNAUTHENTICATED` 401, `FORBIDDEN` 403, `RESOURCE_NOT_FOUND` 404, `ORDER_STATE_INVALID` 409, `PAYLOAD_TOO_LARGE` 413, `IDEMPOTENCY_KEY_REUSED` / `PLAN_UNAVAILABLE` / `PAYMENT_METHOD_UNAVAILABLE` / `NUMBER_LIMIT_EXCEEDED` 422, `RATE_LIMITED` / `PAYMENT_LIMIT_EXCEEDED` 429, `INTERNAL_ERROR` 500, `SERVICE_UNAVAILABLE` 503. Codes are never renamed. |
| FR-013-3 | Validation errors shall add `errors: [{field, message}]` without the rejected values, for body and parameter/header validation alike. |
| FR-013-4 | Unexpected errors shall be logged in full and answered with a generic 500 `INTERNAL_ERROR`. |
| FR-013-5 | Every response shall carry `X-Correlation-Id`: the caller's value if it matches `[A-Za-z0-9-]{8,64}`, otherwise a new UUID; it shall be in every log line of the request and in error bodies. |
| FR-013-6 | Every response shall carry `Server-Timing: app;dur=<ms>`, also when the handler fails. |
| FR-013-7 | The system shall expose Prometheus metrics on the management port for orders by status, payments by outcome, provisioning and refund outcomes, notifications by channel and status, telemetry drops and PII redactions, recommendations by reason, advisor runs and tokens, dead-letter messages, and HTTP latency histograms with 100 ms to 2 s SLO buckets. Tag values shall be closed sets. |
| FR-013-8 | The system shall expose `recharge.orders.stuck`: orders in PAYMENT_PENDING, PAID, PROVISIONING or REFUND_INITIATED unchanged for over 5 minutes. |
| FR-013-9 | Traces shall be exported over OTLP (sampling 10% by default) and continue across calls to external providers, which run on other threads. |

## Non-functional requirements
| Id | Requirement |
|---|---|
| NFR-013-1 | Alerts (`observability/ALERTS.md`): `max(recharge_orders_stuck) > 0` for 10 min pages; payment success ratio < 0.85; unknown payment outcomes > 0.1/s; any dead-letter message; p95 order creation > 300 ms. |
| NFR-013-2 | Metrics, logs and traces contain no PII (constitution IV.2). |

## Acceptance scenarios
### AS-1 Framework errors use the contract
- **Given** a missing `market` parameter and an unknown route
- **When** called
- **Then** 400 `REQUEST_INVALID` and 404 `RESOURCE_NOT_FOUND`, both with `correlationId`
- **Test:** `PlanControllerIT#rejectsMissingMarket`, `#unknownRouteReturnsNotFoundProblem`, `#rejectsMalformedMarketWithProblemJson`

### AS-2 Correlation id
- **Given** a request with a safe id and one with spaces and symbols
- **When** handled
- **Then** the safe id is echoed; the unsafe one is replaced with a generated id
- **Test:** `PlanControllerIT#echoesSafeCorrelationId`, `#replacesUnsafeCorrelationId`

### AS-3 Server timing
- **Given** any request, including one whose handler throws
- **When** handled
- **Then** `Server-Timing` is present and the body is intact
- **Test:** `ServerTimingFilterTest`

### AS-4 Trace and log context reach provider calls
- **Given** a request with a correlation id and an active trace
- **When** a provider call runs on its own thread
- **Then** its logs carry the id and the span joins the trace; nothing leaks to other threads
- **Test:** `OutboundGuardTest#theCallersLogContextIsVisibleInsideTheProviderCallAndDoesNotLeakOut`, `#theCurrentObservationIsCarriedOntoTheProviderCallSoTheTraceDoesNotBreak`

## Edge cases and errors
| Situation | Result |
|---|---|
| Several instances | Each reports the same stuck count: aggregate with `max`, not `sum` |
| No OTLP collector running | Export errors in the log; requests unaffected |

## Out of scope
- Dashboards as code; log shipping.

## Traceability
| Requirement | Code | Tests |
|---|---|---|
| FR-013-1..4 | `GlobalExceptionHandler`, `ErrorCode`, `ProblemSecurityHandlers`, `RateLimitFilter`, `RequestSizeFilter` | `PlanControllerIT`, `RechargeFlowIT` |
| FR-013-5 | `CorrelationIdFilter` | `PlanControllerIT` |
| FR-013-6 | `ServerTimingFilter` | `ServerTimingFilterTest` |
| FR-013-7 | `RechargeMetrics` | asserted for advisor runs and tokens (`AdvisorServiceTest`), telemetry drops and redactions (`TelemetryServiceTest`), dead letters (`KafkaFlowIT`); other counters: **gap** |
| FR-013-8 | `StuckOrdersGauge` | **Gap:** no automated test |
| FR-013-9 | `OutboundGuard`, `application.yml` (`management.tracing`) | `OutboundGuardTest` |
