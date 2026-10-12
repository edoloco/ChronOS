package cl.mesadoble;

import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import android.view.MotionEvent;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Widgets de Android, siempre del PRINCIPAL: aquí viven todos (los de las dos pantallas).
 * El principal los muestra de verdad; para el extendido se dibujan como imagen cada segundo y se envían,
 * y los toques que se hacen en el extendido vuelven aquí y se aplican al widget real.
 */
public class WidgetHub {

    static final int HOST_ID = 4712;
    private static WidgetHub inst;

    static synchronized WidgetHub get(Context c) { if (inst == null) inst = new WidgetHub(c.getApplicationContext()); return inst; }

    final Context app;
    final AppWidgetManager awm;
    final AppWidgetHost host;
    private final Map<Integer, AppWidgetHostView> views = new HashMap<>();
    private final Map<Integer, int[]> wanted = new HashMap<>();     // lo que el extendido está mostrando: id → {ancho, alto}
    private final Map<Integer, Integer> sent = new HashMap<>();     // huella de la última imagen enviada
    private final Handler main = new Handler(Looper.getMainLooper());
    private Hub hub;

    private WidgetHub(Context c) {
        app = c;
        awm = AppWidgetManager.getInstance(c);
        host = new AppWidgetHost(c, HOST_ID);
        try { host.startListening(); } catch (Exception ignored) { }
        // los de la versión anterior (cada teléfono tenía los suyos) ya no se usan
        try { new AppWidgetHost(c, 4711).deleteHost(); } catch (Exception ignored) { }
    }

    int allocate() { return host.allocateAppWidgetId(); }

    void delete(int wid) {
        if (wid < 0) return;
        AppWidgetHostView v = views.remove(wid);
        if (v != null && v.getParent() instanceof android.view.ViewGroup) ((android.view.ViewGroup) v.getParent()).removeView(v);
        wanted.remove(wid);
        sent.remove(wid);
        try { host.deleteAppWidgetId(wid); } catch (Exception ignored) { }
    }

    /** Borra los widgets activados que ya no están en ninguna mesa. */
    void clean(java.util.Set<Integer> used) {
        try { for (int id : host.getAppWidgetIds()) if (!used.contains(id)) delete(id); } catch (Exception ignored) { }
    }

    AppWidgetHostView view(int wid) {
        if (wid < 0) return null;
        AppWidgetHostView v = views.get(wid);
        if (v != null) return v;
        AppWidgetProviderInfo info = awm.getAppWidgetInfo(wid);
        if (info == null) return null;
        v = host.createView(app, wid, info);
        views.put(wid, v);
        return v;
    }

    AppWidgetProviderInfo provider(String flat) {
        ComponentName cn = ComponentName.unflattenFromString(flat);
        if (cn == null) return null;
        for (AppWidgetProviderInfo i : awm.getInstalledProviders()) if (cn.equals(i.provider)) return i;
        return null;
    }

    // ---------------- lista de widgets del principal ----------------

    static JSONArray providers(Context c) {
        PackageManager pm = c.getPackageManager();
        float d = c.getResources().getDisplayMetrics().density;
        JSONArray arr = new JSONArray();
        for (AppWidgetProviderInfo info : AppWidgetManager.getInstance(c).getInstalledProviders()) {
            try {
                ApplicationInfo ai = pm.getApplicationInfo(info.provider.getPackageName(), 0);
                String app = String.valueOf(pm.getApplicationLabel(ai));
                String label = info.loadLabel(pm);
                Drawable icon = info.loadIcon(c, c.getResources().getDisplayMetrics().densityDpi);
                if (icon == null) icon = pm.getApplicationIcon(ai);
                JSONObject o = new JSONObject();
                o.put("p", info.provider.flattenToString());
                o.put("l", label != null ? label : app);
                o.put("a", app);
                o.put("mw", Math.round(info.minWidth / d));
                o.put("mh", Math.round(info.minHeight / d));
                o.put("i", AppsDialog.iconPng(icon, Math.round(48 * d)));
                arr.put(o);
            } catch (Exception ignored) { }
        }
        return arr;
    }

