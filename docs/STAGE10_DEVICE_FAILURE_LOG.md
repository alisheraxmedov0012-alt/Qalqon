# QALQON — Stage 10: Device Failure / Blocked Log

> Companion to `STAGE10_REAL_DEVICE_QA_72H_SOAK.md`. Records (a) blocked real-device
> tests with the exact reason and unblock criteria, and (b) the **template** for
> real-device failures to be filled when a device is available.
>
> **Special-permission/OEM blocker (Redmi Note 14):** analysed and fixed generically with
> JVM regression tests; device verification is BLOCKED (no device). See
> `STAGE10_SPECIAL_PERMISSION_OEM_COMPATIBILITY.md`.
>
> No fabricated entries: there are no observed device failures because no device run
> occurred. Only the verified environment block is recorded.

---

## A. Environment block (verified)

| Field | Value |
|---|---|
| Date | 2026-10-05 |
| Environment | OpenHands dev container (ephemeral) |
| Baseline HEAD | `e995abed91baead0388f464a1a0ad238e6115bc5` |
| Physical device | **NONE** |
| Emulator | **NONE** (emulator package not installed; no AVD) |
| `adb devices -l` | empty (`List of devices attached`) |
| `adb get-state` | `error: no devices/emulators found` |
| `adb connect 127.0.0.1:5555` | `Connection refused` |
| `/dev/bus/usb` | absent (no USB access) |
| `/sys/bus/usb/devices` | empty |
| `/dev/kvm` | absent (no hardware acceleration) |
| CPU virtualization flags | 0 (`vmx`/`svm`) |
| Consequence | All real-device QA + 24/48/72h soak = **BLOCKED** |

### Unblock criteria
1. Attach a physical Android device (API 33–36) with USB debugging; confirm
   `adb devices` shows it as `device` (the harness `gate` command enforces this).
2. (For multi-OEM) attach a second device on a different OEM.
3. Provide a previous-version APK for the upgrade test.
4. Provide a Play Console app + `qalqon_premium` + license tester for real billing.
5. Allocate 72 h wall-clock for the final soak.
6. Run `tools/device-qa/device_qa.sh` (see `STAGE10_DEVICE_EVIDENCE.md` §6) to capture
   consistent evidence.

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
