package cl.mesadoble;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Gamepad editable: joystick, cruceta (pad digital) y botones.
 * En modo edición cada control se arrastra para moverlo y se pellizca con dos dedos para cambiar su tamaño.
 * Cada control "toca" el principal en el mismo lugar relativo donde está en esta pantalla.
 * Al editar se puede pedir una captura de la pantalla del principal (el juego): queda de fondo, con los controles
 * encima semitransparentes, para ponerlos justo sobre los botones reales. El tamaño se cambia con − / + (control
 * elegido) o pellizcando.
 */
public class GamepadView extends View {

    public static final int JOY = 0, DPAD = 1, BTN = 2;

    public interface Sink {
        /** Botón (o dirección de la cruceta) presionado o soltado; x,y = lugar relativo (0..1) a tocar en el principal. */
        void button(String id, boolean down, float x, float y);
        /** Joystick: fase 0 empieza, 1 se mueve, 2 termina. cx,cy centro relativo; r radio relativo; nx,ny en -1..1. */
        void joy(String id, int phase, float cx, float cy, float r, float nx, float ny);
        void layoutChanged(String json);
        /** Pide al principal una captura de su pantalla (el juego) para ubicar los controles encima. */
        void shot();
    }

    static class Ctl {
        String id, label; int type; float x, y, s;
        Ctl(String id, int type, String label, float x, float y, float s) { this.id = id; this.type = type; this.label = label; this.x = x; this.y = y; this.s = s; }
    }

