// SPDX-License-Identifier: GPL-3.0-or-later
package dev.appmatrix.core;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.*;
import java.nio.charset.StandardCharsets;

public final class About {
  private About() {}

  public static void show(Activity activity) {
    String text =
        "Oritwig · 0.1.0\n\n"
            + "Independent offline Android apps, licensed under GPL-3.0-or-later.\n\n"
            + "Telegram Android attribution\n"
            + "Tone-curve interpolation and the interactive curve control are adapted from Telegram"
            + " for Android, copyright Nikolai Kudashov, 2013–2018, GPL-2.0-or-later. Color"
            + " processing includes a Java CPU adaptation of Telegram's HSL/RGB curve shader."
            + " Upstream revision: f2908b14133bbffbf7ab04f641ecb5bfaf533242.\n\n"
            + "What changed\n"
            + "Removed Telegram account, networking and protocol dependencies; isolated the curve"
            + " models and control; added accessible sliders and CPU bitmap processing; fixed"
            + " continuous redraw and curve-cache behavior. CPU sampling is deterministic and is"
            + " not claimed bit-identical to Telegram's GPU output. Product screens, persistence,"
            + " image import/export and these independent app workflows are new App Matrix"
            + " code.\n\n"
            + "No Telegram logos, service access or endorsement. See the repository for exact"
            + " source paths, modifications, matching build instructions and license notices.\n\n"
            + "Markor editing attribution\n"
            + "Field Journal notes undo/redo adapts Markor's public-domain TextViewUndoRedo and"
            + " Gregor Santner's 2018–2025 findDiff helper (Unlicense selected from Unlicense OR"
            + " CC0-1.0). App Matrix adds bounded, session-only history and reliable"
            + " replay/lifecycle handling. Upstream revision:"
            + " 8d657fd20fff71719d782a3bc375c6f982d09b19. Undo history is not logged or saved."
            + " Markor is not affiliated with or endorsing these apps.\n\n"
            + "Privacy\n"
            + "No network permission, analytics or sign-in. Export destinations chosen in Android"
            + " may sync files externally. Images are normalized without original EXIF/GPS. Local"
            + " deletion cannot retract an earlier export or backup. Local data is not encrypted by"
            + " this app; device storage protection is supplied by Android.\n\n";
    TextView body = new TextView(activity);
    body.setText(text);
    body.setTextSize(15);
    body.setTextIsSelectable(true);
    int p = Theme.dp(activity, 20);
    body.setPadding(p, p, p, p);
    ScrollView scroll = new ScrollView(activity);
    scroll.addView(body);
    new AlertDialog.Builder(activity)
        .setTitle("About Oritwig")
        .setView(scroll)
        .setNegativeButton("Close", null)
        .setNeutralButton("License text", (d, w) -> showLicense(activity))
        .setPositiveButton(
            "Source code",
            (d, w) -> {
              try {
                activity.startActivity(
                    new Intent(Intent.ACTION_VIEW, Uri.parse(sourceUrl(activity))));
              } catch (ActivityNotFoundException e) {
                Toast.makeText(activity, "Source: " + sourceUrl(activity), Toast.LENGTH_LONG)
                    .show();
              }
            })
        .show();
  }

  /** Each independent app supplies the repository containing its complete corresponding source. */
  private static String sourceUrl(Activity activity) {
    try {
      Bundle metadata =
          activity
              .getPackageManager()
              .getApplicationInfo(activity.getPackageName(), PackageManager.GET_META_DATA)
              .metaData;
      if (metadata != null) {
        String value = metadata.getString("dev.oritwig.source_url");
        if (value != null && value.startsWith("https://")) return value;
      }
    } catch (PackageManager.NameNotFoundException ignored) {
      // The library's source remains available if the host omitted its application metadata.
    }
    return "https://github.com/athemeroy/oritwig-core";
  }

  private static void showLicense(Activity activity) {
    StringBuilder text = new StringBuilder();
    for (String file :
        new String[] {
          "licenses/GPL-3.0.txt",
          "licenses/Telegram-GPL-2.0.txt",
          "licenses/Markor-Public-Domain.txt",
          "licenses/Markor-Unlicense.txt",
          "licenses/Markor-CC0-1.0.txt"
        }) {
      try (InputStream input = activity.getAssets().open(file);
          BufferedReader reader =
              new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) text.append(line).append('\n');
        text.append("\n\n");
      } catch (IOException e) {
        text.append("License text unavailable; see the source repository.\n");
      }
    }
    TextView body = new TextView(activity);
    body.setText(text);
    body.setTextIsSelectable(true);
    int p = Theme.dp(activity, 16);
    body.setPadding(p, p, p, p);
    ScrollView scroll = new ScrollView(activity);
    scroll.addView(body);
    new AlertDialog.Builder(activity)
        .setTitle("Open-source licenses")
        .setView(scroll)
        .setPositiveButton("Close", null)
        .show();
  }
}
