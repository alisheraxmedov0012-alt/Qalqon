# QALQON — Stage 4: Security & Privacy Hardening Audit

> Commercial Master Plan — Stage 4 deliverable.
> Baseline: `feature/phase4-screen-time-complete` @
> `619a0a40c8d06798bdb90a9a692d8e53d43ea6fc` (Stage 3, PASS WITH LIMITATIONS).
>
> **Honesty contract.** Every statement below is backed by source, manifest or a
> named test. Where a claim cannot be verified on this hardware (no device, no
> emulator, no root) it is marked **NOT VERIFIED / NOT TESTED** rather than
> asserted. No "unhackable", "100% secure" or "hardware-backed" claim is made
> without evidence.

---

## 0. Scope and method

Audited: manifest, source, Room entities/DAOs/migrations, DataStore, Keystore,
PIN/auth/session, logs, screenshots, debug/release variants, exported
components, intents, PendingIntents, permissions, backup/restore, data
extraction, temp/cache files, deletion paths, network surface, secrets and the
dependency tree.

Method: static source analysis of the working tree, the **merged release
manifest** produced by the build, `./gradlew :app:dependencies`, the compiled
classpath, JVM tests and (in CI) the instrumented suite. No code was changed
during the audit (STEP A–K); fixes are in STEP L and are listed in §8.

Out of scope (reported as `OUT OF SCOPE — FUTURE STAGE`): anti-spoofing/liveness
models (Stage 5), subscription/trial (Stage 7), Play compliance (Stage 8), UX
redesign (Stage 6), backend/network/analytics, and any new roadmap phase.

---

## 1. Application attack surface

Source: `app/src/main/AndroidManifest.xml` and the merged release manifest
(`app/build/intermediates/merged_manifests/release/...`).

### Exported components

| Component | Type | exported | Guard | Assessment |
|---|---|---|---|---|
| `uz.faceguard.app.MainActivity` | Activity | `true` | launcher; parent UI behind `AppLockState` + `lockRedirectFor` | Required for launcher. `EXTRA_DESTINATION` is an unvalidated nav route, but every parent route is still gated by the PIN lock, so an external caller cannot bypass authentication. |
| `core.protection.ProtectionForegroundService` | Service | `false` | — | Not callable by other apps. |
| `core.protection.ProtectionBootReceiver` | Receiver | `true` | only `BOOT_COMPLETED` (a protected broadcast) | System-only sender; restore is idempotent and read-only over the persisted intent. |
| `core.accessibility.ProtectionAccessibilityService` | Service | `true` | `android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"` | Only the system may bind. Legitimate requirement of an AccessibilityService. |
| `androidx.profileinstaller.ProfileInstallReceiver` | Receiver | `true` | `android:permission="android.permission.DUMP"` | AndroidX-provided; signature/privileged-only. |
| `androidx.startup.InitializationProvider` | Provider | `false` | — | — |
| `com.google.mlkit.common.internal.MlKitInitProvider` | Provider | `false` | — | ML Kit init, no network. |
| `androidx.room.MultiInstanceInvalidationService` | Service | `false` | — | — |

No `<provider>` is exported. No `android:debuggable` attribute is present in the
release manifest (defaults to `false`).

**Conclusion:** no exported component lets another app change protection, read
data, start the service, trigger deletion or bypass authentication. A malicious
intent caller has no reachable privileged surface.

### Intents and PendingIntents

* **PendingIntents** — exactly two, both `FLAG_IMMUTABLE` and **explicit** to
  `MainActivity`: the foreground-service notification
  (`ProtectionForegroundService`) and the notification dispatcher
  (`core/notification/AndroidNotifications.kt`). No mutable PendingIntent exists.
* **Other intents** — settings deep-links (`ACTION_USAGE_ACCESS_SETTINGS`,
  `ACTION_MANAGE_OVERLAY_PERMISSION`, `ACTION_ACCESSIBILITY_SETTINGS`,
  `ACTION_APPLICATION_DETAILS_SETTINGS`, `ACTION_APP_NOTIFICATION_SETTINGS`) are
  implicit system intents carrying **no app data**. The only extra read from an
  inbound intent is `NotificationNavigation.EXTRA_DESTINATION` (a route string),
  which is navigation-only and still gated by the lock.

### Permissions

| Permission | Why | Grant type | Revoke impact |
|---|---|---|---|
| `CAMERA` | face capture for enrollment + recognition | runtime | frames stop; no-face policy applies (fail-closed) |
| `PACKAGE_USAGE_STATS` | foreground-app detection | special (Settings) | no app-level enforcement |
| `SYSTEM_ALERT_WINDOW` | draw the fallback blocking window | special (Settings) | fallback overlay cannot draw (accessibility overlay unaffected) |
| `FOREGROUND_SERVICE` + `..._SPECIAL_USE` + `..._CAMERA` | keep the protection session + camera alive | normal | session/camera limits |
| `POST_NOTIFICATIONS` | warn the parent | runtime (33+) | notification hidden, service still runs |
| `RECEIVE_BOOT_COMPLETED` | restore after reboot | normal | no auto-restore |
| `USE_BIOMETRIC` / `USE_FINGERPRINT` | OS biometric prompt for the parent UI | normal | falls back to PIN |
| `com.google.android.apps.aicore.service.BIND_SERVICE` | Gemini Nano capability (on-device) | normal | — |
| `INTERNET`, `ACCESS_NETWORK_STATE` | **removed** via `tools:node="remove"` | — | offline contract |
| `QUERY_ALL_PACKAGES` | **not present** (targeted `<queries>` instead) | — | — |