    private final List<Ctl> ctls = new ArrayList<>();
    private final Map<Integer, Ctl> owner = new HashMap<>();
    private final Map<Integer, String> dpadDir = new HashMap<>();
    private final Map<String, float[]> joyPos = new HashMap<>();
    private final Map<String, Boolean> down = new HashMap<>();
    private Sink sink;
    private boolean editing;
    private final float dp;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG),
            text = new Paint(Paint.ANTI_ALIAS_FLAG), dash = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int bg, ctlColor, ctlDown, fg, muted, accent;

    // edición
    private Bitmap backdrop;                 // captura del juego (pantalla del principal), solo se ve al editar
    private String note = "";                // aviso corto sobre la barra de edición
    private Ctl sel;                         // control elegido para cambiarle el tamaño
    private int tbHit = -1;                  // botón de la barra de edición que se está tocando
    private final RectF[] tb = {new RectF(), new RectF(), new RectF()};
    private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Runnable clearNote = () -> { note = ""; invalidate(); };
    private Ctl dragCtl;
    private float dragOffX, dragOffY, pinchStart, sizeStart;

    public GamepadView(Context c, boolean night) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        KbTheme th = KbTheme.of(c, night);
        bg = th.bg;
        ctlColor = th.key;
        ctlDown = th.accent;
        accent = ctlDown;
        fg = th.fg;
        muted = th.muted;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(2 * dp);
        dash.setStyle(Paint.Style.STROKE);
        dash.setStrokeWidth(1.5f * dp);
        dash.setColor(accent);
        dash.setPathEffect(new DashPathEffect(new float[]{6 * dp, 5 * dp}, 0));
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        resetLayout();
    }

    public void setSink(Sink s) { sink = s; }
    public boolean isEditing() { return editing; }
    public void setEditing(boolean e) { editing = e; if (!e) tbHit = -1; releaseAll(); invalidate(); }

    /** Captura de la pantalla del principal: se dibuja estirada sobre toda la vista (así la posición de la vista = la del principal). */
    public void setBackdrop(Bitmap b) { backdrop = b; note = ""; invalidate(); }

    /** Aviso corto (ms = 0: se queda hasta el próximo aviso o captura). */
    public void setNote(String s, long ms) {
        note = s == null ? "" : s;
        removeCallbacks(clearNote);
        if (ms > 0) postDelayed(clearNote, ms);
        invalidate();
    }

    public void resetLayout() {
        ctls.clear();
        // distribución estilo control de consola (horizontal)
        ctls.add(new Ctl("joy", JOY, "", 0.13f, 0.62f, 0.36f));       // joystick izquierdo
        ctls.add(new Ctl("dpad", DPAD, "", 0.33f, 0.80f, 0.28f));
        ctls.add(new Ctl("joyR", JOY, "", 0.66f, 0.78f, 0.30f));      // joystick derecho
        ctls.add(new Ctl("a", BTN, "A", 0.88f, 0.74f, 0.15f));
        ctls.add(new Ctl("b", BTN, "B", 0.95f, 0.56f, 0.15f));
        ctls.add(new Ctl("x", BTN, "X", 0.81f, 0.56f, 0.15f));
        ctls.add(new Ctl("y", BTN, "Y", 0.88f, 0.38f, 0.15f));
        ctls.add(new Ctl("l", BTN, "L1", 0.07f, 0.13f, 0.13f));
        ctls.add(new Ctl("l2", BTN, "L2", 0.19f, 0.13f, 0.13f));
        ctls.add(new Ctl("r2", BTN, "R2", 0.81f, 0.13f, 0.13f));
        ctls.add(new Ctl("r", BTN, "R1", 0.93f, 0.13f, 0.13f));
        ctls.add(new Ctl("l3", BTN, "L3", 0.13f, 0.33f, 0.11f));
        ctls.add(new Ctl("r3", BTN, "R3", 0.66f, 0.50f, 0.11f));
        ctls.add(new Ctl("select", BTN, "Select", 0.43f, 0.15f, 0.11f));
        ctls.add(new Ctl("start", BTN, "Start", 0.57f, 0.15f, 0.11f));
        invalidate();
    }

    public String toJson() {
        JSONArray a = new JSONArray();
        try {
            for (Ctl c : ctls) {
                JSONObject o = new JSONObject();
                o.put("id", c.id); o.put("x", (double) c.x); o.put("y", (double) c.y); o.put("s", (double) c.s);
                a.put(o);
            }
        } catch (Exception ignored) { }
        return a.toString();
    }

    public void fromJson(String s) {
        if (s == null || s.isEmpty()) return;
        try {
            JSONArray a = new JSONArray(s);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                for (Ctl c : ctls) if (c.id.equals(o.optString("id"))) {
                    c.x = (float) o.optDouble("x", c.x); c.y = (float) o.optDouble("y", c.y); c.s = (float) o.optDouble("s", c.s);
                }
            }
        } catch (Exception ignored) { }
        invalidate();
    }

    // ---------------- geometría ----------------

    private float unit() { return Math.min(getWidth(), getHeight()); }
    private float cx(Ctl c) { return c.x * getWidth(); }
    private float cy(Ctl c) { return c.y * getHeight(); }
    private float rad(Ctl c) { return c.s * unit() / 2; }

    private Ctl ctlAt(float x, float y) {
        Ctl best = null; double bd = Double.MAX_VALUE;
        for (Ctl c : ctls) {
            double d = Math.hypot(x - cx(c), y - cy(c));
            if (d <= rad(c) * 1.15 && d < bd) { bd = d; best = c; }
        }
        return best;
    }

    // ---------------- dibujo ----------------

    @Override protected void onDraw(Canvas cv) {
        cv.drawColor(bg);
        boolean shot = editing && backdrop != null;
        if (shot) {
            cv.drawBitmap(backdrop, null, new RectF(0, 0, getWidth(), getHeight()), bmpPaint);
            cv.drawColor(0x55000000);
        }
        // con la captura de fondo los controles se ven semitransparentes, para ver el botón real debajo
        int layer = shot ? cv.saveLayerAlpha(0, 0, getWidth(), getHeight(), 170) : -1;
        for (Ctl c : ctls) {
            float x = cx(c), y = cy(c), r = rad(c);
            boolean on = Boolean.TRUE.equals(down.get(c.id));
            if (c.type == JOY) {
                fill.setColor(ctlColor);
                cv.drawCircle(x, y, r, fill);
                stroke.setColor(muted);
                cv.drawCircle(x, y, r * 0.98f, stroke);
                float[] p = joyPos.get(c.id);
                float kx = x + (p != null ? p[0] * r * 0.6f : 0), ky = y + (p != null ? p[1] * r * 0.6f : 0);
                fill.setColor(p != null ? ctlDown : muted);
                cv.drawCircle(kx, ky, r * 0.4f, fill);
            } else if (c.type == DPAD) {
                fill.setColor(ctlColor);
                float t = r * 0.36f;
                cv.drawRoundRect(new RectF(x - r, y - t, x + r, y + t), 8 * dp, 8 * dp, fill);
                cv.drawRoundRect(new RectF(x - t, y - r, x + t, y + r), 8 * dp, 8 * dp, fill);
                fill.setColor(ctlDown);
                for (String dir : dpadDir.values()) {
                    if ("up".equals(dir)) cv.drawRoundRect(new RectF(x - t, y - r, x + t, y - t), 8 * dp, 8 * dp, fill);
                    if ("down".equals(dir)) cv.drawRoundRect(new RectF(x - t, y + t, x + t, y + r), 8 * dp, 8 * dp, fill);
                    if ("left".equals(dir)) cv.drawRoundRect(new RectF(x - r, y - t, x - t, y + t), 8 * dp, 8 * dp, fill);
                    if ("right".equals(dir)) cv.drawRoundRect(new RectF(x + t, y - t, x + r, y + t), 8 * dp, 8 * dp, fill);
                }
                fill.setColor(muted);
                Path tri = new Path();
                float a = t * 0.45f;
                tri.moveTo(x, y - r + a); tri.lineTo(x - a, y - r + 2 * a); tri.lineTo(x + a, y - r + 2 * a); tri.close();
                tri.moveTo(x, y + r - a); tri.lineTo(x - a, y + r - 2 * a); tri.lineTo(x + a, y + r - 2 * a); tri.close();
                tri.moveTo(x - r + a, y); tri.lineTo(x - r + 2 * a, y - a); tri.lineTo(x - r + 2 * a, y + a); tri.close();
                tri.moveTo(x + r - a, y); tri.lineTo(x + r - 2 * a, y - a); tri.lineTo(x + r - 2 * a, y + a); tri.close();
                cv.drawPath(tri, fill);
            } else {
                fill.setColor(on ? ctlDown : ctlColor);
                boolean wide = c.label.length() > 2;
                if (wide) cv.drawRoundRect(new RectF(x - r, y - r * 0.55f, x + r, y + r * 0.55f), r * 0.55f, r * 0.55f, fill);
                else cv.drawCircle(x, y, r, fill);
                text.setColor(on ? bg : fg);
                text.setTextSize(wide ? Math.min(r * 0.5f, 14 * dp) : c.label.length() == 2 ? r * 0.62f : r * 0.8f);
                cv.drawText(c.label, x, y + text.getTextSize() * 0.36f, text);
            }
            if (editing) {
                if (c == sel) { stroke.setColor(accent); cv.drawCircle(x, y, r + 7 * dp, stroke); }
                cv.drawCircle(x, y, r + 4 * dp, dash);
            }
        }
        if (layer >= 0) cv.restoreToCount(layer);
        if (editing) drawToolbar(cv);
    }

    private String nameOf(Ctl c) {
        switch (c.id) {
            case "joy": return "Joystick izquierdo";
            case "joyR": return "Joystick derecho";
            case "dpad": return "Cruceta";
            default: return c.label;
        }
    }

    private void layoutToolbar() {
        float bh = 40 * dp, y = getHeight() - bh - 10 * dp, small = 48 * dp, big = 132 * dp, gap = 8 * dp;
        float total = small * 2 + big + gap * 2, x0 = (getWidth() - total) / 2f;
        tb[0].set(x0, y, x0 + small, y + bh);
        tb[1].set(x0 + small + gap, y, x0 + 2 * small + gap, y + bh);
        tb[2].set(x0 + 2 * small + 2 * gap, y, x0 + total, y + bh);
    }

    /** Barra de edición: − y + cambian el tamaño del control elegido; Captura pide la pantalla del juego al principal. */
    private void drawToolbar(Canvas cv) {
        layoutToolbar();
        String[] lb = {"−", "+", backdrop == null ? "Captura del juego" : "Nueva captura"};
        for (int i = 0; i < 3; i++) {
            fill.setColor(i == tbHit ? ctlDown : ctlColor);
            cv.drawRoundRect(tb[i], 12 * dp, 12 * dp, fill);
            stroke.setColor(muted);
            cv.drawRoundRect(tb[i], 12 * dp, 12 * dp, stroke);
            text.setColor(i == tbHit ? bg : fg);
            text.setTextSize(i < 2 ? 22 * dp : 12 * dp);
            cv.drawText(lb[i], tb[i].centerX(), tb[i].centerY() + text.getTextSize() * 0.36f, text);
        }
        String msg = !note.isEmpty() ? note
                : sel != null ? nameOf(sel) + " · tamaño " + Math.round(sel.s * 100) + "%"
                : "Toca un control para elegirlo · arrastra para moverlo · − / + o pellizca para el tamaño";
        text.setColor(muted);
        text.setTextSize(12 * dp);
        cv.drawText(msg, getWidth() / 2f, tb[0].top - 8 * dp, text);
    }

    private void toolbarTap(int i) {
        if (i == 2) { setNote("Pidiendo la captura al principal…", 0); if (sink != null) sink.shot(); return; }
        if (sel == null) { setNote("Toca primero un control para elegirlo", 2500); return; }
        sel.s = Math.max(0.08f, Math.min(0.7f, sel.s * (i == 0 ? 0.9f : 1.1f)));
        if (sink != null) sink.layoutChanged(toJson());
    }

    // ---------------- toques ----------------

    @Override public boolean onTouchEvent(MotionEvent e) {
        return editing ? editTouch(e) : playTouch(e);
    }

    private boolean editTouch(MotionEvent e) {
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN) {
            layoutToolbar();
            tbHit = -1;
            for (int i = 0; i < 3; i++) if (tb[i].contains(e.getX(), e.getY())) tbHit = i;
            if (tbHit >= 0) { toolbarTap(tbHit); invalidate(); return true; }
            dragCtl = ctlAt(e.getX(), e.getY());
            if (dragCtl != null) { sel = dragCtl; dragOffX = e.getX() - cx(dragCtl); dragOffY = e.getY() - cy(dragCtl); }
            invalidate();
        } else if (tbHit >= 0) {
            if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) { tbHit = -1; invalidate(); }
        } else if (a == MotionEvent.ACTION_POINTER_DOWN && dragCtl != null && e.getPointerCount() == 2) {
            pinchStart = (float) Math.hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1));
            sizeStart = dragCtl.s;
        } else if (a == MotionEvent.ACTION_MOVE && dragCtl != null) {
            if (e.getPointerCount() >= 2 && pinchStart > 0) {
                float d = (float) Math.hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1));
                dragCtl.s = Math.max(0.08f, Math.min(0.7f, sizeStart * d / pinchStart));
            } else {
                dragCtl.x = Math.max(0.03f, Math.min(0.97f, (e.getX() - dragOffX) / getWidth()));
                dragCtl.y = Math.max(0.05f, Math.min(0.95f, (e.getY() - dragOffY) / getHeight()));
            }
            invalidate();
        } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
            if (dragCtl != null && sink != null) sink.layoutChanged(toJson());
            dragCtl = null; pinchStart = 0;
        } else if (a == MotionEvent.ACTION_POINTER_UP) {
            pinchStart = 0;
        }
        return true;
    }

    private boolean playTouch(MotionEvent e) {
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_POINTER_DOWN) {
            int i = e.getActionIndex(), pid = e.getPointerId(i);
            Ctl c = ctlAt(e.getX(i), e.getY(i));
            if (c == null) return true;
            owner.put(pid, c);
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            if (c.type == BTN) { down.put(c.id, true); if (sink != null) sink.button(c.id, true, c.x, c.y); }
            else if (c.type == JOY) joyUpdate(c, e.getX(i), e.getY(i), 0);
            else dpadUpdate(c, pid, e.getX(i), e.getY(i));
            invalidate();
        } else if (a == MotionEvent.ACTION_MOVE) {
            for (int i = 0; i < e.getPointerCount(); i++) {
                int pid = e.getPointerId(i);
                Ctl c = owner.get(pid);
                if (c == null) continue;
                if (c.type == JOY) joyUpdate(c, e.getX(i), e.getY(i), 1);
                else if (c.type == DPAD) dpadUpdate(c, pid, e.getX(i), e.getY(i));
            }
            invalidate();
        } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_POINTER_UP) {
            int pid = e.getPointerId(e.getActionIndex());
            Ctl c = owner.remove(pid);
            if (c != null) release(c, pid);
            invalidate();
        } else if (a == MotionEvent.ACTION_CANCEL) {
            releaseAll();
        }
        return true;
    }

    private void release(Ctl c, int pid) {
        if (c.type == BTN) { down.put(c.id, false); if (sink != null) sink.button(c.id, false, c.x, c.y); }
        else if (c.type == JOY) {
            joyPos.remove(c.id);
            if (sink != null) sink.joy(c.id, 2, c.x, c.y, c.s / 2, 0, 0);
        } else {
            String old = dpadDir.remove(pid);
            if (old != null && sink != null) { float[] t = dirTarget(c, old); sink.button("dpad_" + old, false, t[0], t[1]); }
        }
    }

    public void releaseAll() {
        for (Map.Entry<Integer, Ctl> en : new HashMap<>(owner).entrySet()) release(en.getValue(), en.getKey());
        owner.clear(); dpadDir.clear(); joyPos.clear(); down.clear();
        invalidate();
    }

    private void joyUpdate(Ctl c, float x, float y, int phase) {
        float r = rad(c);
        float nx = (x - cx(c)) / r, ny = (y - cy(c)) / r;
        float len = (float) Math.hypot(nx, ny);
        if (len > 1) { nx /= len; ny /= len; }
        joyPos.put(c.id, new float[]{nx, ny});
        if (sink != null) sink.joy(c.id, phase, c.x, c.y, c.s / 2, nx, ny);
    }

    private float[] dirTarget(Ctl c, String dir) {
        float off = c.s / 2 * 0.65f, w = getWidth(), h = getHeight(), u = unit();
        float ox = "left".equals(dir) ? -off : "right".equals(dir) ? off : 0;
        float oy = "up".equals(dir) ? -off : "down".equals(dir) ? off : 0;
        return new float[]{c.x + ox * u / w, c.y + oy * u / h};
    }

    private void dpadUpdate(Ctl c, int pid, float x, float y) {
        float dx = x - cx(c), dy = y - cy(c);
        String dir = Math.hypot(dx, dy) < rad(c) * 0.2f ? null
                : Math.abs(dx) > Math.abs(dy) ? (dx > 0 ? "right" : "left") : (dy > 0 ? "down" : "up");
        String old = dpadDir.get(pid);
        if (dir != null && dir.equals(old)) return;
        if (old != null && sink != null) { float[] t = dirTarget(c, old); sink.button("dpad_" + old, false, t[0], t[1]); }
        if (dir != null) {
            dpadDir.put(pid, dir);
            if (sink != null) { float[] t = dirTarget(c, dir); sink.button("dpad_" + dir, true, t[0], t[1]); }
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        } else dpadDir.remove(pid);
    }
}
