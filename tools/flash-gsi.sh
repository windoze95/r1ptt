#!/usr/bin/env bash
# Replaces rabbitOS's system with a LineageOS 21 GSI, keeping Rabbit's kernel and vendor (the side
# button only works with the stock kernel). Also turns verified boot off and wipes data.
#
# GSI: Andy Yan's LineageOS 21, arm64 vanilla, e.g. lineage-21.0-YYYYMMDD-UNOFFICIAL-arm64_bvN.img.gz
#      https://sourceforge.net/projects/andyyan-gsi/files/  (lineage-21-pre-qpr2-td or lineage-21-td)
#
# Usage: tools/flash-gsi.sh <gsi .img|.img.gz|.img.xz> <extracted stock firmware folder> [--keep-product]
#        (R1 in fastboot mode, bootloader unlocked, stock baseline flashed)
set -euo pipefail
source "$(dirname "$0")/lib.sh"

[ $# -ge 2 ] || die "usage: $0 <gsi image> <extracted stock firmware folder> [--keep-product]"
need fastboot
GSI=$(unpacked "$1")
IMG=$(stock_dir "$2")
KEEP_PRODUCT=0
[ "${3:-}" = "--keep-product" ] && KEEP_PRODUCT=1
[ -f "$GSI" ] || die "GSI not found: $GSI"

wait_fastboot
if in_fastbootd; then
  say "In fastbootd; going to the bootloader first for vbmeta"
  fastboot reboot bootloader
  sleep 5
  wait_fastboot
fi
fastboot getvar unlocked 2>&1 | grep -q 'unlocked: yes' || die "Bootloader is locked."

confirm "Flash $(basename "$GSI") over system_a and ERASE all data on the R1?"

say "vbmeta with verification off"
flash_vbmeta_disabled "$IMG"

say "Rebooting to fastbootd"
fastboot reboot fastboot
sleep 5
wait_fastboot
in_fastbootd || die "Didn't reach fastbootd."
fastboot snapshot-update cancel 2>/dev/null || true

if [ "$KEEP_PRODUCT" = 0 ]; then
  # Stock product holds Rabbit's launcher and services: dead weight (and battery drain) on a GSI.
  say "Removing stock product_a"
  fastboot delete-logical-partition product_a 2>/dev/null || warn "product_a not deleted (maybe already gone)"
fi

say "Flashing the GSI to system_a"
fastboot flash system_a "$GSI"
fastboot -w

say "Booting LineageOS. First boot takes a few minutes."
say "Next: finish setup on the R1, enable USB debugging, then follow docs/INSTALL.md (Magisk)."
fastboot reboot
