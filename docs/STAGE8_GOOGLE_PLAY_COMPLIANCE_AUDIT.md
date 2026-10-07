# QALQON — Stage 8: Google Play Compliance Audit

> Commercial Master Plan — Stage 8 deliverable.
> Baseline: `feature/phase4-screen-time-complete` @
> `116be74eb7037649175846c57fb96129714d5804` (Stage 7).
>
> **Honesty contract.** This audit is static (source + merged manifest) plus the
> build. It cannot and does not verify the **Play Console configuration** or the
> **real Google review**. Anything that requires Play Console access or a real
> device/license tester is marked **NOT VERIFIED**. No URL, Play Console status or
> Google approval is invented.

---

## 1. Official sources used

| Topic | Source | Section |
|---|---|---|
| Target API level | `support.google.com/googleplay/android-developer/answer/11926878` | Target API level requirements |
| Target API (dev) | `developer.android.com/google/play/requirements/target-sdk` | Meet Google Play's target API level requirement |
| AGP ↔ API level | `developer.android.com/build/releases/about-agp` | Minimum versions of tools for Android API level |
| AGP ↔ Gradle | `developer.android.com/build/releases/about-agp` | AGP / Gradle compatibility |
| Accessibility API | `support.google.com/googleplay/android-developer/answer/16558241` | Permissions and APIs that access sensitive information → Accessibility API |
| Accessibility API (detail) | `support.google.com/googleplay/android-developer/answer/10964491` | Use of the AccessibilityService API |
| User data | `support.google.com/googleplay/android-developer/answer/10144311` | User Data policy |
| Account deletion | `support.google.com/googleplay/android-developer/answer/13463537` | Account Deletion Requirement |
| Data safety | `support.google.com/googleplay/android-developer/answer/10787469` | Data safety section; "Collect" definition |
| Developer Program Policy | `support.google.com/googleplay/android-developer/answer/18258653` | Accessibility API guidelines |

---

## 2. Target API level — was **P0**, now fixed in code

**Policy (verified):** from **August 31, 2026**, new apps and app updates must
target **Android 16 (API level 36)**. An extension is available **only to
November 1, 2026**, and only for apps that are not yet compliant (form in Play
Console). A *new* app cannot ship on API 35 after the deadline.

**QALQON baseline:** `compileSdk = 35`, `targetSdk = 35` → **non-compliant**.

**Feasibility (verified):** `developer.android.com` requires **AGP ≥ 8.9.1** to
compile API 36. The project was on **AGP 8.7.2 / Gradle 9.7.1**. A direct AGP bump
to 8.13.2 failed at configuration time: *"Plugin 'com.android.internal.application'
relies on 'org.gradle.api.problems.internal.InternalProblems', a Gradle internal API
that was removed in Gradle 9.6.0. … use Gradle 9.5."* AGP 8.x is incompatible with
Gradle ≥ 9.6.

**Fix applied (code):**
- AGP `8.7.2` → **`8.13.2`** (max API 36.1; R8 8.13.19 supports Kotlin 2.3).
- Gradle wrapper `9.7.1` → **`9.5.1`** (Gradle-recommended for AGP 8.x; below the 9.6 removal).
- `compileSdk` `35` → **`36`**; `targetSdk` `35` → **`36`**.

**Verification:** `:app:compileDebugKotlin`, `:app:testDebugUnitTest` (1891 tests,
0 failures) and `:app:assembleDebug` all pass; the merged debug manifest reports
`targetSdkVersion="36"`. KNOWN-EFFECT: Kotlin/KSP/Compose/Hilt/Room/CameraX/ML Kit/
TFLite/Billing 9.1.0 all compile unchanged.

**Status: PASS (code) / NOT VERIFIED (Play Console acceptance).**

---

## 3. Manifest / permissions

Source: merged manifest + `app/src/main/AndroidManifest.xml`. No INTERNET, no
ACCESS_NETWORK_STATE, no QUERY_ALL_PACKAGES, no location (verified in the merged
manifest after the API 36 migration).

