# Pocket Poster

An independent, offline Android poster editor. No Telegram app, Telegram account,
network permission, paid service or API key is required. Pocket Poster has its own
launcher identity, app-private storage and installable APK.

## A complete workflow

1. Import an image using the Android Files picker. Supported Android image formats
   are bounded to 25 MB / 40 megapixels before decoding. The editor keeps an
   upright, lossless private PNG with a longest edge of at most 2048 pixels.
   Original files and their provider are never modified. GPS/EXIF metadata is not
   carried into the private copy or export.
2. Rotate in 90° steps, choose a centered original/square/4:5/16:9 crop, apply an
   original/monochrome/warm/cool look, and add an optional bottom caption.
3. Adjust luminance or RGB tone curves using the actual Telegram-derived graph.
   Five labeled, keyboard/accessibility-adjustable sliders provide an alternative
   to gestures. Curve edits are staged until Apply; Cancel leaves the recipe
   unchanged. Reset channel restores that channel's five default control points.
4. Name and save the editable project. The private source and complete recipe are
   persisted independently. Repeated rendering always starts from the source;
   applying a look or reopening never compounds edits. The current session also
   has an atomic local draft, including unsaved changes, restored after restart.
5. Export a real lossless PNG through Android's create-document picker. Settings
   persist a 1024- or 2048-pixel maximum edge. Smaller images are not enlarged.
6. Open saved projects from the library, explicitly reset image edits, or confirm
   deletion of an individual project. Original photographs and prior exports are
   not removed. Leaving an edited project offers Save / Discard / Keep editing.

Settings also include device/light/dark appearance, privacy information, About,
source attribution, and the bundled open-source license texts. The original
adaptive launcher artwork is drawn in Android vector resources.

## Build and install

From the repository root, after following `docs/BUILDING.md`:

```sh
./tools/build-app.sh poster
adb install -r apps/poster/build/outputs/apk/debug/poster-debug.apk
adb shell am start -n dev.appmatrix.poster/.MainActivity
```

Minimum Android: API 26. Target/compile SDK: 35. Java 17 source compatibility.
There are no runtime UI libraries or external service dependencies.

## Checks

```sh
./gradlew :apps:poster:testDebugUnitTest :apps:poster:lintDebug
./gradlew :apps:poster:assembleDebugAndroidTest
adb install -r apps/poster/build/outputs/apk/androidTest/debug/poster-debug-androidTest.apk
adb shell am instrument -w dev.appmatrix.poster.test/dev.appmatrix.poster.PosterInstrumentation
```

The unit suite covers metadata round-tripping, independent curve copies, cache
invalidation, normalization, text bounds, schema/options validation, path
traversal rejection, and malformed/non-finite curve data.

The native integration runner uses a separate cache-backed store, not the user's
saved library. It checks project/draft persistence across store recreation,
non-destructive/deterministic rendering, actual PNG signature and independent
decoding, exact rotated/cropped output dimensions, invalid saves, damaged metadata,
source retention/deletion, and an independent launcher smoke test. It does not
claim automated coverage of the system document picker or force-stop recovery.

At initial implementation handoff: debug APK compilation passed. Test suite,
lint, emulator interaction and release-build results are tracked separately in
repository QA/release evidence. Do not interpret source-level tests as complete
visual or device certification. The build worker owns the final build results.

## Data and practical limits

- Each project is an atomic JSON record referencing an immutable private PNG;
  there is no shared index whose failure could hide every healthy project
- Import/decode/render/export runs on a single background worker with bounded
  bitmap dimensions; preview work is debounced and stale results are ignored
- Configuration recreation retains in-flight operations and current edits;
  process restart reopens the last durably saved draft
- Imported unsupported, oversized, unreadable or malformed files leave the
  previous project intact and report a recoverable error
- Invalid or newer project records are retained and reported rather than erased
- Crops are centered presets, not a draggable freeform crop
- Very long captions may not fit a small or wide image; the preview explains the
  problem and export stays blocked until it is corrected
- Uninstall removes all private projects; exported PNGs contain finished pixels,
  not an editable-project backup
- Export providers can fail or leave a partial destination. The app reports the
  failure and retains the editable project so export can be retried

## Provenance

App-specific code and icon are original GPL-3.0-or-later. The shared curve control,
curve model and CPU curve rendering are audited Telegram-derived code. See
`third_party/TELEGRAM.md`, `NOTICE` and the root `LICENSE`. This independent product
is not affiliated with or endorsed by Telegram.
