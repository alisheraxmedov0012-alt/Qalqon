# Qalqon — Follow-up Security & Quality Audit

> Read-only audit companion to [`PROJECT_OVERVIEW.md`](PROJECT_OVERVIEW.md).
> Baseline: commit `076fae9` on `feature/phase4-screen-time-complete`.
> Structure/architecture review is in `PROJECT_OVERVIEW.md`; this document covers
> credentials, bypass vectors, dead code, Play policy, ranked bugs and recommendations.
>
> Findings marked **not verified** could not be confirmed from the repository alone.

---

## 1. PIN / session storage & encryption

| Item | Where | Encrypted? |
|---|---|---|
| PIN verification | PBKDF2-HMAC-SHA256, 120 000 iters, 16-byte `SecureRandom` salt — `core/security/PinHasher.kt` | n/a (derived) |
| Constant-time compare | `MessageDigest.isEqual` — `PinHasher.kt` | — |
| Legacy upgrade (single-round SHA-256 → PBKDF2) | `PinHasher.kt`, `AccountRepositoryImpl.kt` | — |
| PIN hash + salt **at rest** | Room `user_accounts.pinHash` / `pinSalt` — `data/db/Entities.kt` | **No — plaintext Room DB** |
| Attempt counters / lockout deadline | DataStore `security` — `data/prefs/PinAttemptStore.kt`; policy 5 attempts → 30/60/300/900 s — `domain/security/PinAttemptPolicy.kt` | **No — plaintext DataStore** |
| Session (active account id only) | DataStore `session`, key `current_account_id` — `data/prefs/SessionManager.kt` | **No — plaintext DataStore** |
| UI lock flag | process-only — `core/security/AppLockState.kt` | not persisted (by design) |
| Face templates | AES-GCM, Keystore, `v1:` envelope — `core/security/KeystoreBiometricTemplateCipher.kt` | **Yes** |
| Backups | `android:allowBackup="false"` — `AndroidManifest.xml` | — |

**Verdict.** The PIN itself is never stored and is well-hashed (per-account salt,
constant-time verify, persisted escalating lockout). Face templates are
Keystore-encrypted. However there is **no database-level encryption** (no SQLCipher, no
`EncryptedSharedPreferences`), so the PIN *hash*, policy, protected-app list and
activity log are readable on a rooted or debuggable device; the PBKDF2 cost is the only
barrier to offline PIN recovery.

## 2. Bypass vectors

See [`../SECURITY.md`](../SECURITY.md) for the maintained, authoritative list with
mitigations and residual risk. Summary of that audit:

| Vector | Mechanism / evidence | Result |
|---|---|---|
| Uninstall | No DeviceAdmin / owner registration | Removes protection and data |
| Clear app data | Room + DataStore wiped | Returns to onboarding |
| Force stop | `START_NOT_STICKY` FGS; force-stopped apps get no `BOOT_COMPLETED` | Halts background protection |
| Revoke Accessibility | App can only detect, never re-enable; legacy overlay is `FLAG_NOT_TOUCHABLE` | Block becomes cosmetic |
| Revoke overlay | `showLegacy()` early-returns without permission | No blocking window |
| Revoke Usage Access | Foreground app not observed; screen-time suppressed | No app-level enforcement |
| Reboot | Restore only if `protectionEnabled` + account; camera FGS type cannot be claimed from boot | Protection resumes; camera limited until UI opened |
| Camera covered/revoked | Frames older than 1.5 s count as no-face; no-face now fails closed | Blocked, but no recognition |
| Safe mode | Third-party services disabled by OS | Protection off |

## 3. Dead code / TODO scan

* **Zero** `TODO`/`FIXME`/`XXX`/`HACK` markers in `app/src/main/java` (word-boundary
  grep = 0), also enforced by `FinalUiConsistencyTest`.
* The only `onClick = {}` occurrences are in design-tooling previews
  (`QalqonComponentPreviews.kt`), not production.
