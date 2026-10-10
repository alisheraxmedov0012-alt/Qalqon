# QALQON — Release Block 3: Real-Device Validation Guide

> **Status: PREPARED — NOT RUN.** This document is a *procedure*. Writing it does **not**
> execute any test and does **not** mark any test PASS. Every row starts at `NOT RUN` and may
> only be set to `PASS`/`FAIL` from evidence recorded on a real device (screen recording,
> screenshot or `adb` log). Full OEM certification remains Block 6.
>
> Scope: Release Completion **Block 3** — Accessibility + Sensitive Permission Compliance.
> Product: QALQON, package `uz.faceguard.app`, offline-first Android parental-control app.
> Branch: `feature/phase4-screen-time-complete`.
> Companion documents: `docs/compliance/BLOCK3_ACCESSIBILITY_PERMISSION_COMPLIANCE.md`
> (the audit + summary matrix), `docs/STAGE8_ACCESSIBILITY_DECLARATION.md` (superseded draft).

---

## 0. Safety rules for the tester (mandatory)

1. **Do not disable Google Play Protect.** If an install is blocked, stop and record the exact
   dialog (see §6); use a trusted install channel instead (see §2.3).
2. **Do not bypass any security control** and do not use tools that patch, de-obfuscate or
   re-sign the APK.
3. **Do not force-grant special access.** Usage Access, Overlay and Accessibility must be granted
   by the user through the normal Android Settings UI, exactly as a parent would.
4. **Do not modify the APK.** Test the artifact as built and record its SHA-256 (§2).
5. This guide is a *test aid*; it must not be used to disable protection or misrepresent the app.

---

## 1. Scope and deliverables

Block 3 is complete only when:
- the code-level contracts pass (already verified: JVM tests, lint, R8 — see the companion doc),
- **at least one clean real-device run of the disclosure/enforcement core (D-02..D-09)** is
  recorded with evidence, and
- **at least one Xiaomi/HyperOS run** (§6) is recorded.

Automated JVM/instrumented tests **cannot** substitute for these device rows.

---

## 2. Build under test

### 2.1 Artifact identity

| Field | Value |
|---|---|
| Source commit (HEAD at packaging) | `93c4037519b9c878d5829d94e6f68ab783936391` |
| Variant | **debug** (`android:debuggable="true"`) — **NOT a release build** |
| applicationId | `uz.faceguard.app` |
| versionCode / versionName | `1` / `1.0.0` |
| minSdk / targetSdk / compileSdk | 26 / 36 / 36 |
| Local packager build path | `app/build/outputs/apk/debug/app-debug.apk` |
| Local build size | 75,351,833 bytes |
| **Local build SHA-256** | `cbb49f1c24f238bac4e5e7525ee43661c96a7e5de675567a184e43fe4cd2c062` |
| Local signing cert SHA-256 | `b6b29da00ab70096306567be6cdd0e6a4fceabc0e5e92559742d875c83d4e458` (`CN=Android Debug`) |

> The debug APK is signed by a **machine-local auto-generated debug key**. A debug APK built on
> another machine (or downloaded from CI) will have a **different** SHA-256 and signing
> certificate for the same source — this is expected and is not a defect. Record the exact
> SHA-256 of the APK you actually install.

### 2.2 CI artifact (recommended for the tester)

