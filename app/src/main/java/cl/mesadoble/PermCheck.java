package cl.mesadoble;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import org.json.JSONObject;

/**
 * Revisor de permisos (Game Pal): cada teléfono informa su estado y abre el ajuste que falte.
 *   pcAsk → pcRes {s: estado}        pcFix {w: qué ajuste} → se abre en ESE teléfono
 */
final class PermCheck {

    private PermCheck() { }

    static boolean xiaomi() {
        String m = (Build.MANUFACTURER + " " + Build.BRAND).toLowerCase(java.util.Locale.ROOT);
        return m.contains("xiaomi") || m.contains("redmi") || m.contains("poco");
    }

    static JSONObject status(Context c, Hub hub) {
        JSONObject o = new JSONObject();
        try {
            o.put("p", hub.isPrincipal());
            o.put("ver", Updater.installedVersion(c));
            o.put("sdk", Build.VERSION.SDK_INT);
            o.put("model", Build.MODEL);
            o.put("mi", xiaomi());
            o.put("full", MesaAccess.available(c));
            o.put("acc", MesaAccess.isEnabled(c));
            o.put("alive", MesaAccess.instance != null);
            o.put("usage", RecentApps.hasAccess(c));
            o.put("batt", ScreenSync.ignoringBattery(c));
            o.put("wifi", !"sin WiFi".equals(Link.localIp()));
        } catch (Exception ignored) { }
        return o;
    }

    private static Intent details(Context c) {
        return new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + c.getPackageName()));
    }

    /** El ajuste de Android que arregla "w" en este teléfono. */
    static Intent fix(Context c, String w) {
        Intent i;
        switch (w) {
            case "acc": i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS); break;
            case "usage": i = RecentApps.accessIntent(); break;
            case "auto":
                i = new Intent().setComponent(new ComponentName("com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity"));
                break;
            case "wifi": i = new Intent(Settings.ACTION_WIFI_SETTINGS); break;
            case "batt":   // en Xiaomi/Poco el "Ahorro de batería" de la app está en su ficha
                i = xiaomi() ? details(c) : new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                break;
            default: i = details(c);
        }
        if (i.resolveActivity(c.getPackageManager()) == null) i = details(c);
        return i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    static String pkgOf(Intent i) {
        return i.getComponent() != null ? i.getComponent().getPackageName() : "com.android.settings";
    }
}
