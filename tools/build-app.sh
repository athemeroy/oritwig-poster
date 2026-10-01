#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
app=${1:?Usage: tools/build-app.sh poster|journal}
case "$app" in poster|journal) ;; *) echo 'Choose poster or journal.' >&2; exit 2;; esac
./tools/verify-core.py
./tools/verify-wrapper.sh
: "${ANDROID_HOME:=${ANDROID_SDK_ROOT:-}}"
[[ -f "$ANDROID_HOME/platforms/android-35/android.jar" ]] || { echo 'Set ANDROID_HOME to an SDK containing platforms;android-35. See docs/BUILDING.md.' >&2; exit 2; }
[[ -x "$ANDROID_HOME/build-tools/35.0.0/aapt2" ]] || { echo 'Install build-tools;35.0.0. See docs/BUILDING.md.' >&2; exit 2; }
export ANDROID_HOME
# API level alone is insufficient: pin the actual Android 35 revision-2 API stubs.
printf '%s  %s\n' '4566663c3876e022b4fa4ced8c8697c4ab1688267f090114fd92d027b32e619b' "$ANDROID_HOME/platforms/android-35/android.jar" | sha256sum -c -
./gradlew --no-daemon --console=plain --stacktrace ":apps:$app:assembleDebug" ":apps:$app:assembleRelease" ":apps:$app:lintDebug" ':shared:core:testDebugUnitTest' ":apps:$app:testDebugUnitTest"
# Do not mistake Gradle NO-SOURCE or wholly skipped tests for actual coverage.
python3 - "$app" <<'CHECK_TESTS'
from pathlib import Path
import sys
import xml.etree.ElementTree as ET
for module in ('shared/core', f'apps/{sys.argv[1]}'):
    reports = list(Path(module, 'build/test-results/testDebugUnitTest').glob('TEST-*.xml'))
    count = 0
    for report in reports:
        root = ET.parse(report).getroot()
        count += int(root.get('tests', 0)) - int(root.get('skipped', 0))
        if int(root.get('failures', 0)) or int(root.get('errors', 0)):
            raise SystemExit(f'Unit test failure in {report}')
    if not count:
        raise SystemExit(f'No unit test assertions ran in {module}.')
    print(f'Verified {count} passing, non-skipped unit tests in {module}.')
CHECK_TESTS
./tools/verify-apks.sh "$app"
