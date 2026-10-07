# QALQON — Data Safety Answer Sheet

> Stage 8 remediation. This is the developer's answer sheet for Play Console →
> **App content → Data safety**. It reflects QALQON's **real, verified behavior**
> (Stages 4–8). The form is **NOT SUBMITTED** (no Play Console access) — see §Status.

**Official definition (verbatim):**
> "'Collect' means transmitting data from your app off a user's device."
> — support.google.com/googleplay/android-developer/answer/10787469

Because QALQON has **no Internet permission** and transmits nothing off the device,
**no data type is "collected" or "shared"** for Data safety purposes. On-device-only
processing and storage are **not** collection.

---

## Global answers
| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | **No** |
| Is all of the user data collected by your app encrypted in transit? | N/A (nothing transmitted) |
| Do you provide a way for users to request that their data be deleted? | **Yes** (in-app + external web, see Account Deletion doc) |

## Per-data-type answer sheet

| Data type | Collected? | Shared? | Optional/Required | Purpose | Ephemeral? | Encrypted at rest? | Deletion available? | Retention | Evidence / justification |
|---|---|---|---|---|---|---|---|---|---|
| Name | No | No | — | — | — | Yes (device) | Yes | Until deletion (on-device) | `UserAccountEntity.fullName`, `ParentProfileEntity.displayName` — local Room only |
| Phone number | No | No | — | — | — | Yes (device) | Yes | Until deletion (on-device) | `UserAccountEntity.phoneNumber` — local Room only |
| Authentication information (PIN) | No | No | — | — | — | Yes (hash only) | Yes | Until deletion | PIN stored as PBKDF2 hash; never transmitted |
| Face / biometric data (face template) | No | No | Required for recognition | Recognition | No | Yes (AES-256-GCM, Keystore) | Yes | Until deletion | On-device only; never transmitted |
| Camera frames | No | No | — | Recognition | Yes (in-memory per frame) | N/A (not persisted) | N/A | Not stored | `FaceCaptureController` — frames in memory only |
| Child profile information (name, level) | No | No | Required | Parental rules | No | Yes (device) | Yes | Until deletion | `ChildProfileEntity` — local Room only |
| App activity (activity log, screen-time) | No | No | — | Show parent what happened | No | Yes (device) | Yes | Until deletion | Room `activity_events`, `daily_app_usage` — local only |
| App interactions | No | No | — | Foreground detection | Yes | N/A | N/A | Not stored | Foreground package name in memory |
| Device or other IDs | No | No | — | — | — | — | — | — | No identifiers collected/transmitted |
| Diagnostics / crash data | No | No | — | — | — | — | — | — | No analytics/crash SDK |
| Purchase / subscription information | No (app) | No | Optional | Premium | — | Device cache only | Yes | Until deletion | Google Play Billing handles the purchase; app caches status only |

## Third-party SDKs
Billing 9.1.0, CameraX 1.4.1, ML Kit face-detection 16.1.7, TFLite 2.16.1, Hilt, Room,
Compose, DataStore, Accompanist. **None transmit user data off-device in this app**:
- ML Kit's transitive telemetry (`datatransport`) services/receiver are removed from the
  merged manifest and it has no INTERNET.
- **Google Play Billing** interacts with Google Play (Play Store IPC); the purchase is a
  Play transaction governed by Google's terms, not data transmitted by the app.

## Consistency check
Data safety answers here **match** the Privacy Policy
(`STAGE8_PRIVACY_POLICY_HOSTING.md`) and the real code behavior. No discrepancy.

## Status
- **PREPARED:** YES (this sheet)
- **SUBMITTED:** NO — `MANUAL ACTION REQUIRED` (Play Console access required)
- **VERIFIED:** NO — cannot be verified without Play Console
