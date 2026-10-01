/* SPDX-License-Identifier: GPL-3.0-or-later */
package dev.appmatrix.poster;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.util.AtomicFile;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import dev.appmatrix.core.BitmapTransforms;
import dev.appmatrix.core.MediaFiles;
import dev.appmatrix.core.PhotoFilterCurvesControl;
import dev.appmatrix.core.Settings;
import dev.appmatrix.core.Theme;
import dev.appmatrix.core.ToneCurves;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.DateFormat;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/** Offline poster editor. Every preview and export is rebuilt from a private source + recipe. */
public final class MainActivity extends Activity {
  private static final int IMPORT_IMAGE = 101, EXPORT_IMAGE = 102;
  private static final int SOURCE_EDGE = 2048, PREVIEW_EDGE = 1000;
  private Session session;
  private LinearLayout root, body;
  private ImageView preview;
  private TextView status, busyLabel, recipeLabel;
  private ProgressBar progress;
  private int ink, muted, surface;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final Runnable persist = () -> persistDraft(false);
  private final Runnable updatePreview = () -> renderPreview();

  /** Retains in-flight imports/exports and edits through configuration recreation. */
  private static final class Session {
    final ExecutorService io = Executors.newSingleThreadExecutor();
    final Handler main = new Handler(Looper.getMainLooper());
    ProjectStore store;
    MainActivity owner;
    PosterProject current, pendingExport;
    boolean dirty, closed, previewRendering;
    Bitmap preview;
    String previewError = "", draftError = "", busy = "", notice = "";
    long previewTicket;
  }

  private interface Work<T> {
    T run() throws Exception;
  }

  private interface Result<T> {
    void accept(Session s, T value);
  }

  @Override
  public void onCreate(Bundle state) {
    Theme.apply(this);
    super.onCreate(state);
    Object retained = getLastNonConfigurationInstance();
    session = retained instanceof Session ? (Session) retained : new Session();
    session.owner = this;
    try {
      if (session.store == null) {
        session.store = new ProjectStore(getApplicationContext());
        JSONObject draft = session.store.draft();
        if (draft != null) {
          session.current = PosterProject.fromJson(draft);
          session.dirty = draft.optBoolean("dirty", true);
          if (!session.store.source(session.current.source).isFile())
            throw new IOException(
                "The draft’s image is missing. Your saved library is still available.");
          session.notice = "Restored your last editing session";
        }
        if (state != null && state.containsKey("pendingExport")) {
          session.pendingExport =
              PosterProject.fromJson(new JSONObject(state.getString("pendingExport")));
        }
      }
    } catch (Exception e) {
      session.current = null;
      session.notice = "Couldn’t restore the editing session: " + message(e);
    }
    palette();
    showScreen();
    if (session.current != null
        && (session.preview == null || session.previewRendering)
        && session.busy.isEmpty()) renderPreview();
  }

  @Override
  public Object onRetainNonConfigurationInstance() {
    return session;
  }

  @Override
  protected void onSaveInstanceState(Bundle out) {
    persistDraft(false);
    try {
      if (session.pendingExport != null)
        out.putString("pendingExport", session.pendingExport.toJson().toString());
    } catch (Exception ignored) {
    }
    super.onSaveInstanceState(out);
  }

  @Override
  protected void onPause() {
    persistDraft(false);
    super.onPause();
  }

  @Override
  protected void onDestroy() {
    main.removeCallbacks(persist);
    main.removeCallbacks(updatePreview);
    if (session != null && session.owner == this) session.owner = null;
    if (session != null && !isChangingConfigurations()) {
      session.closed = true;
      session.previewTicket++;
      session.io.shutdownNow();
      // ImageView rendering can outlive this callback; let Android reclaim its bitmap.
      session.preview = null;
    }
    super.onDestroy();
  }

