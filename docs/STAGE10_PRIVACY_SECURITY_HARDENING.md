# QALQON — Stage 10: Privacy & Security Hardening

> Stage 10 deliverable (roadmap 10/12). A product that handles **children's data and
> biometric templates** must be technically defensible, not just claim privacy.
>
> **Honesty contract.** No physical device and no ADB in this environment. The release
> pipeline is verified **end-to-end here** (a signed release APK was built with R8 +
> resource shrinking using a throwaway test key, never committed), but the minified
> release APK's **runtime behaviour on a device is NOT verified**. No "unhackable" or
> "military-grade" claim is made anywhere.

---

## 1. Audit (before this change)

| Area | State | Verdict |
|---|---|---|
| Face templates | AES-256-GCM, Keystore-backed, `v1:` envelope; no plaintext fallback | ✅ GOOD |
| Encryption | AES-GCM, fresh nonce per encryption, authenticated, versioned envelope | ✅ GOOD |
| Android Keystore | Key generated on-device, `setRandomizedEncryptionRequired` default, never exported | ✅ GOOD |
| Room / DataStore | Plaintext at rest (no SQLCipher) — documented limitation; `allowBackup=false` | ⚠️ BY DESIGN (documented) |
| Backup | `allowBackup=false` + `dataExtractionRules` excluding every domain (cloud + D2D) | ✅ GOOD |
| Logs | 28 `Log` calls, none interpolating a token/PIN/template/embedding | ✅ GOOD |
| Raw images | **No** image is ever written to disk; frames are in-memory and recycled | ✅ GOOD |
| Memory | Face bitmaps recycled; no long-lived image retention | ✅ GOOD |
| Debug functionality | `DebugFlags.DEBUG_SCREENS_ENABLED = BuildConfig.DEBUG`; debug routes guarded | ✅ GOOD |
| Exported components | Only launcher, boot receiver, accessibility service | ✅ GOOD |
| **Release build / R8** | **`isMinifyEnabled = false`** → full symbol names, dead code and unused resources shipped | ❌ **GAP** |

The one real gap was the **release build**: R8 minification and resource shrinking were
disabled, so the shipped APK exposed full symbol names and dead code — trivial reverse
engineering of the recognition, billing and licensing surfaces. The prior engineer had
deferred enabling it pending device verification.

---

## 2. What changed

1. **Release now enables R8 minification + resource shrinking** (`isMinifyEnabled = true`,
   `isShrinkResources = true`), the single largest attack-surface reduction available.
2. **`proguard-rules.pro`** rewritten with the minimum keep rules for the genuinely
   reflective/runtime-loaded surfaces (TFLite interpreter, ML Kit GenAI, the manifest
   components, enums persisted by name, the security/billing surfaces) and no blanket
   keep-all. Debug/test variants are unaffected.
3. **`Stage10ReleaseHardeningTest`** (build config, proguard hygiene, exported surface,
   debuggable) and **`Stage10DataExposureTest`** (no raw images, no secret in logs, no
   hardcoded secret, key hygiene) — tripwires so the hardening cannot silently regress.

No behaviour of Stage 1–9 changed.

---

## 3. Release build verification (end-to-end)

Verified in this environment using a **throwaway test keystore in `/tmp`** (never
committed, deleted afterwards) so the release pipeline could be exercised without any
production secret:

| Check | Result |
|---|---|
| `:app:minifyReleaseWithR8` | ✅ BUILD SUCCESSFUL |
| `:app:assembleRelease` (signed, R8 + shrinkResources) | ✅ BUILD SUCCESSFUL — `app-release.apk` produced |
| Obfuscation actually applied | ✅ `mapping.txt` obfuscates `uz.faceguard.app.feature.*` → `ec.a` … (551,778 lines) |
| Kept surfaces kept | ✅ `core.security.*`, FGS, accessibility service, boot receiver map to themselves |
| Resource shrinking kept what is needed | ✅ `accessibility_service_description`, notification strings, `app_name`, Stage 8 readiness strings all present |
| `INTERNET` in release APK | ✅ **absent** (offline-first contract holds in the shipped manifest) |
| Minified release **runtime on device** | ❌ **NOT VERIFIED** (no device) |

---

## 4. Threat posture (unchanged, re-confirmed)

- Raw PIN never stored/logged/transmitted; PIN is PBKDF2-HMAC-SHA256 (120k iterations,
  per-account salt, constant-time compare) with a persisted escalating lockout.
- Face templates are Keystore-encrypted; an authenticated-but-broken envelope is never
  downgraded to plaintext (it requires re-enrollment).
- `INTERNET`/`ACCESS_NETWORK_STATE` are only ever manifest-merger **removals**.
- Debug/recognition diagnostics are absent from release (`BuildConfig.DEBUG` gate).
- Purchase token is never logged or persisted.

## 5. Explicit non-goals (unchanged, honest)

No anti-tamper/root detection, no server-side validation, no biometric-grade liveness,
no device-admin control. Android-wide-encryption and SQLCipher are not added:
Room/DataStore remain plaintext, mitigated by Keystore encryption of the sensitive
templates and by backup exclusion. This is documented, not hidden.

## 6. Tests

- JVM: **2256 / 0 failures** (was 2241 → **+15**): `Stage10ReleaseHardeningTest` (9),
  `Stage10DataExposureTest` (6).
- lint / assembleDebug / R8 / signed release: all pass.

## 7. Known limitations

- The minified release APK was built but not run on a device; R8 keep rules are verified
  at build time and by the tripwires, not at runtime.
- Room/DataStore are not encrypted at rest (documented; templates are).
- Real-device security validation (rooted-device extraction, D2D transfer) not performed.
