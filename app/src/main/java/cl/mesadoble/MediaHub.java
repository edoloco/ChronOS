package cl.mesadoble;

import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;

/**
 * Fotos, música y videos del principal se ven y se escuchan en el PRINCIPAL con los visores de la app.
 * Los controles aparecen en el extendido (o en el principal si está solo). Mensajes:
 *   mPlay  (extendido → principal) abrir un archivo con su lista
 *   mOpen  (principal → extendido) abrir los controles
 *   mState (principal → ambos)     estado: título, posición, duración, reproduciendo…
 *   mCmd   (cualquiera → principal) play/pausa, adelantar, siguiente…
 *   mEnd   (principal → ambos)     se cerró
 */
public class MediaHub {

    public interface Controller { void command(JSONObject m); }

    static final String IMAGE = "image", VIDEO = "video", AUDIO = "audio";

    static Controller current;
    static String currentKind = "";

    static String kindOf(String name) {
        String t = FilesProvider.mime(name);
        if (t.startsWith("image/")) return IMAGE;
        if (t.startsWith("video/")) return VIDEO;
        if (t.startsWith("audio/") || name.toLowerCase().endsWith(".opus")) return AUDIO;
        return null;
    }

    static String title(String path) {
        String n = new File(path).getName();
        int d = n.lastIndexOf('.');
        return d > 0 ? n.substring(0, d) : n;
    }

    /** Principal: abrir con el visor interno. */
    static void open(Context c, String kind, ArrayList<String> paths, int idx) {
        Hub hub = Hub.get(c);
        currentKind = kind;
        switch (kind) {
            case IMAGE:
                hub.startOrNotify(new Intent(c, PhotoActivity.class).putStringArrayListExtra("paths", paths).putExtra("idx", idx)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP), 41, "Ver foto", "Toca para verla en el principal");
                break;
            case VIDEO:
                hub.startOrNotify(new Intent(c, VideoActivity.class).putStringArrayListExtra("paths", paths).putExtra("idx", idx)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP), 42, "Ver video", "Toca para verlo en el principal");
                break;
            case AUDIO:
                MediaService.play(c, paths, idx);
                break;
        }
        // los controles: en el extendido; si no hay extendido, el reproductor flotante de música aquí mismo
        if (hub.connected()) hub.send("mOpen", "kind", kind);
        else if (AUDIO.equals(kind)) openControls(c, kind);
    }

    /** Abre la ventana de controles en ESTE teléfono. */
    static void openControls(Context c, String kind) {
        Hub hub = Hub.get(c);
        Intent i;
        if (AUDIO.equals(kind)) i = new Intent(c, PlayerActivity.class);
        else if (IMAGE.equals(kind)) i = new Intent(c, RemoteActivity.class).putExtra("kind", kind);
        else i = new Intent(c, KeyboardActivity.class).putExtra("mode", KeyboardActivity.MEDIA).putExtra("manual", true);   // video: modo del teclado
        if ("ext".equals(kind)) i.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        hub.startOrNotify(i, 43, AUDIO.equals(kind) ? "Reproductor" : IMAGE.equals(kind) ? "Controles" : "Control de video", "Toca para abrir los controles");
    }

    /** Desde los controles (aquí o en el otro teléfono). */
    static void cmd(Context c, String a, double v) {
        JSONObject m = new JSONObject();
        try { m.put("t", "mCmd"); m.put("a", a); m.put("v", v); } catch (JSONException ignored) { }
        Hub hub = Hub.get(c);
        if (hub.isPrincipal()) command(c, m); else hub.send(m);
    }

    /** Principal: aplica una orden al visor activo. */
    static void command(Context c, JSONObject m) {
        String a = m.optString("a");
        if ("vol".equals(a)) {
            AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) am.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                    m.optDouble("v") > 0 ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
            return;
        }
        if (current != null) current.command(m);
        else if ("stop".equals(a)) end(c, currentKind);
    }

    static void state(Context c, JSONObject st) {
        Hub hub = Hub.get(c);
        try { st.put("t", "mState"); } catch (JSONException ignored) { }
        if (hub.connected()) hub.send(st);
        hub.local(st);
    }

    static void end(Context c, String kind) {
        JSONObject m = new JSONObject();
        try { m.put("t", "mEnd"); m.put("kind", kind); } catch (JSONException ignored) { }
        Hub hub = Hub.get(c);
        if (hub.connected()) hub.send(m);
        hub.local(m);
    }

    /** Extendido → principal: abrir este archivo con su carpeta como lista. */
    static void requestPlay(Hub hub, String kind, ArrayList<String> paths, int idx) {
        JSONObject m = new JSONObject();
        try { m.put("t", "mPlay"); m.put("kind", kind); m.put("paths", new JSONArray(paths)); m.put("idx", idx); } catch (JSONException ignored) { }
        hub.send(m);
    }

    static ArrayList<String> paths(JSONObject m) {
        ArrayList<String> l = new ArrayList<>();
        JSONArray a = m.optJSONArray("paths");
        for (int i = 0; a != null && i < a.length(); i++) l.add(a.optString(i));
        return l;
    }

    static String fmt(long ms) {
        long s = Math.max(0, ms / 1000);
        return s >= 3600 ? String.format(java.util.Locale.ROOT, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
                : String.format(java.util.Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }
}