  private void palette() {
    boolean dark =
        (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
            == Configuration.UI_MODE_NIGHT_YES;
    String selected = Settings.readTheme(this);
    if ("dark".equals(selected)) dark = true;
    else if ("light".equals(selected)) dark = false;
    ink = dark ? 0xffe5efea : 0xff172c28;
    muted = dark ? 0xffa7bdb6 : 0xff536b64;
    surface = dark ? 0xff14231f : 0xfff3f7f4;
  }

  private void showScreen() {
    root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setBackgroundColor(surface);
    root.setPadding(dp(18), dp(12), dp(18), dp(12));
    root.setOnApplyWindowInsetsListener(
        (v, insets) -> {
          v.setPadding(
              dp(18) + insets.getSystemWindowInsetLeft(),
              dp(12) + insets.getSystemWindowInsetTop(),
              dp(18) + insets.getSystemWindowInsetRight(),
              dp(12) + insets.getSystemWindowInsetBottom());
          return insets;
        });
    LinearLayout header = row();
    TextView brand = text("Pocket Poster", 24, true);
    header.addView(brand, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
    Button settings = button("Settings", this::showSettings);
    header.addView(settings);
    root.addView(header);
    busyLabel = text("", 14, false);
    root.addView(busyLabel);
    progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
    progress.setIndeterminate(true);
    root.addView(progress, new LinearLayout.LayoutParams(-1, dp(3)));
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    body = new LinearLayout(this);
    body.setOrientation(LinearLayout.VERTICAL);
    body.setPadding(0, dp(12), 0, dp(24));
    scroll.addView(body);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    preview = null;
    status = null;
    recipeLabel = null;
    setContentView(root);
    Theme.style(this, root);
    root.requestApplyInsets();
    if (session.store == null) {
      body.addView(text("Private storage is unavailable", 24, true));
      body.addView(
          text(
              "Free some device storage and reopen Pocket Poster. No source files were changed.",
              16,
              false));
    } else if (session.current == null) showLibrary();
    else showEditor();
    updateBusy();
    if (!session.notice.isEmpty()) {
      TextView notice = text(session.notice, 14, false);
      notice.setPadding(0, dp(8), 0, dp(8));
      body.addView(notice, 0);
      session.notice = "";
    }
  }

  private void showLibrary() {
    body.addView(text("Make something worth sharing", 30, true));
    body.addView(
        text("Your photos. Your curves. A finished poster, all on your device.", 16, false));
    body.addView(space(14));
    body.addView(button("＋  Import an image", this::pickImage));
    body.addView(
        text(
            "Choose a photo from Files. We keep a private copy up to 2048 px and never change the"
                + " original.",
            13,
            false));
    body.addView(space(22));
    body.addView(text("Your projects", 22, true));
    try {
      ProjectStore.Listing listing = session.store.list();
      if (listing.projects.isEmpty()) {
        body.addView(text("A little inspiration starts here", 20, true));
        body.addView(
            text(
                "Import an image, shape its light with curves, add a caption and export a PNG."
                    + " Saved projects stay editable.",
                16,
                false));
      }
      if (listing.unreadable > 0)
        body.addView(
            text(
                listing.unreadable
                    + " damaged or newer-format project(s) could not be opened. They have been kept"
                    + " in private storage.",
                14,
                false));
      for (PosterProject project : listing.projects) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(8), dp(12), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(surface);
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), muted);
        card.setBackground(bg);
        card.addView(text(project.title, 20, true));
        card.addView(
            text(
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                        .format(new Date(project.updated))
                    + " · "
                    + cropName(project.crop),
                13,
                false));
        if (!project.caption.isEmpty())
          card.addView(text(PosterProject.limit(project.caption, 90), 14, false));
        LinearLayout actions = row();
        addEqual(actions, button("Open", () -> openProject(project)));
        addEqual(actions, button("Delete", () -> deleteProject(project)));
        card.addView(actions);
        body.addView(space(12));
        body.addView(card);
      }
    } catch (IOException e) {
      body.addView(text("Couldn’t read projects: " + message(e), 16, false));
      body.addView(button("Try again", this::showScreen));
    }
    body.addView(space(20));
    body.addView(text("Private by design · No account · Works offline", 13, false));
  }

  private void showEditor() {
    PosterProject p = session.current;
    LinearLayout toolbar = row();
    addEqual(toolbar, button("‹ Projects", this::leaveEditor));
    addEqual(toolbar, button("Save project", this::saveProject));
    addEqual(toolbar, button("Export PNG", this::startExport));
    body.addView(toolbar);
    status =
        text(
            session.dirty
                ? "Unsaved changes · Draft kept on this device"
                : "Saved project · Changes stay editable",
            13,
            false);
    body.addView(status);
    preview = new ImageView(this);
    preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
    preview.setAdjustViewBounds(true);
    preview.setBackgroundColor(0xff162620);
    preview.setContentDescription("Poster preview. This is the image that will be exported.");
    preview.setImageBitmap(session.preview);
    body.addView(preview, new LinearLayout.LayoutParams(-1, dp(290)));
    recipeLabel = text(recipeSummary(), 13, false);
    body.addView(recipeLabel);
    if (!session.previewError.isEmpty()) status.setText(session.previewError);
    if (!session.draftError.isEmpty()) status.setText(session.draftError);
    body.addView(space(10));
    EditText name = edit("Name this poster", p.title, 100, false);
    body.addView(fieldLabel("Project name", 14, name));
    body.addView(name);
    name.addTextChangedListener(
        watcher(
            value -> {
              session.current.title = value.trim().isEmpty() ? "Untitled poster" : value;
              changed(false);
            }));
    body.addView(text("Image tools", 18, true));
    LinearLayout transforms = row();
    addEqual(
        transforms,
        button(
            "Rotate 90°",
            () -> {
              p.turns = (p.turns + 1) % 4;
              changed(true);
            }));
    addEqual(transforms, button("Center crop", this::chooseCrop));
    addEqual(transforms, button("Look", this::chooseFilter));
    body.addView(transforms);
    body.addView(button("Adjust tone curves", this::editCurves));
    body.addView(
        text(
            "Shape brightness and red, green or blue tones. Curves affect real exported pixels.",
            13,
            false));
    body.addView(space(10));
    EditText caption = edit("Add a few words…", p.caption, 400, true);
    body.addView(fieldLabel("Caption", 18, caption));
    body.addView(caption);
    caption.addTextChangedListener(
        watcher(
            value -> {
              session.current.caption = value;
              changed(true);
            }));
    body.addView(
        text(
            "A contrast-safe caption sits along the bottom edge. Leave it empty for an image-only"
                + " poster.",
            13,
            false));
    body.addView(space(14));
    LinearLayout other = row();
    addEqual(other, button("Reset edits", this::resetEdits));
    addEqual(other, button("New image", () -> afterLeaveDecision(this::pickImage)));
    body.addView(other);
  }

  private void changed(boolean pixels) {
    if (session.current == null) return;
    session.dirty = true;
    if (status != null)
      status.setText(
          session.draftError.isEmpty() ? getString(R.string.unsaved_draft) : session.draftError);
    if (recipeLabel != null) recipeLabel.setText(recipeSummary());
    main.removeCallbacks(persist);
    main.postDelayed(persist, 200);
    if (pixels) {
      session.previewRendering = true;
      main.removeCallbacks(updatePreview);
      main.postDelayed(updatePreview, 180);
    }
  }

  private String recipeSummary() {
    PosterProject p = session.current;
    String size =
        session.preview == null
            ? ""
            : session.preview.getWidth() + " × " + session.preview.getHeight() + " preview · ";
    return size
        + cropName(p.crop)
        + " · "
        + filterName(p.filter)
        + " · "
        + (p.curves.shouldBeSkipped() ? "Natural tone" : "Custom curves");
  }

  private void persistDraft(boolean report) {
    main.removeCallbacks(persist);
    if (session == null || session.store == null || session.current == null) return;
    try {
      session.store.draft(session.current, session.dirty);
      session.draftError = "";
    } catch (IOException e) {
      session.draftError = getString(R.string.draft_failed, message(e));
      if (status != null) status.setText(session.draftError);
      if (report) error("Couldn’t keep draft", e);
    }
  }

  private void saveProject() {
    saveProject(null);
  }

  private boolean saveProject(Runnable then) {
    if (session.current == null) return false;
    try {
      session.current.updated = System.currentTimeMillis();
      session.store.save(session.current);
      session.dirty = false;
      persistDraft(false);
      if (status != null) status.setText(R.string.project_saved);
      toast("Project saved");
      if (then != null) then.run();
      return true;
    } catch (IOException e) {
      error("Couldn’t save project", e);
      return false;
    }
  }

  private void openProject(PosterProject project) {
    if (!session.busy.isEmpty()) return;
    session.current = project.copy();
    session.dirty = false;
    session.previewError = "";
    session.preview = null;
    persistDraft(false);
    showScreen();
    renderPreview();
  }

  private void leaveEditor() {
    afterLeaveDecision(this::returnToLibrary);
  }

  private void afterLeaveDecision(Runnable next) {
    if (!session.busy.isEmpty()) {
      toast("Please wait for " + session.busy.toLowerCase(java.util.Locale.ROOT));
      return;
    }
    if (!session.dirty) {
      next.run();
      return;
    }
    new AlertDialog.Builder(this)
        .setTitle("Keep your changes?")
        .setMessage(
            "Save this editable project, discard changes since the last save, or keep editing.")
        .setPositiveButton("Save", (d, w) -> saveProject(next))
        .setNeutralButton("Keep editing", null)
        .setNegativeButton(
            "Discard",
            (d, w) -> {
              discardEdits();
              next.run();
            })
        .show();
  }

  private void discardEdits() {
    main.removeCallbacks(persist);
    main.removeCallbacks(updatePreview);
    session.previewTicket++;
    session.store.clearDraft();
    session.store.removeUnreferencedSource(session.current);
    // A canceled subsequent picker should keep the last saved project, not discarded edits.
    if (session.current != null && session.store.exists(session.current)) {
      try {
        for (PosterProject p : session.store.list().projects)
          if (p.id.equals(session.current.id)) {
            session.current = p;
            session.dirty = false;
            showScreen();
            renderPreview();
            return;
          }
      } catch (IOException ignored) {
      }
    }
    session.current = null;
    session.dirty = false;
    session.preview = null;
    showScreen();
  }

  private void returnToLibrary() {
    main.removeCallbacks(persist);
    main.removeCallbacks(updatePreview);
    session.previewTicket++;
    session.store.clearDraft();
    session.current = null;
    session.dirty = false;
    session.preview = null;
    session.previewError = "";
    showScreen();
  }

  private void deleteProject(PosterProject project) {
    new AlertDialog.Builder(this)
        .setTitle("Delete “" + project.title + "”?")
        .setMessage(
            "This removes the saved project. Its private image is removed only when no other"
                + " project may need it. Your original photo and exported images stay unchanged."
                + " This cannot be undone.")
        .setNegativeButton("Cancel", null)
        .setPositiveButton(
            "Delete project",
            (d, w) -> {
              try {
                ProjectStore.DeleteResult result = session.store.delete(project);
                if (result == ProjectStore.DeleteResult.DELETED) toast("Project deleted");
                else
                  new AlertDialog.Builder(this)
                      .setTitle("Project deleted")
                      .setMessage(
                          result == ProjectStore.DeleteResult.IMAGE_UNCERTAIN
                              ? "Its private image was kept because some other project records"
                                  + " couldn't be read. This protects images that a recoverable"
                                  + " project may still need."
                              : "Its private image was kept because another saved project still"
                                  + " uses it.")
                      .setPositiveButton("OK", null)
                      .show();
                showScreen();
              } catch (IOException e) {
                error("Couldn’t fully delete project", e);
                showScreen();
              }
            })
        .show();
  }

  private void pickImage() {
    if (!session.busy.isEmpty()) return;
    Intent intent =
        new Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("image/*");
    try {
      startActivityForResult(intent, IMPORT_IMAGE);
    } catch (ActivityNotFoundException e) {
      error(
          "No file picker available",
          new IOException("Install or enable a system Files app to import an image."));
    }
  }

  private void startExport() {
    if (session.current == null || !session.busy.isEmpty()) return;
    persistDraft(false);
    if (session.previewRendering) {
      toast("Please wait for the preview to finish");
      return;
    }
    if (!session.previewError.isEmpty()) {
      error("Fix the preview before exporting", new IOException(session.previewError));
      return;
    }
    session.pendingExport = session.current.copy();
    String filename = session.current.title.replaceAll("[^\\p{L}\\p{N}._-]+", "-");
    if (filename.isEmpty()) filename = "poster";
    Intent intent =
        new Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("image/png")
            .putExtra(Intent.EXTRA_TITLE, PosterProject.limit(filename, 70) + ".png");
    try {
      startActivityForResult(intent, EXPORT_IMAGE);
    } catch (ActivityNotFoundException e) {
      session.pendingExport = null;
      error(
          "No file picker available",
          new IOException("Install or enable a system Files app to choose where to save."));
    }
  }

  @Override
  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (result != RESULT_OK || data == null || data.getData() == null) {
      if (request == EXPORT_IMAGE) session.pendingExport = null;
      return;
    }
    Uri uri = data.getData();
    if (!isSystemDocumentUri(uri)) {
      if (request == EXPORT_IMAGE) session.pendingExport = null;
      error(
          "Unsupported file destination",
          new IOException(
              "Choose a file through the Android system picker. Your project is unchanged."));
      return;
    }
    if (request == IMPORT_IMAGE) importImage(uri);
    if (request == EXPORT_IMAGE) exportImage(uri);
  }

  static boolean isSystemDocumentUri(Uri uri) {
    return uri != null
        && ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())
        && uri.getAuthority() != null
        && !uri.getAuthority().isEmpty();
  }

  private void importImage(Uri uri) {
    final android.content.Context context = getApplicationContext();
    final ProjectStore store = session.store;
    runWork(
        "Importing image…",
        () -> {
          Bitmap image = MediaFiles.readBitmap(context, uri, SOURCE_EDGE);
          PosterProject project = new PosterProject();
          project.source = UUID.randomUUID().toString();
          AtomicFile output = new AtomicFile(store.source(project.source));
          FileOutputStream stream = null;
          try {
            stream = output.startWrite();
            BitmapTransforms.exportPng(image, stream);
            output.finishWrite(stream);
            return project;
          } catch (Exception e) {
            if (stream != null) output.failWrite(stream);
            throw e;
          } finally {
            image.recycle();
          }
        },
        (s, project) -> {
          if (s.closed) {
            s.store.removeUnreferencedSource(project);
            return;
          }
          s.current = project;
          s.dirty = true;
          s.preview = null;
          s.previewError = "";
          try {
            s.store.draft(project, true);
          } catch (IOException e) {
            s.notice = "Image imported, but draft storage failed: " + message(e);
          }
          if (s.owner != null) {
            s.owner.showScreen();
            s.owner.renderPreview();
          }
        });
  }

  private void exportImage(Uri destination) {
    PosterProject snapshot = session.pendingExport;
    session.pendingExport = null;
    if (snapshot == null) {
      error(
          "Export interrupted",
          new IOException("Your project is safe. Tap Export PNG again to choose a destination."));
      return;
    }
    final ProjectStore store = session.store;
    final android.content.Context context = getApplicationContext();
    final int exportEdge = readExportSize();
    runWork(
        "Exporting PNG…",
        () -> {
          Bitmap output = PosterRenderer.render(store, snapshot, exportEdge);
          try {
            MediaFiles.copyPngTo(context, destination, output);
            return output.getWidth() + " × " + output.getHeight();
          } finally {
            output.recycle();
          }
        },
        (s, size) -> {
          s.notice = "PNG exported · " + size + " px";
          if (s.owner != null) {
            s.owner.toast(s.notice);
            if (s.owner.status != null) s.owner.status.setText(s.notice);
            s.notice = "";
          }
        });
  }

  private <T> void runWork(String label, Work<T> work, Result<T> result) {
    if (!session.busy.isEmpty()) return;
    Session s = session;
    s.busy = label;
    updateBusy();
    s.io.execute(
        () -> {
          T value = null;
          Throwable failure = null;
          try {
            value = work.run();
          } catch (Exception | OutOfMemoryError e) {
            failure = e;
          }
          final T output = value;
          final Throwable error = failure;
          s.main.post(
              () -> {
                s.busy = "";
                if (s.owner != null) s.owner.updateBusy();
                if (error == null) result.accept(s, output);
                else {
                  s.notice = label + " failed: " + message(error);
                  if (s.owner != null) {
                    s.owner.error("Couldn’t finish", error);
                    s.notice = "";
                  }
                }
              });
        });
  }

  private void renderPreview() {
    if (session.current == null || session.closed || session.store == null) return;
    Session s = session;
    s.previewRendering = true;
    long ticket = ++s.previewTicket;
    PosterProject snapshot = s.current.copy();
    if (status != null) status.setText(R.string.updating_preview);
    s.io.execute(
        () -> {
          if (ticket != s.previewTicket || s.closed) return;
          Bitmap result = null;
          String failure = "";
          try {
            result = PosterRenderer.render(s.store, snapshot, PREVIEW_EDGE);
          } catch (IOException | RuntimeException | OutOfMemoryError e) {
            failure = "Preview unavailable: " + message(e);
          }
          Bitmap ready = result;
          String error = failure;
          s.main.post(
              () -> {
                if (ticket != s.previewTicket || s.closed) {
                  if (ready != null) ready.recycle();
                  return;
                }
                s.previewError = error;
                s.previewRendering = false;
                s.preview = ready;
                if (s.owner != null) {
                  if (s.owner.preview != null) s.owner.preview.setImageBitmap(ready);
                  if (s.owner.status != null)
                    s.owner.status.setText(
                        error.isEmpty()
                            ? (s.dirty
                                ? "Unsaved changes · Draft kept on this device"
                                : "Saved project · Changes stay editable")
                            : error);
                  if (s.owner.recipeLabel != null)
                    s.owner.recipeLabel.setText(s.owner.recipeSummary());
                  if (s.owner.status != null && !s.draftError.isEmpty())
                    s.owner.status.setText(s.draftError);
                }
                // Detached ImageViews may hold the previous bitmap for one frame; use GC for safe
                // reclamation.
              });
        });
  }

  private int readExportSize() {
    return getSharedPreferences("poster.preferences", MODE_PRIVATE).getInt("exportEdge", 2048);
  }

  private void showSettings() {
    new AlertDialog.Builder(this)
        .setTitle("Pocket Poster settings")
        .setItems(
            new String[] {
              "Appearance, privacy & licenses", "Export size · " + readExportSize() + " px maximum"
            },
            (d, which) -> {
              if (which == 0)
                Settings.show(
                    this,
                    () -> {
                      persistDraft(false);
                      recreate();
                    });
              else {
                new AlertDialog.Builder(this)
                    .setTitle("PNG export size")
                    .setMessage(
                        "The longest edge is limited to this size. Smaller images are never"
                            + " enlarged. PNG preserves image quality.")
                    .setPositiveButton("2048 px", (dialog, button) -> saveExportSize(2048))
                    .setNeutralButton("1024 px", (dialog, button) -> saveExportSize(1024))
                    .setNegativeButton("Cancel", null)
                    .show();
              }
            })
        .setNegativeButton("Close", null)
        .show();
  }

  private void saveExportSize(int size) {
    if (getSharedPreferences("poster.preferences", MODE_PRIVATE)
        .edit()
        .putInt("exportEdge", size)
        .commit()) toast("Export size saved: " + size + " px");
    else error("Couldn’t save setting", new IOException("Your device may be out of storage."));
  }

  private void chooseCrop() {
    String[] names = {"Original proportions", "Square · 1:1", "Portrait · 4:5", "Landscape · 16:9"};
    String[] values = {"original", "square", "portrait", "wide"};
    choose(
        "Center crop",
        names,
        values,
        session.current.crop,
        value -> {
          session.current.crop = value;
          changed(true);
        });
  }

  private void chooseFilter() {
    String[] names = {"Original", "Black & white", "Warm", "Cool"};
    String[] values = {"original", "mono", "warm", "cool"};
    choose(
        "Choose a look",
        names,
        values,
        session.current.filter,
        value -> {
          session.current.filter = value;
          changed(true);
        });
  }

  private interface TextChange {
    void change(String value);
  }

  private void choose(
      String title, String[] names, String[] values, String current, TextChange selected) {
    int index = 0;
    for (int i = 0; i < values.length; i++) if (values[i].equals(current)) index = i;
    new AlertDialog.Builder(this)
        .setTitle(title)
        .setSingleChoiceItems(
            names,
            index,
            (dialog, which) -> {
              selected.change(values[which]);
              dialog.dismiss();
            })
        .setNegativeButton("Cancel", null)
        .show();
  }

  private void editCurves() {
    PosterProject working = session.current.copy();
    LinearLayout content = new LinearLayout(this);
    content.setOrientation(LinearLayout.VERTICAL);
    content.setPadding(dp(16), dp(8), dp(16), dp(10));
    content.addView(
        text(
            "Drag each curve section up or down, or use the five sliders. Apply commits the recipe;"
                + " Cancel leaves the poster unchanged.",
            13,
            false));
    RadioGroup channels = new RadioGroup(this);
    channels.setOrientation(LinearLayout.HORIZONTAL);
    String[] names = {"Light", "Red", "Green", "Blue"};
    int[] channelIds = new int[4];
    for (int i = 0; i < 4; i++) {
      RadioButton radio = new RadioButton(this);
      radio.setText(names[i]);
      radio.setTextSize(12);
      channelIds[i] = View.generateViewId();
      radio.setId(channelIds[i]);
      channels.addView(radio, new RadioGroup.LayoutParams(0, -2, 1));
    }
    channels.check(channelIds[0]);
    content.addView(channels);
    PhotoFilterCurvesControl graph = new PhotoFilterCurvesControl(this, working.curves);
    content.addView(graph, new LinearLayout.LayoutParams(-1, dp(190)));
    String[] labels = {"Blacks", "Shadows", "Midtones", "Highlights", "Whites"};
    SeekBar[] sliders = new SeekBar[5];
    TextView[] values = new TextView[5];
    boolean[] changing = {false};
    Runnable sync =
        () -> {
          changing[0] = true;
          float[] levels =
              PosterProject.levels(
                  PosterProject.channel(working.curves, working.curves.activeType));
          for (int i = 0; i < 5; i++) {
            sliders[i].setProgress(Math.round(levels[i]));
            values[i].setText(getString(R.string.curve_level, labels[i], Math.round(levels[i])));
          }
          changing[0] = false;
          graph.invalidate();
        };
    for (int i = 0; i < 5; i++) {
      LinearLayout line = row();
      values[i] = text(labels[i], 13, false);
      line.addView(values[i], new LinearLayout.LayoutParams(dp(112), -2));
      sliders[i] = new SeekBar(this);
      sliders[i].setMax(100);
      sliders[i].setContentDescription(labels[i] + " output level, 0 to 100");
      line.addView(sliders[i], new LinearLayout.LayoutParams(0, dp(48), 1));
      content.addView(line);
      int point = i;
      sliders[i].setOnSeekBarChangeListener(
          new SeekBar.OnSeekBarChangeListener() {
            public void onStartTrackingTouch(SeekBar bar) {}

            public void onStopTrackingTouch(SeekBar bar) {}

            public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
              if (changing[0] || !fromUser) return;
              PosterProject.setLevel(
                  PosterProject.channel(working.curves, working.curves.activeType), point, value);
              sync.run();
            }
          });
    }
    graph.setDelegate(sync::run);
    channels.setOnCheckedChangeListener(
        (group, checked) -> {
          for (int i = 0; i < channelIds.length; i++)
            if (channelIds[i] == checked) working.curves.activeType = i;
          sync.run();
        });
    sync.run();
    ScrollView scroll = new ScrollView(this);
    scroll.addView(content);
    AlertDialog dialog =
        new AlertDialog.Builder(this)
            .setTitle("Tone curves")
            .setView(scroll)
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Reset channel", null)
            .setPositiveButton(
                "Apply",
                (d, w) -> {
                  session.current.curves = working.curves;
                  changed(true);
                })
            .create();
    dialog.setOnShowListener(
        d ->
            dialog
                .getButton(AlertDialog.BUTTON_NEUTRAL)
                .setOnClickListener(
                    v -> {
                      ToneCurves.CurvesValue curve =
                          PosterProject.channel(working.curves, working.curves.activeType);
                      for (int i = 0; i < 5; i++) PosterProject.setLevel(curve, i, i * 25);
                      sync.run();
                    }));
    dialog.show();
  }

  private void resetEdits() {
    new AlertDialog.Builder(this)
        .setTitle("Reset all image edits?")
        .setMessage(
            "Restore the original crop, rotation, look and curves, and remove the caption. The"
                + " project name stays the same. Save to replace the previous recipe.")
        .setNegativeButton("Cancel", null)
        .setPositiveButton(
            "Reset edits",
            (d, w) -> {
              PosterProject p = session.current;
              p.turns = 0;
              p.crop = "original";
              p.filter = "original";
              p.caption = "";
              p.curves = new ToneCurves.CurvesToolValue();
              session.dirty = true;
              persistDraft(false);
              showScreen();
              renderPreview();
            })
        .show();
  }

  private void updateBusy() {
    if (root == null) return;
    boolean busy = !session.busy.isEmpty();
    busyLabel.setText(session.busy);
    busyLabel.setVisibility(busy ? View.VISIBLE : View.GONE);
    progress.setVisibility(busy ? View.VISIBLE : View.GONE);
    setEnabledRecursively(root, !busy);
    if (busy) {
      busyLabel.setEnabled(true);
      progress.setEnabled(true);
    }
  }

  private void setEnabledRecursively(View view, boolean enabled) {
    view.setEnabled(enabled);
    if (view instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
        setEnabledRecursively(((ViewGroup) view).getChildAt(i), enabled);
  }

  @Override
  public void onBackPressed() {
    if (!session.busy.isEmpty()) {
      toast("Please wait for the current operation to finish");
      return;
    }
    if (session.current != null) leaveEditor();
    else super.onBackPressed();
  }

  private void error(String title, Throwable e) {
    new AlertDialog.Builder(this)
        .setTitle(title)
        .setMessage(
            message(e)
                + "\n\n"
                + "Your original source photo is unchanged. You can retry or choose another file.")
        .setPositiveButton("OK", null)
        .show();
  }

  private static String message(Throwable e) {
    if (e instanceof OutOfMemoryError) return "Not enough memory. Try a smaller image.";
    String s = e.getMessage();
    return s == null || s.trim().isEmpty() ? "Please try again or choose a different file." : s;
  }

  private void toast(String message) {
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
  }

  private int dp(float size) {
    return Math.round(size * getResources().getDisplayMetrics().density);
  }

  private View space(int height) {
    View view = new View(this);
    view.setLayoutParams(new LinearLayout.LayoutParams(1, dp(height)));
    return view;
  }

  private LinearLayout row() {
    LinearLayout row = new LinearLayout(this);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setGravity(Gravity.CENTER_VERTICAL);
    return row;
  }

  private void addEqual(LinearLayout row, View child) {
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
    lp.setMargins(0, 0, dp(4), 0);
    row.addView(child, lp);
  }

  private TextView text(String value, int size, boolean bold) {
    TextView view = new TextView(this);
    view.setText(value);
    view.setTextSize(size);
    view.setTextColor(bold ? ink : muted);
    if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    view.setPadding(0, dp(5), 0, dp(5));
    return view;
  }

  private Button button(String label, Runnable click) {
    Button b = new Button(this);
    b.setText(label);
    b.setAllCaps(false);
    b.setTextSize(14);
    b.setMinHeight(dp(48));
    b.setOnClickListener(
        v -> {
          if (session.busy.isEmpty()) click.run();
        });
    return b;
  }

  private EditText edit(String hint, String value, int limit, boolean multiline) {
    EditText field = new EditText(this);
    field.setId(View.generateViewId());
    field.setHint(hint);
    field.setText(value);
    field.setTextColor(ink);
    field.setHintTextColor(muted);
    field.setTextSize(16);
    field.setFilters(new InputFilter[] {new InputFilter.LengthFilter(limit)});
    field.setSelectAllOnFocus(!multiline);
    field.setSingleLine(!multiline);
    field.setMinLines(multiline ? 2 : 1);
    field.setMaxLines(multiline ? 6 : 1);
    field.setGravity(Gravity.TOP);
    field.setPadding(dp(8), dp(10), dp(8), dp(10));
    return field;
  }

  private TextView fieldLabel(String value, int size, EditText field) {
    TextView label = text(value, size, true);
    label.setLabelFor(field.getId());
    return label;
  }

  private TextWatcher watcher(TextChange change) {
    return new TextWatcher() {
      public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

      public void onTextChanged(CharSequence s, int start, int before, int count) {
        change.change(s.toString());
      }

      public void afterTextChanged(Editable e) {}
    };
  }

  private static String cropName(String crop) {
    switch (crop) {
      case "square":
        return "Square 1:1";
      case "portrait":
        return "Portrait 4:5";
      case "wide":
        return "Landscape 16:9";
      default:
        return "Original proportions";
    }
  }

  private static String filterName(String filter) {
    switch (filter) {
      case "mono":
        return "Black & white";
      case "warm":
        return "Warm look";
      case "cool":
        return "Cool look";
      default:
        return "Original look";
    }
  }
}
