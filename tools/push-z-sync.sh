#!/bin/bash
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"

adb="${root}/tools/adb/adb"
apk="androidApp/build/outputs/apk/debug/androidApp-debug.apk"

if [[ ! -f "$apk" ]]; then
  echo "missing $apk — run tools/build-z-sync.sh first" >&2
  exit 1
fi

"$adb" install -r -d "$apk"

echo "---- phone ----"
"$adb" shell dumpsys package app.zsync | grep -E "versionCode|versionName|lastUpdateTime"

phone_path="$("$adb" shell pm path app.zsync | sed 's/package://' | tr -d '\r')"
echo "---- sha256 ----"
"$adb" shell sha256sum "$phone_path"
shasum -a 256 "$apk"
