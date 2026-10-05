# QALQON — Stage 10: Real Device QA & 72-Hour Soak

> Commercial Master Plan — Stage 10 deliverable.
> Baseline HEAD: `d57d8d98bfda0caca36b99bd9c0f5faf0cb24b6f` (Stage 9, PASS WITH LIMITATIONS).
>
> **Status of this stage in this environment: BLOCKED — no physical Android device.**
> No real-device result is reported as PASS, because none was executed. This document is
> the executable runbook + evidence template for when a device is available, plus the
> exact environment evidence that establishes the block.

---

## 1. Baseline

| Item | Value |
|---|---|
| HEAD | `d57d8d98bfda0caca36b99bd9c0f5faf0cb24b6f` |
| Branch | `feature/phase4-screen-time-complete` |
| Stage 9 tests | 1917 JVM (0/0/0), 560 instrumented |
| Stage 9 CI | `37261141622` (success) |
| Worktree | clean, local == remote |

## 2. Device inventory — NONE AVAILABLE

**Result: no physical Android device is reachable from this environment.**

| Probe | Command | Output | Meaning |
|---|---|---|---|
| ADB devices | `adb devices -l` | `List of devices attached` (empty) | No device/emulator connected |
| USB subsystem | `ls -l /dev/bus/usb` | `No such file or directory` | No USB access → a USB device could not be attached |
| USB sysfs | `ls /sys/bus/usb/devices` | (empty) | No USB devices |
| KVM | `ls -l /dev/kvm` | `No such file or directory` | No hardware acceleration → no local emulator |
| CPU virtualization | `grep -c 'vmx\|svm' /proc/cpuinfo` | `0` | No nested virtualization |
| Emulator package | `ls $ANDROID_HOME/emulator` | not installed | No local emulator |
| AVDs | `ls ~/.android/avd` | not installed | No AVD configured |

Because there is **no device and no emulator**, and the task requires **physical-device**
validation (an emulator is explicitly not a substitute), every real-device test below is
`BLOCKED`. Multi-OEM coverage is therefore impossible: **0 devices**, not "verified".

## 3. What a realistic device QA run needs (unblock criteria)

To execute this stage, the reviewer/device lab must provide at least:
- One **physical Android phone** (API 33–36), USB-debugging enabled, connected via ADB.
- A second device on a **different OEM** to claim any multi-OEM result (e.g. Pixel +
  Samsung/Xiaomi).
- The **installable artifact** (debug APK is built by CI and by
  `./gradlew :app:assembleDebug`; a release-signed APK is out of scope until Stage 11).
- A **previous-version APK** to run the upgrade test (see §4).
- For billing: a **Play Console** app with the `qalqon_premium` product + license-tester.
- Time: **72 h wall-clock** of continuous use with periodic interaction for the final soak.

> Network ADB / a cloud device farm would also work if such access were provided; neither
> is available in this environment.

## 4. Test matrix (all real-device rows BLOCKED here)

Legend: `PASS` executed+passed · `FAIL` executed+failed · `BLOCKED` could not execute ·
`N/A` does not apply. **In this environment every real-device row is BLOCKED**
(no device); the artifact/static prerequisites are noted.

| # | Test | Device | Result | Evidence | Blocker |
|---|---|---|---|---|---|
| 1 | Fresh install | — | BLOCKED | — | no device |
| 2 | Upgrade (N→N+1) | — | BLOCKED | — | no device **and** no previous-version APK |
| 3 | Reinstall | — | BLOCKED | — | no device |
| 4 | Reboot (protection ON) | — | BLOCKED | — | no device |
| 5 | Reboot (protection OFF stays OFF) | — | BLOCKED | — | no device |
| 6 | Sleep / wake | — | BLOCKED | — | no device |
| 7 | Lock / unlock | — | BLOCKED | — | no device |
| 8 | Battery low (20/10/5%) | — | BLOCKED | — | no device / no battery |
| 9 | Battery saver | — | BLOCKED | — | no device |
| 10 | Internet OFF | — | BLOCKED | — | no device |
| 11 | Internet ON | — | BLOCKED | — | no device |
| 12 | Camera permission grant/revoke | — | BLOCKED | — | no device / no camera |
| 13 | Accessibility enable/disable | — | BLOCKED | — | no device |
| 14 | Overlay grant/revoke | — | BLOCKED | — | no device |
| 15 | Usage Access grant/revoke | — | BLOCKED | — | no device |
| 16 | Parent detection | — | BLOCKED | — | no device / no camera |
| 17 | Child detection | — | BLOCKED | — | no device / no camera |
| 18 | Unknown / no-face | — | BLOCKED | — | no device / no camera |
| 19 | Multiple faces | — | BLOCKED | — | no device / no camera |
| 20 | App switching | — | BLOCKED | — | no device |
| 21 | Screen-time boundary | — | BLOCKED | — | no device |
| 22 | Schedule boundary | — | BLOCKED | — | no device |
| 23 | Subscription (real) | — | BLOCKED | — | no device / no Play Console / no license tester |
| 24 | Cancellation (real) | — | BLOCKED | — | same |
| 25 | Expiration (real) | — | BLOCKED | — | same |
| 26 | Account/reset deletion | — | BLOCKED | — | no device |
| 27 | Process death / recovery | — | BLOCKED | — | no device |
| 28 | TalkBack accessibility | — | BLOCKED | — | no device / no TalkBack |
| 29 | 24-hour soak | — | BLOCKED | — | no device |
| 30 | 48-hour soak | — | BLOCKED | — | no device |
| 31 | 72-hour soak | — | BLOCKED | — | no device |
| 32 | OEM matrix (Samsung/Xiaomi/Pixel/…) | — | BLOCKED | — | 0 devices |

