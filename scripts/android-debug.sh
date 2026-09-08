#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [ -z "${JAVA_HOME:-}" ] && [ -d /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ]; then
  export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
fi
./android/gradlew -p android :app:assembleDebug
mkdir -p artifacts
cp android/app/build/outputs/apk/debug/app-debug.apk artifacts/star-explorer-native.apk
if [ "${1:-}" = '--install' ]; then
  adb install -r artifacts/star-explorer-native.apk
  adb shell am start -n com.starexplorer.app/.MainActivity
fi
