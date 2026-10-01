// SPDX-License-Identifier: GPL-3.0-or-later
package dev.appmatrix.core;

import static org.junit.Assert.*;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.Test;

/**
 * Android-free regression tests against frozen, independently generated vectors. Model samples come
 * from the original Telegram nested classes, not ToneCurves. CPU pixels use independent
 * chroma/sector HSL equations and the documented nearest-index adaptation. See
 * /telegram-curves/README.md for provenance.
 */
public final class TelegramCurveParityTest {
  private static final class Vector {
    final String name;
    final float[] controls;
    final float[] samples;
    final int pathLength;
    final String pathHash;

    Vector(String line) {
      String[] columns = line.split("\\|");
      name = columns[0];
      String[] points = columns[1].split(",");
      controls = new float[points.length];
      for (int i = 0; i < points.length; i++) controls[i] = Float.parseFloat(points[i]);
      String[] bits = columns[2].split(",");
      samples = new float[bits.length];
      for (int i = 0; i < bits.length; i++) samples[i] = Float.intBitsToFloat(hex(bits[i]));
      pathLength = Integer.parseInt(columns[3]);
      pathHash = columns[4];
    }
  }

  private static List<String> lines(String resource) throws Exception {
    InputStream input =
        TelegramCurveParityTest.class.getResourceAsStream("/telegram-curves/" + resource);
    assertNotNull("Missing independent fixture " + resource, input);
    List<String> result = new ArrayList<>();
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
      for (String line; (line = reader.readLine()) != null; ) {
        if (!line.isEmpty() && !line.startsWith("#")) result.add(line);
      }
    }
    return result;
  }

  private static Map<String, Vector> vectors() throws Exception {
    Map<String, Vector> out = new LinkedHashMap<>();
    for (String line : lines("upstream-vectors.txt")) {
      Vector v = new Vector(line);
      assertNull("Duplicate fixture", out.put(v.name, v));
    }
    assertEquals(8, out.size());
    return out;
  }

  private static int hex(String text) {
    return (int) Long.parseLong(text, 16);
  }

  private static ToneCurves.CurvesValue curve(float... points) {
    ToneCurves.CurvesValue result = new ToneCurves.CurvesValue();
    set(result, points);
    return result;
  }

  private static void set(ToneCurves.CurvesValue c, float... p) {
    assertEquals(5, p.length);
    c.blacksLevel = p[0];
    c.shadowsLevel = p[1];
    c.midtonesLevel = p[2];
    c.highlightsLevel = p[3];
    c.whitesLevel = p[4];
  }

  private static ToneCurves.CurvesValue[] channels(ToneCurves.CurvesToolValue c) {
    return new ToneCurves.CurvesValue[] {c.luminanceCurve, c.redCurve, c.greenCurve, c.blueCurve};
  }

  private static ToneCurves.CurvesToolValue profile(Map<String, Vector> all, String names) {
    ToneCurves.CurvesToolValue out = new ToneCurves.CurvesToolValue();
    String[] split = names.split(",");
    ToneCurves.CurvesValue[] targets = channels(out);
    assertEquals(4, split.length);
    for (int i = 0; i < 4; i++) set(targets[i], all.get(split[i]).controls);
    return out;
  }

  private static String sha256Floats(float[] values) throws Exception {
    ByteBuffer bytes = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
    for (float value : values) bytes.putFloat(value);
    StringBuilder out = new StringBuilder();
    for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes.array())) {
      out.append(String.format(Locale.ROOT, "%02x", b & 255));
    }
    return out.toString();
  }

  @Test
  public void everyModelSampleMatchesPinnedUpstreamBitForBit() throws Exception {
    for (Vector v : vectors().values()) {
      float[] actual = curve(v.controls).getDataPoints();
      assertEquals(v.name + " must have 200 texture samples", 200, actual.length);
      for (int i = 0; i < actual.length; i++) {
        assertEquals(
            v.name + " sample " + i,
            Float.floatToRawIntBits(v.samples[i]),
            Float.floatToRawIntBits(actual[i]));
      }
    }
  }

  @Test
  public void completeDrawingPathMatchesPinnedUpstream() throws Exception {
    for (Vector v : vectors().values()) {
      float[] path = curve(v.controls).interpolateCurve();
      assertEquals(v.name, v.pathLength, path.length);
      assertEquals(v.name, v.pathHash, sha256Floats(path));
    }
  }

  @Test
  public void drawingEndpointsAndFourKnotsAreNotTextureEndpoints() {
    ToneCurves.CurvesValue c = curve(0, 25, 50, 75, 100);
    float[] path = c.interpolateCurve();
    assertEquals(804, path.length);
    assertEquals(-0.001f, path[0], 0f);
    assertEquals(0f, path[1], 0f);
    for (int k = 1; k <= 4; k++) {
      assertEquals(k / 4f, path[k * 200], 0f);
      assertEquals(k / 4f, path[k * 200 + 1], 0f);
    }
    assertEquals(1.001f, path[802], 0f);
    assertEquals(1f, path[803], 0f);
    float[] samples = c.getDataPoints();
    assertTrue("Sampling starts at t=.01, not the black knot", samples[0] > 0f);
    assertTrue("Sampling ends at t=.99, not the white knot", samples[199] < 1f);
    assertEquals(0.2525f, samples[50], 0.0000001f);
    assertEquals(0.5025f, samples[100], 0.0000001f);
  }

  @Test
  public void identitySamplesAreOrderedAndAllSamplesAreClamped() throws Exception {
    float[] identity = curve(0, 25, 50, 75, 100).getDataPoints();
    for (int i = 1; i < identity.length; i++) assertTrue(identity[i] > identity[i - 1]);
    for (Vector v : vectors().values()) {
      for (float sample : curve(v.controls).getDataPoints()) {
        assertTrue(v.name + " finite", Float.isFinite(sample));
        assertTrue(v.name + " lower bound", sample >= 0f);
        assertTrue(v.name + " upper bound", sample <= 1f);
      }
    }
  }

  @Test
  public void interpolationKeepsUpstreamCatmullRomOvershootThenClamps() {
    float[] saw = curve(0, 100, 0, 100, 0).getDataPoints();
    assertEquals(0f, curve(0, 0, 100, 0, 0).getDataPoints()[25], 0f);
    assertEquals(1f, curve(100, 100, 0, 100, 100).getDataPoints()[25], 0f);
    assertTrue("Saw profile must not be forced monotonic", saw[49] > saw[99]);
    float[] plateau = curve(0, 50, 50, 50, 100).getDataPoints();
    assertTrue("Monotone knots do not imply a monotone Catmull-Rom curve", plateau[65] > .5f);
    assertTrue("Do not substitute a monotonic spline", plateau[135] < .5f);
  }

  @Test
  public void curveBufferUsesUpstreamRgbaOrderAndTruncation() throws Exception {
    Map<String, Vector> all = vectors();
    String[] names = {"lift", "invert", "fraction", "saw"};
    ToneCurves.CurvesToolValue c = profile(all, String.join(",", names));
    c.fillBuffer();
    assertTrue(c.curveBuffer.isDirect());
    assertEquals(ByteOrder.LITTLE_ENDIAN, c.curveBuffer.order());
    assertEquals(800, c.curveBuffer.capacity());
    assertEquals(0, c.curveBuffer.position());
    int[] order = {1, 2, 3, 0};
    for (int i = 0; i < 200; i++) {
      for (int byteChannel = 0; byteChannel < 4; byteChannel++) {
        int expected = (int) (all.get(names[order[byteChannel]]).samples[i] * 255f);
        assertEquals(
            "sample " + i + " byte " + byteChannel,
            expected,
            c.curveBuffer.get(i * 4 + byteChannel) & 255);
      }
    }
    c.curveBuffer.position(799);
    c.fillBuffer();
    assertEquals("Repeated fill rewinds without overflow", 0, c.curveBuffer.position());
  }

  @Test
  public void defaultDetectionCoversEveryChannelAndControlPoint() {
    float[] defaults = {0, 25, 50, 75, 100};
    assertTrue(new ToneCurves.CurvesToolValue().shouldBeSkipped());
    for (int channel = 0; channel < 4; channel++) {
      for (int point = 0; point < 5; point++) {
        ToneCurves.CurvesToolValue c = new ToneCurves.CurvesToolValue();
        float[] changed = defaults.clone();
        changed[point] += point == 4 ? -1f : 1f;
        set(channels(c)[channel], changed);
        assertFalse("channel " + channel + " point " + point, c.shouldBeSkipped());
      }
    }
    ToneCurves.CurvesValue c = new ToneCurves.CurvesValue();
    c.blacksLevel = 0.000001f;
    assertTrue(c.isDefault());
    c.blacksLevel = 0.00001f;
    assertTrue("Float representation lies just below double tolerance", c.isDefault());
    c.blacksLevel = 0.000011f;
    assertFalse(c.isDefault());
  }

  @Test
  public void saveRestoreRestoresEveryValueAndInvalidatesCachedSamples() throws Exception {
    Vector original = vectors().get("fraction");
    ToneCurves.CurvesValue c = curve(original.controls);
    c.saveValues();
    c.getDataPoints();
    set(c, 100, 0, 100, 0, 100);
    float[] edited = c.interpolateCurve();
    c.restoreValues();
    assertArrayEquals(
        original.controls,
        new float[] {
          c.blacksLevel, c.shadowsLevel, c.midtonesLevel, c.highlightsLevel, c.whitesLevel
        },
        0f);
    assertArrayEquals(original.samples, c.getDataPoints(), 0f);
    assertNotSame(edited, c.getDataPoints());
  }

  @Test
  public void modelExplicitReinterpolationRefreshesItsUpstreamCache() {
    ToneCurves.CurvesValue c = new ToneCurves.CurvesValue();
    float[] before = c.getDataPoints();
    c.midtonesLevel = 80;
    assertSame("Public field edits retain upstream cache semantics", before, c.getDataPoints());
    c.interpolateCurve();
    assertNotSame(before, c.getDataPoints());
    assertTrue(c.getDataPoints()[100] > before[100]);
  }

  @Test
  public void cpuPixelsMatchIndependentHslGoldenVectors() throws Exception {
    Map<String, Vector> all = vectors();
    int tested = 0;
    for (String line : lines("cpu-vectors.txt")) {
      String[] fields = line.split("\\|");
      int input = hex(fields[1]);
      int expected = hex(fields[2]);
      assertEquals(line, expected, new CurveMap(profile(all, fields[0])).mapArgb(input));
      tested++;
    }
    assertEquals("Ten independent channel profiles, 25 colors each", 250, tested);
  }

  @Test
  public void defaultBypassIsExactlyIdentityIncludingTransparentRgb() {
    CurveMap identity = new CurveMap(new ToneCurves.CurvesToolValue());
    int[] components = {0, 1, 2, 16, 63, 127, 128, 191, 253, 254, 255};
    for (int alpha : components)
      for (int red : components)
        for (int green : components)
          for (int blue : components) {
            int pixel = (alpha << 24) | (red << 16) | (green << 8) | blue;
            assertEquals(pixel, identity.mapArgb(pixel));
          }
  }

  @Test
  public void channelsUseRgbaOrderAndPreserveEveryAlphaValue() {
    ToneCurves.CurvesToolValue c = new ToneCurves.CurvesToolValue();
    set(c.redCurve, 20, 20, 20, 20, 20);
    set(c.greenCurve, 60, 60, 60, 60, 60);
    set(c.blueCurve, 80, 80, 80, 80, 80);
    CurveMap map = new CurveMap(c);
    for (int alpha = 0; alpha <= 255; alpha++) {
      assertEquals((alpha << 24) | 0x3399cc, map.mapArgb((alpha << 24) | 0x72b5e1));
    }
  }

  @Test
  public void cpuBuildRefreshesStaleModelSamples() throws Exception {
    Map<String, Vector> all = vectors();
    ToneCurves.CurvesToolValue c = new ToneCurves.CurvesToolValue();
    c.fillBuffer();
    set(c.luminanceCurve, all.get("lift").controls);
    set(c.redCurve, all.get("invert").controls);
    CurveMap fresh = new CurveMap(profile(all, "lift,invert,identity,identity"));
    CurveMap stale = new CurveMap(c);
    for (int i = 0; i < 256; i++) {
      int pixel = 0xa5000000 | (i << 16) | ((255 - i) << 8) | (i * 37 & 255);
      assertEquals(fresh.mapArgb(pixel), stale.mapArgb(pixel));
    }
  }

  @Test
  public void cpuMapIsAnImmutableSnapshotEvenWhenFieldsAndCachesChange() throws Exception {
    ToneCurves.CurvesToolValue c = profile(vectors(), "lift,invert,fraction,saw");
    CurveMap before = new CurveMap(c);
    int expected = before.mapArgb(0x7f357ac0);
    for (ToneCurves.CurvesValue channel : channels(c)) {
      set(channel, 0, 0, 0, 0, 0);
      java.util.Arrays.fill(channel.cachedDataPoints, 1f);
    }
    c.activeType = 3;
    c.curveBuffer.put(0, (byte) 0);
    assertEquals(expected, before.mapArgb(0x7f357ac0));
    assertNotEquals(expected, new CurveMap(c).mapArgb(0x7f357ac0));
  }

  @Test
  public void activeEditorChannelDoesNotChangeRendering() throws Exception {
    ToneCurves.CurvesToolValue c = profile(vectors(), "fraction,lift,invert,saw");
    int expected = new CurveMap(c).mapArgb(0x80357ac0);
    for (int type = 0; type < 4; type++) {
      c.activeType = type;
      assertEquals(expected, new CurveMap(c).mapArgb(0x80357ac0));
    }
  }

  @Test
  public void invalidControlsAreRejectedForEveryChannelAndPosition() {
    float[] badValues = {
      Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, -.001f, 100.001f
    };
    for (int channel = 0; channel < 4; channel++)
      for (int point = 0; point < 5; point++)
        for (float bad : badValues) {
          ToneCurves.CurvesToolValue c = new ToneCurves.CurvesToolValue();
          float[] p = {0, 25, 50, 75, 100};
          p[point] = bad;
          set(channels(c)[channel], p);
          try {
            new CurveMap(c);
            fail("Expected rejection for channel " + channel + " point " + point + " = " + bad);
          } catch (IllegalArgumentException expected) {
            /* Intentional API boundary. */
          }
        }
    try {
      new CurveMap(null);
      fail("Null model must be rejected");
    } catch (IllegalArgumentException expected) {
      /* Intentional API boundary. */
    }
  }

  @Test
  public void applyMatchesPointwiseMappingAndAcceptsEmptyArrays() throws Exception {
    CurveMap map = new CurveMap(profile(vectors(), "lift,invert,fraction,saw"));
    int[] pixels = {0, 0xffffffff, 0x01357ac0, 0x807f281c, 0xffc18352};
    int[] expected = pixels.clone();
    for (int i = 0; i < expected.length; i++) expected[i] = map.mapArgb(expected[i]);
    map.apply(pixels);
    assertArrayEquals(expected, pixels);
    map.apply(new int[0]);
  }
}
