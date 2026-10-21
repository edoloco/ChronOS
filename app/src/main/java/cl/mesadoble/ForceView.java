package cl.mesadoble;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.graphics.PixelFormat;
import android.provider.Settings;
import android.view.View;
import android.view.WindowManager;

/**
 * Forzar vista: una ventanita invisible (0 × 0) encima de todo que le pide a Android una orientación. Como está
 * sobre cualquier app, todas giran así, incluso las hechas solo para vertical. Hay un ajuste para el principal
 * ("forceView") y otro para el extendido ("forceViewE"); los dos teléfonos guardan ambos y cada uno aplica el suyo.
 * Necesita la accesibilidad de ChronOS (versión completa) o el permiso "Mostrar sobre otras apps".
 *   forceView {v, w}: w = 1 principal, 0 extendido (sin w: principal, como las versiones anteriores)
 */
final class ForceView {

    private ForceView() { }

    static final String[] NAMES = {"No forzar", "Horizontal", "Horizontal invertido", "Vertical", "Vertical invertido", "Forzar automático"};
    // "Forzar automático": gira según el acelerómetro del principal hacia los 4 lados, aunque la app sea solo
    // vertical y aunque la rotación automática del teléfono esté bloqueada
    private static final int[] ORIENT = {ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT, ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR};

    private static View view;
    private static WindowManager wm;
    private static Context owner;

    private static String key(boolean forPrincipal) { return forPrincipal ? "forceView" : "forceViewE"; }

    /** Ajuste del principal (forPrincipal) o del extendido. */
    static int mode(Context c, boolean forPrincipal) { return c.getSharedPreferences("mesa", Context.MODE_PRIVATE).getInt(key(forPrincipal), 0); }

    /** Ajuste de ESTE teléfono. */
    static int mode(Context c) { return mode(c, principal(c)); }

    static void set(Context c, boolean forPrincipal, int m) {
        c.getSharedPreferences("mesa", Context.MODE_PRIVATE).edit().putInt(key(forPrincipal), m).apply();
        apply(c);
    }

    static boolean principal(Context c) { return c.getSharedPreferences("mesa", Context.MODE_PRIVATE).getInt("side", -1) == 1; }

    /** ¿Se puede forzar en este teléfono? */
    static boolean possible(Context c) { return MesaAccess.instance != null || Settings.canDrawOverlays(c); }

    /** Pone, cambia o quita la ventana según el ajuste. true si quedó aplicado. */
    static synchronized boolean apply(Context c) {
        int m = mode(c);
        if (m <= 0 || m >= ORIENT.length) { remove(); return true; }
        // con la accesibilidad, su propia capa (no pide permiso); si no, "Mostrar sobre otras apps"
        Context ctx = MesaAccess.instance != null ? MesaAccess.instance : c.getApplicationContext();
        int type = MesaAccess.instance != null ? WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                : WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        if (MesaAccess.instance == null && !Settings.canDrawOverlays(c)) { remove(); return false; }
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(0, 0, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT);
        lp.screenOrientation = ORIENT[m];
        try {
            if (view != null && owner == ctx) { wm.updateViewLayout(view, lp); return true; }
            remove();
            wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            view = new View(ctx);
            wm.addView(view, lp);
            owner = ctx;
            return true;
        } catch (Exception e) { view = null; return false; }
    }

    static synchronized void remove() {
        if (view != null && wm != null) try { wm.removeView(view); } catch (Exception ignored) { }
        view = null;
        owner = null;
    }
}
