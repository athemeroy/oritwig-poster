#!/usr/bin/env bash
# Intended for a disposable CI runner with existing authorized KVM access.
# It never changes device permissions, groups, or host security settings.
set -euo pipefail
selection=${1:-all}
case "$selection" in poster|journal) apps=("$selection");; all) apps=(poster journal);; *) echo 'Choose poster, journal, or all.' >&2; exit 2;; esac
cd "$(dirname "$0")/.."
: "${ANDROID_HOME:?Set ANDROID_HOME}"
: "${RUNNER_TEMP:?Run on a disposable CI runner with RUNNER_TEMP}"
[[ -r /dev/kvm && -w /dev/kvm ]] || { echo 'Runner has no existing read/write KVM access; an administrator must authorize it.' >&2; exit 2; }
sdkmanager="$ANDROID_HOME/cmdline-tools/15859902/bin/sdkmanager"
[[ -x "$sdkmanager" ]] || { echo 'Run the pinned SDK setup helper first.' >&2; exit 2; }
# The base Android SDK agreement must already be accepted; no additional
# agreement is accepted implicitly here.
./tools/setup-pinned-emulator.sh
grep -Fxq 'Pkg.Revision=37.1.11' "$ANDROID_HOME/emulator/source.properties"
grep -Fxq 'Pkg.Revision=2' "$ANDROID_HOME/system-images/android-35/default/x86_64/source.properties"
export ANDROID_USER_HOME="$RUNNER_TEMP/app-matrix-android-user"
export ANDROID_AVD_HOME="$RUNNER_TEMP/app-matrix-avds"
mkdir -p "$ANDROID_USER_HOME" "$ANDROID_AVD_HOME" artifacts/runtime
printf 'no\n' | "$ANDROID_HOME/cmdline-tools/15859902/bin/avdmanager" create avd --name app-matrix-ci --package 'system-images;android-35;default;x86_64' --device pixel_2 --path "$ANDROID_AVD_HOME/app-matrix-ci.avd"
adb="$ANDROID_HOME/platform-tools/adb"
"$adb" start-server
"$ANDROID_HOME/emulator/emulator" -accel-check
"$ANDROID_HOME/emulator/emulator" -avd app-matrix-ci -accel on -no-window -no-audio -no-snapshot -no-boot-anim -gpu swiftshader -memory 2048 -cores 2 > artifacts/runtime/emulator.txt 2>&1 &
emulator_pid=$!
cleanup() {
  status=$?
  "$adb" -s emulator-5554 logcat -d > artifacts/runtime/logcat.txt 2>&1 || true
  "$adb" -s emulator-5554 emu kill >/dev/null 2>&1 || kill "$emulator_pid" 2>/dev/null || true
  exit "$status"
}
trap cleanup EXIT
booted=false
deadline=$((SECONDS + 600))
while (( SECONDS < deadline )); do
  kill -0 "$emulator_pid" 2>/dev/null || { cat artifacts/runtime/emulator.txt >&2; exit 1; }
  if [[ $(timeout 15 "$adb" -s emulator-5554 shell -n getprop sys.boot_completed 2>/dev/null | tr -d '\r') == 1 ]]; then
    booted=true; break
  fi
  sleep 5
done
[[ $booted == true ]] || { echo 'Android emulator did not complete boot within 10 minutes.' >&2; exit 1; }
"$adb" -s emulator-5554 shell -n input keyevent 82
./tools/run-device-tests.sh emulator-5554 "$selection"
for app in "${apps[@]}"; do
  "$adb" -s emulator-5554 shell -n am start -W -n "dev.appmatrix.$app/.MainActivity"
  sleep 2
  "$adb" -s emulator-5554 exec-out screencap -p > "artifacts/runtime/$app-launcher.png"
done
