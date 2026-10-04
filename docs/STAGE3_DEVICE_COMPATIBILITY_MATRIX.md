# QALQON — Stage 3: Android System / OEM Compatibility Matrix

> Commercial Master Plan — Stage 3 deliverable.
> Baseline: `feature/phase4-screen-time-complete` @
> `2404d4df3a8db738ae93fc04bfe5e915c07de910` (Stage 2, PASS WITH LIMITATIONS).
>
> **Honesty contract.** No `PASS` is recorded here for anything that was not
> executed. This document distinguishes, and never blurs:
>
> - `REAL DEVICE VERIFIED` — executed on physical hardware.
> - `EMULATOR VERIFIED` — executed on an Android emulator.
> - `CI EMULATOR` — executed by the repository CI on its API 35 emulator.
> - `STATIC ANALYSIS ONLY` — derived from the manifest/source/platform contracts,
>   not executed.
> - `NOT TESTED` — no device, emulator or automation was available.
>
> In this environment there was **no physical device, no emulator, no `/dev/kvm`
> and no CPU virtualization**, so no real-device row can honestly be marked
> `PASS`. Every OEM row below is `NOT TESTED`, and the code-level findings are
> `STATIC ANALYSIS ONLY` unless a JVM test is named.

---

## 1. Scope

In scope: Android/OEM compatibility, lifecycle reliability, background execution,
foreground service, reboot recovery, Doze, battery optimization, background
restrictions, Accessibility, overlay, permission degradation, camera lifecycle,
lock screen, screen OFF/ON, rotation, split-screen/multi-window, notification
behaviour, and process death/recovery.

Out of scope (reported as `OUT OF SCOPE — FUTURE STAGE`): anti-spoofing/liveness
models (Stage 5), security/privacy hardening (Stage 4), subscription/trial
(Stage 7), Play compliance (Stage 8), UX redesign (Stage 6), backend/network,
telemetry, analytics, pricing, and any new roadmap phase.

---

## 2. Environment evidence

| Capability | Present | Evidence |
|---|---|---|
| Physical Android device | **No** | `adb` absent; no device attached |
| Android emulator | **No** | `emulator` package not installed; no AVD |
| `/dev/kvm` | **No** | `ls /dev/kvm` → not found |
| CPU virtualization (vmx/svm) | **No** | `grep -c 'vmx\|svm' /proc/cpuinfo` → 0 |
| JVM unit tests | **Yes** | `:app:testDebugUnitTest` (JDK 17 + Android SDK 35) |
| CI instrumentation (API 35 emulator) | Configured | `.github/workflows/build.yml` → `connectedDebugAndroidTest` on push/PR |

---

## 3. Android API compatibility

Status per API level is **static analysis of the platform contract against the
manifest/source** unless a real-device or emulator run is noted. No row is
`PASS` because none was executed.

| API | Android | Declared support | Assessment | Status |
|---|---|---|---|---|
| 26 | 8.0 Oreo | minSdk | FGS started via `ServiceCompat.startForeground` (no type bit below API 29); `TYPE_APPLICATION_OVERLAY` (O+) used; background-execution limits satisfied by the FGS; `SYSTEM_ALERT_WINDOW` special access read via `Settings.canDrawOverlays`. | STATIC ANALYSIS ONLY |
| 28 | 9 Pie | Included | Same as API 26. | STATIC ANALYSIS ONLY |
| 29 | 10 | Included | `foregroundServiceType` exists (ignored unless a type bit is supplied); manifest type bits below their API are omitted. | STATIC ANALYSIS ONLY |
| 30 | 11 | Included | Camera FGS type available (`FOREGROUND_SERVICE_TYPE_CAMERA`, API 30+); camera type claimed only in a legal while-in-use moment. `<queries>` launcher visibility declared. | STATIC ANALYSIS ONLY |
| 31 | 12 | Included | FGS background-start restriction (`ForegroundServiceStartNotAllowedException`); both the launcher and the boot path are wrapped, and a rejected start degrades instead of crashing. | STATIC ANALYSIS ONLY |
| 33 | 13 | Included | `POST_NOTIFICATIONS` runtime permission (requested from the Requests screen); FGS still runs if it is denied (notification suppressed only). | STATIC ANALYSIS ONLY |
| 34 | 14 | Included | Mandatory FGS types; camera type requires while-in-use + `FOREGROUND_SERVICE_CAMERA` (declared); `RECEIVER_EXPORTED/NOT_EXPORTED` required **except** for receivers registered only for protected system broadcasts — `ScanScheduler` registers only `ACTION_SCREEN_ON`, which is protected, so no flag is required (verified against the platform exception). | STATIC ANALYSIS ONLY |
| 35 | 15 | compileSdk/targetSdk | `camera`/`dataSync`/`media*` FGS starts are forbidden from `BOOT_COMPLETED`; the boot path claims **only** `specialUse`, and the `camera` type is claimed later from a foreground moment. `specialUse` requires a subtype property (declared). | CI EMULATOR (pending); otherwise STATIC ANALYSIS ONLY |
| 36+ | 16+ | Not targeted | compileSdk 35 / targetSdk 35. Not assessed; no SDK 36 platform or runtime was available. | NOT TESTED |

