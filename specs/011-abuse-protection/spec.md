# 011 — Rate limits and abuse protection

**Status:** Implemented · **Owner:** ratelimit · **Constitution:** I.1, VII.2

## Summary
Limits protect the service and the customer: a global ceiling per user or IP, tighter limits on sensitive
endpoints, a lockout against card testing, and request-size limits. With several instances the counters are
shared in Redis.

## User stories
- **US-1** As the operator, I want automated abuse (card testing, scraping, floods) stopped early.
- **US-2** As a legitimate customer, I want normal use never to hit a limit, and a clear retry time if I do.

## Functional requirements
| Id | Requirement |
|---|---|
| FR-011-1 | The system shall count requests per authenticated user, or per client IP for anonymous requests, in fixed windows. |
| FR-011-2 | The system shall apply a global ceiling to all requests (default 120 per minute). |
| FR-011-3 | The system shall apply per-endpoint limits by policy name, defaults: `orders-create` POST /v1/orders 10/h; `orders-pay` POST /v1/orders/{id}/pay 10/h; `numbers-link` POST /v1/numbers 5/day; `recommendations` GET /v1/recommendations 20/h; `telemetry` POST /v1/telemetry/batch 60/min; `devices-put` and `devices-delete` 20/h each. |
| FR-011-4 | When a limit is exceeded, the system shall respond 429 `RATE_LIMITED` as problem+json with `Retry-After` (seconds); on limited endpoints it shall set `X-RateLimit-Remaining`. |
| FR-011-5 | Health checks shall never be limited. |
| FR-011-6 | After a configured number of declined payments in a window (default 5 per hour), the system shall refuse further payment attempts by that user with 429 `RATE_LIMITED`, before any charge. The lockout is per user and ends with the window. |
| FR-011-7 | The system shall reject bodies whose `Content-Length` exceeds the limit (default 16 KB; 256 KB for the telemetry batch) with 413 `PAYLOAD_TOO_LARGE` before parsing. |
| FR-011-8 | With `RATE_LIMIT_STORE=redis` the counters shall be shared by all instances (atomic increment + expiry in one script); with `memory` they are per instance and a warning is logged at startup. |
| FR-011-9 | If Redis is unreachable, then the limiter shall allow the request and log a warning (fail open). |
| FR-011-10 | Every limit and window shall be configurable; an unknown policy name, a limit below 1 or a non-positive window shall stop startup. |

## Non-functional requirements
| Id | Requirement |
|---|---|
| NFR-011-1 | Per-IP limits trust `X-Forwarded-For`, so only a trusted load balancer that overwrites it may reach the instances (spec 012). |
| NFR-011-2 | The load balancer's body limit must be at least the telemetry body limit. |

## Acceptance scenarios
### AS-1 Endpoint policy
- **Given** a user who linked numbers 5 times today
- **When** they try a sixth time
- **Then** 429 `RATE_LIMITED` with `Retry-After`; other endpoints and other users are unaffected
- **Test:** `RateLimitFilterTest#endpointPolicyBlocksTheSixthLinkAttemptOfTheDay`, `#policiesOnlyApplyToTheirOwnMethodAndPath`, `#differentClientsHaveSeparateBudgets`

### AS-2 Card-testing lockout
- **Given** a user with 5 declined payments in the last hour
- **When** they pay again
- **Then** 429 before any PSP call; another user can pay; after the window they can pay again
- **Test:** `AbuseGuardTest#locksOutAfterTooManyFailures`, `#lockoutIsPerUserAndExpires`, `PaymentServiceTest#lockedOutUserIsStoppedBeforeAnyChargeAttempt`

### AS-3 Body size
- **Given** a 17 KB body to a normal endpoint and a 64 KB telemetry batch
- **When** posted
- **Then** 413 for the first, accepted for the second
- **Test:** `RateLimitFilterTest#oversizedBodiesAreRejectedEarly`, `#telemetryEndpointAllowsLargerBodies`

### AS-4 Configuration
- **Given** `RATE_LIMIT_NUMBERS_LINK_LIMIT=2`; and separately a policy named `number-link`
- **When** the app starts and a user links 3 times
- **Then** the third is 429; the misspelled name stops startup listing the known names
- **Test:** `RateLimitFilterTest#configuredPolicyOverrideReplacesTheDefaultLimit`, `#misspelledPolicyNameFailsInsteadOfBeingIgnored`, `LimitsConfigurationTest`

### AS-5 Shared across instances
- **Given** two instances behind a load balancer and Redis
- **When** one user links a number 7 times, alternating instances
- **Then** 200 × 5, then 429 × 2
- **Test:** manual cluster check (`docker-compose.cluster.yml`); **gap:** no automated test

## Edge cases and errors
| Situation | Result |
|---|---|
| Redis down | Limits not enforced until it returns (fail open) |
| Anonymous traffic behind NAT | Shares one per-IP budget |

## Out of scope
- WAF rules and bot detection (load balancer / gateway).

## Traceability
| Requirement | Code | Tests |
|---|---|---|
| FR-011-1..5, 10 | `RateLimitFilter`, `RateLimitProperties` | `RateLimitFilterTest`, `LimitsConfigurationTest` |
| FR-011-6 | `AbuseGuard` | `AbuseGuardTest`, `PaymentServiceTest` |
| FR-011-7 | `RequestSizeFilter` | `RateLimitFilterTest` |
| FR-011-8..9 | `InMemoryRateLimiter`, `RedisRateLimiter` | `InMemoryRateLimiterTest`; Redis limiter: **gap** |
