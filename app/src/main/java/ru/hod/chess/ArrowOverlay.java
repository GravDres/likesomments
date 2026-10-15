package ru.hod.chess;

import android.content.Context;
import android.graphics.*;
import android.hardware.input.InputManager;
import android.os.Build;
import android.view.*;
import ru.hod.chess.core.ArrowGeometry;
import ru.hod.chess.core.BoardVision;

/** Main-thread-only renderer. This window never accepts a touch or keyboard focus. */
final class ArrowOverlay {
    private final WindowManager windows;
    private final ArrowView view;
    private final WindowManager.LayoutParams params;
    private boolean attached;

    ArrowOverlay(Context context) {
        windows = context.getSystemService(WindowManager.class);
        view = new ArrowView(context);
        params = new WindowManager.LayoutParams(1, 1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.LEFT;
        // Android 12+ blocks touches through overly opaque untrusted overlays,
        // even when their pixels are transparent. Respect the platform's limit.
        float limit = .8f;
        if (Build.VERSION.SDK_INT >= 31) {
            limit = context.getSystemService(InputManager.class)
                    .getMaximumObscuringOpacityForTouch();
        }
        params.alpha = Math.min(.60f, Math.max(0, limit * .9f));
        if (Build.VERSION.SDK_INT >= 30) {
            params.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        } else if (Build.VERSION.SDK_INT >= 28) {
            params.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
    }

    void show(ArrowGeometry geometry) {
        params.x = geometry.region.left();
        params.y = geometry.region.top();
        params.width = params.height = geometry.region.size();
        view.geometry = geometry;
        view.outline = null;
        if (!attached) {
            windows.addView(view, params);
            attached = true;
        } else {
            windows.updateViewLayout(view, params);
        }
        view.setVisibility(View.VISIBLE);
        view.invalidate();
    }

    void showOutline(BoardVision.Region region) {
        params.x = region.left(); params.y = region.top();
        params.width = params.height = region.size();
        view.geometry = null; view.outline = region;
        if (!attached) { windows.addView(view, params); attached = true; }
        else windows.updateViewLayout(view, params);
        view.setVisibility(View.VISIBLE); view.invalidate();
    }

    void hide() {
        view.setVisibility(View.INVISIBLE);
    }

    void close() {
        if (attached) {
            try { windows.removeView(view); } catch (IllegalArgumentException ignored) {}
            attached = false;
        }
    }

    private static final class ArrowView extends View {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final int[] origin = new int[2];
        ArrowGeometry geometry;
        BoardVision.Region outline;

        ArrowView(Context c) {
            super(c);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override protected void onDraw(Canvas canvas) {
            if (outline != null) {
                getLocationOnScreen(origin);
                float left = outline.left() - origin[0], top = outline.top() - origin[1];
                float cell = outline.size() / 8f;
                paint.setColor(Ui.ACCENT); paint.setStrokeWidth(Ui.dp(getContext(), 1.5f));
                paint.setStyle(Paint.Style.STROKE);
                for (int i = 0; i <= 8; i++) {
                    canvas.drawLine(left + i * cell, top, left + i * cell, top + outline.size(), paint);
                    canvas.drawLine(left, top + i * cell, left + outline.size(), top + i * cell, paint);
                }
                return;
            }
            ArrowGeometry g = geometry;
            if (g == null) return;
            getLocationOnScreen(origin);
            canvas.save();
            canvas.translate(-origin[0], -origin[1]);
            paint.setColor(0xffc2ef67);
            paint.setStrokeWidth(g.stroke);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStyle(Paint.Style.STROKE);
            canvas.drawCircle(g.start.x(), g.start.y(), g.sourceRadius, paint);
            canvas.drawLine(g.start.x(), g.start.y(), g.neck.x(), g.neck.y(), paint);
            paint.setStyle(Paint.Style.FILL);
            Path tip = new Path();
            tip.moveTo(g.end.x(), g.end.y());
            tip.lineTo(g.headLeft.x(), g.headLeft.y());
            tip.lineTo(g.headRight.x(), g.headRight.y());
            tip.close();
            canvas.drawPath(tip, paint);
            if (g.move.promotion() != '\0') {
                canvas.drawCircle(g.end.x(), g.end.y(), g.cell * .23f, paint);
                paint.setColor(Ui.BG);
                paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                paint.setTextSize(g.cell * .32f);
                paint.setTextAlign(Paint.Align.CENTER);
                float baseline = g.end.y() - (paint.ascent() + paint.descent()) / 2;
                canvas.drawText(String.valueOf(Character.toUpperCase(g.move.promotion())),
                        g.end.x(), baseline, paint);
            }
            canvas.restore();
        }
    }
}
