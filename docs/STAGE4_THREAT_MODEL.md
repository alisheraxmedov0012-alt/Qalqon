# QALQON — Stage 4: Security Threat Model

> Companion to [`STAGE4_SECURITY_PRIVACY_AUDIT.md`](STAGE4_SECURITY_PRIVACY_AUDIT.md)
> and [`STAGE4_BIOMETRIC_DATA_LIFECYCLE.md`](STAGE4_BIOMETRIC_DATA_LIFECYCLE.md).
> This is the Stage 4 expansion of the maintained [`../SECURITY.md`](../SECURITY.md).

QALQON is a **best-effort, offline-first, on-device** parental-control app. It
holds no server account, requests no `INTERNET` permission, and stores all data
locally. Assets: parent PIN, parent/child face templates, protection
configuration, activity history.

---

## Adversaries

| # | Adversary | Capability assumed |
|---|---|---|
| A | Another ordinary Android app | sandboxed; can send intents, register receivers |
| B | Malicious intent caller | can launch exported components with crafted extras |
| C | Local file extraction **without root** | can read only its own app data |
| D | Backup extraction | can trigger cloud backup or D2D transfer |
| E | Debug build exposure | can install a debug/attacker-built APK |
| F | Physical access (non-root) | can use any UI, screenshot, use Recents |
| G | Rooted device | full filesystem + memory access |
| H | Compromised OS | can read memory, tamper, remove permissions |

---

## Threat matrix

| Asset | Threat | Attack surface | Impact | Likelihood | Existing mitigation | Required fix | Residual risk |
|---|---|---|---|---|---|---|---|
| Face templates | App A reads them | app-private Room | High | Very low | Android sandbox; AES-GCM + Keystore key | none | none w/o root |
| Face templates | B via exported component | manifest | High | Very low | no exported component returns data | none | none |
| Face templates | D via backup/D2D | backup subsystem | High | Low | `allowBackup=false`; **new** `dataExtractionRules` exclude all domains; Keystore key never transfers | verify D2D on a real device | ciphertext copy (if any) is undecryptable |
| Face templates | G root reads DB | filesystem | High | Medium (if rooted) | AES-GCM + Keystore (key in TEE; DB alone is ciphertext) | none possible | **plaintext in memory while app runs** on a rooted device |
| Raw face images | any | camera/disk | High | — | never persisted; memory-only per frame | none | memory inspection on rooted device |
| PIN | F offline brute force | Room `pinHash` | High | Low | PBKDF2-HMAC-SHA256 120k + per-account salt; constant-time compare; escalating lockout | none | weak PIN + root can still be brute-forced offline |
| PIN | A/B | UI / intents | Medium | Very low | parent UI behind `AppLockState`; routes gated by `lockRedirectFor`; PIN never rendered/logged | none | shoulder-surfing (physical) |
| Session | stale auth after process death | in-memory lock | Medium | Low | `AppLockState` is process-only, starts locked; re-locks on background grace | none | — |
| Config / history | A/B/D/G | Room/DataStore | Medium | Low | app-private; backup excluded | none | plaintext to root |
| Whole app | E debug exposure | debug build | High | Low | debug screens gated by `BuildConfig.DEBUG`; release not debuggable | keep gate | installing a debug APK is the user's choice |
| Whole app | A/B enumerate packages | `<queries>` | Low | — | targeted launcher `<queries>`; no `QUERY_ALL_PACKAGES` | none | launcher apps visible (intended) |

---

## Attackers that cannot be fully defended

* **G — rooted device:** with root, app-private storage and process memory are
  readable. QALGON reduces exposure (encrypted templates, no raw image on disk,
  no logs) but **cannot** be claimed "unhackable" or "fully protected against
  root". Residual: face templates are recoverable in memory during use.
* **H — compromised OS:** a modified OS can read memory, remove permissions and
  falsify API results. Nothing in a sandboxed app defends against this.
* **F — physical access:** a determined non-root user can uninstall, clear data
  or force-stop (documented in `SECURITY.md`); these are product-level limits,
  not code defects.

---

## Stage 4 changes to the model

1. **Backup / D2D (S4-2):** added `dataExtractionRules` excluding all domains, so
   the "no egress" boundary now covers device-to-device transfer, not just cloud
   backup.
2. **Deletion (S4-1):** the full reset now clears every Room table, so the
   "all local data deleted" promise is true at the storage level.

No new trust assumptions were introduced, and no mitigation was removed.

---

## Privacy verdict

> **"Does QALQON have a code/data path that sends face embeddings or raw face
> data off-device?"**

**NO — VERIFIED, NO FACE DATA EGRESS PATH FOUND.**

Evidence:

1. **No network permission.** The merged release manifest grants neither
   `INTERNET` nor `ACCESS_NETWORK_STATE` (both are `tools:node="remove"`);
   without them no socket can be opened.
2. **No network code.** No `java.net`, URL, OkHttp, Retrofit, Volley, Socket,
   WebSocket, Firebase, analytics, crash-reporting, upload or multipart usage
   exists in `app/src/main/java`.
3. **Transitive telemetry neutralised.** ML Kit's `datatransport`/`firebase-encoders`
   are present on the classpath but their upload service/receiver are removed from
   the merged manifest and, without `INTERNET`, could not transmit anyway. No
   `TransportRuntime` initializer/provider remains.
4. **On-device only.** Face detection (bundled ML Kit model), embedding (TFLite
   MobileFaceNet from `assets/models`) and matching run entirely on the device.
5. **Never persisted raw.** Raw frames/crops/embeddings exist only in memory;
   the only durable artifact is the AES-GCM ciphertext of the template.
6. **Never logged.** No log statement carries embeddings, templates, scores,
   names, phone numbers or account ids.
7. **Excluded from backup and transfer.** `allowBackup=false` + the new
   `data_extraction_rules.xml` exclude all domains from cloud backup and D2D.

**Limits of this verification:** it is a static/source and manifest analysis plus
the build's merged manifest; a live network capture and a real device-to-device
transfer test were **not** performed (no device/emulator available). The absence
of the `INTERNET` permission is a hard platform guarantee, but the honest label
for the end-to-end behavioural claim is "verified by construction, not measured
on a device".
