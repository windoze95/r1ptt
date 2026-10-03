#!/usr/bin/env bash
# Builds everything the R1 needs into out/: the app (release APK) and the Magisk module zip.
set -euo pipefail
source "$(dirname "$0")/lib.sh"

mkdir -p "$OUT"

if [ -z "${JAVA_HOME:-}" ] && [ -d /opt/homebrew/opt/openjdk@21 ]; then
  export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
fi

say "Building the app"
(cd "$ROOT" && ./gradlew -q :app:assembleRelease)
cp "$ROOT/app/build/outputs/apk/release/app-release.apk" "$OUT/r1ptt.apk"

say "Packing the Magisk module"
rm -f "$OUT/r1ptt-system.zip"
(cd "$ROOT/magisk/r1ptt-system" && zip -qr -X "$OUT/r1ptt-system.zip" . -x '.*' -x '*/.*')

ls -l "$OUT/r1ptt.apk" "$OUT/r1ptt-system.zip"
