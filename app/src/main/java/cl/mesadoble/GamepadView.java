package cl.mesadoble;

import android.content.Context;
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
 */
public class GamepadView extends View {

    public static final int JOY = 0, DPAD = 1, BTN = 2;

    public interface Sink {
        /** Botón (o dirección de la cruceta) presionado o soltado; x,y = lugar relativo (0..1) a tocar en el principal. */
        void button(String id, boolean down, float x, float y);
        /** Joystick: fase 0 empieza, 1 se mueve, 2 termina. cx,cy centro relativo; r radio relativo; nx,ny en -1..1. */
        void joy(String id, int phase, float cx, float cy, float r, float nx, float ny);
        void layoutChanged(String json);
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
    private Ctl dragCtl;
    private float dragOffX, dragOffY, pinchStart, sizeStart;

    public GamepadView(Context c, boolean night) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        bg = night ? 0xFF121920 : 0xFFCDD5DC;
        ctlColor = night ? 0xFF2A3848 : 0xFFFFFFFF;
        ctlDown = night ? 0xFF5CC3CF : 0xFF1F7F8C;
        accent = ctlDown;
        fg = night ? 0xFFE6EDF3 : 0xFF1D2733;
        muted = night ? 0xFF8798A9 : 0xFF5B6B7A;
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
    public void setEditing(boolean e) { editing = e; releaseAll(); invalidate(); }

    public void resetLayout() {
        ctls.clear();
        ctls.add(new Ctl("joy", JOY, "", 0.16f, 0.62f, 0.42f));
        ctls.add(new Ctl("dpad", DPAD, "", 0.40f, 0.70f, 0.34f));
        ctls.add(new Ctl("a", BTN, "A", 0.86f, 0.70f, 0.17f));
        ctls.add(new Ctl("b", BTN, "B", 0.94f, 0.50f, 0.17f));
        ctls.add(new Ctl("x", BTN, "X", 0.78f, 0.50f, 0.17f));
        ctls.add(new Ctl("y", BTN, "Y", 0.86f, 0.30f, 0.17f));
        ctls.add(new Ctl("l", BTN, "L", 0.10f, 0.14f, 0.15f));
        ctls.add(new Ctl("r", BTN, "R", 0.90f, 0.12f, 0.15f));
        ctls.add(new Ctl("select", BTN, "Select", 0.44f, 0.16f, 0.12f));
        ctls.add(new Ctl("start", BTN, "Start", 0.58f, 0.16f, 0.12f));
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
        if (editing) {
            text.setTextSize(13 * dp);
            text.setColor(muted);
            cv.drawText("Edición: arrastra para mover · pellizca con dos dedos para cambiar tamaño",
                    getWidth() / 2f, getHeight() - 14 * dp, text);
        }
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
                if (c.label.length() > 1) cv.drawRoundRect(new RectF(x - r, y - r * 0.55f, x + r, y + r * 0.55f), r * 0.55f, r * 0.55f, fill);
                else cv.drawCircle(x, y, r, fill);
                text.setColor(on ? bg : fg);
                text.setTextSize(c.label.length() > 1 ? Math.min(r * 0.5f, 14 * dp) : r * 0.8f);
                cv.drawText(c.label, x, y + text.getTextSize() * 0.36f, text);
            }
            if (editing) cv.drawCircle(x, y, r + 4 * dp, dash);
        }
    }

    // ---------------- toques ----------------

    @Override public boolean onTouchEvent(MotionEvent e) {
        return editing ? editTouch(e) : playTouch(e);
    }

    private boolean editTouch(MotionEvent e) {
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN) {
            dragCtl = ctlAt(e.getX(), e.getY());
            if (dragCtl != null) { dragOffX = e.getX() - cx(dragCtl); dragOffY = e.getY() - cy(dragCtl); }
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

    private void releaseAll() {
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
