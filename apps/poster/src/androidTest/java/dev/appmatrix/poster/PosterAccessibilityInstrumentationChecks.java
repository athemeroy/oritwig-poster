/* SPDX-License-Identifier: GPL-3.0-or-later */
package dev.appmatrix.poster;

import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.EditText;
import android.widget.TextView;
import java.lang.reflect.Method;

/** Framework semantics checks, not a substitute for a TalkBack listening pass. */
final class PosterAccessibilityInstrumentationChecks {
  private PosterAccessibilityInstrumentationChecks() {}

  /** Run on the main thread; creates detached fields and never changes a project. */
  static int run(MainActivity activity) throws Exception {
    Method edit =
        MainActivity.class.getDeclaredMethod(
            "edit", String.class, String.class, int.class, boolean.class);
    Method label =
        MainActivity.class.getDeclaredMethod("fieldLabel", String.class, int.class, EditText.class);
    edit.setAccessible(true);
    label.setAccessible(true);
    String sample = "Observación 🌳 東京 العربية";
    int previousId = View.NO_ID;
    int passed = 0;
    for (boolean multiline : new boolean[] {false, true}) {
      String hint = multiline ? "Add a few words…" : "Name this poster";
      EditText field =
          (EditText) edit.invoke(activity, hint, sample, multiline ? 400 : 100, multiline);
      check(
          field.getId() != View.NO_ID && field.getId() != previousId,
          "Editable fields need unique label targets");
      previousId = field.getId();
      check(
          field.getContentDescription() == null,
          "A static description must not replace editable text");
      check(hint.contentEquals(field.getHint()), "Empty-field purpose must remain available");
      AccessibilityNodeInfo node = field.createAccessibilityNodeInfo();
      try {
        check(
            node.isEditable() && sample.contentEquals(node.getText()),
            "Accessibility node must expose the full Unicode value");
        check(node.getContentDescription() == null, "Accessibility node has a static override");
      } finally {
        node.recycle();
      }
      TextView heading =
          (TextView) label.invoke(activity, multiline ? "Caption" : "Project name", 14, field);
      check(
          heading.getLabelFor() == field.getId(), "Visible label is not associated with its field");
      passed += 6;
    }
    EditText boundary = (EditText) edit.invoke(activity, "Caption", "", 400, true);
    char[] prefix = new char[399];
    java.util.Arrays.fill(prefix, 'x');
    boundary.setText(new String(prefix) + "🌳");
    check(boundary.length() == 399, "Caption limit split a supplementary Unicode character");
    return passed + 1;
  }

  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
