#!/bin/sh
# Build output -> emulator: install -r, cold start, wait for the scene.
SERIAL=${SERIAL:-emulator-5562}
ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
APK=C:/Users/Admin/Downloads/feel-number/app/build/outputs/apk/debug/app-debug.apk
"$ADB" -s $SERIAL shell am force-stop io.embodit.feelnumber
"$ADB" -s $SERIAL install -r "$APK" | tail -1
"$ADB" -s $SERIAL logcat -c
"$ADB" -s $SERIAL shell monkey -p io.embodit.feelnumber -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep ${1:-12}
"$ADB" -s $SERIAL logcat -d -s FeelNumber:* AndroidRuntime:E | grep -E "STATE|SCENE|ROOM|FATAL|Exception" | cut -c19- | head -20
