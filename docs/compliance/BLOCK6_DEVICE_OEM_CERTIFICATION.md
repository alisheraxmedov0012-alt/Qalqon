# QALQON — Release Block 6: Real Device / OEM Certification Matrix

> **Status: BLOCKED — REQUIRED PHYSICAL DEVICES UNAVAILABLE.** No physical device, no
> emulator and no KVM are available in the environment where this document was written
> (evidence in §1). Every real-device, OEM and soak row below is therefore `NOT RUN`
> (no hardware) or `BLOCKED`. Nothing here is a device-validated `PASS`.
>
> Automated/JVM/CI results prove code correctness only. They do **not** prove behaviour on
> a physical phone, under OEM background restrictions, or over a 24/48/72-hour soak.
>
> Scope: Release Completion **Block 6** — Real Device / OEM Certification.
> Branch: `feature/phase4-screen-time-complete`. Package `uz.faceguard.app`.
> This block does **not** close Block 3, 4 or 5.
>
> Detailed per-test *procedures* already exist and are referenced, not duplicated:
> `docs/STAGE3_DEVICE_COMPATIBILITY_MATRIX.md`, `docs/STAGE6_PERMISSION_OEM_COMPATIBILITY.md`,
> `docs/STAGE7_ANDROID_VERSION_COMPATIBILITY.md`,
> `docs/STAGE10_REAL_DEVICE_QA_72H_SOAK.md` (§5 method, §6 soak method),
> `docs/STAGE10_DEVICE_EVIDENCE.md`, `docs/STAGE10_DEVICE_FAILURE_LOG.md`,
> `docs/STAGE12_FINAL_DEVICE_CERTIFICATION.md`,
> `docs/compliance/BLOCK3_REAL_DEVICE_TEST_GUIDE.md`.

---

## 1. Starting state & environment inventory

| Field | Value |
|---|---|
| Branch | `feature/phase4-screen-time-complete` |
| HEAD at start | `3122b7f52d0ba28bcb9a3b0d27f102852fa6c491` |
| `git status` | clean ("nothing to commit, working tree clean") |
| compileSdk / targetSdk / minSdk | 36 / 36 / 26 |
| QALQON versionCode / versionName | 1 / `1.0.0` (debug) |

**Tooling actually present in this environment:**

| Capability | Present? | Evidence |
|---|---|---|
| `adb` | yes (binary) | `/opt/android-sdk/platform-tools/adb` |
| **Connected physical device** | **NO** | `adb devices` → empty list |
| **Android emulator binary** | **NO** | `/opt/android-sdk/emulator` does not exist |
| **KVM (hardware accel)** | **NO** | `/dev/kvm` absent |
| **System image (AVD)** | **NO** | `/opt/android-sdk/system-images` absent |
| JDK 17 | yes | `/opt/java/jdk-17.0.20.1+1` |

➡ **No physical device and no runnable emulator exist here.** Local
`:app:connectedDebugAndroidTest` cannot run. The only "device-like" execution available is
the CI emulator job (§6), which is an AOSP x86_64 API-35 emulator — **not** a physical OEM
device — and is known to be flaky.

### Device inventory (what has actually been tested)

| OEM | Model | Android | OEM UI | Available | Tested |
|---|---|---|---|---|---|
| Xiaomi / Redmi | Redmi Note 14 | 16 (reported by user, earlier task) | HyperOS 3.0.305.0 | reported, **not inspectable here** | **NOT RUN** |
| Samsung | — | — | One UI | no | **NOT RUN** |
| Google | Pixel | — | Pixel/AOSP | no | **NOT RUN** |
| OPPO | — | — | ColorOS | no | **NOT RUN** |
| Vivo | — | — | Funtouch/OriginOS | no | **NOT RUN** |
| Huawei | — | — | EMUI/HarmonyOS | no | **NOT RUN** |

**Missing for full coverage:** every Tier A device except the (uninspectable) Redmi report,
every Tier B device, and Android 13/14/16 physical coverage (STAGE3's AOSP emulator row was
API 35 only).

---

## 2. Test coverage — required matrix

Legend: **PASS** = verified with evidence · **FAIL** = proven defect · **NOT RUN** = no
device/emulator here · **BLOCKED** = cannot be executed in this environment.
Static analysis (source/manifest) is recorded separately and is **not** a device PASS.

Tier A = Xiaomi/Redmi, Samsung, Google Pixel. Tier B = OPPO, Vivo, Huawei. Versions = 13, 14, 15, 16.

### 2.1 Full matrix (Tier A × Android 13–16)

Each cell is `PASS / FAIL / BLOCKED / NOT RUN`. All are `NOT RUN` (no device). A single
emulator API-35 result (CI) is noted in §6; it does not satisfy any physical-device cell.

