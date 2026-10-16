package cl.mesadoble;

import android.app.admin.DevicePolicyManager;
import android.content.Context;

/**
 * Centro de apagado: Android no deja que una app apague o reinicie el teléfono por su cuenta. Lo que sí se puede:
 *  - con la versión completa (accesibilidad), abrir el menú de apagado de Android (Apagar / Reiniciar) en ese teléfono;
 *  - con cualquier versión, bloquear la pantalla (accesibilidad o "administrador del dispositivo").
 *   power {a: menu | lock} (de un teléfono al otro) · powerR {a, ok}
 */
final class Power {

    private Power() { }

    /** true si se pudo. */
    static boolean run(Context c, String a) {
        if ("menu".equals(a)) {
            return MesaAccess.instance != null && MesaAccess.instance.performGlobalAction(6 /* GLOBAL_ACTION_POWER_DIALOG */);
        }
        if ("lock".equals(a)) {
            if (MesaAccess.instance != null && MesaAccess.instance.performGlobalAction(8 /* LOCK_SCREEN */)) return true;
            if (ScreenSync.adminActive(c)) {
                try { ((DevicePolicyManager) c.getSystemService(Context.DEVICE_POLICY_SERVICE)).lockNow(); return true; } catch (Exception ignored) { }
            }
        }
        return false;
    }
}
