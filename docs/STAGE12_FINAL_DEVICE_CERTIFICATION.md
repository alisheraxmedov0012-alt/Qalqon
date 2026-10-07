# QALQON — Stage 12: Final Device Certification & Release

> Stage 12 (roadmap **12/12 — final**). This is a **certification report**, not a
> success-generation exercise. Everything that could not be executed with the available
> hardware/accounts/credentials is marked **NOT AVAILABLE / NOT TESTED / NOT VERIFIED**.
>
> **Environment truth:** this runtime has **no physical device, no `adb`, no `/dev/kvm`
> (so no emulator can run), no Play Console access, and no production signing
> credentials.** Only the JVM suite, `lint`, the debug/release builds and the AAB are
> genuinely executable here.

---

## 1. Stage status

| | |
|---|---|
| Stage | **12/12 — Final Device Certification & Release** |
| Decision | **PASS WITH LIMITATIONS** (see §31) |

## 2. Git / baseline

| | |
|---|---|
| Branch | `feature/phase4-screen-time-complete` |
| Baseline (Stage 11 HEAD) | `81368b38b1b23c10b75017f7f43ffd567310a5c7` — "stage11: prepare Play Store & international launch artifacts" |
| Working tree at baseline | clean (start of Stage 12) |
| Push | pushed to `origin/feature/phase4-screen-time-complete` |

## 3. Device matrix

| Device | OEM | Android | API | Physical/Emulator | Result |
|---|---|---|---|---|---|
| — | — | — | — | — | **NOT AVAILABLE** |

No physical device and no `adb` exist in this environment; `/dev/kvm` is absent so the
emulator cannot run. **PHYSICAL DEVICE CERTIFICATION = NOT AVAILABLE.** No emulator PASS
is reported (none was run).

## 4. Android / API matrix

| API | Level | Coverage in this environment |
|---|---|---|
| 26–28 | 8.0–9 | NOT TESTED (no device/emulator) |
| 29–32 | 10–12L | NOT TESTED |
| 33 | 13 | NOT TESTED |
| 34 | 14 | NOT TESTED |
| 35 | 15 | **CI emulator only** (instrumented suite, GitHub Actions) — not a physical PASS |
| 36 | 16 | NOT TESTED on device; `targetSdk 36` compile/build verified |

Stage 7 statically asserts the cross-version behaviour matrix on the JVM for API 26–36.

## 5. Install / update / uninstall

**NOT TESTED** (no device). Not verifiable here: fresh install, upgrade/migration,
DataStore/Keystore preservation, reinstall, uninstall residue. Room migrations and
deletion/reset are covered by JVM + instrumented (CI) tests only.

## 6. Parent recognition

**NOT TESTED** (no camera/device). Enroll/parent-recognised/no-false-block behaviour was
not exercised on hardware.

## 7. Child recognition

**NOT TESTED**. Real-camera recognition, protection activation, 500 ms re-assertion and
recovery on child-leaves were not exercised on hardware.

## 8. Unknown face

**NOT TESTED** on device. Policy mapping covered by JVM tests (Stage 1–2).

## 9. No-face

**NOT TESTED** on device. Fail-closed no-face policy covered by JVM tests.

## 10. Multi-face

**NOT TESTED** on device. `CHILD > PARENT > UNKNOWN > NO_FACE` precedence and order
independence are covered by JVM tests (Stage 2).

## 11. Liveness / anti-spoof

**NOT TESTED** on device. **ANTI-SPOOF MODEL = NOT PRODUCTION-CERTIFIED** — the build
ships a passive heuristic/`AntiSpoofModel` seam but no production anti-spoof model. This
limitation is unchanged from Stage 3 and is **not** converted into a PASS.

## 12. Protection enforcement

**NOT TESTED** on device. Enforcement architecture (accessibility overlay touch-blocking,
visual fallback, 500 ms self-heal re-assert) is covered by unit/instrumented tests only.

