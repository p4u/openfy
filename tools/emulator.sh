#!/usr/bin/env bash
# Headless emulator helper for the osp35 AVD (API 35, Google Play, x86_64).
# Usage: tools/emulator.sh {start|stop|shot <file>}
set -euo pipefail
source "$(dirname "$0")/env.sh"
AVD=osp35
LOG=/tmp/osp35-emulator.log

case "${1:-}" in
  start)
    if adb get-state >/dev/null 2>&1; then echo "emulator already running"; else
      # Audio left enabled (default). Add -no-audio if the host audio backend misbehaves.
      setsid nohup emulator -avd "$AVD" -no-window -no-boot-anim \
        -gpu swiftshader_indirect -no-snapshot-save >"$LOG" 2>&1 </dev/null &
      adb wait-for-device
    fi
    for _ in $(seq 1 120); do
      [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && { echo "boot completed"; exit 0; }
      sleep 5
    done
    echo "boot timeout; see $LOG" >&2; exit 1 ;;
  stop) adb emu kill ;;
  shot) [ -n "${2:-}" ] || { echo "usage: $0 shot <file>" >&2; exit 2; }
        adb exec-out screencap -p > "$2" ;;
  *) echo "usage: $0 {start|stop|shot <file>}" >&2; exit 2 ;;
esac
