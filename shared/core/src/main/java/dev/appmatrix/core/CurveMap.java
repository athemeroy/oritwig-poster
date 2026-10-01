// SPDX-License-Identifier: GPL-2.0-or-later
/* CPU adaptation for App Matrix, 2026-10-01, from Telegram Android
 * FilterShaders.java at f2908b14133bbffbf7ab04f641ecb5bfaf533242.
 * Retains HSL luminance adjustment, saturation attenuation and subsequent RGB
 * curves. Uses deterministic nearest-index 200-entry CPU tables rather than
 * OpenGL texture filtering; GPU output is not claimed bit-identical.
 * See third_party/TELEGRAM.md.
 */
package dev.appmatrix.core;

/** Immutable per-operation curve table, independent of Android and GPU state. */
public final class CurveMap {
  private final int[][] tables = new int[4][200];
  private final boolean identity;

  public CurveMap(ToneCurves.CurvesToolValue curves) {
    if (curves == null) throw new IllegalArgumentException("Curves are required");
    identity = curves.shouldBeSkipped();
    ToneCurves.CurvesValue[] channels = {
      curves.luminanceCurve, curves.redCurve, curves.greenCurve, curves.blueCurve
    };
    for (int c = 0; c < 4; c++) {
      validate(channels[c]);
      channels[c].interpolateCurve(); // Never use stale samples after direct field edits.
      float[] samples = channels[c].getDataPoints();
      if (samples.length != 200) throw new IllegalStateException("Invalid curve table");
      for (int i = 0; i < 200; i++)
        tables[c][i] = Math.max(0, Math.min(255, (int) (samples[i] * 255)));
    }
  }

  private static void validate(ToneCurves.CurvesValue c) {
    for (float v :
        new float[] {
          c.blacksLevel, c.shadowsLevel, c.midtonesLevel, c.highlightsLevel, c.whitesLevel
        }) {
      if (!Float.isFinite(v) || v < 0 || v > 100)
        throw new IllegalArgumentException("Curve points must be 0–100");
    }
  }

  private float sample(int channel, float value) {
    return tables[channel][Math.max(0, Math.min(199, (int) Math.floor(value * 200)))] / 255f;
  }

  private static float smooth(float low, float high, float v) {
    float x = Math.max(0, Math.min(1, (v - low) / (high - low)));
    return x * x * (3 - 2 * x);
  }

  private static float hue(float f1, float f2, float h) {
    if (h < 0) h += 1;
    else if (h > 1) h -= 1;
    if (6 * h < 1) return f1 + (f2 - f1) * 6 * h;
    if (2 * h < 1) return f2;
    if (3 * h < 2) return f1 + (f2 - f1) * (2f / 3 - h) * 6;
    return f1;
  }

  public int mapArgb(int argb) {
    if (identity) return argb;
    float r = ((argb >>> 16) & 255) / 255f,
        g = ((argb >>> 8) & 255) / 255f,
        b = (argb & 255) / 255f;
    float min = Math.min(r, Math.min(g, b)), max = Math.max(r, Math.max(g, b));
    float delta = max - min, l = (max + min) / 2, h = 0, s = 0;
    if (delta != 0) {
      s = l < .5f ? delta / (max + min) : delta / (2 - max - min);
      float dr = (((max - r) / 6) + (delta / 2)) / delta;
      float dg = (((max - g) / 6) + (delta / 2)) / delta;
      float db = (((max - b) / 6) + (delta / 2)) / delta;
      if (r == max) h = db - dg;
      else if (g == max) h = 1f / 3 + dr - db;
      else h = 2f / 3 + dg - dr;
      if (h < 0) h += 1;
      else if (h > 1) h -= 1;
    }
    s *= smooth(0, .1f, l) * (1 - smooth(.8f, 1, l));
    l = sample(0, l);
    if (s == 0) r = g = b = l;
    else {
      float f2 = l < .5f ? l * (1 + s) : l + s - s * l;
      float f1 = 2 * l - f2;
      r = hue(f1, f2, h + 1f / 3);
      g = hue(f1, f2, h);
      b = hue(f1, f2, h - 1f / 3);
    }
    int rr = Math.round(sample(1, r) * 255),
        gg = Math.round(sample(2, g) * 255),
        bb = Math.round(sample(3, b) * 255);
    return (argb & 0xff000000) | (rr << 16) | (gg << 8) | bb;
  }

  public void apply(int[] pixels) {
    for (int i = 0; i < pixels.length; i++) pixels[i] = mapArgb(pixels[i]);
  }
}
