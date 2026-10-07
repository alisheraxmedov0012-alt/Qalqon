# QALQON — Stage 10: Privacy & Security Audit (Threat Model + Findings)

> Stage 10 (Privacy & Security Hardening, roadmap 10/12). This is the **audit record**:
> the sensitive-asset inventory, the per-area findings with severity, what was fixed, and
> the honest residual risk. The build-hardening deliverable is in
> [`STAGE10_PRIVACY_SECURITY_HARDENING.md`](STAGE10_PRIVACY_SECURITY_HARDENING.md).
>
> **No "100% secure" / "unhackable" claim is made.** Hardening is risk reduction.

---

## 1. Threat model

Assumed adversary: a child with full physical access to an unlocked device and any
standard Android UI, plus a **curious user inspecting the shipped APK**. Not in scope: a
rooted device, a custom ROM, a malicious system app, physical forensic extraction, or a
server-side attacker (there is no server).

### Sensitive assets

| # | Asset | Created | Stored | Encrypted at rest | Logged | Backed up | Exported off device |
|---|---|---|---|---|---|---|---|
| A | Face templates (parent/child), encrypted envelope | enrollment | Room (`faceTemplateRef`) | ✅ AES-256-GCM (Keystore) | ❌ | ❌ | ❌ |
| B | Raw camera frames / crops / bitmaps | camera pipeline | **memory only, bounded** | n/a | ❌ | ❌ | ❌ |
| C | Identity metadata (name, phone, child ids) | registration / profiles | Room | ❌ (plaintext, needed for login/UI) | ❌ | ❌ | ❌ |
| D | Protection/settings/schedules/screen-time | user config | Room + DataStore | ❌ (plaintext) | ❌ | ❌ | ❌ |
| E | Billing state (entitlement, product/base-plan) | Play | DataStore (account-scoped) | ❌ (non-secret state) | ❌ | ❌ | ❌ |
| E' | Purchase token | Play | **never persisted** | n/a | ❌ | ❌ | ❌ |
| F | Cryptographic material | Keystore | Android Keystore (device-bound) | n/a (in Keystore) | ❌ | ❌ | ❌ |
| G | Debug data (scores, embeddings, diagnostics) | debug screens | memory only, debug builds only | n/a | ❌ | ❌ | ❌ |

---

## 2. Findings

Severity: CRITICAL / HIGH / MEDIUM / LOW / INFORMATIONAL.

### F-1 — Release build was not minified — **MEDIUM** — **FIXED**

| | |
|---|---|
| Attack surface | The shipped APK |
| Existing behavior | `isMinifyEnabled = false` → full symbol names, dead code, unused resources |
| Risk | Trivial reverse engineering of recognition/billing/licensing surfaces |
| Exploitability | High (static, no device needed) |
| Impact | Medium (no direct data exposure; the sandbox/Keystore still protect data) |
| Fix | Enabled R8 + `isShrinkResources`; minimal keep rules (no blanket keep-all) |
| Verification | `Stage10ReleaseHardeningTest`; `:app:minifyReleaseWithR8` + signed `assembleRelease` built; `mapping.txt` shows real obfuscation |

### F-2 — Biometric template carried in the domain model — **LOW** — NOT FIXED (by design)

| | |
|---|---|
| Attack surface | In-process object graph |
| Existing behavior | `ParentProfile`/`ChildProfile` carry `faceTemplateRef` (the **encrypted** `v1:` envelope) so the recognizer can match |
| Risk | The value transits the domain→core layers |
| Exploitability | Very low — it is ciphertext bound to the device Keystore, and it reaches no log/Intent/URI |
| Impact | Low |
| Recommendation | Not changed: moving it out of the domain model is a Stage 1–2 refactor, out of Stage 10 scope. Documented. |
| Verification | Not logged/exported (`Stage10DataExposureTest`, `Stage10DataLifecycleAndSurfaceTest`) |

### F-3 — Room / DataStore are not encrypted at rest — **MEDIUM** — NOT FIXED (documented)

| | |
|---|---|
| Attack surface | On-disk DB/prefs on a rooted or debuggable device |
| Existing behavior | Room + DataStore are plaintext; the sensitive biometric columns are app-level Keystore-encrypted |
| Risk | On a rooted device, non-biometric data (names, phone, protection config, activity log) is readable |
| Exploitability | Requires root/forensic access (out of scope) |
| Impact | Medium for non-biometric data; the biometric template is encrypted |
| Recommendation | Not changed: SQLCipher/whole-DB encryption is an explicit non-goal for this stage; `allowBackup=false` + extraction rules prevent off-device extraction. Documented in SECURITY.md §3.10. |

### F-4 — No server-side purchase validation — **INFORMATIONAL** — NOT FIXED (accepted)

