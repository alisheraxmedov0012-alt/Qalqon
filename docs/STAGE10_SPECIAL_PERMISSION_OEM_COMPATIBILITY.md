# QALQON — Stage 10: Special Permission / OEM Compatibility

> Stage 10 deliverable (Real Device QA). A blocker was reported on a **Xiaomi Redmi
> Note 14**: after granting Usage Access, the draw-over-other-apps permission and the
> accessibility service in system Settings, the Qalqon **Himoya / Home** requirement
> rows still did not flip to "Tayyor" (Ready). Camera, parent face and protected apps were
> Ready.
>
> **Honesty contract.** This environment has **no physical device and no ADB**, so the
> device itself could not be inspected or re-tested. This document records the code
> audit, the defects found, the generic fix and the JVM regression tests — and marks
> **all real-device verification as BLOCKED**. No PASS is claimed for the device.

---

## 1. Device (reported by the user; NOT inspectable from this environment)

| Field | Value |
|---|---|
| Manufacturer | Xiaomi |
| Model | Redmi Note 14 |
| Android version | **NOT AVAILABLE** (no ADB) |
| API level | **NOT AVAILABLE** |
| OEM skin / version | HyperOS — version **NOT AVAILABLE** |

`adb shell getprop …` could not be run: `adb devices -l` is empty and `/dev/bus/usb`
does not exist here. The device properties must be captured with the device attached
(`tools/device-qa/device_qa.sh inventory`).

## 2. Test timestamp
2026-10-05 (this session). Device tests: **not executed** (BLOCKED).

## 3. Before / After (device-observed)

| Capability | Before (reported) | After (this change) | Device-verified? |
|---|---|---|---|
| Camera | Ready | Ready | n/a (was already Ready) |
| Parent face | Ready | Ready | n/a |
| Protected apps | Ready | Ready | n/a |
| **Usage Access** | Never Ready | Fix applied | **BLOCKED — no device** |
| **Draw-over-other-apps** | Never Ready | Fix applied | **BLOCKED — no device** |
| **Accessibility service** | Never Ready | Fix applied | **BLOCKED — no device** |

## 4. Audit (what the code actually does)

| Capability | Check | Where |
|---|---|---|
| Usage Access | `AppOpsManager` app-op `GET_USAGE_STATS` == `MODE_ALLOWED` | `core/usage/AndroidUsageAccess.kt` |
| Overlay | `Settings.canDrawOverlays(context)` (true pre-M) | `core/protection/OverlayControllerImpl.kt#hasPermission`, `core/permission/AndroidProtectionCapabilitySource` |
| Accessibility | `AccessibilityManager.getEnabledAccessibilityServiceList(...)` **or** `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` matched by component | `core/accessibility/AccessibilityCapability.kt` |
| UI state | `ProtectionRuntimeState.{usageAccessGranted, overlayGranted, accessibilityEnabled}` → `RequirementsCard` rows | `ProtectionRuntime`, `feature/protection/ProtectionScreen.kt`, `feature/home/HomeScreen.kt` |
| Refresh | `ProtectionRuntime.refreshPermissions()` on `ON_RESUME` (Protection + Home) | `ProtectionScreen`, `HomeScreen` |

## 5. Root cause

The standard, correct Android idiom was used, so the failure was a set of **generic
robustness defects in the permission flow**, all of which match the reported class
("granted in Settings but still not Ready"):

1. **Settings deep-links were not failure-safe (definite defect).**
   `context.startActivity(viewModel.usageAccessIntent()/overlayPermissionIntent()/
   accessibilitySettingsIntent())` and the degraded-banner jump were called with **no
   `resolveActivity` guard and no try/catch**. On a device that has no activity for one of
   those pages this throws `ActivityNotFoundException` — i.e. a required permission can be
   *impossible to reach* (or the app crashes instead of guiding the user). No generic
   fallback existed.

2. **The capability probe was single-shot (definite weakness for the reported symptom).**
   `refreshPermissions()` probed once, immediately on `ON_RESUME`. On several OEMs
   (MIUI/HyperOS among them) the app-op / `Settings.Secure` accessibility value is updated
   a fraction of a second **after** the activity resumes, so that single probe reads stale
   "not granted" — and, with no further re-probe, the row stays stuck until the screen is
   reopened or the app is restarted.

