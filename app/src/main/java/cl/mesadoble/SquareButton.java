package cl.mesadoble;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/**
 * Botón cuadrado con ícono y etiqueta. Su contenido gira en el lugar para quedar derecho
 * según cómo se sostiene el teléfono, sin mover el botón (como los íconos de una cámara).
 */
public class SquareButton extends View {

    public static final int NOTE = 0, IMAGE = 1, APPS = 2, KEYBOARD = 3, SETTINGS = 4, STATUS = 5,
            BACK = 6, HOME = 7, RECENTS = 8;

    private final int kind;
    private final String text;
    private final float dp;
    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG),
            fill = new Paint(Paint.ANTI_ALIAS_FLAG), label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int bgColor, bgPressed, fgColor, accent, warn;
    private final boolean primary;
    private float rot = 0;
    private ValueAnimator anim;
    private boolean pressed;
    private int statusLevel = 0;   // 0 sin conexión, 1 esperando, 2 conectado

    public SquareButton(Context c, int kind, String text, boolean primary, boolean night) {
        super(c);
        this.kind = kind;
        this.text = text;
        this.primary = primary;
        dp = c.getResources().getDisplayMetrics().density;
        accent = night ? 0xFF5CC3CF : 0xFF1F7F8C;
        warn = night ? 0xFFF0A26B : 0xFFB4541A;
        bgColor = primary ? accent : (night ? 0xFF223040 : 0xFFFFFFFF);
        bgPressed = night ? 0xFF2F4258 : 0xFFE2E8ED;
        fgColor = primary ? (night ? 0xFF18212B : 0xFFFFFFFF) : (night ? 0xFFE6EDF3 : 0xFF1D2733);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(2 * dp);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setColor(fgColor);
        fill.setColor(fgColor);
        label.setColor(fgColor);
        label.setTextAlign(Paint.Align.CENTER);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setTextSize(9.5f * dp);
        setContentDescription(text);
        setClickable(true);
        setFocusable(true);
    }

    public void setStatus(int level) { statusLevel = level; invalidate(); }

    /** Gira el contenido por el camino más corto. */
    public void setGlyphRotation(float target) {
        if (anim != null) anim.cancel();
        float from = rot, delta = ((target - from) % 360 + 540) % 360 - 180;
        anim = ValueAnimator.ofFloat(from, from + delta);
        anim.setDuration(220);
        anim.addUpdateListener(a -> { rot = (float) a.getAnimatedValue(); invalidate(); });
        anim.start();
    }

    @Override protected void onMeasure(int w, int h) {
        int s = Math.round(52 * dp);
        setMeasuredDimension(s, s);
    }

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

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), r = 12 * dp;
        bg.setColor(pressed && !primary ? bgPressed : bgColor);
        c.drawRoundRect(new RectF(0, 0, w, h), r, r, bg);
        c.save();
        c.rotate(rot, w / 2, h / 2);
        float cx = w / 2, cy = h * 0.42f, u = 7 * dp;   // u: unidad del ícono
        int ink = fgColor;
        if (kind == STATUS) ink = statusLevel == 2 ? accent : statusLevel == 1 ? warn : 0xFF8798A9;
        stroke.setColor(ink);
        fill.setColor(ink);
        Path p = new Path();
        switch (kind) {
            case NOTE:
                c.drawRoundRect(new RectF(cx - u, cy - u, cx + u, cy + u), 2 * dp, 2 * dp, stroke);
                c.drawLine(cx, cy - u * 0.5f, cx, cy + u * 0.5f, stroke);
                c.drawLine(cx - u * 0.5f, cy, cx + u * 0.5f, cy, stroke);
                break;
            case IMAGE:
                c.drawRoundRect(new RectF(cx - u * 1.2f, cy - u, cx + u * 1.2f, cy + u), 2 * dp, 2 * dp, stroke);
                p.moveTo(cx - u * 1.2f, cy + u * 0.7f); p.lineTo(cx - u * 0.3f, cy - u * 0.1f);
                p.lineTo(cx + u * 0.4f, cy + u * 0.5f); p.lineTo(cx + u * 1.2f, cy - u * 0.2f);
                c.drawPath(p, stroke);
                c.drawCircle(cx + u * 0.5f, cy - u * 0.45f, 1.6f * dp, fill);
                break;
            case APPS:
                for (int i = -1; i <= 1; i++) for (int j = -1; j <= 1; j++)
                    c.drawRoundRect(new RectF(cx + i * u * 0.8f - 2.6f * dp, cy + j * u * 0.8f - 2.6f * dp,
                            cx + i * u * 0.8f + 2.6f * dp, cy + j * u * 0.8f + 2.6f * dp), 1.2f * dp, 1.2f * dp, fill);
                break;
            case KEYBOARD:
                c.drawRoundRect(new RectF(cx - u * 1.4f, cy - u * 0.85f, cx + u * 1.4f, cy + u * 0.85f), 2 * dp, 2 * dp, stroke);
                for (int i = -2; i <= 2; i++) {
                    c.drawCircle(cx + i * u * 0.5f, cy - u * 0.3f, 1.2f * dp, fill);
                }
                c.drawLine(cx - u * 0.7f, cy + u * 0.35f, cx + u * 0.7f, cy + u * 0.35f, stroke);
                break;
            case SETTINGS:
                c.drawCircle(cx, cy, u * 0.45f, stroke);
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI / 4 * i;
                    c.drawLine(cx + (float) Math.cos(a) * u * 0.8f, cy + (float) Math.sin(a) * u * 0.8f,
                            cx + (float) Math.cos(a) * u * 1.15f, cy + (float) Math.sin(a) * u * 1.15f, stroke);
                }
                c.drawCircle(cx, cy, u * 0.8f, stroke);
                break;
            case BACK: {   // triángulo de "atrás", como la barra de Android
                Path t = new Path();
                t.moveTo(cx - u * 0.75f, cy); t.lineTo(cx + u * 0.55f, cy - u * 0.8f); t.lineTo(cx + u * 0.55f, cy + u * 0.8f); t.close();
                c.drawPath(t, stroke);
                break;
            }
            case HOME:
                c.drawCircle(cx, cy, u * 0.8f, stroke);
                break;
            case RECENTS:
                c.drawRoundRect(new RectF(cx - u * 0.7f, cy - u * 0.7f, cx + u * 0.7f, cy + u * 0.7f), 2.5f * dp, 2.5f * dp, stroke);
                break;
            case STATUS:
                for (int i = 1; i <= 2; i++) {
                    float rr = u * 0.55f * i;
                    c.drawArc(new RectF(cx - rr, cy + u * 0.5f - rr, cx + rr, cy + u * 0.5f + rr), 225, 90, false, stroke);
                }
                c.drawCircle(cx, cy + u * 0.5f, 2 * dp, fill);
                break;
        }
        label.setColor(kind == STATUS ? ink : fgColor);
        c.drawText(text, cx, h - 8 * dp, label);
        c.restore();
    }
}
