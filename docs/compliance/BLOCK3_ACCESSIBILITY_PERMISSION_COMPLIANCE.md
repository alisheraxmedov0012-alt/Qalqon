# QALQON — Release Block 3: Accessibility & Sensitive Permission Compliance Package

> **Status: PREPARED — NOT SUBMITTED. No Play Console declaration has been submitted and no
> approval (Google Play or legal) is claimed or implied.**
>
> Scope: Release Completion **Block 3** — Accessibility + Sensitive Permission Compliance.
> Product: QALQON, package `uz.faceguard.app`, offline-first Android parental-control app.
> Everything under "Verified code facts" below was checked against the source at the revision
> in §7; everything under "Requires human confirmation" still needs a person (and, for several
> items, a physical device).
>
> Companion documents: `docs/playstore/PERMISSION_DISCLOSURES.md`,
> `docs/playstore/DATA_SAFETY.md`, `docs/STAGE8_ACCESSIBILITY_DECLARATION.md` (earlier draft,
> superseded by this package), `docs/STAGE8_OVERLAY_DECLARATION.md`,
> `docs/STAGE8_FGS_DECLARATION.md`, `docs/STAGE8_USAGE_ACCESS_DECLARATION.md`.

---

## Part 0 — Verified code facts (evidence base)

| Fact | Evidence |
|---|---|
| Single AccessibilityService subclass | `core/accessibility/ProtectionAccessibilityService.kt` is the only `: AccessibilityService` |
| Service binding is system-only | manifest: `android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"`, `android:exported="true"` |
| Not declared as an accessibility tool | `isAccessibilityTool` absent from the service config and manifest |
| Only window transitions observed | `accessibility_service_config.xml`: `typeWindowStateChanged\|typeWindowsChanged`; `AccessibilityEventFilter.isRelevant()` rejects every other type |
| No window content retrieval | config `canRetrieveWindowContent="false"`, `accessibilityFlags="flagDefault"`; no `getRootInActiveWindow`/`getWindows`/`AccessibilityNodeInfo`/`performGlobalAction`/`takeScreenshot` anywhere in `main` |
| No gestures | `canPerformGestures` absent (default false); no global-action calls |
| Data read is only the package name | `onAccessibilityEvent()` reads `event.packageName` only |
| No off-device transmission | merged **release** manifest contains **no** `INTERNET`/`ACCESS_NETWORK_STATE`; no okhttp/retrofit/firebase/analytics/crashlytics dependency; no `java.net`/`WebView` in `main` |
| Consent is affirmative & gated | `core/ui/qalqon/AccessibilityConsentGate.kt` is the only dialog; both entry points route through `requiresAccessibilityDisclosure()`; settings launch only inside `onConfirm` |
| Enforcement uses a touchable accessibility overlay | `AccessibilityOverlayWindow` uses `TYPE_ACCESSIBILITY_OVERLAY` with **no** `FLAG_NOT_TOUCHABLE` (consumes touches) |
| Fallback is visual-only | `OverlayControllerImpl.showLegacy()` uses `TYPE_APPLICATION_OVERLAY` **with** `FLAG_NOT_TOUCHABLE` |
| Honest degraded state | `protectionReadiness()` -> `LIMITED` whenever any of Accessibility/Overlay/Usage Access/Camera is missing; degraded banner names the missing capabilities |

---

## Part 1 — Accessibility declaration draft (for the Play Console form)

> Do not paste this verbatim as if it were an official Google form; the form's wording changes.
> Use it as the source of truth for the answers and verify the on-screen prompts when submitting.

1. **Core functionality.** QALQON is a parental-control app used by a parent/guardian to apply
   rules to their child's use of the same Android device: blocking selected ("protected") apps,
   schedules, screen-time limits and eye-safety. It is not a disability-support tool.

