/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package dev.appmatrix.core;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.text.TextPaint;
import android.view.MotionEvent;
import android.view.View;
import java.util.Locale;

/* Adapted for App Matrix on 2026-10-01 from Telegram Android commit
 * f2908b14133bbffbf7ab04f641ecb5bfaf533242. Standalone density/area helpers,
 * automatic layout bounds, clamped segments, symmetric gestures, accessibility,
 * explicit sample invalidation and removal of continuous redraw loop.
 * See third_party/TELEGRAM.md for exact source and license details.
 */
public class PhotoFilterCurvesControl extends View {
  private int dp(float value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }

  @Override
  protected void onSizeChanged(int w, int h, int oldw, int oldh) {
    super.onSizeChanged(w, h, oldw, oldh);
    setActualArea(dp(12), dp(12), Math.max(1, w - dp(24)), Math.max(1, h - dp(30)));
  }

  @Override
  public boolean performClick() {
    super.performClick();
    return true;
  }

  public interface PhotoFilterCurvesControlDelegate {
    void valueChanged();
  }

  private static final int CurvesSegmentNone = 0;
  private static final int CurvesSegmentBlacks = 1;
  private static final int CurvesSegmentShadows = 2;
  private static final int CurvesSegmentMidtones = 3;
  private static final int CurvesSegmentHighlights = 4;
  private static final int CurvesSegmentWhites = 5;

  private static final int GestureStateBegan = 1;
  private static final int GestureStateChanged = 2;
  private static final int GestureStateEnded = 3;
  private static final int GestureStateCancelled = 4;
  private static final int GestureStateFailed = 5;

  private int activeSegment = CurvesSegmentNone;

  private boolean isMoving;
  private boolean checkForMoving = true;

  private float lastX;
  private float lastY;

  private static final class Area {
    float x, y, width, height;
  }

  private Area actualArea = new Area();

  private Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private Paint paintDash = new Paint(Paint.ANTI_ALIAS_FLAG);
  private Paint paintCurve = new Paint(Paint.ANTI_ALIAS_FLAG);
  private TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
  private Path path = new Path();

  private PhotoFilterCurvesControlDelegate delegate;

  private ToneCurves.CurvesToolValue curveValue;

  public PhotoFilterCurvesControl(Context context, ToneCurves.CurvesToolValue value) {
    super(context);
    setWillNotDraw(false);
    setBackgroundColor(0xff17212d);
    setContentDescription(
        "Tone curve. Drag a vertical section up or down. Accessible sliders are below.");
    setFocusable(true);

    curveValue = value;

    paint.setColor(0x99ffffff);
    paint.setStrokeWidth(dp(1));
    paint.setStyle(Paint.Style.STROKE);

    paintDash.setColor(0x99ffffff);
    paintDash.setStrokeWidth(dp(2));
    paintDash.setStyle(Paint.Style.STROKE);

    paintCurve.setColor(0xffffffff);
    paintCurve.setStrokeWidth(dp(2));
    paintCurve.setStyle(Paint.Style.STROKE);

    textPaint.setColor(0xffbfbfbf);
    textPaint.setTextSize(dp(13));
  }

  public void setDelegate(PhotoFilterCurvesControlDelegate photoFilterCurvesControlDelegate) {
    delegate = photoFilterCurvesControlDelegate;
  }

  public void setActualArea(float x, float y, float width, float height) {
    actualArea.x = x;
    actualArea.y = y;
    actualArea.width = width;
    actualArea.height = height;
  }

  @Override
  public boolean onTouchEvent(MotionEvent event) {
    int action = event.getActionMasked();

    switch (action) {
      case MotionEvent.ACTION_POINTER_DOWN:
      case MotionEvent.ACTION_DOWN:
        {
          if (event.getPointerCount() == 1) {
            if (checkForMoving && !isMoving) {
              float locationX = event.getX();
              float locationY = event.getY();
              lastX = locationX;
              lastY = locationY;
              if (locationX >= actualArea.x
                  && locationX <= actualArea.x + actualArea.width
                  && locationY >= actualArea.y
                  && locationY <= actualArea.y + actualArea.height) {
                isMoving = true;
              }
              checkForMoving = false;
              if (isMoving) {
                getParent().requestDisallowInterceptTouchEvent(true);
                handlePan(GestureStateBegan, event);
              }
            }
          } else {
            if (isMoving) {
              handlePan(GestureStateEnded, event);
              checkForMoving = true;
              isMoving = false;
            }
          }
          break;
        }

      case MotionEvent.ACTION_POINTER_UP:
      case MotionEvent.ACTION_CANCEL:
      case MotionEvent.ACTION_UP:
        {
          if (isMoving) {
            handlePan(GestureStateEnded, event);
            isMoving = false;
          }
          checkForMoving = true;
          getParent().requestDisallowInterceptTouchEvent(false);
          if (action == MotionEvent.ACTION_UP) performClick();
          break;
        }

      case MotionEvent.ACTION_MOVE:
        {
          if (isMoving) {
            handlePan(GestureStateChanged, event);
          }
        }
    }
    return true;
  }

