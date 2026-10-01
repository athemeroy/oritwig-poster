# Visible Android UI acceptance plans

These first-party tools exercise a dedicated disposable AVD through Android's
real UI hierarchy. They resolve each tap against a fresh `uiautomator` dump and
save PNG screenshots, XML hierarchy, and a step log. They stop at an ambiguous or
missing selector. Prepared plans are not proof of a passing runtime test.

The driver requires an emulator serial, `ro.kernel.qemu=1`, completed boot, and an
exact dedicated AVD name (default `app-matrix-ci`). It only starts/stops these two
app packages. Do not use an emulator containing personal data. Use a newly
created AVD and synthetic fixtures; the plans write observations and exports.

## Run after successful native integration tests

Install `requirements.txt` in a disposable Python virtual environment. Fixture
generation and raster inspection use Pillow; the driver uses only the standard
library. Set `ANDROID_HOME` and ensure the app APKs are installed.

```sh
python3 tools/ui/generate_fixtures.py artifacts/runtime/fixtures
"$ANDROID_HOME/platform-tools/adb" -s emulator-5554 push artifacts/runtime/fixtures/. /sdcard/Download/
python3 tools/ui/adb_ui_driver.py tools/ui/poster-picker-smoke.plan.json --out artifacts/runtime/poster-ui
python3 tools/ui/adb_ui_driver.py tools/ui/poster-image-ui.plan.json --out artifacts/runtime/poster-ui
python3 tools/ui/adb_ui_driver.py tools/ui/poster-exif-limits-ui.plan.json --out artifacts/runtime/poster-ui
python3 tools/ui/adb_ui_driver.py tools/ui/journal-text-ui.plan.json --out artifacts/runtime/journal-ui
python3 tools/ui/adb_ui_driver.py tools/ui/journal-photo-backup-ui.plan.json --out artifacts/runtime/journal-ui
```

Run the Journal text plan before its photo/backup plan: the latter expects the
synthetic entries created by the former. The Poster image plan covers actual SAF
import/export, cancel/apply curves, and save/force-stop/reopen equivalence. The
Journal plans cover actual EditText undo/redo, dirty recovery, JPEG adjustment,
backup preview cancellation, and confirmed restore. All Poster plans must use the same output folder, and both Journal plans must
share their output folder. Capture sequence numbers continue across plans.
Verify required exported pixels, metadata, and backup checksums with:

```sh
python3 tools/ui/verify_export_pixels.py --poster-dir artifacts/runtime/poster-ui --journal-dir artifacts/runtime/journal-ui --fixtures artifacts/runtime/fixtures
```

For one app repository provide only its output-directory argument. A missing
required export fails validation. `inspect_exports.py` also provides independent
image/ZIP details. Review the screenshots separately.

UI text and DocumentsUI vary by API/locale. Use the English AOSP API 35 test image
and update selectors only from observed captured hierarchy, never to hide a
product failure. The malformed/oversized/EXIF plan adds import-limit and orientation checks;
generating its fixtures does not mean those cases were executed. Physical
devices, landscape, font scaling, and the full manual acceptance checklist remain
separate checks.
