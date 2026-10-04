# QALQON — Stage 4: Biometric Data Lifecycle & Storage Matrix

> Companion to [`STAGE4_SECURITY_PRIVACY_AUDIT.md`](STAGE4_SECURITY_PRIVACY_AUDIT.md).
> Baseline commit: `619a0a40c8d06798bdb90a9a692d8e53d43ea6fc`.
> Every cell below is derived from the implementation, not assumption.

---

## 1. Biometric pipeline trace

```
CameraX (front, analyzer-only)
  → ImageProxy → Image → Bitmap            (in memory, per frame)
  → rotation → upright Bitmap              (in memory)
  → ML Kit FaceDetector.process(InputImage)
  → primary face bounding box
  → FaceImageUtils.cropFace → cropped Bitmap (in memory)
  → TFLite MobileFaceNet.embed(bitmap)  OR  geometry fallback (19 floats)
  → FloatArray features (192-d model / 19-d fallback) — FrameEvent (in memory)
  → Recognizer.evaluate(frame, parent, children)
        → FaceEmbeddingCodec.decode(storedTemplateRef)  // compare only
  → cosine similarity → RecognitionResult
```

Enrollment turns accepted frames into one template:

```
accepted FrameEvent.features (N × 192/19 floats, in memory)
  → MeanFaceEmbeddingCollector → mean FloatArray
  → FaceEmbeddingCodec.encode → little-endian Float32 → Base64 string
  → BiometricTemplateCipher.protect(base64)
        → AesGcmSecureCrypto.encrypt(utf8 bytes) with AndroidKeystore AES-256-GCM key
        → "v1:" + Base64(magic|version|alg|ivLen|iv|ciphertext+tag)
  → Room  parent_profiles.faceTemplateRef / child_profiles.faceTemplateRef   (at rest)
```

Recognition never writes anything: `decode` + `cosine` operate on the decrypted
template and the live frame, both transiently in memory.

---

## 2. Data map

| Stage | Data type | Location | Persistent? | Encryption | Lifetime | Logged? | Backed up? |
|---|---|---|---|---|---|---|---|
| Capture | `ImageProxy`/`Image`/`Bitmap` (raw face pixels) | memory | No | n/a (never at rest) | one frame | No | No |
| Detection | ML Kit `Face` (box/landmarks) | memory | No | n/a | one frame | No | No |
| Crop | cropped `Bitmap` (raw face) | memory | No | n/a | one frame | No | No |
| Embedding | `FloatArray` (192-d / 19-d) | memory | No | n/a | frame/session | No | No |
| Encoding | Base64 `String` of the float vector | memory | No | n/a | enrollment only | No | No |
| Storage | `v1:<base64(AES-GCM envelope)>` | Room column | **Yes** | **AES-256-GCM, Android Keystore key** | until face/child/account deletion | No | **Excluded** |
| Recognition | decrypted `FloatArray` | memory | No | n/a | evaluation only | No | No |
| Deletion | ciphertext removed (`faceTemplateRef = NULL`) + key removable on full reset | Room + Keystore | — | — | immediate | No | — |

**Raw face images are never written to disk, cache, files, or any external
storage** — a working-tree scan for `FileOutputStream`/`createTempFile`/`cacheDir`/
`filesDir`/`Bitmap.compress`/`getExternalStorage` returns nothing. The only file
access to a face-related artifact is reading the bundled model from `assets/`.

---

## 3. Storage & encryption matrix

| Asset | Storage | Encryption | Key | Backup | Logs | Cache | Deletion | Status |
|---|---|---|---|---|---|---|---|---|
| Parent face template | Room `parent_profiles.faceTemplateRef` | AES-256-GCM, `v1:` envelope | Android Keystore alias `qalqon.biometric.v1` (device-bound) | Excluded (allowBackup=false + dataExtractionRules) | Never | Never written | `deleteFaceData(accountId)` sets `faceTemplateRef=NULL`, `isFaceEnrolled=0` | **Encrypted at rest** |
| Child face template | Room `child_profiles.faceTemplateRef` | AES-256-GCM, `v1:` envelope | same Keystore alias | Excluded | Never | Never written | `deleteFaceData(accountId, childId)`; full reset removes key | **Encrypted at rest** |
| PIN data | Room `user_accounts.pinHash`/`pinSalt` | PBKDF2-HMAC-SHA256 (120k iters, per-account 16-byte salt) — one-way, not reversible | n/a (derived) | Excluded | Never | Never | full reset deletes account row | **Hashed, not encrypted** |
| Parent profile (name) | Room `parent_profiles` | Plaintext | — | Excluded | Never | Never | full reset | Plaintext at rest (owner-readable only) |
| Child profile (name, level) | Room `child_profiles` | Plaintext | — | Excluded | Never | Never | full reset / child delete (row) | Plaintext at rest |
| Activity history | Room `activity_events` | Plaintext | — | Excluded | Never | Never | full reset | Plaintext at rest |
| Protection settings | DataStore `settings` | Plaintext | — | Excluded | Never | Never | full reset (`clearAll`) | Plaintext at rest |
| Session (account id) | DataStore `session` | Plaintext | — | Excluded | Never | Never | logout / full reset | Plaintext at rest |
| PIN attempt state | DataStore `security` | Plaintext (counters + deadline only) | — | Excluded | Never | Never | success / full reset | Plaintext, non-secret |
| Language | DataStore app prefs | Plaintext | — | Excluded | Never | Never | not cleared (UI preference) | Non-sensitive |

