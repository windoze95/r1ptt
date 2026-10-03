#!/usr/bin/env bash
# Flashes a Magisk-patched boot image to the active slot, which roots the R1 (tools/root.sh makes one).
#
# tools/root.sh makes the patched image from the R1's own boot partition and calls this. By hand: in the
# Magisk app, Install → "Select and Patch a File", then adb pull /sdcard/Download/magisk_patched-*.img.
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

# Only the active slot: the other one may hold a different kernel generation.
SLOT=$(fastboot getvar current-slot 2>&1 | sed -n "s/^current-slot: \([ab]\).*/\1/p")
[ -n "$SLOT" ] || die "Couldn't read the active slot."
fastboot flash "boot_$SLOT" "$1"
say "Rebooting. Open Magisk on the R1 to confirm it shows as installed; next: tools/provision.sh"
fastboot reboot
