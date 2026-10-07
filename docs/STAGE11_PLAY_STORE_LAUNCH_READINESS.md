# QALQON — Stage 11: Play Store & International Launch Readiness

> Stage 11 (roadmap 11/12). Goal: bring QALQON to a **technically and documentarily
> release-ready** state for Google Play and international launch.
>
> **"Prepared" ≠ "Submitted" ≠ "Approved".** These three states are kept separate
> everywhere below. No Play Console access, no device, and no license tester are available
> here, so submission/approval and real purchase are **NOT VERIFIED**.

---

## 1. Policy verification (online, official sources only)

Web access was available; the following were fetched from Google's official pages on
**2026-10-07**. Quotes are verbatim from the cited URLs.

| Topic | Verified fact | Source |
|---|---|---|
| Target API level | New apps and updates must target Android 16 (API 36); deadline **2026-08-31**, extension to 2026-11-01 | https://support.google.com/googleplay/android-developer/answer/11926878 |
| Data safety "collect" | "'Collect' means transmitting data from your app off a user's device." | https://support.google.com/googleplay/android-developer/answer/10787469 |
| Account deletion | "This option must be available both from within your app and externally through a designated web resource." | https://support.google.com/googleplay/android-developer/answer/10144311 |
| Accessibility (not a tool) | "you will be required to complete an accessibility declaration in Play Console." and the disclosure "Must require affirmative user action for consent …" | https://support.google.com/googleplay/android-developer/answer/10964491 |

QALQON: `compileSdk = 36`, `targetSdk = 36` → **compliant** with the target API requirement.
Policy pages change over time — **re-verify current Play Console UI** during submission.

---

## 2. Release artifact

| Item | Result |
|---|---|
| `:app:assembleRelease` (signed, R8) | ✅ builds (Stage 10, throwaway /tmp key) |
| `:app:bundleRelease` (AAB) | ✅ **BUILD SUCCESSFUL** — `app/build/outputs/bundle/release/app-release.aab` (36 MB) |
| AAB contents | TFLite model + bundled models, R8 `proguard.map`/`r8.json`, baseline profile |
| AAB permissions (protobuf) | CAMERA, PACKAGE_USAGE_STATS, SYSTEM_ALERT_WINDOW, FGS(+CAMERA,+SPECIAL_USE), POST_NOTIFICATIONS, RECEIVE_BOOT_COMPLETED; **INTERNET 0, ACCESS_NETWORK_STATE 0, location 0** |
| R8 / minification | ✅ enabled for release (Stage 10) |
| versionCode / versionName | `1` / `0.1.0` (overridable via `-PqalqonVersionCode` / `-PqalqonVersionName`) |
| applicationId | `uz.faceguard.app` |
| **RELEASE SIGNING / PLAY UPLOAD SIGNING** | **NOT VERIFIED** (no signing secrets here; AAB is unsigned) |

---

## 3. Versioning

`versionCode` default `1`, `versionName` default `0.1.0`, both overridable per build
without editing the file (CI/pipeline increments `versionCode` for each Play upload).
`minSdk 26`, `targetSdk 36`, `compileSdk 36`, release build type minified + resource-shrunk.

---

## 4. Privacy policy & account deletion

- Final text: `docs/playstore/PRIVACY_POLICY.md` — **code-accurate** (offline, on-device,
  no raw-image persistence, encrypted templates, no sharing).
- Account deletion: in-app reset **IMPLEMENTED** (deletes all data + Keystore key); external
  web resource **MISSING**. See `docs/playstore/ACCOUNT_DELETION.md`.
- **PRIVACY POLICY PUBLIC URL = NOT VERIFIED**; **ACCOUNT DELETION URL = MISSING**.

---

## 5. Data safety

Full matrix in `docs/playstore/DATA_SAFETY.md`. Because there is no Internet permission and
nothing is transmitted, **no data type is "collected" or "shared"**. On-device storage and
processing are not collection. Matches the privacy policy and code.

---

## 6. Accessibility / Usage Access / Overlay / FGS

