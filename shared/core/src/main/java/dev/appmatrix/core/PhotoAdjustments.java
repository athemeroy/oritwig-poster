// SPDX-License-Identifier: GPL-3.0-or-later
package dev.appmatrix.core;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.view.View;
import android.widget.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Shared cancelable photo adjustment workflow; it does not own product state. */
public final class PhotoAdjustments {
  public interface Callback {
    void onApplied(Bitmap bitmap);
  }

  private PhotoAdjustments() {}

  private static ToneCurves.CurvesValue channel(ToneCurves.CurvesToolValue c) {
    switch (c.activeType) {
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

  public static ToneCurves.CurvesToolValue copy(ToneCurves.CurvesToolValue source) {
    ToneCurves.CurvesToolValue out = new ToneCurves.CurvesToolValue();
    ToneCurves.CurvesValue[]
        from = {source.luminanceCurve, source.redCurve, source.greenCurve, source.blueCurve},
        to = {out.luminanceCurve, out.redCurve, out.greenCurve, out.blueCurve};
    for (int i = 0; i < 4; i++) {
      to[i].blacksLevel = from[i].blacksLevel;
      to[i].shadowsLevel = from[i].shadowsLevel;
      to[i].midtonesLevel = from[i].midtonesLevel;
      to[i].highlightsLevel = from[i].highlightsLevel;
      to[i].whitesLevel = from[i].whitesLevel;
    }
    out.activeType = source.activeType;
    return out;
  }

  public static void edit(Activity activity, Bitmap original, Callback callback) {
    if (original == null || original.isRecycled())
      throw new IllegalArgumentException("A readable image is required");
    ToneCurves.CurvesToolValue curves = new ToneCurves.CurvesToolValue();
    Bitmap small = BitmapTransforms.resize(original, 720);
    ExecutorService worker = Executors.newSingleThreadExecutor();
    AtomicInteger revision = new AtomicInteger();
    boolean[] syncing = {false};
    LinearLayout root = new LinearLayout(activity);
    root.setOrientation(LinearLayout.VERTICAL);
    int p = Theme.dp(activity, 16);
    root.setPadding(p, p, p, p);
    ImageView preview = new ImageView(activity);
    preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
    preview.setImageBitmap(small);
    preview.setContentDescription("Photo adjustment preview");
    root.addView(preview, new LinearLayout.LayoutParams(-1, Theme.dp(activity, 190)));
    TextView hint = new TextView(activity);
    hint.setText("Adjust light or a color channel. Drag the curve or use the five sliders.");
    hint.setPadding(0, p, 0, p / 2);
    root.addView(hint);
    Spinner channels = new Spinner(activity);
    channels.setContentDescription("Curve channel");
    channels.setAdapter(
        new ArrayAdapter<>(
            activity,
            android.R.layout.simple_spinner_dropdown_item,
            new String[] {"Light", "Red", "Green", "Blue"}));
    root.addView(channels);
    PhotoFilterCurvesControl control = new PhotoFilterCurvesControl(activity, curves);
    root.addView(control, new LinearLayout.LayoutParams(-1, Theme.dp(activity, 170)));
    String[] names = {"Blacks", "Shadows", "Midtones", "Highlights", "Whites"};
    SeekBar[] sliders = new SeekBar[5];
    TextView[] labels = new TextView[5];
    for (int i = 0; i < 5; i++) {
      labels[i] = new TextView(activity);
      root.addView(labels[i]);
      sliders[i] = new SeekBar(activity);
      sliders[i].setMax(100);
      sliders[i].setContentDescription(names[i]);
      sliders[i].setMinimumHeight(Theme.dp(activity, 48));
      root.addView(sliders[i]);
    }
    ScrollView scroll = new ScrollView(activity);
    scroll.addView(root);
    AlertDialog dialog =
        new AlertDialog.Builder(activity)
            .setTitle("Photo curves")
            .setView(scroll)
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Reset", null)
            .setPositiveButton("Apply", null)
            .create();
    Runnable refreshSliders =
        () -> {
          syncing[0] = true;
          ToneCurves.CurvesValue c = channel(curves);
          float[] values = {
            c.blacksLevel, c.shadowsLevel, c.midtonesLevel, c.highlightsLevel, c.whitesLevel
          };
          for (int i = 0; i < 5; i++) {
            sliders[i].setProgress(Math.round(values[i]));
            labels[i].setText(names[i] + " · " + Math.round(values[i]));
          }
          syncing[0] = false;
          control.invalidate();
        };
    Runnable render =
        () -> {
          int requested = revision.incrementAndGet();
          ToneCurves.CurvesToolValue snapshot = copy(curves);
          worker.execute(
              () -> {
                if (requested != revision.get()) return;
                try {
                  Bitmap result = BitmapTransforms.applyCurves(small, snapshot);
                  activity.runOnUiThread(
                      () -> {
                        if (dialog.isShowing()
                            && !activity.isDestroyed()
                            && requested == revision.get()) preview.setImageBitmap(result);
                      });
                } catch (RuntimeException ignored) {
                  activity.runOnUiThread(
                      () -> {
                        if (dialog.isShowing())
                          hint.setText("Preview unavailable. Reset the adjustment and try again.");
                      });
                }
              });
        };
    for (int i = 0; i < 5; i++) {
      final int index = i;
      sliders[i].setOnSeekBarChangeListener(
          new SeekBar.OnSeekBarChangeListener() {
            public void onStartTrackingTouch(SeekBar b) {}

            public void onStopTrackingTouch(SeekBar b) {}

            public void onProgressChanged(SeekBar b, int v, boolean user) {
              if (syncing[0] || !user) return;
              ToneCurves.CurvesValue c = channel(curves);
              switch (index) {
                case 0:
                  c.blacksLevel = v;
                  break;
                case 1:
                  c.shadowsLevel = v;
                  break;
                case 2:
                  c.midtonesLevel = v;
                  break;
                case 3:
                  c.highlightsLevel = v;
                  break;
                case 4:
                  c.whitesLevel = v;
                  break;
              }
              c.interpolateCurve();
              labels[index].setText(names[index] + " · " + v);
              control.invalidate();
              render.run();
            }
          });
    }
    channels.setOnItemSelectedListener(
        new AdapterView.OnItemSelectedListener() {
          public void onNothingSelected(AdapterView<?> a) {}

          public void onItemSelected(AdapterView<?> a, View v, int position, long id) {
            curves.activeType = position;
            refreshSliders.run();
          }
        });
    control.setDelegate(
        () -> {
          refreshSliders.run();
          render.run();
        });
    refreshSliders.run();
    dialog.setOnDismissListener(
        d -> {
          revision.incrementAndGet();
          worker.shutdownNow();
        });
    dialog.setOnShowListener(
        d -> {
          dialog
              .getButton(AlertDialog.BUTTON_NEUTRAL)
              .setOnClickListener(
                  v -> {
                    ToneCurves.CurvesValue c = channel(curves);
                    c.blacksLevel = 0;
                    c.shadowsLevel = 25;
                    c.midtonesLevel = 50;
                    c.highlightsLevel = 75;
                    c.whitesLevel = 100;
                    c.interpolateCurve();
                    refreshSliders.run();
                    render.run();
                  });
          dialog
              .getButton(AlertDialog.BUTTON_POSITIVE)
              .setOnClickListener(
                  v -> {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                    hint.setText("Applying photo adjustment…");
                    ToneCurves.CurvesToolValue snapshot = copy(curves);
                    worker.execute(
                        () -> {
                          try {
                            Bitmap result = BitmapTransforms.applyCurves(original, snapshot);
                            activity.runOnUiThread(
                                () -> {
                                  if (dialog.isShowing() && !activity.isDestroyed()) {
                                    dialog.dismiss();
                                    callback.onApplied(result);
                                  }
                                });
                          } catch (RuntimeException e) {
                            activity.runOnUiThread(
                                () -> {
                                  if (dialog.isShowing()) {
                                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                                    hint.setText(
                                        "Could not apply the adjustment. Try a smaller image.");
                                  }
                                });
                          }
                        });
                  });
        });
    dialog.show();
  }
}
