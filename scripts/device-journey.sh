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
adb pull "/sdcard/Android/data/$PKG/files/device-shots/." device-shots/ || true
ls -la device-shots
adb logcat -d > logcat.txt
if grep -q "OK (1 test)" journey.log; then
  echo "Device journey passed."
else
  echo "::error::Device journey failed"
  grep -B2 -A40 "FAILURES\|Error in\|Exception" journey.log | head -120
  grep -A40 "FATAL EXCEPTION" logcat.txt | head -80
  exit 1
fi
