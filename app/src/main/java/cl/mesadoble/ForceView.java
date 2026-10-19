package cl.mesadoble;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.graphics.PixelFormat;
import android.provider.Settings;
import android.view.View;
import android.view.WindowManager;

/**
 * Forzar vista: una ventanita invisible (0 × 0) encima de todo que le pide a Android una orientación. Como está
 * sobre cualquier app, el teléfono entero gira así, incluso las apps hechas solo para vertical.
 * Necesita la accesibilidad de ChronOS (versión completa) o el permiso "Mostrar sobre otras apps".
 *   forceView {v}: el otro teléfono aplica la misma (los dos van unidos, giran juntos)
 */
final class ForceView {

    private ForceView() { }

    static final String[] NAMES = {"No forzar", "Horizontal", "Horizontal invertido", "Vertical", "Vertical invertido"};
    private static final int[] ORIENT = {ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT};

    private static View view;
    private static WindowManager wm;
    private static Context owner;

    static int mode(Context c) { return c.getSharedPreferences("mesa", Context.MODE_PRIVATE).getInt("forceView", 0); }

    static void set(Context c, int m) {
        c.getSharedPreferences("mesa", Context.MODE_PRIVATE).edit().putInt("forceView", m).apply();
        apply(c);
    }

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
