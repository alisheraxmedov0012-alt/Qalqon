# QALQON — Stage 7: Android Version Compatibility

> Stage 7 deliverable (roadmap 7/12). Goal: not "the app compiles", but "protection's
> permission, camera, FGS, background, overlay, accessibility, notification and
> lifecycle behaviour stays correct as the Android version changes", across
> **API 26 (Android 8.0) → API 36 (Android 16)**.
>
> **Honesty contract.** The only runtime evidence in this environment is the CI
> **API 35 emulator** (`instrumented tests`). No API 26–34 or API 36 emulator is
> available here, and there is **no physical device / ADB**. Every level that was not
> executed is marked **NOT TESTED**; nothing is claimed as VERIFIED without a run.

---

## 1. Audit (before this change)

| Area | Finding |
|---|---|
| SDK gates | 11 `Build.VERSION.SDK_INT` checks, all using named `VERSION_CODES.*`; no bare literals. |
| FGS types | Correct (`camera` from API 30, `specialUse` from API 34) but buried in the Service — **not testable per API**. |
| Overlay | `TYPE_APPLICATION_OVERLAY` (O+) / `TYPE_PHONE` fallback; `canDrawOverlays` M gate. |
| **Display cutout** | **Missing.** Full-screen blocking overlays used `FLAG_LAYOUT_IN_SCREEN` only, so on API 28+ notched devices a strip above the block could remain uncovered. |
| Runtime receivers | Registered for **system broadcasts** (SCREEN_ON/OFF, USER_PRESENT) without an export flag. Legal (system broadcasts are exempt from the API 34 requirement) but implicit. |
| **Edge-to-edge** | **Unhandled.** `targetSdk = 36`, so Android 15 (API 35) *enforces* edge-to-edge; behaviour differed between API < 35 and 35+. |
| Notification | `POST_NOTIFICATIONS` gated at API 33. |
| Manifest | Permissions/components correct; `exported` set on every component; INTERNET removed. |
| PendingIntent | Both `FLAG_IMMUTABLE`. |
| Storage / Keystore / Biometric | Room (additive migrations), DataStore, AES-GCM Keystore, `BIOMETRIC_STRONG` with PIN fallback — all API 26-compatible. |

No crash-prone version bug was found; the gaps were **cutout coverage** and
**edge-to-edge consistency**, plus testability of the FGS/cutout matrices.

---

## 2. What changed

1. **`core/compat/PlatformCompat.kt`** — the version matrix as pure, Android-free
   functions, so every level is asserted on the JVM:
   - `foregroundServiceTypes(sdkInt, includeCamera)` (camera from 30, specialUse from 34),
   - `fullscreenOverlayCutoutMode(sdkInt)` (default < 28, shortEdges 28–29, always 30+),
   - `notificationRuntimePermissionRequired(sdkInt)` (≥ 33),
   - `supportsUnsafeCheckOp` (≥ 29), `supportsDisplayCutoutMode` (≥ 28).
2. **`ProtectionForegroundService`** now delegates its type mask to `PlatformCompat`.
3. **Display-cutout coverage** added to both full-screen overlays
   (`AccessibilityOverlayWindow`, legacy `OverlayControllerImpl`), gated at API 28.
4. **Runtime receivers** moved to `ContextCompat.registerReceiver(..., RECEIVER_NOT_EXPORTED)`
   (system broadcasts; the compat call supplies the export flag only where the platform
   requires it, API 34+).
5. **`MainActivity` calls `enableEdgeToEdge()`** so system-bar behaviour is uniform across
   API 26–36 instead of edge-to-edge only from API 35.
6. Notification gate constant now re-exports `PlatformCompat.NOTIFICATION_PERMISSION_API`.

Stage 1–6 mechanisms are untouched.

---

## 3. Android Version Matrix

> **Tested?** = executed in this environment. Only the CI API 35 emulator runs.

| Android | API | Tested? | Camera | FGS | Accessibility | Overlay | Usage | Notification | Background | Result |
|---|---|---|---|---|---|---|---|---|---|---|
| 8.0 | 26 | No | — | — | — | — | — | — | — | **NOT TESTED** |
| 8.1 | 27 | No | — | — | — | — | — | — | — | **NOT TESTED** |
| 9 | 28 | No | — | — | — | — | — | — | — | **NOT TESTED** |
| 10 | 29 | No | — | — | — | — | — | — | — | **NOT TESTED** |
| 11 | 30 | No | — | — | — | — | — | — | — | **NOT TESTED** |
| 12 | 31 | No | — | — | — | — | — | — | — | **NOT TESTED** |
| 12L | 32 | No | — | — | — | — | — | — | — | **NOT TESTED** |
| 13 | 33 | No | — | — | — | — | — | — | — | **NOT TESTED** |
| 14 | 34 | No | — | — | — | — | — | — | — | **NOT TESTED** |
| 15 | 35 | **Emulator (CI)** | struct | struct | struct | struct | struct | struct | struct | **PARTIALLY VERIFIED (emulator)** |
| 16 | 36 | No | — | — | — | — | — | — | — | **NOT TESTED** |

"struct" = structural/compile-time verification (manifest, matrix, JVM suite). API 35
instrumentation passing is **not** a certification of every Android 15 physical device.

---

## 4. Version-Specific Findings