| Field | Value |
|---|---|
| Workflow run | `38066526442` (#233, commit `93c4037`) |
| Artifact name | `qalqon-debug-apk` |
| Artifact id / size | `11674939098` / 45,654,192 bytes (zip) |
| Artifact digest | `sha256:ea244c45efd83d9df91b3a817a754129a564a460c42e00a687333a867898d50e` |
| Expires | 2026-11-09 |
| Download | GitHub → repo **Actions** → run **#233** → **Artifacts → qalqon-debug-apk** |

On the phone: open the run page in a browser, tap the artifact, unzip, tap `app-debug.apk`.
Artifacts require being signed in to GitHub.

### 2.3 Trusted install channels (do not fight Play Protect)

Prefer one of these instead of an "install from browser/messaging/file manager" flow:
- **`adb install app-debug.apk`** from a trusted computer, or
- **Google Play internal testing** (for a signed release build — out of scope for this debug run).

If an install is blocked by Play Protect, **record it** (§6) and switch channel; do not disable
Play Protect.

---

## 3. Test result recording

Record one row per test in the table below (append rows as needed). Status is one of
`PASS`, `FAIL`, `BLOCKED`, `NOT RUN`. A test with no evidence stays `NOT RUN`.

| Test ID | Date | Device / OS | Build SHA-256 (first 12) | Status | Evidence file | Notes |
|---|---|---|---|---|---|---|
| _(example)_ | | | | NOT RUN | | |

**Failure criterion (applies to every test):** an unexpected crash, a wrong/over-claimed
protection state, the disclosure being skipped before Settings, or any screen text / notification
content / credential being read or transmitted.

---

## 4. Core tests — D-02..D-09 (disclosure, consent, enforcement) + supporting rows

> These IDs match the summary matrix in
> `docs/compliance/BLOCK3_ACCESSIBILITY_PERMISSION_COMPLIANCE.md` Part 6.

### D-01 — First run is clean

- **Purpose:** no crash before the core flows.
- **Initial state:** fresh install, no account, no PIN.
- **Exact actions:** launch QALQON → create account → create PIN.
- **Expected result:** the app reaches Home without crashing; consent/disclosure screens appear
  where designed.
- **Failure criterion:** crash, ANR, or a skipped mandatory setup step.
- **Evidence:** screen recording.
- **Status:** `NOT RUN`

### D-02 — Disclosure appears before Settings (Protection requirements row)

- **Purpose:** the prominent disclosure is shown *before* the user is sent to Accessibility settings.
- **Initial state:** signed in; Protection screen open; Accessibility not enabled.
- **Exact actions:** tap the **"Background app monitoring service"** requirement row's action button.
- **Expected result:** an in-app dialog appears with title/body explaining purpose, that only the
  foreground **package name** is read, **no** screen content, and that nothing leaves the device;
  settings do **not** open yet.
- **Failure criterion:** Android Accessibility settings open without the dialog, or the dialog
  omits the data-access / on-device statements.
- **Evidence:** screen recording (must show the full disclosure text).
- **Status:** `NOT RUN`

### D-03 — Disclosure appears from the degraded-banner "fix" (no shortcut)

- **Purpose:** every path to Accessibility settings is gated, not just the requirements row.
- **Initial state:** a degraded banner is visible naming Accessibility as missing.
- **Exact actions:** tap the banner's accessibility **"fix"** action.
- **Expected result:** the **same** disclosure dialog appears; settings do not open.
- **Failure criterion:** the fix action opens Settings directly.
- **Evidence:** screen recording.
- **Status:** `NOT RUN`

### D-04 — Affirmative consent opens Settings

- **Purpose:** consent is an explicit action that leads to the enable screen.
- **Initial state:** the disclosure dialog is open (from D-02/D-03).
- **Exact actions:** tap **"I agree — open settings"**.
- **Expected result:** Android Accessibility settings open; the dialog closes.
- **Failure criterion:** nothing opens, or a different (unrelated) settings page opens.
- **Evidence:** screen recording.
- **Status:** `NOT RUN`

### D-05 — Declining opens nothing

- **Purpose:** declining/dismissing is not treated as consent.
- **Initial state:** the disclosure dialog is open.
- **Exact actions:** tap **Cancel**; then re-open the dialog and press **Back**.
- **Expected result:** the dialog closes; **no** settings open; the app state is unchanged.
- **Failure criterion:** Settings open on decline/dismiss.
- **Evidence:** screen recording.
- **Status:** `NOT RUN`

### D-06 — User enables the service manually

- **Purpose:** the app never enables the service itself; the user does.
- **Initial state:** Android Accessibility settings open for QALQON.
- **Exact actions:** toggle QALQON's service **on** by hand → return to the app.
- **Expected result:** the Accessibility requirement row shows Ready; the app does not
  self-enable or simulate consent.
- **Failure criterion:** the app enables the service on its own, or the row still shows missing.
- **Evidence:** screen recording.
- **Status:** `NOT RUN`

### D-07 — Protected app is blocked

- **Purpose:** the declared core function works.
- **Initial state:** Accessibility on; a protected app selected; parent face enrolled.
- **Exact actions:** open the protected app as the child would.
- **Expected result:** the app is blocked by a **touch-consuming** overlay that cannot be
  dismissed by the child (no tapping through, no Back-through).
- **Failure criterion:** the protected app is usable, or the overlay can be dismissed by the child.
- **Evidence:** screen recording.
- **Status:** `NOT RUN`

### D-08 — Accessibility off, overlay on: visual-only, honestly LIMITED

- **Purpose:** the fallback is not represented as equivalent to touch-blocking.
- **Initial state:** Accessibility **off**; `SYSTEM_ALERT_WINDOW` overlay **on**.
- **Exact actions:** open a protected app.
- **Expected result:** a **visual-only** scrim shows; touches are **not** consumed; the app
  reports **LIMITED** (never "ready").
- **Failure criterion:** the app claims full protection, or touches are consumed without the
  accessibility service.
- **Evidence:** screen recording.
- **Status:** `NOT RUN`

### D-09 — Accessibility off, overlay off: no block, LIMITED

- **Purpose:** honest state when nothing can block.
- **Initial state:** both Accessibility and Overlay **off**.
- **Exact actions:** open a protected app; return to the app.
- **Expected result:** no block occurs; the app reports **LIMITED** and the degraded banner names
  the missing capabilities.
- **Failure criterion:** the app claims protection is active.
- **Evidence:** screen recording.
- **Status:** `NOT RUN`

---

## 5. Permission grant/revoke, FGS and privacy rows

### D-10 — Usage Access revoked → re-checked

- **Actions:** grant Usage Access → return → then revoke it in Settings → return.
- **Expected:** the capability is re-checked on resume; after revoke it is reported missing; no crash.
- **Evidence:** screen recording. **Status:** `NOT RUN`

### D-11 — Overlay revoked → re-checked

- **Actions:** grant "Display over other apps" → revoke it → return.
- **Expected:** capability reported missing; no crash; readiness drops honestly.
- **Evidence:** screen recording. **Status:** `NOT RUN`

### D-12 — Camera denied → granted

- **Actions:** deny the camera prompt; then grant it and enroll a face.
- **Expected:** denial handled with a clear message; granting works; enrollment succeeds.
- **Evidence:** screen recording. **Status:** `NOT RUN`

### D-13 — Camera busy / unavailable

- **Actions:** open the camera in another app; then open QALQON / start protection.
- **Expected:** a recovery banner appears; no crash; recovery retries and restores.
- **Evidence:** screen recording. **Status:** `NOT RUN`

### D-14 — Notifications denied

- **Actions:** deny `POST_NOTIFICATIONS`; open Requests.
- **Expected:** an inline reason is shown; requests are still saved; FGS is unaffected.
- **Evidence:** screen recording. **Status:** `NOT RUN`

### D-15 — FGS notification and stop

- **Actions:** enable protection → observe the persistent notification → turn protection off.
- **Expected:** the ongoing notification is shown while protection runs; the service stops cleanly
  when protection is disabled.
- **Evidence:** screen recording. **Status:** `NOT RUN`

### D-16 — Process/Recents resilience

- **Actions:** with protection on, remove the app from Recents.
- **Expected:** protection continues (best-effort); the notification persists.
- **Evidence:** screen recording. **Status:** `NOT RUN`

### D-17 — Reboot restore

- **Actions:** with protection on, reboot the device.
- **Expected:** the restore path runs; camera-based recognition may stay **limited until the app is
  opened once** (Android 15+ FGS/camera rules); no crash.
- **Evidence:** screen recording. **Status:** `NOT RUN`

### PRIV-01 — No screen text / notification content is read

- **Purpose:** verify the claim that only the foreground **package name** is used.
- **Actions:** with the service enabled, use an app that shows an on-screen secret (e.g. type a
  one-off password into a browser field) and switch between apps; inspect the app's local
  activity log (debug screen) and the device logcat for the app.
- **Expected:** only package/foreground identifiers are recorded; **no** typed text, message
  content, password or notification content appears anywhere.
- **Failure criterion:** any screen text / credential / notification content is captured.
- **Evidence:** screenshot of the activity log + saved logcat excerpt. **Status:** `NOT RUN`

### PRIV-02 — No network egress

- **Purpose:** confirm the offline-first claim at runtime.
- **Actions:** with the app running, inspect the device's network use (e.g. a firewall/monitor
  already on the device) and the declared permissions.