2. **Why Accessibility is necessary.** Android offers no other supported mechanism for a
   parental-control app to (a) receive system-wide foreground window/app transitions and (b)
   draw an overlay that *consumes* the child's touches over another app. `SYSTEM_ALERT_WINDOW`
   overlays cannot intercept input, and Usage Access cannot block. The Accessibility service is
   therefore required for the declared core function (parent-authorised app blocking).

3. **Exact events processed.** `TYPE_WINDOW_STATE_CHANGED` and `TYPE_WINDOWS_CHANGED` only.
   Every other event type is ignored in code (`AccessibilityEventFilter`).

4. **Exact data read.** Only the **foreground package name** from the event. No window content,
   no node tree, no text/IME, no screenshots. `canRetrieveWindowContent="false"` and no
   node-tree API is referenced anywhere in the app.

5. **What the app does with it.** The package name drives the existing protection runtime to
   decide whether the foreground app is one the parent protected, and — when it is, and no
   parent face is present — the service shows/hides a `TYPE_ACCESSIBILITY_OVERLAY` blocking
   window that consumes touches. The service holds no policy logic, no database access and no
   camera/recognition code.

6. **Data retention / sharing.** On-device only. Nothing is transmitted (the app has no
   internet permission). The foreground package name may be written to the app's *local*
   activity log for events such as "protected app entered"; that record never leaves the device.

7. **User consent & revocation.** Tapping the accessibility action (Protection screen
   requirements row, or a Home/Protection degraded-banner "fix") opens an in-app prominent
   disclosure dialog with an explicit affirmative button ("I agree — open settings"). Only that
   button opens Android Accessibility settings; declining or dismissing opens nothing. The app
   never enables the service itself. The user revokes by disabling QALQON in Android
   Settings -> Accessibility (wording varies by Android/OEM).

8. **When Accessibility is disabled.** Protection is reported as **limited** (never "ready"):
   the app falls back to Usage Access polling for foreground detection and, if the
   `SYSTEM_ALERT_WINDOW` overlay is granted, shows a **visual-only** blocking scrim that cannot
   consume touches. The app must not be described as fully blocking in that state.

9. **Distinguish from Usage Access and the overlay fallback.** Usage Access is a separate
   special access used only for foreground detection; the `SYSTEM_ALERT_WINDOW` overlay is a
   visual fallback only. Neither is equivalent to the Accessibility touch-blocking mechanism.

10. **No over-claim.** QALQON does **not** claim it can block every Android surface (e.g.
    system UI, Settings, Recents, the power menu) — the implementation does not prove that.

### Requires human confirmation (not a code fact)
- The exact Play Console prompts/labels at submission time.
- Whether Google accepts the parental-control justification for this specific app.
- Any inconsistency between this package and the final store listing / Data safety entry.

---

## Part 2 — Demo-video script & shot list

Deliverable: a short screen recording for Play review. **Every shot marked DEVICE requires a
real Android device**; nothing here has been recorded, and no recording is fabricated.

Narration is in English (captions on screen). Keep it under ~2 minutes.

| # | Device? | Shot | Narration (caption) |
|---|---|---|---|
| 1 | DEVICE | Home dashboard; open **Protection**. | "QALQON is a parental-control app. A parent sets up rules for their child on this same device." |
| 2 | DEVICE | Protection -> protected-apps selection; show a protected app chosen. | "The parent chooses which apps to protect and how." |
| 3 | DEVICE | Protection **requirements** card -> tap the accessibility row. | "QALQON asks for the Accessibility service to detect the foreground app." |
| 4 | DEVICE | The disclosure dialog appears **before** Settings. | "A clear in-app disclosure is shown first: only the foreground app's package name is read; no screen content; on-device only." |
| 5 | DEVICE | Tap **"I agree — open settings"**; Android Accessibility screen opens for QALQON. | "Only the explicit affirmative action opens Android Accessibility settings. QALQON never enables it itself." |
| 6 | DEVICE | Toggle QALQON's service on manually. | "The user enables it manually." |
| 7 | DEVICE | Return to QALQON; open a protected app as the child would. | "With Accessibility enabled, opening a protected app is blocked; the child cannot dismiss it." |
| 8 | DEVICE | Disable QALQON's accessibility service; return; open the protected app. | "If Accessibility is turned off, protection is reported as limited — the app no longer blocks touches." |
| 9 | DEVICE | Show the Home/Protection **degraded** banner listing the missing capability. | "QALQON honestly tells the parent exactly what is missing." |
| 10 | DEVICE | Re-open the disclosure; tap **Cancel**; Settings does not open. | "Declining the disclosure opens nothing." |
| 11 | DEVICE | Android Settings -> Accessibility -> QALQON -> turn off. | "The user can revoke access at any time in Android Settings." |
| 12 | DEVICE | (Optional) The visual-only overlay fallback when Accessibility is off but the overlay permission is granted. | "Without Accessibility, the fallback is a visual indication only — it does not consume touches." |

