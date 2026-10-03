# Shared helpers for the tools/*.sh scripts. Source, don't run.

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$ROOT/out"

say()  { printf '\033[1;32m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m!!\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31mxx\033[0m %s\n' "$*" >&2; exit 1; }

need() { command -v "$1" >/dev/null 2>&1 || die "$1 not found (install Android platform-tools / $1)"; }

# Ask before anything destructive. Skipped with YES=1.
confirm() {
  [ "${YES:-0}" = 1 ] && return 0
  read -r -p "$1 [y/N] " a
  [ "$a" = y ] || [ "$a" = Y ] || die "Cancelled."
}

# Waits until `fastboot devices` lists something (bootloader or fastbootd).
wait_fastboot() {
  for _ in $(seq 1 90); do
    fastboot devices 2>/dev/null | grep -q . && return 0
    sleep 1
  done
  die "No fastboot device after 90 s."
}

# True when connected to userspace fastbootd rather than the bootloader's fastboot.
in_fastbootd() { fastboot getvar is-userspace 2>&1 | grep -q 'is-userspace: yes'; }

# Finds the folder with boot.img inside an extracted stock firmware zip.
stock_dir() {
  local d
  d=$(find "$1" -name boot.img -not -path '*__MACOSX*' -print -quit 2>/dev/null)
  [ -n "$d" ] || die "No boot.img under $1 (extract rabbit_OS_v0.8.293.zip there first)."
  dirname "$d"
}

# Writes a copy of a vbmeta image with verification off and prints its path. This sets the AVB
# header's flags (big-endian u32 at byte 120) to 3, HASHTREE_DISABLED | VERIFICATION_DISABLED: the
# same edit `fastboot --disable-verity --disable-verification` makes, done here because some
# fastboot builds (e.g. 36.0.2) fail it with "Failed to find AVB_MAGIC at offset: 0".
vbmeta_disabled() {
  local src=$1 dst
  dst="$OUT/$(basename "$1" .img)-noverify.img"
  mkdir -p "$OUT"
  [ "$(head -c 4 "$src")" = AVB0 ] || die "$src doesn't start with an AVB header"
  cp "$src" "$dst"
  printf '\000\000\000\003' | dd of="$dst" bs=1 seek=120 count=4 conv=notrunc 2>/dev/null
  echo "$dst"
}

# Flashes vbmeta images with verification off: the R1 only boots a non-Rabbit system this way.
flash_vbmeta_disabled() {
  local img=$1 v s patched
  for v in vbmeta vbmeta_system vbmeta_vendor; do
    patched=$(vbmeta_disabled "$img/$v.img")
    for s in a b; do
      fastboot flash "${v}_$s" "$patched"
    done
  done
}

# Decompresses .gz / .xz images into out/ and prints the resulting path.
unpacked() {
  local f=$1 base
  base=$(basename "$f")
  mkdir -p "$OUT"
  case "$f" in
    *.gz) [ -f "$OUT/${base%.gz}" ] || gunzip -c "$f" > "$OUT/${base%.gz}"; echo "$OUT/${base%.gz}" ;;
    *.xz) [ -f "$OUT/${base%.xz}" ] || xz -dc "$f" > "$OUT/${base%.xz}"; echo "$OUT/${base%.xz}" ;;
    *)    echo "$f" ;;
  esac
}
