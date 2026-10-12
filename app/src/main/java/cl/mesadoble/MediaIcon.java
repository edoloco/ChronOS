package cl.mesadoble;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/** Botón redondo con un ícono de reproducción dibujado (play, pausa, siguiente, etc.). */
public class MediaIcon extends View {

    public static final int PLAY = 0, PAUSE = 1, PREV = 2, NEXT = 3, STOP = 4, BACK10 = 5, FWD10 = 6, CLOSE = 7,
            VOL_DOWN = 8, VOL_UP = 9, SLIDES = 10, ROTATE = 11, ZOOM_IN = 12, ZOOM_OUT = 13, MINIMIZE = 14;

    private int kind;
    private final float dp;
    private final int size;
    private int bgColor, fgColor, pressedColor;
    private boolean pressed;
    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG), ink = new Paint(Paint.ANTI_ALIAS_FLAG),
            stroke = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG);

    public MediaIcon(Context c, int kind, int sizeDp, int bgColor, int fgColor) {
        super(c);
        this.kind = kind;
        dp = c.getResources().getDisplayMetrics().density;
        size = Math.round(sizeDp * dp);
        this.bgColor = bgColor; this.fgColor = fgColor;
        pressedColor = (bgColor & 0x00FFFFFF) | Math.min(255, ((bgColor >>> 24) + 60)) << 24;
        ink.setColor(fgColor);
        stroke.setColor(fgColor);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(2.2f * dp);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        text.setColor(fgColor);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        setClickable(true);
    }

    public void setKind(int k) { if (kind != k) { kind = k; invalidate(); } }

    @Override protected void onMeasure(int w, int h) { setMeasuredDimension(size, size); }

    @Override public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: pressed = true; invalidate(); return true;
            case MotionEvent.ACTION_CANCEL: pressed = false; invalidate(); return true;
            case MotionEvent.ACTION_UP:
                pressed = false; invalidate();
                if (e.getX() >= 0 && e.getY() >= 0 && e.getX() <= getWidth() && e.getY() <= getHeight()) {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    performClick();
                }
                return true;
        }
        return true;
    }

    @Override public boolean performClick() { return super.performClick(); }

    private void tri(Canvas c, float cx, float cy, float s, boolean right) {
        Path p = new Path();
        if (right) { p.moveTo(cx - s * 0.6f, cy - s); p.lineTo(cx + s * 0.9f, cy); p.lineTo(cx - s * 0.6f, cy + s); }
        else { p.moveTo(cx + s * 0.6f, cy - s); p.lineTo(cx - s * 0.9f, cy); p.lineTo(cx + s * 0.6f, cy + s); }
        p.close();
        c.drawPath(p, ink);
    }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), cx = w / 2, cy = h / 2, r = Math.min(w, h) / 2;
        bg.setColor(pressed ? pressedColor : bgColor);
        c.drawCircle(cx, cy, r, bg);
        float u = r * 0.36f;
        switch (kind) {
            case PLAY: tri(c, cx + u * 0.15f, cy, u, true); break;
            case PAUSE:
                c.drawRoundRect(new RectF(cx - u * 0.75f, cy - u, cx - u * 0.2f, cy + u), 2 * dp, 2 * dp, ink);
                c.drawRoundRect(new RectF(cx + u * 0.2f, cy - u, cx + u * 0.75f, cy + u), 2 * dp, 2 * dp, ink);
                break;
            case PREV:
                tri(c, cx + u * 0.2f, cy, u * 0.8f, false);
                c.drawRect(cx - u * 0.95f, cy - u * 0.8f, cx - u * 0.65f, cy + u * 0.8f, ink);
                break;
            case NEXT:
                tri(c, cx - u * 0.2f, cy, u * 0.8f, true);
                c.drawRect(cx + u * 0.65f, cy - u * 0.8f, cx + u * 0.95f, cy + u * 0.8f, ink);
                break;
            case STOP: c.drawRoundRect(new RectF(cx - u * 0.75f, cy - u * 0.75f, cx + u * 0.75f, cy + u * 0.75f), 2 * dp, 2 * dp, ink); break;
            case BACK10: case FWD10: {
                boolean f = kind == FWD10;
                RectF arc = new RectF(cx - u * 1.1f, cy - u * 1.1f, cx + u * 1.1f, cy + u * 1.1f);
                c.drawArc(arc, f ? -60 : -120, f ? 300 : -300, false, stroke);
                float ax = f ? cx + u * 0.55f : cx - u * 0.55f, ay = cy - u * 0.95f;
                Path p = new Path();
                p.moveTo(ax + (f ? -u * 0.35f : u * 0.35f), ay - u * 0.3f); p.lineTo(ax, ay); p.lineTo(ax + (f ? -u * 0.35f : u * 0.35f), ay + u * 0.3f);
                c.drawPath(p, stroke);
                text.setTextSize(u * 0.95f);
                c.drawText("10", cx, cy + u * 0.35f, text);
                break;
            }
            case CLOSE:
                c.drawLine(cx - u * 0.7f, cy - u * 0.7f, cx + u * 0.7f, cy + u * 0.7f, stroke);
                c.drawLine(cx + u * 0.7f, cy - u * 0.7f, cx - u * 0.7f, cy + u * 0.7f, stroke);
                break;
            case MINIMIZE: c.drawLine(cx - u * 0.7f, cy + u * 0.3f, cx + u * 0.7f, cy + u * 0.3f, stroke); break;
            case VOL_DOWN: case VOL_UP: {
                Path p = new Path();
                p.moveTo(cx - u, cy - u * 0.35f); p.lineTo(cx - u * 0.5f, cy - u * 0.35f); p.lineTo(cx, cy - u * 0.85f);
                p.lineTo(cx, cy + u * 0.85f); p.lineTo(cx - u * 0.5f, cy + u * 0.35f); p.lineTo(cx - u, cy + u * 0.35f); p.close();
                c.drawPath(p, ink);
                c.drawLine(cx + u * 0.35f, cy, cx + u, cy, stroke);
                if (kind == VOL_UP) c.drawLine(cx + u * 0.675f, cy - u * 0.33f, cx + u * 0.675f, cy + u * 0.33f, stroke);
                break;
            }
            case SLIDES:
                c.drawRoundRect(new RectF(cx - u, cy - u * 0.75f, cx + u, cy + u * 0.75f), 2 * dp, 2 * dp, stroke);
                tri(c, cx + u * 0.1f, cy, u * 0.4f, true);
                break;
            case ROTATE:
                c.drawArc(new RectF(cx - u * 0.85f, cy - u * 0.85f, cx + u * 0.85f, cy + u * 0.85f), -80, 290, false, stroke);
                Path ar = new Path();
                ar.moveTo(cx + u * 0.05f, cy - u * 1.2f); ar.lineTo(cx + u * 0.45f, cy - u * 0.85f); ar.lineTo(cx + u * 0.05f, cy - u * 0.5f);
                c.drawPath(ar, stroke);
                break;
            case ZOOM_IN: case ZOOM_OUT:
                c.drawLine(cx - u * 0.75f, cy, cx + u * 0.75f, cy, stroke);
                if (kind == ZOOM_IN) c.drawLine(cx, cy - u * 0.75f, cx, cy + u * 0.75f, stroke);
                break;
        }
    }
}
