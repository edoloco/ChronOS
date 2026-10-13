package cl.mesadoble;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;

/** Música en el principal: sigue sonando aunque se cambie de app. Se controla desde el reproductor flotante. */
public class MediaService extends Service implements MediaHub.Controller {

    private static final String CHANNEL = "musica";
    private MediaPlayer mp;
    private Boost.Engine be;          // "Boost experience": canales repartidos entre los dos teléfonos
    static MediaService instance;
    private ArrayList<String> list = new ArrayList<>();
    private int idx;
    private String title = "", artist = "";
    private boolean prepared;
    private final Handler h = new Handler(Looper.getMainLooper());

    static void play(Context c, ArrayList<String> paths, int idx) {
        Intent i = new Intent(c, MediaService.class).setAction("play").putStringArrayListExtra("paths", paths).putExtra("idx", idx);
        try { c.startForegroundService(i); } catch (Exception ignored) { }
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() { super.onCreate(); instance = this; }

    /** Se activó o desactivó Boost: la canción sigue desde el mismo punto con el otro modo. */
    static void boostChanged(Context c) {
        MediaService s = instance;
        if (s == null || s.list.isEmpty()) {
            if (!Boost.enabled(c)) Hub.get(c).send("boost", "on", false);
            return;
        }
        s.h.post(() -> {
            boolean was = s.isPlaying();
            long p = s.pos();
            s.start(s.idx, p);
            if (!was) s.doPause();
        });
    }

    private boolean isPlaying() { return be != null ? be.playing() : mp != null && prepared && mp.isPlaying(); }
    private long pos() { return be != null ? be.pos() : prepared && mp != null ? mp.getCurrentPosition() : 0; }
    private long dur() { return be != null ? be.dur() : prepared && mp != null ? mp.getDuration() : 0; }
    private void doPlay() { if (be != null) be.play(); else if (prepared) mp.start(); }
    private void doPause() { if (be != null) be.pause(); else if (prepared) mp.pause(); }
    private void doSeek(long ms) { if (be != null) be.seek(ms); else if (prepared) mp.seekTo((int) ms); }

    @Override public int onStartCommand(Intent i, int flags, int id) {
        foreground();
        MediaHub.current = this;
        MediaHub.currentKind = MediaHub.AUDIO;
        if (i != null && "play".equals(i.getAction())) {
            ArrayList<String> p = i.getStringArrayListExtra("paths");
            if (p != null && !p.isEmpty()) { list = p; start(Math.max(0, Math.min(p.size() - 1, i.getIntExtra("idx", 0)))); }
        } else if (i != null && "cmd".equals(i.getAction())) {
            JSONObject m = new JSONObject();
            try { m.put("a", i.getStringExtra("a")); } catch (JSONException ignored) { }
            command(m);
        }
        return START_NOT_STICKY;
    }

    private void start(int i) { start(i, 0); }

    private void start(int i, long startMs) {
        idx = i;
        release();
        String path = list.get(idx);
        title = MediaHub.title(path); artist = "";
        try {
            MediaMetadataRetriever r = new MediaMetadataRetriever();
            r.setDataSource(path);
            String t = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE), a = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
            if (t != null && !t.trim().isEmpty()) title = t.trim();
            if (a != null) artist = a.trim();
            r.release();
        } catch (Exception ignored) { }
        if (Boost.enabled(this)) {
            prepared = true;
            Boost.serve();
            Hub.get(this).send("boost", "on", true);
            Boost.syncVolume(this, Hub.get(this), true);
            be = new Boost.Engine(path, startMs, err -> h.post(() -> {
                if (be == null) return;
                if (idx + 1 < list.size()) start(idx + 1);
                else { be = null; foreground(); publish(); }
            }));
            be.start();
            foreground();
            tick.run();
            return;
        }
        Hub.get(this).send("boost", "on", false);
        Boost.syncVolume(this, Hub.get(this), false);
        mp = new MediaPlayer();
        prepared = false;
        try {
            mp.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            mp.setDataSource(path);
            mp.setOnPreparedListener(p -> { prepared = true; if (startMs > 0) p.seekTo((int) startMs); p.start(); foreground(); tick.run(); });
            mp.setOnCompletionListener(p -> { if (idx + 1 < list.size()) start(idx + 1); else { p.seekTo(0); p.pause(); foreground(); publish(); } });
            mp.setOnErrorListener((p, w, e) -> { if (idx + 1 < list.size()) start(idx + 1); else stopAll(); return true; });
            mp.prepareAsync();
        } catch (Exception e) { if (idx + 1 < list.size()) start(idx + 1); else stopAll(); }
        foreground();
    }

