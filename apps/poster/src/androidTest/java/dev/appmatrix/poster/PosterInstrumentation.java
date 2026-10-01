/* SPDX-License-Identifier: GPL-3.0-or-later */
package dev.appmatrix.poster;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import dev.appmatrix.core.BitmapTransforms;
import dev.appmatrix.core.MediaFiles;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.UUID;

/**
 * Framework-only integration tests, deliberately isolated from the user's actual project library.
 */
public final class PosterInstrumentation extends Instrumentation {
  private int passed;

  @Override
  public void onCreate(Bundle arguments) {
    super.onCreate(arguments);
    start();
  }

  @Override
  public void onStart() {
    Bundle result = new Bundle();
    File testRoot = null;
    try {
      testRoot =
          new File(getTargetContext().getCacheDir(), "poster-instrumentation-" + UUID.randomUUID());
      if (!testRoot.mkdirs()) throw new AssertionError("Cannot create isolated test directory");
      final File isolated = testRoot;
      Context context =
          new ContextWrapper(getTargetContext()) {
            @Override
            public File getFilesDir() {
              return isolated;
            }
          };
      ProjectStore store = new ProjectStore(context);
      check(
          MainActivity.isSystemDocumentUri(
              Uri.parse("content://com.android.providers.media.documents/document/image%3A1")),
          "accept provider-backed system document URI");
      check(
          !MainActivity.isSystemDocumentUri(Uri.fromFile(new File(testRoot, "private.json"))),
          "reject private file URI returned by replacement picker");
      check(
          !MainActivity.isSystemDocumentUri(Uri.parse("content:///missing-provider")),
          "reject content URI without provider");
      PosterProject project = new PosterProject();
      project.source = UUID.randomUUID().toString();
      project.title = "Instrumentation fixture";
      Bitmap fixture = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888);
      for (int y = 0; y < fixture.getHeight(); y++)
        for (int x = 0; x < fixture.getWidth(); x++)
          fixture.setPixel(x, y, Color.rgb(x % 256, y % 256, (x + y) % 256));
      try (FileOutputStream out = new FileOutputStream(store.source(project.source))) {
        BitmapTransforms.exportPng(fixture, out);
      }
      fixture.recycle();
      check(store.list().projects.isEmpty(), "fresh library empty");
      project.turns = 1;
      project.crop = "portrait";
      project.filter = "warm";
      project.caption = "HELLO";
      PosterProject.setLevel(project.curves.luminanceCurve, 2, 70);
      store.save(project);
      store.draft(project, true);
      ProjectStore reopened = new ProjectStore(context);
      check(reopened.list().projects.size() == 1, "save and reopen one project");
      PosterProject restored = reopened.list().projects.get(0);
      check(
          restored.toJson().toString().equals(project.toJson().toString()),
          "complete recipe persists");
      check(reopened.draft().getBoolean("dirty"), "dirty draft persists");
      Bitmap rendered = PosterRenderer.render(reopened, restored, 2048);
      check(
          rendered.getWidth() == 240 && rendered.getHeight() == 300,
          "rotation and 4:5 crop produce exact dimensions");
      PosterProject withoutCurves = restored.copy();
      withoutCurves.curves = new dev.appmatrix.core.ToneCurves.CurvesToolValue();
      Bitmap natural = PosterRenderer.render(reopened, withoutCurves, 2048);
      check(!rendered.sameAs(natural), "shared curves change actual rendered pixels");
      natural.recycle();
      Bitmap smallPreview = PosterRenderer.render(reopened, restored, 128);
      Bitmap expectedPreview = BitmapTransforms.resize(rendered, 128);
      check(
          smallPreview.sameAs(expectedPreview),
          "preview keeps exact export composition and caption wrapping");
      smallPreview.recycle();
      expectedPreview.recycle();
      Bitmap again = PosterRenderer.render(reopened, restored, 2048);
      check(rendered.sameAs(again), "repeated rendering is deterministic and non-destructive");
      again.recycle();
      Bitmap original = MediaFiles.loadBitmap(reopened.source(restored.source).getPath());
      check(
          original.getWidth() == 320 && original.getHeight() == 240, "source dimensions unchanged");
      check(original.getPixel(100, 50) == Color.rgb(100, 50, 150), "source pixels unchanged");
      original.recycle();
      File export = new File(testRoot, "actual-export.png");
      MediaFiles.copyPngTo(getTargetContext(), Uri.fromFile(export), rendered);
      rendered.recycle();
      byte[] header = new byte[8];
      try (FileInputStream in = new FileInputStream(export)) {
        check(in.read(header) == 8, "export has header");
      }
      check(
          header[0] == (byte) 137 && header[1] == 'P' && header[2] == 'N' && header[3] == 'G',
          "export is an actual PNG");
      Bitmap decoded = BitmapFactory.decodeFile(export.getPath());
      check(
          decoded != null && decoded.getWidth() == 240 && decoded.getHeight() == 300,
          "export decodes independently");
      decoded.recycle();
      File malformed = new File(testRoot, "not-an-image.png");
      try (FileOutputStream out = new FileOutputStream(malformed)) {
        out.write("not an image".getBytes(StandardCharsets.UTF_8));
      }
      boolean badImportRejected = false;
      try {
        MediaFiles.readBitmap(context, Uri.fromFile(malformed), 2048);
      } catch (java.io.IOException expected) {
        badImportRejected = true;
      }
      check(
          badImportRejected && reopened.list().projects.size() == 1,
          "malformed import does not change the library");
      Bitmap wide = Bitmap.createBitmap(2300, 100, Bitmap.Config.ARGB_8888);
      File wideSource = new File(testRoot, "wide.png");
      try (FileOutputStream out = new FileOutputStream(wideSource)) {
        BitmapTransforms.exportPng(wide, out);
      }
      wide.recycle();
      Bitmap bounded = MediaFiles.readBitmap(context, Uri.fromFile(wideSource), 2048);
      check(
          Math.max(bounded.getWidth(), bounded.getHeight()) <= 2048,
          "import dimensions are bounded");
      bounded.recycle();
      PosterProject invalid = project.copy();
      invalid.id = UUID.randomUUID().toString();
      invalid.source = UUID.randomUUID().toString();
      boolean rejected = false;
      try {
        reopened.save(invalid);
      } catch (java.io.IOException expected) {
        rejected = true;
      }
      check(
          rejected && reopened.list().projects.size() == 1,
          "failed save preserves existing projects");
      File recoverable = new File(reopened.root, project.id + ".json");
      check(
          recoverable.renameTo(new File(recoverable.getPath() + ".bak")),
          "stage recoverable AtomicFile backup");
      check(
          reopened.list().projects.size() == 1 && recoverable.isFile(),
          "library restores a legacy atomic backup before cleanup");
      File damaged = new File(reopened.root, UUID.randomUUID() + ".json");
      try (FileOutputStream out = new FileOutputStream(damaged)) {
        out.write("not json".getBytes(StandardCharsets.UTF_8));
      }
      check(
          reopened.list().unreadable == 1 && reopened.list().projects.size() == 1,
          "corrupt metadata does not hide healthy projects");
      reopened.removeUnreferencedSource(project);
      check(reopened.source(project.source).exists(), "saved source is not garbage-collected");
      reopened.clearDraft();
      check(reopened.draft() == null, "draft can be discarded");
      reopened.delete(project);
      check(
          reopened.list().projects.isEmpty() && reopened.source(project.source).exists(),
          "delete preserves source when another unreadable record may depend on it");
      reopened.removeUnreferencedSource(project);
      check(
          reopened.source(project.source).exists(),
          "cleanup preserves source on uncertain library state");
      check(damaged.delete(), "remove isolated damaged fixture");
      File mismatched = new File(reopened.root, UUID.randomUUID() + ".json");
      try (FileOutputStream out = new FileOutputStream(mismatched)) {
        out.write(project.toJson().toString().getBytes(StandardCharsets.UTF_8));
      }
      check(
          reopened.list().unreadable == 1 && reopened.list().projects.isEmpty(),
          "mismatched record filename is not trusted");
      check(mismatched.delete(), "remove isolated mismatched fixture");
      reopened.removeUnreferencedSource(project);
      check(
          !reopened.source(project.source).exists(),
          "cleanup removes only known-unreferenced private image");
      Activity activity =
          startActivitySync(
              new Intent(getTargetContext(), MainActivity.class)
                  .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      waitForIdleSync();
      final Throwable[] accessibilityFailure = {null};
      runOnMainSync(
          () -> {
            try {
              ArrayList<View> matches = new ArrayList<>();
              activity
                  .getWindow()
                  .getDecorView()
                  .findViewsWithText(matches, "Pocket Poster", View.FIND_VIEWS_WITH_TEXT);
              check(!matches.isEmpty(), "independent launcher displays Pocket Poster");
              passed += PosterAccessibilityInstrumentationChecks.run((MainActivity) activity);
            } catch (Throwable error) {
              accessibilityFailure[0] = error;
            } finally {
              activity.finish();
            }
          });
      if (accessibilityFailure[0] != null)
        throw new AssertionError("Poster accessibility semantics failed", accessibilityFailure[0]);
      result.putString(
          "stream", "\nPocket Poster: " + passed + " integration assertions passed.\n");
      result.putInt("passed", passed);
      finish(Activity.RESULT_OK, result);
    } catch (Throwable error) {
      result.putString(
          "stream",
          "\nPocket Poster failed after "
              + passed
              + " assertions: "
              + error
              + "\n"
              + android.util.Log.getStackTraceString(error));
      finish(Activity.RESULT_CANCELED, result);
    } finally {
      if (testRoot != null) removeTree(testRoot);
    }
  }

  private void check(boolean condition, String name) {
    if (!condition) throw new AssertionError(name);
    passed++;
  }

  private void removeTree(File file) {
    if (file.isDirectory()) {
      File[] children = file.listFiles();
      if (children != null) for (File child : children) removeTree(child);
    }
    file.delete();
  }
}
