#!/bin/bash
# Captures what happens when Z-Sync is sent to the background during a transfer.
#
#   tools/capture-background-drop.sh [seconds]     default 120
#
# What it records, all inside docs/debug/<version>/:
#   phone-logs.txt     full logcat (app logs, freeze/kill, wifi CMD_UNWANTED_NETWORK)
#   process-state.txt  app process state sampled every 2s, so a freeze or kill is visible
#   connectivity.txt   one snapshot of the network requests / camera network at the end
#
# Reproduce while it runs:
#   1. connect, start 同步到相册
#   2. leave Z-Sync for another app (相册 / B站) for ~20s
#   3. come back
# It stops by itself; then send the three files.
set -uo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"
adb="$root/tools/adb/adb"
if [[ ! -x "$adb" ]]; then
  echo "missing adb at $adb" >&2
  exit 1
fi
if ! "$adb" get-state >/dev/null 2>&1; then
  echo "no phone connected" >&2
  exit 1
fi

version="$(sed -nE 's/.*versionName = "([^"]+)".*/\1/p' androidApp/build.gradle.kts | head -1)"
dir="$root/docs/debug/${version:-unknown}"
mkdir -p "$dir"
logs="$dir/phone-logs.txt"
state="$dir/process-state.txt"
conn="$dir/connectivity.txt"
secs="${1:-120}"

echo "phone   : $("$adb" shell getprop ro.product.brand | tr -d '\r') $("$adb" shell getprop ro.product.model | tr -d '\r')"
echo "version : $version"
echo "output  : $dir"
echo

{
  echo
  echo "# ---------- capture started $(date '+%Y-%m-%d %H:%M:%S') version=$version ----------"
} >> "$logs"
echo "# ---------- process state, every 2s, $(date '+%Y-%m-%d %H:%M:%S') ----------" >> "$state"

# Full logcat straight to the file (adb writes to the file itself, so there is no pipe buffer to
# worry about), and the pid we get back is the one to kill when the capture ends.
"$adb" logcat -v threadtime >> "$logs" 2>/dev/null &
logpid=$!
disown

# Sample the app process so a freeze (cached) or a kill shows up with a timestamp. The exact
# dumpsys fields differ per OEM, so record ps (alive / pid / state) plus pssProcState, where a
# small number means foreground and 19 means cached.
(
  end=$(( $(date +%s) + secs ))
  while [[ $(date +%s) -lt $end ]]; do
    alive="$("$adb" shell ps -A -o PID,STAT,NAME 2>/dev/null | grep -i 'app\.zsync' | tr -d '\r' | tr -s ' ' | tr '\n' ' ')"
    detail="$("$adb" shell dumpsys activity processes 2>/dev/null \
      | grep -A20 'ProcessRecord{.*app\.zsync' \
      | grep -E 'pssProcState=|lastActivityTime=' | head -2 | tr -d '\r' | tr -s ' ' | tr '\n' ' ')"
    printf '%s  alive=%-30s %s\n' "$(date '+%H:%M:%S')" "${alive:-NO}" "$detail" >> "$state"
    sleep 2
  done
) 2>/dev/null &
statepid=$!
disown

echo "capturing ${secs}s. Now on the phone:"
echo "  1) 连上相机，点「同步到相册」开始传"
echo "  2) 切到 相册 或 B 站，待 20 秒左右（有流量的话顺手放个视频）"
echo "  3) 回到 Z-Sync"
echo
echo "leave this running until it prints done..."
sleep "$secs"

kill "$statepid" 2>/dev/null || true
kill "$logpid" 2>/dev/null || true
sleep 1
wait 2>/dev/null || true

{
  echo
  echo "# ---------- final process state $(date '+%Y-%m-%d %H:%M:%S') ----------"
  "$adb" shell dumpsys activity processes 2>/dev/null | grep -A20 'ProcessRecord{.*app\.zsync' | head -22 | tr -d '\r'
  echo
  echo "# ---------- activity state ----------"
  "$adb" shell dumpsys activity activities 2>/dev/null | grep -i -B1 -A3 'app\.zsync' | head -20 | tr -d '\r'
  echo
  echo "# ---------- installed version ----------"
  "$adb" shell dumpsys package app.zsync 2>/dev/null | grep -E 'versionCode|versionName|lastUpdateTime' | head -3 | tr -d '\r'
  echo
  echo "# ---------- procstats (last 1h) ----------"
  "$adb" shell dumpsys procstats --hours 1 2>/dev/null | grep -i -A3 'app\.zsync' | head -20 | tr -d '\r'
} >> "$state"

{
  echo "# ---------- connectivity snapshot $(date '+%Y-%m-%d %H:%M:%S') ----------"
  "$adb" shell dumpsys connectivity 2>/dev/null | grep -iE 'z-sync|NIKON|NetworkRequest|NetworkAgentInfo' | head -60 | tr -d '\r'
} > "$conn"

echo
echo "done."
echo "  $logs"
echo "  $state"
echo "  $conn"
