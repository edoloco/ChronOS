package cl.mesadoble;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.Point;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Game Pal en el PRINCIPAL: varios "dedos" virtuales a la vez (mover y apuntar al mismo tiempo).
 * Android ejecuta un gesto por vez, así que cada pocos milisegundos se manda UN gesto con la continuación de
 * todos los dedos apoyados; el siguiente sale cuando termina el anterior.
 *   gp {f: dedo, ph: 0 apoyar | 1 mover | 2 levantar, x, y (0..1 de la pantalla)}
 *   gpShot → gpImg {img} (pantalla del principal, para ubicar los botones del juego)
 */
final class GamePalTouch {

    private GamePalTouch() { }

    private static final long DUR = 40;   // ms de cada tramo
    private static final Handler main = new Handler(Looper.getMainLooper());

    /** Un dedo: dónde está, adónde va, dónde se apoyó (bx, by) y si hay que volver a apoyarlo tras un corte. */
    private static final class F { float x, y, tx, ty, bx, by; GestureDescription.StrokeDescription s; boolean ending, restart; }

    private static final Map<Integer, F> fingers = new HashMap<>();
    private static boolean busy;
    private static int jiggle, stale = -1, cuts;

    private static Point screen(MesaAccess s) {
        Point p = new Point();
        WindowManager wm = (WindowManager) s.getSystemService(android.content.Context.WINDOW_SERVICE);
        if (wm != null) wm.getDefaultDisplay().getRealSize(p);
        return p;
    }

    static void onMessage(MesaAccess s, JSONObject m) {
        Point sc = screen(s);
        int id = m.optInt("f"), ph = m.optInt("ph");
        float x = clamp((float) m.optDouble("x", 0.5) * sc.x, sc.x), y = clamp((float) m.optDouble("y", 0.5) * sc.y, sc.y);
        F f = fingers.get(id);
        if (ph == 0) {
            if (f != null) { f.ending = true; fingers.remove(id); fingers.put(--stale, f); }   // quedó colgado: se suelta
            F n = new F();
            n.x = n.tx = n.bx = x; n.y = n.ty = n.by = y;
            fingers.put(id, n);
        } else if (f != null) {
            f.tx = x; f.ty = y;
            if (ph == 2) f.ending = true;
        }
        pump(s);
    }

    private static float clamp(float v, int max) { return Math.max(1, Math.min(max - 2, v)); }

    private static void pump(MesaAccess s) {
        if (busy || fingers.isEmpty()) return;
        GestureDescription.Builder b = new GestureDescription.Builder();
        int n = 0;
        jiggle = 1 - jiggle;
        for (Iterator<Map.Entry<Integer, F>> it = fingers.entrySet().iterator(); it.hasNext(); ) {
            F f = it.next().getValue();
            Path p = new Path();
            if (f.s == null && f.restart) {
                // Android cortó el gesto: el dedo se vuelve a apoyar donde empezó y de ahí va a su posición,
                // así un joystick que se crea donde se apoya el dedo (Brawl Stars) sigue apuntando igual
                f.x = f.bx; f.y = f.by;
                f.restart = false;
            }
            p.moveTo(f.x, f.y);
            float tx = f.tx, ty = f.ty;
            if (Math.abs(tx - f.x) < 0.5f && Math.abs(ty - f.y) < 0.5f) tx = f.x + (jiggle == 0 ? 0.6f : -0.6f);   // quieto: un pelo, para que el tramo no sea vacío
            p.lineTo(tx, ty);
            boolean cont = !f.ending;
            try {
                f.s = f.s == null ? new GestureDescription.StrokeDescription(p, 0, DUR, cont) : f.s.continueStroke(p, 0, DUR, cont);
                b.addStroke(f.s);
                n++;
            } catch (Exception e) { f.s = null; }
            // el próximo tramo tiene que empezar EXACTAMENTE donde Android dejó el dedo: Android calcula ese punto
            // midiendo el trazo (no usa el número que le dimos), y si no calza ni por una milésima, corta el gesto
            android.graphics.PathMeasure pm = new android.graphics.PathMeasure(p, false);
            float[] end = new float[2];
            if (pm.getLength() > 0 && pm.getPosTan(pm.getLength() * 1.0f, end, null)) { f.x = end[0]; f.y = end[1]; }
            else { f.x = tx; f.y = ty; }
            if (f.ending) it.remove();
            if (n >= 10) break;
        }
        if (n == 0) return;
        busy = true;
        boolean ok;
        try {
            ok = s.dispatchGesture(b.build(), new AccessibilityService.GestureResultCallback() {
                @Override public void onCompleted(GestureDescription g) { busy = false; pump(s); }
                @Override public void onCancelled(GestureDescription g) {
                    // Android cortó el gesto (por ejemplo, se tocó la pantalla de arriba): los dedos vuelven a apoyarse
                    busy = false;
                    cuts++;
                    for (F f : fingers.values()) { if (f.s != null) f.restart = true; f.s = null; }
                    Hub h = Hub.get(s);
                    if (h.connected()) h.send("gpCut", "n", cuts);
                    pump(s);
                }
            }, main);
        } catch (Exception e) { ok = false; }
        if (!ok) { busy = false; for (F f : fingers.values()) { if (f.s != null) f.restart = true; f.s = null; } }
    }

    /** Suelta todos los dedos (se cerró Game Pal). */
    static void releaseAll(MesaAccess s) {
        for (F f : fingers.values()) f.ending = true;
        pump(s);
    }

    /** Captura de la pantalla del principal, para ubicar los botones del juego en el extendido. */
    static void shot(MesaAccess s, Hub hub) {
        Sketch.take(s, b -> {
            if (b == null) { hub.send("gpImg", "err", "No se pudo capturar el principal (necesita Android 11 o más nuevo)"); return; }
            RecentApps.worker.execute(() -> {
                try {
                    float k = Math.min(1f, 1100f / Math.max(b.getWidth(), b.getHeight()));
                    Bitmap sm = Bitmap.createScaledBitmap(b, Math.max(1, (int) (b.getWidth() * k)), Math.max(1, (int) (b.getHeight() * k)), true);
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    sm.compress(Bitmap.CompressFormat.JPEG, 75, bos);
                    hub.send("gpImg", "img", android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP));
                } catch (Exception ignored) { }
            });
        });
    }
}
