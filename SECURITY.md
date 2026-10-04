# Qalqon — Security Threat Model

> Scope: the on-device parental-control app in this repository (`uz.faceguard.app`).
> This document is deliberately honest: it lists every bypass vector we know of, what
> the app actually does about it today, and the residual risk we accept. Nothing here
> claims a guarantee the code does not provide.

Qalqon is a **best-effort, offline-first, on-device** parental-control app. It holds no
server account, requests no `INTERNET` permission, and stores all data locally. Its
threat model assumes a **child with full physical access to the device** and the
ability to use any standard Android UI, but **not** a rooted device, a custom ROM, or
an attacker who can modify the APK.

---

## 1. Assets

| Asset | Where it lives | Notes |
|---|---|---|
| Parent PIN | PBKDF2-HMAC-SHA256 envelope (`pinHash`, `pinSalt`) in Room `user_accounts` | Raw PIN never stored, logged or transmitted (`core/security/PinHasher.kt`) |
| Face templates (parent + children) | Room, AES-GCM **Keystore-encrypted** envelope `v1:<base64>` | `core/security/KeystoreBiometricTemplateCipher.kt` |
| Protection configuration | Room (policies, schedules, limits) + DataStore (settings) | Plaintext at rest (see §5) |
| Activity / request history | Room | Plaintext at rest |
| Protected-app catalogue | Room | Device-specific |

## 2. Trust boundaries

* The **parent UI** is gated behind a PIN + optional OS biometric prompt
  (`AppLockState`, `lockRedirectFor` in `navigation/NavGraph.kt`).
* The **child / any non-parent user** is untrusted and must not reach Settings,
  protection, schedules or the language picker.
* The **OS** is trusted to enforce permissions. Qalqon can only *detect* that a
  capability was removed — it can never re-grant one.

## 3. Bypass vectors, current mitigation and residual risk

### 3.1 Uninstall
* **Current mitigation:** none. Qalqon is a normal app; it does not request Device
  Admin, device-owner or profile-owner privileges, and there is no uninstall guard
  (`grep DeviceAdmin|ACTION_DELETE|REQUEST_DELETE_PACKAGES|PACKAGE_REMOVED` → none).
* **Residual risk:** **High — accepted.** Uninstalling removes protection entirely
  and wipes local data. Closing this properly requires device-admin / Android
  Enterprise enrolment, which is out of scope for the current build. Play policy also
  restricts self-uninstall prevention.

### 3.2 Clear app data
* **Current mitigation:** none. Clearing data wipes Room + DataStore, returning the
  app to onboarding with protection off. `android:allowBackup="false"` (`AndroidManifest.xml`).
* **Residual risk:** **High — accepted.** Indistinguishable from a fresh install.

### 3.3 Force stop
* **Current mitigation:** `ProtectionForegroundService` returns `START_NOT_STICKY`
  (`core/protection/ProtectionForegroundService.kt`). A force-stopped app also stops
  receiving `BOOT_COMPLETED` until manually reopened.
* **Residual risk:** **High — accepted.** Force stop halts background protection; the
  OS provides no restart mechanism for user-stopped apps.

### 3.4 Rebooting the device
* **Current mitigation:** `ProtectionBootReceiver` → `ProtectionBootRestorer` restart
  protection from the persisted intent when `protectionEnabled` and an account exists.
  The service is started with the `specialUse` FGS type only, because a `camera`-type
  FGS cannot be started from `BOOT_COMPLETED`. The runtime reports this limitation
  (`ProtectionRuntimeState.cameraLimitedAfterBoot`) and Home/Protection show the
  `protection_after_boot_camera` banner until the camera type is claimed on the first
  UI foreground.
* **Residual risk:** **Medium — accepted.** After a reboot, recognition is limited
  until the app is opened once; app-blocking policy still runs once capabilities are
  available. A child who simply never opens the app keeps it in the limited state.

### 3.5 Revoking or omitting permissions
Qalqon now detects all four as a **degraded** state, warns on Home + Protection, offers
a one-tap deep link to the right settings page, and posts a parent notification
(`domain/protection/ProtectionDegradation.kt`, `ProtectionDegradedBanner`,
`NotificationType.PROTECTION_DEGRADED`). Re-check happens on resume
(`ProtectionRuntime.refreshPermissions`).

