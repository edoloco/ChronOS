package cl.mesadoble;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Bat Op (Battery Optimizer), menú Inicio › Bat Op. Pensado para el extendido, se elige en cuál teléfono (o los dos).
 * Si nadie toca el teléfono y no está mostrando nada activo (teclado, control de video, Game-Asist, video pasado,
 * llamada, sonido):
 *  1) a los batOpT1 minutos: pantalla negra al mínimo de brillo con un reloj tenue y las baterías (P, E y accesorios).
 *     Un toque y vuelve al instante (la pantalla nunca se apagó).
 *  2) batOpT2 minutos después: se apaga la pantalla (la conexión sigue viva, más lenta) — 0 = nunca.
 * Despierta solo cuando el otro teléfono lo necesita (Hub llama a wake()): teclado, llamada, video pasado, etc.
 * Prefs (en los dos, con prefSet): batOpP / batOpE (activo en principal / extendido), batOpT1, batOpT2.
 */
final class BatOp {

    private BatOp() { }

    private static final Handler h = new Handler(Looper.getMainLooper());
    private static volatile long lastTouch = System.currentTimeMillis();
    private static Context app;
    private static Hub hub;
    private static Black black;
    private static WindowManager wm;
    private static boolean dimmed, slept;

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("mesa", Context.MODE_PRIVATE); }

    /** ¿Bat Op activo en ESTE teléfono? */
    static boolean on(Context c, Hub hb) { return prefs(c).getBoolean(hb.isPrincipal() ? "batOpP" : "batOpE", false); }
    static int t1(Context c) { return prefs(c).getInt("batOpT1", 1); }
    static int t2(Context c) { return prefs(c).getInt("batOpT2", 5); }

    static void start(Context c, Hub hb) {
        if (app == null) { app = c.getApplicationContext(); hub = hb; h.postDelayed(tick, 15000); }
    }

    /** El usuario tocó algo en este teléfono. */
    static void touch() {
        lastTouch = System.currentTimeMillis();
        slept = false;
        testUntil = 0;
        if (dimmed) h.post(BatOp::undim);
    }

    /** El otro teléfono lo necesita (teclado, llamada, video…): de vuelta al instante. */
    static void wake() {
        lastTouch = System.currentTimeMillis();
        h.post(() -> {
            undim();
            if (slept && app != null) { slept = false; ScreenSync.wakeUnlocked(app); }
        });
    }

    /** Probar: la pantalla negra ahora mismo. */
    static void test() { testUntil = System.currentTimeMillis() + 60_000; h.post(() -> { if (!dimmed && app != null) dim(); }); }
    private static volatile long testUntil;
    private static volatile String why = "";

    /** Para el menú: qué está haciendo Bat Op en este teléfono ahora. */
    static String status(Context c, Hub hb) {
        if (!on(c, hb)) return "apagado en este teléfono";
        if (dimmed) return "en reposo (pantalla negra)";
        String b = busyWhy();
        if (b != null) return "esperando: " + b;
        long idle = (System.currentTimeMillis() - lastTouch) / 1000;
        return "sin uso hace " + idle + " s (negro a los " + t1(c) * 60 + " s)";
    }

    /** ¿Hay algo activo que no se debe tapar ni apagar? */
    private static boolean busy() { return busyWhy() != null; }

    /** null = nada activo; si no, qué lo tiene ocupado (se muestra en el menú). */
    private static String busyWhy() {
        if (app == null) return null;
        if (KeyboardActivity.showingMedia) return "control de video abierto";
        if (KeyboardActivity.showingCamera) return "cámara abierta";
        if (KeyboardActivity.showingAsist) return "Game-Asist abierto";
        if (hub != null && hub.keyboardVisible) return "teclado abierto";
        if (CaptureService.running) return "capturando sonido";
        if (DsMirror.running) return "segunda pantalla del DS";
        AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        if (am != null && am.isMusicActive()) return "hay sonido";
        int md = am == null ? AudioManager.MODE_NORMAL : am.getMode();
        if (md == AudioManager.MODE_IN_CALL || md == AudioManager.MODE_RINGTONE) return "llamada";
        String f = MesaAccess.front;
        if (f != null && (MoveTo.YT.equals(f) || MoveTo.isBrowser(app, f))) return "video o página a la vista";
        return null;
    }

    private static final Runnable tick = new Runnable() {
        @Override public void run() {
            h.postDelayed(this, 10000);
            if (System.currentTimeMillis() < testUntil) return;   // prueba: se queda hasta que la toquen
            if (app == null || hub == null || !on(app, hub)) { if (dimmed) undim(); return; }
            PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isInteractive()) return;   // ya está apagada
            if (busy()) { lastTouch = Math.max(lastTouch, System.currentTimeMillis() - 5000); if (dimmed) undim(); return; }
            long idle = System.currentTimeMillis() - lastTouch;
            long a = t1(app) * 60_000L, b = t2(app) * 60_000L;
            if (!dimmed && idle >= a) dim();
            if (dimmed && b > 0 && idle >= a + b && !slept) {   // etapa 2: se apaga
                slept = true;
                undim();
                ScreenSync.lockNow(app, hub);
            }
        }
    };

    // ---------------- etapa 1: negro con reloj ----------------

    private static void dim() {
        Context ctx = MesaAccess.instance != null ? MesaAccess.instance : app;
        int type = MesaAccess.instance != null ? WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY : WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        if (MesaAccess.instance == null && !Settings.canDrawOverlays(app)) return;
        wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        black = new Black(ctx);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(-1, -1, type,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN, PixelFormat.OPAQUE);
        lp.screenBrightness = 0.01f;   // al mínimo
        if (android.os.Build.VERSION.SDK_INT >= 28) lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        try { wm.addView(black, lp); dimmed = true; } catch (Exception e) { black = null; }
    }

    private static void undim() {
        if (black == null) { dimmed = false; return; }
        black.stop();
        try { wm.removeView(black); } catch (Exception ignored) { }
        black = null;
        dimmed = false;
    }

    /** Pantalla negra: reloj grande y tenue (se corre un poco cada minuto para no marcar la pantalla) y baterías. */
    private static final class Black extends View {
        private final float dp;
        private final Paint clock = new Paint(Paint.ANTI_ALIAS_FLAG), small = new Paint(Paint.ANTI_ALIAS_FLAG), bar = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Runnable minute = new Runnable() { @Override public void run() { invalidate(); h.postDelayed(this, 30000); } };

        Black(Context c) {
            super(c);
            dp = c.getResources().getDisplayMetrics().density;
            setBackgroundColor(Color.BLACK);
            clock.setColor(0xFF6A7380);
            clock.setTextAlign(Paint.Align.CENTER);
            clock.setTypeface(android.graphics.Typeface.create("sans-serif-thin", android.graphics.Typeface.NORMAL));
            small.setColor(0xFF59616C);
            small.setTextAlign(Paint.Align.CENTER);
            h.postDelayed(minute, 30000);
        }

        void stop() { h.removeCallbacks(minute); }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) touch();   // un toque: vuelve
            return true;
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), hh = getHeight();
            long m = System.currentTimeMillis() / 60000;
            float jx = (float) Math.sin(m * 1.7) * 18 * dp, jy = (float) Math.cos(m * 1.3) * 14 * dp;   // se corre un poco
            float cx = w / 2 + jx, cy = hh * 0.42f + jy;
            clock.setTextSize(Math.min(w, hh) * 0.26f);
            c.drawText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date()), cx, cy, clock);
            small.setTextSize(Math.max(13 * dp, Math.min(w, hh) * 0.04f));
            c.drawText(new SimpleDateFormat("EEEE d 'de' MMMM", new Locale("es")).format(new Date()), cx, cy + small.getTextSize() * 1.6f, small);
            // baterías: P, E y accesorios, en fila
            Hub hb = hub;
            float y = cy + small.getTextSize() * 4.2f;
            java.util.List<String[]> rows = new java.util.ArrayList<>();
            if (hb != null) {
                int pb = hb.isPrincipal() ? hb.myBatt : hb.otherBatt, eb = hb.isPrincipal() ? hb.otherBatt : hb.myBatt;
                boolean pc = hb.isPrincipal() ? hb.myChg : hb.otherChg, ec = hb.isPrincipal() ? hb.otherChg : hb.myChg;
                if (pb >= 0) rows.add(new String[]{"P", String.valueOf(pb), pc ? "1" : ""});
                if (eb >= 0) rows.add(new String[]{"E", String.valueOf(eb), ec ? "1" : ""});
            }
            List<Peri.Item> per = Peri.all();
            for (int i = 0; i < per.size() && i < 4; i++) rows.add(new String[]{iconName(per.get(i).kind), String.valueOf(per.get(i).pct), ""});
            float one = 92 * dp, x0 = cx - rows.size() * one / 2 + one / 2;
            for (int i = 0; i < rows.size(); i++) drawBatt(c, rows.get(i), x0 + i * one, y);
        }

        private String iconName(String k) {
            switch (k) { case "h": return "🎧"; case "g": return "🎮"; case "k": return "⌨"; case "m": return "🖱"; case "w": return "⌚"; default: return "ᛒ"; }
        }

        private void drawBatt(Canvas c, String[] r, float x, float y) {
            int pct = Integer.parseInt(r[1]);
            boolean chg = !r[2].isEmpty();
            small.setColor(0xFF59616C);
            c.drawText(r[0], x, y - 12 * dp, small);
            RectF body = new RectF(x - 22 * dp, y, x + 18 * dp, y + 16 * dp);
            bar.setStyle(Paint.Style.STROKE);
            bar.setStrokeWidth(1.5f * dp);
            bar.setColor(0xFF4A525C);
            c.drawRoundRect(body, 3 * dp, 3 * dp, bar);
            bar.setStyle(Paint.Style.FILL);
            c.drawRect(x + 18 * dp, y + 5 * dp, x + 21 * dp, y + 11 * dp, bar);
            bar.setColor(chg ? 0xFF6E8B3D : pct <= 15 ? 0xFF8B3D3D : 0xFF4F6A78);
            float in = 3 * dp;
            c.drawRect(body.left + in, body.top + in, body.left + in + (body.width() - 2 * in) * Math.max(0, Math.min(100, pct)) / 100f, body.bottom - in, bar);
            c.drawText(pct + "%" + (chg ? " ⚡" : ""), x, y + 34 * dp, small);
        }
    }
}
