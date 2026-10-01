/* SPDX-License-Identifier: GPL-3.0-or-later */
package dev.appmatrix.poster;

import static org.junit.Assert.*;

import dev.appmatrix.core.ToneCurves;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

public class PosterProjectTest {
  private PosterProject fixture() {
    PosterProject p = new PosterProject();
    p.source = UUID.randomUUID().toString();
    p.title = "Night garden 🌙";
    p.caption = "Small moments\nStay curious";
    p.crop = "portrait";
    p.filter = "warm";
    p.turns = 3;
    p.updated = 1234567;
    return p;
  }

  @Test
  public void completeRecipeRoundTripsWithoutAliasing() throws Exception {
    PosterProject p = fixture();
    for (int channel = 0; channel < 4; channel++)
      for (int point = 0; point < 5; point++)
        PosterProject.setLevel(
            PosterProject.channel(p.curves, channel), point, 7 + channel * 5 + point * 13);
    PosterProject copy = PosterProject.fromJson(new JSONObject(p.toJson().toString()));
    assertEquals(p.toJson().toString(), copy.toJson().toString());
    PosterProject.setLevel(copy.curves.redCurve, 0, 88);
    assertNotEquals(copy.curves.redCurve.blacksLevel, p.curves.redCurve.blacksLevel, 0.01f);
  }

  @Test
  public void clonePreservesAllSourceAndEditFields() throws Exception {
    PosterProject p = fixture();
    assertEquals(p.toJson().toString(), p.copy().toJson().toString());
  }

  @Test
  public void normalizesQuarterTurns() throws Exception {
    JSONObject json = fixture().toJson();
    json.put("turns", -1);
    assertEquals(3, PosterProject.fromJson(json).turns);
    json.put("turns", 9);
    assertEquals(1, PosterProject.fromJson(json).turns);
  }

  @Test
  public void rejectsNewerSchema() throws Exception {
    JSONObject json = fixture().toJson();
    json.put("schema", 2);
    reject(json);
  }

  @Test
  public void rejectsPathTraversal() throws Exception {
    JSONObject json = fixture().toJson();
    json.put("source", "../private-file");
    reject(json);
    json = fixture().toJson();
    json.put("id", "/absolute");
    reject(json);
  }

  @Test
  public void rejectsUnknownRenderOptions() throws Exception {
    JSONObject json = fixture().toJson();
    json.put("crop", "executable");
    reject(json);
    json = fixture().toJson();
    json.put("filter", "remote");
    reject(json);
  }

  @Test
  public void rejectsIncompleteCurves() throws Exception {
    JSONObject json = fixture().toJson();
    json.put("curves", new JSONArray());
    reject(json);
    json = fixture().toJson();
    json.getJSONArray("curves").put(0, new JSONArray().put(1));
    reject(json);
  }

  @Test
  public void rejectsNonFiniteAndOutOfRangeCurves() throws Exception {
    for (Object invalid : new Object[] {-1, 101, "NaN", "Infinity"}) {
      JSONObject json = fixture().toJson();
      json.getJSONArray("curves").getJSONArray(0).put(0, invalid);
      reject(json);
    }
  }

  @Test
  public void boundsImportedTextWithoutInterpretingMarkup() throws Exception {
    JSONObject json = fixture().toJson();
    json.put("title", "x".repeat(500));
    json.put("caption", "<script>".repeat(100));
    PosterProject result = PosterProject.fromJson(json);
    assertEquals(100, result.title.length());
    assertEquals(400, result.caption.length());
    assertTrue(result.caption.startsWith("<script>"));
  }

  @Test
  public void textLimitsNeverSplitSupplementaryCharacters() {
    for (int limit : new int[] {90, 100, 400}) {
      String prefix = "x".repeat(limit - 1);
      assertEquals(prefix, PosterProject.limit(prefix + "🌳", limit));
      String complete = "x".repeat(limit - 2) + "🌳";
      assertEquals(complete, PosterProject.limit(complete + "extra", limit));
    }
    assertEquals("", PosterProject.limit("🌳", 0));
  }

  @Test
  public void changesInvalidateCurveSamplesAndClampLevels() {
    ToneCurves.CurvesValue curve = new ToneCurves.CurvesValue();
    curve.interpolateCurve();
    assertNotNull(curve.cachedDataPoints);
    PosterProject.setLevel(curve, 2, 120);
    assertNull(curve.cachedDataPoints);
    assertEquals(100, curve.midtonesLevel, 0);
    PosterProject.setLevel(curve, 0, -4);
    assertEquals(0, curve.blacksLevel, 0);
  }

  private void reject(JSONObject json) {
    try {
      PosterProject.fromJson(json);
      fail("Invalid project accepted");
    } catch (JSONException expected) {
      assertNotNull(expected.getMessage());
    }
  }
}