## 13. System UI (Home/Back/Recents/shade/QS/PiP/split-screen)

**NOT TESTED** on device. Known Android limitation: a consumer app cannot prevent system
UI (Home, Recents, notification shade, Quick Settings, power menu, split-screen/PiP)
bypassing the overlay. Documented as an **expected Android limitation**, not a release
blocker (would require Device Owner/Kiosk, which is an architectural change out of scope).

## 14. Permissions (camera/accessibility/usage/overlay/notifications/battery)

**NOT TESTED** on device (grant/deny/revoke/re-grant flows). The **no-false-READY**
guarantee (Stage 8 `ProtectionReadiness`) is covered by JVM tests. Static manifest and
declaration correctness verified (`PlayComplianceContractTest`).

## 15. OEM compatibility

**NOT TESTED.** No Xiaomi/Redmi, Samsung, or other OEM device is available. OEM guidance
and settings-fallback resolution are covered only by JVM/static tests (Stage 6).

## 16. Reboot

**NOT TESTED** on device. Boot-restore path covered by JVM tests; the Android-imposed
camera-limited-until-first-foreground limitation is documented (Stage 5).

## 17. Process death / Recents / force-stop

**NOT TESTED** on device. Documented: normal process death → best-effort recovery;
**force stop → Android intentionally prevents automatic restart** (not claimed as
recoverable).

## 18. Background / long-run

**NOT TESTED** on device (screen on/off cycles, app switching, memory/ANR/overlay
longevity).

## 19–21. Soak tests

| Test | Duration | Result |
|---|---|---|
| 24h soak | — | **NOT COMPLETED** |
| 48h soak | — | **NOT COMPLETED** |
| 72h soak | — | **NOT COMPLETED** |

No soak was performed (no device). No short test is presented as a 72h certification.

## 22. Billing / subscription

**REAL BILLING = NOT VERIFIED.** No Play Console access, no license tester, no device.
Purchase/trial/acknowledgement/restore/reinstall/cancellation were not exercised against
Google Play. The entitlement logic (product-identity check, no local-flag authority,
no false Premium) is covered by JVM tests (Stage 9) only.

## 23. Account / data deletion

**Logical verification PASS (test-level); device verification NOT TESTED.** The in-app
reset deletes Room tables, DataStore state, the biometric encryption key, and the
session; verified by `Stage4DeletionAndBackupTest` + `PlayComplianceContractTest`
(JVM/instrumented). Logout ≠ delete. The external web deletion resource remains
**MISSING** (Play launch item).

## 24. Privacy / security final check (release artifact)

Inspected the **actual final AAB/APK**, not only source:

| Check | Result |
|---|---|
| No `INTERNET` permission | ✅ absent (AAB + APK) |
| No `ACCESS_NETWORK_STATE` / location | ✅ absent |
| No `android:debuggable` | ✅ absent |
| R8 minification + resource shrinking | ✅ enabled; `proguard.map` + `r8.json` in AAB |
| Backup/D2D exclusions | ✅ intact (11 domain excludes) |
| Biometric templates encrypted (no plaintext persistence) | ✅ (Stage 10; no raw-image/storage code) |
| No hardcoded secrets / tokens in repo | ✅ clean scan |
| Debug routes reachable in release | ✅ **not reachable** — the debug composables are registered only under `if (BuildConfig.DEBUG)` (false in release); the surviving `recognition_debug`/`settings_developer` **strings** in the dex come from the static PIN-gate allow-list (`PROTECTED_ROUTE_PREFIXES`), which creates no destination |
| No fake billing path in release | ✅ (Stage 9) |

## 25. Final release artifact — APK

