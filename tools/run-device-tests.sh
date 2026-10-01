#!/usr/bin/env bash
# Runs framework-only test APKs on an explicitly selected emulator/test device.
set -euo pipefail
cd "$(dirname "$0")/.."
serial=${1:?Usage: tools/run-device-tests.sh SERIAL poster|journal|all}
selection=${2:-all}
case "$selection" in poster|journal) apps=("$selection");; all) apps=(poster journal);; *) exit 2;; esac
: "${ANDROID_HOME:=${ANDROID_SDK_ROOT:-}}"
adb="$ANDROID_HOME/platform-tools/adb"
[[ -x "$adb" ]] || { echo 'Set ANDROID_HOME to your SDK.' >&2; exit 2; }
[[ $("$adb" -s "$serial" get-state) == device ]] || { echo 'Selected test device is unavailable.' >&2; exit 2; }
mkdir -p artifacts/runtime
for app in "${apps[@]}"; do
  ./tools/verify-wrapper.sh
  ./gradlew --no-daemon --console=plain ":apps:$app:assembleDebug" ":apps:$app:assembleDebugAndroidTest"
  "$adb" -s "$serial" install -r "apps/$app/build/outputs/apk/debug/$app-debug.apk"
  "$adb" -s "$serial" install -r -t "apps/$app/build/outputs/apk/androidTest/debug/$app-debug-androidTest.apk"
  case "$app" in poster) runner=PosterInstrumentation;; journal) runner=JournalInstrumentation;; esac
  logfile="artifacts/runtime/$app-instrumentation.txt"
  "$adb" -s "$serial" shell -n am instrument -w "dev.appmatrix.$app.test/dev.appmatrix.$app.$runner" | tee "$logfile"
  python3 - "$logfile" <<'CHECK'
from pathlib import Path
import re, sys
text = Path(sys.argv[1]).read_text()
passed = re.search(r'INSTRUMENTATION_RESULT: passed=(\d+)', text)
if 'INSTRUMENTATION_CODE: -1' not in text or not passed or int(passed[1]) < 1:
    raise SystemExit('Native integration runner did not report successful assertions.')
print(f'Confirmed {passed[1]} passing native integration assertions.')
CHECK
done
