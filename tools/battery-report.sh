#!/usr/bin/env bash
# Reports how fast the R1 drains while idle, from the app's screen on/off log plus Android's own
# stats. Unplug the R1 for the test period (USB keeps it awake and charging), then plug it in and
# run this.
#
# Usage: tools/battery-report.sh
set -euo pipefail
source "$(dirname "$0")/lib.sh"
need adb
adb wait-for-device
mkdir -p "$OUT"

adb shell "su -c 'cat /data/data/dev.r1ptt/files/battery.csv'" | tr -d '\r' > "$OUT/battery.csv" ||
  die "Couldn't read the app's battery log (is the app installed and rooted?)"

say "Idle drain (unplugged screen-off periods of 30+ minutes)"
awk -F, 'NR > 1 {
    if (prev_event == "screen_off" && prev_plugged == "false" && $5 == "false") {
      h = ($2 - prev_elapsed) / 3600000.0
      if (h >= 0.5 && $4 <= prev_level) {
        hours += h; drop += prev_level - $4; n++
        printf "  %5.1f h  %3d%% -> %3d%%  wifi at sleep: %s\n", h, prev_level, $4, prev_wifi
      }
    }
    prev_event = $3; prev_elapsed = $2; prev_level = $4; prev_plugged = $5; prev_wifi = $6
  }
  END {
    if (n == 0) { print "  not enough data yet"; exit }
    rate = drop / hours
    printf "  total: %.2f %%/hour over %.1f h", rate, hours
    if (rate > 0) printf "  (about %.0f hours from full)", 100 / rate
    printf "\n"
  }' "$OUT/battery.csv"

say "Suspend (should succeed many times; failures point at a driver or wakelock)"
adb shell "su -c 'for d in /sys/power/suspend_stats /sys/kernel/debug/suspend_stats; do [ -e \$d ] && { cat \$d/success \$d/fail 2>/dev/null || cat \$d; } && break; done'" 2>/dev/null |
  tr -d '\r' | head -20 || true

say "Android battery stats since last full charge"
adb shell dumpsys batterystats --charged | tr -d '\r' |
  grep -E 'Time on battery|Screen off discharge|Screen on discharge|Discharge:|Device light idling|Device full idling|Wifi on:|Mobile radio active' |
  sed 's/^ */  /' | head -20

say "Top kernel wakelocks"
adb shell dumpsys batterystats --charged | tr -d '\r' | sed -n '/All kernel wake locks:/,/^$/p' | head -12

say "Top app wakelocks"
adb shell dumpsys batterystats --charged | tr -d '\r' | sed -n '/All partial wake locks:/,/^$/p' | head -12