| Capability | Consequence when missing | Detected | Residual risk |
|---|---|---|---|
| **SYSTEM_ALERT_WINDOW (overlay)** | No blocking window at all — `showLegacy()` returns early when the permission is absent (`OverlayControllerImpl.kt`) | ✅ | High — accepted: the OS lets the user revoke it at will |
| **Accessibility service** | Blocking degrades to a non-touchable scrim (`FLAG_NOT_TOUCHABLE`) — cosmetic only; the diagnostics layer documents it as "the only true input block" | ✅ | High — accepted |
| **Usage Access** | Foreground app is never observed, so no app-level block; screen-time enforcement is suppressed | ✅ | High — accepted |
| **Camera** | No recognition. No-face now **fails closed** (`noFacePolicy` defaults to `SOFT_BLOCK`), so a covered/revoked camera does not silently pass a protected app | ✅ | Medium — accepted |
| **POST_NOTIFICATIONS** | Parent notifications are not shown (requests still exist and are visible in-app) | ✅ | Low |

### 3.6 Safe mode
* **Current mitigation:** none. Android disables third-party services/receivers in safe
  mode, so the accessibility service and boot restore do not run.
* **Residual risk:** **High — accepted.** Not addressable by a normal app.

### 3.7 Camera covered / obstructed / dark
* **Current mitigation:** frames older than `FRAME_TTL_MS = 1_500` count as "no face";
  the fail-closed default (`noFacePolicy = SOFT_BLOCK`) blocks rather than passes. The
  group-9 liveness layer has an `AntiSpoofModel` seam but **no model is bundled**, and
  the passive heuristic is weak.
* **Residual risk:** **Medium/High — accepted.** Printed photos, phone screens and
  video replays are **not** reliably detected; no spoofing guarantee is claimed.

### 3.8 Unattended / handed-over device, Recents
* **Current mitigation:** the parent UI re-locks after a **15 s** background grace
  (`AppLockState.lockAfter`, driven from `MainActivity.onStop`), and screen-capture is
  blocked on the credential screens and the blocking overlay via `FLAG_SECURE`
  (`core/ui/SecureFlag.kt`).
* **Residual risk:** **Low/Medium.** During the grace window an unlocked UI is visible
  to whoever holds the device; `FLAG_SECURE` does not stop a camera pointed at the screen.

### 3.9 PIN brute force
* **Current mitigation:** PBKDF2-HMAC-SHA256, 120 000 iterations, per-account random
  salt, constant-time compare; persisted escalating lockout (5 attempts → 30 s / 60 s /
  300 s / 900 s) that survives a process kill (`PinAttemptStore`). Strict hex decoding
  of salts — a malformed salt can never be zero-filled (`PinHasher`).
* **Residual risk:** **Medium — accepted.** The lockout is a local delay, not a hard
  cap; a rooted attacker who extracts the database can attempt an offline PBKDF2 search
  bounded only by the iteration count and PIN length.

### 3.10 Data at rest
* **Current mitigation:** face templates are Keystore-encrypted. The PIN is only stored
  as a salted PBKDF2 hash.
* **Residual risk:** **Medium — accepted.** The **Room database and the DataStore files
  are not encrypted** (no SQLCipher / EncryptedSharedPreferences). On a rooted or
  debuggable device, the PIN *hash*, protection configuration and activity log are
  readable; `allowBackup="false"` prevents cloud backup extraction.

## 4. Explicit non-goals

* No anti-tamper / integrity attestation, no root detection, no obfuscation-based
  secrecy.
* No server-side policy enforcement, no remote wipe, no device-admin control.
* No biometric-grade face matching or certified liveness detection.
* No guarantee that protection survives a determined, technically capable user.

## 5. Reporting a vulnerability

This is a local MVP with no hosted service. Report issues privately to the repository
owner (see `README.md`) rather than opening a public issue for anything exploitable.
Please include the build commit, device/OS version, exact steps, and observed vs
expected behaviour.

## 6. Hard invariants the code enforces

* The raw PIN is never stored, logged or sent anywhere.
* `INTERNET` / `ACCESS_NETWORK_STATE` are only ever declared as manifest-merger
  removals; the app is offline by design.
* The domain layer (`UserAccount`) carries no credential material — the hash/salt live
  only in the data layer (`UserAccountEntity`).
* The UI lock never controls protection: protection keeps running while the parent UI is
  locked (`AppLockProtectionIndependenceTest`).
