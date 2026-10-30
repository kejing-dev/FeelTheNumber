#!/bin/sh
# QA helper for Feel the Number.
#   fn.sh cmd <command> [am extras...]   send a debug broadcast and print the reply lines
#   fn.sh shot <name> [wait_s]           console screenshot (two in a row, the second kept) -> $SHOTS/<name>.png
SERIAL=${SERIAL:-emulator-5562}
ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
SHOTS=${SHOTS:-C:/Users/Admin/Downloads/spatial-app-factory/apps/feel-number/build/shots}
case "$1" in
  cmd)
    shift; c=$1; shift
    "$ADB" -s $SERIAL logcat -c
    "$ADB" -s $SERIAL shell am broadcast -a io.embodit.feelnumber.DEBUG -p io.embodit.feelnumber --es cmd "$c" "$@" >/dev/null
    sleep ${WAIT:-2}
    "$ADB" -s $SERIAL logcat -d -s FeelNumber:* AndroidRuntime:E | grep -v "^---" | cut -c19- ;;
  shot)
    name=$2; sleep ${3:-6}
    tmp=$(mktemp -d); w=$(cygpath -w "$tmp")
    "$ADB" -s $SERIAL emu screenrecord screenshot "$w" >/dev/null; sleep 5
    rm -f "$tmp"/*.png
    "$ADB" -s $SERIAL emu screenrecord screenshot "$w" >/dev/null; sleep 1
    mkdir -p "$SHOTS"; mv "$(ls -t "$tmp"/*.png | head -1)" "$SHOTS/$name.png" && echo "$SHOTS/$name.png"
    rm -rf "$tmp" ;;
esac