| Area | Implementation | Disclosure | Play declaration |
|---|---|---|---|
| Accessibility | `ProtectionAccessibilityService`, events only, `canRetrieveWindowContent=false`, touch-blocking overlay | In-app separate disclosure + affirmative consent | **REQUIRES PLAY CONSOLE MANUAL SUBMISSION** |
| Usage Access | `AppOpsManager` foreground-package detection, on-device only | Requirements-card rationale | Declaration if prompted |
| Overlay | `SYSTEM_ALERT_WINDOW` visual fallback; primary block is the accessibility overlay | Requirements-card rationale | Declaration if prompted |
| FGS | `camera` + `specialUse`, subtype `parental_control_protection_state`, persistent notification | Ongoing notification | **FGS declaration — manual** |

---

## 7. Subscription

Product `qalqon_premium`, base plan `monthly`, trial offer `trial-3-day`; price dynamic
from Play (never hardcoded). Disclosure in `docs/playstore/SUBSCRIPTION_DISCLOSURE.md`.
**TRIAL CONFIGURATION = NOT VERIFIED; PRICE = NOT VERIFIED; real purchase test = NOT VERIFIED.**

---

## 8. Store listing & localization

- Copy: `docs/playstore/STORE_LISTING.md` (en primary, uz/ru). No forbidden claims; a
  claim↔capability table cross-checks every statement against the code.
- Localization: app ships **uz (default) / en / ru** with enforced string parity
  (`LocalizationCompletenessTest`); store copy prepared in all three.
- Graphics/screenshots: **MISSING** (plan in `docs/playstore/SCREENSHOTS_PLAN.md`).

---

## 9. Support, legal, OSS

- **SUPPORT CONTACT = MISSING** (no real email/URL; placeholders only).
- Legal: privacy text prepared; **Terms of Service MISSING**; **LEGAL REVIEW REQUIRED**.
- Third-party licenses: inventory prepared (`docs/playstore/THIRD_PARTY_LICENSES.md`);
  bundled-model provenance to confirm; **LEGAL REVIEW REQUIRED**.

---

## 10. Permission minimization

Declared permissions: CAMERA, PACKAGE_USAGE_STATS, SYSTEM_ALERT_WINDOW,
FOREGROUND_SERVICE, FOREGROUND_SERVICE_CAMERA, FOREGROUND_SERVICE_SPECIAL_USE,
POST_NOTIFICATIONS, RECEIVE_BOOT_COMPLETED. `INTERNET` / `ACCESS_NETWORK_STATE` are
manifest-merger **removals**. **No unused permission to remove; no new permission added
for the form.** (USE_BIOMETRIC/USE_FINGERPRINT and BILLING come from libraries.)

---

## 11. Play Console checklist

See `docs/playstore/PLAY_CONSOLE_CHECKLIST.md` (40 items, each READY / MISSING /
NOT VERIFIED). **Technically prepared to submit: YES. Submitted/approved: NO.**

---

## 12. Tests

`Stage11LaunchReadinessTest` (see the code) adds focused, non-duplicative guards:
release AAB configuration, `bundleRelease` wiring, version overridability, permission
minimization (no extra permission), no network capability, debug disabled, minimal
exported surface, and presence + code-consistency of the launch docs (privacy policy,
data safety, subscription disclosure, account-deletion content). Existing
`PlayComplianceContractTest` (target API, accessibility config/disclosure, offline
manifest, FGS types, no biometric logging, privacy screen, reset deletes key) and the
Stage 10 tests remain green.

---

## 13. Honest status

| Question | Answer |
|---|---|
| Real device walkthrough | **NO** |
| Play Console access | **NO** |
| Live submission | **NO** |
| Play review | **NO** |
| Privacy/support URLs hosted | **NO** |
| Trial/price/license tester verified | **NO** |

Known limitations: no public privacy/support URLs; no store graphics; no release/upload
signing; Play declarations not submitted; real purchase and device validation pending;
bundled-model licensing needs legal confirmation. **No "Play Store ready" / "approved"
claim is made.**
