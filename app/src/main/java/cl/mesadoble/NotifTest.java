package cl.mesadoble;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;

/**
 * Prueba de las notificaciones en la barra (se hace en el PRINCIPAL, se pida desde cualquiera de los dos):
 * revisa el permiso, "reinicia" el lector de notificaciones si Android lo detuvo (lo apaga y prende),
 * publica un aviso de prueba y mira si el lector lo recibió.
 *   notifTest (extendido → principal) · notifTestR {perm, conn, post, seen, fixed}
 */
final class NotifTest {

    static final String TAG = "chronos_test";
    static volatile long seenAt;

    private NotifTest() { }

    static void run(Context c, Hub hub) {
        Handler h = new Handler(Looper.getMainLooper());
        boolean perm = NotifService.enabled(c);
        if (!perm) { report(hub, false, false, false, false, false); return; }
        boolean fixed = false;
        if (NotifService.instance == null) {
            // Android lo tenía detenido (pasa en Xiaomi): se apaga y prende el servicio para que lo vuelva a conectar
            fixed = true;
            ComponentName cn = new ComponentName(c, NotifService.class);
            PackageManager pm = c.getPackageManager();
            try {
                pm.setComponentEnabledSetting(cn, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
                pm.setComponentEnabledSetting(cn, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
            } catch (Exception ignored) { }
            try { NotificationListenerService.requestRebind(cn); } catch (Exception ignored) { }
        }
        final boolean f = fixed;
        h.postDelayed(() -> {
            boolean conn = NotifService.instance != null;
            boolean post = post(c);
            long start = System.currentTimeMillis();
            seenAt = 0;
            h.postDelayed(() -> {
                boolean seen = seenAt >= start;
                report(hub, true, conn || seen, post, seen, f);
                NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) h.postDelayed(() -> nm.cancel(TAG, 9), 5000);
            }, 2500);
        }, fixed ? 2000 : 200);
    }

    private static boolean post(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return false;
        if (android.os.Build.VERSION.SDK_INT >= 33
                && c.checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) return false;
        if (!nm.areNotificationsEnabled()) return false;
        if (nm.getNotificationChannel("prueba") == null)
            nm.createNotificationChannel(new NotificationChannel("prueba", "Prueba de notificaciones", NotificationManager.IMPORTANCE_DEFAULT));
        int icon = c.getResources().getIdentifier("ic_stat", "drawable", c.getPackageName());
        Notification n = new Notification.Builder(c, "prueba")
                .setSmallIcon(icon != 0 ? icon : android.R.drawable.stat_notify_more)
                .setContentTitle("Prueba de ChronOS")
                .setContentText("Si ves esto en la barra del reloj o en el extendido, las notificaciones funcionan")
                .setAutoCancel(true)
                .build();
        try { nm.notify(TAG, 9, n); return true; } catch (Exception e) { return false; }
    }

    private static void report(Hub hub, boolean perm, boolean conn, boolean post, boolean seen, boolean fixed) {
        org.json.JSONObject m = new org.json.JSONObject();
        try {
            m.put("t", "notifTestR"); m.put("perm", perm); m.put("conn", conn); m.put("post", post); m.put("seen", seen); m.put("fixed", fixed);
        } catch (Exception ignored) { }
        hub.local(m);
        if (hub.connected()) hub.send(m);
    }
}
