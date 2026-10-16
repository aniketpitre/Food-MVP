#!/usr/bin/env bash
# Installs a release APK on a running emulator, starts it, lets Android's monkey tester tap
# around for a while, and fails if the app crashed or froze (ANR).
# usage: scripts/device-smoke.sh path/to/app-release.apk
set -u
APK="$1"
PKG=app.foodfeed.android
adb install -r "$APK" || exit 1
adb logcat -c
adb shell am start -n "$PKG/app.foodfeed.MainActivity" >/dev/null
sleep 15
adb shell monkey -p "$PKG" --pct-syskeys 0 --pct-appswitch 0 --pct-anyevent 0 --throttle 120 -s 42 -v 600 > monkey.log 2>&1
sleep 3
echo "--- monkey summary"; tail -5 monkey.log
# Only FoodFeed's own crashes count (system apps on the emulator image sometimes crash too).
if grep -qE "// CRASH: $PKG|// NOT RESPONDING: $PKG" monkey.log || adb logcat -d | grep -qE "E AndroidRuntime: Process: $PKG"; then
  echo "::error::FoodFeed crashed or froze on the emulator"
  grep -A30 -E "// CRASH|// NOT RESPONDING" monkey.log | head -80
  adb logcat -d | grep -B2 -A40 "Process: $PKG" | head -120
  exit 1
fi
echo "No crashes."