- **Expected:** the app requests **no** `INTERNET` permission and produces no outbound traffic.
- **Evidence:** screenshot of the app's permission list + monitor. **Status:** `NOT RUN`

> The merged **release** manifest is already verified to contain **0** active `INTERNET`
> permission (code evidence). PRIV-02 is the on-device confirmation.

### D-18 — Second OEM device (if available)

- **Actions:** repeat D-02..D-09 on a non-Xiaomi OEM device (Samsung / Pixel / OPPO / Vivo).
- **Expected:** same honest behaviour; OEM settings labels may differ.
- **Evidence:** screen recording. **Status:** `NOT RUN`

### D-19 — Additional OEMs (if available)

- **Actions:** repeat D-02..D-09 on any further OEM devices available.
- **Expected:** best-effort compatibility; no false "fully protected" claim.
- **Evidence:** screen recording. **Status:** `NOT RUN`

### D-20 — Install-source capture (observation only)

- **Actions:** attempt to install the debug APK from one Internet-sideloading source (browser /
  messaging / file manager) and record the exact install-time dialog.
- **Expected:** if a Play Protect block appears, capture the **verbatim** title/body and classify
  whether it is the hard "App blocked to protect your device" screen or a soft scan prompt.
  Do **not** disable Play Protect.
- **Evidence:** screenshot + verbatim text. **Status:** `NOT RUN`

### D-21 — Two CI debug APKs (update behaviour)