Recording guidance: record on a normal (non-rooted) device; keep OS navigation visible; do not
edit out the disclosure or the manual enable step; retain the raw file as evidence.

---

## Part 3 — Submission checklist

### Verified code facts already documented above
- [x] Service purpose, events, data read, no content access, no transmission (Part 0/1).
- [x] Consent + revocation flow and entry points (Part 1 §7).
- [x] Disabled-state behaviour and fallback distinction (Part 1 §8-9).

### To be entered / confirmed in Play Console (human action — NOT done here)
- [ ] **App content -> Accessibility** declaration: purpose = parental control; not an
      accessibility tool; include the demo video.
- [ ] **App content -> Data safety**: re-confirm "no data collected / not shared" still holds
      (it does at the code level — no internet permission).
- [ ] **Foreground service declaration**: `camera` + `specialUse` with subtype justification
      (see `docs/STAGE8_FGS_DECLARATION.md`).
- [ ] **Privacy policy URL** (`https://qalqon.win/en/privacy-policy`) and **account deletion URL**
      (`https://qalqon.win/en/delete-account`) entered (Block 2 deliverables).
- [ ] Target audience / content rating (adults; not Designed for Families).
- [ ] Retain the raw demo video and screenshots as submission evidence.

> No submission, review, or approval is claimed. Console access is out of scope for this task.

---

## Part 4 — Sensitive permission & capability audit

Merged **release** manifest inventory (this is what the store sees):

