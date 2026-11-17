# 009 — Client telemetry and journey analytics

**Status:** Implemented · **Owner:** telemetry · **Constitution:** III.1–2, IV.2–3

## Summary
The app sends batches of events so the team can see where customers drop out and how fast the API feels on
real networks. Operational data needs no consent and carries no PII; behavioural data is accepted only after the
user opts in to analytics. Server-side journey events are recorded through the outbox.

## User stories
- **US-1** As a product owner, I want a purchase funnel (plans viewed, payment started, recharge completed).
- **US-2** As an engineer, I want client-measured latency per endpoint and network type.
- **US-3** As a customer, I want behavioural tracking only if I agreed, and never my contact data in analytics.

## Functional requirements
| Id | Requirement |
|---|---|
| FR-009-1 | `POST /v1/telemetry/batch` shall accept `sessionId` (`[A-Za-z0-9-]{8,64}`), `appVersion` (`[0-9A-Za-z.-]{1,20}`) and 1 to 100 events, and respond 202 with `{accepted, dropped}`. Malformed batches get 400. |
| FR-009-2 | Each event shall have an id, a type (`journey`, `network_call`, `lifecycle`, `config_change`), a name (`[a-z0-9_]{1,40}`), a time, optional journey and order ids, and properties. |
| FR-009-3 | `network_call` events shall be accepted without consent; `journey`, `lifecycle` and `config_change` events shall be dropped unless the user granted `analytics` consent. |
| FR-009-4 | Events with a non-UUID id, or a time more than 5 min in the future or more than 7 days in the past, shall be dropped. |
| FR-009-5 | Properties shall be cleaned: at most 20 keys matching `[a-zA-Z0-9_]{1,40}`, numbers and booleans kept, strings cut to 100 chars, nested values and nulls dropped, and any string that looks like an email or phone number replaced with `[redacted]` (counted). |
| FR-009-6 | The user id shall be replaced with a pseudonymous id (keyed hash) before the event leaves the request. |
| FR-009-7 | Accepted events shall go to Kafka (`recharge.client.telemetry.v1`) keyed by the pseudonymous id, or be stored inline when Kafka is disabled. If Kafka does not confirm within 3 s, then the batch shall get 503 `SERVICE_UNAVAILABLE`. |
| FR-009-8 | The system shall record server journey events (payment started, succeeded, failed, recharge completed, provisioning failed, refund issued) through the outbox, in the order's transaction (spec 003). |
| FR-009-9 | Journey events shall be stored idempotently by event id. `network_call` events shall be folded into hourly rollups per (endpoint, network type, app version): calls, errors, latency sums and cumulative buckets (≤100, 250, 500, 1000, 2500 ms, all). |
| FR-009-10 | Rollup dimensions shall be bounded: unknown endpoints become `other`, unknown network types and versions `unknown`, durations clamped to 0..10 min; events without a duration are ignored. |
| FR-009-11 | The consumer shall retry twice (after 2 s and 6 s) and then send the event to the dead-letter topic; unparseable events go there directly. |

## Non-functional requirements
| Id | Requirement |
|---|---|
| NFR-009-1 | Analytics are kept 13 months (spec 006). |
| NFR-009-2 | Body limit for the batch endpoint is 256 KB and its rate limit 60 per minute per user (spec 011). |
| NFR-009-3 | Views `v_journey_funnel` and `v_network_latency` serve dashboards. |

## Acceptance scenarios
### AS-1 Consent gate and redaction
- **Given** a user without analytics consent sending a `journey` and a `network_call` event, one property holding an email
- **When** the batch is posted
- **Then** the journey event is dropped, the network call accepted, and the email replaced with `[redacted]`
- **Test:** `RechargeFlowIT#telemetry_isConsentGated_andRedactsPii`, `TelemetryServiceTest#behavioralEventsAreDroppedWithoutConsent`, `#piiInPropsIsRedactedAndCounted`

### AS-2 Malformed input
- **Given** a batch with a bad session id, too many events, or a bad event type
- **When** posted
- **Then** 400; within a valid batch, events with a bad id or time are dropped and counted
- **Test:** `RechargeFlowIT#telemetry_rejectsMalformedBatches`, `TelemetryServiceTest#malformedIdsAndTimestampsAreDropped`

### AS-3 Bounded rollups
- **Given** a network call to an unknown endpoint over an unknown network with an absurd duration
- **When** rolled up
- **Then** endpoint `other`, network `unknown`, duration clamped
- **Test:** `RollupRowTest`

## Edge cases and errors
| Situation | Result |
|---|---|
| Kafka down | 503 for the batch; recharges unaffected |
| Same event sent twice | Stored once |
| Everything in a batch dropped | Nothing published; 202 with `accepted: 0` |

## Out of scope
- Crash reporting, session replay, third-party analytics SDKs.

## Traceability
| Requirement | Code | Tests |
|---|---|---|
| FR-009-1..7 | `TelemetryController`, `TelemetryService`, `PiiGuard`, `KafkaTelemetrySink`, `InlineTelemetrySink` | `TelemetryServiceTest`, `PiiGuardTest`, `RechargeFlowIT` |
| FR-009-8 | `JourneyRecorder`, `OrderTransitions` | `RechargeFlowIT#happyPath_endsCompleted_withEventsJourneyAndNotifications` |
| FR-009-9..11 | `TelemetryConsumer`, `TelemetryProcessor`, `RollupRow` | `RollupRowTest`, `KafkaFlowIT` |
