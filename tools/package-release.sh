#!/bin/bash
# Builds the signed release APK and drops it into dist/ with a SHA-256 next to it.
#
#   tools/package-release.sh            build only
#   tools/package-release.sh --install  build, then install on the connected phone
#
# The release key lives in keystore/ and its passwords in keystore.properties; both are
# created on first run and kept out of git. Back them up: signing an update with a different
# key means the phone has to uninstall first.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"

install_after=""
if [[ "${1:-}" == "--install" ]]; then install_after="yes"; fi

gradle_file="androidApp/build.gradle.kts"
version="$(sed -nE 's/.*versionName = "([^"]+)".*/\1/p' "$gradle_file" | head -1)"
code="$(sed -nE 's/.*versionCode = ([0-9]+).*/\1/p' "$gradle_file" | head -1)"
if [[ -z "$version" || -z "$code" ]]; then
  echo "cannot read versionName/versionCode from $gradle_file" >&2
  exit 1
fi

props="$root/keystore.properties"
if [[ ! -f "$props" ]]; then
  mkdir -p "$root/keystore"
  pw="$(head -c 64 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 24)"
  keytool -genkeypair \
    -keystore "$root/keystore/z-sync-release.jks" \
    -alias z-sync -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$pw" -keypass "$pw" \
    -dname "CN=Z-Sync, O=Z-Sync, C=CN" >/dev/null 2>&1
  cat > "$props" <<EOF
storeFile=keystore/z-sync-release.jks
storePassword=$pw
keyAlias=z-sync
keyPassword=$pw
EOF
  chmod 600 "$props"
  echo "created a release keystore in keystore/ (kept out of git; back it up)"
fi

./gradlew :androidApp:assembleRelease --no-daemon

src="$(find androidApp/build/outputs/apk/release -maxdepth 1 -name '*.apk' | head -1)"
if [[ -z "$src" ]]; then
  echo "release apk not found under androidApp/build/outputs/apk/release" >&2
  exit 1
fi

out="$root/dist"
mkdir -p "$out"
name="z-sync-$version-$code.apk"
cp "$src" "$out/$name"
( cd "$out" && shasum -a 256 "$name" > "$name.sha256" )

echo
echo "package : dist/$name"
echo "sha256  : $(cut -d' ' -f1 "$out/$name.sha256")"

if [[ -n "$install_after" ]]; then
  adb="${root}/tools/adb/adb"
  if ! "$adb" install -r "$out/$name" >/dev/null 2>&1; then
    # Moving from a debug-signed build to the release key changes the signature, so the phone
    # refuses the update. Uninstall once and install clean.
    echo "install rejected (signature changed?); uninstalling app.zsync and retrying"
    "$adb" uninstall app.zsync >/dev/null 2>&1 || true
    "$adb" install "$out/$name" >/dev/null
  fi
  echo "---- phone ----"
  "$adb" shell dumpsys package app.zsync | grep -E "versionCode|versionName|lastUpdateTime" | head -3
  phone_path="$("$adb" shell pm path app.zsync | sed 's/package://' | tr -d '\r')"
  echo "---- sha256 ----"
  "$adb" shell sha256sum "$phone_path"
  ( cd "$out" && shasum -a 256 "$name" )
fi
