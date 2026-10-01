# Oritwig Core · 源枝

Reusable, account-free Android capabilities extracted from mature open-source applications, with exact source provenance and focused tests. This is a library, not one of the standalone apps.

The independent applications are [Oritwig Poster](https://github.com/athemeroy/oritwig-poster) and [Oritwig Journal](https://github.com/athemeroy/oritwig-journal). Each app carries a pinned copy of the complete shared source and builds without a sibling checkout, Telegram account, API key or runtime server.

## Capabilities

- Telegram-derived five-point luminance/RGB curves: curve interpolation, interactive graph and an attributed HSL/RGB CPU adapter
- Markor-derived text editing: bounded in-memory undo/redo, replacement coalescing, cursor/selection recovery and lifecycle-safe Android TextWatcher integration
- Original bounded image import/orientation, non-destructive bitmap transformations, exports, appearance and license UI

`dev.appmatrix.core` remains the media namespace for source compatibility. Generic text editing is exposed as `dev.appmatrix.core.text.TextUndoController`; the small `EditHistory` implementation stays internal. No product navigation, journal store, poster schema or account state belongs here.

## Build and tests

Use JDK 17+ (the helper pins Temurin 21), Android SDK 35 and build tools 35.0.0. Read and accept Google's SDK terms yourself before using the explicit `--accept-sdk-license` setup option.

```sh
./tools/setup-jdk.sh /path/to/jdk
export JAVA_HOME=/path/to/jdk
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME="$HOME/Android/Sdk"
./tools/setup-android-sdk.sh --accept-sdk-license
./tools/build-core.sh
```

The AAR is `shared/core/build/outputs/aar/core-release.aar`. JVM tests cover exact Telegram interpolation/sample vectors, CPU lookup behavior and 28 original editing-history cases. Device-level gesture, bitmap and EditText checks run in the independent app repositories. Building an AAR or passing a JVM suite does not establish full device acceptance.

## Consuming the core

The first apps vendor the exact `shared/core` source at an immutable commit, record SHA-256 for every file, and verify the lock before each build. Their complete-source archives contain that source and the build inputs; cloning or downloading an app repository is sufficient. Updating the core is an explicit, reviewable source change, never a floating `main` dependency.

The UI host sets the application metadata key `dev.oritwig.source_url` to the repository with its complete corresponding application source. The shared About screen uses that URL and provides source attribution and full license text offline.

## Attribution and licenses

- [Telegram provenance](third_party/TELEGRAM.md): original GPL-2.0-or-later notices are preserved; CPU lookup sampling is deliberately not claimed bit-identical to Telegram's GPU output
- [Markor provenance](third_party/MARKOR.md): public-domain history/controller plus the selected Unlicense option for Gregor Santner's helper; generic relocation changes names and package boundaries, not behavior
- Original Oritwig contributions and the combined library use [GPL-3.0-or-later](LICENSE); see [NOTICE](NOTICE)

There is no Telegram or Markor branding, account integration, endorsement, telemetry or network permission. The library's licenses also apply when it is combined into another app; distribute matching complete corresponding source with binary releases.
