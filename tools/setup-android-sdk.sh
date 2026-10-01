#!/usr/bin/env bash
# Downloads only official Google packages. Does not accept unrelated SDK licenses.
set -euo pipefail
if [[ ${1:-} != --accept-sdk-license ]]; then
  echo 'Read https://developer.android.com/studio/terms first.' >&2
  echo 'To accept the Android SDK agreement for the requested build packages, run:' >&2
  echo '  ANDROID_HOME=/absolute/path tools/setup-android-sdk.sh --accept-sdk-license' >&2
  exit 2
fi
[[ $(uname -s) == Linux && $(uname -m) == x86_64 ]] || { echo 'This download helper supports Linux x86_64; use Android Studio on other systems.' >&2; exit 2; }
: "${ANDROID_HOME:?Set ANDROID_HOME to an absolute SDK directory}"
[[ $ANDROID_HOME == /* ]] || { echo 'ANDROID_HOME must be absolute.' >&2; exit 2; }
tools="$ANDROID_HOME/cmdline-tools/15859902"
if [[ ! -x "$tools/bin/sdkmanager" ]]; then
  temp=$(mktemp -d)
  trap 'rm -rf "$temp"' EXIT
  curl -fsSL --retry 3 https://dl.google.com/android/repository/commandlinetools-linux-15859902_latest.zip -o "$temp/tools.zip"
  printf '%s  %s\n' 4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583 "$temp/tools.zip" | sha256sum -c -
  unzip -q "$temp/tools.zip" -d "$temp/unpack"
  mkdir -p "$(dirname "$tools")"
  mv "$temp/unpack/cmdline-tools" "$tools"
fi
# Only these packages are requested, so no Google Play, preview, or other licenses
# are accepted by this helper. sdkmanager verifies downloaded package checksums.
set +o pipefail
yes | "$tools/bin/sdkmanager" --sdk_root="$ANDROID_HOME" 'platforms;android-35' 'build-tools;35.0.0' 'platform-tools'
status=${PIPESTATUS[1]}
set -o pipefail
exit "$status"
