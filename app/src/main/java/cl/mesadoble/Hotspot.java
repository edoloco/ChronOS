package cl.mesadoble;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.regex.Pattern;

/**
 * Zona Wi-Fi con un toque. Android no deja que una app la encienda o apague sola, así que ChronOS (con su
 * accesibilidad) abre la pantalla de la zona Wi-Fi, toca el interruptor y vuelve atrás, todo de una vez.
 * Sin accesibilidad (versión liviana) queda abierta la pantalla para tocarlo a mano.
 */
final class Hotspot {

    private Hotspot() { }

    private static final Handler h = new Handler(Looper.getMainLooper());
    private static final Pattern NAME = Pattern.compile("(?i)(zona wi-?fi|zona m[oó]vil|punto de acceso|hotspot|anclaje (a )?(red )?wi-?fi|compartir (la )?conexi[oó]n wi-?fi)");
    private static boolean busy;

    static boolean isOn(Context c) {
        try {
            WifiManager w = (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            return w != null && (Boolean) w.getClass().getMethod("isWifiApEnabled").invoke(w);
        } catch (Throwable e) { return false; }
    }

    /** Enciende o apaga la zona Wi-Fi de este teléfono. done(true) si quedó como se pidió. */
    static void set(Context c, boolean on, java.util.function.Consumer<Boolean> done) {
        Context app = c.getApplicationContext();
        if (isOn(app) == on) { if (done != null) done.accept(true); return; }
        MesaAccess s = MesaAccess.instance;
        if (!openSettings(app)) { if (done != null) done.accept(false); return; }
        if (s == null || busy) { if (done != null) done.accept(false); return; }
        busy = true;
        final long start = System.currentTimeMillis();
        final int[] pages = {1};
        Runnable step = new Runnable() {
            @Override public void run() {
                if (isOn(app) == on) { finish(s, pages[0], done, true); return; }
                if (System.currentTimeMillis() - start > 7000) { busy = false; if (done != null) done.accept(false); return; }
                int r = tap(s, on);
                if (r == 2) pages[0]++;   // entró a la pantalla de la zona Wi-Fi: el interruptor está ahí
                h.postDelayed(this, r == 1 ? 900 : 450);
            }
        };
        h.postDelayed(step, 700);
    }

    private static void finish(MesaAccess s, int pages, java.util.function.Consumer<Boolean> done, boolean ok) {
        busy = false;
        // volver a donde se estaba (las pantallas de Ajustes que se abrieron)
        for (int i = 0; i < pages; i++) h.postDelayed(() -> s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK), 250 + i * 350L);
        if (done != null) h.postDelayed(() -> done.accept(ok), 300 + pages * 350L);
    }

    private static boolean openSettings(Context c) {
        Intent[] tries = {
                new Intent().setComponent(new ComponentName("com.android.settings", "com.android.settings.Settings$WifiTetherSettingsActivity")),
                new Intent().setComponent(new ComponentName("com.android.settings", "com.android.settings.wifi.tether.WifiTetherSettingsActivity")),
                new Intent().setComponent(new ComponentName("com.android.settings", "com.android.settings.TetherSettings")),
                new Intent("android.settings.TETHER_SETTINGS")};
        for (Intent i : tries) {
            try { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION)); return true; } catch (Exception ignored) { }
        }
        return false;
    }

    /** 1 = tocó el interruptor, 2 = entró a la fila de la zona Wi-Fi, 0 = nada todavía. */
    private static int tap(MesaAccess s, boolean on) {
        AccessibilityNodeInfo root;
        try { root = s.getRootInActiveWindow(); } catch (Exception e) { return 0; }
        if (root == null) return 0;
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(root);
        AccessibilityNodeInfo named = null, firstSwitch = null;
        int seen = 0;
        while (!q.isEmpty() && seen++ < 700) {
            AccessibilityNodeInfo n = q.poll();
            if (n == null) continue;
            if (n.isVisibleToUser()) {
                if (n.isCheckable() && firstSwitch == null) firstSwitch = n;
                CharSequence t = n.getText();
                if (named == null && t != null && t.length() < 60 && NAME.matcher(t).find()) named = n;
            }
            for (int i = 0; i < n.getChildCount(); i++) q.add(n.getChild(i));
        }
        // el interruptor de la fila "Zona Wi-Fi…" (en la misma fila, hasta 3 niveles arriba)
        if (named != null) {
            AccessibilityNodeInfo row = named;
            for (int d = 0; row != null && d <= 3; row = row.getParent(), d++) {
                AccessibilityNodeInfo sw = findCheckable(row, 3);
                if (sw != null) { if (sw.isChecked() != on) click(sw); return 1; }
            }
        }
        // ya dentro de la pantalla de la zona Wi-Fi: su interruptor es el de arriba
        if (firstSwitch != null) { if (firstSwitch.isChecked() != on) click(firstSwitch); return 1; }
        // la fila no tiene interruptor: abre la pantalla de la zona Wi-Fi
        if (named != null)
            for (AccessibilityNodeInfo x = named; x != null; x = x.getParent())
                if (x.isClickable()) { x.performAction(AccessibilityNodeInfo.ACTION_CLICK); return 2; }
        return 0;
    }

    private static AccessibilityNodeInfo findCheckable(AccessibilityNodeInfo n, int depth) {
        if (n == null) return null;
        if (n.isCheckable()) return n;
        if (depth <= 0) return null;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo r = findCheckable(n.getChild(i), depth - 1);
            if (r != null) return r;
        }
        return null;
    }

    private static void click(AccessibilityNodeInfo n) {
        for (AccessibilityNodeInfo x = n; x != null; x = x.getParent())
            if (x.isClickable()) { x.performAction(AccessibilityNodeInfo.ACTION_CLICK); return; }
    }
}
