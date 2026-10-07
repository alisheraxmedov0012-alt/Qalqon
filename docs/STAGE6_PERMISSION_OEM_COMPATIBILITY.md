# QALQON — Stage 6: Permission & OEM Compatibility

> Stage 6 deliverable (roadmap 6/12). Goal: move from "the app asks for permissions"
> to "the app detects each capability, reports its real state, guides the user to the
> right settings page, detects a later revoke, and stays fail-safe on OEM-restricted
> devices".
>
> **Honesty contract.** This environment has **no physical device and no ADB** and the
> emulator cannot run here (no `/dev/kvm`). Every OEM below is therefore **NOT TESTED**
> on real hardware. The AI-generated fixes are verified by JVM unit tests and CI
> emulator compilation only. No OEM is claimed as "supported" or "verified".

---

## 1. Audit (before this change)

| Area | State before Stage 6 |
|---|---|
| Camera permission | Probed (runtime state `cameraGranted`); revocation stops the camera session (Stage 5 reconcile). |
| Usage Access | App-op read (`AndroidUsageAccess`), MODE_ALLOWED only. |
| Accessibility | Own-component match via `ComponentName` + `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`. |
| Overlay | `Settings.canDrawOverlays`; `SettingsIntents.overlay`. |
| Notifications | Permission-aware; degrades only the notification, not protection. |
| FGS / camera / specialUse | Declared and correct (Stage 5). |
| Settings intents | Failure-safe `AndroidSettingsStarter` + `openSettingsOrFallback`. |
| Re-probe | On resume + bounded OEM-lag re-probe (3 × 700ms). |
| **Battery optimization** | **Missing entirely.** |
| **OEM detection** | **Missing.** |
| **OEM/autostart guidance** | **Missing.** |
| **Capability health** | Only 4 capabilities; no `UNAVAILABLE`; battery/OEM invisible. |

Nothing found was crash-prone; the gap was **honesty and guidance**.

---

## 2. What changed

1. **Capability model extended** (`domain/diagnostics`): `DiagnosticStatus.UNAVAILABLE`;
   two new checks `BATTERY_OPTIMIZATION` and `OEM_BACKGROUND`. Both are *recommended*
   signals — they can warn or be unavailable, **never** FAILED.
2. **Battery optimization** (`core/oem/AndroidBatteryOptimization`): reads
   `PowerManager.isIgnoringBatteryOptimizations` (null below API 23), and offers the
   permission-free `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` list page. No new
   manifest permission; the user is never forced.
3. **OEM detection** (`domain/oem/OemDetector` + `core/oem/AndroidOemDetector`): pure,
   case-insensitive detection from `Build.MANUFACTURER/BRAND/MODEL`, resolved **once**
   and cached. Sub-brands (Redmi/POCO/iQOO/Honor) are resolved before their parents.
4. **Centralised OEM manager** (`core/oem/AndroidOemSettings` + `domain/oem/OemCompatibilityProfiles`):
   the *only* place with OEM settings knowledge. Produces an **ordered candidate list**
   `OEM page(s) -> generic platform page -> app details`, each resolved before launch.
5. **Runtime reporting** (`ProtectionRuntime`): exposes `oemFamily`,
   `oemGuidanceAvailable`, `batteryOptimizationIgnored` (reporting-only, never a
   capability) and safe intent accessors.
6. **UI** (`ProtectionScreen`): a "Device reliability" card with battery status +
   guidance and OEM background guidance, each with a resolve-then-fallback action.

Detection, battery and OEM state are **local-only**; no device data leaves the app
(no INTERNET permission).

---

## 3. Capability matrix

| Capability | Before | After | Revoke handled | Regrant handled |
|---|---|---|---|---|
| Camera | Probing + degraded | Same + regression-tested | Yes (session stops, stale cleared) | Yes (legal-moment rebind) |
| Usage Access | App-op read | Same | Yes (degraded) | Yes (resume re-probe) |
| Accessibility | Own-component match | Same | Yes (degraded; visual-only fallback intact) | Yes (reconnect) |
| Overlay | canDrawOverlays | Same | Yes (degraded) | Yes (resume re-probe) |
| Notification | Permission-aware | Same | Yes (reported, never fails core) | Yes |
| Battery | Not modelled | **Modelled** (recommended) | n/a (read on resume) | n/a |
| Background / Autostart / OEM | Not modelled | **Guidance** (reporting-only) | n/a (not readable) | n/a |

---

## 4. OEM matrix

> **All OEMs are NOT TESTED on real hardware** in this environment. CI compiles and
> runs the instrumented suite on a Google-API emulator, which is **not** an OEM device.