| Field | Value |
|---|---|
| File | `app/build/outputs/apk/release/app-release.apk` (61 MB) |
| applicationId | `uz.faceguard.app` |
| versionCode / versionName | `1` / `1.0.0` |
| compileSdk / targetSdk / minSdk | 36 / 36 / 26 |
| Permissions | CAMERA, PACKAGE_USAGE_STATS, SYSTEM_ALERT_WINDOW, FOREGROUND_SERVICE(+CAMERA,+SPECIAL_USE), POST_NOTIFICATIONS, RECEIVE_BOOT_COMPLETED, USE_BIOMETRIC, USE_FINGERPRINT, BILLING, AICore BIND_SERVICE — **no INTERNET/location** |
| debuggable | no |
| Signing | signed with a **throwaway test key** (NOT production) |

## 26. Final release artifact — AAB

| Field | Value |
|---|---|
| File | `app/build/outputs/bundle/release/app-release.aab` (36 MB) |
| `:app:bundleRelease` | ✅ BUILD SUCCESSFUL |
| versionName | `1.0.0` present in AAB manifest |
| Permissions (protobuf) | CAMERA 2, BILLING 1; **INTERNET 0, ACCESS_NETWORK_STATE 0, LOCATION 0** |
| R8 artifacts | `BUNDLE-METADATA/.../proguard.map`, `r8.json` present |
| Structure | Play-compatible bundle (base/manifest, base/dex, assets, res) |

## 27. Signing

**PRODUCTION SIGNING = NOT VERIFIED.** No production/upload keystore exists in this
environment. The APK was signed with a **throwaway `/tmp` key** purely to exercise the
release build; that is **not** proof of Play release signing. Play App Signing /
upload-key configuration is a manual Play Console action.

## 28. Regression (exact numbers)

| Gate | Result |
|---|---|
| JVM unit tests | **2284 passed / 0 failed / 0 skipped** (195 suites) |
| lint (`:app:lintDebug`, `lintVitalRelease`) | **passed** |
| debug build (`:app:assembleDebug`) | **passed** (incl. instrumented-test compilation `assembleDebugAndroidTest`) |
| release APK (`:app:assembleRelease`) | **passed** (R8 + shrink) |
| release AAB (`:app:bundleRelease`) | **passed** |
| instrumentation | **CI API 35 emulator only** (this environment: not run) |
| CI | tracked via push (see the report) |
| Stage 1–11 regression | **green** (one suite set; no stage regressed) |

## 29. Known limitations (real, remaining)

- No physical-device certification; no OEM coverage; no soak.
- Anti-spoof is a heuristic only — **not production-certified**.
- Consumer-app Android limits: system UI (Home/Recents/shade/QS/power/PiP/split-screen),
  force-stop, uninstall, clear-data are not preventable without Device Owner/Kiosk.
- Room/DataStore plaintext at rest (templates are Keystore-encrypted).
- Client-only billing: no server-side verification; grace/account-hold/revocation not
  exposed client-side.
- Play items outstanding: public privacy/support URLs, store graphics, production signing,
  Console declarations, real purchase test, Terms of Service, OSS legal review.

## 30. Release blockers

| Severity | Count | Items |
|---|---|---|
| **P0 — release blocker** | **0** | none found in the executable scope |
| **P1 — high** | **0** | none found in the executable scope |
| **P2 — non-blocking** | 1 | Debug route-name string constants remain in the release dex (used by the PIN-gate allow-list); no destination is registered, so not reachable — cosmetic hardening only |

Android platform limitations (system UI, force-stop, uninstall) are **not** classified as
blockers: they do not violate an explicit product requirement and are documented.

## 31. Final decision

**PASS WITH LIMITATIONS.**

The product is **technically releaseable**: builds (debug/release/AAB) succeed, R8 and
resource shrinking are on, the regression suite is green (2284/0), the release artifact is
clean of INTERNET/location/debuggable and carries the expected minimal permissions.

It is **not certified on real hardware** because no device, Play Console, license tester,
or production signing is available — so device/OEM/billing/soak remain **NOT VERIFIED**.

### Play readiness (kept distinct)

| State | Value |
|---|---|
| Technically ready | **YES** |
| Play Console verified | **NO** |
| Production signed | **NO** |
| Submitted | **NO** |
| Approved | **NO** |