---

## 4. OEM compatibility

No OEM device was available. Every row is `NOT TESTED`; the "Expected risk"
column records the *documented* OEM behaviour Stage 3 must later verify — it is
**not** an observed result.

| OEM | Device present | Known OEM risk to verify | Status |
|---|---|---|---|
| Google Pixel | No | Reference device; baseline Doze/battery behaviour. | NOT TESTED |
| Samsung (One UI) | No | Sleeping/deep-sleeping apps, background restrictions, autostart. | NOT TESTED |
| Xiaomi / Redmi (MIUI/HyperOS) | No | Memory cleaner, autostart, battery saver, process killing. | NOT TESTED |
| OnePlus (OxygenOS) | No | Aggressive background kill, battery optimization. | NOT TESTED |
| OPPO (ColorOS) | No | Background freeze, autostart. | NOT TESTED |
| Vivo (Funtouch/OriginOS) | No | Background restrictions, autostart. | NOT TESTED |
| Realme (realme UI) | No | Background kill (ColorOS-derived). | NOT TESTED |
| Huawei (EMUI/HarmonyOS) | No | Protected apps list, background restrictions (no Play services). | NOT TESTED |

---

## 5. System behaviour

All rows are `NOT TESTED` for real hardware. The "Static finding" column records
what the code/or platform contract implies and what a real-device pass must
confirm.

| Behaviour | Static finding | Status |
|---|---|---|
| Doze | FGS + camera session are process-scoped; no `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` and no `WAKE_LOCK` are declared, so during Doze the CPU/network are idle while the FGS persists. Recognition depends on the camera, whose frame freshness TTL (1.5 s) treats a stalled camera as no-face (fail-closed). | NOT TESTED |
| Battery optimization | No exemption is requested; the app relies on the FGS. On OEMs that ignore FGS or kill on battery-saver, protection can stop silently — must be measured per OEM. | NOT TESTED |
| Background | FGS keeps the process alive; starting from background is guarded; `START_NOT_STICKY` (Stage 2 decision) means no system resurrection after a process kill. | NOT TESTED |
| Foreground service | `specialUse` base type at start; `camera` type claimed only in a legal foreground moment; idempotent start/stop; `START_NOT_STICKY` retained deliberately. | NOT TESTED |
| Accessibility | Service owns the touch-consuming `TYPE_ACCESSIBILITY_OVERLAY`; if disabled, blocking degrades to the visual-only legacy scrim and `ProtectionCapability.ACCESSIBILITY` is reported missing. | NOT TESTED |
| Overlay | Accessibility overlay is preferred; legacy `TYPE_APPLICATION_OVERLAY` fallback is `FLAG_NOT_TOUCHABLE` (visual only). **Stage 3 fix**: the legacy Compose overlay now installs its `ViewTreeLifecycleOwner`/`ViewModelStoreOwner`/`SavedStateRegistryOwner`, which a Service-hosted `ComposeView` requires — previously it could not render. | JVM VERIFIED (`LegacyOverlayHostTest`); attach path NOT TESTED |
| Usage Access | Read-only app-op check; poll failures are isolated (Stage 2); revocation keeps the last known package rather than flapping. | NOT TESTED |
| Reboot | `BOOT_COMPLETED` → `ProtectionBootReceiver` → `ProtectionBootRestorer` → FGS (`specialUse` only) → existing runtime. Camera type cannot be claimed from boot, so recognition is limited until the app is next opened (`cameraLimitedAfterBoot`, already surfaced to the parent). | NOT TESTED |
| Lock screen | No dedicated lock/unlock handling; `ACTION_SCREEN_ON` re-arms a scan. State is held by the singleton runtime and re-derived. | NOT TESTED |
| Screen OFF/ON | No `ACTION_SCREEN_OFF` handling; the camera session is latched and the runtime keeps evaluating. Battery behaviour while the screen is off is unmeasured. | NOT TESTED |
| Rotation | `MainActivity` handles `orientation\|screenSize\|keyboardHidden`, so rotation does not recreate the Activity; the runtime/camera session are process-scoped, so protection state is not reset. | STATIC ANALYSIS ONLY |
| Split screen | `screenSize` is handled so resizes do not recreate the Activity; `smallestScreenSize`/`screenLayout` are not declared, so a multi-window transition may still recreate it (state is re-derived, so protection is unaffected). Foreground detection during multi-window is OEM-dependent. | STATIC ANALYSIS ONLY |
| Notifications | Dedicated low-importance channel; the FGS notification is `ongoing` and silent; `POST_NOTIFICATIONS` denial suppresses display but not the service. Notification loss must not be equated with protection loss. | NOT TESTED |
| Process death | Runtime state is in-memory and re-derived from DataStore/Room; no duplicate runtime/camera session on re-entry (idempotent). After a kill with no user interaction, nothing resurrects the process (`START_NOT_STICKY`). | NOT TESTED |