| Capability | Declared where | Checked / requested | Rationale shown to user | Denied / revoked behaviour | Essential? | Verified by |
|---|---|---|---|---|---|---|
| **Accessibility service** | manifest `<service ... ProtectionAccessibilityService>` + `BIND_ACCESSIBILITY_SERVICE` | `AccessibilityCapability.isEnabled()`; disclosure gate | Disclosure dialog; requirement row "why" | Falls back to Usage Access polling + visual overlay; reported LIMITED | Essential for touch-blocking | Source + JVM tests; device NOT RUN |
| **Usage Access** (`PACKAGE_USAGE_STATS`, app-op) | manifest `<uses-permission>` | `AndroidUsageAccess.isGranted()` (app-op `unsafeCheckOpNoThrow`, MODE_ALLOWED only) | Requirement row "why" | Poll failure isolated; capability reported missing -> LIMITED | Essential (foreground detection) | Source + JVM tests; device NOT RUN |
| **Overlay** (`SYSTEM_ALERT_WINDOW`, `TYPE_APPLICATION_OVERLAY`) | manifest `<uses-permission>` | `Settings.canDrawOverlays()` | Requirement row "why" | Visual-only fallback; if absent, no visual scrim; reported missing | Fallback-only (visual) | Source + tests; device NOT RUN |
| **Accessibility overlay** (`TYPE_ACCESSIBILITY_OVERLAY`) | runtime window, owned by the service | Service connection (`AccessibilityOverlayRegistry`) | Part of the accessibility disclosure | Falls back to legacy scrim | Essential for touch-blocking | Source + tests; device NOT RUN |
| **Camera** | manifest `<uses-permission CAMERA>` + `<uses-feature required=false>` | `rememberPermissionState(CAMERA)` (Accompanist); runtime check in FGS | Camera rationale card before the system prompt | Failure handled (`CameraRecoveryCard`); capability -> LIMITED | Essential for recognition | Source + JVM tests; device NOT RUN |
| **Foreground service** (`camera`, `specialUse`) | manifest `<service ... foregroundServiceType="camera\|specialUse">` + `FOREGROUND_SERVICE(_CAMERA/_SPECIAL_USE)` | `PlatformCompat.foregroundServiceTypes()` | Ongoing notification + channel | Rejected background start logged, not fatal; STOP on disable | Essential (background runtime) | Source + JVM tests; device NOT RUN |
| **Notifications** (`POST_NOTIFICATIONS`) | manifest `<uses-permission>` | requested on explicit tap (`RequestsScreen`); `areNotificationsEnabled()` | Inline reason before the prompt | Denial is harmless; requests still saved; FGS unaffected | Optional | Source + JVM tests; device NOT RUN |
| **Boot receiver** (`RECEIVE_BOOT_COMPLETED`) | manifest `<receiver>` (system-only broadcast) | `ProtectionBootReceiver` -> existing restore path | — | Restore is best-effort; camera type not claimed from boot | Optional (reliability) | Source + JVM tests; device NOT RUN |
| **Billing** (`com.android.vending.BILLING`) | merged from the Play Billing library | `BillingClient` | Subscription screens | Handled by the billing error mapper | Optional (Premium) | Source + tests; real purchase NOT RUN |
| **Biometric** (`USE_BIOMETRIC`, `USE_FINGERPRINT`) | merged from `androidx.biometric` | Parent-UI unlock | — | Falls back to PIN | Optional | Source |
| **AICore bind** (`com.google.android.apps.aicore.service.BIND_SERVICE`) | merged from `com.google.mlkit:genai-prompt` | capability detection only | — | Detection returns `SDK_ERROR` -> deterministic assistant | Informational (unused for data) | Source + merger report |
| **Dynamic receiver perm** (`uz.faceguard.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`) | merged from `androidx.core` | internal | — | — | Framework-internal | Merged manifest |

Not present (confirmed): `INTERNET`, `ACCESS_NETWORK_STATE`, `QUERY_ALL_PACKAGES`, any location,
any external-storage permission, `RECORD_AUDIO`, exact-alarm permissions.

### Risk checks
- **Accessibility `exported`/binding** — correct (`exported="true"` is required so the *system*
  can bind; guarded by `BIND_ACCESSIBILITY_SERVICE`). PASS.
- **Event types/flags/content access match declared purpose** — PASS (Part 0).
- **No automatic enable / simulated consent / disclosure bypass** — PASS (Part 0 + ACC-03).
- **Usage Access: no broad package inventory** — PASS (`<queries>` scoped to MAIN/LAUNCHER).
- **Overlay vs fallback not represented as equivalent** — PASS (readiness + degraded banner;
  `AccessibilityOverlayWindow` vs `OverlayControllerImpl.showLegacy()` differ in `FLAG_NOT_TOUCHABLE`).
- **No "fully active" claim while blocking unavailable** — PASS (`protectionReadiness` -> LIMITED).
- **FGS/camera restrictions** — PASS at source level; boot/camera behaviour NOT RUN on a device.

---

## Part 5 — Previously reported findings (Objective D)

### PERM-03 — dependency-merged `com.google.android.apps.aicore.service.BIND_SERVICE`
- **Origin:** `com.google.mlkit:genai-prompt:1.0.0-beta4` (evidence: manifest-merger report,
  `app/build/outputs/logs/manifest-merger-release-report.txt`). It also adds
  `<queries><package android:name="com.google.android.aicore" /></queries>`.
- **Why present:** the app detects on-device Gemini Nano availability
  (`feature/help/ai/GeminiNanoCapability.kt`). That check queries the AICore service.
- **Risk:** LOW. It is a signature-level permission; it can never be granted to a third-party
  app on its own and grants no user-data access. No generation is wired up (Phase A1 is
  detection only).
