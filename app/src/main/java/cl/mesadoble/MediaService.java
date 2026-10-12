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

    private void start(int i) {
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
        mp = new MediaPlayer();
        prepared = false;
        try {
            mp.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            mp.setDataSource(path);
            mp.setOnPreparedListener(p -> { prepared = true; p.start(); foreground(); tick.run(); });
            mp.setOnCompletionListener(p -> { if (idx + 1 < list.size()) start(idx + 1); else { p.seekTo(0); p.pause(); foreground(); publish(); } });
            mp.setOnErrorListener((p, w, e) -> { if (idx + 1 < list.size()) start(idx + 1); else stopAll(); return true; });
            mp.prepareAsync();
        } catch (Exception e) { if (idx + 1 < list.size()) start(idx + 1); else stopAll(); }
        foreground();
    }

    private void release() {
        if (mp != null) { try { mp.release(); } catch (Exception ignored) { } mp = null; }
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            publish();
            h.removeCallbacks(this);
            if (mp != null) h.postDelayed(this, 500);
        }
    };

    private void publish() {
        JSONObject s = new JSONObject();
        try {
            s.put("kind", MediaHub.AUDIO); s.put("title", title); s.put("sub", artist.isEmpty() ? new File(list.get(idx)).getParentFile().getName() : artist);
            s.put("pos", prepared && mp != null ? mp.getCurrentPosition() : 0); s.put("dur", prepared && mp != null ? mp.getDuration() : 0);
            s.put("play", prepared && mp != null && mp.isPlaying()); s.put("idx", idx); s.put("n", list.size());
        } catch (Exception ignored) { }
        MediaHub.state(this, s);
    }

    @Override public void command(JSONObject m) {
        String a = m.optString("a");
        double v = m.optDouble("v", 0);
        if (mp == null && !"stop".equals(a)) return;
        switch (a) {
            case "toggle": if (prepared) { if (mp.isPlaying()) mp.pause(); else mp.start(); } break;
            case "play": if (prepared) mp.start(); break;
            case "pause": if (prepared) mp.pause(); break;
            case "seek": if (prepared) mp.seekTo((int) v); break;
            case "rel": if (prepared) mp.seekTo(Math.max(0, Math.min(mp.getDuration(), mp.getCurrentPosition() + (int) v))); break;
            case "next": if (idx + 1 < list.size()) start(idx + 1); break;
            case "prev": if (prepared && mp.getCurrentPosition() > 3000) mp.seekTo(0); else if (idx > 0) start(idx - 1); break;
            case "stop": stopAll(); return;
        }
        foreground();
        publish();
    }

    private void stopAll() {
        h.removeCallbacks(tick);
        release();
        if (MediaHub.current == this) MediaHub.current = null;
        MediaHub.end(this, MediaHub.AUDIO);
        stopForeground(true);
        stopSelf();
    }

    @Override public void onDestroy() {
        h.removeCallbacks(tick);
        release();
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
        boolean playing = mp != null && prepared && mp.isPlaying();
        PendingIntent open = PendingIntent.getActivity(this, 50, new Intent(this, PlayerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(title.isEmpty() ? "Mesa Doble" : title)
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
