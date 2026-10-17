package cl.mesadoble;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;

import java.util.function.IntConsumer;

/** Selector de color de DUAL-Draw: cuadro de saturación/brillo, barra de tono y muestra. */
final class ColorWheel extends View {

    private final float[] hsv = new float[3];
    private final float d;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private RectF sq, bar, prev;
    private int touching;   // 1 cuadro, 2 barra
    /** Se llama al soltar el dedo con el color nuevo (panel de colores del principal). */
    IntConsumer onPick;
    private int fixedH;

    void setColor(int c) { Color.colorToHSV(c, hsv); invalidate(); }
    void setFixedHeight(int h) { fixedH = h; requestLayout(); }

    ColorWheel(Activity a, int color) {
        super(a);
        d = a.getResources().getDisplayMetrics().density;
        Color.colorToHSV(color, hsv);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(3 * d);
        ring.setColor(Color.WHITE);
    }

    int color() { return Color.HSVToColor(hsv); }

    @Override protected void onMeasure(int w, int h) {
        int W = MeasureSpec.getSize(w);
        setMeasuredDimension(W, fixedH > 0 ? fixedH : Math.round(Math.min(W * 0.62f, 300 * d)));
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        float g = 12 * d;
        float barW = 34 * d, prevW = 56 * d;
        sq = new RectF(0, 0, w - barW - prevW - 2 * g, h);
        bar = new RectF(sq.right + g, 0, sq.right + g + barW, h);
        prev = new RectF(bar.right + g, 0, w, prevW);
    }

    @Override protected void onDraw(Canvas c) {
        if (sq == null) return;
        int hue = Color.HSVToColor(new float[]{hsv[0], 1, 1});
        p.setShader(new LinearGradient(sq.left, 0, sq.right, 0, Color.WHITE, hue, Shader.TileMode.CLAMP));
        c.drawRoundRect(sq, 10 * d, 10 * d, p);
        p.setShader(new LinearGradient(0, sq.top, 0, sq.bottom, 0x00000000, 0xFF000000, Shader.TileMode.CLAMP));
        c.drawRoundRect(sq, 10 * d, 10 * d, p);
        int[] hues = new int[7];
        for (int i = 0; i < 7; i++) hues[i] = Color.HSVToColor(new float[]{i * 60f % 360, 1, 1});
        p.setShader(new LinearGradient(0, bar.top, 0, bar.bottom, hues, null, Shader.TileMode.CLAMP));
        c.drawRoundRect(bar, 10 * d, 10 * d, p);
        p.setShader(null);
        p.setColor(color());
        c.drawRoundRect(prev, 12 * d, 12 * d, p);
        float sx = sq.left + hsv[1] * sq.width(), sy = sq.top + (1 - hsv[2]) * sq.height();
        c.drawCircle(sx, sy, 9 * d, ring);
        float hy = bar.top + hsv[0] / 360f * bar.height();
        c.drawRoundRect(new RectF(bar.left - 3 * d, hy - 4 * d, bar.right + 3 * d, hy + 4 * d), 4 * d, 4 * d, ring);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) touching = sq.contains(x, y) ? 1 : bar.contains(x, y) ? 2 : 0;
        if (touching == 1) {
            hsv[1] = Math.max(0, Math.min(1, (x - sq.left) / sq.width()));
            hsv[2] = 1 - Math.max(0, Math.min(1, (y - sq.top) / sq.height()));
        } else if (touching == 2) hsv[0] = Math.max(0, Math.min(359.9f, (y - bar.top) / bar.height() * 360f));
        getParent().requestDisallowInterceptTouchEvent(touching != 0);
        if (e.getActionMasked() == MotionEvent.ACTION_UP && touching != 0 && onPick != null) onPick.accept(color());
        invalidate();
        return true;
    }

    /** Abre el selector; al tocar "Listo" entrega el color. */
    static void pick(Activity a, int color, IntConsumer done) {
        ColorWheel w = new ColorWheel(a, color);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        // colores rápidos
        LinearLayout quick = new LinearLayout(a);
        int[] base = {0xFF1B1B1B, 0xFFFFFFFF, 0xFFE53935, 0xFFFB8C00, 0xFFFDD835, 0xFF43A047, 0xFF1E88E5, 0xFF8E24AA, 0xFF6D4C41};
        float d = a.getResources().getDisplayMetrics().density;
        for (int c : base) {
            View v = new View(a);
            android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
            g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            g.setColor(c);
            g.setStroke(Math.round(1 * d), 0x33000000);
            v.setBackground(g);
            v.setOnClickListener(x -> { Color.colorToHSV(c, w.hsv); w.invalidate(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Math.round(30 * d), 1);
            lp.setMargins(Math.round(3 * d), 0, Math.round(3 * d), Math.round(12 * d));
            quick.addView(v, lp);
        }
        box.addView(quick);
        box.addView(w);
        new Ui.Sheet(a).title("Color").view(box)
                .button("Cancelar", false, null)
                .button("Listo", true, () -> done.accept(w.color()))
                .show();
    }
}