* Documented "not implemented" seams (not TODOs): domain-only `DIM/BLUR/BLACK_SCREEN`
  actions, the un-bundled `AntiSpoofModel`, and the `sync/NoOpSyncGateways`.

## 4. Google Play policy risks

| Area | Risk | Evidence / note |
|---|---|---|
| AccessibilityService used for app-blocking (non-assistive purpose) | **High** | Declared service + `BIND_ACCESSIBILITY_SERVICE`; Play requires the Accessibility declaration form. `canRetrieveWindowContent="false"` helps review but does not remove the requirement |
| Children's biometric data | **High** | Face templates stored; Play Families + Sensitive Data policies require Data-safety disclosure and prominent in-app disclosure. On-device encryption helps but disclosure is still mandatory. Privacy-policy URL presence **not verified** |
| `PACKAGE_USAGE_STATS` (special) | Medium | Play reviews justification |
| `SYSTEM_ALERT_WINDOW` | Medium | Restricted permission, requires justification |
| `FOREGROUND_SERVICE_CAMERA` / `SPECIAL_USE` | Medium | Subtype declared; background camera use is scrutinised (while-in-use design mitigates) |
| targetSdk 35 + parental-control category | Medium | Specific disclosure expectations |

**Positives:** no `QUERY_ALL_PACKAGES` (targeted `<queries>` launcher intent);
`INTERNET`/`ACCESS_NETWORK_STATE` only as `tools:node="remove"`; cleartext traffic off;
ML Kit telemetry backend removed.
**Not verified:** what declarations are currently filed in Play Console.

## 5. Ranked bugs

**Critical**
1. Parent UI never re-locked on background (`AppLockState.lock()` only from logout/reset) — *fixed in this branch (`5edf794`).*
2. No anti-removal: uninstall / clear-data / force-stop defeat protection.

**High**
3. `noFacePolicy = ALLOW` default → camera cover/revoke bypass — *fixed (`f8d2c24`).*
4. Accessibility off ⇒ block is cosmetic (legacy overlay `FLAG_NOT_TOUCHABLE`).
5. Overlay permission off ⇒ block no-ops silently.
6. Usage Access off ⇒ no foreground detection and screen-time silently disabled.

**Medium**
7. Reboot restore cannot reclaim the camera FGS type — *reported as a degraded state (`81a9e99`).*
8. Plaintext Room + DataStores (no SQLCipher/EncryptedSharedPreferences).
9. No `FLAG_SECURE` — *fixed (`199a64c`).*
10. Documentation drift (README/AGENTS toolchain, migrations, tests) — *fixed (`72dda98`).*

**Low**
11. `UserAccount` domain model carried `pinHash` — *fixed (`c6b6b3c`).*
12. `PinHasher.fromHex` zero-filled malformed hex — *fixed (`3a2a23e`).*
13. `gradlew` had no execute bit — *fixed (`f0a2ea4`).*
14. Home-path compiler warnings — *fixed (`fa7d7c0`).*

> Items marked *fixed* were addressed by later commits on this branch; the remaining
> Open/High items are accepted gaps documented in `SECURITY.md`.

## 6. Top recommendations

| # | Recommendation | Effort |
|---|---|---|
| 1 | Add uninstall/anti-removal protection (Device Admin / profile-owner) | L |
| 2 | Encrypt the persisted stores (SQLCipher and/or `EncryptedSharedPreferences`) | L |
| 3 | Surface a persistent, prominent degraded banner when a capability is revoked — *done* | M |
| 4 | Prepare Play compliance artefacts (Accessibility declaration, Data-safety form, FGS justification, privacy-policy URL) | M |
| 5 | Ship a real anti-spoofing model behind the `AntiSpoofModel` seam | L |
| 6 | Consider R8/minify on release with conservative keep rules | M |
| 7 | Enable and commit Room exported schemas | S |
| 8 | Keep `SECURITY.md` current as mitigations land | S |

---

*Generated from a static audit plus a live `:app:testDebugUnitTest` run. All numeric
results are measured, not estimated.*