---

## 6. Permission degradation

Each capability is re-probed from the application context; a loss is reported as
a missing capability (`degraded`) rather than shown as "fully protected". The
behaviour below is `STATIC ANALYSIS ONLY` unless a JVM test is named.

| Capability | granted → revoked | Crash? | Protection state | Status |
|---|---|---|---|---|
| Camera | permission removed | No code crash; frames stop, freshness TTL → no-face (fail-closed) | `cameraGranted=false`, CAMERA reported missing | STATIC ANALYSIS ONLY |
| Usage Access | app-op revoked | No; poll isolated (Stage 2) | `usageAccessGranted=false`, USAGE_ACCESS missing | STATIC ANALYSIS ONLY |
| Overlay | `SYSTEM_ALERT_WINDOW` revoked | No; `showLegacy()` early-returns | OVERLAY reported missing | STATIC ANALYSIS ONLY |
| Notifications | `POST_NOTIFICATIONS` denied | No | service runs, notification hidden | STATIC ANALYSIS ONLY |
| Accessibility | service disabled | No; registry unregisters | ACCESSIBILITY missing, block becomes cosmetic | STATIC ANALYSIS ONLY |
| Battery optimization | restricted | No | depends on OEM kill behaviour | NOT TESTED |

---

## 7. Device Compatibility Matrix

Legend: `✅` verified (with the evidence named), `—` not applicable / not
exercised, `❌` failed, `NT` **NOT TESTED** (no device/emulator).

| Manufacturer | Model | Android | API | OEM skin | Protection ON/OFF | Foreground Service | Background | Doze | Battery Opt. | Overlay | Accessibility | Usage Access | Reboot | Lock Screen | Screen OFF/ON | Rotation | Split Screen | Notification | Process Death | Recovery | Required Config | Result | Known Limitation |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Google | CI emulator (x86_64) | 15 | 35 | AOSP | NT | NT | NT | NT | NT | NT | NT | NT | NT | NT | NT | NT | NT | NT | NT | NT | — | CI EMULATOR (instrumented job; pending this run) | Instrumented tests, not real hardware |
| Google | Pixel | — | — | Pixel UI | NT | NT | NT | NT | NT | NT | NT | NT | NT | NT | NT | NT | NT | NT | NT | NT | — | NOT TESTED | No device |
| Samsung | Galaxy | — | — | One UI | NT | NT | NT | NT | NT | NT | NT | NT | NT | NT | NT | NT | NT | NT | NT | NT | Likely: exempt battery/background restrictions | NOT TESTED | No device |
| Xiaomi/Redmi | — | — | — | MIUI/HyperOS | NT | NT | NT | NT | NT | NT | NT | NT | NT | NT | NT | NT | NT | NT | NT | NT | Likely: autostart + no battery saver | NOT TESTED | No device |
| OnePlus | — | — | — | OxygenOS | NT | NT | NT | NT | NT | NT | NT | NT | NT | NT | NT | NT | NT | NT | NT | NT | Unknown | NOT TESTED | No device |
| OPPO | — | — | — | ColorOS | NT | NT | NT | NT | NT | NT | NT | NT | NT | NT | NT | NT | NT | NT | NT | NT | Unknown | NOT TESTED | No device |
| Vivo | — | — | — | Funtouch/OriginOS | NT | NT | NT | NT | NT | NT | NT | NT | NT | NT | NT | NT | NT | NT | NT | NT | Unknown | NOT TESTED | No device |
| Realme | — | — | — | realme UI | NT | NT | NT | NT | NT | NT | NT | NT | NT | NT | NT | NT | NT | NT | NT | NT | Unknown | NOT TESTED | No device |
| Huawei | — | — | — | EMUI/HarmonyOS | NT | NT | NT | NT | NT | NT | NT | NT | NT | NT | NT | NT | NT | NT | NT | NT | Unknown | NOT TESTED | No device |

