# QALQON — Google Play Data Safety (answer sheet + technical matrix)

> **Status: PREPARED — NOT SUBMITTED.** Play Console → App content → Data safety is
> **NOT SUBMITTED** and **NOT VERIFIED** (no Play Console access here). This sheet is
> derived from the real code behavior (Stages 4–10) and matches `PRIVACY_POLICY.md`.

**Official definition (verbatim, verified 2026-10-07):**
> "'Collect' means transmitting data from your app off a user's device."
> — https://support.google.com/googleplay/android-developer/answer/10787469

QALQON has **no Internet permission** and transmits nothing off the device, so **no data
type is "collected" or "shared"** for Data safety purposes. On-device-only processing and
storage are **not** collection.

## Global answers
| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | **No** |
| Is all user data collected by your app encrypted in transit? | N/A (nothing transmitted) |
| Do you provide a mechanism for users to request data deletion? | **Yes** (in-app + external web resource) |

## Per-data-type matrix (Play Data safety semantics)
| Data type | Collected (sent off device)? | Shared? | Purpose | Required | On-device only | Encrypted at rest | Deletion |
|---|---|---|---|---|---|---|---|
| Name | No | No | Account/profile | Required | Yes | Yes (device) | In-app + web |
| Phone number | No | No | Account login | Required | Yes | Yes (device) | In-app + web |
| Authentication info (PIN) | No | No | Unlock parent UI | Required | Yes | Hash only (PBKDF2) | In-app + web |
| Face / biometric data (template) | No | No | Recognition | Required | Yes | AES-256-GCM (Keystore) | In-app + web |
| Photos/videos (camera frames) | No | No | Recognition | — | Yes (ephemeral) | Not persisted | n/a |
| Child profile information | No | No | Parental rules | Required | Yes | Yes (device) | In-app + web |
| App activity (activity log, screen-time) | No | No | Show parent | — | Yes | Yes (device) | In-app + web |
| App interactions (foreground package) | No | No | Foreground detection | — | Yes (in-memory) | Not persisted | n/a |
| Device or other IDs | No | No | — | — | — | — | — |
| Diagnostics / crash logs | No | No | — | — | — | — | — |
| Purchase / subscription info | No (app) | No | Premium | Optional | Play holds it | Device cache only | In-app |

**Purpose mapping:** the Play form's purposes would be *App functionality* only (nothing
is used for advertising, analytics or personalisation).

## Third-party SDKs
Billing 9.1.0 (Play IPC), CameraX 1.4.1, ML Kit face-detection 16.1.7, TFLite 2.16.1,
Hilt, Room, Compose, DataStore, Accompanist. **None transmit user data off-device in this
app** (no INTERNET; ML Kit's transitive `datatransport` services/receiver are removed from
the merged manifest). Google Play Billing interacts with Google Play over IPC; the
purchase is a Play transaction governed by Google's terms, not data transmitted by the app.

## Consistency check
This matrix **matches** `PRIVACY_POLICY.md`, `PERMISSION_DISCLOSURES.md` and the real code
(verified by `PlayComplianceContractTest`, `Stage10DataExposureTest`,
`Stage10DataLifecycleAndSurfaceTest`). No discrepancy.

## Status
- **PREPARED:** YES · **SUBMITTED:** NO (Play Console access required) · **VERIFIED:** NO
- Any ambiguity in Play's form wording: **POLICY INTERPRETATION NEEDS MANUAL REVIEW**.
