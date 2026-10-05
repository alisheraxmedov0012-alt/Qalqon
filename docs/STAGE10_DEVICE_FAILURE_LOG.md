# QALQON — Stage 10: Device Failure / Blocked Log

> Companion to `STAGE10_REAL_DEVICE_QA_72H_SOAK.md`. Records (a) blocked real-device
> tests with the exact reason and unblock criteria, and (b) the **template** for
> real-device failures to be filled when a device is available.
>
> No fabricated entries: there are no observed device failures because no device run
> occurred. Only the verified environment block is recorded.

---

## A. Environment block (verified)

| Field | Value |
|---|---|
| Date | 2026-10-05 |
| Environment | OpenHands dev container (ephemeral) |
| Physical device | **NONE** |
| Emulator | **NONE** (emulator package not installed; no AVD) |
| `adb devices -l` | empty (`List of devices attached`) |
| `/dev/bus/usb` | absent (no USB access) |
| `/dev/kvm` | absent (no hardware acceleration) |
| CPU virtualization flags | 0 (`vmx`/`svm`) |
| Consequence | All real-device QA + 24/48/72h soak = **BLOCKED** |

### Unblock criteria
1. Attach a physical Android device (API 33–36) with USB debugging; confirm
   `adb devices` shows it as `device`.
2. (For multi-OEM) attach a second device on a different OEM.
3. Provide a previous-version APK for the upgrade test.
4. Provide a Play Console app + `qalqon_premium` + license tester for real billing.
5. Allocate 72 h wall-clock for the final soak.

---

## B. Device failure template (to fill per device run)

| Field | Value |
|---|---|
| Timestamp | |
| Device ID / model / manufacturer | |
| Android version / API / OEM skin | |
| Build fingerprint / security patch | |
| Battery % / charging | |
| Network state | |
| Permission state (camera/usage/overlay/accessibility/notifications) | |
| Protection state (ON/OFF, degraded capabilities) | |
| Steps to reproduce | |
| Expected | |
| Actual | |
| Logs (`adb logcat` excerpt) | |
| Screenshot / screen recording | |
| Severity (P0/P1/P2/P3) | |
| Root cause | |
| Fix | |
| Regression test (automated or device-case) | |
| Real-device re-test result | |

**Privacy:** do not attach face images, PINs, purchase tokens or any personal data. Log
excerpts must be sanitised.

---

## C. Soak heartbeat log template

| TIMESTAMP | DEVICE | ACTION | EXPECTED | ACTUAL | CPU/MEM | BATTERY | CRASH/ANR | RESULT |
|---|---|---|---|---|---|---|---|---|
| | | | | | | | | |

---

## D. Logged entries

_None — no device run was possible. This section will record real findings once a device
is attached._
