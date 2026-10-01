#!/usr/bin/env bash
# Exact official Android runtime archives for disposable Linux x86_64 CI SDKs.
# Requires the previously accepted Android SDK agreement; accepts no new terms.
set -euo pipefail
: "${ANDROID_HOME:?Set the isolated SDK directory}"
: "${RUNNER_TEMP:?Set a disposable CI temporary directory}"
case "$ANDROID_HOME" in "$RUNNER_TEMP"/*) ;; *) echo 'Use an isolated SDK under RUNNER_TEMP; existing developer SDKs are not modified.' >&2; exit 2;; esac
[[ $(uname -s) == Linux && $(uname -m) == x86_64 ]] || exit 2
[[ -f "$ANDROID_HOME/licenses/android-sdk-license" ]] || { echo 'Accept the Android SDK agreement with the SDK setup helper first.' >&2; exit 2; }
image="$ANDROID_HOME/system-images/android-35/default/x86_64"
[[ ! -e "$ANDROID_HOME/emulator" && ! -e "$image" ]] || { echo 'Use a fresh isolated SDK; runtime package directories already exist.' >&2; exit 2; }
temp=$(mktemp -d)
trap 'rm -rf "$temp"' EXIT
cache=${APP_MATRIX_TOOLCHAIN_CACHE:-$temp}
mkdir -p "$cache"
fetch() {
  local url=$1 sha=$2 name=${1##*/}
  if [[ ! -f "$cache/$name" ]]; then
    curl -fsSL --retry 3 "$url" -o "$temp/download"
    mv "$temp/download" "$cache/$name"
  fi
  printf '%s  %s\n' "$sha" "$cache/$name" | sha256sum -c -
}
fetch https://dl.google.com/android/repository/emulator-linux_x64-15917651.zip 95771e0ae431897b2a4bd2d97fa095f29a8b0624a7b216baf529f9306161c266
fetch https://dl.google.com/android/repository/sys-img/android/x86_64-35_r02.zip 6dd7de33e63ef105cf2fabea6badda1dbe7665c96d8908e5f6e1407e63ff4556
unzip -q "$cache/emulator-linux_x64-15917651.zip" -d "$ANDROID_HOME"
mkdir -p "$ANDROID_HOME/system-images/android-35/default"
unzip -q "$cache/x86_64-35_r02.zip" -d "$ANDROID_HOME/system-images/android-35/default"
grep -Fxq 'Pkg.Revision=37.1.11' "$ANDROID_HOME/emulator/source.properties"
grep -Fxq 'Pkg.Revision=2' "$image/source.properties"
# Google ships the emulator archive without package.xml. Register exactly the
# verified package version, retaining the existing official SDK license text.
# The accepted-license file is not created or changed by this helper.
python3 - "$ANDROID_HOME" <<'REGISTER'
from pathlib import Path
import copy
import sys
import xml.etree.ElementTree as E
sdk = Path(sys.argv[1])
license_element = None
for path in (sdk/'platforms/android-35/package.xml', sdk/'platform-tools/package.xml'):
    if not path.exists():
        continue
    for element in E.parse(path).getroot():
        if element.tag.endswith('license') and element.get('id') == 'android-sdk-license':
            license_element = copy.deepcopy(element)
            break
    if license_element is not None:
        break
if license_element is None:
    raise SystemExit('Official installed SDK package license metadata is required.')
common = 'http://schemas.android.com/repository/android/common/02'
xsi = 'http://www.w3.org/2001/XMLSchema-instance'
E.register_namespace('sdk', common)
E.register_namespace('xsi', xsi)
root = E.Element('{'+common+'}repository', {'xmlns:generic': 'http://schemas.android.com/repository/android/generic/02'})
root.append(license_element)
package = E.SubElement(root, 'localPackage', {'path': 'emulator', 'obsolete': 'false'})
E.SubElement(package, 'type-details', {'{'+xsi+'}type': 'generic:genericDetailsType'})
revision = E.SubElement(package, 'revision')
for key, value in [('major', '37'), ('minor', '1'), ('micro', '11')]:
    E.SubElement(revision, key).text = value
E.SubElement(package, 'display-name').text = 'Android Emulator'
E.SubElement(package, 'uses-license', {'ref': 'android-sdk-license'})
E.ElementTree(root).write(sdk/'emulator/package.xml', encoding='UTF-8', xml_declaration=True)
REGISTER
# Register the official source.properties as local SDK package metadata.
"$ANDROID_HOME/cmdline-tools/15859902/bin/sdkmanager" --sdk_root="$ANDROID_HOME" --list_installed
