package cl.mesadoble;

import android.app.KeyguardManager;
import android.app.WallpaperManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.WindowManager;

/**
 * Pantalla de bloqueo naranja de ChronOS. Android no deja reemplazar la pantalla de bloqueo, pero sí su fondo:
 * se dibuja un degradado naranja (continuo entre los dos teléfonos) con el mensaje de cómo desbloquear, girado en
 * el sentido de las pantallas cuando se usan las dos (principal arriba, extendido abajo, en horizontal).
 * La huella, el PIN y el reloj siguen siendo los de Android.
 *   lockArt {on} → el otro teléfono aplica (o quita) el suyo
 */
final class LockArt {

    private LockArt() { }

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("mesa", Context.MODE_PRIVATE); }
    static boolean enabled(Context c) { return prefs(c).getBoolean("lockArt", false); }

    static final String[] METHODS = {"PIN", "patrón", "contraseña"};
    static int method(Context c) { return prefs(c).getInt("lockMethod", 0); }

    /** Guarda hacia dónde se gira el teléfono en horizontal (1 = 90°, 3 = 270°); si cambió, rehace el fondo. */
    static void noteRotation(Context c, Hub hub, int rot) {
        if (rot != 1 && rot != 3) return;
        if (prefs(c).getInt("landRot", 1) == rot) return;
        prefs(c).edit().putInt("landRot", rot).apply();
        if (enabled(c)) apply(c, hub);
    }

    @SuppressWarnings("deprecation")
    static boolean fingerprint(Context c) {
        try {
            android.hardware.fingerprint.FingerprintManager fm = (android.hardware.fingerprint.FingerprintManager) c.getSystemService(Context.FINGERPRINT_SERVICE);
            return fm != null && fm.isHardwareDetected() && fm.hasEnrolledFingerprints();
        } catch (Exception e) { return false; }
    }

    /** El mensaje según cómo se desbloquea este teléfono. */
    static String message(Context c) {
        KeyguardManager km = (KeyguardManager) c.getSystemService(Context.KEYGUARD_SERVICE);
        boolean secure = km != null && km.isDeviceSecure();
        if (!secure) return "Desliza para desbloquear";
        String m;
        switch (method(c)) {
            case 1: m = "dibuja tu patrón"; break;
            case 2: m = "escribe tu contraseña"; break;
            default: m = "ingresa tu PIN";
        }
        if (fingerprint(c)) return "Pon tu huella o " + m + " para desbloquear";
        return m.substring(0, 1).toUpperCase() + m.substring(1) + " para desbloquear";
    }

    /** Aplica (o quita) el fondo de bloqueo en este teléfono. */
    static void apply(Context c, Hub hub) {
        Context app = c.getApplicationContext();
        boolean on = enabled(app);
        boolean solo = MainActivity.soloMode(app);
        boolean principal = hub.isPrincipal() || solo;
        int r0 = solo ? 0 : prefs(app).getInt("landRot", 1);
        int rot = r0 != 0 && prefs(app).getBoolean("lockFlip", false) ? 4 - r0 : r0;   // por si quedó al revés
        RecentApps.worker.execute(() -> {
            try {
                WallpaperManager wm = WallpaperManager.getInstance(app);
                if (!on) { wm.clear(WallpaperManager.FLAG_LOCK); return; }
                Bitmap b = draw(app, principal, solo, rot);
                wm.setBitmap(b, null, true, WallpaperManager.FLAG_LOCK);
                b.recycle();
            } catch (Exception ignored) { }
        });
    }

    static Bitmap draw(Context c, boolean principal, boolean solo, int rot) {
        Point p = new Point();
        WindowManager wmg = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
        wmg.getDefaultDisplay().getRealSize(p);
        int W = Math.min(p.x, p.y), H = Math.max(p.x, p.y);   // la pantalla de bloqueo es vertical
        Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bmp);
        float d = c.getResources().getDisplayMetrics().density;
        // se dibuja en el espacio "como lo ve el usuario": girado si se usan las dos pantallas en horizontal
        float lw = W, lh = H;
        if (rot == 1 || rot == 3) {
            cv.translate(W / 2f, H / 2f);
            cv.rotate(rot == 1 ? 90 : -90);
            cv.translate(-H / 2f, -W / 2f);
            lw = H; lh = W;
        }
        // degradado continuo: el principal (arriba) más claro, el extendido (abajo) sigue hacia el naranja quemado
        int[] cols = solo ? new int[]{0xFFFFB04A, 0xFFFF7A1A, 0xFFC2410C}
                : principal ? new int[]{0xFFFFC069, 0xFFFF9636, 0xFFFF7A1A}
                : new int[]{0xFFFF7A1A, 0xFFE85D12, 0xFFB8380B};
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setShader(new LinearGradient(0, 0, 0, lh, cols, null, Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, lw, lh, bg);
        // brillos suaves
        glow(cv, lw * (principal ? 0.18f : 0.82f), lh * (principal ? 0.25f : 0.7f), Math.max(lw, lh) * 0.55f, 0x55FFF1D6);
        glow(cv, lw * (principal ? 0.85f : 0.2f), lh * (principal ? 0.95f : 0.1f), Math.max(lw, lh) * 0.45f, 0x40FF5A00);
        glow(cv, lw * 0.5f, principal ? lh * 1.1f : -lh * 0.1f, lw * 0.6f, 0x30FFFFFF);   // la "unión" entre pantallas
        Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
        sheen.setShader(new LinearGradient(0, 0, lw, lh, new int[]{0x00FFFFFF, 0x1AFFFFFF, 0x00FFFFFF}, new float[]{0.3f, 0.5f, 0.7f}, Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, lw, lh, sheen);

        Typeface tf;
        try { tf = Typeface.createFromAsset(c.getAssets(), "fonts/didact.ttf"); } catch (Exception e) { tf = Typeface.SANS_SERIF; }
        TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        tp.setTypeface(tf);
        tp.setColor(0xFFFFFFFF);
        tp.setShadowLayer(6 * d, 0, 2 * d, 0x66802A00);
        float maxW = Math.min(lw * 0.82f, 560 * d);
        if (principal) {
            // logo y nombre arriba, el mensaje grande al centro
            int ic = c.getResources().getIdentifier("ic_launcher", "mipmap", c.getPackageName());
            float logo = 54 * d, top = solo ? lh * 0.58f : lh * 0.18f;
            if (ic != 0) {
                Bitmap lg = BitmapFactory.decodeResource(c.getResources(), ic);
                if (lg != null) cv.drawBitmap(lg, null, new RectF(lw / 2 - logo / 2, top, lw / 2 + logo / 2, top + logo), new Paint(Paint.FILTER_BITMAP_FLAG));
            }
            tp.setTextSize(15 * d);
            tp.setLetterSpacing(0.35f);
            tp.setTextAlign(Paint.Align.CENTER);
            cv.drawText("CHRONOS", lw / 2, top + logo + 24 * d, tp);
            tp.setTextAlign(Paint.Align.LEFT);
            tp.setLetterSpacing(0);
            tp.setTextSize((solo ? 26 : 30) * d);
            text(cv, tp, message(c), lw / 2, top + logo + (solo ? 60 : 56) * d, maxW);
        } else {
            tp.setTextSize(44 * d);
            tp.setLetterSpacing(0.2f);
            tp.setTextAlign(Paint.Align.CENTER);
            cv.drawText("ChronOS", lw / 2, lh * 0.48f, tp);
            tp.setLetterSpacing(0);
            tp.setTextAlign(Paint.Align.LEFT);
            tp.setTextSize(17 * d);
            tp.setColor(0xE6FFFFFF);
            text(cv, tp, "↑  Desbloquea en la pantalla de arriba", lw / 2, lh * 0.48f + 22 * d, maxW);
        }
        return bmp;
    }

    private static void glow(Canvas cv, float x, float y, float r, int col) {
        Paint g = new Paint(Paint.ANTI_ALIAS_FLAG);
        g.setShader(new RadialGradient(x, y, r, col, col & 0x00FFFFFF, Shader.TileMode.CLAMP));
        cv.drawCircle(x, y, r, g);
    }

    /** Texto centrado en varias líneas, empezando en y. */
    private static void text(Canvas cv, TextPaint tp, String s, float cx, float y, float maxW) {
        StaticLayout l = StaticLayout.Builder.obtain(s, 0, s.length(), tp, Math.round(maxW))
                .setAlignment(Layout.Alignment.ALIGN_CENTER).setLineSpacing(0, 1.1f).build();
        cv.save();
        cv.translate(cx - maxW / 2, y);
        l.draw(cv);
        cv.restore();
    }

}
