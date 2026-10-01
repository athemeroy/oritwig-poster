// SPDX-License-Identifier: GPL-3.0-or-later
package dev.appmatrix.core;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/** A compact visual foundation shared by independent product screens. */
public final class Theme {
  private Theme() {}

  public static boolean isDark(Context context) {
    String value = Settings.readTheme(context);
    return "dark".equals(value)
        || ("system".equals(value)
            && (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES);
  }

  public static void apply(Activity activity) {
    activity.setTheme(
        isDark(activity)
            ? android.R.style.Theme_Material_NoActionBar
            : android.R.style.Theme_Material_Light_NoActionBar);
  }

  public static int background(Context c) {
    return isDark(c) ? 0xff10171c : 0xfff4f6f4;
  }

  public static int surface(Context c) {
    return isDark(c) ? 0xff1b272e : Color.WHITE;
  }

  public static int text(Context c) {
    return isDark(c) ? 0xfff1f5f3 : 0xff142b2b;
  }

  public static int muted(Context c) {
    return isDark(c) ? 0xffb5c5c2 : 0xff526964;
  }

  public static int accent(Context c) {
    return isDark(c) ? 0xff7ee0bd : 0xff176f58;
  }

  public static int dp(Context c, float value) {
    return Math.round(value * c.getResources().getDisplayMetrics().density);
  }

  public static GradientDrawable card(Context c) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(surface(c));
    d.setCornerRadius(dp(c, 18));
    return d;
  }

  public static void style(Activity activity, View root) {
    root.setBackgroundColor(background(activity));
    activity.getWindow().setStatusBarColor(background(activity));
    activity.getWindow().setNavigationBarColor(background(activity));
    activity
        .getWindow()
        .getDecorView()
        .setSystemUiVisibility(
            isDark(activity)
                ? 0
                : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
  }

  public static void textTree(Context c, View view) {
    if (view instanceof TextView) ((TextView) view).setTextColor(text(c));
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) textTree(c, group.getChildAt(i));
    }
  }
}
