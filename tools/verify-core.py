#!/usr/bin/env python3
"""Verify a pinned, complete local core snapshot without Git or network access."""
# SPDX-License-Identifier: GPL-3.0-or-later
import hashlib, json, pathlib, re, sys
root = pathlib.Path(__file__).resolve().parent.parent
lock_path = root / 'vendor/core.lock.json'
if not lock_path.is_file():
    raise SystemExit('Core is not pinned yet. Run tools/sync-core.py with an immutable published core commit.')
lock = json.loads(lock_path.read_text())
if lock.get('schema') != 1 or lock.get('repository') != 'https://github.com/athemeroy/oritwig-core.git' or not re.fullmatch('[0-9a-f]{40}', lock.get('commit', '')) or not re.fullmatch('[0-9a-f]{40}', lock.get('source_tree', '')):
    raise SystemExit('Invalid or floating core source lock')
expected = set()
for item in lock.get('files', []):
    name = item.get('path', '')
    path = pathlib.PurePosixPath(name)
    if not name.startswith('shared/core/') or '..' in path.parts or '\\' in name or name in expected:
        raise SystemExit('Unsafe or duplicate vendor path')
    expected.add(name)
    file = root / name
    if file.is_symlink() or not file.is_file() or not file.resolve().is_relative_to((root/'shared/core').resolve()):
        raise SystemExit('Missing or unsafe shared source: '+name)
    if hashlib.sha256(file.read_bytes()).hexdigest() != item.get('sha256'):
        raise SystemExit('Vendored core differs from its immutable pin: '+name)
nodes = list((root/'shared/core').rglob('*'))
if any(p.is_symlink() for p in nodes):
    raise SystemExit('Symbolic links are not permitted in the vendored source')
actual = {p.relative_to(root).as_posix() for p in nodes if p.is_file() and p.relative_to(root/'shared/core').parts[0] not in ('build', '.gradle')}
if not expected or actual != expected:
    raise SystemExit('Missing or unexpected shared source files: '+str(actual ^ expected))
for item in lock.get('notices', []):
    name = item.get('path', '')
    if not (name.startswith('third_party/') or name.startswith('vendor/core-')) or '..' in pathlib.PurePosixPath(name).parts or '\\' in name:
        raise SystemExit('Unsafe provenance path')
    file = root / name
    if file.is_symlink() or not file.resolve().is_relative_to(root.resolve()) or not file.is_file() or hashlib.sha256(file.read_bytes()).hexdigest() != item['sha256']:
        raise SystemExit('Core provenance notice changed: '+item['path'])
app = lock.get('consumer')
if app not in ('poster', 'journal'):
    raise SystemExit('Unknown standalone application in core lock')
asset=root/'apps'/app/'src/main/assets/oritwig/core-source.json'
if not asset.is_file() or asset.read_bytes()!=lock_path.read_bytes():
    raise SystemExit('APK core-source metadata is missing or differs from the lock')
print(f'Verified {len(expected)} vendored core files at immutable commit {lock["commit"]}')
