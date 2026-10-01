/* SPDX-License-Identifier: GPL-3.0-or-later */
package dev.appmatrix.poster;

import dev.appmatrix.core.ToneCurves;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** The complete non-destructive edit recipe. Sources are never rewritten by edits. */
final class PosterProject {
  static final int SCHEMA = 1;
  String id = UUID.randomUUID().toString();
  String source;
  String title = "Untitled poster";
  String caption = "";
  String crop = "original";
  String filter = "original";
  int turns;
  long updated = System.currentTimeMillis();
  ToneCurves.CurvesToolValue curves = new ToneCurves.CurvesToolValue();

  PosterProject copy() {
    try {
      return fromJson(toJson());
    } catch (JSONException e) {
      throw new IllegalStateException(e);
    }
  }

  JSONObject toJson() throws JSONException {
    JSONObject j = new JSONObject();
    j.put("schema", SCHEMA);
    j.put("id", id);
    j.put("source", source);
    j.put("title", title);
    j.put("caption", caption);
    j.put("crop", crop);
    j.put("filter", filter);
    j.put("turns", turns);
    j.put("updated", updated);
    JSONArray values = new JSONArray();
    for (int c = 0; c < 4; c++) {
      ToneCurves.CurvesValue v = channel(curves, c);
      JSONArray a = new JSONArray();
      for (float f : levels(v)) a.put((double) f);
      values.put(a);
    }
    j.put("curves", values);
    return j;
  }

  static PosterProject fromJson(JSONObject j) throws JSONException {
    if (j.getInt("schema") != SCHEMA) throw new JSONException("Unsupported project version");
    PosterProject p = new PosterProject();
    p.id = safeId(j.getString("id"));
    p.source = safeId(j.getString("source"));
    p.title = limit(j.optString("title", "Untitled poster"), 100);
    p.caption = limit(j.optString("caption", ""), 400);
    p.crop = oneOf(j.optString("crop"), "original", "square", "portrait", "wide");
    p.filter = oneOf(j.optString("filter"), "original", "mono", "warm", "cool");
    p.turns = ((j.optInt("turns") % 4) + 4) % 4;
    p.updated = Math.max(0, j.optLong("updated", System.currentTimeMillis()));
    JSONArray values = j.getJSONArray("curves");
    if (values.length() != 4) throw new JSONException("Invalid curve channels");
    for (int c = 0; c < 4; c++) {
      JSONArray a = values.getJSONArray(c);
      if (a.length() != 5) throw new JSONException("Invalid curve points");
      for (int i = 0; i < 5; i++) {
        double x = a.getDouble(i);
        if (Double.isNaN(x) || Double.isInfinite(x) || x < 0 || x > 100)
          throw new JSONException("Invalid curve level");
        setLevel(channel(p.curves, c), i, (float) x);
      }
    }
    return p;
  }

  static String safeId(String s) throws JSONException {
    if (s == null
        || !s.matches(
            "[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}"))
      throw new JSONException("Invalid project identifier");
    return s;
  }

  static String oneOf(String s, String... choices) throws JSONException {
    for (String c : choices) if (c.equals(s)) return s;
    throw new JSONException("Unsupported edit option");
  }

  static String limit(String s, int max) {
    if (s.length() <= max) return s;
    int end = Math.max(0, max);
    if (end > 0
        && Character.isHighSurrogate(s.charAt(end - 1))
        && Character.isLowSurrogate(s.charAt(end))) end--;
    return s.substring(0, end);
  }

  static ToneCurves.CurvesValue channel(ToneCurves.CurvesToolValue c, int index) {
    switch (index) {
      case 1:
        return c.redCurve;
      case 2:
        return c.greenCurve;
      case 3:
        return c.blueCurve;
      default:
        return c.luminanceCurve;
    }
  }

  static float[] levels(ToneCurves.CurvesValue v) {
    return new float[] {
      v.blacksLevel, v.shadowsLevel, v.midtonesLevel, v.highlightsLevel, v.whitesLevel
    };
  }

  static void setLevel(ToneCurves.CurvesValue v, int index, float value) {
    value = Math.max(0, Math.min(100, value));
    switch (index) {
      case 0:
        v.blacksLevel = value;
        break;
      case 1:
        v.shadowsLevel = value;
        break;
      case 2:
        v.midtonesLevel = value;
        break;
      case 3:
        v.highlightsLevel = value;
        break;
      case 4:
        v.whitesLevel = value;
        break;
      default:
        throw new IllegalArgumentException("Unknown level");
    }
    v.cachedDataPoints = null;
  }
}