| Test ID | Category | Xiaomi/Redmi A13 | Xiaomi A14 | Xiaomi A15 | Xiaomi A16 | Samsung A13 | Samsung A14 | Samsung A15 | Samsung A16 | Pixel A13 | Pixel A14 | Pixel A15 | Pixel A16 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| C-ENR-01 | Parent enrollment | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN |
| C-ENR-02 | Child enrollment | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN |
| C-ENR-03 | Invalid/denied enrollment | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN |
| C-REC-01 | Parent recognised | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN |
| C-REC-02 | Child recognised | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN |
| C-REC-03 | Unknown face | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN |
| C-REC-04 | Multi-face (parent+child / +unknown) | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN |
| C-REC-05 | Lighting / angle / distance | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN |
| C-ACC-01 | Accessibility consent + enable | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN |
| C-ACC-02 | Accessibility revocation / recovery | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN |
| C-OVL-01 | Touch-blocking overlay vs visual fallback | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN |
| C-USE-01 | Usage Access grant/revoke | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN |
| C-FGS-01 | FGS start / notification / stop | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN |
| C-BG-01 | Background/process death recovery | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN |
| C-LOCK-01 | Screen lock/unlock | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN |
| C-BOOT-01 | Reboot restore | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN |
| C-CAM-01 | Camera recovery | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN |
| C-BATT-01 | Battery optimization / OEM restriction | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN |
| C-SYS-01 | Home/Recents/Back/shade/QS | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN |

### 2.2 Tier B (optional)

| Test ID | OEM | Coverage | Status |
|---|---|---|---|
| C-OPPO-01.. | OPPO (ColorOS) | A13–A16 | **NOT RUN** (no device) |
| C-VIVO-01.. | Vivo (Funtouch/OriginOS) | A13–A16 | **NOT RUN** (no device) |
| C-HW-01.. | Huawei (EMUI/HarmonyOS) | A13–A16 | **NOT RUN** (no device) |

**Huawei note:** Google Play services availability is device/region specific and must be
recorded per device. **No Google Play Billing test may be marked done** on a device without
Play services; billing is verified separately (Block 4).

### 2.3 Test-record template (fill per row when a device exists)

| Column | Meaning |
|---|---|
| Test ID | e.g. `C-ACC-01` |
| OEM / Model / Android / patch | e.g. Xiaomi / Redmi Note 14 / 16 / 2026-xx |
| OEM UI + version | HyperOS 3.0.305.0 |
| RAM / free storage | e.g. 8 GB / 40 GB |
| QALQON build | versionCode/Name + APK SHA-256 + install method |
| Preconditions / Steps | the exact actions |
| Expected (policy) | the expected protection policy for the identity |
| Actual | observed |
| Status | PASS / FAIL / BLOCKED / NOT RUN |
| Evidence | screen recording / logcat / `adb` output |
| Issue ID / Retest | link to §5 issue, retest status |

Full step-by-step procedures: `docs/compliance/BLOCK3_REAL_DEVICE_TEST_GUIDE.md`
(accessibility/consent/enforcement) and `docs/STAGE10_REAL_DEVICE_QA_72H_SOAK.md` §5
(install/reboot/lock/permissions/recognition) and §6 (soak).

---

## 3. Android 13–16 platform-contract check (static only)

Verified against source/manifest **without a device** — these are **STATIC ANALYSIS**, not
device PASSes. Each still requires device confirmation.

| # | Android | Claim | Evidence | Device status |
|---|---|---|---|---|
| P1 | 13 (33) | `POST_NOTIFICATIONS` is a runtime permission; requested only on explicit user action | `PlatformCompat.notificationRuntimePermissionRequired`; `RequestsScreen` | NOT RUN |
| P2 | 14 (34) | `specialUse` FGS type + `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` declared | `AndroidManifest.xml` service block | NOT RUN |
| P3 | 15 (35) | `camera` FGS type **not** started from `BOOT_COMPLETED`; boot claims `specialUse` only, camera claimed later in a legal foreground moment | `ProtectionForegroundService.startForegroundCompat(includeCamera=false)` at `onStartCommand`; `includeCamera=true` only at the foreground claim; `PlatformCompat.foregroundServiceTypes(sdk, includeCamera)` | NOT RUN |
| P4 | 14–16 | `foregroundServiceType="camera\|specialUse"` matches the declared `FOREGROUND_SERVICE_*` permissions | `AndroidManifest.xml` | NOT RUN |
| P5 | 13–16 | Overscoped/`QUERY_ALL_PACKAGES` absent; `<queries>` scoped to MAIN/LAUNCHER | `AndroidManifest.xml`; merged release manifest | NOT RUN |
| P6 | 13–16 | No `INTERNET` (offline-first) | merged release manifest: 0 active INTERNET | NOT RUN |
| P7 | 13–16 | Accessibility service guarded by `BIND_ACCESSIBILITY_SERVICE`; `canRetrieveWindowContent=false`; `isAccessibilityTool` unset | `AndroidManifest.xml`; `accessibility_service_config.xml` | NOT RUN |
| P8 | 13–16 | Accessibility-overlay touch-blocking vs visual `TYPE_APPLICATION_OVERLAY` (`FLAG_NOT_TOUCHABLE`) separation | `AccessibilityOverlayWindow` vs `OverlayControllerImpl` | NOT RUN |