- **Action:** **documented, not removed.** Stripping it would only disable a detection query and
  was judged unjustified (removing a dependency-provided entry to make a scan look cleaner is
  explicitly out of scope). A source-manifest comment now records this (AndroidManifest.xml).

### OVERLAY-03/04 — inert protection when both Accessibility and overlay are absent
- **Clarified:** with both absent, no window can be shown and touch-blocking is impossible.
  The app nevertheless reports **LIMITED** (never READY) and the degraded banner names the
  missing capabilities ("Protection is not fully working — Needed: Overlay, Accessibility").
- **Conclusion:** the state is **honestly communicated**; no misleading readiness was found, so
  no behaviour change was made. Documented as retained behaviour.

### Lint — `ClickableViewAccessibility`
- **Where:** `AccessibilityOverlayWindow.buildRootView()`.
- **Fix:** the touch listener now calls `view.performClick()` on `ACTION_UP` while still
  returning `true`, so the touch is still consumed and the click is surfaced to accessibility
  services. Behaviourally identical for enforcement.
- **Other warnings:** retained (pre-existing, unrelated to Block 3) — reported, not suppressed.

---

## Part 6 — Physical-device / OEM manual test matrix

> Every row is **NOT RUN — NO DEVICE AVAILABLE** in this environment. Do not mark any row PASS
> from source inspection or JVM tests. Record Actual Result + evidence when a device is used.

