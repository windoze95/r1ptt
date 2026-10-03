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

# Flashes vbmeta images with verification off: the R1 only boots a non-Rabbit system this way.
flash_vbmeta_disabled() {
  local img=$1 v s
  for v in vbmeta vbmeta_system vbmeta_vendor; do
    for s in a b; do
      fastboot --disable-verity --disable-verification flash "${v}_$s" "$img/$v.img"
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