## 5. Method (to run per device, when available)

### 5.1 Pre-test baseline
Install the APK; record `adb shell getprop ro.build.fingerprint`, `ro.product.*`,
Android/API, security patch, RAM/storage/battery (`adb shell dumpsys battery`); launch
once; capture DB version, permission/accessibility/overlay/usage/notification states,
protection + subscription state, parent/child/face/settings state; keep a baseline
screenshot/log.

### 5.2 Fresh install
Uninstall → (optional reboot) → install → first launch → onboarding → register + PIN →
parent profile + face → add child + face → select protected apps → enable protection →
normal use. Verify: no crash/ANR/blank screen; DB created; settings persisted; recognition
and protection work.

### 5.3 Upgrade
If a previous-version APK exists: create realistic data (parent, child, faces, policies,
schedules, screen time, activity, subscription state, protection ON) → upgrade the APK →
launch → verify **data preserved, migration successful, no duplicates/corruption**. If no
previous APK exists: **BLOCKED** (do not fabricate).

### 5.4 Reboot
Protection ON → reboot → unlock → open a protected app: verify restore, no crash, no
duplicate service, no restart loop. Then Protection OFF → reboot → verify stays OFF.

### 5.5 Sleep/wake, lock/unlock
Repeat cycles; verify camera/recognition recovery, no stuck overlay, no duplicate camera
session, no frozen UI.

### 5.6 Battery low / battery saver / internet
Low battery (20/10/5%), battery saver ON/OFF, internet OFF/ON: verify protection remains
functional (or degrades cleanly), no crash, no unacceptable recovery loop; internet state
must not change protection logic (offline-first).

### 5.7 Permissions
Camera / Usage Access / Overlay / Accessibility / Notifications: grant → use → revoke →
use → re-grant → recovery; verify no crash, clear state, safe fallback, retry path, and
that the accessibility **disclosure+consent** appears before enabling.

### 5.8 Recognition & protection
Parent (angles/light/movement/glasses), child (protected+allowed apps), unknown, no-face,
multiple faces. Verify: CHILD+protected→block; CHILD+allowed→allow; parent→unrestricted;
no-face/unknown→configured policy. This is **functional validation, not a FAR/FRR study**;
Stage 5 anti-spoof limitations remain (do not mark them "fixed").

### 5.9 Screen time / schedule
Small/normal/exhausted limits with real time boundaries; schedule before/inside/after and
an overnight schedule (23:xx→00:xx→01:xx); record exact times.

### 5.10 Subscription / account / process death
Real Play purchase/cancel/expire only with a configured Play product + license tester;
otherwise **BLOCKED**. Account reset → verify all local data + Keystore material removed.
Kill the process via ADB; relaunch; verify state.

## 6. Soak method (24 / 48 / 72 h)

**Heartbeat log** (see `STAGE10_DEVICE_FAILURE_LOG.md` for the template): for each
observation record `TIMESTAMP | DEVICE | ACTION | EXPECTED | ACTUAL | CPU/MEM | BATTERY |
CRASH/ANR | RESULT`. During the soak, protection stays ON and a **realistic periodic
interaction** pattern runs at varied times (§32 of the brief) — not just leaving the phone
idle. Any crash/ANR/freeze/service death/camera/overlay/recognition failure/state
corruption is recorded, and a critical bug restarts the qualification clock after the fix.

**No soak duration was run in this environment** (no device) → 24h/48h/72h = BLOCKED.

## 7. Failure protocol
Bug → reproduce → capture (timestamp, device, Android, battery, network, permission,
protection state, steps, expected vs actual, logs/screenshot) → root cause → **minimal
fix** → **automated regression test** (Stage 9 rule) → build → **real-device re-test** →
restart/continue soak. Biometric images/credentials are never committed as evidence.

## 8. Severity gates
- **P0** (data loss, privacy breach, protection fully bypassed, account/entitlement
  cross-contamination, crash loop, device-unusable): must be closed before PASS.
- **P1** (protection frequently fails, service permanently dies, child detection
  systematically fails, parent wrongly blocked, state corruption, material subscription
  error): must be closed before PASS.
- **P2/P3**: recoverable/UI/cosmetic.

Because no device run occurred, **no P0/P1 was observed** — and equally, **none was ruled
out**. The block is therefore explicit.

## 9. Automated validation performed (the only executable part)

- `./gradlew :app:testDebugUnitTest` → **1917 / 0 / 0 / 0** (verified this stage).
- `./gradlew :app:assembleDebug` → **installable** debug APK produced (the
  device-testable artifact), verified with `aapt2 dump badging`:

  | Field | Value |
  |---|---|
  | package | `uz.faceguard.app` |
  | versionCode / versionName | `1` / `0.1.0` |
  | compileSdkVersion | `36` |
  | targetSdkVersion | `36` |
  | application-label | `Qalqon` |
  | size | 75,211,431 bytes |
  | SHA-256 | `76b19e62d4f6fb85356ed988b27330a83538a6e5990ac88d2159529785f38437` |

  This proves a valid, installable artifact exists for device testing; it does **not**
  substitute for running it on a device.
- CI (`build` + API 35 instrumentation) re-verified green after this commit.

## 10. Verdict

**BLOCKED.** No physical Android device (and no emulator) is available, so real-device QA
and the 24/48/72-hour soaks could not be executed. Per the stage's no-fake-pass policy,
nothing is reported as PASS. Automated suites remain green (the necessary-but-not-
sufficient precondition). This stage becomes executable as soon as a physical device is
attached (see §3 unblock criteria).