| # | Issue | Root cause | Fix | Test |
|---|---|---|---|---|
| 1 | Full-screen block could leave the notch strip uncovered | `FLAG_LAYOUT_IN_SCREEN` does not cover display cutouts; no `layoutInDisplayCutoutMode` set | Set the cutout mode (API 28+) from `PlatformCompat` on both overlays | `PlatformCompatTest`, `Stage7VersionGateHygieneTest`, instrumented cutout check |
| 2 | Edge-to-edge differed between API < 35 and 35+ | `targetSdk = 36` enforces edge-to-edge from API 35; nothing normalised it | `enableEdgeToEdge()` in `MainActivity` | build + structural (visual NOT TESTED) |
| 3 | FGS type matrix was untestable per API | Gate lived inside the Service using the runtime `SDK_INT` | Extracted to pure `PlatformCompat.foregroundServiceTypes` | `PlatformCompatTest` (every API 26–36) |
| 4 | Runtime receiver export behaviour implicit | Un-flagged `registerReceiver` for system broadcasts | `ContextCompat.registerReceiver(..., RECEIVER_NOT_EXPORTED)` | `Stage7PermissionGateTest` |

---

## 5. Permission Matrix

| Capability | Kind | Required from | Notes |
|---|---|---|---|
| Camera | Runtime permission | API 26 (always) | `uses-feature camera required=false` |
| `POST_NOTIFICATIONS` | Runtime permission | **API 33** | Below 33 it is implicitly granted |
| `FOREGROUND_SERVICE` | Normal permission | API 26 | Base FGS |
| `FOREGROUND_SERVICE_CAMERA` | Normal permission | API 30 (type) | Required to run the camera FGS |
| `FOREGROUND_SERVICE_SPECIAL_USE` | Normal permission | API 34 (type) | With `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` |
| Draw-over-other-apps | Special access (settings) | API 26 (settings from 23) | `Settings.canDrawOverlays` |
| Usage Access | App-op (settings) | API 26 | `unsafeCheckOpNoThrow` from API 29 |
| Accessibility | User-enabled service | API 26 | No runtime request possible |
| Battery optimization | Settings (recommended) | API 23 | Read-only; never forced |
| `RECEIVE_BOOT_COMPLETED` | Normal permission | API 26 | Boot restore |

---

## 6. Manifest audit (summary)

- **Permissions:** CAMERA, PACKAGE_USAGE_STATS, SYSTEM_ALERT_WINDOW, FOREGROUND_SERVICE,
  FOREGROUND_SERVICE_CAMERA, FOREGROUND_SERVICE_SPECIAL_USE, POST_NOTIFICATIONS,
  RECEIVE_BOOT_COMPLETED. **INTERNET / ACCESS_NETWORK_STATE removed** (`tools:node="remove"`).
  No location/storage/media/exact-alarm/battery-dialog permission.
- **Services:** `ProtectionForegroundService` (`exported=false`, `stopWithTask=false`,
  `foregroundServiceType="camera|specialUse"`, subtype property);
  `ProtectionAccessibilityService` (`exported=true`, `BIND_ACCESSIBILITY_SERVICE`, config meta-data).
- **Receiver:** `ProtectionBootReceiver` (`exported=true`, `BOOT_COMPLETED`).
- **Activity:** `MainActivity` (`exported=true`, launcher).
- **Queries:** scoped to `MAIN`/`LAUNCHER` only.
- Telemetry backend (`datatransport`) removed.

---

## 7. Storage / Data

- **Room** schema v10, additive migrations only (`addMigrations`), no destructive fallback.
- **DataStore** preferences (no `SharedPreferences`).
- **Keystore** AES-256-GCM (`AES/GCM/NoPadding`, random nonce), device-bound.
- **Files:** app-private only; scoped storage changes (API 29/30) do not affect QALQON
  because it never touches shared/external storage.
- **Backup:** `allowBackup="false"` (covers < API 31) + `dataExtractionRules` excluding
  every domain (covers API 31+ D2D transfer).

## 8. Security

- Both `PendingIntent`s use `FLAG_IMMUTABLE` (required from API 31); no `FLAG_MUTABLE`.
- The accessibility service is bound only by the system (`BIND_ACCESSIBILITY_SERVICE`);
  `canRetrieveWindowContent=false`.
- Every component declares `exported` (mandatory from API 31).
- Biometric uses `BIOMETRIC_STRONG`, degrading to the QALQON PIN on every failure mode.

---

## 9. Tests

- JVM: **2181 / 0 failures** (was 2126 → **+55**).
  New: `PlatformCompatTest` (13), `Stage7ManifestAuditTest` (12),
  `Stage7DataAndSecurityCompatTest` (9), `Stage7VersionGateHygieneTest` (7),
  `Stage7PermissionGateTest` (9), `Stage7RegressionGuardTest` (7).
- Instrumented: `Stage7VersionCompatInstrumentedTest` (6) — executed in CI on API 35.
- lint / assembleDebug / CI: see the report.

**Emulator APIs tested:** API 35 (CI). All others NOT TESTED.

---

## 10. Real device validation

**REAL DEVICE TEST = NO.** No physical device and no ADB.

---

## 11. Known limitations

- Only API 35 is executed (emulator); API 26–34 and 36 are compile/structural only.
- Edge-to-edge and inset rendering are not visually verified.
- Pre-API-34 device-specific OEM behaviour is out of scope here (Stage 6) and not re-run.
- No speculative Android 16 workaround was added beyond documented platform behaviour.
