package cl.mesadoble;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Mesa de dibujo para Sketchbook (lado PRINCIPAL, versión completa).
 * Con Sketchbook al frente, el extendido es una tableta con zoom: el trazo se hace abajo, al soltar viaja
 * entero y aquí se "dibuja" como un dedo (accesibilidad). Después se toma una captura de la zona y vuelve
 * al extendido para que vea cómo quedó. No hay transmisión continua: solo una imagen tras cada trazo.
 *   sketchOn / sketchOff (principal → extendido): Sketchbook se abrió / se fue
 *   skStroke {p:[x,y,…] (0..1 de la pantalla), d (ms), r:[l,t,r,b], w, h}: dibujar ese trazo y mandar la imagen
 *   skTap {x, y, r, w, h}: un toque (atajos: deshacer, color…)
 *   skShot {r, w, h, full}: mandar la imagen de esa zona (full = pantalla completa, para elegir atajos)
 *   skEnd: se cerró la mesa (se quita el marco)
 *   skImg (→ extendido) {img (jpg base64), r, sw, sh, full} o {err}
 */
final class Sketch {

    private Sketch() { }

    private static final Handler main = new Handler(Looper.getMainLooper());

    static boolean isSketchbook(String pkg) {
        return pkg != null && (pkg.equals("com.adsk.sketchbook") || pkg.toLowerCase().contains("sketchbook"));
    }

    // ---------------- ¿Sketchbook al frente? ----------------

    private static boolean on;
    private static final Runnable offLater = () -> {
        // antes de cerrar la mesa se confirma qué app está de verdad al frente (las ventanas propias de ChronOS,
        // como el marco verde o el teclado, también avisan "cambio de ventana" y no deben cerrarla)
        MesaAccess s = MesaAccess.instance;
        if (s != null) {
            try {
                android.view.accessibility.AccessibilityNodeInfo root = s.getRootInActiveWindow();
                if (root != null && root.getPackageName() != null && isSketchbook(root.getPackageName().toString())) return;
            } catch (Exception ignored) { }
        }
        on = false;
        Hub h = MesaAccess.instance != null ? Hub.get(MesaAccess.instance) : null;
        if (h != null && h.connected()) h.send("sketchOff");
        hideFrame();
    };

    /** Cambió la app del frente en el principal ("" = ChronOS o el inicio). Teclados y avisos no llegan aquí. */
    static void front(Hub hub, String pkg) {
        if (!hub.isPrincipal()) return;
        if (isSketchbook(pkg)) {
            main.removeCallbacks(offLater);
            if (!on && hub.connected()) { on = true; hub.send("sketchOn"); }
        } else if (on) {
            main.removeCallbacks(offLater);
            main.postDelayed(offLater, 1500);   // un diálogo de otra app no la cierra al tiro
        }
    }

    /** Se conectó el extendido con Sketchbook ya abierto. */
    static void resend(Hub hub) { if (on) hub.send("sketchOn"); }

    // ---------------- mensajes del extendido ----------------

    private static double[] rect = {0, 0, 1, 1};
    private static int outW = 1280, outH = 720;

    static void handle(MesaAccess s, Hub hub, JSONObject m) {
        String t = m.optString("t");
        JSONArray r = m.optJSONArray("r");
        if (r != null && r.length() == 4) rect = new double[]{r.optDouble(0), r.optDouble(1), r.optDouble(2), r.optDouble(3)};
        if (m.has("w")) { outW = Math.max(64, Math.min(1600, m.optInt("w"))); outH = Math.max(64, Math.min(1600, m.optInt("h"))); }
        switch (t) {
            case "skStroke": stroke(s, hub, m.optJSONArray("p"), m.optLong("d", 300)); break;
            case "skTap": tap(s, hub, m.optDouble("x"), m.optDouble("y")); break;
            case "skShot":
                if (m.optBoolean("full")) shot(s, hub, new double[]{0, 0, 1, 1}, true);
                else { showFrame(s); shot(s, hub, rect, false); }
                break;
            case "skEnd": hideFrame(); break;
        }
    }

    private static Point screen(MesaAccess s) {
        Point p = new Point();
        WindowManager wm = (WindowManager) s.getSystemService(android.content.Context.WINDOW_SERVICE);
        if (wm != null) wm.getDefaultDisplay().getRealSize(p);
        return p;
    }

