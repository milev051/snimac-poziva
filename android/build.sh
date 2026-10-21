#!/bin/bash
# Napravi APK.
#   ./build.sh          release APK (potpisan ključem iz ~/.config/snimac-poziva)
#   ./build.sh install  napravi i pošalji na povezan telefon
#   ./build.sh test     samo JVM testovi
set -euo pipefail
cd "$(dirname "$0")"

if [ ! -f local.properties ]; then
  echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
fi

if [ "${1:-}" = "test" ]; then
  ./gradlew testReleaseUnitTest
  exit 0
fi

./gradlew testReleaseUnitTest assembleRelease
APK=app/build/outputs/apk/release/app-release.apk
echo
echo "APK: $APK  ($(du -h "$APK" | cut -f1))"

if [ "${1:-}" = "install" ]; then
  if [ -z "$(adb devices | sed '1d' | grep -w device || true)" ]; then
    echo "Nema povezanog telefona. Uključi USB debugging pa: adb devices"
    exit 1
  fi
  adb install -r "$APK"
  echo "Instalirano."
fi
