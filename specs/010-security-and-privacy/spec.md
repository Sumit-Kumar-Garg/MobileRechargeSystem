# 010 — Authentication, authorization and PII

**Status:** Implemented · **Owner:** security, crypto · **Constitution:** IV.1–4, VII.2

## Summary
Every request is authenticated with a JWT issued by the company identity provider; the user id comes only from
the token. Resources are checked against their owner, and personal data is encrypted at rest and never leaves
the database in clear form.

## User stories
- **US-1** As a customer, I want nobody else to see or act on my numbers and orders.
- **US-2** As the security team, I want production tokens verified with the IdP's keys and personal data
  protected even if the database leaks.

## Functional requirements
| Id | Requirement |
|---|---|
| FR-010-1 | The system shall require a valid bearer JWT on every endpoint except `GET /v1/plans`, `/v1/demo/**` (demo profile only), the API docs, and health, info and metrics on the management port. |
| FR-010-2 | When `JWKS_URI` is set, the system shall verify tokens against the IdP's public keys; otherwise it shall use the shared HS256 secret (demo and tests only; ≥ 32 bytes; a warning is logged). |
| FR-010-3 | The system shall check the issuer, and the audience when `JWT_AUDIENCE` is set. |
| FR-010-4 | The user id shall be the token subject parsed as a UUID; a non-UUID subject is 401 `UNAUTHENTICATED`. User ids in request bodies or parameters shall never be used. |
| FR-010-5 | Missing or invalid tokens shall get 401 `UNAUTHENTICATED`; forbidden access 403 `FORBIDDEN`; both as problem+json. |
| FR-010-6 | Another user's number or order shall answer 404 `RESOURCE_NOT_FOUND`, the same as a missing one. |
| FR-010-7 | Phone numbers, emails and push tokens shall be encrypted with AES-256-GCM, a fresh 12-byte IV per value, layout `[key id][IV][ciphertext+tag]`. |
| FR-010-8 | Data encrypted with an earlier key shall stay readable while that key is listed as a previous key; a removed key can no longer decrypt. Key ids are 1 to 255 and must be distinct. |
| FR-010-9 | Lookups and pseudonymous ids shall use HMAC-SHA256 with a separate key of at least 32 bytes. |
| FR-010-10 | The system shall refuse to start without valid keys outside the demo profile. |
| FR-010-11 | `POST /v1/demo/token` shall exist only in the demo profile and issue a 1-hour token for a given or random user id. |

## Non-functional requirements
| Id | Requirement |
|---|---|
| NFR-010-1 | The API is stateless: no server sessions, no cookies, CSRF protection off for the bearer-only API. |
| NFR-010-2 | Responses never contain stack traces, exception messages of unexpected errors, or rejected input values. |
| NFR-010-3 | Logs, metrics, Kafka events and analytics contain only masked numbers and pseudonymous ids. |

## Acceptance scenarios
### AS-1 Authentication required
- **Given** no token
- **When** any protected endpoint is called
- **Then** 401 `UNAUTHENTICATED` as problem+json
- **Test:** `RechargeFlowIT#protectedEndpointsRequireAuthentication`, `PlanControllerIT#unauthenticatedRouteIsRejectedWith401Problem`

### AS-2 Ownership
- **Given** user A's number and order
- **When** user B reads, pays or orders on them
- **Then** 404 each time and A's order is unchanged
- **Test:** `RechargeFlowIT#otherUsersCannotSeeOrPayOrOrderOnSomeoneElsesNumber`, `RechargeFlowIT#recommendationForSomeoneElsesNumberIsNotFound`

### AS-3 Encryption and key rotation
- **Given** a value encrypted with key 1
- **When** key 2 becomes current and key 1 is listed as previous; then key 1 is removed
- **Then** the value is readable; then it is not; tampered ciphertext is always rejected
- **Test:** `PiiCryptoTest#dataEncryptedWithAnOldKeyIsStillReadableAfterRotation`, `#anOldKeyThatWasRemovedCanNoLongerDecrypt`, `#tamperedCiphertextIsRejected`

## Edge cases and errors
| Situation | Result |
|---|---|
| Weak or missing keys | Startup fails with a message naming the property |
| HMAC key change | Needs a re-hash migration (stored hashes are lookup keys) |

## Out of scope
- The identity provider itself, user registration and login.

## Traceability
| Requirement | Code | Tests |
|---|---|---|
| FR-010-1..5 | `SecurityConfig`, `CurrentUser`, `ProblemSecurityHandlers` | `RechargeFlowIT`, `PlanControllerIT` |
| FR-010-6 | `NumberService#requireOwned`, `OrderStore#findByIdAndUserId` | `NumberServiceTest#notOwnedLooksLikeNotFound`, `OrderServiceTest#getReturnsOnlyOwnOrders` |
| FR-010-7..10 | `PiiCrypto`, `CryptoProperties` | `PiiCryptoTest` |
| FR-010-11 | `DemoAuthController` | integration tests use `TestJwt` |