| OEM | Example device | Android | Real device? | Result | Limitation |
|---|---|---|---|---|---|
| Samsung | (none) | — | No | **NOT TESTED** | No per-app autostart page; guidance points at battery |
| Xiaomi | (none) | — | No | **NOT TESTED** | MIUI autostart page is a resolved candidate |
| Redmi | Redmi Note 14 (reported) | unknown | No | **NOT TESTED** | Prior blocker fix (Stage 10) not re-verified on device |
| POCO | (none) | — | No | **NOT TESTED** | Shares Xiaomi surface |
| OPPO | (none) | — | No | **NOT TESTED** | ColorOS candidates resolved at runtime |
| OnePlus | (none) | — | No | **NOT TESTED** | OxygenOS/ColorOS candidates resolved |
| Vivo | (none) | — | No | **NOT TESTED** | Funtouch/OriginOS candidates resolved |
| Realme | (none) | — | No | **NOT TESTED** | Shares ColorOS surface |
| Honor | (none) | — | No | **NOT TESTED** | Magic OS candidates resolved |
| Huawei | (none) | — | No | **NOT TESTED** | EMUI candidates resolved |
| Motorola | (none) | — | No | **NOT TESTED** | Generic guidance |
| Google Pixel | Emulator only | API 35 | No (emulator) | **PARTIALLY VERIFIED (emulator compile + structural)** | Emulator is not a Pixel device |

The exact OEM component names are **candidates**; the manager resolves each with
`PackageManager.getActivityInfo` and falls back. A stale candidate degrades to the
app's own settings page — it can never crash or dead-end.

---

## 5. Permission revoke / regrant flow

| Capability | Revoke | Detection | Degraded state | Guidance | Regrant | Runtime recovery |
|---|---|---|---|---|---|---|
| Camera | Yes | runtime `cameraGranted` | `CAMERA` missing | settings → app details | Yes | session rebind in a legal moment |
| Usage Access | Yes | app-op probe | `USAGE_ACCESS` missing (still enforced via accessibility) | usage-access settings | Yes | resume re-probe |
| Accessibility | Yes | secure setting + manager | `ACCESSIBILITY` missing, visual-only fallback | accessibility settings | Yes | `onAccessibilityConnected` |
| Overlay | Yes | `canDrawOverlays` | `OVERLAY` missing | overlay settings | Yes | resume re-probe |
| Notification | Yes | `areNotificationsEnabled` | reported only | app notification settings | Yes | next dispatch |
| Battery | n/a | `isIgnoringBatteryOptimizations` | reported only | battery list page | n/a | re-probe on resume |
| OEM background | not readable | — | reported only | OEM/autostart page | n/a | n/a |

---

## 6. Android limitation matrix (Stage 6 scope)

| Scenario | Status | Note |
|---|---|---|
| Camera permission revoked while protecting | Handled | Session stops safely; stale recognition cleared (Stage 5) |
| Usage Access revoked | Handled | Enforced via accessibility; else degraded |
| Accessibility disabled externally | Handled | Detected on next probe; visual-only fallback |
| Overlay revoked | Handled | Reported degraded; block re-asserts when restored |
| Battery optimization active | Reported (recommended) | Never a failure; user chooses |
| OEM autostart / task killer | Guidance only | **ANDROID/OEM-LIMITED**, not readable/bypassable |
| Force stop | Unchanged | **ANDROID-LIMITED** (Stage 5) |

---

## 7. Tests

* `OemDetectorTest` (20) — every family, case/trim normalisation, sub-brand priority,
  empty identity, and the background-restriction flag.
* `OemCompatibilityProfilesTest` (9) — every family has a profile; SUPPORTED carries
  candidates; GENERIC carries none; candidates well-formed; battery is platform-only.
* `Stage6SettingsIntentFallbackTest` (6) — ordered resolution and fallback.
* `Stage6HealthEvaluatorTest` (11) — battery/OEM checks: OK / WARNING / UNAVAILABLE /
  UNKNOWN, never a blocker, recommended-settings baseline stays HEALTHY.
* `Stage6CapabilityMatrixTest` (15) — revoke/regrant for all four required
  capabilities, priority ordering, order-independent keys, and battery/OEM as
  reporting-only.
* `Stage6RegressionGuardTest` (6) — Stage 1–5 invariants intact; no new dangerous
  permission; INTERNET still removed.

JVM total: **2059 → 2126** (0 failures, 0 errors, 0 skipped).

Instrumented (compile in CI; **not** executed here): `Stage6OemCompatibilityInstrumentedTest`.

---

## 8. Real device validation

**REAL DEVICE TEST = NO.** No physical device and no ADB in this environment. The CI
emulator is not an OEM device and is not presented as OEM validation.

---

## 9. Deferred

* OEM-specific workarounds / exact component maintenance per OS version → ongoing,
  Stage 6 evidence.
* Android 8–16 exhaustive matrix → **Stage 7 (Android Version Compatibility)**.
* Play policy for `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` was deliberately avoided
  (no such permission requested).