3. **Usage Access read used the deprecated app-op overload** on every API level, and the
   usage check was duplicated in two places (`AndroidUsageAccess` and
   `ForegroundAppMonitor.hasUsageAccess`), which can diverge.

4. **Accessibility match was brittle** (`service.name == expected.className`): some OEMs
   report a relative vs. absolute service name, so an exact string compare can miss
   Qalqon's own enabled service.

> Note: the code was **already correct for standard Android** (checks are the documented
> idioms; `syncActive()`/`refreshPermissions()` set the three flags; the UI refreshes on
> resume). The defects above are what can make it fail specifically on OEMs and on
> missing-handler devices. The exact Redmi Note 14 behaviour could not be confirmed
> without the device, so the root cause is stated as "the generic defects found", not as
> a confirmed device diagnosis.

## 6. Fix (generic, OEM-independent, minimal)

| File | Change |
|---|---|
| `core/permission/ProtectionCapabilities.kt` **(new)** | `ProtectionCapabilities` + `ProtectionCapabilitySource` seam; `SettingsIntents` (usage/overlay/accessibility/notification/app-details); `AndroidSettingsStarter` (resolve-first, never throws); **generic `openWithFallback`** + `Context.openSettingsOrFallback` (falls back to the app details page when a settings page has no handler). |
| `core/usage/AndroidUsageAccess.kt` | Use `unsafeCheckOpNoThrow` on API 29+, `checkOpNoThrow` below; extract pure, tested `modeGrantsUsageAccess`. |
| `core/monitor/ForegroundAppMonitor.kt` | `hasUsageAccess()` now **delegates** to `AndroidUsageAccess` (single source of truth). |
| `core/accessibility/AccessibilityCapability.kt` | Match the enabled service via `ComponentName(service.packageName, service.name) == expected` (only Qalqon's own component). |
| `core/protection/ProtectionRuntime.kt` | Read the three capabilities through the seam; **bounded post-resume re-probe** (`CAPABILITY_REPROBE_ATTEMPTS = 3`, `INTERVAL = 700ms`) that stops early once all are granted — catches an OEM update that lands just after resume, and **never reports a false Ready** (every probe reads real system state). |
| `feature/protection/ProtectionScreen.kt`, `feature/home/HomeScreen.kt` | Route all settings deep-links through `openSettingsOrFallback` (no raw `startActivity` → no crash, generic fallback). |

No Xiaomi/HyperOS package, component or boolean is hardcoded. No legacy name changed. No
database, subscription, network or unrelated UI change.

## 7. Tests (JVM, deterministic)

`permission/SpecialPermissionCompatibilityTest` (10 tests):
- granted → ready / denied → not-ready for each of the three capabilities (via
  `ProtectionRuntimeState.degradedCapabilities`);
- `ProtectionCapabilities.allGranted` truth table;
- a fake `ProtectionCapabilitySource` drives readiness deterministically;
- settings fallback used **only** when the primary has no handler; both-fail is reported,
  never thrown;
- only `MODE_ALLOWED` grants Usage Access (mode mapping);
- source contracts: the UI uses the failure-safe launcher (no raw settings
  `startActivity`), the runtime re-probes after resume, and the accessibility check
  matches only Qalqon's component.

Counts: JVM suite **1917 → 1927** (+10), 0 failures / 0 errors / 0 skipped.

## 8. Real-device evidence
**BLOCKED.** No device/ADB in this environment. The required Redmi Note 14 before/after
matrix (§3) and the flow tests (Usage Access ON/OFF, Overlay ON/OFF, Accessibility
ON/OFF, 3/3 Ready → Protection ON → parent/child block) were **not executed**. To run
them: attach the device and use `tools/device-qa/device_qa.sh` plus the runbook in
`STAGE10_REAL_DEVICE_QA_72H_SOAK.md`.

## 9. Limitations
- The fix could not be validated on the Redmi Note 14 (or any device): **device
  verification BLOCKED**.
- Multi-OEM compatibility (Samsung/Pixel/OPPO/Vivo) is **not** claimed — only one device
  was reported and none is reachable here.
- The bounded re-probe is ~2 s; a device that updates far slower, or requires a full
  app restart after enabling accessibility, may still need the app to be reopened — the
  UI will honestly keep showing "Ruxsat berish" rather than a false Ready.
- The 72-hour soak remains BLOCKED (Stage 10 gate: permissions must be device-verified
  3/3 first).