| Permission | Why | Runtime/special | Play risk | In code | Status |
|---|---|---|---|---|---|
| `CAMERA` | face enrollment + recognition | runtime | Medium (sensitive) | Yes | Keep |
| `PACKAGE_USAGE_STATS` | foreground-app detection | special access | Medium (Play review) | Yes | Keep + declaration |
| `SYSTEM_ALERT_WINDOW` | fallback blocking overlay | special access | Medium (Play review) | Yes | Keep + declaration |
| `FOREGROUND_SERVICE` + `_SPECIAL_USE` + `_CAMERA` | keep protection alive | normal | Medium (FGS review) | Yes | Keep + declaration |
| `POST_NOTIFICATIONS` | parent-facing status/requests | runtime (33+) | Low | Yes | Keep |
| `RECEIVE_BOOT_COMPLETED` | restore protection after reboot | normal | Low | Yes | Keep |
| `USE_BIOMETRIC` / `USE_FINGERPRINT` | OS prompt for parent UI | normal | Low | Yes | Keep |
| `com.android.vending.BILLING` | subscription | normal (injected) | Medium | Yes (Billing 9.1.0) | Keep |
| `INTERNET`, `ACCESS_NETWORK_STATE` | — | — | — | removed via `tools:node="remove"` | Not granted |
| `QUERY_ALL_PACKAGES` | — | — | — | absent (targeted `<queries>`) | Not granted |

No unnecessary permission found; none removed (each maps to a real core feature).

---

## 4. Sensitive data — the Data Safety "collection" definition

**Policy (verified):** Data safety *"’Collect’ means transmitting data from your
app off a user’s device."* On-device-only processing is **not** "collection" for
the Data safety form. Libraries/SDKs that transmit off-device would count.

**QALQON behavior (Stage 4 verified, re-verified here):** no INTERNET permission;
on-device face detection/embedding/matching (bundled ML Kit model + TFLite from
assets); ML Kit's transitive `datatransport`/`firebase-encoders` upload
service/receiver removed from the merged manifest; no analytics/crash/telemetry
SDK; account (name, phone) and all data stored **locally only**.

**Consequence:** the Data safety form should declare **no data collected / no data
shared** (the app transmits nothing off-device). Face/biometric data is processed
**on-device only** and never transmitted. This must be stated consistently in the
form, the privacy policy and the listing.

**Status: PASS (code) / Play Console form NOT VERIFIED.**

---

## 5. Face / biometric data lifecycle (policy perspective)

Camera frame → ML Kit face detection → crop → MobileFaceNet embedding →
AES-256-GCM (Keystore) encrypted template → Room (`faceTemplateRef`) → recognition
→ deletion. Raw frames/embeddings never persisted; templates encrypted at rest;
device-bound key; never transmitted (no INTERNET).

- **Collected?** No (never transmitted off-device).
- **Shared?** No.
- **Processed on-device only?** Yes.
- **Stored?** Yes — encrypted at rest, local only.
- **Deleted?** Yes (per-subject face delete; full reset deletes the key).

**Status: PASS (code).** Must be mirrored in the privacy policy + Data safety.

---

## 6. Privacy policy

**Requirement (verified):** apps that collect personal/sensitive data must post a
privacy policy **in Play Console and within the app**. QALQON has an **in-app**
privacy explainer (`feature/privacy/PrivacyScreen.kt`), but there is **no hosted
privacy-policy URL** and the in-app screen is a short explainer, not a full
policy.

**Status: PARTIAL / BLOCKED for submission** — a **public HTTPS privacy-policy URL**
is required in Play Console, and the in-app screen should link to it. QALQON
cannot invent a URL; hosting is a developer action. See the Play Console action
items doc. (Do not claim legal compliance.)

---

## 7. Account deletion — **P0 BLOCKER (external resource)**

**Requirement (verified):** apps that allow account creation must provide deletion
**both in-app and via an external web resource**, and must delete **all**
associated data (freezing is not sufficient). Retention must be disclosed.

- **In-app deletion:** EXISTS. `ResetRepository.resetAll()` deletes the account and
  every associated table, wipes settings/session/security DataStore, and deletes
  the Android Keystore key (Stage 4 verified and tested). Framed as "delete all
  local data" (the app has a single local account, so this is account deletion).
- **External web resource:** **MISSING.** No hosted account-deletion URL exists.

**Status: in-app PASS (code); external web resource MISSING → BLOCKED for
submission.** Requires a hosted URL + Play Console declaration (developer action;
cannot be fabricated).

---

## 8. AccessibilityService — disclosure fixed in code

