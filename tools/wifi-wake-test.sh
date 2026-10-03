#!/usr/bin/env bash
# Times how long the R1's Wi-Fi takes to come back after the app's idle cut: that's how long the
# first press after a long idle waits, and it decides how aggressive power.wifiIdleMinutes can be.
# Runs over USB (adb doesn't need Wi-Fi).
#
# Usage: tools/wifi-wake-test.sh [rounds]     (default 3)
set -euo pipefail
source "$(dirname "$0")/lib.sh"
need adb
ROUNDS=${1:-3}
adb wait-for-device

now_ms() { node -e 'console.log(Date.now())'; }
connected() { adb shell cmd wifi status 2>/dev/null | grep -q 'Wifi is connected to'; }
has_ip() { adb shell ip -4 addr show wlan0 2>/dev/null | grep -q 'inet '; }

connected || die "Wi-Fi isn't connected to start with."
for r in $(seq 1 "$ROUNDS"); do
  adb shell cmd wifi set-wifi-enabled disabled
  sleep 5
  t0=$(now_ms)
  adb shell cmd wifi set-wifi-enabled enabled
  until connected; do sleep 0.2; done
  t1=$(now_ms)
  until has_ip; do sleep 0.2; done
  t2=$(now_ms)
  say "round $r: associated after $(( (t1 - t0) )) ms, has an IP after $(( (t2 - t0) )) ms"
  sleep 3
done
