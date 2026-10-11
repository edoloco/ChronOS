package cl.mesadoble;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.SystemClock;
import android.view.KeyEvent;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Conexión única entre los dos teléfonos, compartida por toda la app:
 * la mesa, el teclado que escribe y el teclado que recibe usan la misma.
 * Vive mientras el proceso de la app vive (lo sostiene LinkService).
 */
public class Hub implements Link.Listener {

    private static Hub inst;

    public static synchronized Hub get(Context c) {
        if (inst == null) inst = new Hub(c.getApplicationContext());
        return inst;
    }

    private final Context app;
    private final SharedPreferences prefs;
    public final Link link;
    private final List<Link.Listener> listeners = new CopyOnWriteArrayList<>();
    private int state = Link.IDLE, side;
    private String info;
    private boolean started, imeReady, remoteImeReady;

    private Hub(Context c) {
        app = c;
        prefs = c.getSharedPreferences("mesa", Context.MODE_PRIVATE);
        side = prefs.getInt("side", -1);
        link = new Link(c, this);
    }

    public int side() { return side; }
    public int state() { return state; }
    public boolean connected() { return state == Link.CONNECTED; }
    public boolean remoteImeReady() { return remoteImeReady; }

    public void ensureStarted() {
        if (side >= 0 && !started) { started = true; link.start(side == 0); }
    }

    public void setSide(int s) {
        boolean changed = s != side;
        side = s;
        prefs.edit().putInt("side", s).apply();
        if (changed || !started) { started = true; link.start(s == 0); }
    }

    /** Agrega un oyente y le informa de inmediato el estado actual. */
    public void add(Link.Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
        l.onState(state, info);
    }

    public void remove(Link.Listener l) { listeners.remove(l); }

    public void send(JSONObject m) { link.send(m); }

    public void send(String type, Object... kv) {
        JSONObject m = new JSONObject();
        try {
            m.put("t", type);
            for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        } catch (JSONException ignored) { }
        link.send(m);
    }

    // ---------------- teclado receptor ----------------

    /** El teclado Mesa Doble de ESTE teléfono está (o no) listo para recibir texto. */
    public void setImeReady(boolean r) {
        if (imeReady == r) return;
        imeReady = r;
        sendImeState();
    }

    private void sendImeState() { send("kbState", "ready", imeReady); }

    // ---------------- Link.Listener ----------------

    @Override public void onState(int st, String inf) {
        state = st;
        info = inf;
        if (st == Link.CONNECTED) sendImeState();
        else remoteImeReady = false;
        for (Link.Listener l : listeners) l.onState(st, inf);
    }

    @Override public void onMessage(JSONObject m) {
        String t = m.optString("t");
        if ("media".equals(t)) { media(m.optString("k")); return; }
        if ("kbAsk".equals(t)) { sendImeState(); return; }
        if ("kbState".equals(t)) remoteImeReady = m.optBoolean("ready");
        for (Link.Listener l : listeners) l.onMessage(m);
    }

    /** Multimedia y volumen: funcionan aunque el teclado receptor no esté activo. */
    private void media(String k) {
        AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        switch (k) {
            case "vup": am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI); break;
            case "vdown": am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI); break;
            case "mute": am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI); break;
            case "play": mediaKey(am, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE); break;
            case "next": mediaKey(am, KeyEvent.KEYCODE_MEDIA_NEXT); break;
            case "prev": mediaKey(am, KeyEvent.KEYCODE_MEDIA_PREVIOUS); break;
        }
    }

    private void mediaKey(AudioManager am, int code) {
        long t = SystemClock.uptimeMillis();
        am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0));
        am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0));
    }
}