  private void handlePan(int state, MotionEvent event) {
    float locationX = event.getX();
    float locationY = event.getY();

    switch (state) {
      case GestureStateBegan:
        {
          selectSegmentWithPoint(locationX);
          break;
        }

      case GestureStateChanged:
        {
          float delta = Math.max(-2, Math.min(2, (lastY - locationY) / dp(8)));

          ToneCurves.CurvesValue curveValue = null;
          switch (this.curveValue.activeType) {
            case ToneCurves.CurvesToolValue.CurvesTypeLuminance:
              curveValue = this.curveValue.luminanceCurve;
              break;

            case ToneCurves.CurvesToolValue.CurvesTypeRed:
              curveValue = this.curveValue.redCurve;
              break;

            case ToneCurves.CurvesToolValue.CurvesTypeGreen:
              curveValue = this.curveValue.greenCurve;
              break;

            case ToneCurves.CurvesToolValue.CurvesTypeBlue:
              curveValue = this.curveValue.blueCurve;
              break;

            default:
              break;
          }

          switch (activeSegment) {
            case CurvesSegmentBlacks:
              curveValue.blacksLevel = Math.max(0, Math.min(100, curveValue.blacksLevel + delta));
              break;

            case CurvesSegmentShadows:
              curveValue.shadowsLevel = Math.max(0, Math.min(100, curveValue.shadowsLevel + delta));
              break;

            case CurvesSegmentMidtones:
              curveValue.midtonesLevel =
                  Math.max(0, Math.min(100, curveValue.midtonesLevel + delta));
              break;

            case CurvesSegmentHighlights:
              curveValue.highlightsLevel =
                  Math.max(0, Math.min(100, curveValue.highlightsLevel + delta));
              break;

            case CurvesSegmentWhites:
              curveValue.whitesLevel = Math.max(0, Math.min(100, curveValue.whitesLevel + delta));
              break;

            default:
              break;
          }

          curveValue.interpolateCurve();
          invalidate();

          if (delegate != null) {
            delegate.valueChanged();
          }

          lastX = locationX;
          lastY = locationY;
        }
        break;

      case GestureStateEnded:
      case GestureStateCancelled:
      case GestureStateFailed:
        {
          unselectSegments();
        }
        break;

      default:
        break;
    }
  }

  private void selectSegmentWithPoint(float pointx) {
    if (activeSegment != CurvesSegmentNone) {
      return;
    }
    float segmentWidth = actualArea.width / 5.0f;
    pointx -= actualArea.x;
    activeSegment = Math.max(1, Math.min(5, (int) Math.floor((pointx / segmentWidth) + 1)));
  }

  private void unselectSegments() {
    if (activeSegment == CurvesSegmentNone) {
      return;
    }
    activeSegment = CurvesSegmentNone;
  }

  @SuppressLint("DrawAllocation")
  @Override
  protected void onDraw(Canvas canvas) {
    float segmentWidth = actualArea.width / 5.0f;

    for (int i = 0; i < 4; i++) {
      canvas.drawLine(
          actualArea.x + segmentWidth + i * segmentWidth,
          actualArea.y,
          actualArea.x + segmentWidth + i * segmentWidth,
          actualArea.y + actualArea.height,
          paint);
    }

    canvas.drawLine(
        actualArea.x,
        actualArea.y + actualArea.height,
        actualArea.x + actualArea.width,
        actualArea.y,
        paintDash);

    ToneCurves.CurvesValue curvesValue = null;
    switch (curveValue.activeType) {
      case ToneCurves.CurvesToolValue.CurvesTypeLuminance:
        paintCurve.setColor(0xffffffff);
        curvesValue = curveValue.luminanceCurve;
        break;

      case ToneCurves.CurvesToolValue.CurvesTypeRed:
        paintCurve.setColor(0xffed3d4c);
        curvesValue = curveValue.redCurve;
        break;

      case ToneCurves.CurvesToolValue.CurvesTypeGreen:
        paintCurve.setColor(0xff10ee9d);
        curvesValue = curveValue.greenCurve;
        break;

      case ToneCurves.CurvesToolValue.CurvesTypeBlue:
        paintCurve.setColor(0xff3377fb);
        curvesValue = curveValue.blueCurve;
        break;

      default:
        break;
    }

    for (int a = 0; a < 5; a++) {
      String str;
      switch (a) {
        case 0:
          str = String.format(Locale.US, "%.2f", curvesValue.blacksLevel / 100.0f);
          break;
        case 1:
          str = String.format(Locale.US, "%.2f", curvesValue.shadowsLevel / 100.0f);
          break;
        case 2:
          str = String.format(Locale.US, "%.2f", curvesValue.midtonesLevel / 100.0f);
          break;
        case 3:
          str = String.format(Locale.US, "%.2f", curvesValue.highlightsLevel / 100.0f);
          break;
        case 4:
          str = String.format(Locale.US, "%.2f", curvesValue.whitesLevel / 100.0f);
          break;
        default:
          str = "";
          break;
      }
      float width = textPaint.measureText(str);
      canvas.drawText(
          str,
          actualArea.x + (segmentWidth - width) / 2 + segmentWidth * a,
          actualArea.y + actualArea.height - dp(4),
          textPaint);
    }

    float[] points = curvesValue.interpolateCurve();
    path.reset();
    for (int a = 0; a < points.length / 2; a++) {
      if (a == 0) {
        path.moveTo(
            actualArea.x + points[a * 2] * actualArea.width,
            actualArea.y + (1.0f - points[a * 2 + 1]) * actualArea.height);
      } else {
        path.lineTo(
            actualArea.x + points[a * 2] * actualArea.width,
            actualArea.y + (1.0f - points[a * 2 + 1]) * actualArea.height);
      }
    }

    canvas.drawPath(path, paintCurve);
  }
}
