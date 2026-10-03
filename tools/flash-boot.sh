#!/usr/bin/env bash
# Flashes a Magisk-patched copy of the stock boot.img to both slots, which roots the R1.
#
# Make the patched image on the R1: install Magisk (adb install Magisk.apk), push the stock boot.img
# (adb push boot.img /sdcard/Download/), then Magisk → Install → "Select and Patch a File".
# Pull it back with: adb pull /sdcard/Download/magisk_patched-XXXXX.img out/
#
# Usage: tools/flash-boot.sh <magisk_patched-*.img>     (R1 booted with USB debugging, or in fastboot)
set -euo pipefail
source "$(dirname "$0")/lib.sh"

[ $# -ge 1 ] && [ -f "$1" ] || die "usage: $0 <magisk_patched-*.img>"
need fastboot
need adb

if adb get-state >/dev/null 2>&1; then
  say "Rebooting to the bootloader"
  adb reboot bootloader
fi
wait_fastboot
if in_fastbootd; then
  fastboot reboot bootloader
  sleep 5
  wait_fastboot
fi

for s in a b; do fastboot flash "boot_$s" "$1"; done
say "Rebooting. Open Magisk on the R1 to confirm it shows as installed; next: tools/provision.sh"
fastboot reboot
