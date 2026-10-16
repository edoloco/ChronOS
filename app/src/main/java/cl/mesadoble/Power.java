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

    /** Por qué no se puede abrir el menú de apagado aquí: "lite" (versión liviana), "off" (accesibilidad apagada),
     *  "dead" (activada pero Android detuvo el servicio) o null si está todo bien. */
    static String why(Context c) {
        if (!MesaAccess.available(c)) return "lite";
        if (!MesaAccess.isEnabled(c)) return "off";
        if (MesaAccess.instance == null) return "dead";
        return null;
    }

    /** Explicación para el usuario según why(). */
    static String explain(String why, boolean here) {
        String who = here ? "este teléfono" : "el otro teléfono";
        if ("lite".equals(why)) return "En " + who + " está la versión liviana: el menú de apagado necesita la completa.";
        if ("dead".equals(why)) return "En " + who + " la accesibilidad de ChronOS figura activada pero Android la detuvo. Desactívala y vuelve a activarla"
                + " (en Xiaomi/Poco, activa también el Inicio automático de ChronOS).";
        return "En " + who + " falta activar la accesibilidad de ChronOS: Ajustes → Accesibilidad → Apps descargadas → ChronOS."
                + " Si sale \"ajuste restringido\", ve a Ajustes → Aplicaciones → ChronOS → ⋮ → Permitir ajustes restringidos.";
    }

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
