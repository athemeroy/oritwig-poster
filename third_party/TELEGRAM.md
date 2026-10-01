# Telegram Android source attribution

App Matrix reuses a narrow, account-free photo-adjustment capability from [Telegram for Android](https://github.com/DrKLO/Telegram). The apps do not contain Telegram's account system, messaging service, brand assets, or complete photo editor. This is source reuse, not Telegram service integration or endorsement.

Upstream revision: `f2908b14133bbffbf7ab04f641ecb5bfaf533242`.

## Included source and modifications

### ToneCurves.java

- Local: `shared/core/src/main/java/dev/appmatrix/core/ToneCurves.java`
- [Upstream PhotoFilterView.java](https://github.com/DrKLO/Telegram/blob/f2908b14133bbffbf7ab04f641ecb5bfaf533242/TMessagesProj/src/main/java/org/telegram/ui/Components/PhotoFilterView.java)
- Upstream Git blob: `0ad67a8ac554984c5217415e0898e7f7d9af650c`
- Reused portions: `CurvesValue`, `CurvesToolValue`, `curveGranularity`, `curveDataStep`
- Original notice retained: Copyright Nikolai Kudashov, 2013–2018; GNU GPL version 2 or later
- Modified 2026-10-01: isolated the nested models in a standalone Java class/package; removed Telegram protocol serialization; retained interpolation, default values, sample cache, and packed 200-sample curve-buffer implementation

### PhotoFilterCurvesControl.java

- Local: `shared/core/src/main/java/dev/appmatrix/core/PhotoFilterCurvesControl.java`
- [Upstream PhotoFilterCurvesControl.java](https://github.com/DrKLO/Telegram/blob/f2908b14133bbffbf7ab04f641ecb5bfaf533242/TMessagesProj/src/main/java/org/telegram/ui/Components/PhotoFilterCurvesControl.java)
- Upstream Git blob: `9eb92ad89a2e8b28ccd75b4239d875f3ae2aab11`
- Reused portion: interactive curve control and rendering
- Original notice retained: Copyright Nikolai Kudashov, 2013–2018; GNU GPL version 2 or later
- Modified 2026-10-01: changed package/model references; replaced `AndroidUtilities.dp` and `RectOld` with local helpers; added automatic layout bounds, accessibility description/click handling and scroll-parent touch coordination; clamped edge segment selection; made drag speed symmetric; invalidated cached data before callbacks; removed the continuous redraw loop

### CurveMap.java

- Local: `shared/core/src/main/java/dev/appmatrix/core/CurveMap.java`
- [Upstream FilterShaders.java](https://github.com/DrKLO/Telegram/blob/f2908b14133bbffbf7ab04f641ecb5bfaf533242/TMessagesProj/src/main/java/org/telegram/ui/Components/FilterShaders.java)
- Upstream Git blob: `8e1f5cdf23b5b1f4a429f25124d06e7e7e7a73bc`
- Reused portions: shader `rgbToHsl`, `hueToRgb`, `hslToRgb`, `applyLuminanceCurve`, `applyRGBCurve`, and their application order
- This upstream file has no per-file copyright header at the pinned revision; it is covered by the Telegram repository license. No new upstream author attribution is inferred
- Modified 2026-10-01: ported GLSL to Java CPU processing; immutable per-operation lookup tables; input validation, preserved alpha, explicit cache refresh, and exact default bypass
- Intentional difference: deterministic nearest-index 200-entry CPU lookup replaces OpenGL texture sampling. HSL luminance, highlight/shadow saturation attenuation and subsequent RGB order are retained. Output is not claimed bit-identical to Telegram's GPU rendering

## License and corresponding source

The upstream code is GNU GPL version 2 or later. [The full upstream GPLv2 license](licenses/Telegram-GPL-2.0.txt) is included. The combined App Matrix programs are distributed under GPL-3.0-or-later using the upstream later-version option; [the full GPLv3 text](../LICENSE) and original notices are included. These notices also ship inside each app through the shared About/licenses screen.

When distributing an APK, provide the matching complete App Matrix source, dependency/build definitions, and build instructions from the same source revision, including these adapted files. A pointer only to the unmodified Telegram repository is not the corresponding source of these apps. The build/release workflow bundles the matching source with its outputs.

## Original App Matrix code

Product navigation, local project/journal storage, backup/restore, bounded image import, simple named filters, crop/rotation, caption composition, settings, exports, and the adjustment dialog surrounding the extracted control are new App Matrix implementations. They are not claimed as extractions. No Telegram icons, fonts, images, account identifiers, API credentials or network service are included.
