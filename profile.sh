#!/usr/bin/env bash
# Records which code runs on a rooted phone and saves it as app/src/main/baseline-prof.txt,
# so release builds are compiled ahead of time for the paths that are really used.
set -e
cd "$(dirname "$0")"
export MSYS_NO_PATHCONV=1
PKG=dev.kevtr.hilightlink
./gradlew --console=plain -q :app:assembleProfiling
adb install -r app/build/outputs/apk/profiling/app-profiling.apk
adb shell cmd package compile --reset $PKG
adb shell am start -n $PKG/.ui.MainActivity
read -r -p "Use every screen of the app on the phone, then press Enter here. "
adb shell "kill -10 \$(pidof $PKG); sleep 2; cmd package snapshot-profile $PKG"
APK=$(adb shell pm path $PKG | tr -d '\r' | sed 's/package://')
adb shell "/apex/com.android.art/bin/profman --dump-classes-and-methods \
  --profile-file=/data/misc/profman/$PKG.prof --apk=$APK --dex-location=$APK" |
  tr -d '\r' | sed 's/+.*$//' | grep -E '^H?S?P?L' | LC_ALL=C sort -u > app/src/main/baseline-prof.txt
wc -l app/src/main/baseline-prof.txt
echo "Now run ./build.sh and install the release build again."
