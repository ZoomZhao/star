#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [ -z "${JAVA_HOME:-}" ] && [ -d /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ]; then
  export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
fi
VITE_API_URL=http://127.0.0.1:3001 npm run build
STAR_ANDROID_DEV=true npx cap sync android
./android/gradlew -p android :app:assembleDebug
mkdir -p artifacts
cp android/app/build/outputs/apk/debug/app-debug.apk artifacts/star-explorer-usb-debug.apk
adb reverse tcp:3001 tcp:3001
adb install -r artifacts/star-explorer-usb-debug.apk
adb shell am start -n com.starexplorer.app/.MainActivity
