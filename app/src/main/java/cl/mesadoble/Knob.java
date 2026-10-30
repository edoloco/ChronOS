package cl.mesadoble;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

/**
 * Perilla giratoria (como la de un amplificador): se gira arrastrando hacia arriba o abajo (o en círculo).
 * Doble toque vuelve al valor de partida. Muestra el valor al centro.
 */
public class Knob extends View {

    public interface Change { void onChange(int value, boolean done); }

    private final float dp;
    private int min, max, value, home;
    private String label = "", suffix = "%";
    private Change change;
    private int ink = 0xFF1D2733, track = 0x33000000, accent = 0xFFF57C1F, face = 0xFFFFFFFF;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), t = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final float START = 135, SWEEP = 270;

    public Knob(Context c, int min, int max, int value) {
        super(c);
        this.min = min; this.max = max; this.value = value; this.home = value;
        dp = c.getResources().getDisplayMetrics().density;
        t.setTextAlign(Paint.Align.CENTER);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    }

    public Knob label(String l) { label = l; invalidate(); return this; }
    public Knob suffix(String s) { suffix = s; invalidate(); return this; }
    public Knob colors(int ink, int track, int face) { this.ink = ink; this.track = track; this.face = face; invalidate(); return this; }
    public void setChange(Change c) { change = c; }
    public int value() { return value; }
    public void setValue(int v) { value = Math.max(min, Math.min(max, v)); invalidate(); }

    @Override protected void onMeasure(int ws, int hs) {
        int w = resolveSize(Math.round(150 * dp), ws);
        setMeasuredDimension(w, resolveSize(Math.round(w + 26 * dp), hs));
    }

    private float frac() { return (value - min) / (float) Math.max(1, max - min); }

    @Override protected void onDraw(Canvas c) {
        float W = getWidth(), r = Math.min(W, getHeight() - 26 * dp) / 2 - 10 * dp, cx = W / 2, cy = r + 10 * dp;
        RectF arc = new RectF(cx - r, cy - r, cx + r, cy + r);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(7 * dp);
        p.setColor(track);
        c.drawArc(arc, START, SWEEP, false, p);
        p.setColor(accent);
        c.drawArc(arc, START, SWEEP * frac(), false, p);
        // marca del 100 % (sin cambio)
        float hf = (home - min) / (float) Math.max(1, max - min), ha = (float) Math.toRadians(START + SWEEP * hf);
        p.setStrokeWidth(2 * dp);
        p.setColor(ink);
        c.drawLine(cx + (float) Math.cos(ha) * (r + 7 * dp), cy + (float) Math.sin(ha) * (r + 7 * dp),
                cx + (float) Math.cos(ha) * (r + 12 * dp), cy + (float) Math.sin(ha) * (r + 12 * dp), p);
        // el botón de la perilla, con su rayita
        float kr = r - 14 * dp;
        p.setStyle(Paint.Style.FILL);
        p.setShadowLayer(6 * dp, 0, 2 * dp, 0x44000000);
        p.setColor(face);
        c.drawCircle(cx, cy, kr, p);
        p.clearShadowLayer();
        float a = (float) Math.toRadians(START + SWEEP * frac());
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(3.5f * dp);
        p.setColor(accent);
        c.drawLine(cx + (float) Math.cos(a) * kr * 0.45f, cy + (float) Math.sin(a) * kr * 0.45f,
                cx + (float) Math.cos(a) * kr * 0.85f, cy + (float) Math.sin(a) * kr * 0.85f, p);
        t.setColor(ink);
        t.setTextSize(Math.min(20 * dp, kr * 0.62f));
        c.drawText(value + suffix, cx, cy + t.getTextSize() * 0.35f, t);
        t.setTextSize(14 * dp);
        c.drawText(label, cx, getHeight() - 6 * dp, t);
    }

    private float lastY, lastAng;
    private long lastTap;

    private float angle(MotionEvent e) {
        float cx = getWidth() / 2f, cy = Math.min(getWidth(), getHeight() - 26 * dp) / 2;
        return (float) Math.toDegrees(Math.atan2(e.getY() - cy, e.getX() - cx));
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                lastY = e.getY(); lastAng = angle(e);
                long now = System.currentTimeMillis();
                if (now - lastTap < 300) { setValue(home); if (change != null) change.onChange(value, true); }
                lastTap = now;
                return true;
            case MotionEvent.ACTION_MOVE: {
                // girar en círculo o arrastrar arriba/abajo: lo que mueva más
                float da = angle(e) - lastAng;
                if (da > 180) da -= 360; if (da < -180) da += 360;
                float byAng = da / SWEEP * (max - min), byY = (lastY - e.getY()) / (1.6f * dp);
                float d = Math.abs(byAng) > Math.abs(byY) ? byAng : byY;
                if (Math.abs(d) >= 1) {
                    int nv = Math.max(min, Math.min(max, value + Math.round(d)));
                    lastY = e.getY(); lastAng = angle(e);
                    if (nv != value) { value = nv; invalidate(); if (change != null) change.onChange(value, false); }
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (change != null) change.onChange(value, true);
                return true;
        }
        return true;
    }
}
