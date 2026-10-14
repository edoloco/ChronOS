package cl.mesadoble;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.widget.Toast;

/**
 * Modo teléfono normal (en el PRINCIPAL): cuando el extendido se queda sin batería o no aparece, se ofrece volver
 * a la interfaz de siempre del teléfono: su pantalla de inicio y su teclado. Al volver a abrir ChronOS, todo regresa.
 *   phoneOffer (extendido → principal o local) {title}: mostrar la oferta
 */
final class PhoneMode {

    private PhoneMode() { }

    private static long enteredAt = -100000;
    static final String LOST = "No se encuentra el teléfono extendido";

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("mesa", Context.MODE_PRIVATE); }

    static boolean active(Context c) { return prefs(c).getBoolean("normalMode", false); }

    /** Ofrece el modo teléfono normal: en la mesa si está a la vista, si no con un aviso. */
    static void offer(Context c, Hub hub, String title) {
        if (!hub.isPrincipal() || active(c)) return;
        if (hub.foreground) {
            org.json.JSONObject m = new org.json.JSONObject();
            try { m.put("t", "phoneOffer"); m.put("title", title); } catch (Exception ignored) { }
            hub.local(m);
            return;
        }
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (nm.getNotificationChannel("avisos") == null)
            nm.createNotificationChannel(new NotificationChannel("avisos", "Avisos del teclado", NotificationManager.IMPORTANCE_HIGH));
        // tocar el aviso abre la pregunta en ChronOS; los botones hacen cada cosa directo
        Intent ask = new Intent(c, MainActivity.class).putExtra("phoneAsk", title)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(c, 81, ask, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent go = new Intent(c, MainActivity.class).putExtra("phoneNow", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pNormal = PendingIntent.getActivity(c, 85, go, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent fl = new Intent(c, MainActivity.class).putExtra("forceLink", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pForce = PendingIntent.getActivity(c, 86, fl, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        int icon = c.getResources().getIdentifier("ic_stat", "drawable", c.getPackageName());
        Notification n = new Notification.Builder(c, "avisos")
                .setSmallIcon(icon != 0 ? icon : android.R.drawable.stat_notify_more)
                .setContentTitle(title)
                .setContentText(LOST.equals(title) ? "¿Teléfono normal o buscar el extendido?" : "Toca para volver al modo teléfono normal")
                .setContentIntent(pi)
                .addAction(new Notification.Action.Builder(null, "Teléfono normal", pNormal).build())
                .setAutoCancel(true)
                .build();
        if (LOST.equals(title)) {
            n = Notification.Builder.recoverBuilder(c, n).addAction(new Notification.Action.Builder(null, "Buscar extendido", pForce).build()).build();
        }
        try { nm.notify(8, n); } catch (Exception ignored) { }
    }

    /** Vuelve a la interfaz normal del teléfono. */
    static void enter(Activity a, Hub hub) {
        SharedPreferences p = prefs(a);
        enteredAt = android.os.SystemClock.uptimeMillis();
        p.edit().putBoolean("normalMode", true).apply();
        // si ChronOS era la pantalla de inicio, se deja de serlo por ahora (vuelve al abrir ChronOS)
        ComponentName esc = new ComponentName(a, a.getPackageName() + ".Escritorio");
        PackageManager pm = a.getPackageManager();
        if (pm.getComponentEnabledSetting(esc) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
            p.edit().putBoolean("wasLauncher", true).apply();
            pm.setComponentEnabledSetting(esc, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
        }
        // el teclado de siempre
        boolean imePending = false;
        if (MesaAccess.instance != null) MesaAccess.instance.restorePrevious();
        if (Hub.imeSelected(a)) imePending = true;
        if (hub.connected()) hub.send("boostMsg", "msg", "El principal volvió al modo teléfono normal");
        // la pantalla de inicio del teléfono (no la de ChronOS)
        Intent home = homeOfPhone(a);
        try { a.startActivity(home); } catch (Exception e) { a.moveTaskToBack(true); }
        if (imePending) {
            Toast.makeText(a, "Elige tu teclado de siempre", Toast.LENGTH_LONG).show();
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                try { a.startActivity(new Intent(a, ImePickerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception ignored) { }
            }, 900);
        }
    }

    private static Intent homeOfPhone(Context c) {
        Intent h = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        PackageManager pm = c.getPackageManager();
        ResolveInfo best = null;
        for (ResolveInfo r : pm.queryIntentActivities(h, 0)) {
            if (r.activityInfo == null || c.getPackageName().equals(r.activityInfo.packageName)) continue;
            if ("com.android.settings".equals(r.activityInfo.packageName)) continue;   // "inicio de reserva" de Android
            boolean sys = (r.activityInfo.applicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0;
            if (best == null || sys) best = r;
            if (sys) break;
        }
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (best != null) i.setClassName(best.activityInfo.packageName, best.activityInfo.name);
        return i;
    }

    /** Se volvió a abrir ChronOS en el principal: todo como antes. */
    static void exit(Context c, Hub hub) {
        SharedPreferences p = prefs(c);
        if (!p.getBoolean("normalMode", false)) return;
        if (android.os.SystemClock.uptimeMillis() - enteredAt < 4000) return;   // la mesa recién se está yendo
        p.edit().putBoolean("normalMode", false).apply();
        if (p.getBoolean("wasLauncher", false)) {
            p.edit().putBoolean("wasLauncher", false).apply();
            c.getPackageManager().setComponentEnabledSetting(new ComponentName(c, c.getPackageName() + ".Escritorio"),
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
        }
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(8);
    }

    /** El extendido volvió a aparecer estando en modo normal: un aviso para regresar a ChronOS. */
    static void extendedBack(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (nm.getNotificationChannel("avisos") == null)
            nm.createNotificationChannel(new NotificationChannel("avisos", "Avisos del teclado", NotificationManager.IMPORTANCE_HIGH));
        Intent go = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(c, 82, go, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        int icon = c.getResources().getIdentifier("ic_stat", "drawable", c.getPackageName());
        Notification n = new Notification.Builder(c, "avisos")
                .setSmallIcon(icon != 0 ? icon : android.R.drawable.stat_notify_more)
                .setContentTitle("El extendido volvió")
                .setContentText("Toca para volver a ChronOS")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        try { nm.notify(8, n); } catch (Exception ignored) { }
    }
}
