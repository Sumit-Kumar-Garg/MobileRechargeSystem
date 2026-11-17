# 012 — Multi-instance operation and configuration

**Status:** Implemented · **Owner:** platform · **Constitution:** VII.1–2, III.4

## Summary
The service runs as any number of identical instances behind a load balancer. Background jobs run on one
instance at a time, shared state lives in the database or Redis, and every operational value is an environment
variable with a safe default and startup validation.

## User stories
- **US-1** As an operator, I want to add or remove instances without changing behaviour or doubling work.
- **US-2** As an operator, I want to tune limits and timings per environment without a code change, and a bad
  value to fail loudly at startup.
- **US-3** As a developer, I want local data to survive restarts of the Docker stack.

## Functional requirements
| Id | Requirement |
|---|---|
| FR-012-1 | Each background job (outbox publisher, reconciliation, catalog sync, retention, provisioning worker) shall run only on the instance holding its database lease; the holder renews it, and if it dies another instance takes over when the lease expires. |
| FR-012-2 | Leases shall be judged by the database clock, not by instance clocks. |
| FR-012-3 | Every instance shall check a catalog version (last sync time, active count, active price sum) every 10 s by default and clear its own plans cache when it changed; the first check only records the version; a failed check is logged and the cache TTL still applies. |
| FR-012-4 | Rate-limit counters shall be shared through Redis when `RATE_LIMIT_STORE=redis` (spec 011). |
| FR-012-5 | Every value in `application.yml` that may differ between environments shall be read from an environment variable with the current default (connections, secrets, limits, schedules, timeouts, thread pools, advisor settings); `.env.example` shall list all of them. |
| FR-012-6 | Limits shall be bound to typed configuration per module (`RateLimitProperties`, `NumberProperties`, `NotificationProperties`, `ReconciliationProperties`, `ProvisioningProperties`, `OrderProperties`, `AdvisorProperties`); zero or negative limits, non-positive durations and unknown policy names shall stop startup. |
| FR-012-7 | Scheduler delays shall be ISO-8601 durations (`PT15S`); other durations accept `15m` style. |
| FR-012-8 | The `demo` profile shall always use its own dev-only secrets, seed plans and mock providers, whatever the environment says. |
| FR-012-9 | Local Postgres, Kafka, Redis and observability data shall live in named Docker volumes and survive `docker compose down`; only `down -v` deletes them. Kafka shall store its log in the volume with a fixed cluster id. |
| FR-012-10 | The cluster setup shall run two instances behind nginx that: is the only published entry (port 8000 by default), proxies only `/v1`, `/v3/api-docs` and Swagger, overwrites `X-Forwarded-For`, does not retry POST requests, and limits bodies to 256 KB. |

## Non-functional requirements
| Id | Requirement |
|---|---|
| NFR-012-1 | Instances × `DB_POOL_SIZE` shall stay below Postgres `max_connections`. |
| NFR-012-2 | The image shall run as a non-root user with a liveness health check on the management port. |
| NFR-012-3 | Builds require JDK 21 to 23. |

## Acceptance scenarios
### AS-1 One lease holder
- **Given** two lease clients for the same job
- **When** both try to acquire it; then the holder releases it
- **Then** only the first holds it and can renew; after release the second takes it over
- **Test:** `RechargeFlowIT#jobLease_isHeldByOneOwnerAndCanBeTakenOverAfterRelease`, `ProvisioningWorkerTest#sweepsOnlyOnTheInstanceHoldingTheLease`, `OutboxPublisherTest#doesNothingWhenAnotherInstanceIsThePublisher`

### AS-2 Caches follow the catalog
- **Given** two instances with the plans cached
- **When** a plan's price changes in the database
- **Then** both instances serve the new price within about 10 s, without a restart
- **Test:** `PlanCacheRefresherTest`; cluster check (price change visible on both instances within 12 s)

### AS-3 Configuration defaults and overrides
- **Given** the real `application.yml`
- **When** bound with no environment, with overrides, and with invalid values
- **Then** the former hard-coded defaults; the overridden values; a startup failure
- **Test:** `LimitsConfigurationTest`

### AS-4 Data survives `down`
- **Given** an order in Postgres, Kafka topics and Redis counters
- **When** `docker compose down` then `up`
- **Then** the order, the topics and the unexpired counters are still there
- **Test:** manual check performed on the compose stack; **gap:** no automated test

## Edge cases and errors
| Situation | Result |
|---|---|
| Lease holder dies | Takeover within the lease time (60 s to 10 min depending on the job) |
| Clock drift between instances | No effect on leases (database clock) |
| Mock payment provider with several instances | Its in-memory state is per instance; `tok_timeout` may end declined (demo only) |

## Out of scope
- Kubernetes manifests, autoscaling policies, multi-region.

## Traceability
| Requirement | Code | Tests |
|---|---|---|
| FR-012-1..2 | `JobLease`, all scheduled jobs | `RechargeFlowIT`, `ProvisioningWorkerTest`, `OutboxPublisherTest`, `ReconciliationJobTest` |
| FR-012-3 | `PlanCacheRefresher` | `PlanCacheRefresherTest` |
| FR-012-5..8 | `application.yml`, `application-demo.yml`, `*Properties`, `AppConfig`, `.env.example` | `LimitsConfigurationTest` |
| FR-012-9..10 | `docker-compose*.yml`, `deploy/nginx.conf`, `Dockerfile` | manual cluster checks |
