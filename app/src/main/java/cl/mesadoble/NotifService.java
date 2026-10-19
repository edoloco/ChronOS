package cl.mesadoble;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;

/**
 * Notificaciones del PRINCIPAL para la barra de ChronOS. Se activa a mano en Ajustes → "Acceso a notificaciones".
 * Cada notificación nueva sale animada en la barra del reloj (si ChronOS está a la vista en el principal) o como
 * tarjeta en el extendido (si el principal está en otra app). También dice qué apps tienen notificaciones
 * pendientes (el punto rojo de las apps se apaga cuando se borran).
 *   notif  {pkg, app, title, text, icon}
 */
public class NotifService extends NotificationListenerService {

    static volatile NotifService instance;
    private static final Map<String, String> icons = new HashMap<>();
    private final Map<String, Long> lastShown = new HashMap<>();

    static boolean enabled(Context c) {
        String s = Settings.Secure.getString(c.getContentResolver(), "enabled_notification_listeners");
        return s != null && s.contains(new ComponentName(c, NotifService.class).flattenToString());
    }

    static Intent settingsIntent() { return new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"); }

    @Override public void onListenerConnected() { instance = this; }
    @Override public void onListenerDisconnected() { if (instance == this) instance = null; }

    /** Apps con notificaciones pendientes (que se pueden borrar). */
    JSONArray pending() {
        TreeSet<String> s = new TreeSet<>();
        try {
            for (StatusBarNotification n : getActiveNotifications())
                if (n.isClearable() && !n.getPackageName().equals(getPackageName()) && !"android".equals(n.getPackageName())) s.add(n.getPackageName());
        } catch (Exception ignored) { }
        return new JSONArray(s);
    }

    /** Borra una notificación (se deslizó en la lista de ChronOS). */
    void dismiss(String key) { try { cancelNotification(key); } catch (Exception ignored) { } }

    /**
     * Abre lo que abriría tocar la notificación (por ejemplo, ese chat de WhatsApp). false si no se pudo:
     * entonces se abre la app.
     */
    boolean open(String key) {
        try {
            for (StatusBarNotification n : getActiveNotifications()) {
                if (!n.getKey().equals(key)) continue;
                android.app.PendingIntent pi = n.getNotification().contentIntent;
                if (pi == null) return false;
                Bundle opts = null;
                if (android.os.Build.VERSION.SDK_INT >= 34) {   // Android 14: hay que permitir que abra una pantalla
                    try {
                        android.app.ActivityOptions ao = android.app.ActivityOptions.makeBasic();
                        android.app.ActivityOptions.class.getMethod("setPendingIntentBackgroundActivityStartMode", int.class).invoke(ao, 1);
                        opts = ao.toBundle();
                    } catch (Exception ignored) { }
                }
                pi.send(this, 0, null, null, null, null, opts);
                if ((n.getNotification().flags & Notification.FLAG_AUTO_CANCEL) != 0) dismiss(key);
                return true;
            }
        } catch (Exception ignored) { }
        return false;
    }

    /** Para la banda tipo noticiero: las pendientes, la más nueva primero (una por conversación/clave). */
    JSONArray items() {
        JSONArray out = new JSONArray();
        try {
            StatusBarNotification[] all = getActiveNotifications();
            java.util.Arrays.sort(all, (a, b) -> Long.compare(b.getPostTime(), a.getPostTime()));
            PackageManager pm = getPackageManager();
            for (StatusBarNotification n : all) {
                String pkg = n.getPackageName();
                if (!n.isClearable() || "android".equals(pkg)) continue;
                if (pkg.equals(getPackageName()) && !NotifTest.TAG.equals(n.getTag())) continue;
                Notification nn = n.getNotification();
                if ((nn.flags & Notification.FLAG_GROUP_SUMMARY) != 0) continue;
                CharSequence t = nn.extras.getCharSequence(Notification.EXTRA_TITLE), x = nn.extras.getCharSequence(Notification.EXTRA_TEXT);
                if (t == null && x == null) continue;
                String label = pkg;
                try { label = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0))); } catch (Exception ignored) { }
                String ic = icons.get(pkg);
                if (ic == null) {
                    try { ic = AppsDialog.iconPng(pm.getApplicationIcon(pkg), 48); } catch (Exception e) { ic = ""; }
                    icons.put(pkg, ic);
                }
                JSONObject o = new JSONObject();
                o.put("key", n.getKey()); o.put("pkg", pkg); o.put("app", label);
                o.put("title", t == null ? "" : t.toString()); o.put("text", x == null ? "" : x.toString());
                o.put("time", n.getPostTime()); o.put("icon", ic);
                out.put(o);
                if (out.length() >= 12) break;
            }
        } catch (Exception ignored) { }
        return out;
    }

    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        Hub hub = Hub.get(this);
        if (!hub.isPrincipal()) return;
        Notification n = sbn.getNotification();
        String pkg = sbn.getPackageName();
        boolean test = pkg.equals(getPackageName()) && NotifTest.TAG.equals(sbn.getTag());   // la prueba de ChronOS sí pasa
        if (test) NotifTest.seenAt = System.currentTimeMillis();
        if ((pkg.equals(getPackageName()) && !test) || !sbn.isClearable()) return;           // propias y permanentes (música, descargas…)
        if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;                         // el resumen de un grupo repite lo mismo
        if ((n.flags & Notification.FLAG_ONLY_ALERT_ONCE) != 0 && lastShown.containsKey(sbn.getKey())) return;   // actualización silenciosa
        Bundle x = n.extras;
        CharSequence title = x.getCharSequence(Notification.EXTRA_TITLE), text = x.getCharSequence(Notification.EXTRA_TEXT);
        if (title == null && text == null) return;
        long now = System.currentTimeMillis();
        Long prev = lastShown.get(sbn.getKey());
        if (prev != null && now - prev < 2500) return;   // la misma, repetida enseguida
        lastShown.put(sbn.getKey(), now);
        JSONObject m = new JSONObject();
        try {
            PackageManager pm = getPackageManager();
            String label = pkg;
            try { ApplicationInfo ai = pm.getApplicationInfo(pkg, 0); label = String.valueOf(pm.getApplicationLabel(ai)); } catch (Exception ignored) { }
            String ic = icons.get(pkg);
            if (ic == null) {
                try { ic = AppsDialog.iconPng(pm.getApplicationIcon(pkg), 96); } catch (Exception e) { ic = ""; }
                icons.put(pkg, ic);
            }
            m.put("t", "notif"); m.put("pkg", pkg); m.put("app", label); m.put("key", sbn.getKey());
            m.put("title", title == null ? "" : title.toString()); m.put("text", text == null ? "" : text.toString()); m.put("icon", ic);
            m.put("here", hub.foreground);   // ¿ChronOS está a la vista en el principal?
        } catch (Exception ignored) { }
        if (hub.foreground) hub.local(m);   // en la barra del reloj
        else if (hub.connected()) hub.send(m);   // como tarjeta en el extendido
        hub.notifsChanged();
    }

    @Override public void onNotificationRemoved(StatusBarNotification sbn) { Hub.get(this).notifsChanged(); }
}
