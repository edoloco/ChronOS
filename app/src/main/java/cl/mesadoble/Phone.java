package cl.mesadoble;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CallLog;
import android.provider.ContactsContract;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Llamadas del PRINCIPAL (widget de llamadas): últimas llamadas, buscar contactos y llamar.
 *   callLogReq → callLog {items:[{name, num, type, time}], perm}
 *   contactsReq {q} → contacts {q, items:[{name, num}]}
 *   dial {num}: llama (o abre el marcador con el número si falta el permiso de llamar)
 */
final class Phone {

    private Phone() { }

    static boolean can(Context c, String p) { return c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED; }

    static JSONObject callLog(Context c) {
        JSONObject m = new JSONObject();
        JSONArray a = new JSONArray();
        boolean perm = can(c, Manifest.permission.READ_CALL_LOG);
        if (perm) {
            try (Cursor k = c.getContentResolver().query(CallLog.Calls.CONTENT_URI,
                    new String[]{CallLog.Calls.CACHED_NAME, CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE},
                    null, null, CallLog.Calls.DATE + " DESC")) {
                java.util.Set<String> seen = new java.util.HashSet<>();
                while (k != null && k.moveToNext() && a.length() < 20) {
                    String num = k.getString(1) == null ? "" : k.getString(1);
                    if (num.isEmpty() || !seen.add(num.replaceAll("[^0-9+]", ""))) continue;   // una fila por número
                    JSONObject o = new JSONObject();
                    o.put("name", k.getString(0) == null ? "" : k.getString(0));
                    o.put("num", num);
                    o.put("type", k.getInt(2));
                    o.put("time", k.getLong(3));
                    a.put(o);
                }
            } catch (Exception ignored) { }
        }
        try { m.put("t", "callLog"); m.put("items", a); m.put("perm", perm); } catch (Exception ignored) { }
        return m;
    }

    static JSONObject contacts(Context c, String q) {
        JSONObject m = new JSONObject();
        JSONArray a = new JSONArray();
        if (can(c, Manifest.permission.READ_CONTACTS)) {
            String sel = q == null || q.trim().isEmpty() ? null
                    : ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ? OR " + ContactsContract.CommonDataKinds.Phone.NUMBER + " LIKE ?";
            String[] args = sel == null ? null : new String[]{"%" + q.trim() + "%", "%" + q.trim() + "%"};
            try (Cursor k = c.getContentResolver().query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    new String[]{ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER},
                    sel, args, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " COLLATE NOCASE ASC")) {
                java.util.Set<String> seen = new java.util.HashSet<>();
                while (k != null && k.moveToNext() && a.length() < 60) {
                    String name = k.getString(0) == null ? "" : k.getString(0), num = k.getString(1) == null ? "" : k.getString(1);
                    if (!seen.add(name + num.replaceAll("[^0-9+]", ""))) continue;
                    JSONObject o = new JSONObject();
                    o.put("name", name); o.put("num", num);
                    a.put(o);
                }
            } catch (Exception ignored) { }
        }
        try { m.put("t", "contacts"); m.put("q", q == null ? "" : q); m.put("items", a); } catch (Exception ignored) { }
        return m;
    }

    /** Llama desde el principal (si no tiene el permiso de llamar, abre el marcador con el número listo). */
    static void dial(Context c, Hub hub, String num) {
        if (num == null || num.trim().isEmpty()) return;
        boolean call = can(c, Manifest.permission.CALL_PHONE);
        Intent i = new Intent(call ? Intent.ACTION_CALL : Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(num.trim())))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        hub.startExternal(i, "com.android.dialer", "Teléfono", true);
    }
}
