#!/usr/bin/env bash
# Puts a known stock base on the R1: rabbitOS v0.8.293's kernel (boot), dtbo, vbmeta and super
# (system + vendor + product), all on slot a, with verified boot turned off.
#
# Rabbit's own flash tool (https://rabbit-hmi-oss.github.io/flashing/ → "Flash Stock ROM") does
# the same and also rewrites the firmware blobs; prefer it, and use this script if you'd rather
# stay on the command line. Either way, boot once into rabbitOS to confirm the base works.
#
# Never touched: preloader, lk, nvram/nvdata/nvcfg, protect*, proinfo, seccfg, frp, md1img.
#
# Usage: tools/flash-baseline.sh <folder with the extracted rabbit_OS_v0.8.293.zip>
#        (R1 in fastboot mode, bootloader unlocked)
set -euo pipefail
source "$(dirname "$0")/lib.sh"

[ $# -ge 1 ] || die "usage: $0 <extracted stock firmware folder>"
need fastboot
IMG=$(stock_dir "$1")
for f in boot.img dtbo.img vbmeta.img vbmeta_system.img vbmeta_vendor.img super.img; do
  [ -f "$IMG/$f" ] || die "missing $IMG/$f"
done

wait_fastboot
in_fastbootd && die "This is fastbootd; reboot to the bootloader first: fastboot reboot bootloader"
fastboot getvar unlocked 2>&1 | grep -q 'unlocked: yes' || die "Bootloader is locked. Unlock it first (see docs/INSTALL.md)."

confirm "Flash stock v0.8.293 boot/dtbo/vbmeta/super and ERASE all data on the R1?"

say "Slot a, stock kernel and dtbo"
# Stock super holds slot-a partitions, so slot a must be the one that boots (OTAs may have left b active).
fastboot --set-active=a || true
fastboot getvar current-slot 2>&1 | grep -q 'current-slot: a' ||
  die "Couldn't switch to slot a. Use Rabbit's flash tool for the baseline instead."
for s in a b; do
  fastboot flash "boot_$s" "$IMG/boot.img"
  fastboot flash "dtbo_$s" "$IMG/dtbo.img"
done

say "vbmeta with verification off"
flash_vbmeta_disabled "$IMG"

say "Rebooting to fastbootd for the super partition"
fastboot reboot fastboot
sleep 5
wait_fastboot
in_fastbootd || die "Didn't reach fastbootd."
fastboot snapshot-update cancel 2>/dev/null || true
say "Flashing super (about two minutes)"
fastboot flash super "$IMG/super.img"
fastboot -w

say "Booting stock rabbitOS. Once it reaches its setup screen the base is good; next: tools/flash-gsi.sh"
fastboot reboot