---

## 2. Debug / release separation

* `DebugFlags.DEBUG_SCREENS_ENABLED = BuildConfig.DEBUG`
  (`core/debug/DebugFlags.kt`). Developer screens (`NavGraph.kt`,
  `SettingsScreen.kt`, `HomeScreen.kt`) are gated by it, so the recognition
  debug screen, developer hub and fake-data tooling do not appear or navigate in
  a release build.
* `BuildConfig.DEBUG` is `false` in release; the merged release manifest has no
  `debuggable` attribute.
* **R8/minification is off** (`isMinifyEnabled = false`, no `shrinkResources`).
  This is a hardening *opportunity*, not a confirmed vulnerability: enabling R8
  requires keep-rules for Hilt/Room/Compose/ML Kit/TFLite and carries real
  breakage risk, so Stage 4 does **not** force it on. Reported as a residual
  hardening item (§8, P3).
* Signing secrets are read from environment / gitignored `local.properties`
  only; `assembleRelease` fails loudly without them (`app/build.gradle.kts`).

---

## 3. Logging audit

23 `Log.*` call sites exist in `app/src/main/java`; there are **no** `println`,
`System.out`/`System.err` uses in production code. Every log either reports a
failure with a `Throwable` or a non-identifying status string. None logs a PIN,
hash, salt, face embedding, template, similarity score, child/parent name, phone
number or account id. The screen-time collector logs counts and timings
(`packages=N accountedMs=… testedMs=…`), never package names or child names.

**Conclusion:** no sensitive-data logging in source, debug or release. `Log.*`
output is additionally not compiled out by R8 because R8 is off — see §8 P3.

---

## 4. Network / privacy boundary

* Manifest: `INTERNET` and `ACCESS_NETWORK_STATE` are declared only as
  `tools:node="remove"`, so the **merged release manifest grants no network
  permission** (verified in the merged manifest).
* Source: no `java.net`, `URL`, `HttpURLConnection`, OkHttp, Retrofit, Volley,
  Socket, WebSocket, Firebase, analytics, crash-reporting, upload or multipart
  usage anywhere in `app/src/main/java`.
* Dependency tree (`releaseRuntimeClasspath`): ML Kit face detection drags in
  `com.google.android.datatransport:transport-runtime/-backend-cct` and
  `com.google.firebase:firebase-encoders(-json)` transitively. These are the
  telemetry/logging backends. Their upload **services and receiver are removed
  from the manifest** (`tools:node="remove"` for `TransportBackendDiscovery`,
  `JobInfoSchedulerService`, `AlarmManagerSchedulerBroadcastReceiver`), and
  without `INTERNET` they could not reach the network even if invoked. No
  `TransportRuntime` initializer/provider remains in the merged manifest.
* All face detection, embedding and matching run on-device (ML Kit bundled model
  + TFLite MobileFaceNet from `assets/models`). No face data is sent anywhere.

**Conclusion:** there is **no code or data path that sends face embeddings or raw
face data off-device** — see the Privacy Verdict in
[`STAGE4_THREAT_MODEL.md`](STAGE4_THREAT_MODEL.md).

---

## 5. Backup, restore and data extraction

* `android:allowBackup="false"` (unchanged).
* **Stage 4 fix:** `android:dataExtractionRules="@xml/data_extraction_rules"` was
  added. On Android 12+ `allowBackup` only governs **cloud** backups; it no
  longer covers device-to-device (D2D) transfers. Without rules, a phone-to-phone
  transfer could copy the app-private database. The new rules exclude the `root`,
  `file`, `database`, `sharedpref` and `external` domains from **both**
  `cloud-backup` and `device-transfer`.
* The face templates are additionally AES-GCM-encrypted with a key held in the
  Android Keystore. Keystore keys are device-bound and are not part of any
  backup/transfer, so even a copied ciphertext is undecryptable elsewhere.
* **Uninstall/reinstall:** app-private data (Room DB, DataStore, cache) is
  deleted by the OS on uninstall, and there is no restore mechanism
  (`allowBackup=false` + exclusion rules) — so old biometric state cannot return
  after a reinstall.
* **Data extraction without root:** modern Android blocks other apps from
  reading another app's private storage; `FLAG_SECURE` (below) blocks
  screenshots/recents capture of sensitive screens; ADB backup is disabled.
  **With root**, app-private plaintext (non-biometric tables; see the storage
  matrix) is readable — the app cannot prevent that, and the residual risk is
  stated honestly rather than denied.

---

## 6. Screenshot / screen-recording exposure

