// SPDX-License-Identifier: GPL-3.0-or-later
package dev.appmatrix.core;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import java.io.IOException;
import java.io.OutputStream;

/** Non-destructive, account-free image operations. Inputs are never recycled. */
public final class BitmapTransforms {
  private BitmapTransforms() {}

  public static Bitmap rotate(Bitmap input, int quarterTurns) {
    int turns = Math.floorMod(quarterTurns, 4);
    if (turns == 0) return input;
    Matrix m = new Matrix();
    m.postRotate(turns * 90f);
    return Bitmap.createBitmap(input, 0, 0, input.getWidth(), input.getHeight(), m, true);
  }

  public static Bitmap centerCrop(Bitmap input, int widthRatio, int heightRatio) {
    if (widthRatio <= 0 || heightRatio <= 0)
      throw new IllegalArgumentException("Crop ratio must be positive");
    double ratio = (double) widthRatio / heightRatio;
    int w = input.getWidth(), h = input.getHeight();
    int cw = w, ch = h;
    if ((double) w / h > ratio) cw = Math.max(1, (int) Math.round(h * ratio));
    else ch = Math.max(1, (int) Math.round(w / ratio));
    return Bitmap.createBitmap(input, (w - cw) / 2, (h - ch) / 2, cw, ch);
  }

  public static Bitmap resize(Bitmap input, int maxDimension) {
    if (maxDimension < 1 || maxDimension > 4096)
      throw new IllegalArgumentException("Export size must be 1–4096");
    float scale =
        Math.min(1f, (float) maxDimension / Math.max(input.getWidth(), input.getHeight()));
    if (scale == 1f) return input;
    return Bitmap.createScaledBitmap(
        input,
        Math.max(1, Math.round(input.getWidth() * scale)),
        Math.max(1, Math.round(input.getHeight() * scale)),
        true);
  }

  public static Bitmap applyFilter(Bitmap input, String filter) {
    if (filter == null || "original".equals(filter)) return input;
    if (!"mono".equals(filter) && !"warm".equals(filter) && !"cool".equals(filter))
      throw new IllegalArgumentException("Unknown filter");
    Bitmap output = input.copy(Bitmap.Config.ARGB_8888, true);
    int w = output.getWidth();
    int[] row = new int[w];
    for (int y = 0; y < output.getHeight(); y++) {
      output.getPixels(row, 0, w, 0, y, w, 1);
      for (int x = 0; x < w; x++) {
        int p = row[x], r = Color.red(p), g = Color.green(p), b = Color.blue(p);
        if ("mono".equals(filter)) {
          int v = Math.round(.2126f * r + .7152f * g + .0722f * b);
          r = g = b = v;
        } else if ("warm".equals(filter)) {
          r = Math.min(255, r + 15);
          g = Math.min(255, g + 4);
          b = Math.max(0, b - 12);
        } else {
          r = Math.max(0, r - 10);
          g = Math.min(255, g + 3);
          b = Math.min(255, b + 15);
        }
        row[x] = Color.argb(Color.alpha(p), r, g, b);
      }
      output.setPixels(row, 0, w, 0, y, w, 1);
    }
    return output;
  }

  public static Bitmap applyCurves(Bitmap input, ToneCurves.CurvesToolValue curves) {
    if (curves.shouldBeSkipped()) return input;
    CurveMap map = new CurveMap(curves);
    Bitmap result = input.copy(Bitmap.Config.ARGB_8888, true);
    int w = result.getWidth();
    int[] row = new int[w];
    for (int y = 0; y < result.getHeight(); y++) {
      result.getPixels(row, 0, w, 0, y, w, 1);
      map.apply(row);
      result.setPixels(row, 0, w, 0, y, w, 1);
    }
    return result;
  }

  public static Bitmap caption(Bitmap input, String text) {
    if (text == null || text.trim().isEmpty()) return input;
    if (text.length() > 400)
      throw new IllegalArgumentException("Use at most 400 caption characters");
    int margin = Math.max(1, Math.round(input.getWidth() / 24f));
    TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    paint.setColor(Color.WHITE);
    paint.setTextSize(Math.max(1, input.getWidth() / 22f));
    paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    StaticLayout layout =
        StaticLayout.Builder.obtain(
                text, 0, text.length(), paint, Math.max(1, input.getWidth() - 2 * margin))
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .build();
    if (layout.getHeight() + 2 * margin > input.getHeight())
      throw new IllegalArgumentException("Caption is too long for this image");
    Bitmap out = input.copy(Bitmap.Config.ARGB_8888, true);
    Canvas canvas = new Canvas(out);
    Paint background = new Paint();
    background.setColor(0xcc101923);
    float top = out.getHeight() - layout.getHeight() - 2 * margin;
    canvas.drawRect(0, top, out.getWidth(), out.getHeight(), background);
    canvas.save();
    canvas.translate(margin, top + margin);
    layout.draw(canvas);
    canvas.restore();
    return out;
  }

  public static void exportJpeg(Bitmap input, OutputStream output, int quality) throws IOException {
    if (quality < 1 || quality > 100) throw new IllegalArgumentException("Quality must be 1–100");
    if (!input.compress(Bitmap.CompressFormat.JPEG, quality, output))
      throw new IOException("Could not encode image");
  }

  public static void exportPng(Bitmap input, OutputStream output) throws IOException {
    if (!input.compress(Bitmap.CompressFormat.PNG, 100, output))
      throw new IOException("Could not encode image");
  }
}