    void sendProviders(Hub h) {
        new Thread(() -> {
            JSONObject m = new JSONObject();
            try { m.put("t", "wListR"); m.put("list", providers(app)); } catch (JSONException ignored) { }
            h.send(m);
        }, "widgets-lista").start();
    }

    // ---------------- espejo para el extendido ----------------

    /** El extendido dice qué widgets está mostrando y a qué tamaño (en píxeles). */
    void want(JSONObject m, Hub h) {
        hub = h;
        wanted.clear();
        JSONArray ids = m.optJSONArray("ids"), ws = m.optJSONArray("w"), hs = m.optJSONArray("h");
        for (int i = 0; ids != null && i < ids.length(); i++)
            wanted.put(ids.optInt(i), new int[]{Math.max(40, ws.optInt(i)), Math.max(40, hs.optInt(i))});
        sent.clear();   // el extendido pudo reiniciarse: se vuelve a mandar todo una vez
        main.removeCallbacks(tick);
        if (!wanted.isEmpty()) main.post(tick);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (wanted.isEmpty() || hub == null || !hub.connected()) return;
            for (Map.Entry<Integer, int[]> e : wanted.entrySet()) render(e.getKey(), e.getValue()[0], e.getValue()[1]);
            main.postDelayed(this, 1000);
        }
    };

    private void layoutIfNeeded(View v, int w, int h) {
        if (v.getWidth() > 0 && v.getHeight() > 0 && v.getVisibility() == View.VISIBLE && v.getParent() != null) return;
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        v.layout(0, 0, w, h);
    }

    private void render(int wid, int w, int h) {
        AppWidgetHostView v = view(wid);
        if (v == null) return;
        try {
            layoutIfNeeded(v, w, h);
            int vw = v.getWidth(), vh = v.getHeight();
            if (vw <= 0 || vh <= 0) return;
            float k = Math.min(1f, 640f / Math.max(vw, vh));
            Bitmap b = Bitmap.createBitmap(Math.max(1, Math.round(vw * k)), Math.max(1, Math.round(vh * k)), Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            c.scale(k, k);
            v.draw(c);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.PNG, 100, bos);
            byte[] bytes = bos.toByteArray();
            int hash = Arrays.hashCode(bytes);
            Integer old = sent.get(wid);
            if (old != null && old == hash) return;
            sent.put(wid, hash);
            JSONObject m = new JSONObject();
            m.put("t", "wImg"); m.put("wid", wid); m.put("img", Base64.encodeToString(bytes, Base64.NO_WRAP));
            hub.send(m);
        } catch (Exception ignored) { }
    }

    /** Un toque hecho en el extendido sobre la imagen del widget (x, y de 0 a 1). */
    void tap(JSONObject m) {
        int wid = m.optInt("wid", -1);
        AppWidgetHostView v = view(wid);
        if (v == null) return;
        // el reloj de Mesa Doble: abre las alarmas directo (un toque simulado no puede abrir apps con otra app al frente)
        AppWidgetProviderInfo info = awm.getAppWidgetInfo(wid);
        if (info != null && info.provider != null && info.provider.getClassName().equals(ClockWidget.class.getName())) {
            Hub.get(app).openAlarms();
            return;
        }
        int[] size = wanted.get(wid);
        layoutIfNeeded(v, size != null ? size[0] : 400, size != null ? size[1] : 300);
        float x = (float) m.optDouble("x") * v.getWidth(), y = (float) m.optDouble("y") * v.getHeight();
        long t = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0);
        v.dispatchTouchEvent(down);
        down.recycle();
        main.postDelayed(() -> {
            MotionEvent up = MotionEvent.obtain(t, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0);
            v.dispatchTouchEvent(up);
            up.recycle();
            sent.remove(wid);   // que la próxima imagen se envíe aunque se parezca
        }, 70);
    }
}