    /** El trazo entero, como un dedo, con la misma duración con que se dibujó abajo. */
    private static void stroke(MesaAccess s, Hub hub, JSONArray p, long dur) {
        if (p == null || p.length() < 2) return;
        Point sc = screen(s);
        Path path = new Path();
        for (int i = 0; i + 1 < p.length(); i += 2) {
            float x = clamp((float) p.optDouble(i) * sc.x, sc.x), y = clamp((float) p.optDouble(i + 1) * sc.y, sc.y);
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        long d = Math.max(p.length() <= 2 ? 40 : 80, Math.min(dur, 20000));
        dispatch(s, hub, path, d);
    }

    private static void tap(MesaAccess s, Hub hub, double nx, double ny) {
        Point sc = screen(s);
        Path path = new Path();
        path.moveTo(clamp((float) nx * sc.x, sc.x), clamp((float) ny * sc.y, sc.y));
        dispatch(s, hub, path, 50);
    }

    private static float clamp(float v, int max) { return Math.max(1, Math.min(max - 2, v)); }

    private static void dispatch(MesaAccess s, Hub hub, Path path, long d) {
        try {
            boolean ok = s.dispatchGesture(new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(path, 0, d)).build(),
                    new AccessibilityService.GestureResultCallback() {
                        @Override public void onCompleted(GestureDescription g) { main.postDelayed(() -> shot(s, hub, rect, false), 280); }
                        @Override public void onCancelled(GestureDescription g) { main.postDelayed(() -> shot(s, hub, rect, false), 280); }
                    }, main);
            if (!ok) main.postDelayed(() -> shot(s, hub, rect, false), 200);
        } catch (Exception e) { main.postDelayed(() -> shot(s, hub, rect, false), 200); }
    }

    // ---------------- captura (sin permiso de grabación: la toma la accesibilidad, Android 11+) ----------------

    private static boolean busy, again;
    private static double[] againRect;
    private static boolean againFull;
    private static long lastShot;

    static void shot(MesaAccess s, Hub hub, double[] r, boolean full) {
        if (busy) { again = true; againRect = r; againFull = full; return; }
        long wait = 360 - (android.os.SystemClock.uptimeMillis() - lastShot);   // Android deja una captura cada ~1/3 s
        if (wait > 0) {
            busy = true;
            main.postDelayed(() -> {
                busy = false;
                if (again) { again = false; shot(s, hub, againRect, againFull); } else shot(s, hub, r, full);
            }, wait);
            return;
        }
        busy = true;
        lastShot = android.os.SystemClock.uptimeMillis();
        hideFrameFor(full);
        take(s, b -> {
            showFrameAfter(s, full);
            if (b == null) {
                hub.send("skImg", "err", android.os.Build.VERSION.SDK_INT < 30
                        ? "Este principal tiene Android 10 o anterior: no puede capturar la imagen (el dibujo sí funciona)"
                        : "No se pudo capturar la pantalla del principal");
            } else RecentApps.worker.execute(() -> send(hub, b, r, full));
            main.postDelayed(() -> {
                busy = false;
                if (again) { again = false; shot(s, hub, againRect, againFull); }
            }, 30);
        });
    }