Room file: `faceguard.db` (app-private, no SQLCipher). DataStore files: `settings`,
`session`, `security`, app `preferences_pb`.

**Interpretation.** Biometric material (the actual face templates) is the only
data that is *encrypted*, and that encryption is the strongest available on the
device (Keystore AES-GCM). Everything else at rest is app-private plaintext,
readable only by QALQON or root. The PIN is never stored in a reversible form.

---

## 4. Encryption details (AES-GCM)

Source: `core/security/SecureCrypto.kt`, `KeystoreBiometricTemplateCipher.kt`.

| Property | Value |
|---|---|
| Algorithm / mode / padding | `AES/GCM/NoPadding` (JCA standard transformation) |
| Key size | 256-bit |
| Key store | Android Keystore, alias `qalqon.biometric.v1`, `PURPOSE_ENCRYPT\|DECRYPT`, `BLOCK_MODE_GCM`, `ENCRYPTION_PADDING_NONE` |
| IV / nonce | Fresh random nonce **per encryption**, generated by the platform (`cipher.init` without a caller IV); length written into the envelope |
| Authentication tag | 128-bit GCM tag (authenticated encryption) |
| Envelope | `magic("QSEC") \| version(1) \| algorithm(1) \| ivLength(1) \| iv \| ciphertext+tag`, then Base64, prefixed `v1:` |
| Key retrieval | `KeyStore.getInstance("AndroidKeyStore")`; `key()` returns `null` if unusable |
| Key generation | On first use only, 256-bit AES-GCM |
| Key invalidation | If the platform invalidates/removes the key, `key()` does **not** silently mint a replacement that could decrypt nothing; `decrypt` returns `KeyUnavailable` and the app moves to `RECOVERY_REQUIRED` (re-enrollment needed) |
| Key deletion | Only on full reset (`deleteKey()`); scoped to the app alias |

**Hardware-backed status: NOT VERIFIED.** The key is generated in `AndroidKeyStore`
(TEE/StrongBox where available), but hardware backing cannot be proven on an
emulator and there is no `KeyInfo`-based attestation check in the code. It is
**not** claimed.

### Failure behaviour (deterministic, fail-closed)

| Input | Result | Security state | Plaintext fallback? |
|---|---|---|---|
| Valid ciphertext + correct key | `Success` | — | n/a |
| Corrupted ciphertext / wrong key / invalid tag | `Corrupted` | `RECOVERY_REQUIRED` | **No** |
| Unknown envelope version | `UnsupportedVersion` | `RECOVERY_REQUIRED` | **No** |
| Malformed header/lengths | `Malformed` | `CORRUPTED` | **No** |
| Missing/invalidated key | `KeyUnavailable` | `RECOVERY_REQUIRED` | **No** |
| Encrypt fails | returns `null`; **nothing is written** | `RECOVERY_REQUIRED` | **No** |

Encryption/decryption never throw to the caller and never log the payload or key.
Covered by `security/SecurityCryptoTest.kt` (JVM) and
`security/SecurityPersistenceTest.kt` (instrumented, real Keystore).

---

## 5. Runtime (memory) biometric lifecycle

* The live `FloatArray` features and the decrypted template exist only for the
  duration of a frame/evaluation; nothing caches embeddings in a field.
* `Recognizer` holds no template cache — it decodes from the (account-scoped)
  profile objects it is handed each evaluation.
* On account change / sign-out, `ProtectionRuntime` calls
  `engine.resetIdentity()`, `resetLiveness()`, `resetSchedule()`,
  `resetEyeSafety()`, `cancelRecovery()`, and Room flows are account-scoped, so a
  previous account's biometric state cannot leak into the next session.
* `SecurityStateHolder` is process-scoped and holds no secret.

---

## 6. Deletion verification

Deletion is verified at the SQL level (not by a function's return value).

### Parent face
`ParentProfileRepositoryImpl.deleteFaceData(accountId)` →
`ParentProfileDao.clearFaceData` sets `isFaceEnrolled = 0`, `faceTemplateRef = NULL`,
`enrollmentStatus = 'NONE'`, bumps `enrollmentVersion`. After this the stored
template is gone; recognition cannot match it again.

### Child face
`ChildProfileRepositoryImpl.deleteFaceData(accountId, childId)` →
`ChildProfileDao.clearFaceData` does the same for one child, account-scoped.

### Account / full reset
`ResetRepositoryImpl.resetAll()` clears **all 14 Room tables**, wipes the three
DataStore files (settings/session/security), and deletes the Keystore key, so the
old ciphertext is worthless even if a copy existed.
**Stage 4 fix:** it now also clears `daily_app_usage`, `child_screen_time_limits`,
`schedule_rules`, `schedule_app_targets` and `child_eye_safety` (see S4-1).

Verified by:
* `security/Stage4DeletionAndBackupTest.kt` — every table is cleared
  (source contract, JVM).
* `security/SecurityPersistenceTest.aFullResetClearsBiometricDataAndTheKey` —
  real Room + Keystore key removal (instrumented).
* `security/SecurityPersistenceTest.aFullResetClearsEveryTableNotJustBiometrics`
  — seeds the previously-missed tables and asserts the wipe (instrumented).

**Post-restart recognition after deletion:** because the template is `NULL` and
the Keystore key is removed, a restart cannot resurrect the old face. This is
asserted structurally (no template → `RecognitionResult.NoFace`/`Unknown` path)
and at the DB level; a real-device camera run to watch it happen is **NOT TESTED**
(no device/emulator locally; the instrumented job runs in CI).
