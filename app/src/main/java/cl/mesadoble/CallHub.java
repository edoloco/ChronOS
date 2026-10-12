package cl.mesadoble;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;
import android.telecom.TelecomManager;
import android.telephony.TelephonyManager;

import org.json.JSONObject;

/**
 * Llamadas del PRINCIPAL: el principal muestra quién llama (su pantalla de llamada de siempre) y el extendido
 * muestra los controles para contestar o colgar. Sin accesibilidad: Android lo permite con los permisos
 * "Teléfono" (estado de la llamada y contestar/colgar), "Contactos" (el nombre) y "Registro de llamadas" (el número).
 *   call    (principal → extendido) {state: ring|active|idle, number, name}
 *   callCmd (extendido → principal) {a: answer|end}
 *   callAck (principal → extendido) {ok, msg}
 */
final class CallHub {

    static final String[] PERMS = {Manifest.permission.READ_PHONE_STATE, Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.READ_CONTACTS, Manifest.permission.READ_CALL_LOG};

    /** Último estado conocido (en el extendido, para la pantalla de llamada). */
    static volatile JSONObject last;
    private static String lastState = "idle", lastNumber = "";

    private CallHub() { }

    static boolean has(Context c, String p) { return c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED; }

    static boolean ready(Context c) { return has(c, Manifest.permission.READ_PHONE_STATE) && has(c, Manifest.permission.ANSWER_PHONE_CALLS); }

    /** Principal: escucha el estado del teléfono (lo llama Hub al iniciar). */
    static void listen(Context c, Hub hub) {
        c.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context cx, Intent i) {
                if (!hub.isPrincipal()) return;
                String st = i.getStringExtra(TelephonyManager.EXTRA_STATE);
                @SuppressWarnings("deprecation")
                String num = i.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER);
                String state = TelephonyManager.EXTRA_STATE_RINGING.equals(st) ? "ring"
                        : TelephonyManager.EXTRA_STATE_OFFHOOK.equals(st) ? "active" : "idle";
                if (num != null && !num.isEmpty()) lastNumber = num;
                // Android avisa dos veces (sin y con número): se reenvía si llegó el número
                if (state.equals(lastState) && (num == null || num.isEmpty())) return;
                if ("idle".equals(state)) lastNumber = "";
                lastState = state;
                JSONObject m = new JSONObject();
                try {
                    m.put("t", "call"); m.put("state", state); m.put("number", lastNumber);
                    m.put("name", lastNumber.isEmpty() ? "" : name(cx, lastNumber));
                } catch (Exception ignored) { }
                if (hub.connected()) hub.send(m);
            }
        }, new IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED));
    }

    /** Nombre del contacto, si está guardado. */
    static String name(Context c, String number) {
        if (!has(c, Manifest.permission.READ_CONTACTS)) return "";
        Uri u = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number));
        try (Cursor cur = c.getContentResolver().query(u, new String[]{ContactsContract.PhoneLookup.DISPLAY_NAME}, null, null, null)) {
            if (cur != null && cur.moveToFirst()) return cur.getString(0);
        } catch (Exception ignored) { }
        return "";
    }

    /** Principal: contestar o colgar. */
    @SuppressWarnings({"deprecation", "MissingPermission"})
    static void command(Context c, Hub hub, JSONObject m) {
        String a = m.optString("a");
        String err = null;
        TelecomManager tm = (TelecomManager) c.getSystemService(Context.TELECOM_SERVICE);
        if (tm == null) err = "Este teléfono no permite controlar llamadas";
        else if (!has(c, Manifest.permission.ANSWER_PHONE_CALLS)) err = "Falta el permiso de llamadas en el principal (Ajustes → Llamadas)";
        else {
            try {
                if ("answer".equals(a)) tm.acceptRingingCall();
                else if ("end".equals(a)) { if (!tm.endCall()) err = "No se pudo colgar"; }
            } catch (Exception e) { err = "Android no dejó " + ("answer".equals(a) ? "contestar" : "colgar"); }
        }
        hub.send("callAck", "ok", err == null, "msg", err == null ? "" : err);
    }

    /** Extendido: llegó el estado de la llamada. */
    static void onCall(Context c, Hub hub, JSONObject m) {
        last = m;
        String st = m.optString("state");
        if ("ring".equals(st) && !CallActivity.showing) {
            Intent i = new Intent(c, CallActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            hub.startOrNotify(i, 49, "Llamada en el principal", "Toca para contestar o colgar");
        }
    }
}
