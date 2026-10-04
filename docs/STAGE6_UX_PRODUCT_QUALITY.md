# QALQON — Stage 6: UX / Product Quality Audit

> Commercial Master Plan — Stage 6 deliverable.
> Baseline: `feature/phase4-screen-time-complete` @
> `c7788001c0000c7f3447a8a32f4d8cb5e1532c87` (Stage 5, PASS WITH LIMITATIONS).
>
> **Honesty contract.** This audit is grounded in source, navigation, state,
> resources and tests. Accessibility was reviewed **statically / by source
> contract only** — no TalkBack run, no real device. User-journey verdicts are
> code-derived, **not** real-user validated. Anything unverifiable is marked
> NOT TESTED, never "pass".

---

## 1. Method and evidence base

* 31 Compose screens across 18 feature packages; a finished 7-phase UI/UX redesign.
* **Localization:** 793 keys in each of `values` (uz), `values-en`, `values-ru` —
  exact key-set parity (verified by `LocalizationCompletenessTest`).
* **Hardcoded user-facing strings:** none. No `Text("literal")` in any feature
  screen; the only string constants are test tags, debug labels and the internal
  `SUBJECT_PARENT` route key.
* **Empty states:** modelled with paired message + hint (`children_empty` /
  `children_empty_hint`, `papps_empty` / `papps_empty_hint`, …) plus a shared
  `QalqonEmptyState` component. No bare "No data".
* **Accessibility:** 66 `semantics` / `contentDescription` / `onClickLabel`
  usages across feature screens; icon-only buttons carry `contentDescription`
  (e.g. back arrows, add, more-options).
* **Error handling:** `UiState.Error(messageRes)` carries a resource id, never a
  raw string — with two exceptions fixed below.

---

## 2. UX score — 82 / 100

| Category | Max | Score | Evidence |
|---|---|---|---|
| Onboarding & First-use | 15 | 14 | Welcome screen states purpose, parent-vs-child, offline, on-device, battery; two clear CTAs. No jargon. |
| Core Parent/Child Flow | 20 | 17 | Register/login/PIN validate with typed errors; child CRUD confirmed; face enrollment has staged, amaliy guidance (`enroll_hint_*`). |
| Protection Setup & Clarity | 15 | 11 | Per-capability requirement rows with Grant/Open/Ready; degraded banner; honest background note (corrected). Capability names are semi-technical. |
| Error/Recovery/State UX | 15 | 12 | `UiState` + empty/hint pairs + confirmations; raw exception leak fixed; recovery paths present. |
| Accessibility | 10 | 7 | Good semantics coverage by source contract; **not** TalkBack-verified. |
| Localization | 10 | 9 | Full 3-locale parity, no hardcoded strings; long RU strings handled by scroll/wrap. |
| Trust/Privacy/Clarity | 10 | 8 | Privacy screen honest; no spoof/liveness claims (Stage 5 honoured); background note corrected. |
| Consistency/Polish | 5 | 4 | Consistent design system; minor technical section exposes internal enum names. |
| **Total** | **100** | **82** | |

Not a PASS at 100 because accessibility was not device-verified and the
capability names remain semi-technical; both are honest limitations, not failures.

---

## 3. Findings

### P2 — S6-1: raw exception detail shown to the user
* **Screens:** Parent profile (`ParentProfileScreen.kt`), Face enrollment
  (`FaceEnrollmentScreen.kt`).
* **Problem:** `reportError(error)` set the user-facing Toast text to
  `error.message ?: error.javaClass.simpleName`, rendered through
  `error_generic` ("Error: %1$s"). A DB/IO/encryption exception message or a class
  name could reach a non-technical parent.
* **Why it matters:** Stage 6 explicitly forbids surfacing internal technical
  detail; it is also a small information-disclosure surface.
* **Fix:** the exception is now written to logcat (`Log.w(TAG, …)`), and the UI
  receives `R.string.error_unexpected` (generic, localized). `_errorMessage` is a
  resource id, not a string.

