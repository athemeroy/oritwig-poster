#!/usr/bin/env bash
# Verify product identity, development/release signing state, notices and provenance.
set -euo pipefail
cd "$(dirname "$0")/.."
app=${1:?Usage: tools/verify-apks.sh poster|journal}
case "$app" in poster|journal) ;; *) exit 2;; esac
: "${ANDROID_HOME:=${ANDROID_SDK_ROOT:-}}"
python3 - "$app" "$ANDROID_HOME/build-tools/35.0.0" <<'CHECK'
from pathlib import Path
import re
import struct
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile

app, tool_directory = sys.argv[1:]
tools = Path(tool_directory)
root = Path.cwd()


def require(condition, message):
    if not condition:
        raise SystemExit(message)


def run(*args):
    return subprocess.run(args, check=True, text=True, capture_output=True).stdout


# Keep version expectations tied to the checked-in product definition, rather
# than accepting whatever version happened to be left in the output directory.
build = Path(f'apps/{app}/build.gradle').read_text()


def literal(pattern, description):
    matches = re.findall(pattern, build, re.MULTILINE)
    require(len(matches) == 1, f'Expected one literal {description} in apps/{app}/build.gradle')
    return matches[0]


application_id = literal(r"^\s*applicationId\s+['\"]([^'\"]+)['\"]\s*$", 'applicationId')
version_code = literal(r'^\s*versionCode\s+(\d+)\s*$', 'versionCode')
version_name = literal(r"^\s*versionName\s+['\"]([^'\"]+)['\"]\s*$", 'versionName')
require(application_id == f'dev.appmatrix.{app}', 'Unexpected product application ID')
manifest = ET.parse(f'apps/{app}/src/main/AndroidManifest.xml').getroot()
label = manifest.find('application').get('{http://schemas.android.com/apk/res/android}label')
if label and label.startswith('@string/'):
    values = ET.parse(f'apps/{app}/src/main/res/values/strings.xml').getroot()
    label = next((x.text for x in values if x.get('name') == label[8:]), None)
require(label, 'Cannot determine product label from checked-in resources')

# The consumer carries the complete core source; neither a sibling checkout nor
# a moving remote branch may supply hidden build inputs.
core_check = subprocess.run([sys.executable, str(root / 'tools/verify-core.py')],
                            text=True, capture_output=True)
require(core_check.returncode == 0, core_check.stdout + core_check.stderr)
core_lock = (root / 'vendor/core.lock.json').read_bytes()
source_url = f'https://github.com/athemeroy/oritwig-{app}'


def compiled_source_urls(xmltree):
    """Read only direct attributes of Android manifest meta-data elements."""
    found = []
    fields = None
    element_indent = None
    for line in xmltree.splitlines():
        indent = len(line) - len(line.lstrip())
        element = re.match(r'\s*E: ([^ ]+)', line)
        if element:
            if fields is not None and indent <= element_indent:
                if fields.get('name') == 'dev.oritwig.source_url':
                    found.append(fields.get('value'))
                fields = None
            if element.group(1) == 'meta-data':
                fields, element_indent = {}, indent
        elif fields is not None and indent > element_indent:
            attribute = re.search(r'A: android:(name|value)\([^)]*\)="([^"]*)"', line)
            if attribute:
                fields[attribute.group(1)] = attribute.group(2)
    if fields is not None and fields.get('name') == 'dev.oritwig.source_url':
        found.append(fields.get('value'))
    return found

# An archive can be built without Git, but must not claim a verified commit.
# package-artifacts.sh additionally requires a clean, committed checkout.
commit = ''
try:
    if Path(run('git', 'rev-parse', '--show-toplevel').strip()).resolve() == root:
        commit = run('git', 'rev-parse', '--verify', 'HEAD').strip()
except (subprocess.CalledProcessError, FileNotFoundError):
    pass

license_files = (
    'GPL-3.0.txt', 'Telegram-GPL-2.0.txt', 'Markor-Public-Domain.txt',
    'Markor-Unlicense.txt', 'Markor-CC0-1.0.txt',
)
license_bytes = {}
for name in license_files:
    canonical = Path('LICENSE') if name == 'GPL-3.0.txt' else Path('third_party/licenses', name)
    expected = canonical.read_bytes()
    require(expected and Path('shared/core/src/main/assets/licenses', name).read_bytes() == expected,
            f'In-app license source differs from canonical notice: {name}')
    license_bytes[name] = expected

