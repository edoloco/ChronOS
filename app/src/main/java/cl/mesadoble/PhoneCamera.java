package cl.mesadoble;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.provider.MediaStore;

import org.json.JSONObject;

/**
 * Controla la app de CÁMARA DEL TELÉFONO en el principal (la de MIUI, Google, etc.).
 * Sin accesibilidad Android solo deja abrirla con instrucciones: foto o video, trasera o delantera, y
 * "sacar foto" como lo pide el asistente de voz (si la cámara lo acepta). Con la versión completa
 * (accesibilidad) además se tocan sus botones: obturador, cambiar cámara, flash y zoom.
 *   phoneCam      (extendido → principal) {a: photo|video|toggle|shot|zoomin|zoomout|flash|state}
 *   phoneCamState (principal → extendido) {front, app, access}
 */
final class PhoneCamera {

    private PhoneCamera() { }

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("mesa", Context.MODE_PRIVATE); }

    /** Paquete de la app de cámara del teléfono. */
    static String pkg(Context c) {
        PackageManager pm = c.getPackageManager();
        Intent i = new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA);
        ResolveInfo def = pm.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY);
        if (def != null && def.activityInfo != null && !"android".equals(def.activityInfo.packageName)) return def.activityInfo.packageName;
        for (ResolveInfo ri : pm.queryIntentActivities(i, 0)) {
            String p = ri.activityInfo.packageName;
            if (!p.equals(c.getPackageName())) return p;
        }
        return "";
    }

    static String label(Context c, String pkg) {
        try { return String.valueOf(c.getPackageManager().getApplicationLabel(c.getPackageManager().getApplicationInfo(pkg, 0))); }
        catch (Exception e) { return "Cámara"; }
    }

    /** Intención para abrir la cámara del teléfono con la cámara elegida (se mandan los nombres que usan las distintas marcas). */
    static Intent intent(String action, String pkg, boolean front) {
        Intent i = new Intent(action);
        if (!pkg.isEmpty()) i.setPackage(pkg);
        i.putExtra("android.intent.extras.CAMERA_FACING", front ? 1 : 0);
        i.putExtra("android.intent.extras.LENS_FACING_FRONT", front ? 1 : 0);
        i.putExtra("android.intent.extras.LENS_FACING_BACK", front ? 0 : 1);
        i.putExtra("android.intent.extra.USE_FRONT_CAMERA", front);
        i.putExtra("com.google.assistant.extra.USE_FRONT_CAMERA", front);
        i.putExtra("camerafacing", front ? "front" : "rear");
        i.putExtra("default_camera", front ? "1" : "0");
        return i;
    }

    static void command(Context c, Hub hub, JSONObject m) {
        String a = m.optString("a");
        String pkg = pkg(c);
        boolean front = prefs(c).getBoolean("pcFront", false);
        MesaAccess acc = MesaAccess.instance;
        String lbl = label(c, pkg);
        switch (a) {
            case "photo":
                hub.startExternal(intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA, pkg, front), pkg, lbl, false);
                break;
            case "video":
                hub.startExternal(intent(MediaStore.INTENT_ACTION_VIDEO_CAMERA, pkg, front), pkg, lbl, false);
                break;
            case "toggle": {
                front = !front;
                prefs(c).edit().putBoolean("pcFront", front).apply();
                // con accesibilidad: se toca el botón de la cámara; si no, se vuelve a abrir con la otra cámara
                if (acc != null && acc.cameraClick("switch")) break;
                Intent i = intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA, pkg, front);
                i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
                hub.startExternal(i, pkg, lbl, false);
                break;
            }
            case "shot": {
                if (acc != null && acc.cameraClick("shot")) break;
                // como "Ok Google, saca una foto": la cámara cuenta unos segundos y dispara (si la app lo acepta)
                Intent i = intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA, pkg, front);
                i.putExtra("android.intent.extra.TIMER_DURATION_SECONDS", m.optInt("v", 3));
                i.putExtra("com.google.assistant.extra.TIMER_DURATION_SECONDS", m.optInt("v", 3));
                i.putExtra("android.intent.extra.CAMERA_OPEN_ONLY", false);
                i.putExtra("com.google.assistant.extra.CAMERA_OPEN_ONLY", false);
                hub.startExternal(i, pkg, lbl, false);
                break;
            }
            case "zoomin": if (acc != null) acc.cameraZoom(true); break;
            case "zoomout": if (acc != null) acc.cameraZoom(false); break;
            case "flash": if (acc != null) acc.cameraClick("flash"); break;
        }
        // el control aparece en el extendido (si no estaba a la vista)
        if (!"state".equals(a) && hub.connected()) hub.send("camApp", "pkg", pkg);
        JSONObject st = new JSONObject();
        try { st.put("t", "phoneCamState"); st.put("front", front); st.put("app", lbl); st.put("access", acc != null); } catch (Exception ignored) { }
        if (hub.connected()) hub.send(st);
        hub.local(st);
    }
}