The JVM rows that *were* executed are recorded in §8, not here: they verify
code-level invariants, not device behaviour.

---

## 8. Bugs found and fixed in Stage 3

| # | Severity | Category | Finding | Fix | Regression coverage |
|---|---|---|---|---|---|
| 1 | P1 | QALQON BUG | The legacy `TYPE_APPLICATION_OVERLAY` scrim is a Compose view hosted directly in `WindowManager` from an application context. A Service-hosted `ComposeView` has no `ViewTreeLifecycleOwner`, so Compose throws `IllegalStateException: ViewTreeLifecycleOwner not found` the moment the view attaches. The fallback therefore could not render (and, when the attach ran after `addView` returned, could crash the process). | Install `ViewTreeLifecycleOwner`, `ViewTreeViewModelStoreOwner` and `ViewTreeSavedStateRegistryOwner` on the overlay view before `setContent`, backed by a real owner (INITIALIZED → RESUMED → DESTROYED) that is torn down with the window. | `LegacyOverlayHostTest` (source contract + owner lifecycle) |
| 2 | — | — | `ScanScheduler` registers a receiver for `ACTION_SCREEN_ON` without an export flag. | **No change**: the Android 14 requirement exempts receivers registered only for protected system broadcasts, and `ACTION_SCREEN_ON` is protected. Verified against the platform exception, not assumed. | — |

No other deterministic code-level compatibility bug was reproducible from the
repository alone. Behaviour that can only be confirmed on hardware (OEM process
kills, Doze timing, multi-window foreground detection) is recorded as
`NOT TESTED`, never as a pass.

---

## 9. Known limitations (verified or documented, never guessed)

1. **`START_NOT_STICKY`** is retained (Stage 2 decision). After an OS/OEM process
   kill with no user interaction, protection is not resurrected until the app is
   reopened or the device reboots. Whether to switch to `START_STICKY` (and on
   which APIs/OEMs it helps) requires real-device evidence — `NOT TESTED`.
2. **Post-reboot recognition** is camera-limited until the app is next opened,
   because the camera FGS type may not be claimed from `BOOT_COMPLETED`.
3. **The legacy overlay is visual-only** (`FLAG_NOT_TOUCHABLE`); real input
   blocking requires the Accessibility service. Reported honestly as degraded.
4. **OEM background killers** are unmeasured; on aggressive skins protection may
   require user-configured battery/autostart exemptions
   (`PASS WITH CONFIGURATION` at best, once a device is available).
5. **Multi-window foreground detection** depends on the accessibility/usage-stats
   source and is OEM-dependent.
6. **Android 16 / API 36** was not assessed (no SDK 36 platform or runtime).

---

## 10. Reproduction

```bash
# JVM verification (executed): 1834 baseline + 5 new Stage 3 tests = 1839
# tests, 0 failures / 0 errors / 0 skipped
./gradlew :app:testDebugUnitTest --no-daemon

# Debug assembly (executed): app/build/outputs/apk/debug/app-debug.apk
# (also compiles the instrumented-test APK via the build bridge)
./gradlew :app:assembleDebug --no-daemon

# Release compilation succeeds, but packaging is intentionally gated on the
# release signing secrets (QALQON_RELEASE_STORE_FILE/_PASSWORD, _KEY_ALIAS,
# _KEY_PASSWORD): packageRelease fails loudly by design, so no unsigned APK can
# be mistaken for a production build.
./gradlew :app:assembleRelease --no-daemon   # → packageRelease FAILS (signing gate)

# Instrumented verification — CI only (needs an API 35 emulator + KVM)
./gradlew :app:connectedDebugAndroidTest
```