Client-only Google Play Billing; no backend (Stage 9 acceptance). Premium is a business
gate, not a security boundary (core protection is free). Documented.

### F-5 — Debug screens — **INFORMATIONAL** — verified safe

`DebugFlags.DEBUG_SCREENS_ENABLED = BuildConfig.DEBUG`; debug routes and the developer
settings category are guarded; no billing/entitlement path references the flag.

### F-6 — Secrets — **INFORMATIONAL** — verified clean

No tracked secret files, no hardcoded key/token, no `BEGIN PRIVATE KEY`; signing secrets
are read from the environment / `local.properties` only. No credential has entered git
history, so no rotation is required.

### Areas audited and found GOOD (no finding)

Encryption (AES-GCM, random per-op nonce, authenticated), Keystore usage (alias,
AES-256-GCM, no export, deletion on reset), backup (cloud + D2D fully excluded),
raw-image lifecycle (memory-only), logging (no sensitive interpolation),
exported components (minimal), Intents (no dangerous action reachable), PendingIntents
(immutable), providers/URI/clipboard (none), network (`INTERNET` removed), PIN (PBKDF2 +
lockout), deletion lifecycle (per-subject + full reset deletes the key).

---

## 3. Room entity audit

| Data | Entity | Sensitive | Encrypted | Plaintext | Needed |
|---|---|---|---|---|---|
| Parent face template | `parent_profiles.faceTemplateRef` | ✅ | ✅ Keystore AES-GCM | ❌ | ✅ recognition |
| Child face template | `child_profiles.faceTemplateRef` | ✅ | ✅ Keystore AES-GCM | ❌ | ✅ recognition |
| PIN hash + salt | `user_accounts.pinHash/pinSalt` | ✅ | n/a (PBKDF2 hash, not reversible) | hash only | ✅ auth |
| Full name / phone | `user_accounts.fullName/phoneNumber` | ⚠️ PII | ❌ | ✅ | ✅ login/UI |
| Display / child name | `parent_profiles`, `child_profiles` | ⚠️ PII | ❌ | ✅ | ✅ UI |
| Settings / policies / schedules / screen-time | multiple | ⚠️ | ❌ | ✅ | ✅ features |
| Activity log / requests / notifications | multiple | ⚠️ | ❌ | ✅ | ✅ features |
| Subscription state | (DataStore) | ⚠️ | ❌ | ✅ | ✅ entitlement |

## 4. DataStore audit

| Key group | Content | Sensitive | Verdict |
|---|---|---|---|
| `settings` | scan mode, policies, recovery delay, protection on/off | ⚠️ config | OK (no secret) |
| `active_child_id`, `current_account_id`, `app_language` | identifiers | ⚠️ | OK |
| `pin_failed_*`, `pin_lock_until_*` | counters/timestamps | ❌ | OK (not the PIN) |
| `entitlement_*` | entitlement state/product/base-plan | ⚠️ | OK (no token) |

No template, phone, PIN material, token or key is written to DataStore
(`Stage10DataLifecycleAndSurfaceTest`).

## 5. Data deletion lifecycle

| Event | Parent template | Child template | Other data | Key |
|---|---|---|---|---|
| Child delete | — | row removed | child's rows removed | kept |
| Child face-delete | — | nulled (`deleteFaceData`) | kept | kept |
| Parent face-delete | nulled (`clearFaceData`) | — | kept | kept |
| Logout | — | — | kept (account-scoped) | kept |
| Full reset | all rows wiped | all rows wiped | Room + DataStore wiped | **Keystore key deleted** |
| App data clear | wiped (Android) | wiped | wiped | wiped |
| Uninstall | wiped (Android) | wiped | wiped | wiped (app-scoped) |
| Backup / D2D | **excluded** | **excluded** | excluded | never transferred |

## 6. Release APK attack surface

- Permissions: CAMERA, PACKAGE_USAGE_STATS, SYSTEM_ALERT_WINDOW, FOREGROUND_SERVICE(+CAMERA,
  +SPECIAL_USE), POST_NOTIFICATIONS, RECEIVE_BOOT_COMPLETED, USE_BIOMETRIC/USE_FINGERPRINT,
  BILLING, AICore BIND_SERVICE. **No INTERNET, no network, no storage/media, no location.**
- Exported: launcher activity, boot receiver (system broadcast), accessibility service
  (system-bound). No provider.
- What another app can invoke: nothing that disables protection, activates Premium or
  reaches debug/dev data.

## 7. Explicit security limitations (honest)

Rooted/compromised device, memory dumping, physical forensic extraction, OEM privileged
access, Play server-side verification (absent), perfect anti-spoof and "100% biometric
protection" are **out of scope and NOT claimed**. Obfuscation is attack-surface
reduction, not secrecy.
