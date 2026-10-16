package ru.hod.chess;

import android.content.Context;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import java.util.function.Consumer;
import ru.hod.chess.core.BoardVision;
import ru.hod.chess.core.ImageViewport;

/** A prefilled verification screen is used only when automatic selection is uncertain. */
final class BoardSelector {
    record Choice(BoardVision.Region region, boolean whiteBottom) {}
    private final WindowManager windows;
    private final FrameLayout root;
    private final Bitmap bitmap;
    private boolean closed;

    BoardSelector(Context context, int[] pixels, int width, int height,
                  BoardVision.Region initial, boolean whiteBottom,
                  Consumer<Choice> done, Runnable cancelled) {
        windows = context.getSystemService(WindowManager.class);
        bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
        root = new FrameLayout(context);
        root.setBackgroundColor(Color.BLACK);
        Selection view = new Selection(context, bitmap, initial);
        root.addView(view, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout instructions = Ui.col(context);
        Ui.pad(instructions, 16);
        instructions.setBackground(Ui.shape(0xe6101512, Ui.dp(context, 16)));
        instructions.addView(Ui.bold(context, initial == null
                ? "Обведи только 64 клетки доски" : "Проверь найденную сетку", 17, Ui.INK));
        Switch side = new Switch(context);
        side.setText("Белые фигуры снизу"); side.setTextColor(Ui.INK); side.setChecked(whiteBottom);
        instructions.addView(side);
        FrameLayout.LayoutParams title = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        title.setMargins(Ui.dp(context, 16), Ui.dp(context, 48), Ui.dp(context, 16), 0);
        root.addView(instructions, title);
        LinearLayout controls = Ui.row(context);
        Ui.pad(controls, 12);
        controls.setBackground(Ui.shape(Ui.BG, Ui.dp(context, 20)));
        Button cancel = Ui.button(context, "Отмена", false, () -> { close(); cancelled.run(); });
        Button confirm = Ui.button(context, "Готово", true, () -> {
            BoardVision.Region region = view.region();
            if (region == null) { Toast.makeText(context, "Проверь границы доски", Toast.LENGTH_SHORT).show(); return; }
            close(); done.accept(new Choice(region, side.isChecked()));
        });
        controls.addView(cancel, new LinearLayout.LayoutParams(0, Ui.dp(context, 52), 1));
        controls.addView(new View(context), new LinearLayout.LayoutParams(Ui.dp(context, 10), 1));
        controls.addView(confirm, new LinearLayout.LayoutParams(0, Ui.dp(context, 52), 1));
        FrameLayout.LayoutParams bottom = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        bottom.setMargins(Ui.dp(context, 16), 0, Ui.dp(context, 16), Ui.dp(context, 40));
        root.addView(controls, bottom);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(-1, -1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        windows.addView(root, lp);
    }

    void close() {
        if (closed) return;
        closed = true;
        try { windows.removeViewImmediate(root); } catch (IllegalArgumentException ignored) {}
        bitmap.recycle();
    }

    private static final class Selection extends View {
        final Bitmap image;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint bitmapPaint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
        final Paint shadePaint=new Paint();
        final BoardVision.Region initial;
        float ax, ay, bx, by;
        float downX,downY,anchorX,anchorY,oldAx,oldAy,oldBx,oldBy;
        boolean selected,drawing,wasSelected;

        Selection(Context c, Bitmap image, BoardVision.Region initial) {
            super(c); this.image = image; this.initial = initial;
            bitmapPaint.setAlpha(255);shadePaint.setColor(0x88000000);
            setContentDescription("Проверка найденной шахматной доски");
        }

        @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            if (!selected && initial != null && initial.inside(image.getWidth(),image.getHeight())) {
                // Keep the selection in screenshot coordinates. Drawing and
                // touch input use the same uniform mapping, even with nav insets.
                ax = initial.left(); ay = initial.top();
                bx = ax + initial.size(); by = ay + initial.size();
                selected = true;
            }
        }

        RectF selection() {
            float size = Math.min(Math.abs(bx - ax), Math.abs(by - ay));
            float left = bx >= ax ? ax : ax - size, top = by >= ay ? ay : ay - size;
            return new RectF(left, top, left + size, top + size);
        }

        BoardVision.Region region() {
            if (!selected) return null;
            RectF r = selection();
            BoardVision.Region region = new BoardVision.Region(Math.round(r.left),
                    Math.round(r.top), Math.round(r.width()));
            return region.inside(image.getWidth(), image.getHeight()) ? region : null;
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if(getWidth()<=0||getHeight()<=0)return false;
            ImageViewport viewport=viewport();ImageViewport.Point point=viewport.toSource(e.getX(),e.getY());
            float x=point.x(),y=point.y();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if(!viewport.contains(e.getX(),e.getY()))return false;
                    downX=e.getX();downY=e.getY();anchorX=x;anchorY=y;drawing=false;
                    oldAx=ax;oldAy=ay;oldBx=bx;oldBy=by;wasSelected=selected;break;
                case MotionEvent.ACTION_MOVE: case MotionEvent.ACTION_UP:
                    if(!drawing&&Math.hypot(e.getX()-downX,e.getY()-downY)>ViewConfiguration.get(getContext()).getScaledTouchSlop()){
                        drawing=true;selected=true;ax=anchorX;ay=anchorY;
                    }
                    if(drawing){bx=x;by=y;}break;
                case MotionEvent.ACTION_CANCEL:
                    ax=oldAx;ay=oldAy;bx=oldBx;by=oldBy;selected=wasSelected;drawing=false;break;
                default: return true;
            }
            invalidate(); if (e.getActionMasked() == MotionEvent.ACTION_UP) performClick(); return true;
        }

        @Override public boolean performClick() { super.performClick(); return true; }

        @Override protected void onDraw(Canvas canvas) {
            canvas.drawColor(Color.BLACK);
            if(image.isRecycled()||getWidth()<=0||getHeight()<=0)return;
            ImageViewport viewport=viewport();RectF imageRect=rect(viewport.image());
            canvas.drawBitmap(image, null, imageRect, bitmapPaint);
            canvas.drawRect(imageRect, shadePaint);
            if (!selected) return;
            RectF source=selection();RectF r=rect(viewport.toView(source.left,source.top,source.width(),source.height()));
            canvas.save(); canvas.clipRect(r);
            canvas.drawBitmap(image, null, imageRect, bitmapPaint); canvas.restore();
            paint.setColor(Ui.ACCENT); paint.setStrokeWidth(Ui.dp(getContext(), 2)); paint.setStyle(Paint.Style.STROKE); canvas.drawRect(r, paint);
            paint.setStrokeWidth(Ui.dp(getContext(), 1)); paint.setColor(0x99c2ef97);
            for (int i = 1; i < 8; i++) {
                canvas.drawLine(r.left + r.width() * i / 8, r.top, r.left + r.width() * i / 8, r.bottom, paint);
                canvas.drawLine(r.left, r.top + r.height() * i / 8, r.right, r.top + r.height() * i / 8, paint);
            }
            paint.setStyle(Paint.Style.FILL);
        }
        private ImageViewport viewport(){return new ImageViewport(image.getWidth(),image.getHeight(),getWidth(),getHeight());}
        private static RectF rect(ImageViewport.Rect r){return new RectF(r.left(),r.top(),r.right(),r.bottom());}
    }
}
