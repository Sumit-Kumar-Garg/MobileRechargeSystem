# 001 — Plan catalog and provider sync

**Status:** Implemented · **Owner:** catalog · **Constitution:** I.4, III.2, VI.2, VII.1

## Summary
Customers browse the recharge plans on sale in their market. Plans come from plan providers (the operator and
partners such as a travel-SIM supplier) and are copied into the catalog on a schedule, so browsing and ordering
never depend on a provider being reachable.

## User stories
- **US-1** As a customer, I want to see the plans for my country with price, validity, allowances and benefits,
  so that I can choose one.
- **US-2** As a partner team, I want to supply plans through a contract without changing the core, so that my
  plans are sold next to the operator's.
- **US-3** As the operator, I want a broken partner feed never to take plans off sale, so that a partner outage
  cannot hide the catalog.

## Functional requirements
| Id | Requirement |
|---|---|
| FR-001-1 | When a client requests `GET /v1/plans?market=XX`, the system shall return the active plans of that market, ordered by price, then code, then provider, each with id, provider, code, name, price (minor units + currency), validity days, allowances and benefits. |
| FR-001-2 | The plan list shall be public (no authentication). |
| FR-001-3 | If `market` is missing or is not two upper-case letters, then the system shall respond 400 `REQUEST_INVALID`. |
| FR-001-4 | When the market has no plans, the system shall return an empty list, not an error. |
| FR-001-5 | The system shall return `Cache-Control: public, max-age=60` on the plan list. |
| FR-001-6 | Allowances that a plan does not meter separately shall be `null` (for example unlimited voice is a benefit, not a number). |
| FR-001-7 | The system shall sync every plan provider on a schedule (default every 15 min, first run 10 s after start), on one instance at a time. |
| FR-001-8 | When a feed is synced, the system shall upsert valid plans by (provider, market, code) and deactivate, never delete, that provider's active plans missing from a non-empty feed. |
| FR-001-9 | If a feed throws, is empty, or contains no valid plan, then the system shall leave that provider's plans unchanged and report the sync as failed; other providers are unaffected. |
| FR-001-10 | The system shall skip a plan that fails validation: code `[A-Za-z0-9._-]{1,40}`, market two upper-case letters, currency three upper-case letters, non-blank name of at most 120 characters, positive price, validity 1 to 3650 days, no negative allowance. At most 10 benefits are kept per plan, each cut to 160 characters. |
| FR-001-11 | A provider with an id not matching `[a-z0-9-]{2,40}` shall be ignored. |
| FR-001-12 | After a successful sync, the system shall clear the plans cache (see spec 012 for other instances). |
| FR-001-13 | Order creation shall read the plan live from the database, never from the cache (spec 003). |

## Non-functional requirements
| Id | Requirement |
|---|---|
| NFR-001-1 | The plan list shall be served from a per-instance cache (default max 500 markets, 5 min TTL). |
| NFR-001-2 | A plan provider adapter shall depend only on `catalog.spi` and be switched on by configuration (`app.providers.<name>.enabled`). |
| NFR-001-3 | The catalog tables are read-only to the API; only the sync writes them. |

## Acceptance scenarios
### AS-1 Plans of a market, cheapest first
- **Given** active DE plans priced 10, 15 and 25 EUR
- **When** `GET /v1/plans?market=DE`
- **Then** 200 with the three plans in price order, each with its benefits, and no plan of another market
- **Test:** `PlanControllerIT#listsActivePlansForMarketSortedByPriceWithBenefits`, `#doesNotLeakPlansOfOtherMarkets`

### AS-2 Invalid or unknown market
- **Given** any catalog
- **When** `market=de`, or `market` is missing, or `market=ZZ`
- **Then** 400 `REQUEST_INVALID` for the first two, 200 with an empty list for `ZZ`
- **Test:** `PlanControllerIT#rejectsMalformedMarketWithProblemJson`, `#rejectsMissingMarket`, `#unknownMarketReturnsEmptyList`

### AS-3 A broken feed never takes plans off sale
- **Given** a partner with plans on sale
- **When** its feed throws, returns nothing, or contains only invalid plans
- **Then** its plans stay on sale and the sync reports `failed`
- **Test:** `ProvidersIT#syncIsSafe_updatesInPlace_deactivatesMissing_skipsInvalid_andSurvivesABrokenFeed`

### AS-4 A partner's plans are sold and fulfilled by that partner
- **Given** the travel-SIM provider is enabled
- **When** the catalog syncs
- **Then** its plans appear in the DE list with `provider = travelsim` and can be ordered and fulfilled
- **Test:** `ProvidersIT#aPartnersPlansAreSyncedSoldAndFulfilledByThatPartner`

### AS-5 Validation
- **Given** a feed with a zero price, a bad currency, an over-long name or a negative allowance
- **When** validated
- **Then** each such plan is rejected and a well-formed plan is accepted
- **Test:** `CatalogSyncValidationTest#rejectsWhatCouldHurtCustomersOrTheCatalog`, `#acceptsAWellFormedPlan`

## Edge cases and errors
| Situation | Result |
|---|---|
| Plan removed from a partner feed | Deactivated; existing orders keep referencing it |
| Partner unreachable | Previous copy stays; warning logged |
| Two providers use the same plan code | Allowed: uniqueness is per (provider, market, code) |

## Out of scope
- Admin UI for editing plans; operator plans are seeded by migrations.

## Traceability
| Requirement | Code | Tests |
|---|---|---|
| FR-001-1..6 | `PlanController`, `PlanService`, `PlanRepository`, `PlanView` | `PlanControllerIT` |
| FR-001-7..12 | `CatalogSyncJob`, `CatalogSyncService` | `ProvidersIT`, `CatalogSyncValidationTest` |
| FR-001-13 | `PlanService#getActivePlan` | `OrderServiceTest#createsOrderWithServerSidePrice` |
| NFR-001-2 | `catalog.spi.PlanProvider`, `providers.travelsim` | `PlanProviderContract`, `ArchitectureTest` |
