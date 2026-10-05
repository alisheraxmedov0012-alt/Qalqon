# QALQON — Stage 10: Device Evidence

> Companion to `STAGE10_REAL_DEVICE_QA_72H_SOAK.md` and
> `STAGE10_DEVICE_FAILURE_LOG.md`. Records the **fresh** physical-device gate on the
> Stage 10 continuation, the (empty) device inventory, the readiness checklist, and the
> exact reproducible commands. No real-device result is claimed.

---

## 1. Session

| Item | Value |
|---|---|
| Date (UTC) | 2026-10-05 |
| Baseline HEAD | `e995abed91baead0388f464a1a0ad238e6115bc5` |
| Branch | `feature/phase4-screen-time-complete` |
| Worktree | clean, local == remote |

## 2. Physical-device gate — result: **NO DEVICE**

Re-run fresh on the Stage 10 continuation (not carried over):

| Probe | Command | Output | Meaning |
|---|---|---|---|
| ADB daemon restart | `adb kill-server && adb start-server` | started | adb healthy |
| Device list | `adb devices -l` | `List of devices attached` (empty) | **no device** |
| Device state | `adb get-state` | `error: no devices/emulators found` | **no device** |
| USB bus | `ls -l /dev/bus/usb` | `No such file or directory` | no USB access → cannot attach a phone |
| USB sysfs | `ls /sys/bus/usb/devices` | (empty) | no USB devices |
| KVM | `ls -l /dev/kvm` | `No such file or directory` | no hardware acceleration |
| CPU virtualization | `grep -c 'vmx\|svm' /proc/cpuinfo` | `0` | no nested virtualization |
| Emulator package | `ls $ANDROID_HOME/emulator` | not installed | no emulator |
| AVDs | `ls ~/.android/avd` | not installed | no AVD |
| Network ADB (local) | `adb connect 127.0.0.1:5555` | `Connection refused` | no TCP-attached device |

**Conclusion: 0 physical devices and 0 emulators.** Real-device QA and the 24/48/72h
soaks remain **BLOCKED**. (An emulator would not satisfy the stage even if present.)

## 3. Device inventory

**Empty — no device to enumerate.** For each device the runbook requires manufacturer,
model, Android/API, OEM skin, RAM, storage, security patch, fingerprint. **None available.**

## 4. Pre-test health checklist

`N/A` for every item (no device): battery level/temperature, free storage, developer
options, USB debugging, network state, camera, notifications, accessibility, overlay,
usage access, battery optimization, date/time, timezone.

## 5. Build artifact (the device-testable input, verified)

Built from the current source tree:

| Field | Value |
|---|---|
| Command | `./gradlew :app:assembleDebug` |
| File | `app/build/outputs/apk/debug/app-debug.apk` |
| Package | `uz.faceguard.app` |
| versionCode / versionName | `1` / `0.1.0` |
| compileSdk / targetSdk | `36` / `36` |
| size | 75,211,431 bytes |
| SHA-256 | `76b19e62d4f6fb85356ed988b27330a83538a6e5990ac88d2159529785f38437` |

This is the exact artifact the harness installs; it proves an installable build exists,
and does **not** substitute for running it on a device.

## 6. Executable harness (new, to run the stage fast when a device is attached)

`tools/device-qa/device_qa.sh` — a read-mostly `adb` wrapper that produces consistent,
timestamped evidence. It never collects secrets or biometric data. Syntax-checked with
`bash -n`; `gate` verified to fail cleanly when no device is present.

```bash
export ANDROID_HOME=$HOME/Android/Sdk
export PATH=$ANDROID_HOME/platform-tools:$PATH

tools/device-qa/device_qa.sh gate                      # fails loudly if no device
tools/device-qa/device_qa.sh inventory > inventory.txt # manufacturer/model/API/patch/battery/storage
tools/device-qa/device_qa.sh install app/build/outputs/apk/debug/app-debug.apk
tools/device-qa/device_qa.sh launch
tools/device-qa/device_qa.sh state                     # camera/overlay/usage/accessibility/doze state
tools/device-qa/device_qa.sh logcat 60                 # evidence log
tools/device-qa/device_qa.sh crash-scan 120            # FATAL/ANR scan
tools/device-qa/device_qa.sh stop                      # process-death test
tools/device-qa/device_qa.sh mem / battery             # soak samples
tools/device-qa/device_qa.sh heartbeat "wake+protected app: child blocked"
```

Evidence lands under `QALQON_EVIDENCE_DIR` (default `/tmp/qalqon-device-qa/<utc-ts>/`).

## 7. Evidence rules (enforced)

Record: screenshots, logcat, `adb`/`dumpsys` output, timestamps, device info, APK SHA,
test notes. **Never** commit: face images, biometric embeddings, PIN/passwords, billing
or license credentials. Log excerpts must be sanitised of personal data.

## 8. Verdict

**BLOCKED** — the physical-device gate failed on this continuation exactly as before
(0 devices). Nothing is marked PASS. The stage is now turnkey: attach one device (API
33–36), and `tools/device-qa/device_qa.sh` + the runbook execute the matrix and the soak
with consistent evidence.