### P2 — S6-2: stale/misleading background-protection note
* **Screen:** Protection (`protection_limit_note`).
* **Problem:** the note claimed background camera "will need a system service in a
  later phase". The foreground service and process-scoped camera session already
  exist (Phase 7.1), so the note understated protection and referenced a completed
  phase.
* **Fix:** replaced with accurate copy in all three locales: protection runs in the
  background via a system service; the camera starts when Qalqon is opened and then
  keeps recognizing in the background, resuming on the next open after a restart.

### P3 — S6-3: technical-details section exposes internal enum names
* `protection_scan_trigger` renders `ScanTrigger.name` (e.g. `APP_OPENED`) and
  `home_foreground_current` renders a package name. The card is explicitly labelled
  "Technical details" and collapsed by default, so it is opt-in; **not changed**
  (avoiding scope creep / churn).

### P3 — S6-4: capability rows are semi-technical
* `protection_req_*` name the capability ("Usage access permission", "Accessibility
  service" → "Background app monitoring service") with a purpose header and
  Open/Grant buttons, but no one-line "why". Acceptable for a setup screen; **not
  changed** to avoid adding 4×3 new strings for marginal gain.

### P3 — S6-5: reset hint enumerates a subset of cleared data
* `data_reset_hint` lists "account, profiles, faces, and settings"; Stage 4's reset
  also clears activity, schedules, screen-time and eye-safety. It already says "all
  local data", which is accurate (Stage 4 made it true); **not changed**.

---

## 4. Non-technical parent acceptance test (code-derived)

| Step | Knows what to do | Knows why | Knows what happens next | Recovers from error |
|---|---|---|---|---|
| Understand QALQON | YES | YES | YES | n/a |
| Create account | YES | YES | YES | YES (typed validation) |
| Create PIN | YES | YES | YES | YES |
| Enroll own face | YES | YES | YES | YES (staged guidance + retry) |
| Add child | YES | YES | YES | YES |
| Enroll child face | YES | YES | YES | YES |
| Pick protected apps | YES | YES | YES | YES |
| Grant permissions | YES | PARTIAL (name + header, no per-line reason) | YES | YES (Open/Grant/Ready) |
| Turn protection on | YES | YES | YES | YES |
| Understand when/how it works | YES | YES | YES | n/a |
| Screen Time / Schedule | YES | YES | YES | YES |
| Eye Safety | YES | YES | YES | YES |
| Understand blocking | YES | YES | YES | YES (emergency unlock) |
| Recover from a problem | YES | YES | YES | YES |

**Javob: barcha qadamlar "YES", bittasi "PARTIAL"** (permission "why").
No dead-ends found. This is a code/state assessment, **not** a real-user study.

---

## 5. Accessibility

* **STATIC / SOURCE-CONTRACT AUDIT ONLY** — no TalkBack, no device.
* 66 semantics/contentDescription/onClickLabel usages; icon-only buttons labelled;
  empty/error/loading components exist and carry text (so they are announced).
* **Not verified:** focus order, contrast ratios, font-scale reflow, TalkBack
  announcements. Deferred to Stage 10 (real-device QA).

## 6. Localization

* Uzbek: PASS · Russian: PASS · English: PASS (exact key parity, no blanks, no
  hardcoded user-facing strings).
* New key `error_unexpected` added to all three locales; parity preserved.

## 7. Security / privacy regression

No security mechanism was weakened: PIN gate, encrypted biometric templates,
FLAG_SECURE, exported-component protections, offline-first (no `INTERNET`) all
unchanged. No DB schema change, no new dependency, no network code, no legacy
rename.

## 8. Known limitations

* Real-device QA outstanding (Stage 10): accessibility, camera, and OEM behaviour
  are NOT TESTED.
* No production-grade liveness (Stage 5): photo/screen/video replay not stopped.
* System-level limits (force-stop, uninstall, safe mode) remain (Stage 1/3/4 docs).
* Capability rows and the technical-details card are semi-technical (P3).