- **Actions:** install an older CI debug APK, then attempt to update with a newer CI debug APK.
- **Expected:** the debug-key rotation makes the in-place update fail
  (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`); uninstall then install succeeds.
- **Evidence:** `adb install` output. **Status:** `NOT RUN`

### D-22 — Install-source comparison

- **Actions:** compare an install from a browser vs `adb install` vs Play internal testing.
- **Expected:** only the Internet-sideloading source is subject to the sensitive-permission
  auto-block; the trusted channels behave differently. Record which channel triggers what.
- **Evidence:** screen recording. **Status:** `NOT RUN`

---

## 6. Xiaomi / HyperOS specifics (Redmi Note 14, HyperOS 3 / Android 16)

> These run **in addition to** §4–§5 on the Redmi Note 14. No instruction here disables Play
> Protect or grants anything forcibly.

### X-01 — Open Accessibility settings and enable the service

- **Actions:** follow D-02→D-04 to reach Settings; enable QALQON under HyperOS's
  Accessibility menu (wording may differ, e.g. "Accessibility → Downloaded apps").
- **Expected:** the disclosure is shown first (D-02/D-03); the user enables it manually; the row
  becomes Ready.
- **Evidence:** screen recording. **Status:** `NOT RUN`

### X-02 — Usage Access granted and re-checked

- **Actions:** grant Usage Access from the requirements row in HyperOS's "Usage access" list;
  return; then revoke and return again.
- **Expected:** the state is re-checked on resume; correct after both grant and revoke.
- **Evidence:** screen recording. **Status:** `NOT RUN`

### X-03 — Overlay fallback behaviour

- **Actions:** grant "Display pop-up windows while running in the background"
  (`SYSTEM_ALERT_WINDOW`); with Accessibility **off**, open a protected app.
- **Expected:** visual-only scrim (no touch consumption); app reports **LIMITED**.
- **Evidence:** screen recording. **Status:** `NOT RUN`

### X-04 — Camera permission and service state

- **Actions:** grant camera; start protection; check the foreground-service notification; then
  revoke camera in Settings and return.
- **Expected:** recognition works while granted; on revoke the capability drops honestly; no crash.
- **Evidence:** screen recording. **Status:** `NOT RUN`

### X-05 — UI state when protection is off / permission revoked

- **Actions:** turn protection off; and separately revoke Accessibility.
- **Expected:** the UI never claims full protection in either state; a degraded banner names what
  is missing.
- **Evidence:** screen recording. **Status:** `NOT RUN`

### X-06 — Play Protect block + install source (observation only)

- **Purpose:** record the Play Protect behaviour without changing any security setting.
- **Actions:** attempt to install the debug APK from **one** Internet-sideloading source
  (e.g. browser) and **once** via `adb install`; on the block, tap the dialog's details/more
  affordance **only to read** the message — do **not** disable Play Protect.
- **Expected:** record, verbatim, the exact dialog title and body, and which channel triggered it.
  If it is the hard "App blocked to protect your device" screen, note that the sensitive-permission
  block lists `ACCESSIBILITY` and that QALQON declares an AccessibilityService.
- **Failure criterion / caution:** do not disable Play Protect; do not use any bypass; a block is a
  *finding*, not a defect to route around in code.
- **Evidence:** screenshot + verbatim text. **Status:** `NOT RUN`

### X-07 — D-21 on HyperOS (two CI debug APKs)

- **Actions:** install an older CI debug APK, then try to update with a newer one.
- **Expected:** a debug-key rotation makes in-place update fail
  (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`); uninstalling first then installing succeeds.
- **Evidence:** `adb install` output. **Status:** `NOT RUN`

---

## 7. What each result means for Block 3

| Result | Meaning |
|---|---|
| All of D-02..D-09 = PASS on a clean device | the disclosure/enforcement core is device-verified |
| X-01..X-05 = PASS on Redmi Note 14 | the HyperOS path is device-verified |
| Any FAIL | Block 3 stays OPEN; record the log and open a scoped fix |
| Any BLOCKED (e.g. no device) | Block 3 stays OPEN; nothing is marked PASS |

**Block 3 is NOT closed by this document.** It closes only after the device rows above carry
evidence **and** the Play Console declarations (§8) are submitted.

---

## 8. Remaining Play Console work (separate from device testing)

- [ ] App content → **Accessibility** declaration (+ demo video) — answers in the companion doc.
- [ ] App content → **Data safety**.
- [ ] App content → **Foreground service** declaration (`camera` + `specialUse`).
- [ ] Privacy policy URL + account-deletion URL entered.
- [ ] Target audience / content rating (adults; not Designed for Families).
- [ ] Retain raw video/screenshots as evidence.

---

## 9. Revision of record

| Field | Value |
|---|---|
| Branch | `feature/phase4-screen-time-complete` |
| Prepared at commit | `93c4037519b9c878d5829d94e6f68ab783936391` |
| Date | 2026-10-10 |
| Status | PREPARED — NOT RUN. No device test executed by this document. |

Update this table if the guide is edited again.
