package cl.mesadoble;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lanzar a la otra pantalla: una app que está en un teléfono se abre en el otro en el mismo punto
 * (el mismo video en el mismo segundo, la misma página…) y aquí se pausa.
 * No se mueve la ventana (son dos Android distintos): se "clona" con lo que se sabe de ella.
 *   handoff {pkg, label, url, q, pos, playing, pip, yt}: abrir esto aquí
 *   installReq (extendido → principal) {pkg}: no está instalada en el extendido; el principal manda installApp
 * El teléfono que la recibe guarda 'here' para poder devolverla. En el extendido, mientras la app está al frente,
 * ChronOS queda como un botoncito en la esquina (HandoffUi) y el sonido puede salir por el principal.
 */
final class Handoff {

    private Handoff() { }

    static final String YT = "com.google.android.youtube";
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final Pattern YT_ID = Pattern.compile("[A-Za-z0-9_-]{11}");

    /** Lo que se abrió aquí desde el otro teléfono (null si nada). */
    static volatile JSONObject here;

    static boolean active() { return here != null; }

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("mesa", Context.MODE_PRIVATE); }

    /** ¿El sonido de la app lanzada al extendido sale por el principal? (por defecto sí: el Bluetooth está allá) */
    static boolean soundUp(Context c) { return prefs(c).getBoolean("handoffSound", true); }

    // ---------------- qué hay al frente y dónde va ----------------

    /** Sesión de música/video de esa app (necesita el acceso a notificaciones de ChronOS). */
    static MediaController controller(Context c, String pkg) {
        try {
            MediaSessionManager msm = (MediaSessionManager) c.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (msm == null) return null;
            List<MediaController> l = msm.getActiveSessions(new ComponentName(c, NotifService.class));
            MediaController playing = null;
            for (MediaController mc : l) {
                if (pkg != null && pkg.equals(mc.getPackageName())) return mc;
                PlaybackState st = mc.getPlaybackState();
                if (pkg == null && playing == null && st != null && st.getState() == PlaybackState.STATE_PLAYING) playing = mc;
            }
            return playing;
        } catch (Exception e) { return null; }
    }

    private static long position(PlaybackState st) {
        if (st == null || st.getPosition() < 0) return -1;
        long p = st.getPosition();
        if (st.getState() == PlaybackState.STATE_PLAYING && st.getLastPositionUpdateTime() > 0)
            p += (long) ((SystemClock.elapsedRealtime() - st.getLastPositionUpdateTime()) * Math.max(0.1f, st.getPlaybackSpeed()));
        return p;
    }

    /** Arma el "cómo abrirla allá" de la app pkg de este teléfono. pkg null = la que esté sonando. */
    static JSONObject describe(Context c, String pkg, boolean pip) {
        JSONObject o = new JSONObject();
        try {
            MediaController mc = controller(c, pkg);
            if (pkg == null && mc != null) pkg = mc.getPackageName();
            if (pkg == null) return null;
            o.put("pkg", pkg);
            String label = pkg;
            try { label = String.valueOf(c.getPackageManager().getApplicationLabel(c.getPackageManager().getApplicationInfo(pkg, 0))); } catch (Exception ignored) { }
            o.put("label", label);
            o.put("pip", pip);
            o.put("at", SystemClock.uptimeMillis());
            if (mc != null) {
                PlaybackState st = mc.getPlaybackState();
                o.put("pos", position(st));
                o.put("playing", st != null && st.getState() == PlaybackState.STATE_PLAYING);
                MediaMetadata md = mc.getMetadata();
                if (md != null) {
                    String id = md.getString(MediaMetadata.METADATA_KEY_MEDIA_ID), uri = md.getString(MediaMetadata.METADATA_KEY_MEDIA_URI);
                    String title = md.getString(MediaMetadata.METADATA_KEY_TITLE), artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
                    String yt = ytId(id);
                    if (yt == null) yt = ytId(uri);
                    if (YT.equals(pkg)) {
                        if (yt != null) o.put("yt", yt);
                        else if (title != null) o.put("q", title + (artist != null && !artist.isEmpty() ? " " + artist : ""));
                    }
                }
            }
            if (MesaAccess.instance != null && !YT.equals(pkg)) {
                String url = MesaAccess.instance.readUrl(pkg);
                if (url != null) {
                    String yt = url.contains("youtu") ? ytId(url) : null;
                    if (yt != null) o.put("yt", yt);
                    else o.put("url", url);
                }
            }
        } catch (Exception ignored) { }
        return o;
    }

    /** Saca el ID de un video de YouTube de un texto (el ID solo, o un enlace). */
    static String ytId(String s) {
        if (s == null || s.isEmpty()) return null;
        if (YT_ID.matcher(s).matches()) return s;
        Matcher m = Pattern.compile("(?:v=|youtu\\.be/|shorts/|embed/)([A-Za-z0-9_-]{11})").matcher(s);
        return m.find() ? m.group(1) : null;
    }

    /** ¿Se puede abrir allá en el mismo punto? (para la flecha llena o con contorno) */
    static boolean exact(Context c, String pkg) {
        if (YT.equals(pkg) || MesaAccess.isBrowser(pkg) && MesaAccess.instance != null) return true;
        MediaController mc = controller(c, pkg);
        return mc != null && mc.getPlaybackState() != null && mc.getPlaybackState().getPosition() > 0;
    }

    // ---------------- mandar ----------------

    /** Lanza al otro teléfono la app pkg de aquí. */
    static void send(Context c, Hub hub, JSONObject info) {
        if (info == null) return;
        if (!hub.connected()) { Toast.makeText(c, "El otro teléfono no está conectado", Toast.LENGTH_SHORT).show(); return; }
        try {
            // la posición se pone al día justo antes de mandar
            if (info.optLong("pos", -1) > 0 && info.optBoolean("playing"))
                info.put("pos", info.optLong("pos") + SystemClock.uptimeMillis() - info.optLong("at", SystemClock.uptimeMillis()));
            info.put("t", "handoff");
        } catch (Exception ignored) { }
        hub.send(info);
        String pkg = info.optString("pkg");
        pause(c, pkg);
        Toast.makeText(c, "Pasando " + info.optString("label") + (hub.isPrincipal() ? " al extendido" : " al principal"), Toast.LENGTH_SHORT).show();
        if (!info.optBoolean("pip")) main.postDelayed(hub::goDesk, 250);   // aquí queda la mesa de ChronOS
    }

    /** Desde aquí: la app pkg (o la que suena, si es la ventana flotante) se va al otro teléfono. */
    static void launchOther(Context c, Hub hub, String pkg, boolean pip) {
        RecentApps.worker.execute(() -> {
            JSONObject o = describe(c, pkg, pip);
            main.post(() -> send(c, hub, o));
        });
    }

    /** Pausa la música o el video de esa app aquí. */
    static void pause(Context c, String pkg) {
        MediaController mc = controller(c, pkg);
        if (mc != null) { try { mc.getTransportControls().pause(); return; } catch (Exception ignored) { } }
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        if (am == null || !am.isMusicActive()) return;
        am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE));
        am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE));
    }

    // ---------------- recibir ----------------

    static boolean installed(Context c, String pkg) {
        try { c.getPackageManager().getApplicationInfo(pkg, 0); return true; } catch (Exception e) { return false; }
    }

    /** Llegó una app del otro teléfono: abrirla aquí. */
    static void receive(Context c, Hub hub, JSONObject m) {
        String pkg = m.optString("pkg"), label = m.optString("label");
        boolean hasLink = !m.optString("url").isEmpty() || !m.optString("yt").isEmpty();
        if (!pkg.isEmpty() && !installed(c, pkg) && !hasLink) {
            if (!hub.isPrincipal()) {
                Toast.makeText(c, label + " no está en el extendido: te ofrezco instalarla", Toast.LENGTH_LONG).show();
                hub.send("installReq", "pkg", pkg);
            } else Toast.makeText(c, label + " no está instalada en el principal", Toast.LENGTH_LONG).show();
            return;
        }
        if (!hub.isPrincipal() && soundUp(c) && !CaptureService.running && !Boost.headphones(c)) {
            // primero el permiso de captura (Android lo pide cada vez); después se abre la app
            Intent ca = new Intent(c, CaptureActivity.class).putExtra("up", true).putExtra("then", m.toString())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (hub.startExternal(ca, c.getPackageName(), "ChronOS", false) == Hub.LAUNCHED) return;
        }
        open(c, hub, m);
    }

    /** Abre la app con lo que se sabe (enlace, video, búsqueda o solo la app). */
    static void open(Context c, Hub hub, JSONObject m) {
        String pkg = m.optString("pkg"), label = m.optString("label"), url = m.optString("url"), yt = m.optString("yt"), q = m.optString("q");
        long pos = m.optLong("pos", -1);
        Intent i = null;
        boolean seekLater = pos > 1000;
        if (!yt.isEmpty()) {
            i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://youtu.be/" + yt + (pos > 1000 ? "?t=" + pos / 1000 : "")));
            if (installed(c, YT)) i.setPackage(YT);
            seekLater = false;
        } else if (!url.isEmpty()) {
            i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            if (!pkg.isEmpty() && installed(c, pkg)) {
                Intent p = new Intent(i).setPackage(pkg);
                if (p.resolveActivity(c.getPackageManager()) != null) i = p;
            }
        } else if (!q.isEmpty() && installed(c, YT)) {
            i = new Intent(Intent.ACTION_SEARCH).setPackage(YT).putExtra("query", q);
            Toast.makeText(c, "Toca el video en la lista: se busca \"" + q + "\"", Toast.LENGTH_LONG).show();
        }
        if (i == null || i.resolveActivity(c.getPackageManager()) == null) i = c.getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) { Toast.makeText(c, "No pude abrir " + label + " aquí", Toast.LENGTH_SHORT).show(); return; }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (c instanceof android.app.Activity) { try { c.startActivity(i); } catch (Exception e) { hub.startExternal(i, pkg, label); } }
        else hub.startExternal(i, pkg, label);
        try {
            JSONObject h = new JSONObject(m.toString());
            h.put("at", SystemClock.uptimeMillis());
            h.remove("t");
            here = h;
        } catch (Exception ignored) { }
        // apps de música/video sin enlace: se intenta llevarla al mismo segundo por su sesión de medios
        if (seekLater) for (long d : new long[]{2500, 5000}) main.postDelayed(() -> {
            MediaController mc = controller(c, pkg);
            if (mc != null) try { mc.getTransportControls().seekTo(pos + d); } catch (Exception ignored) { }
        }, d);
        // la ventana flotante vuelve a ser flotante (con accesibilidad: ir al inicio la achica)
        if (m.optBoolean("pip") && MesaAccess.instance != null)
            main.postDelayed(() -> { if (MesaAccess.instance != null) MesaAccess.instance.performGlobalAction(2 /* HOME */); }, 3500);
        main.postDelayed(() -> HandoffUi.update(c, hub), 900);
    }

    // ---------------- devolver / terminar (en el teléfono que la recibió) ----------------

    /** La app vuelve al otro teléfono en el punto en que va. */
    static void giveBack(Context c, Hub hub) {
        JSONObject h = here;
        if (h == null) return;
        String pkg = h.optString("pkg");
        RecentApps.worker.execute(() -> {
            JSONObject o = describe(c, pkg, false);
            try {
                if (o == null) o = new JSONObject().put("pkg", pkg).put("label", h.optString("label"));
                // lo que no se pudo leer aquí se completa con lo que llegó (y el tiempo que pasó)
                if (o.optString("yt").isEmpty() && o.optString("url").isEmpty()) {
                    if (!h.optString("yt").isEmpty()) o.put("yt", h.optString("yt"));
                    else if (!h.optString("url").isEmpty()) o.put("url", h.optString("url"));
                }
                if (o.optLong("pos", -1) <= 0 && h.optLong("pos", -1) > 0) {
                    o.put("pos", h.optLong("pos") + SystemClock.uptimeMillis() - h.optLong("at"));
                    o.put("playing", false);
                }
            } catch (Exception ignored) { }
            JSONObject f = o;
            main.post(() -> { send(c, hub, f); end(c, hub, true); });
        });
    }

    /** Se terminó: se para el sonido hacia el principal, vuelve ChronOS y (si kill) se cierra la app. */
    static void end(Context c, Hub hub, boolean kill) {
        JSONObject h = here;
        here = null;
        HandoffUi.hideBubble();
        if (CaptureService.up) CaptureService.stop(c);
        if (h == null) return;
        String pkg = h.optString("pkg");
        if (kill) pause(c, pkg);
        hub.goDesk();
        if (kill) main.postDelayed(() -> {
            try { ((android.app.ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE)).killBackgroundProcesses(pkg); } catch (Exception ignored) { }
        }, 1500);
    }

    // ---------------- Compartir → "Abrir en la otra pantalla" ----------------

    /** Respaldo para cualquier app: se comparte el enlace y se abre en el otro teléfono. */
    public static class Share extends android.app.Activity {
        @Override protected void onCreate(android.os.Bundle b) {
            super.onCreate(b);
            Intent in = getIntent();
            CharSequence txt = in == null ? null : in.getCharSequenceExtra(Intent.EXTRA_TEXT);
            String from = null;
            try { Uri r = getReferrer(); if (r != null && "android-app".equals(r.getScheme())) from = r.getHost(); } catch (Exception ignored) { }
            final String text = txt == null ? "" : txt.toString(), src = from;
            Hub hub = Hub.get(this);
            Context c = getApplicationContext();
            RecentApps.worker.execute(() -> {
                JSONObject o = src != null ? describe(c, src, false) : null;
                try {
                    if (o == null) o = new JSONObject().put("pkg", src == null ? "" : src).put("label", "el enlace").put("at", SystemClock.uptimeMillis());
                    Matcher m = Pattern.compile("https?://\\S+").matcher(text);
                    String url = m.find() ? m.group() : null;
                    String yt = url != null && url.contains("youtu") ? ytId(url) : null;
                    if (yt != null) { o.put("yt", yt); o.remove("url"); }
                    else if (url != null) o.put("url", url);
                    if (o.optString("pkg").isEmpty() && url == null) o = null;
                } catch (Exception ignored) { }
                JSONObject f = o;
                main.post(() -> {
                    if (f == null) Toast.makeText(c, "No hay nada que abrir en la otra pantalla", Toast.LENGTH_SHORT).show();
                    else send(c, hub, f);
                });
            });
            finish();
        }
    }
}
