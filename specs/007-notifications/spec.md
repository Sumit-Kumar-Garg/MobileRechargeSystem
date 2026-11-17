# 007 — Notifications, devices and preferences

**Status:** Implemented · **Owner:** notification, outbox · **Constitution:** II.3, III.1–2, IV.2, V.1

## Summary
Customers get a receipt when a recharge completes or is refunded: by push, with SMS as fallback, and by email as
an independent receipt. Events reach the notification service through a transactional outbox and Kafka, so a
notification problem can never fail or slow a recharge.

## User stories
- **US-1** As a customer, I want a message when my recharge is active or refunded, in my language.
- **US-2** As a customer, I want to choose push, SMS and email and to stop receipts on a shared phone at logout.
- **US-3** As the operator, I want SMS cost capped and no customer to get the same message twice.

## Functional requirements
| Id | Requirement |
|---|---|
| FR-007-1 | The system shall write domain events to the outbox in the same transaction as the order change (spec 003, FR-003-13). |
| FR-007-2 | A publisher shall, every 1 s by default and on one instance at a time (lease 60 s, renewed every 10 s), send up to 100 unpublished events to Kafka in id order, keyed by order id, and mark each published only after the broker acknowledges it (5 s timeout). |
| FR-007-3 | If sending an event fails, then the publisher shall count the failure on that event and stop the batch, so later events never overtake it. |
| FR-007-4 | The notification consumer shall act on `OrderCompleted` and `OrderRefunded` only and ignore other event types and unknown fields. |
| FR-007-5 | The system shall render the message in German or English from the user's locale (default `de-DE`; other languages fall back to English), using only the masked number, the plan name, the amount formatted for the locale and the validity. |
| FR-007-6 | When push is enabled and the user has active devices, the system shall send push first. |
| FR-007-7 | If push is disabled, unavailable or fails, and SMS is enabled, then the system shall send an SMS to the recharged number, at most 5 per user per day by default. |
| FR-007-8 | When email is enabled and an address is set, the system shall send an email receipt independently of push and SMS. |
| FR-007-9 | Each (order, event type, channel) shall be delivered at most once: a row is claimed first, and a SENT row is never sent again, also when the event is redelivered. |
| FR-007-10 | If a channel that should have reached the user did not (and the SMS cap did not block it), or an email failed, then the system shall throw so that Kafka retries after 5 s, 30 s and 3 min; after that the event goes to the dead-letter topic and is counted. |
| FR-007-11 | An unparseable event shall skip retries and go straight to the dead-letter topic without blocking later events. |
| FR-007-12 | `PUT /v1/devices` with a push token (`[A-Za-z0-9:_\-.]{20,512}`) shall register or refresh it (204); `DELETE /v1/devices` shall deactivate it (204). At most 5 devices per user stay active; the least recently seen are deactivated. |
| FR-007-13 | `GET` / `PUT /v1/notification-preferences` shall read or replace push, SMS and email switches, locale (`de` or `en`, optional region) and email address; without saved preferences everything is on, locale `de-DE`, no email. |

## Non-functional requirements
| Id | Requirement |
|---|---|
| NFR-007-1 | Push tokens and email addresses shall be encrypted at rest; delivery rows and logs shall hold no contact data. |
| NFR-007-2 | Kafka is at-least-once; correctness relies on FR-007-9, not on exactly-once delivery. |
| NFR-007-3 | A channel is a `NotificationChannel` adapter; the default `log` channels only log masked data. |

## Acceptance scenarios
### AS-1 End to end through Kafka
- **Given** a completed order and a real Kafka
- **When** the outbox publishes the event
- **Then** the customer is notified once and the journey store has the event
- **Test:** `KafkaFlowIT#aCompletedOrderReachesTheCustomerAndTheJourneyStoreThroughKafka`

### AS-2 Duplicates and poison messages
- **Given** the same event delivered twice, and an unreadable message ahead of good ones
- **When** consumed
- **Then** one notification; the unreadable message goes to the DLT and the good ones are processed
- **Test:** `KafkaFlowIT#deliveringTheSameEventTwiceSendsOneNotification`, `#anUnreadableMessageGoesToTheDeadLetterTopicWithoutBlockingTheOnesBehindIt`

### AS-3 Channel choice
- **Given** a user with a device, a user without, and a failing push provider
- **When** `OrderCompleted` is handled
- **Then** push only; SMS fallback; SMS fallback; email always as a separate receipt when an address exists
- **Test:** `NotificationServiceTest#pushIsPreferredAndSmsIsNotUsedWhenPushWorks`, `#smsFallsBackWhenTheUserHasNoDevice`, `#smsFallsBackWhenPushFails`, `#emailIsAnIndependentReceipt`

### AS-4 Cap and retries
- **Given** a user who already got 5 SMS today and has no device
- **When** another event arrives
- **Then** no SMS and no endless retry
- **Test:** `NotificationServiceTest#dailySmsCapStopsFurtherSmsWithoutEndlessRetries`, `#throwsWhenNothingReachedTheUserSoKafkaRetries`

### AS-5 Outbox ordering
- **Given** three unpublished events and a broker failure on the second
- **When** the publisher runs
- **Then** the first is marked published, the second keeps its row for retry, the third is not sent
- **Test:** `OutboxPublisherTest#stopsAtTheFirstFailureAndKeepsThatRowForRetry`, `#publishesInOrderAndMarksEachRowOnlyAfterTheBrokerAck`

### AS-6 Messages
- **Given** a completed order for `+49 *** ***4567`
- **When** rendered in German and English
- **Then** the texts contain the plan, the amount (`15,00 €` / `€15.00`) and the validity, never the full number
- **Test:** `MessageRendererTest`

## Edge cases and errors
| Situation | Result |
|---|---|
| Kafka down | Events wait in the outbox; recharges unaffected |
| Redelivery after a partial failure | Only the channel that failed is retried |
| Logout on a shared phone | `DELETE /v1/devices` stops push to that device |

## Out of scope
- Real FCM, SMS-gateway and email adapters; marketing messages.

## Traceability
| Requirement | Code | Tests |
|---|---|---|
| FR-007-1..3 | `OutboxWriter`, `OutboxPublisher`, `OutboxRepository` | `OutboxPublisherTest`, `KafkaFlowIT` |
| FR-007-4, 10..11 | `OrderEventConsumer`, `PoisonMessageException` | `OrderEventConsumerTest`, `KafkaFlowIT` |
| FR-007-5 | `MessageRenderer` | `MessageRendererTest` |
| FR-007-6..9 | `NotificationService`, `Notification`, `NotificationRepository` | `NotificationServiceTest` |
| FR-007-12..13 | `DeviceController`, `DeviceService`, `PreferenceController`, `PreferenceService` | `RechargeFlowIT#deviceTokensCanBeRegisteredAndRemoved` |
