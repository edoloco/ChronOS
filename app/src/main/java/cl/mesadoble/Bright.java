package cl.mesadoble;

import android.content.Context;
import android.content.res.Resources;
import android.provider.Settings;

/**
 * Brillo de las dos pantallas (widget tipo mesa de audio). Cada teléfono cambia su propio brillo del sistema;
 * Android pide el permiso "Modificar ajustes del sistema" una vez en cada teléfono.
 *   brSet {f, done} (al otro teléfono) · brNow {f, ok} (respuesta) · brLock {on} · brAsk
 * f va de 0 a 1 en escala "a ojo" (el valor del sistema es f² para que el control se sienta parejo).
 */
final class Bright {

    static volatile float other = -1;
    static volatile boolean otherOk = true;
    private static float cache = -1;
    private static long cacheAt;

    private Bright() { }

    static int max(Context c) {
        int m = 255;
        try {
            int id = Resources.getSystem().getIdentifier("config_screenBrightnessSettingMaximum", "integer", "android");
            if (id != 0) m = Resources.getSystem().getInteger(id);
        } catch (Exception ignored) { }
        if (m <= 1) m = 255;
        try { m = Math.max(m, Settings.System.getInt(c.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS)); } catch (Exception ignored) { }
        return m;
    }

    /** Brillo de este teléfono (0..1), leído del sistema (con un segundo de caché: se dibuja seguido). */
    static float mine(Context c) {
        long now = System.currentTimeMillis();
        if (cache >= 0 && now - cacheAt < 1000) return cache;
        try {
            int v = Settings.System.getInt(c.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS);
            cache = (float) Math.sqrt(Math.max(0, v) / (float) max(c));
        } catch (Exception e) { cache = 0.5f; }
        cacheAt = now;
        return cache;
    }

    static boolean canWrite(Context c) { return Settings.System.canWrite(c); }

    /** Cambia el brillo de este teléfono (deja el brillo automático apagado). false si falta el permiso. */
    static boolean set(Context c, float f) {
        f = Math.max(0.02f, Math.min(1f, f));
        cache = f;
        cacheAt = System.currentTimeMillis();
        if (!canWrite(c)) return false;
        try {
            Settings.System.putInt(c.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
            Settings.System.putInt(c.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, Math.max(1, Math.round(f * f * max(c))));
            return true;
        } catch (Exception e) { return false; }
    }

    static boolean locked(Context c) { return c.getSharedPreferences("mesa", Context.MODE_PRIVATE).getBoolean("brLock", false); }
    static void setLocked(Context c, boolean on) { c.getSharedPreferences("mesa", Context.MODE_PRIVATE).edit().putBoolean("brLock", on).apply(); }

    static android.content.Intent permIntent(Context c) {
        return new android.content.Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, android.net.Uri.parse("package:" + c.getPackageName()))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
    }
}
