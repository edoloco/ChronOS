package cl.mesadoble;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.Base64;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Barra de tareas del extendido, como la de Windows: las apps abiertas en el principal, en fila.
 *  - La que está al frente en el principal tiene el "foco" (marco y fondo).
 *  - Tocar una la abre en el principal (y pasa a tener el foco).
 *  - Mantener presionada muestra un círculo rojo con una X: al tocarlo se cierra esa app en el principal.
 * Se alimenta con el mismo mensaje de apps recientes del principal (recList).
 */
public class TaskbarView extends View {

    public interface Host {
        void onOpen(String pkg, String label);
        void onClose(String pkg, String label);
        void onNeedAccess();
    }

    private static final class App { String pkg, label; Bitmap icon; }

    private final float dp;
    private final List<App> apps = new ArrayList<>();
    private final Set<String> notified = new HashSet<>();
    private String sig = "", active = "", closing;
    private boolean loaded, access = true;
    private Host host;
    private int ink;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), bmp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG),
            txt = new Paint(Paint.ANTI_ALIAS_FLAG);

    public TaskbarView(Context c, int ink) {
        super(c);
        this.ink = ink;
        dp = c.getResources().getDisplayMetrics().density;
    }

    public void setHost(Host h) { host = h; }
    public void setInk(int i) { ink = i; invalidate(); }
    public String sig() { return loaded ? sig : "?"; }

    public void setNotified(JSONArray a) {
        notified.clear();
        for (int i = 0; a != null && i < a.length(); i++) notified.add(a.optString(i));
        invalidate();
    }

    /** Lista del principal (recList): {apps:[{p,l,i}], active, access, sig}. */
    public void set(JSONObject m) {
        loaded = true;
        access = m.optBoolean("access", true);
        active = m.optString("active");
        String s = m.optString("sig");
        if (!s.equals(sig)) {
            sig = s;
            List<App> keep = new ArrayList<>(apps);
            apps.clear();
            JSONArray a = m.optJSONArray("apps");
            for (int i = 0; a != null && i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                App x = null;
                for (App k : keep) if (k.pkg.equals(o.optString("p"))) x = k;
                if (x == null) {
                    x = new App();
                    x.pkg = o.optString("p");
                    try { byte[] b = Base64.decode(o.optString("i"), Base64.DEFAULT); x.icon = BitmapFactory.decodeByteArray(b, 0, b.length); }
                    catch (Exception ignored) { }
                }
                x.label = o.optString("l");
                apps.add(x);
            }
            if (closing != null && indexOf(closing) < 0) closing = null;
        }
        invalidate();
    }

    private int indexOf(String pkg) {
        for (int i = 0; i < apps.size(); i++) if (apps.get(i).pkg.equals(pkg)) return i;
        return -1;
    }

    private float cell() { return getHeight() - 4 * dp; }
    private float slot() { return cell() + 8 * dp; }

    private RectF box(int i) {
        float s = cell(), x = 2 * dp + i * slot();
        return new RectF(x, (getHeight() - s) / 2, x + s, (getHeight() + s) / 2);
    }

    @Override protected void onDraw(Canvas c) {
        float H = getHeight();
        if (!loaded || !access || apps.isEmpty()) {
            txt.setColor((ink & 0x00FFFFFF) | 0xAA000000);
            txt.setTextSize(12 * dp);
            String t = !loaded ? "Apps abiertas…" : !access ? "Toca para permitir ver las apps abiertas del principal" : "No hay apps abiertas en el principal";
            c.drawText(t, 10 * dp, H / 2 + 4 * dp, txt);
            return;
        }
        int max = Math.max(1, (int) (getWidth() / slot()));
        for (int i = 0; i < apps.size() && i < max; i++) {
            App a = apps.get(i);
            RectF r = box(i);
            boolean foc = a.pkg.equals(active);
            // la caja (más marcada la que tiene el foco)
            p.setStyle(Paint.Style.FILL);
            p.setColor(foc ? 0x59FFFFFF : 0x1FFFFFFF);
            c.drawRoundRect(r, 8 * dp, 8 * dp, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth((foc ? 2.2f : 1f) * dp);
            p.setColor(foc ? 0xFFF57C1F : (ink & 0x00FFFFFF) | 0x55000000);
            c.drawRoundRect(r, 8 * dp, 8 * dp, p);
            p.setStyle(Paint.Style.FILL);
            if (a.icon != null) {
                float in = r.width() * 0.14f;
                c.drawBitmap(a.icon, null, new RectF(r.left + in, r.top + in, r.right - in, r.bottom - in), bmp);
            }
            if (foc) {   // rayita bajo la que tiene el foco, como en Windows
                p.setColor(0xFFF57C1F);
                c.drawRoundRect(new RectF(r.centerX() - r.width() * 0.25f, r.bottom - 3 * dp, r.centerX() + r.width() * 0.25f, r.bottom - 1 * dp), dp, dp, p);
            }
            if (notified.contains(a.pkg)) {
                p.setColor(0xFFFFFFFF);
                c.drawCircle(r.left + 5 * dp, r.top + 5 * dp, 4.5f * dp, p);
                p.setColor(0xFF2FA866);
                c.drawCircle(r.left + 5 * dp, r.top + 5 * dp, 3.3f * dp, p);
            }
            if (a.pkg.equals(closing)) {   // círculo rojo con X: cerrar
                float cx = r.right - 2 * dp, cy = r.top + 2 * dp, rad = 9 * dp;
                p.setColor(0xFFFFFFFF);
                c.drawCircle(cx, cy, rad + 1.5f * dp, p);
                p.setColor(0xFFE53935);
                c.drawCircle(cx, cy, rad, p);
                p.setColor(0xFFFFFFFF);
                p.setStrokeWidth(2.2f * dp);
                p.setStyle(Paint.Style.STROKE);
                float k = rad * 0.45f;
                c.drawLine(cx - k, cy - k, cx + k, cy + k, p);
                c.drawLine(cx - k, cy + k, cx + k, cy - k, p);
                p.setStyle(Paint.Style.FILL);
            }
        }
    }

    // ---------------- toques ----------------

    private float downX, downY;
    private int downI = -1;
    private boolean longDone, moved;
    private final Runnable longPress = () -> {
        if (downI < 0 || downI >= apps.size()) return;
        longDone = true;
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        closing = apps.get(downI).pkg;
        invalidate();
    };

    private int hit(float x) {
        int i = (int) ((x - 2 * dp) / slot());
        return i >= 0 && i < apps.size() && x - 2 * dp - i * slot() <= cell() ? i : -1;
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                downX = e.getX(); downY = e.getY(); longDone = false; moved = false;
                downI = hit(e.getX());
                if (downI >= 0) postDelayed(longPress, 450);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (Math.hypot(e.getX() - downX, e.getY() - downY) > ViewConfiguration.get(getContext()).getScaledTouchSlop()) { moved = true; removeCallbacks(longPress); }
                return true;
            case MotionEvent.ACTION_UP: {
                removeCallbacks(longPress);
                if (longDone || moved) return true;
                if (!loaded || !access || apps.isEmpty()) { if (host != null && loaded && !access) host.onNeedAccess(); return true; }
                // ¿tocó la X roja?
                if (closing != null) {
                    int ci = indexOf(closing);
                    if (ci >= 0) {
                        RectF r = box(ci);
                        if (Math.hypot(e.getX() - (r.right - 2 * dp), e.getY() - (r.top + 2 * dp)) < 18 * dp) {
                            App a = apps.get(ci);
                            closing = null;
                            if (host != null) host.onClose(a.pkg, a.label);
                            apps.remove(ci);   // se quita al tiro (el principal confirma con la lista nueva)
                            sig = "?";
                            invalidate();
                            return true;
                        }
                    }
                    closing = null;   // tocar en otro lado cancela
                    invalidate();
                    return true;
                }
                int i = hit(e.getX());
                if (i >= 0 && host != null) {
                    App a = apps.get(i);
                    active = a.pkg;   // el foco pasa a esa app
                    invalidate();
                    host.onOpen(a.pkg, a.label);
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL: removeCallbacks(longPress); return true;
        }
        return true;
    }
}
