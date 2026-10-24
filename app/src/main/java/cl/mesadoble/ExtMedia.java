package cl.mesadoble;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.SystemClock;

import org.json.JSONObject;

import java.util.List;

/**
 * Video o música de OTRAS apps del principal (YouTube, Spotify, Netflix…), con la sesión multimedia de Android.
 * Necesita el acceso a notificaciones de ChronOS (el mismo de la banda). Con eso se sabe qué se está viendo
 * (título, canal, duración, miniatura) y se controla de verdad: adelantar/retroceder 10 s y mover la barra.
 *   xState (principal → extendido) {pkg, app, title, sub, dur, pos, play, seek, art?}
 *   xCmd   (extendido → principal) {a: toggle | rel | seek | next | prev, v}
 */
final class ExtMedia {

    private ExtMedia() { }

    /** La sesión de otra app que está sonando (o la última activa). null si no hay o falta el permiso. */
    static MediaController pick(Context c) {
        try {
            MediaSessionManager msm = (MediaSessionManager) c.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (msm == null) return null;
            List<MediaController> l = msm.getActiveSessions(new ComponentName(c, NotifService.class));
            MediaController any = null;
            for (MediaController mc : l) {
                if (c.getPackageName().equals(mc.getPackageName())) continue;   // el reproductor propio va aparte
                PlaybackState st = mc.getPlaybackState();
                if (st != null && st.getState() == PlaybackState.STATE_PLAYING) return mc;
                if (any == null) any = mc;
            }
            return any;
        } catch (Exception e) { return null; }
    }

    static long position(PlaybackState st) {
        if (st == null) return 0;
        long p = st.getPosition();
        if (st.getState() == PlaybackState.STATE_PLAYING && st.getLastPositionUpdateTime() > 0)
            p += (long) ((SystemClock.elapsedRealtime() - st.getLastPositionUpdateTime()) * st.getPlaybackSpeed());
        return Math.max(0, p);
    }

    private static String lastArtKey = "";

    /** Estado para el extendido (null si no hay nada sonando en otra app). */
    static JSONObject state(Context c) {
        MediaController mc = pick(c);
        if (mc == null) return null;
        try {
            MediaMetadata md = mc.getMetadata();
            PlaybackState st = mc.getPlaybackState();
            JSONObject o = new JSONObject();
            o.put("t", "xState");
            o.put("pkg", mc.getPackageName());
            String app = mc.getPackageName();
            try { app = c.getPackageManager().getApplicationLabel(c.getPackageManager().getApplicationInfo(mc.getPackageName(), 0)).toString(); }
            catch (Exception ignored) { }
            o.put("app", app);
            String title = md == null ? "" : first(md, MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
            String sub = md == null ? "" : first(md, MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
            o.put("title", title);
            o.put("sub", sub);
            long dur = md == null ? 0 : md.getLong(MediaMetadata.METADATA_KEY_DURATION);
            o.put("dur", Math.max(0, dur));
            o.put("pos", position(st));
            o.put("play", st != null && st.getState() == PlaybackState.STATE_PLAYING);
            o.put("seek", st != null && (st.getActions() & PlaybackState.ACTION_SEEK_TO) != 0);
            // miniatura: solo cuando cambia el video (para no mandarla cada segundo)
            String key = mc.getPackageName() + "|" + title;
            if (md != null && !key.equals(lastArtKey)) {
                Bitmap b = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
                if (b == null) b = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
                if (b == null) b = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
                if (b != null) {
                    float k = Math.min(1f, 320f / Math.max(b.getWidth(), b.getHeight()));
                    Bitmap s = Bitmap.createScaledBitmap(b, Math.max(1, Math.round(b.getWidth() * k)), Math.max(1, Math.round(b.getHeight() * k)), true);
                    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                    s.compress(Bitmap.CompressFormat.JPEG, 80, bos);
                    o.put("art", android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP));
                }
                lastArtKey = key;
            }
            return o;
        } catch (Exception e) { return null; }
    }

    /** Que la próxima vez se mande la miniatura otra vez (por ejemplo, al reconectar). */
    static void resetArt() { lastArtKey = ""; }

    private static String first(MediaMetadata md, String... keys) {
        for (String k : keys) { CharSequence s = md.getText(k); if (s != null && s.length() > 0) return s.toString(); }
        return "";
    }

    /** Órdenes del extendido. true si había una sesión que las recibió. */
    static boolean command(Context c, JSONObject m) {
        MediaController mc = pick(c);
        if (mc == null) return false;
        MediaController.TransportControls tc = mc.getTransportControls();
        PlaybackState st = mc.getPlaybackState();
        String a = m.optString("a");
        double v = m.optDouble("v", 0);
        switch (a) {
            case "toggle":
                if (st != null && st.getState() == PlaybackState.STATE_PLAYING) tc.pause(); else tc.play();
                break;
            case "play": tc.play(); break;
            case "pause": tc.pause(); break;
            case "next": tc.skipToNext(); break;
            case "prev": tc.skipToPrevious(); break;
            case "rel": {
                long to = Math.max(0, position(st) + (long) v);
                MediaMetadata md = mc.getMetadata();
                long dur = md == null ? 0 : md.getLong(MediaMetadata.METADATA_KEY_DURATION);
                if (dur > 0) to = Math.min(dur - 500, to);
                tc.seekTo(to);
                break;
            }
            case "seek": tc.seekTo(Math.max(0, (long) v)); break;
            default: return false;
        }
        return true;
    }
}
