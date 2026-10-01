/* SPDX-License-Identifier: GPL-3.0-or-later */
package dev.appmatrix.poster;

import android.graphics.Bitmap;
import dev.appmatrix.core.BitmapTransforms;
import dev.appmatrix.core.MediaFiles;
import java.io.IOException;

final class PosterRenderer {
  private PosterRenderer() {}

  /** Owns all intermediate bitmaps; the caller owns the returned bitmap. */
  static Bitmap render(ProjectStore store, PosterProject p, int maxDimension) throws IOException {
    Bitmap current = MediaFiles.loadBitmap(store.source(p.source).getAbsolutePath());
    try {
      current = replace(current, BitmapTransforms.rotate(current, p.turns));
      switch (p.crop) {
        case "square":
          current = replace(current, BitmapTransforms.centerCrop(current, 1, 1));
          break;
        case "portrait":
          current = replace(current, BitmapTransforms.centerCrop(current, 4, 5));
          break;
        case "wide":
          current = replace(current, BitmapTransforms.centerCrop(current, 16, 9));
          break;
        default:
          break;
      }
      current = replace(current, BitmapTransforms.applyFilter(current, p.filter));
      current = replace(current, BitmapTransforms.applyCurves(current, p.curves));
      current = replace(current, BitmapTransforms.caption(current, p.caption));
      // Scale only the final composition: preview and export keep identical caption wrapping.
      current = replace(current, BitmapTransforms.resize(current, maxDimension));
      return current;
    } catch (RuntimeException | OutOfMemoryError e) {
      current.recycle();
      throw new IOException(
          e instanceof OutOfMemoryError
              ? "Not enough memory. Try a smaller image."
              : e.getMessage(),
          e);
    }
  }

  private static Bitmap replace(Bitmap before, Bitmap after) {
    if (before != after) before.recycle();
    return after;
  }
}
