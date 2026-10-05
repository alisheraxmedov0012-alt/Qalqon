#!/usr/bin/env bash
#
# QALQON — Stage 10 device QA harness.
#
# A thin, read-mostly wrapper over `adb` that captures consistent, timestamped
# evidence for the Stage 10 real-device test matrix and the 24/48/72h soak, so a
# reviewer with a physical device can execute the runbook in minutes and without
# transcription mistakes.
#
# It NEVER collects secrets or biometric data: only device properties, package
# state, permission/accessibility/overlay/usage state, battery, memory and logcat
# (which the reviewer is told not to paste raw if it contains personal data).
#
# The commands that mutate the device (`install`, `process-kill`, `reboot`) are
# explicit subcommands; everything else only reads.
#
# Usage:
#   tools/device-qa/device_qa.sh <command> [args]
#
# Commands:
#   gate                     Verify a physical device is attached; fail loudly if not.
#   inventory                Dump manufacturer/model/API/fingerprint/battery/storage.
#   state                    Dump package + permission/accessibility/overlay/usage state.
#   install <path-to.apk>    Install (adb install -r) the given APK.
#   launch                   Launch QALQON's MainActivity.
#   stop                     Force-stop the app (process-death test).
#   reboot                   Reboot the device (asks for confirmation).
#   logcat <seconds> [tag]   Capture logcat for N seconds to l1.
#   crash-scan <seconds>     Watch logcat for crashes/ANRs for N seconds.
#   mem                      Sample app memory (dumpsys meminfo).
#   battery                  Sample battery (dumpsys battery).
#   heartbeat "<note>"       Append one soak heartbeat row to the heartbeat log.
#   where                    Print the evidence directory path.
#
# Environment:
#   QALQON_EVIDENCE_DIR   Evidence output dir (default: /tmp/qalqon-device-qa/<utc-ts>)
#   ANDROID_SERIAL        Target a specific device when several are attached.
#   QALQON_PACKAGE        Package name (default: uz.faceguard.app)

set -uo pipefail

PKG="${QALQON_PACKAGE:-uz.faceguard.app}"
MAIN_ACTIVITY="${PKG}/.MainActivity"

if [[ -z "${QALQON_EVIDENCE_DIR:-}" ]]; then
  QALQON_EVIDENCE_DIR="/tmp/qalqon-device-qa/$(date -u +%Y%m%dT%H%M%SZ)"
fi
EDIR="$QALQON_EVIDENCE_DIR"

die() { echo "ERROR: $*" >&2; exit 1; }
note() { echo "[device-qa] $*"; }

adb_run() {
  if [[ -n "${ANDROID_SERIAL:-}" ]]; then
    adb -s "$ANDROID_SERIAL" "$@"
  else
    adb "$@"
  fi
}

require_adb() {
  command -v adb >/dev/null 2>&1 || die "adb not found on PATH (install platform-tools)."
}

# Fail unless exactly one (or the selected) device is in the 'device' state.
gate() {
  require_adb
  local out
  out="$(adb devices -l | sed '1d' | grep -w 'device' || true)"
  [[ -n "$out" ]] || die "No physical device attached (adb devices shows none). Stage 10 = BLOCKED. See docs/STAGE10_REAL_DEVICE_QA_72H_SOAK.md."
  echo "$out"
}

ensure_dir() { mkdir -p "$EDIR" || die "cannot create evidence dir $EDIR"; }

prop() { adb_run shell getprop "$1" 2>/dev/null | tr -d '\r'; }

cmd_inventory() {
  gate >/dev/null
  ensure_dir
  local f="$EDIR/device_inventory.txt"
  {
    echo "# QALQON device inventory"
    echo "captured_utc: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "serial: ${ANDROID_SERIAL:-$(adb get-serialno)}"
    echo "manufacturer: $(prop ro.product.manufacturer)"
    echo "model: $(prop ro.product.model)"
    echo "device: $(prop ro.product.device)"
    echo "android_version: $(prop ro.build.version.release)"
    echo "api_level: $(prop ro.build.version.sdk)"
    echo "security_patch: $(prop ro.build.version.security_patch)"
    echo "fingerprint: $(prop ro.build.fingerprint)"
    echo "oem_skin: $(prop ro.build.display.id)"
    echo "--- battery ---"
    adb_run shell dumpsys battery 2>/dev/null | tr -d '\r' | sed -n '1,25p'
    echo "--- storage ---"
    adb_run shell df -h /data 2>/dev/null | tr -d '\r'
    echo "--- memory ---"
    adb_run shell cat /proc/meminfo 2>/dev/null | tr -d '\r' | sed -n '1,3p'
  } | tee "$f"
  note "wrote $f"
}

