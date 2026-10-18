package cl.mesadoble;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.provider.Settings;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

/**
 * ChronOS instalado como parte del sistema (paquete ChronOS-sistema.zip, por ejemplo en el J7 Prime):
 *  - /system/etc/chronos.conf trae el papel de fábrica (role=extendido) para no preguntar al primer inicio.
 *  - Con WRITE_SECURE_SETTINGS (que el sistema le da a ChronOS) se activa sola la accesibilidad y se ocultan
 *    las notificaciones y controles de video en la pantalla de bloqueo.
 * En un teléfono normal no hace nada.
 */
final class SystemSetup {

    private SystemSetup() { }

    static final String CONF = "/system/etc/chronos.conf";

    static boolean isSystem(Context c) {
        try { return (c.getPackageManager().getApplicationInfo(c.getPackageName(), 0).flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0; }
        catch (Exception e) { return false; }
    }

    static boolean canSecure(Context c) {
        return c.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED;
    }

    private static String conf(String key) {
        File f = new File(CONF);
        if (!f.canRead()) return null;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String l;
            while ((l = r.readLine()) != null) {
                l = l.trim();
                if (l.startsWith(key + "=")) return l.substring(key.length() + 1).trim();
            }
        } catch (Exception ignored) { }
        return null;
    }

    /** Al crear el Hub, antes de leer el papel: aplica lo que trae el sistema. */
    static void preset(Context c, SharedPreferences prefs) {
        if (!prefs.getBoolean("sysPreset", false)) {
            String role = conf("role");
            if (role != null) {
                SharedPreferences.Editor e = prefs.edit().putBoolean("sysPreset", true);
                if (prefs.getInt("side", -1) < 0) {
                    if ("extendido".equals(role)) e.putInt("side", 0).putBoolean("roleV2", true);
                    else if ("principal".equals(role)) e.putInt("side", 1).putBoolean("roleV2", true);
                }
                if ("si".equals(conf("lockart"))) e.putBoolean("lockArt", true);
                e.apply();
            }
        }
        if (canSecure(c)) secure(c, prefs);
    }

    /** Lo que se puede hacer con permiso de ajustes seguros. */
    static void secure(Context c, SharedPreferences prefs) {
        try {
            // accesibilidad de ChronOS siempre activa (versión completa)
            if (MesaAccess.available(c) && !MesaAccess.isEnabled(c)) {
                String me = new ComponentName(c, MesaAccess.class).flattenToString();
                String list = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
                list = list == null || list.isEmpty() ? me : list + ":" + me;
                Settings.Secure.putString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, list);
                Settings.Secure.putInt(c.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            }
            // pantalla de bloqueo limpia: sin notificaciones ni controles de video
            int clean = prefs.getBoolean("cleanLock", true) ? 0 : 1;
            Settings.Secure.putInt(c.getContentResolver(), "lock_screen_show_notifications", clean);
            Settings.Secure.putInt(c.getContentResolver(), "lock_screen_allow_private_notifications", clean);
            Settings.Secure.putInt(c.getContentResolver(), "media_controls_lock_screen", clean);
        } catch (Exception ignored) { }
    }
}