`FLAG_SECURE` is applied to the credential screens (`SecureFlag.kt` used by the
PIN unlock/create screens) and to **both** blocking overlay windows
(`AccessibilityOverlayWindow`, `OverlayControllerImpl`). This prevents
screenshots, screen recording and the Recents thumbnail from capturing the
typed PIN or the blocking overlay. Pinned by `security/SecureWindowFlagTest.kt`.
Other parent screens rely on the fact that the PIN is never displayed; they hold
no rendered secret. (Full coverage on real devices — NOT TESTED, no device.)

---

## 7. Secrets

Working-tree scan (excluding `build/`) for API keys, tokens, private keys,
passwords and high-entropy literals found **none**. Git history (`git log -S`)
for `ghp_`, `github_pat_`, `AIza…` and `BEGIN … PRIVATE KEY` found **none**. The
release keystore is not in the repository, is gitignored, and `app/build.gradle.kts`
only reads it from environment variables / gitignored `local.properties`.

---

## 8. Findings

Severity: P0 biometric egress / auth bypass; P1 major biometric/privacy exposure;
P2 meaningful hardening; P3 minor/cleanup.

| ID | Sev | Category | Asset | Description | Root cause | Impact | Fix | Regression test | Residual risk |
|---|---|---|---|---|---|---|---|---|---|
| S4-1 | P2 | QALQON BUG (privacy/deletion) | Child schedule, eye-safety config, screen-time limits + usage history | `ResetRepositoryImpl.resetAll()` cleared only 9 of 14 Room tables. The Privacy screen tells the user "all local data is deleted" and the method claims it "clears every Room table", but `daily_app_usage`, `child_screen_time_limits`, `schedule_rules`, `schedule_app_targets` and `child_eye_safety` survived on disk with orphaned account ids. | The reset was written before those tables existed and was never extended. | User-visible false promise of deletion; residual child data on disk after a "full wipe". No biometric residue and no cross-account leak (ids are unique). | Added `deleteAll()` to `ScheduleDao` (`schedule_rules`/`schedule_app_targets`) and `ChildEyeSafetyDao`; `resetAll()` now clears all five tables. | `security/Stage4DeletionAndBackupTest.kt` (JVM contract) + `SecurityPersistenceTest.aFullResetClearsEveryTableNotJustBiometrics` (instrumented, real DB) | Deletion is verified at the SQL level; a rooted attacker can still read the (now empty) tables' file pages until SQLite vacuum. |
| S4-2 | P2 | Privacy hardening (platform semantics) | Encrypted face-template DB + all app-private data | `android:allowBackup="false"` does not exclude app data from **device-to-device** transfer on Android 12+; no `dataExtractionRules` were declared. | Android 12 moved D2D transfer control from `allowBackup` to `dataExtractionRules`, which was never added. | A phone-to-phone transfer could copy app-private data off the device. Face templates would remain undecryptable (the Keystore key does not transfer), but the data still leaves the device. | Added `res/xml/data_extraction_rules.xml` excluding all domains from `cloud-backup` and `device-transfer`; referenced from the manifest. | `security/Stage4DeletionAndBackupTest.kt` | None for face data; still recommend real-device D2D verification (NOT TESTED). |
| S4-3 | P3 | Deletion hygiene | Deleted child's per-child config | Deleting a *child profile* leaves that child's `child_app_policies`, `schedule_rules`/`schedule_app_targets`, `child_eye_safety`, `daily_app_usage` and `child_screen_time_limits` rows. No biometric residue and no cross-account/child leak (autoGenerate ids are never reused). | `ChildProfileRepositoryImpl.deleteChild` only deletes the child row. | Storage residue only; no functional or confidentiality impact. | **Not changed** — a clean fix requires cross-repository cascade in a core repository, which exceeds a minimal Stage 4 patch; documented as a known issue. | — | Residual storage residue; revisit if a product need arises. |
| S4-4 | P3 | Hardening opportunity | Whole app | R8/minification and resource shrinking are off; `Log.*` is therefore not stripped in release. | `isMinifyEnabled = false`. | Larger APK; release logs are not removed. No secret is logged, so impact is low. | **Not changed** — enabling R8 needs keep-rules for Hilt/Room/Compose/ML Kit/TFLite and risks breakage; deferred to Release Engineering (Stage 11). | — | Residual; documented. |

No P0 or P1 finding was found.

---

## 9. Reproduce the checks

```bash
# Deletion-completeness + backup-exclusion JVM contract
./gradlew :app:testDebugUnitTest --tests "uz.faceguard.app.security.Stage4DeletionAndBackupTest"

# Full JVM suite (1839 baseline + 5 new Stage 4 tests)
./gradlew :app:testDebugUnitTest --no-daemon

# Debug assembly (validates the data-extraction-rules resource via AAPT)
./gradlew :app:assembleDebug --no-daemon

# Release compilation (packaging is gated on the release signing secrets by design)
./gradlew :app:assembleRelease --no-daemon

# Instrumented real-DB deletion verification (CI emulator)
./gradlew :app:connectedDebugAndroidTest
```
