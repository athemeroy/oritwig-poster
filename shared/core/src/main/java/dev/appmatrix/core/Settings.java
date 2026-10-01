// SPDX-License-Identifier: GPL-3.0-or-later
package dev.appmatrix.core;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.*;

public final class Settings {
  private Settings() {}

  private static SharedPreferences prefs(Context c) {
    return c.getSharedPreferences("matrix.preferences", Context.MODE_PRIVATE);
  }

  public static String readTheme(Context c) {
    return prefs(c).getString("theme", "system");
  }

  public static void writeTheme(Context c, String value) {
    if (!"system".equals(value) && !"light".equals(value) && !"dark".equals(value))
      throw new IllegalArgumentException("Unknown theme");
    if (!prefs(c).edit().putString("theme", value).commit())
      throw new IllegalStateException("Could not save theme");
  }

  public static int readExportQuality(Context c) {
    return prefs(c).getInt("exportQuality", 90);
  }

  public static void writeExportQuality(Context c, int value) {
    if (value != 80 && value != 90 && value != 100)
      throw new IllegalArgumentException("Quality must be 80, 90 or 100");
    if (!prefs(c).edit().putInt("exportQuality", value).commit())
      throw new IllegalStateException("Could not save quality");
  }

  public static void show(Activity activity, Runnable onChanged) {
    LinearLayout box = new LinearLayout(activity);
    box.setOrientation(LinearLayout.VERTICAL);
    int pad = Theme.dp(activity, 20);
    box.setPadding(pad, pad, pad, pad);
    TextView label = new TextView(activity);
    label.setText("Appearance");
    label.setTextSize(17);
    box.addView(label);
    RadioGroup group = new RadioGroup(activity);
    String[] values = {"system", "light", "dark"};
    String[] names = {"Use device theme", "Light", "Dark"};
    int[] identifiers = new int[3];
    for (int i = 0; i < 3; i++) {
      RadioButton radio = new RadioButton(activity);
      identifiers[i] = View.generateViewId();
      radio.setId(identifiers[i]);
      radio.setText(names[i]);
      radio.setMinHeight(Theme.dp(activity, 48));
      group.addView(radio);
      if (values[i].equals(readTheme(activity))) radio.setChecked(true);
    }
    box.addView(group);
    TextView local = new TextView(activity);
    local.setText(
        "Your work stays in this app until you choose an export destination. No account, analytics"
            + " or Telegram connection is used. Uninstalling removes local projects and entries.");
    local.setTextSize(14);
    local.setPadding(0, pad, 0, pad);
    box.addView(local);
    Button about = new Button(activity);
    about.setText("About & open-source licenses");
    about.setOnClickListener(v -> About.show(activity));
    box.addView(about);
    AlertDialog dialog =
        new AlertDialog.Builder(activity)
            .setTitle("Settings")
            .setView(box)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save", null)
            .create();
    dialog.setOnShowListener(
        v ->
            dialog
                .getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(
                    button -> {
                      try {
                        int selected = 0;
                        for (int i = 0; i < identifiers.length; i++) {
                          if (group.getCheckedRadioButtonId() == identifiers[i]) selected = i;
                        }
                        writeTheme(activity, values[selected]);
                        dialog.dismiss();
                        if (onChanged != null) onChanged.run();
                      } catch (RuntimeException ex) {
                        Toast.makeText(
                                activity,
                                "Settings could not be saved. Try again.",
                                Toast.LENGTH_LONG)
                            .show();
                      }
                    }));
    dialog.show();
  }
}
