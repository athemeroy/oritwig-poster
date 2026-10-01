# Oritwig Poster · Pocket Poster

> **Superseded development preview.** This repository is preserved for source history and is not part of the current upstream-first Oritwig product releases. See the [current catalog](https://github.com/athemeroy/app-matrix).

An earlier independent Android development preview to compose local images, retain editable projects, and export PNG. Part of [Oritwig · 源枝](https://github.com/athemeroy/app-matrix).

> Development preview. Build/unit checks and native/UI acceptance are reported separately by CI. Debug APKs are testing builds; unsigned release APKs require the distributor's own signing key.

## Independent by construction

This repository contains only this product and an exact vendored [Oritwig Core](https://github.com/athemeroy/oritwig-core) source snapshot. It does not require the other app, a sibling checkout, Telegram, a server, account, API key or paid model service. The source lock uses an immutable commit plus SHA-256 per file; no build tracks a moving branch.

The Android application ID remains `dev.appmatrix.poster` so existing development installs keep their separate private data. The launcher name is Pocket Poster. See [the product guide](apps/poster/README.md) for full workflows, limits and privacy details.

## Build, install and test

Requirements: JDK 21 (pinned and tested), Android SDK/API 35, build tools 35.0.0, and Python 3.9+ for source-lock verification. Java source compatibility is 17. The checked-in wrapper pins Gradle 8.11.1 and AGP 8.9.3. The optional setup scripts download official verified toolchains; SDK license acceptance is explicit.

```sh
export JAVA_HOME=/path/to/jdk
export ANDROID_HOME=/path/to/android-sdk
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
./tools/build-app.sh poster
adb install -r apps/poster/build/outputs/apk/debug/poster-debug.apk
adb shell am start -n dev.appmatrix.poster/.MainActivity
```

`build-app.sh` first verifies the complete pinned core, then builds debug and unsigned release APKs, runs lint, runs real shared/app tests, and validates package identities, signatures and license assets. A clean Git commit can be packaged with `./tools/package-artifacts.sh poster`; this rebuilds and includes the exact complete corresponding source.

For native framework assertions on a dedicated test emulator:

```sh
./gradlew :apps:poster:assembleDebugAndroidTest
./tools/run-device-tests.sh emulator-5554 poster
```

Never run state-changing UI plans against a device with real user data. The optional tools/ui helpers require the dedicated emulator and use synthetic fixtures. Compiling a test APK is not a device-test pass.

## Source reuse and updates

- Telegram: real five-point curve models, interactive graph and attributed CPU processing
- Markor: bounded, session-only text undo/redo in the shared library; Journal uses it in its notes editor
- Original product: navigation, persistence, document-picker flows, settings and end-to-end workflow

Exact original notices and adaptations are in [Telegram provenance](third_party/TELEGRAM.md) and [Markor provenance](third_party/MARKOR.md). The core lock and full source are in `vendor/core.lock.json` and `shared/core`. To adopt a reviewed core update, run `./tools/sync-core.py <40-character-core-commit>`, review the diff, then commit source, lock and notices together.

## Privacy and license

No network/storage permission, hidden upload, analytics or sign-in. System-picked exports may go to a cloud provider chosen by the user. Local deletion cannot retract exports or backups, and uninstalling removes app-private data. Images are normalized without the original EXIF/GPS. Journal backups are readable, unencrypted archives.

The combined app is [GPL-3.0-or-later](LICENSE), retaining original GPL-2.0-or-later Telegram notices and Markor public-domain/selected-Unlicense notices. [NOTICE](NOTICE) and the exact core notices travel with source and APKs. There is no affiliation with or endorsement by Telegram or Markor.
