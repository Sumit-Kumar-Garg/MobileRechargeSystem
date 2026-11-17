# Specifications

Spec-driven development for `recharge-backend`. The specs say **what** the service must do and **why**; the code
says how. Behaviour changes start here: change the spec, then the tests, then the code.

These specs were written from the implementation as built (version `0.2.0-SNAPSHOT`), so every requirement
is already implemented, and the **Traceability** table of each spec names the code and the tests that prove it.

## Layout
```
specs/
  constitution.md            principles every feature must keep (non-negotiable)
  NNN-feature/spec.md        one feature: user stories, requirements, acceptance scenarios, traceability
  _template/spec.md          template for a new feature
```

## Features
| Spec | Feature | Main package(s) |
|---|---|---|
| [001](001-plan-catalog/spec.md) | Plan catalog and provider sync | `catalog` |
| [002](002-linked-numbers/spec.md) | Linked phone numbers | `number`, `crypto` |
| [003](003-orders/spec.md) | Orders: idempotent creation and state machine | `order` |
| [004](004-payments/spec.md) | Payments and provider routing | `payment` |
| [005](005-provisioning-and-refunds/spec.md) | Provisioning, retries and refunds | `provisioning`, `payment` |
| [006](006-reconciliation-and-expiry/spec.md) | Reconciliation, order expiry, data retention | `reliability` |
| [007](007-notifications/spec.md) | Notifications, devices and preferences | `notification`, `outbox` |
| [008](008-recommendations/spec.md) | Recommendations and the AI advisor | `recommendation`, `consent` |
| [009](009-telemetry/spec.md) | Client telemetry and journey analytics | `telemetry`, `consent` |
| [010](010-security-and-privacy/spec.md) | Authentication, authorization, PII | `security`, `crypto`, `common` |
| [011](011-abuse-protection/spec.md) | Rate limits and abuse protection | `ratelimit` |
| [012](012-multi-instance-and-config/spec.md) | Multi-instance operation and configuration | `common`, `catalog`, deployment |
| [013](013-observability/spec.md) | Observability and the error contract | `observability`, `common` |

## Conventions
- **Requirement ids** are `FR-<spec>-<n>` (functional) and `NFR-<spec>-<n>` (non-functional). Ids are never
  reused or renumbered; a removed requirement is marked *Withdrawn*.
- **Requirements** use EARS wording: "The system shall …", "When …, the system shall …", "If …, then the system
  shall …", "While …, the system shall …".
- **Acceptance scenarios** use Given / When / Then and each names the test that automates it.
- **Defaults** given in a requirement (limits, durations) are configurable; the variable is named in spec 012.
- **Error codes** are the stable `code` values of the problem+json error contract (spec 013).

## Known test gaps
Requirements implemented but not yet covered by an automated test (constitution VIII.2). Each is marked
**Gap** in its spec's Traceability table.

| Spec | Requirement | Missing test |
|---|---|---|
| 006 | FR-006-9 data retention | Integration test that old rows are deleted and recent ones kept |
| 011 | FR-011-8..9 Redis limiter, AS-5 | Testcontainers Redis: shared counters across two limiters; fail-open when Redis stops |
| 012 | AS-4 data survives `down` | Scripted compose check (manual today) |
| 013 | FR-013-7 most counters, FR-013-8 stuck-orders gauge | Assert counters per outcome; gauge counts only old orders in money states |

## Workflow for a change
1. Edit or add the spec (copy `_template/spec.md` for a new feature). Check it against `constitution.md`.
2. Write or change the acceptance tests named in the spec; they fail.
3. Implement until they pass; update the Traceability table.
4. `./mvnw verify` must pass (unit, contract, architecture and integration tests).