cmd_state() {
  gate >/dev/null
  ensure_dir
  local f="$EDIR/app_state.txt"
  {
    echo "# QALQON app state"
    echo "captured_utc: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "--- package ---"
    adb_run shell dumpsys package "$PKG" 2>/dev/null | tr -d '\r' | grep -E "versionName|versionCode|firstInstallTime|lastUpdateTime" | head
    echo "--- camera permission ---"
    adb_run shell dumpsys package "$PKG" 2>/dev/null | tr -d '\r' | grep -E "android.permission.CAMERA" | head
    echo "--- overlay (SYSTEM_ALERT_WINDOW) appop ---"
    adb_run shell appops get "$PKG" SYSTEM_ALERT_WINDOW 2>/dev/null | tr -d '\r'
    echo "--- usage access appop ---"
    adb_run shell appops get "$PKG" android:get_usage_stats 2>/dev/null | tr -d '\r'
    echo "--- enabled accessibility services ---"
    adb_run shell settings get secure enabled_accessibility_services 2>/dev/null | tr -d '\r'
    echo "--- accessibility enabled flag ---"
    adb_run shell settings get secure accessibility_enabled 2>/dev/null | tr -d '\r'
    echo "--- battery optimization (doze whitelist) ---"
    adb_run shell dumpsys deviceidle whitelist 2>/dev/null | tr -d '\r' | grep -i "$PKG" || echo "(not whitelisted)"
  } | tee "$f"
  note "wrote $f"
}

cmd_install() {
  gate >/dev/null
  local apk="${1:-}"
  [[ -n "$apk" && -f "$apk" ]] || die "usage: install <path-to.apk>"
  ensure_dir
  echo "apk_sha256: $(sha256sum "$apk" | awk '{print $1}')" | tee "$EDIR/install.txt"
  adb_run install -r "$apk" 2>&1 | tee -a "$EDIR/install.txt"
}

cmd_launch() {
  gate >/dev/null
  adb_run shell am start -n "$MAIN_ACTIVITY" 2>&1
}

cmd_stop() {
  gate >/dev/null
  adb_run shell am force-stop "$PKG" 2>&1
  echo "force-stopped $PKG (process-death test)"
}

cmd_reboot() {
  gate >/dev/null
  read -r -p "Reboot $(adb get-serialno)? [y/N] " a
  [[ "$a" == "y" || "$a" == "Y" ]] || { echo "aborted"; exit 0; }
  adb_run reboot
  note "reboot issued; wait for device, then run: device_qa.sh state"
}

cmd_logcat() {
  gate >/dev/null
  local secs="${1:-30}" tag="${2:-}"
  ensure_dir
  local f="$EDIR/logcat_$(date -u +%H%M%S).txt"
  note "capturing logcat for ${secs}s -> $f"
  timeout "$secs" adb_run logcat -v threadtime ${tag:+$tag:V *:S} > "$f" 2>&1 || true
  echo "lines: $(wc -l < "$f")"
}

cmd_crash_scan() {
  gate >/dev/null
  local secs="${1:-60}"
  ensure_dir
  local f="$EDIR/crashscan_$(date -u +%H%M%S).txt"
  note "watching for crashes/ANRs for ${secs}s -> $f"
  timeout "$secs" adb_run logcat -v threadtime > "$f" 2>&1 || true
  local hits
  hits="$(grep -nE "FATAL EXCEPTION|ANR in|beginning of crash|Force finishing" "$f" || true)"
  if [[ -n "$hits" ]]; then
    echo "CRASH/ANR DETECTED:"; echo "$hits"
  else
    echo "no crash/ANR matched in ${secs}s"
  fi
}

cmd_mem() {
  gate >/dev/null
  ensure_dir
  adb_run shell dumpsys meminfo "$PKG" 2>/dev/null | tr -d '\r' | tee -a "$EDIR/memory.log"
}

cmd_battery() {
  gate >/dev/null
  ensure_dir
  adb_run shell dumpsys battery 2>/dev/null | tr -d '\r' | tee -a "$EDIR/battery.log"
}

cmd_heartbeat() {
  ensure_dir
  local note="${1:-}"
  local f="$EDIR/soak_heartbeat.tsv"
  [[ -f "$f" ]] || printf 'timestamp_utc\tdevice\tbattery\tnetwork\tprotection\tcurrent_app\tface_state\texpected\tactual\tissue\tresult\n' > "$f"
  local b n
  b="$(adb_run shell dumpsys battery 2>/dev/null | tr -d '\r' | grep -i ' level:' | awk '{print $2}')"
  n="$(adb_run shell dumpsys connectivity 2>/dev/null | tr -d '\r' | grep -i 'Active default' | head -1)"
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "${ANDROID_SERIAL:-$(adb get-serialno 2>/dev/null)}" \
    "${b:-?}" "${n:-?}" "?" "?" "?" "$note" "" "" "REVIEW" >> "$f"
  note "appended heartbeat (fill expected/actual/result) -> $f"
}

case "${1:-}" in
  gate) shift; gate "${@:-}" ;;
  inventory) shift; cmd_inventory ;;
  state) shift; cmd_state ;;
  install) shift; cmd_install "$@" ;;
  launch) shift; cmd_launch ;;
  stop) shift; cmd_stop ;;
  reboot) shift; cmd_reboot ;;
  logcat) shift; cmd_logcat "$@" ;;
  crash-scan) shift; cmd_crash_scan "$@" ;;
  mem) shift; cmd_mem ;;
  battery) shift; cmd_battery ;;
  heartbeat) shift; cmd_heartbeat "$@" ;;
  where) echo "$EDIR" ;;
  ""|-h|--help|help) grep -E '^#( |$)' "$0" | sed 's/^# \{0,1\}//' ;;
  *) die "unknown command: $1 (run with no args for help)" ;;
esac
