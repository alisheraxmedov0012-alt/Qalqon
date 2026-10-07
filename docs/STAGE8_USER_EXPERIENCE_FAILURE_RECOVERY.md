# QALQON — Stage 8: User Experience & Failure Recovery

> Stage 8 deliverable (roadmap 8/12). Goal: a non-technical parent can set QALQON up
> unaided, understand *why* each permission is needed, see an **honest** readiness
> state, and — when something breaks — be told what happened, why, and what to do next.
> Core principle: **USER ERROR ≠ APP ERROR.**
>
> **Honesty contract.** No physical device and no ADB are available in this environment.
> The only runtime evidence is the CI **API 35 emulator** (instrumented tests). Visual
> first-run walkthroughs are **NOT TESTED**.

---

## 1. Audit (before this change)

The journey and every screen were audited (Welcome → Register → PIN → Parent → Parent
face → Child → Child face → Protected apps → Permissions → Protection → Ready → Active,
plus error/recovery/help/privacy/debug). Findings:

| Area | Verdict before | Detail |
|---|---|---|
| Welcome | GOOD | Plain language, four promise cards. |
| Registration / PIN | GOOD | Generic errors, no enumeration. |
| Parent/Child creation | GOOD | Clear CRUD with confirm. |
| Face enrollment | GOOD | Per-rejection guidance (no face, too far, too dark, blurry, occluded…). |
| Permission list | **CONFUSING** | Technical labels ("Fondagi ilovalarni kuzatish xizmati") with **no explanation of why**. |
| **Ready state** | **BROKEN (false success)** | `ProtectionRuntimeState.ready` **excluded accessibility**, so the Protection status card showed "Himoya faol" (active) while the only touch-blocking mechanism was off — contradicting the degraded banner. |
| Camera recovery | **MISSING** | Stage 5's `cameraRecovering` was set but never shown; a camera interruption looked like nothing was wrong. |
| Error severity | PARTIAL | A single warning level; no clear INFO / WARNING / BLOCKED split. |
| Recognition states | GOOD | "No face"/"unknown" already treated as normal states, not errors. |
| Debug UI | GOOD | Already gated by `BuildConfig.DEBUG`. |
| Hardcoded strings | GOOD | None in production screens (only `@Preview`). |
| Localization | GOOD | uz/en/ru parity enforced by tests; new strings must follow. |
| Settings-return re-probe | GOOD | Stage 6 resume re-probe already updates capability state. |

The one genuinely broken thing was the **false "active"** status; the rest was clarity
and missing recovery feedback.

---

## 2. What changed

1. **Honest readiness model** — `domain/protection/ProtectionReadiness.kt`:
   `OFF / NOT_READY / LIMITED / READY`, derived from real capability health
   (`degradedCapabilities`, which **includes accessibility**), not the on/off setting.
   Exposed as `ProtectionRuntimeState.readiness`. `LIMITED` is shown whenever any
   capability is missing, so "Protection: ON" can never be presented as full protection.
2. **Protection status card** now shows the readiness verdict + a one-line "what to do"
   hint + the runtime state, replacing the misleading boolean-derived title.