    private void release() {
        if (mp != null) { try { mp.release(); } catch (Exception ignored) { } mp = null; }
        if (be != null) { be.release(); be = null; }
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            publish();
            h.removeCallbacks(this);
            if (mp != null || be != null) h.postDelayed(this, 500);
        }
    };

    private void publish() {
        JSONObject s = new JSONObject();
        try {
            s.put("kind", MediaHub.AUDIO); s.put("title", title); s.put("sub", artist.isEmpty() ? new File(list.get(idx)).getParentFile().getName() : artist);
            s.put("pos", pos()); s.put("dur", dur());
            s.put("play", isPlaying()); s.put("idx", idx); s.put("n", list.size()); s.put("boost", be != null);
        } catch (Exception ignored) { }
        MediaHub.state(this, s);
    }

    @Override public void command(JSONObject m) {
        String a = m.optString("a");
        double v = m.optDouble("v", 0);
        if (mp == null && be == null && !"stop".equals(a)) return;
        switch (a) {
            case "toggle": if (isPlaying()) doPause(); else doPlay(); break;
            case "play": doPlay(); break;
            case "pause": doPause(); break;
            case "seek": doSeek((long) v); break;
            case "rel": doSeek(Math.max(0, Math.min(dur(), pos() + (long) v))); break;
            case "next": if (idx + 1 < list.size()) start(idx + 1); break;
            case "prev": if (pos() > 3000) doSeek(0); else if (idx > 0) start(idx - 1); break;
            case "stop": stopAll(); return;
        }
        foreground();
        publish();
    }

    private void stopAll() {
        h.removeCallbacks(tick);
        release();
        Hub.get(this).send("boost", "on", false);
        Boost.syncVolume(this, Hub.get(this), false);
        if (MediaHub.current == this) MediaHub.current = null;
        MediaHub.end(this, MediaHub.AUDIO);
        stopForeground(true);
        stopSelf();
    }

    @Override public void onDestroy() {
        h.removeCallbacks(tick);
        release();
        if (instance == this) instance = null;
        if (MediaHub.current == this) MediaHub.current = null;
        super.onDestroy();
    }

    private PendingIntent action(String a, int code) {
        return PendingIntent.getService(this, code, new Intent(this, MediaService.class).setAction("cmd").putExtra("a", a), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    @SuppressWarnings("deprecation")
    private void foreground() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Música", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        boolean playing = isPlaying();
        PendingIntent open = PendingIntent.getActivity(this, 50, new Intent(this, PlayerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(title.isEmpty() ? "ChronOS" : title)
                .setContentText(artist.isEmpty() ? "Reproduciendo en el principal" : artist)
                .setContentIntent(open)
                .setOngoing(playing)
                .addAction(new Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_media_previous), "Anterior", action("prev", 51)).build())
                .addAction(new Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play), playing ? "Pausa" : "Reproducir", action("toggle", 52)).build())
                .addAction(new Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_media_next), "Siguiente", action("next", 53)).build())
                .addAction(new Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "Detener", action("stop", 54)).build())
                .setStyle(new Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2))
                .build();
        try { startForeground(5, n); } catch (Exception ignored) { }
    }
}
