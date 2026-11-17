# 002 — Linked phone numbers

**Status:** Implemented · **Owner:** number · **Constitution:** IV.1, IV.2, IV.4, V.1

## Summary
A customer links the phone numbers they want to recharge. A number is the unit of ownership: every order is for
a number the caller linked, and the number itself is stored only encrypted.

## User stories
- **US-1** As a customer, I want to link my number (and family members' numbers), so that I can recharge them.
- **US-2** As a customer, I want my number never to be visible in clear outside the system, so that it cannot
  leak.

## Functional requirements
| Id | Requirement |
|---|---|
| FR-002-1 | When an authenticated user calls `POST /v1/numbers` with `msisdn`, the system shall normalize it (remove spaces, dashes, dots, brackets) and require E.164 form: `+` and 8 to 15 digits, first digit not 0. |
| FR-002-2 | The system shall derive the market from the dialling prefix (`+420` CZ, `+49` DE, `+48` PL, `+43` AT, `+31` NL); if the prefix is not supported, then it shall respond 400 `REQUEST_INVALID`. |
| FR-002-3 | When the user links a number they already linked, the system shall return the existing record (idempotent), even for concurrent requests. |
| FR-002-4 | If the user already has the maximum number of linked numbers (default 3), then the system shall respond 422 `NUMBER_LIMIT_EXCEEDED`. |
| FR-002-5 | The system shall return a number as `{id, msisdnMasked, market}`; the masked form keeps the country code and last 4 digits (`+49 *** ***4567`). |
| FR-002-6 | `GET /v1/numbers` shall return the caller's numbers, oldest first. |
| FR-002-7 | When another module asks for a number by (id, user) and it belongs to someone else, the system shall respond 404 `RESOURCE_NOT_FOUND`, identical to a missing number. |
| FR-002-8 | The plain number shall be available only to internal callers (provisioning, SMS) and never through an API. |

## Non-functional requirements
| Id | Requirement |
|---|---|
| NFR-002-1 | The number shall be stored AES-256-GCM encrypted, with a fresh IV per value and the key id in the first byte. |
| NFR-002-2 | Duplicate detection shall use an HMAC-SHA256 hash of the normalized number, never the clear value. |
| NFR-002-3 | The request body `msisdn` shall be at most 24 characters. |

## Acceptance scenarios
### AS-1 Link a number
- **Given** an authenticated user with no numbers
- **When** they link `+49 170 1234567`
- **Then** 200 with `market = DE`, `msisdnMasked = +49 *** ***4567`, and the stored value is encrypted
- **Test:** `NumberServiceTest#linksNewNumberEncryptedAndMasked`, `MsisdnTest#normalizesSeparators`, `MsisdnTest#maskHidesTheMiddle`

### AS-2 Linking twice is idempotent; the limit applies
- **Given** a user with 3 linked numbers
- **When** they link one of them again, then a fourth number
- **Then** the first returns the existing record; the second is 422 `NUMBER_LIMIT_EXCEEDED`
- **Test:** `NumberServiceTest#linkingTheSameNumberAgainReturnsTheExistingOne`, `#enforcesPerUserLimit`, `RechargeFlowIT#numberLimitAndValidation`

### AS-3 Someone else's number looks like no number
- **Given** a number linked by user A
- **When** user B orders on it
- **Then** 404 `RESOURCE_NOT_FOUND`
- **Test:** `NumberServiceTest#notOwnedLooksLikeNotFound`, `RechargeFlowIT#otherUsersCannotSeeOrPayOrOrderOnSomeoneElsesNumber`

## Edge cases and errors
| Situation | Result |
|---|---|
| `0170` (not international) | 400 `REQUEST_INVALID` |
| Unsupported country | 400 `REQUEST_INVALID` |
| Two parallel links of the same number | One record; both requests get it |

## Out of scope
- Proving the caller owns the SIM (OTP or SIM check). Linking is currently unverified; see SDD open items.

## Traceability
| Requirement | Code | Tests |
|---|---|---|
| FR-002-1..2, 5 | `Msisdn` | `MsisdnTest` |
| FR-002-3..8 | `NumberService`, `NumberController`, `SubscriberNumberRepository` | `NumberServiceTest`, `RechargeFlowIT` |
| NFR-002-1..2 | `PiiCrypto` | `PiiCryptoTest` |
