#!/bin/bash
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"

./gradlew :androidApp:assembleDebug --no-daemon
echo "package: androidApp/build/outputs/apk/debug/androidApp-debug.apk"