> `targetSdk` is **36** (the STAGE3 matrix's "35 / NOT ASSESSED" rows are superseded here).

---

## 4. Soak test — 24h / 48h / 72h

| Stage | Status | Reason |
|---|---|---|
| 24 h | **BLOCKED** | No device; requires a continuously-running physical device |
| 48 h | **BLOCKED** | Only attempted if 24 h is clean |
| 72 h | **BLOCKED** | Only attempted if 48 h is clean |

No memory/CPU/battery/camera/FGS/recognition stability over time is claimed. The soak method
and per-interval observables are defined in `docs/STAGE10_REAL_DEVICE_QA_72H_SOAK.md` §6.
**A passing 24 h run would not substitute for the 48 h / 72 h stages.**

---

## 5. Issues found

**No device-specific defect can be reported** — none was reproduced, because no device is
available. Recording a device issue here without a device would be fabrication.

| ID | Severity | Issue | Evidence | Fix | Retest |
|---|---|---|---|---|---|
| — | — | None reproducible in this environment | — | — | — |

Known **non-device** intermittent CI flake (already reported in the Block 5 investigation,
outside Block 6 scope): `ScreenTimeCollectionLifecycleTest` /
`ScreenTimeLimitsConfigurationViewModelTest` occasionally fail on the CI emulator under load.
It is a timing flake in a screen-time test, not an OEM/device defect.

---

## 6. Automated verification performed (the only executable part)

| Check | Command | Result |
|---|---|---|
| JVM unit tests | `./gradlew :app:testDebugUnitTest --rerun-tasks` | **BUILD SUCCESSFUL — 2385 tests, 0 failures, 0 errors, 0 skipped** |
| Lint | `./gradlew :app:lintDebug --rerun-tasks` | **0 errors**, 168 warnings |
| Debug build | `./gradlew :app:assembleDebug` | SUCCESS |
| Release R8 | `./gradlew :app:minifyReleaseWithR8` | SUCCESS |
| Local instrumented | `:app:connectedDebugAndroidTest` | **NOT RUN** — no emulator/KVM/device |
| CI emulator (AOSP x86_64, API 35) | GitHub Actions `instrumented-tests` job | see §7 — **emulator, not a physical OEM device** |

---

## 7. Build / CI results

Automated (this block, on a clean toolchain):

- `:app:testDebugUnitTest --rerun-tasks` -> **BUILD SUCCESSFUL - 2385 tests, 0 failures, 0 errors, 0 skipped** (206 result files).
- `:app:lintDebug --rerun-tasks` -> **0 errors**, 168 pre-existing warnings.
- `:app:assembleDebug` -> SUCCESS.
- `:app:minifyReleaseWithR8` -> SUCCESS.
- Signing guard intact: `assembleRelease` still fails without signing material (not bypassed).
- Local `:app:connectedDebugAndroidTest` -> **NOT RUN** (no emulator/KVM/device).

CI (GitHub Actions, non-physical):

- For commit `3122b7f` (Block-5 HEAD): run **#236** - `build` job **success**; `instrumented-tests`
  job **failure** caused by the known, non-OEM intermittent flake
  `ScreenTimeCollectionLifecycleTest > signingOutStopsCollection`
  (`runtime active state expected:<true> but was:<false>`). This is an emulator-timing flake in a
  screen-time test, **not** a device/OEM defect.
- The CI emulator is an **AOSP x86_64 API-35** device. It is **not** a physical Xiaomi/Samsung/
  Pixel and cannot certify OEM behaviour, Android 13/14/16, reboot, or soak.
- The Block-6 push triggers a new run; its result is recorded in the Block-6 report.

---

## 8. Remaining risks / coverage gaps

| Gap | Impact | Unblock |
|---|---|---|
| No physical Xiaomi/Redmi (HyperOS) run | HyperOS background/autostart/memory-cleaner behaviour unverified | A Redmi device |
| No physical Samsung (One UI) run | One UI deep-sleep/background restrictions unverified | A Samsung device |
| No physical Pixel run | Reference baseline unverified | A Pixel device |
| No physical Android 13 / 14 / 16 run | Version-specific behaviour (esp. A16 FGS/boot) unverified; only AOSP API 35 emulator exists | Devices (or emulator images 33/34/36 via KVM CI) |
| No OPPO / Vivo / Huawei run | Tier B unverified; Huawei Play-services + billing untested | Devices |
| No reboot / process-death / lock / camera-recovery physical run | Recovery paths unverified | Devices |
| No 24/48/72 h soak | Long-run stability/battery unverified | Device + continuous monitoring |
| Accessibility/overlay/usage/protected-app behaviour | Only implied by JVM/contract tests | Devices + `docs/compliance/BLOCK3_REAL_DEVICE_TEST_GUIDE.md` |
| No real biometric FAR/FRR | Accuracy unmeasured | Labelled dataset + protocol |

**A single device's result must not be extended to another model or Android version.**

---

## 9. Revision of record

| Field | Value |
|---|---|
| Branch | `feature/phase4-screen-time-complete` |
| Prepared against commit | `3122b7f52d0ba28bcb9a3b0d27f102852fa6c491` |
| Date | 2026-10-11 |
| Status | **BLOCKED — REQUIRED PHYSICAL DEVICES UNAVAILABLE.** Not closed. |