3. **Camera-interruption feedback** — a `CameraRecoveryCard` on the Protection screen and
   a `CAMERA_RECOVERING` Home banner, distinct from the post-reboot camera limit and from
   a missing permission, with an idempotent **retry** (`ProtectionRuntime.retryCameraRecovery()`
   → the single Stage 5 session's `retryNow()`).
4. **Permission UX** — each required capability now shows a plain-language *why*
   ("QALQON uses the camera to tell who is looking at the phone.") with a READY / not-enabled
   state, grouped under "Required for full protection".
5. **Localization** — 21 new keys in Uzbek, English and Russian.

Stage 1–7 mechanisms are untouched.

---

## 3. First-Time User Flow

| Step | Status | UX change |
|---|---|---|
| Welcome | GOOD | — |
| Parent setup | GOOD | — |
| Parent enrollment | GOOD | — |
| Child setup | GOOD | — |
| Child enrollment | GOOD | — |
| Protected apps | GOOD | — |
| Permissions | **IMPROVED** | Why + READY/missing per capability |
| Ready | **FIXED** | Honest OFF / NOT READY / LIMITED / READY |
| Protection active | **FIXED** | Never "active" while a critical capability is missing |

---

## 4. Permission UX Matrix

| Capability | Missing state | Explanation (why) | Action | Retry/re-probe | Final state |
|---|---|---|---|---|---|
| Camera | MISSING | "uses the camera to tell who is looking" | grant (runtime) | re-probe on resume | READY / LIMITED |
| Usage Access | MISSING | "needs to know which app is open" | settings | re-probe on resume | READY / LIMITED |
| Accessibility | MISSING | "limit what the child can open on screen" | disclosure → settings | re-probe / onConnected | READY / LIMITED |
| Overlay | MISSING | "show the protection screen over other apps" | settings | re-probe on resume | READY / LIMITED |
| Notifications | off | (recommended) | notification settings | re-probe | never blocks protection |
| Battery/background | optimized | (recommended) | battery list | re-probe | never blocks protection |

---

## 5. Failure & Recovery Matrix

| Failure | Detected? | User message | Recovery action | Retry | Result |
|---|---|---|---|---|---|
| Camera permission denied/revoked | Yes | readiness LIMITED + why | grant camera | resume re-probe | READY when restored |
| Camera interrupted | Yes | "Restarting the camera" + "may be used by another app" | automatic bounded recovery; manual retry | `retryCameraRecovery()` (idempotent) | READY when rebound |
| Accessibility disabled | Yes | readiness LIMITED + why | enable in settings | onConnected / re-probe | READY when restored |
| Overlay revoked | Yes | readiness LIMITED + why | grant in settings | resume re-probe | READY when restored |
| Usage Access lost | Yes | readiness LIMITED + why | enable in settings | resume re-probe | READY when restored |
| Recognition issue (no face / unknown) | Yes | **not** an error | — | — | normal state |
| Protection degraded | Yes | degraded banner + readiness LIMITED | fix highest-priority capability | re-probe | READY when restored |

---

## 6. Ready State

- **READY**: enabled + active + parent face + ≥1 protected app + **no** missing capability.
- **LIMITED**: enabled + active + set up, but ≥1 capability missing (accessibility counts).
- **NOT READY**: enabled but no session / no parent face / no protected app.
- **OFF**: the parent has not switched protection on.
- **False-success prevention**: the status verdict derives from `readiness`, and the
  readiness function treats every capability (including accessibility) as required for
  full protection — pinned by `ProtectionReadinessTest`.

---

## 7. Localization

- New keys (21) present and non-blank in **Uzbek (default), English, Russian**.
- Global parity remains enforced by `LocalizationCompletenessTest`; Stage 8 keys are
  additionally pinned by `Stage8LocalizationTest`.
- No hardcoded user-facing strings in production screens.

## 8. Tests

- JVM: **2221 / 0 failures** (was 2181 → **+40**):
  `ProtectionReadinessTest` (14), `Stage8CameraRecoveryBannerTest` (6),
  `Stage8LocalizationTest` (6), `Stage8RegressionGuardTest` (14).
- lint / assembleDebug / CI: see the report.

## 9. Manual UX testing

**REAL DEVICE UX TEST = NO.** No physical device, no ADB. The first-run walkthrough was
not performed on a device or emulator; the emulator is not presented as a UX validation.

## 10. Known limitations

- No visual/device walkthrough; layout and copy are validated structurally + by unit tests.
- Permission dialogs and system settings pages are Android-owned; wording there is the OS's.
- Recovery feedback is text, not animation; no redesign was undertaken (deliberate).
