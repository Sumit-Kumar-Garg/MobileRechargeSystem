# Recharge & Top-up Backend

[![CI](https://github.com/Sumit-Kumar-Garg/MobileRechargeSystem/actions/workflows/ci.yml/badge.svg)](https://github.com/Sumit-Kumar-Garg/MobileRechargeSystem/actions/workflows/ci.yml)
![Java 21](https://img.shields.io/badge/Java-21-orange)
![Spring Boot 3.3](https://img.shields.io/badge/Spring%20Boot-3.3-6DB33F)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-336791)
![Kafka](https://img.shields.io/badge/Apache%20Kafka-231F20)
![Redis](https://img.shields.io/badge/Redis-DC382D)

The backend for a mobile recharge app. Customers link their phone numbers, browse prepaid plans, pay, and get the
top-up applied to their number. If a step fails after they've paid, they're refunded automatically.

## Features
- **Plan catalog:** prepaid plans per market, with validity and benefits, cached for fast browsing
- **Linked numbers:** customers link up to a set number of phone numbers to their account (encrypted at rest)
- **Orders and payments:** idempotent orders, a strict order state machine, several payment providers (card and wallet)
- **Provisioning with automatic refunds:** retries the operator top-up, then refunds the customer if it keeps failing
- **Reconciliation:** resolves payments whose result was lost, and expires abandoned orders
- **Notifications:** push, SMS and email driven by Kafka events, with retries and a dead-letter topic
- **AI recharge advisor:** recommends a plan from the customer's usage, with consent checks and a rules-based fallback
- **Abuse protection:** per-endpoint rate limits, lockout after repeated declined payments, request size limits
- **Observability:** Prometheus metrics, OpenTelemetry tracing, Grafana dashboards, client telemetry
- **Runs as several instances:** database leases for background jobs, shared rate limits in Redis

## Prerequisites
- JDK 21 (the build rejects JDK 24+)
- Docker (for Postgres, Kafka, Redis and the integration tests)
- `jq` and `curl` for the API examples below (optional)

## Quick start
```bash
git clone https://github.com/Sumit-Kumar-Garg/MobileRechargeSystem.git && cd MobileRechargeSystem
docker compose up -d                                  # start postgres, kafka, redis
SPRING_PROFILES_ACTIVE=demo ./mvnw spring-boot:run    # seed data, mock providers, dev secrets
```
Then open http://localhost:8080/swagger-ui.html for the API docs, or run `./scripts/demo-flow.sh` to walk through a
full recharge.

## Documentation
- [`ARCHITECTURE.md`](ARCHITECTURE.md): how the system is shaped, the money path, invariants and failure behaviour
- [`specs/`](specs/README.md): one spec per feature (catalog, orders, payments, refunds, notifications, ...)
- [`.env.example`](.env.example): every configuration setting with its default

## License
No license has been chosen yet, so all rights are reserved by default.

---

The rest of this file is the detailed developer guide.

## Overview
Spring Boot 3.3 / Java 21 / PostgreSQL / Kafka. Modular monolith for the "Recharge & Top-up" self-care app.

Behaviour is specified in [`specs/`](specs/README.md) (spec-driven development: constitution + one spec per
feature with requirements, acceptance scenarios and traceability to code and tests). Change the spec first.

## Run locally
```bash
docker compose up -d                       # postgres, kafka, redis
SPRING_PROFILES_ACTIVE=demo ./mvnw spring-boot:run   # needs JDK 21: export JAVA_HOME=$(/usr/libexec/java_home -v 21)
open http://localhost:8080/swagger-ui.html # API docs (demo profile only)
```
Several instances behind a load balancer (see "Running several instances" below):
`docker compose -f docker-compose.yml -f docker-compose.cluster.yml up -d --build`, then use http://localhost:8000.

Local data (Postgres, Kafka, Redis, and the observability stack) lives in named Docker volumes, so it survives
`docker compose down`, restarts and image rebuilds. `docker compose down -v` deletes it. Back up the database with
`docker compose exec postgres pg_dump -U recharge -Fc recharge > recharge.dump` and restore it with
`docker compose exec -T postgres pg_restore -U recharge -d recharge --clean --if-exists < recharge.dump`.

Optional observability: `docker compose -f docker-compose.observability.yml up -d`, then start the app with
`TRACE_SAMPLING=1.0`. Grafana: http://localhost:3000 (Prometheus, Tempo and the Postgres funnel views are pre-wired).

Try it:
```bash
TOKEN=$(curl -s -X POST localhost:8080/v1/demo/token | jq -r .accessToken)
NUM=$(curl -s -X POST localhost:8080/v1/numbers -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"msisdn":"+491701234567"}' | jq -r .id)
PLAN=$(curl -s "localhost:8080/v1/plans?market=DE" | jq -r '.plans[0].id')
ORDER=$(curl -s -X POST localhost:8080/v1/orders -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $(uuidgen | tr -d -)" -d "{\"numberId\":\"$NUM\",\"planId\":\"$PLAN\"}" | jq -r .id)
curl -s -X POST localhost:8080/v1/orders/$ORDER/pay -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"paymentToken":"tok_success"}'
```
Or run the whole flow (token, number, consent, recommendation, order, payment, notifications, telemetry) in one go:
`./scripts/demo-flow.sh` (optional argument: base URL, default `http://localhost:8080`; needs `python3`, not `jq`).

## Demo switches (mock PSP and OCS)
| Input | Behavior |
|---|---|
| payment token `tok_success` / `tok_decline` | approved / declined |
| payment token `tok_timeout` | PSP approves but the response is lost: order stays `PAYMENT_PENDING`, reconciliation resolves it |
| number ending `0000` | OCS always down: retries, then automatic refund |
| number ending `0001` | OCS fails twice, then works |
| number ending `9999` | OCS rejects the number permanently: refunded after one attempt |

## Providers
Two payment providers and two plan providers run side by side in the demo profile.

| Provider | Kind | Offers | Switch |
|---|---|---|---|
| `mockpay` | payment | card, any currency | `app.psp.mode=mock` (set by the `demo` profile only) |
| `altpay` | payment | wallet; card in EUR and PLN | `ALTPAY_ENABLED=true` |
| `operator` | plans + fulfilment | the seeded plans | `app.ocs.mode=mock` (set by the `demo` profile only) |
| `travelsim` | plans + fulfilment | roaming packs for DE (appear after the first catalog sync) | `TRAVELSIM_ENABLED=true` |

Routing order for new payments: `PAYMENT_PROVIDER_PRIORITY` (default `mockpay,altpay`). Pay with a wallet:
`-d '{"paymentToken":"tok_success","method":"wallet"}'`. Available methods: `GET /v1/payment-methods?currency=EUR`.

The mock providers keep their state in memory, so they are for demo and tests only. Outside the `demo` profile
`PSP_MODE` and `OCS_MODE` default to `none`, and the service refuses to start unless at least one real payment adapter
and one real fulfilment adapter are enabled.

To add a provider: create `providers/<name>/`, implement the contract from the matching `spi` package, extend its
contract test kit in `src/test/.../spi/`, guard it with `app.providers.<name>.enabled`. No core change is needed.
`ArchitectureTest` fails the build if an adapter reaches past the contracts. Team ownership: `.github/CODEOWNERS`.

## Test
```bash
./mvnw verify      # unit tests need nothing; integration tests (*IT) need Docker (Testcontainers)
```
Load test: `k6 run -e BASE_URL=http://localhost:8080 loadtest/k6-recharge.js`

## Slices
| Slice | Scope | Main packages |
|---|---|---|
| S0 | Foundation: error contract, correlation id, Flyway, config | `common` |
| S1 | Catalog: plans, validity, benefits, caching | `catalog` |
| S2 | Security and ownership: JWT, PII encryption, linked numbers | `security`, `crypto`, `number` |
| S3 | Order core: state machine, idempotent create, audit, outbox write | `order`, `outbox` |
| S4 | Payment: PSP port, circuit breaker, attempts, pending state | `payment` |
| S5 | Provisioning and compensation: OCS port, retries, refund saga | `provisioning` |
| S6 | Kafka and reliability: outbox publisher, reconciliation, expiry, retention | `outbox`, `reliability` |
| S7 | Notifications: push/SMS/email, devices, preferences, retry topics, DLT | `notification` |
| S8 | Abuse protection: rate limits, payment lockout, size limits | `ratelimit` |
| S9 | Recommendation and consent | `recommendation`, `consent` |
| S10 | Observability: Server-Timing, metrics, tracing, stuck-order gauge | `observability` |
| S11 | Client telemetry and journey analytics | `telemetry` |
| S12 | Hardening: failure-path tests, load test | `src/test`, `loadtest` |
| S13 | Docs | this file, `ARCHITECTURE.md` |

## Configuration (environment variables)
Every setting in `application.yml` and the compose files is an environment variable with a local default;
**`.env.example` lists all of them** with their defaults and meaning. Copy it to `.env` (git-ignored) for local
overrides: Docker Compose reads `.env` automatically and passes it to the app containers; for
`./mvnw spring-boot:run` export the variables in your shell (`set -a; source .env; set +a`).

The essentials: `DB_URL, DB_USER, DB_PASSWORD, JWKS_URI and JWT_AUDIENCE (production) or JWT_SECRET (demo, >= 32
bytes), PII_AES_KEY (base64, 32 bytes), PII_HMAC_KEY (base64, >= 32 bytes), KAFKA_BOOTSTRAP, REDIS_HOST,
REDIS_PASSWORD, RATE_LIMIT_STORE (memory | redis; redis is required with more than one instance)`. Business rules
(order expiry, payment and provisioning attempts), abuse limits (per-endpoint rate limits, failed-payment lockout,
request size, numbers per user, SMS per day, devices per user), reconciliation timings, schedules, timeouts, thread
pools and the advisor limits are configurable the same way. Out-of-range values (zero, negative) and unknown
rate-limit policy names stop the application at startup instead of being used. Scheduler delays must be ISO-8601 durations (`PT15S`), the other durations accept `15m`.
The app refuses to start without the secrets and keys. The `demo` profile supplies dev-only values and always
uses them, whatever is set in the environment.

## Running several instances
The service is stateless per request and can run as many instances as needed behind a load balancer.
What makes that safe, and what a deployment must provide:

| Concern | How it is handled |
|---|---|
| Background jobs (outbox publisher, reconciliation, catalog sync, retention, provisioning worker) | One instance per job at a time through a database lease (`job_leases`), judged by the database clock so instance clock drift cannot double-hold a lease. A dead holder's lease expires and another instance takes over. |
| Rate limits, payment lockout, SMS cap, advisor run limit | **Set `RATE_LIMIT_STORE=redis`.** With `memory` every instance counts on its own and each limit is multiplied by the number of instances; the app logs a warning at startup. The Redis limiter fails open if Redis is down. |
| Plans cache (Caffeine, per instance) | Every instance checks a catalog version every 10 s and clears its own cache when it changed (`PlanCacheRefresher`). Orders always read the live price, so a stale list can never change what is charged. |
| Orders and payments | Row locks and state checks in the database; two instances can never charge or provision an order twice. |
| Kafka consumers | Consumer groups share partitions; notifications are idempotent per (order, event, channel). |
| Flyway | Migrations take a database lock; instances starting together are safe. |
| Metrics | `recharge_orders_stuck` is the same database count on every instance: alert on `max(...)`, not `sum(...)`. |
| Load balancer | Must overwrite `X-Forwarded-For` (per-IP limits trust it) and be the only way to reach the instances. Health and metrics stay on port 8081 and are not proxied. |
| Database connections | Instances x `DB_POOL_SIZE` must stay below Postgres `max_connections`. |

Local cluster: `docker-compose.cluster.yml` runs two instances (`app1`, `app2`) behind nginx (`deploy/nginx.conf`) on
http://localhost:8000; the `X-Upstream` response header shows which instance answered.
`./scripts/demo-flow.sh http://localhost:8000` runs the whole flow through it.
Demo limitation: the mock payment provider keeps its state in memory per instance, so a `tok_timeout` payment
may be resolved by another instance that never saw it and end up declined. Real provider adapters keep the state at
the provider and are not affected.

## Module boundaries
Every table has one owning package. Other packages never touch its repository; they use the owner's API:
`order.OrderStore` (read and lock orders), `order.OrderTransitions` (the only way to change an order's status or
failure code), `payment.PaymentService` and `payment.RefundService` (everything about money). `ArchitectureTest`
fails the build when a class uses a repository from another package.

## Tests
- `mvn test` runs the unit tests; `mvn verify` also runs the integration tests (they need Docker).
- `KafkaFlowIT` starts a real Kafka and Postgres and proves outbox -> Kafka -> notification and journey rows,
  duplicate delivery, and the dead-letter path.
- Status: compiles and all 293 unit tests and 39 integration tests pass on JDK 21 (`./mvnw verify`, Docker needed for
  the integration tests; skip them with `-DskipITs`). JDK 24+ is rejected by the build.

## AI recharge advisor (agent)
The recommendation is made by an agent: a language model that reads the customer's past usage and recharges
through three read-only tools, compares them with the plans on sale, and submits one plan with a one-sentence
reason. The app never talks to the model and never holds its key. It keeps calling `GET /v1/recommendations`.

```
app -> GET /v1/recommendations -> stored advice?  yes -> AI_ADVISOR + explanation   (a few ms)
                                                 no  -> rules answer now, agent starts in the background
agent (background): model <-> tools (usage, recharge history, plans) -> validated -> stored for 24h
```

| Setting | Meaning |
|---|---|
| `ADVISOR_LLM=none` (default) | Agent off, rules only |
| `ADVISOR_LLM=scripted` (demo profile) | Built-in stand-in for a model; no key, no network |
| `ADVISOR_LLM=openai-compatible` + `ADVISOR_LLM_BASE_URL`, `ADVISOR_LLM_API_KEY`, `ADVISOR_LLM_MODEL` | A real model behind the common chat-completions-with-tools format (hosted, internal gateway or self-hosted) |

Try it in the demo profile: give personalization consent, set usage with `POST /v1/demo/usage`, call
`GET /v1/recommendations?numberId=...` twice. The first answer is `USAGE_FIT` (rules); a moment later the
second is `AI_ADVISOR` with an `explanation`.

Safeguards: consent before any personal data is read; the model gets no phone number and no ids; tools are
read-only and bound to one number by the server; the answer is accepted only if the plan is on sale and the text is
plain and short; step, token, per-user and concurrency limits; any problem leaves the rule-based answer in place.
Code: `recommendation/agent` (loop, tools, storage), `recommendation/spi` (model contract), `recommendation/llm`
(adapters). Metrics: `recharge.advisor.runs{outcome}`, `recharge.advisor.tokens{direction}`.