| # | Preconditions | Steps | Expected result | Actual | Evidence | Status |
|---|---|---|---|---|---|---|
| D-01 | Fresh install, no account | First-run: create account/PIN | No crash; consent screens appear | — | screen recording | NOT RUN |
| D-02 | Signed in, Protection screen | Tap accessibility row | Disclosure dialog appears before Settings | — | recording | NOT RUN |
| D-03 | Same | Tap the Home degraded-banner accessibility "fix" | Same disclosure dialog appears (no direct Settings) | — | recording | NOT RUN |
| D-04 | Disclosure open | Tap "I agree — open settings" | Android Accessibility settings open | — | recording | NOT RUN |
| D-05 | Disclosure open | Tap Cancel / back | Nothing opens; no Settings launch | — | recording | NOT RUN |
| D-06 | Settings opened | Manually enable QALQON service | Service binds; requirement row shows Ready | — | recording | NOT RUN |
| D-07 | Accessibility on, all caps | Open a protected app as child | Blocked; overlay cannot be dismissed | — | recording | NOT RUN |
| D-08 | Accessibility off, overlay on | Open a protected app | Visual-only scrim; app reports LIMITED | — | recording | NOT RUN |
| D-09 | Accessibility off, overlay off | Open a protected app | No block; LIMITED + degraded banner | — | recording | NOT RUN |
| D-10 | Usage Access granted then revoked | Return to app | Poll isolated; capability reported missing | — | recording | NOT RUN |
| D-11 | Overlay granted then revoked | Return to app | Capability reported missing; no crash | — | recording | NOT RUN |
| D-12 | Camera denied then granted | Enroll a face | Denial handled; grant works | — | recording | NOT RUN |
| D-13 | Camera in use by another app | Open QALQON | Recovery banner; no crash | — | recording | NOT RUN |
| D-14 | Notifications denied | Open Requests | Inline reason; requests still saved | — | recording | NOT RUN |
| D-15 | Protection on | Start FGS; stop protection | Notification shown; service stops cleanly | — | recording | NOT RUN |
| D-16 | Protection on | Kill app from Recents | Protection continues | — | recording | NOT RUN |
| D-17 | Protection on | Reboot device | Restore path runs; camera limited until app opened | — | recording | NOT RUN |
| D-18 | Xiaomi/HyperOS device | Repeat D-02..D-09 | Same honest behaviour; OEM settings labels may differ | — | recording | NOT RUN |
| D-19 | Samsung / Pixel / OPPO / Vivo (if available) | Repeat D-02..D-09 | Best-effort compatibility | — | recording | NOT RUN |
| D-20 | Sideload install of a CI debug APK on Redmi Note 14 / HyperOS / Android 16 | Install via browser/messaging/file manager | Record the exact Play Protect dialog and whether it is the hard "App blocked to protect your device" screen or a soft scan prompt | — | screenshot + verbatim text | NOT RUN |
| D-21 | Two consecutive CI debug APKs | Install the older, then try to update with the newer | A debug-key rotation makes in-place update fail (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`); uninstall then install | — | `adb install` output | NOT RUN |
| D-22 | HyperOS install flow | Compare an install from a browser vs `adb install` vs Play internal testing | Only the Internet-sideloading source is subject to the sensitive-permission auto-block | — | recording | NOT RUN |

Device evidence required before Block 3 compliance can be considered complete:
at least one clean device run of D-02..D-09 (the disclosure/enforcement core) plus one
Xiaomi/HyperOS run. Full OEM certification remains Block 6.

---

## Part 7 — Revision of record

| Field | Value |
|---|---|
| Branch | `feature/phase4-screen-time-complete` |
| Audit performed at commit | `f3591688749bff83cdd8d0418ff3f247d2ce0421` |
| Documentation revision | this file's edit commit (see Part 8 §8.1 for the audit commit) |
| Date | 2026-10-10 |
| Status | PREPARED — NOT SUBMITTED. No Play Console declaration submitted; no device test run. |

Update this table if the docs are edited again.

---

## Part 8 — Block 3 final audit (this revision)

### 8.1 Repository state

| Field | Value |
|---|---|
| Branch | `feature/phase4-screen-time-complete` |
| HEAD at audit | `f3591688749bff83cdd8d0418ff3f247d2ce0421` |
| Worktree | clean (`git status` = nothing to commit) |
| Remote | `github.com/alisheraxmedov0012-alt/Qalqon` |

### 8.2 Requirement status matrix

Legend: **PASS** = verified by a code/test artefact in this repo · **FAIL** = proven defect ·
**NOT RUN** = cannot be verified in this environment (no device) · **BLOCKED** = needs Play
Console / Google.

| # | Requirement | Status | Evidence |
|---|---|---|---|
| A1 | AccessibilityService declared, system-bound only | PASS | `AndroidManifest.xml`; `AccessibilityServiceContractTest` (8/8) |
| A2 | `isAccessibilityTool` not set anywhere | PASS | config + manifest; byte-scan of the built APK (absent) |
| A3 | `canRetrieveWindowContent="false"`, no node-tree API | PASS | `accessibility_service_config.xml`; `AccessibilityServiceContractTest` |
| A4 | Only `TYPE_WINDOW_STATE_CHANGED` / `TYPE_WINDOWS_CHANGED` | PASS | `AccessibilityEventFilter.kt`; contract test |
| A5 | Only the foreground package name is read | PASS | `ProtectionAccessibilityService.kt`; contract test |
| A6 | No off-device transmission | PASS | merged **release** manifest has **0** active `INTERNET`; no okhttp/retrofit/firebase/ktor; contract test |
| A7 | Every path to Accessibility settings is gated by the disclosure | PASS | `AccessibilityConsentGateTest` (13/13) |
| A8 | Launch happens only from the affirmative action | PASS | `AccessibilityConsentGateTest`; `AccessibilityConsentGate.kt` |
| A9 | Revocation / disabled state is reported honestly (LIMITED) | PASS | `ProtectionReadiness` + degraded banner; `ProtectionReadinessTest` |
| B1 | In-app prominent disclosure before the permission | PASS | `AccessibilityDisclosureDialog` (shared gate) |
| B2 | Affirmative action required; decline opens nothing | PASS | `AccessibilityConsentGateTest` |
| B3 | Disclosure covers purpose / data / use / off-device / revoke (uz·en·ru) | PASS | `strings.xml` ×3; `AccessibilityConsentGateTest` |
| C1 | Camera: rationale before the system prompt | PASS | `protection_capability_why_camera`; `RequirementsCard` |
| C2 | Camera denial/recovery handled; no leftover frames | PASS | `CameraRecoveryCard`; `CameraRecoveryBackoffTest`; `ProtectionBootCameraTest` |
| D1 | Usage Access: explained, user-initiated, re-checked on resume | PASS | `AndroidUsageAccess`; `ProtectionScreen` ON_RESUME refresh |
| E1 | Overlay: purpose explained; accessibility vs visual fallback separated | PASS | `AccessibilityOverlayWindow` (touchable) vs `OverlayControllerImpl.showLegacy()` (`FLAG_NOT_TOUCHABLE`); contract test |
| F1 | FGS types correct per API; no illegal background start | PASS | `PlatformCompat.foregroundServiceTypes`; `ProtectionServicePolicyTest`; `ProtectionServiceLaunch...` tests |
| G1 | Notifications requested only on explicit action; denial harmless | PASS | `RequestsScreen` (explicit tap); `ProtectionRuntime` |
| H1 | Transitive permissions identified & justified | PASS | merged release manifest + merger report (PERM-03) |
| I1 | Google Play Console Accessibility declaration submitted | BLOCKED | requires Play Console (Part 9) |
| I2 | Data safety form submitted | BLOCKED | requires Play Console (Part 9) |
| I3 | FGS declaration submitted | BLOCKED | requires Play Console (Part 9) |
| I4 | Privacy policy URL / account-deletion URL hosted & entered | BLOCKED | Block 2 deliverables, hosting pending |
| J1 | Real-device disclosure/enforcement run (D-02..D-09) | NOT RUN | no device; matrix Part 6 |
| J2 | Xiaomi/HyperOS run (D-18, D-20..D-22) | NOT RUN | no device; matrix Part 6 |
| J3 | Instrumented/emulator suite green | NOT RUN | CI emulator job has failed historically; see Part 8 §3 |

**No FAIL row and no proven code defect was found.** No source, manifest, permission, resource,
signing or workflow change was required. Block 3 cannot be *closed* while I1–I4 are BLOCKED and
J1–J2 are NOT RUN.

### 8.3 Test / build results (this revision)

| Check | Command | Result |
|---|---|---|
| JVM unit tests | `./gradlew :app:testDebugUnitTest --rerun-tasks` | BUILD SUCCESSFUL — **2368 tests, 0 failures, 0 errors, 0 skipped** |
| Compliance contracts | (subset) | `AccessibilityServiceContractTest` 8 · `AccessibilityConsentGateTest` 13 · `PlayComplianceContractTest` 14 · `ReleaseSigningGuardContractTest` 6 — all pass |
| Lint | `./gradlew :app:lintDebug` | **0 errors**, 168 pre-existing warnings |
| Release R8 | `./gradlew :app:minifyReleaseWithR8` | BUILD SUCCESSFUL (R8 ran on the release variant) |
| Merged release manifest | `:app:processReleaseMainManifest` | 13 permissions, **no** INTERNET/ACCESS_NETWORK_STATE/QUERY_ALL_PACKAGES/location/SMS/audio/storage |
| Instrumented (emulator) | `:app:connectedDebugAndroidTest` | **NOT RUN** here (no emulator); CI emulator job must be re-checked |

### 8.4 Play Protect observation (recorded, not resolved)

On the reporter's Redmi Note 14 (HyperOS / Android 16) both older and current CI debug APKs are
blocked by Play Protect, currently with the hard "App blocked to protect your device" screen
rather than a "Details / install anyway" prompt. **Recorded as an open observation.**
- The hard-block category in Google's guidance is the *sensitive-permission Internet-sideloading*
  block, which lists `ACCESSIBILITY` among the guarded permissions; QALQON declares an
  `AccessibilityService`.
- The fact that a byte-identical older file now behaves differently points to a **server-side**
  Play Protect classification/enforcement change, not to an app change — but this is a
  **hypothesis, not a proven cause**. No Google/Xiaomi defect is asserted.
- Do **not** disable Play Protect, hide permissions, add `isAccessibilityTool`, remove the
  AccessibilityService, or add any bypass. Any appeal is a separate decision and is **not**
  claimed to have succeeded.
- Debug signing-key rotation across CI runs is a documented fact (each run signs with a different
  auto-generated debug key) and would explain *update* failures, but is **not** claimed as the
  cause of the classification block.

---

## Part 9 — Play Console declaration / Data safety preparation

> Everything below is **PREPARED, NOT SUBMITTED**. Console access is out of scope for this task.

### 9.1 Accessibility declaration — ready answers (with code evidence)

| Prompt (typical) | Answer | Evidence |
|---|---|---|
| Why does your app need the AccessibilityService API? | **App functionality** (parental-control app blocking) | Part 1 §1–2; `ProtectionAccessibilityService` |
| Do you collect and/or share personal or sensitive data using the accessibility capabilities? | **No** | Only `event.packageName` is read; nothing transmitted (no INTERNET) |
| Is your app an accessibility tool (`isAccessibilityTool=true`)? | **No** | flag absent; `AccessibilityServiceContractTest` |
| Provide a short video of the prominent disclosure | attach the demo video | Part 2 shot list |

### 9.2 Data safety — questions to answer from the real data handling

Answer these from the code, not by assumption (`docs/STAGE8_DATA_SAFETY_ANSWER_SHEET.md`):
1. Is any data collected or shared off-device? (code says **no** — no INTERNET permission.)
2. Is the foreground package name / activity log ever transmitted? (**no** — local DB only.)
3. Is the face template / biometric data transmitted? (**no** — AES-GCM, key in Android Keystore.)
4. Does Google Play Billing data need declaring? (payment processed by Play, app never accesses
   card data — per Play's Data safety FAQ.)
5. Is data encrypted in transit? (not applicable — nothing is transmitted.)
6. Is data deletion available? (in-app `ResetRepository` + external web resource — Block 2.)

### 9.3 Foreground service — questions to answer

1. Which FGS types and why: `camera` (on-device recognition) + `specialUse`
   (subtype `parental_control_protection_state`); evidence `AndroidManifest.xml`.
2. Is the user told why the service runs? (persistent notification; `AndroidNotifications`.)
3. Is `camera` ever started from a boot context? (**no** — boot restores `specialUse` only).

### 9.4 Demo video — reproducible scenario

Use the Part 2 shot list verbatim (12 shots). The video must show the disclosure **before**
Settings, the affirmative tap, the manual enable, a blocked protected app, revocation, and the
honest LIMITED state. It must **not** be edited to hide the disclosure or the manual enable step.
**Status: NOT CREATED — MANUAL ACTION REQUIRED** (no device).

---

## Part 10 — Remaining work and next step

### 10.1 Manual Play Console actions (all BLOCKED on Console access)

- [ ] App content → **Accessibility** declaration (answers in Part 9.1) + demo video.
- [ ] App content → **Data safety** (answers in Part 9.2).
- [ ] App content → **Foreground service** declaration (Part 9.3).
- [ ] Privacy policy URL + account-deletion URL (Block 2 hosting).
- [ ] Target audience / content rating (adults; not Designed for Families).
- [ ] Retain the raw demo video + screenshots as evidence.

### 10.2 Device testing actions (BLOCKED on a device)

- [ ] D-02..D-09 on a clean non-Xiaomi device.
- [ ] D-18 and D-20..D-22 on Redmi Note 14 / HyperOS / Android 16.
- [ ] Re-check the CI emulator job (`connectedDebugAndroidTest`) log for the real root cause.

### 10.3 Next most important step

**Record one clean real-device run of D-02..D-09** (the disclosure → consent → enable → block →
revoke core) with a screen recording, and file the Accessibility declaration. Those two items are
the Block 3 closure gate; everything else is either already PASS at the code level or a Console
hosting task.
