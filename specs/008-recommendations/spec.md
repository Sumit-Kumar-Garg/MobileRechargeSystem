# 008 — Recommendations and the AI advisor

**Status:** Implemented · **Owner:** recommendation, consent · **Constitution:** III.1–2, IV.2–3, VI.1

## Summary
The app shows a "recommended" plan for a number. Personal data (usage, purchase history) is used only with
personalization consent. With consent, an AI advisor agent may produce the recommendation in the background;
an explainable rules engine answers immediately and remains the fallback.

## User stories
- **US-1** As a customer, I want a plan suggestion that fits how I use my phone, with a short reason.
- **US-2** As a customer, I want my data used for this only if I agreed, and the advice deleted if I withdraw.
- **US-3** As the operator, I want the AI to be cheap, bounded and unable to do or leak anything, and never to
  slow down or break the plan screen.

## Functional requirements
| Id | Requirement |
|---|---|
| FR-008-1 | `GET /v1/recommendations?numberId=` shall return `{planId, planCode, reason, personalized, cheaperAlternativePlanId, modelVersion, explanation}` for one of the caller's numbers (404 otherwise), or 204 when the market has no plans. |
| FR-008-2 | Without personalization consent, the system shall not read usage or history, shall delete any stored advice for the number, and shall return the middle plan of the price-sorted catalog (`POPULAR_DEFAULT`, `personalized=false`). |
| FR-008-3 | With consent and fresh stored advice (valid, same language, plan still on sale), the system shall return it with reason `AI_ADVISOR` and its explanation, without calling the model. |
| FR-008-4 | With consent and no fresh advice, the system shall answer with the rules now and start an advisor run in the background. |
| FR-008-5 | The rules shall pick, in order: (1) `USAGE_FIT` — the cheapest plan whose data covers the average of the last 3 months' usage plus 10%, or the biggest plan if none does; (2) `REPEAT_PURCHASE` — the plan bought most often among the last 5 completed orders still on sale, ties to the most recent; (3) `POPULAR_DEFAULT` — the middle plan. Zero usage counts as no usage. |
| FR-008-6 | The response shall name the cheapest plan as `cheaperAlternativePlanId` when it differs from the pick. |
| FR-008-7 | With consent, each recommendation shall be logged (plan, reason, model version) for A/B analysis. |
| FR-008-8 | Any failure shall degrade to the popular default; the endpoint never fails because of the recommender. |
| FR-008-9 | The advisor shall be a language model with three read-only tools bound by the server to one number: usage summary (6 months), recharge history (month only, no exact times), plans on sale. No tool takes an identifier from the model. |
| FR-008-10 | The advisor's answer shall be accepted only if the model read the customer's data and the plans, the plan code is on sale, and the explanation is plain text (no links, markup or control characters, ≤ 200 chars). A rejected answer is returned to the model as a tool error within the same step budget. |
| FR-008-11 | Accepted advice shall be stored per number for 24 h by default, in the requested language (`en` or `de`; others fall back to `en`). |
| FR-008-12 | An advisor run shall be limited to 6 model turns and 400 output tokens per turn; one run per number at a time (lease 3 min); 5 runs per user per day. |
| FR-008-13 | `GET` / `PUT /v1/consents/{purpose}` shall read or set `personalization` and `analytics` consent; absence means not granted; an unknown purpose is 400. |

## Non-functional requirements
| Id | Requirement |
|---|---|
| NFR-008-1 | The model is called only by the backend, through a guard (concurrency 10, timeout 20 s, circuit breaker); the API key never leaves the server. With `ADVISOR_LLM=none` the agent is off and the rules answer. |
| NFR-008-2 | Text from partner feeds (plan names, benefits) shall be cleaned before the model sees it, so a feed cannot inject instructions. |
| NFR-008-3 | The agent packages shall not depend on payment, provisioning, notification or `OrderTransitions` (enforced by `ArchitectureTest`). |
| NFR-008-4 | Model usage shall be counted: runs by outcome and tokens in/out. |

## Acceptance scenarios
### AS-1 Consent gates personal data
- **Given** a number with heavy usage and no consent
- **When** a recommendation is requested; then consent is granted and it is requested again
- **Then** first the middle plan with `personalized=false`; then a usage-based pick with `personalized=true`
- **Test:** `RechargeFlowIT#recommendations_respectConsent`, `RecommendationServiceTest#withoutConsentPersonalDataIsNeverRead`

### AS-2 Rules first, agent next
- **Given** consent and the scripted model
- **When** the first and a later request are made
- **Then** the first is answered by the rules and starts a run; the later one is `AI_ADVISOR` with an explanation
- **Test:** `AdvisorIT#theFirstRequestIsAnsweredByTheRulesAndTheNextOneByTheAgent`

### AS-3 Withdrawn consent removes advice
- **Given** stored advice
- **When** consent is withdrawn and a recommendation is requested
- **Then** the agent is never asked and the advice is deleted
- **Test:** `AdvisorIT#withoutConsentTheAgentNeverRunsAndWithdrawingConsentRemovesItsAdvice`

### AS-4 The model is untrusted
- **Given** a model that names a plan not on sale, skips the data, answers in prose, adds a link, or loops
- **When** the agent runs
- **Then** each answer is rejected (or the step budget ends the run); nothing reaches the customer
- **Test:** `RechargeAdvisorAgentTest#aPlanThatIsNotOnSaleIsRejectedAndTheModelCanCorrectItself`, `#anAnswerGivenWithoutLookingAtTheCustomersDataIsNotAccepted`, `#anExplanationWithALinkIsRejected`, `#theStepBudgetStopsAModelThatNeverFinishes`, `#toolArgumentsFromTheModelCannotSelectAnotherCustomer`

### AS-5 Usage fit with headroom
- **Given** DE plans S-28 (3 GB), M-28 (10 GB) and L-28 (30 GB), consent, and average usage of 9,500 MB
- **When** the rules run
- **Then** L-28 with reason `USAGE_FIT`: 9,500 MB + 10% = 10,450 MB, which M-28 does not cover; the cheapest plan is offered as the cheaper alternative
- **Test:** `RecommendationRulesTest#headroomPushesBorderlineUsageToTheNextPlan`, `#alwaysOffersTheCheapestPlanAsAnAlternative`

## Edge cases and errors
| Situation | Result |
|---|---|
| Model down or slow | Counted as `model_unavailable`; rules answer |
| Advice for a plan that left the catalog | Not served |
| User exceeds daily runs | No model call; rules answer |

## Out of scope
- Training or fine-tuning models; recommendations for users without a linked number.

## Traceability
| Requirement | Code | Tests |
|---|---|---|
| FR-008-1..8 | `RecommendationController`, `RecommendationService`, `RecommendationRules` | `RecommendationServiceTest`, `RecommendationRulesTest`, `RechargeFlowIT` |
| FR-008-9..12 | `AdvisorService`, `RechargeAdvisorAgent`, `AdvisorToolbox`, `StoredAdvice`, `LlmGateway` | `AdvisorServiceTest`, `RechargeAdvisorAgentTest`, `AdvisorIT` |
| FR-008-13 | `ConsentController`, `ConsentService` | `RechargeFlowIT#recommendations_respectConsent` |
| NFR-008-1 | `OpenAiCompatibleLlmClient`, `ScriptedLlmClient`, `LlmGateway` | `OpenAiCompatibleLlmClientTest`, `ScriptedLlmClientTest` |
