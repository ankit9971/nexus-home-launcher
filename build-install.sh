#!/usr/bin/env bash
# Build the launcher (debug build) and install it on a TV or projector over Wi-Fi ADB.
#   TV=<ip>:5555 ./build-install.sh     build + install
#   ./build-install.sh build            build only
# Needs a JDK 17+ (JAVA_HOME), the Android SDK with platform 34 and build-tools 34 (ANDROID_HOME), and adb.
#   macOS: brew install openjdk@21 android-platform-tools && brew install --cask android-commandlinetools
set -euo pipefail
cd "$(dirname "$0")/launcher"
./gradlew --console=plain assembleDebug
APK=app/build/outputs/apk/debug/app-debug.apk
echo "built $APK ($(wc -c < "$APK") bytes)"
[ "${1:-}" = build ] && exit 0
: "${TV:?set TV=<ip>:5555 (Developer options: USB debugging and Network debugging on)}"
adb connect "$TV" >/dev/null
# install through the shell (adb install can wait for an on-screen confirmation on some TVs)
adb -s "$TV" push "$APK" /data/local/tmp/nexus-home.apk
adb -s "$TV" shell pm install -r /data/local/tmp/nexus-home.apk
adb -s "$TV" shell rm /data/local/tmp/nexus-home.apk
adb -s "$TV" shell pm grant app.nexus.launcher android.permission.ACCESS_FINE_LOCATION || true
echo "installed. To make it the home screen (the stock launcher must be disabled first on most firmware):"
echo "  adb -s $TV shell cmd package set-home-activity app.nexus.launcher/.LauncherActivity"
