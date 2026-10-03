#!/usr/bin/env bash
# Roots the R1 by patching its *current* boot image with Magisk from this computer, so there's no
# tapping through the Magisk app on the 2.9" screen. Uses Magisk's own boot_patch.sh, run on the
# R1 as root; needs a userdebug system (the LineageOS GSI is one) so `adb root` can read the boot
# partition. The unpatched image is kept in out/ for unrooting.
#
# Usage: tools/root.sh [Magisk.apk]     (default: the newest firmware/Magisk-*.apk)
set -euo pipefail
source "$(dirname "$0")/lib.sh"
need adb
need fastboot

APK=${1:-$(find "$ROOT/firmware" -maxdepth 1 -name 'Magisk-*.apk' 2>/dev/null | sort | tail -1)}
[ -n "$APK" ] && [ -f "$APK" ] || die "No Magisk APK: download Magisk-v*.apk into firmware/ (github.com/topjohnwu/Magisk/releases)"

adb wait-for-device
adb root >/dev/null 2>&1 || true
adb wait-for-device
[ "$(adb shell id -u | tr -d '\r')" = 0 ] || die "adb root isn't available on this build (enable Rooted debugging in Developer options)"
SLOT=$(adb shell getprop ro.boot.slot_suffix | tr -d '\r')
[ -n "$SLOT" ] || die "Couldn't read the active slot"

say "Installing the Magisk app"
adb install -r "$APK" >/dev/null

say "Patching boot$SLOT with Magisk's own script, on the R1"
T=$(mktemp -d)
unzip -q -j "$APK" 'assets/*.sh' 'assets/stub.apk' 'lib/arm64-v8a/*' -d "$T"
for f in "$T"/lib*.so; do n=$(basename "$f" .so); mv "$f" "$T/${n#lib}"; done
adb shell 'rm -rf /data/local/tmp/magisk && mkdir -p /data/local/tmp/magisk'
adb push "$T/." /data/local/tmp/magisk/ >/dev/null
rm -rf "$T"
if ! LOG=$(adb shell "dd if=/dev/block/by-name/boot$SLOT of=/data/local/tmp/magisk/boot.img bs=1M 2>&1 &&
    cd /data/local/tmp/magisk && chmod 755 * && sh ./boot_patch.sh boot.img 2>&1 && test -s new-boot.img"); then
  printf '%s\n' "$LOG" | tail -15 >&2
  die "Magisk couldn't patch the boot image"
fi

mkdir -p "$OUT"
adb pull /data/local/tmp/magisk/boot.img "$OUT/boot$SLOT-unpatched.img" >/dev/null
adb pull /data/local/tmp/magisk/new-boot.img "$OUT/magisk_patched-boot.img" >/dev/null
adb shell 'rm -rf /data/local/tmp/magisk'
say "Saved $OUT/boot$SLOT-unpatched.img (flash it back with tools/flash-boot.sh to unroot)"

"$ROOT/tools/flash-boot.sh" "$OUT/magisk_patched-boot.img"
