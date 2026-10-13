package cl.mesadoble;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.util.Base64;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Apps recientes dentro de la action bar (esquina inferior izquierda del extendido): una tira que se desliza bajo
 * una mira fija. Lo que queda en la mira es la app al frente en el principal ("Desktop" = la mesa).
 */
public class RecentsBar extends View {

    public interface Pick { void onPick(String pkg, String label); }

    static final class R { String pkg, label; Bitmap icon; }

    private final float dp;
    private final List<R> strip = new ArrayList<>();
    private final Set<String> notified = new HashSet<>();
    private String sig = "", active = "";
    private boolean loaded, access = true, scrubbing, moved;
    private float scroll, target, startScroll, downX, slot = 1;
    private long userUntil, pressAt;
    private VelocityTracker vt;
    private Pick pick;
    private int ink;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), bmp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG),
            txt = new Paint(Paint.ANTI_ALIAS_FLAG);

    public RecentsBar(Context c, int ink) {
        super(c);
        this.ink = ink;
        dp = c.getResources().getDisplayMetrics().density;
        txt.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    }

    public void setPick(Pick k) { pick = k; }
    public void setInk(int i) { ink = i; invalidate(); }
    public String sig() { return loaded ? sig : "?"; }

    private int indexOf(String pkg) {
        for (int i = 0; i < strip.size(); i++) if (strip.get(i).pkg.equals(pkg)) return i;
        return -1;
    }

    public void setNotified(JSONArray a) {
        notified.clear();
        for (int i = 0; a != null && i < a.length(); i++) notified.add(a.optString(i));
        invalidate();
    }

    /** Lista del principal (mensaje recList). */
    public void set(JSONObject m) {
        loaded = true;
        access = m.optBoolean("access", true);
        String s = m.optString("sig");
        if (!s.equals(sig)) {
            sig = s;
            if (strip.isEmpty()) { R d = new R(); d.pkg = ""; d.label = "Desktop"; strip.add(d); }
            Set<String> now = new HashSet<>();
            JSONArray a = m.optJSONArray("apps");
            for (int i = 0; a != null && i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                String pk = o.optString("p");
                now.add(pk);
                int k = indexOf(pk);
                R x = k >= 0 ? strip.get(k) : new R();
                x.label = o.optString("l");
                if (k < 0) {
                    x.pkg = pk;
                    try { byte[] b = Base64.decode(o.optString("i"), Base64.DEFAULT); x.icon = BitmapFactory.decodeByteArray(b, 0, b.length); }
                    catch (Exception ignored) { }
                    strip.add(x);
                }
            }
            String cur = strip.isEmpty() ? "" : strip.get(Math.max(0, Math.min(strip.size() - 1, Math.round(scroll)))).pkg;
            for (int i = strip.size() - 1; i > 0; i--) if (!now.contains(strip.get(i).pkg)) strip.remove(i);
            int ci = indexOf(cur);
            if (ci >= 0 && !scrubbing) { scroll += ci - Math.round(scroll); target = ci; }
        }
        active = m.optString("active");
        if (!scrubbing && SystemClock.uptimeMillis() > userUntil) { int k = indexOf(active); if (k >= 0) target = k; }
        invalidate();
    }

    @Override protected void onDraw(Canvas c) {
        float W = getWidth(), H = getHeight();
        if (!loaded || !access || strip.size() <= 1) {
            txt.setColor((ink & 0x00FFFFFF) | 0xAA000000);
            txt.setTextSize(11.5f * dp);
            String t = !loaded ? "Apps recientes…" : !access ? "Toca: permitir apps recientes" : "Sin apps recientes";
            c.drawText(t, 10 * dp, H / 2 + 4 * dp, txt);
            return;
        }
        float icon = Math.min(H - 16 * dp, 34 * dp);
        slot = icon + 14 * dp;
        float cx0 = W / 2, cy = H / 2 - 4 * dp;
        boolean again = false;
        if (!scrubbing) {
            scroll += (target - scroll) * 0.22f;
            if (Math.abs(target - scroll) > 0.003f) again = true; else scroll = target;
        }
        c.save();
        c.clipRect(0, 0, W, H);
        long now = SystemClock.uptimeMillis();
        for (int i = 0; i < strip.size(); i++) {
            float d = Math.abs(i - scroll), x = cx0 + (i - scroll) * slot;
            if (x < -slot || x > W + slot) continue;
            float s = icon * (1 - Math.min(d, 3) * 0.14f);
            if (d < 0.5f && now - pressAt < 380) { s *= 1 - 0.15f * (float) Math.sin(Math.PI * (now - pressAt) / 380f); again = true; }
            int alpha = Math.round(255 * Math.max(0.2f, 1 - Math.min(d, 4) * 0.22f));
            R r = strip.get(i);
            RectF dst = new RectF(x - s / 2, cy - s / 2, x + s / 2, cy + s / 2);
            if (r.pkg.isEmpty()) {
                p.setColor((0xFF1F7F8C & 0x00FFFFFF) | (alpha << 24));
                c.drawRoundRect(dst, s * 0.24f, s * 0.24f, p);
                txt.setColor((alpha << 24) | 0x00FFFFFF);
                txt.setTextSize(s * 0.2f);
                c.drawText("Desktop", dst.centerX() - txt.measureText("Desktop") / 2, dst.centerY() + s * 0.07f, txt);
            } else if (r.icon != null) {
                bmp.setAlpha(alpha);
                c.drawBitmap(r.icon, null, dst, bmp);
            }
            if (notified.contains(r.pkg)) {
                p.setColor((alpha << 24) | 0x00FFFFFF);
                c.drawCircle(dst.right - s * 0.04f, dst.top + s * 0.04f, 5 * dp, p);
                p.setColor((alpha << 24) | 0x00E53935);
                c.drawCircle(dst.right - s * 0.04f, dst.top + s * 0.04f, 3.8f * dp, p);
            }
        }
        bmp.setAlpha(255);
        c.restore();
        // la mira
        float sh = icon / 2 + 5 * dp;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2.2f * dp);
        p.setColor(0xFF1F7F8C);
        c.drawRoundRect(cx0 - sh, cy - sh, cx0 + sh, cy + sh, 11 * dp, 11 * dp, p);
        p.setStyle(Paint.Style.FILL);
        R cur = strip.get(Math.max(0, Math.min(strip.size() - 1, Math.round(scroll))));
        txt.setColor(ink);
        txt.setTextSize(9.5f * dp);
        String name = cur.label;
        if (txt.measureText(name) > W * 0.7f) name = name.substring(0, Math.max(1, Math.min(name.length(), 14))) + "…";
        c.drawText(name, cx0 - txt.measureText(name) / 2, H - 2 * dp, txt);
        if (cur.pkg.equals(active)) { p.setColor(0xFF34C759); c.drawCircle(cx0 - txt.measureText(name) / 2 - 6 * dp, H - 5 * dp, 2.6f * dp, p); }
        if (again) postInvalidateOnAnimation();
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                downX = e.getX(); startScroll = scroll; scrubbing = true; moved = false;
                if (vt != null) vt.recycle();
                vt = VelocityTracker.obtain();
                vt.addMovement(e);
                return true;
            case MotionEvent.ACTION_MOVE: {
                vt.addMovement(e);
                float dx = e.getX() - downX;
                if (!moved && Math.abs(dx) < ViewConfiguration.get(getContext()).getScaledTouchSlop()) return true;
                moved = true;
                scroll = Math.max(-0.45f, Math.min(strip.size() - 0.55f, startScroll - dx / slot));
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                scrubbing = false;
                float vx = 0;
                if (vt != null) { vt.addMovement(e); vt.computeCurrentVelocity(1000); vx = vt.getXVelocity(); vt.recycle(); vt = null; }
                if (e.getActionMasked() == MotionEvent.ACTION_CANCEL) { invalidate(); return true; }
                if (!access || strip.size() <= 1) { if (pick != null) pick.onPick(null, null); return true; }
                float t = moved ? scroll - vx / slot * 0.12f : scroll + (e.getX() - getWidth() / 2f) / slot;
                int i = Math.max(0, Math.min(strip.size() - 1, Math.round(t)));
                target = i;
                userUntil = SystemClock.uptimeMillis() + 3500;
                pressAt = SystemClock.uptimeMillis();
                invalidate();
                R x = strip.get(i);
                if (pick != null && !x.pkg.equals(active)) pick.onPick(x.pkg, x.label);
                return true;
            }
        }
        return true;
    }
}