    private static void send(Hub hub, Bitmap b, double[] r, boolean full) {
        try {
            int sw = b.getWidth(), sh = b.getHeight();
            int l = (int) Math.round(r[0] * sw), t = (int) Math.round(r[1] * sh);
            int w = Math.max(1, Math.min(sw - l, (int) Math.round((r[2] - r[0]) * sw))), h = Math.max(1, Math.min(sh - t, (int) Math.round((r[3] - r[1]) * sh)));
            Bitmap crop = Bitmap.createBitmap(b, Math.max(0, l), Math.max(0, t), w, h);
            float k = Math.min(1f, Math.min(outW / (float) w, outH / (float) h));
            if (full) k = Math.min(1f, 900f / Math.max(w, h));
            Bitmap out = k < 1f ? Bitmap.createScaledBitmap(crop, Math.max(1, (int) (w * k)), Math.max(1, (int) (h * k)), true) : crop;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            out.compress(Bitmap.CompressFormat.JPEG, 72, bos);
            JSONObject m = new JSONObject();
            m.put("t", "skImg");
            m.put("img", android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP));
            m.put("r", new JSONArray().put(r[0]).put(r[1]).put(r[2]).put(r[3]));
            m.put("sw", sw); m.put("sh", sh); m.put("full", full);
            hub.send(m);
        } catch (Exception ignored) { }
    }

    /** AccessibilityService.takeScreenshot (Android 11+), por reflexión. */
    private static void take(MesaAccess s, Consumer<Bitmap> cb) {
        if (android.os.Build.VERSION.SDK_INT < 30) { cb.accept(null); return; }
        try {
            Class<?> cbI = Class.forName("android.accessibilityservice.AccessibilityService$TakeScreenshotCallback");
            Object proxy = java.lang.reflect.Proxy.newProxyInstance(cbI.getClassLoader(), new Class<?>[]{cbI}, (pr, method, args) -> {
                String n = method.getName();
                if ("onSuccess".equals(n)) {
                    Bitmap out = null;
                    try {
                        Object res = args[0];
                        Object hb = res.getClass().getMethod("getHardwareBuffer").invoke(res);
                        Object cs = res.getClass().getMethod("getColorSpace").invoke(res);
                        Bitmap hw = (Bitmap) Bitmap.class.getMethod("wrapHardwareBuffer", Class.forName("android.hardware.HardwareBuffer"), android.graphics.ColorSpace.class)
                                .invoke(null, hb, cs);
                        if (hw != null) out = hw.copy(Bitmap.Config.ARGB_8888, false);
                        try { hb.getClass().getMethod("close").invoke(hb); } catch (Exception ignored) { }
                    } catch (Exception ignored) { }
                    final Bitmap f = out;
                    main.post(() -> cb.accept(f));
                } else if ("onFailure".equals(n)) {
                    main.post(() -> cb.accept(null));
                } else if ("hashCode".equals(n)) return System.identityHashCode(pr);
                else if ("equals".equals(n)) return pr == args[0];
                else if ("toString".equals(n)) return "captura";
                return null;
            });
            Executor ex = main::post;
            AccessibilityService.class.getMethod("takeScreenshot", int.class, Executor.class, cbI).invoke(s, 0, ex, proxy);
        } catch (Exception e) { cb.accept(null); }
    }

    // ---------------- marco en el principal: qué zona se está dibujando abajo ----------------

    private static View frame;

    private static final class FrameView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float dp;
        FrameView(MesaAccess s) {
            super(s);
            dp = s.getResources().getDisplayMetrics().density;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2 * dp);
            p.setColor(0xCC2ECC71);
        }
        @Override protected void onDraw(Canvas c) {
            float W = getWidth(), H = getHeight(), o = 2 * dp;   // por fuera de la zona: no sale en la imagen
            if (rect[0] <= 0.001 && rect[1] <= 0.001 && rect[2] >= 0.999 && rect[3] >= 0.999) return;
            c.drawRect((float) rect[0] * W - o, (float) rect[1] * H - o, (float) rect[2] * W + o, (float) rect[3] * H + o, p);
        }
    }

    private static void showFrame(MesaAccess s) {
        main.post(() -> {
            if (frame != null) { frame.setVisibility(View.VISIBLE); frame.invalidate(); return; }
            WindowManager wm = (WindowManager) s.getSystemService(android.content.Context.WINDOW_SERVICE);
            if (wm == null) return;
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            FrameView v = new FrameView(s);
            try { wm.addView(v, lp); frame = v; } catch (Exception ignored) { }
        });
    }

    /** La pantalla completa no debe salir con el marco encima. */
    private static void hideFrameFor(boolean full) { if (full && frame != null) frame.setVisibility(View.INVISIBLE); }
    private static void showFrameAfter(MesaAccess s, boolean full) { if (full && frame != null) frame.setVisibility(View.VISIBLE); }

    static void hideFrame() {
        main.post(() -> {
            if (frame == null) return;
            try { ((WindowManager) frame.getContext().getSystemService(android.content.Context.WINDOW_SERVICE)).removeView(frame); } catch (Exception ignored) { }
            frame = null;
        });
    }
}
