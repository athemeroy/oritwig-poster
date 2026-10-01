/* SPDX-License-Identifier: GPL-3.0-or-later */
package dev.appmatrix.poster;

import android.content.Context;
import android.util.AtomicFile;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONException;
import org.json.JSONObject;

/** Per-project atomic records prevent one failed write from damaging the library. */
final class ProjectStore {
  private static final int MAX_RECORD_BYTES = 65536;
  final File root, images;

  ProjectStore(Context context) throws IOException {
    root = new File(context.getFilesDir(), "posters");
    images = new File(context.getFilesDir(), "poster-images");
    if ((!root.isDirectory() && !root.mkdirs()) || (!images.isDirectory() && !images.mkdirs()))
      throw new IOException("Cannot create private project storage");
  }

  File source(String id) throws IOException {
    try {
      return new File(images, PosterProject.safeId(id) + ".png");
    } catch (JSONException e) {
      throw new IOException("Invalid image identifier", e);
    }
  }

  static final class Listing {
    final List<PosterProject> projects = new ArrayList<>();
    int unreadable;
  }

  Listing list() throws IOException {
    Listing result = new Listing();
    File[] files =
        root.listFiles(
            (dir, name) ->
                (name.endsWith(".json") || name.endsWith(".json.bak"))
                    && !name.equals("draft.json")
                    && !name.equals("draft.json.bak"));
    if (files == null) throw new IOException("Cannot read project library");
    Set<String> visited = new HashSet<>();
    for (File f : files) {
      if (f.getName().endsWith(".bak"))
        f = new File(root, f.getName().substring(0, f.getName().length() - 4));
      if (!visited.add(f.getName())) continue;
      try {
        PosterProject project = PosterProject.fromJson(read(f));
        if (!f.getName().equals(project.id + ".json"))
          throw new IOException("Project filename and identifier do not match");
        result.projects.add(project);
      } catch (IOException | JSONException e) {
        result.unreadable++;
      }
    }
    Collections.sort(result.projects, (a, b) -> Long.compare(b.updated, a.updated));
    return result;
  }

  void save(PosterProject project) throws IOException {
    try {
      if (!source(project.source).isFile()) throw new IOException("The original image is missing");
      write(new File(root, PosterProject.safeId(project.id) + ".json"), project.toJson());
    } catch (JSONException e) {
      throw new IOException("Cannot save project", e);
    }
  }

  void draft(PosterProject project, boolean dirty) throws IOException {
    try {
      JSONObject j = project.toJson();
      j.put("dirty", dirty);
      write(new File(root, "draft.json"), j);
    } catch (JSONException e) {
      throw new IOException("Cannot save draft", e);
    }
  }

  JSONObject draft() throws IOException {
    File f = new File(root, "draft.json");
    if (!f.exists() && !new File(f.getPath() + ".bak").exists()) return null;
    return read(f);
  }

  void clearDraft() {
    new AtomicFile(new File(root, "draft.json")).delete();
  }

  boolean exists(PosterProject p) {
    return new File(root, p.id + ".json").isFile();
  }

  enum DeleteResult {
    DELETED,
    IMAGE_IN_USE,
    IMAGE_UNCERTAIN
  }

  DeleteResult delete(PosterProject p) throws IOException {
    File record = new File(root, p.id + ".json");
    if (record.exists() && !record.delete()) throw new IOException("Could not delete this project");
    new AtomicFile(record).delete();
    // Keep the source if a future compatible project shares it.
    Listing listing = list();
    if (listing.unreadable > 0) return DeleteResult.IMAGE_UNCERTAIN;
    for (PosterProject remaining : listing.projects)
      if (remaining.source.equals(p.source)) return DeleteResult.IMAGE_IN_USE;
    File image = source(p.source);
    if (image.exists() && !image.delete())
      throw new IOException("Project removed, but its private image could not be removed");
    return DeleteResult.DELETED;
  }

  void removeUnreferencedSource(PosterProject p) {
    if (p == null || exists(p)) return;
    try {
      Listing listing = list();
      if (listing.unreadable > 0) return;
      for (PosterProject remaining : listing.projects)
        if (remaining.source.equals(p.source)) return;
      source(p.source).delete();
    } catch (IOException ignored) {
      /* Do not delete on an uncertain library state. */
    }
  }

  private static JSONObject read(File file) throws IOException {
    try (FileInputStream in = new AtomicFile(file).openRead();
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[2048];
      int count;
      while ((count = in.read(buffer)) != -1) {
        if (out.size() + count > MAX_RECORD_BYTES)
          throw new IOException("Project file is too large");
        out.write(buffer, 0, count);
      }
      return new JSONObject(out.toString(StandardCharsets.UTF_8.name()));
    } catch (JSONException e) {
      throw new IOException("Project file is damaged", e);
    }
  }

  private static void write(File file, JSONObject value) throws IOException {
    AtomicFile atomic = new AtomicFile(file);
    FileOutputStream stream = null;
    try {
      stream = atomic.startWrite();
      byte[] bytes = value.toString().getBytes(StandardCharsets.UTF_8);
      if (bytes.length > MAX_RECORD_BYTES) throw new IOException("Project is too large");
      stream.write(bytes);
      atomic.finishWrite(stream);
    } catch (IOException | RuntimeException e) {
      if (stream != null) atomic.failWrite(stream);
      if (e instanceof IOException) throw (IOException) e;
      throw new IOException("Could not write project", e);
    }
  }
}