for variant in ('debug', 'release'):
    suffix = 'debug' if variant == 'debug' else 'release-unsigned'
    apk_path = Path(f'apps/{app}/build/outputs/apk/{variant}/{app}-{suffix}.apk')
    badging = run(str(tools / 'aapt'), 'dump', 'badging', str(apk_path))
    package_line = next((line for line in badging.splitlines() if line.startswith('package: ')), '')
    package = dict(re.findall(r"(\w+)='([^']*)'", package_line))
    require(package.get('name') == application_id, f'Wrong application ID in {apk_path}')
    require(package.get('versionCode') == version_code and package.get('versionName') == version_name,
            f'APK version does not match the current product source: {apk_path}')
    require("sdkVersion:'26'" in badging.splitlines(), f'Wrong minimum SDK in {apk_path}')
    require("targetSdkVersion:'35'" in badging.splitlines(), f'Wrong target SDK in {apk_path}')
    require(f"application-label:'{label}'" in badging.splitlines(), f'Wrong app label in {apk_path}')
    debuggable = 'application-debuggable' in badging.splitlines()
    require(debuggable == (variant == 'debug'), f'Unexpected debuggable flag in {apk_path}')
    permissions = run(str(tools / 'aapt'), 'dump', 'permissions', str(apk_path))
    require('uses-permission' not in permissions, f'Unexpected permission in {apk_path}')
    xmltree = run(str(tools / 'aapt'), 'dump', 'xmltree', str(apk_path), 'AndroidManifest.xml')
    require(compiled_source_urls(xmltree) == [source_url],
            f'Compiled source URL does not point uniquely to this app repository: {apk_path}')

    with zipfile.ZipFile(apk_path) as apk:
        require(apk.testzip() is None, f'Corrupt ZIP entry in {apk_path}')
        require(apk.read('assets/oritwig/core-source.json') == core_lock,
                f'Embedded core provenance differs from the current immutable source lock: {apk_path}')
        for name in ('AndroidManifest.xml', 'classes.dex'):
            require(apk.read(name), f'Empty required APK content: {name}')
        for name in license_files:
            expected = license_bytes[name]
            require(apk.read('assets/licenses/' + name) == expected,
                    f'Missing, stale or altered license {name} in {apk_path}')
        if variant == 'release':
            # A failed apksigner command alone cannot prove an APK is unsigned:
            # it could have a damaged signature. Reject both v1 signature entries
            # and the v2/v3 signing-block structure explicitly.
            signatures = [name for name in apk.namelist() if re.fullmatch(
                r'META-INF/(?:MANIFEST\.MF|[^/]+\.(?:SF|RSA|DSA|EC))', name, re.IGNORECASE)]
            require(not signatures, f'Unexpected JAR signing entries in unsigned release: {apk_path}')
            data = apk_path.read_bytes()
            eocd = data.rfind(b'PK\x05\x06', max(0, len(data) - 65557))
            require(eocd >= 0 and eocd + 22 <= len(data), 'Cannot locate APK central directory')
            central_offset = struct.unpack_from('<I', data, eocd + 16)[0]
            require(central_offset != 0xffffffff, 'ZIP64 APKs require explicit signing-block verification')
            require(data[max(0, central_offset - 16):central_offset] != b'APK Sig Block 42',
                    f'Unexpected APK signing block in unsigned release: {apk_path}')
            metadata = apk.read('META-INF/version-control-info.textproto').decode('utf-8')
            if commit:
                revisions = re.findall(r'\brevision:\s*"([0-9a-f]{40})"', metadata)
                require('generate_error_reason:' not in metadata and revisions == [commit],
                        f'Release source metadata does not match HEAD {commit}: {apk_path}')
                require('local_root_path: "$PROJECT_DIR"' in metadata,
                        f'Unexpected source path in release metadata: {apk_path}')
                print(f'Verified release source commit: {commit}')
            else:
                print('No committed project checkout: release source identity is unverified; '
                      'this is a development build, not a verified distribution bundle.')

    signature = subprocess.run([str(tools / 'apksigner'), 'verify', '--verbose', '--print-certs',
                                str(apk_path)], text=True, capture_output=True)
    if variant == 'debug':
        require(signature.returncode == 0, f'Debug APK signature failed: {apk_path}\n{signature.stderr}')
        require(re.search(r'^Signer #1 certificate DN: .*\bCN=Android Debug(?:,|$)',
                          signature.stdout, re.MULTILINE),
                f'Expected a local Android Debug certificate: {apk_path}')
    else:
        require(signature.returncode != 0, f'Release unexpectedly signed: {apk_path}')
    print(f'Verified {app} {variant}: {application_id}, version {version_name} ({version_code}), '
          'API 26+, label, debug/signing state, no permissions, all five exact license assets, '
          'own source URL and exact embedded core lock.')
CHECK
