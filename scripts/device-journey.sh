#!/usr/bin/env bash
# Runs DeviceJourneyTest (the real app on a running emulator against a local FoodFeed
# server) and collects the screenshots it takes into ./device-shots.
# usage: scripts/device-journey.sh app-debug.apk app-debug-androidTest.apk
set -u
APP="$1"
TESTS="$2"
PKG=app.foodfeed.android.debug
adb install -r -g "$APP" || exit 1
adb install -r "$TESTS" || exit 1
adb logcat -c
adb shell am instrument -w -r -e class app.foodfeed.DeviceJourneyTest "$PKG.test/androidx.test.runner.AndroidJUnitRunner" | tee journey.log
mkdir -p device-shots
# The test app's own files; run-as works because the debug build is debuggable.
for f in $(adb shell run-as "$PKG" ls files/device-shots 2>/dev/null | tr -d '\r'); do
  adb exec-out run-as "$PKG" cat "files/device-shots/$f" > "device-shots/$f"
done
ls -la device-shots
adb logcat -d > logcat.txt
echo "--- UI tree and app log lines"
grep -E "JOURNEY|FoodFeed|app.foodfeed" logcat.txt | tail -150
if grep -q "OK (1 test)" journey.log; then
  echo "Device journey passed."
else
  echo "::error::Device journey failed"
  grep -B2 -A40 "FAILURES\|Error in\|Exception" journey.log | head -120
  grep -A40 "FATAL EXCEPTION" logcat.txt | head -80
  exit 1
fi