**Requirement (verified):** Google Play permits the AccessibilityService API for
parental control (the policy explicitly allows "prevent the ability … unless
authorised by a parent or guardian through a **parental control app**"). Only
disability-support apps may set `isAccessibilityTool=true`; all other apps must
(a) complete an **accessibility declaration** in Play Console, (b) implement a
**clear in-app disclosure**, (c) obtain **affirmative user consent**, and
(d) document the API use **in the Play listing**.

**QALQON state:**
- `isAccessibilityTool` is **not** set (correct — not a disability tool).
- `canRetrieveWindowContent="false"` (privacy-preserving); only
  `typeWindowStateChanged|typeWindowsChanged` are consumed; only the package name
  is read.
- **Previously missing:** tapping "enable" launched system settings with **no
  in-app disclosure/consent**.

**Fix applied (code):** the Protection screen now shows a **disclosure dialog**
(what the service does + exactly what data it accesses + on-device only) with an
explicit **"I agree — open settings"** confirm; the settings intent is launched
only after that affirmative action. Strings added in uz/en/ru.

**Status: CODE PASS (disclosure + consent now present).** The **Play Console
accessibility declaration** and the **listing documentation** (and, if requested,
a **demo video**) remain **NOT VERIFIED — Play Console action required**.

---

## 9. Usage Access (`PACKAGE_USAGE_STATS`)

Used only to detect which app is in the foreground, for protection. No historical
app-usage aggregation beyond screen-time accounting (local). No installed-app
inventory is collected for its own sake (the picker enumerates launcher apps
locally via a targeted `<queries>`). **Status: PASS (code); Play declaration
recommended.**

## 10. Overlay (`SYSTEM_ALERT_WINDOW`)

A **fallback** visual blocking scrim (`TYPE_APPLICATION_OVERLAY`, `FLAG_NOT_TOUCHABLE`);
the real input block is the `TYPE_ACCESSIBILITY_OVERLAY` owned by the accessibility
service. Uses are user-visible and non-deceptive; screenshot-protected (`FLAG_SECURE`).
**Status: PASS (code); Play declaration recommended.**

## 11. Foreground Service

`ProtectionForegroundService` with `camera|specialUse`; the `specialUse` subtype is
declared (`PROPERTY_SPECIAL_USE_FGS_SUBTYPE=parental_control_protection_state`); the
`camera` type is claimed only in a legal while-in-use moment; an ongoing
low-importance, user-visible notification is shown; `START_NOT_STICKY`. **Status:
PASS (code); Play Console FGS declaration + justification required.**

## 12. Notifications

Dedicated low-importance channel; `POST_NOTIFICATIONS` requested from the Requests
screen; denial suppresses display only (protection still runs). Localized channel
name/description. **Status: PASS (code).**

## 13. Child-related functionality (Families policy)

QALQON is a **parental-control app used by a parent/guardian**, not a
child-directed app: it is not "designed for children", it has no child accounts,
no ads, no third-party analytics, and the child has no account of their own — the
child's face template is enrolled by the parent. The **target audience**
declaration and the Families policy applicability must be chosen in Play Console
as **"parental control / not designed for children"**, with the correct content
rating. This is a **Play Console decision (NOT VERIFIED)**, not derivable from code
alone.

## 14. Subscription / billing compliance

Existing Stage 7 implementation unchanged (this stage does **not** rewrite billing).
The purchase screen discloses trial length (3 days), the Play-provided monthly price,
billing frequency, auto-renewal, the first-charge point, cancellation and a
Manage-subscription path; prices are dynamic (never hardcoded — pinned by test).
**Status: CODE PASS.** The real Play Console product/base-plan/trial configuration
and the real purchase lifecycle are **NOT VERIFIED — Play Console + license tester
required.** No "fully compliant" claim is made from code alone.

## 15. App Content / store listing / screenshots / reviewer access

All are **Play Console / asset** items, not code. QALQON has **no store listing,
screenshots, feature graphic or hosted URLs** in the repository. Any that claim
"100% secure", "unbypassable", "anti-spoofing", "works on every device" would be
**unsupported** (Stage 3/5 limits) and must not be used. See the Play Console
action items doc.

## 16. Data Safety ↔ Privacy Policy ↔ App behavior cross-check

| Data | Code behavior | Data safety (should say) | Privacy policy (should say) | Status |
|---|---|---|---|---|
| Name | local only | Not collected | local only | Consistent |
| Phone number | local only | Not collected | local only | Consistent |
| PIN | local hash only | Not collected | never stored raw | Consistent |
| Face template | on-device, encrypted | Not collected | on-device, encrypted | Consistent |
| Camera frames | on-device, ephemeral | Not collected | not stored/transmitted | Consistent |
| Foreground app (package) | local only | Not collected | local only | Consistent |
| App activity (activity log) | local only | Not collected | local only | Consistent |
| Purchase data | Google Play (Billing) | handled by Play | payment via Play | Consistent |
| Device identifiers | none used for tracking | Not collected | none | Consistent |

No discrepancy found **in code**; the Play Console form and the hosted policy must
be written to match.

## 17. Third-party SDKs

Billing 9.1.0, CameraX 1.4.1, ML Kit face-detection 16.1.7, TFLite 2.16.1, Hilt,
Room, Compose, DataStore, Accompanist. ML Kit's transitive transport/telemetry
components remain removed from the merged manifest (re-verified: no transport
service/receiver, no INTERNET). No analytics/crash SDK. **Status: PASS (code).**

## 18. Network / offline

No INTERNET/ACCESS_NETWORK_STATE in the merged manifest; no OkHttp/Retrofit/
WebView/Firebase; no remote model download. Billing uses Play IPC (not the
network permission). **Status: PASS (code).**

## 19. Backup / data extraction

`allowBackup="false"` + `dataExtractionRules` excluding every domain from
cloud-backup and device-transfer (Stage 4). Consistent with "no off-device data".
**Status: PASS (code).**

---

## 20. Findings

| ID | Title | Severity | Category | Status |
|---|---|---|---|---|
| S8-1 | targetSdk 35 < required API 36 | **P0** | Code | **FIXED** (AGP 8.13.2 + Gradle 9.5.1 + API 36) |
| S8-2 | No external web account-deletion resource | **P0** | Play Console / hosting | **BLOCKED** (developer action) |
| S8-3 | No hosted privacy-policy URL (and no in-app link) | **P1** | Play Console / hosting | **BLOCKED** (developer action) |
| S8-4 | No in-app accessibility disclosure/consent | **P1** | Code | **FIXED** (disclosure dialog + consent) |
| S8-5 | Play Console accessibility declaration / listing doc / demo video | **P1** | Play Console | **NOT VERIFIED** (action required) |
| S8-6 | Data safety form not submitted | **P1** | Play Console | **NOT VERIFIED** (action required) |
| S8-7 | Target audience / Families / content rating not declared | **P1** | Play Console | **NOT VERIFIED** (action required) |
| S8-8 | Subscription Play Console config + real purchase untested | **P1** | Play Console + device | **NOT VERIFIED** |
| S8-9 | Store listing / screenshots / feature graphic absent | **P2** | Assets | **ACTION REQUIRED** |
| S8-10 | Reviewer access (test account/instructions) not prepared | **P2** | Play Console | **ACTION REQUIRED** |
| S8-11 | In-app account deletion is worded as "delete all data" | **P3** | Code/copy | Documented (deletes the account; explicit "Delete account" wording is a polish item) |

## 21. Fixed in this stage

1. **S8-1** — API 36 migration (AGP, Gradle, compileSdk, targetSdk). Regression
   tests: `PlayComplianceContractTest` (target API + toolchain).
2. **S8-4** — Accessibility disclosure + affirmative consent before enabling the
   service. Regression tests: `PlayComplianceContractTest` (disclosure + consent +
   no-`isAccessibilityTool` + `canRetrieveWindowContent=false`).

## 22. Not verified (requires Play Console / device / license tester)

Play Console configuration, privacy-policy URL, external account-deletion URL,
Data safety form, accessibility declaration, target audience/content rating,
subscription product config, real Google purchase lifecycle, real review, real
license tester, store listing review.

---

## 23. Verdict

**PASS WITH LIMITATIONS.** All **code-side** Play compliance requirements checked
are satisfied (API 36 target, no unnecessary permissions, no off-device data, no
`isAccessibilityTool`, accessibility disclosure+consent, subscription disclosures).
**Submission is BLOCKED** on non-code items: the external account-deletion web
resource (**P0**) and the hosted privacy-policy URL (**P1**), plus the Play Console
declarations (Data safety, accessibility, target audience, content rating,
subscription config) and store assets. These cannot be completed from the
repository and are listed in `STAGE8_PLAY_CONSOLE_ACTION_ITEMS.md`.
