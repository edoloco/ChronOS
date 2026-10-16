package cl.mesadoble;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.IBinder;

/**
 * Boost para apps (YouTube, Netflix…): captura el sonido que reproducen las apps del PRINCIPAL y lo manda al
 * extendido, que lo toca a la vez. Android pide permiso cada vez (aviso de "capturar lo que se reproduce").
 */
public class CaptureService extends Service {

    static volatile boolean running;
    /** Modo "sonido arriba" (en el extendido): lo de las apps de aquí suena en el principal y aquí se silencia. */
    static volatile boolean up;
    private int savedVol = -1;
    private boolean muted;
    private long mutedAt, heardAt;
    private static final int RATE = 48000;
    private MediaProjection projection;
    private AudioRecord rec;
    private volatile boolean stop, quiet;
    private long btCheck;

    static void stop(Context c) { c.stopService(new Intent(c, CaptureService.class)); }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent i, int flags, int id) {
        if (i != null && !running && !"stop".equals(i.getAction())) up = i.getBooleanExtra("up", false);
        foreground();
        if (i != null && "stop".equals(i.getAction())) { stopSelf(); return START_NOT_STICKY; }
        if (i == null || running) return START_NOT_STICKY;
        int code = i.getIntExtra("code", Activity.RESULT_CANCELED);
        Intent data = i.getParcelableExtra("data");
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        try { projection = mpm.getMediaProjection(code, data); } catch (Exception e) { projection = null; }
        if (projection == null) { fail("No se pudo capturar el sonido (Android no dio el permiso)"); return START_NOT_STICKY; }
        try {
            rec = build(projection);
        } catch (Exception e) { rec = null; }
        if (rec == null || rec.getState() != AudioRecord.STATE_INITIALIZED) { fail("Este teléfono no deja capturar el sonido de las apps"); return START_NOT_STICKY; }
        running = true;
        Hub hub = Hub.get(this);
        Boost.serve();
        if (up) {
            hub.send("boostUp", "on", true);
            android.widget.Toast.makeText(this, "El sonido de esta app sale por el principal", android.widget.Toast.LENGTH_SHORT).show();
        } else {
            hub.send("boost", "on", true);
            Boost.syncVolume(this, hub, true);
            if (hub.connected()) hub.send("boostMsg", "msg", "Boost activo: el sonido sale por los dos teléfonos");
        }
        new Thread(this::loop, "captura").start();
        return START_NOT_STICKY;
    }

    private void fail(String msg) {
        Hub hub = Hub.get(this);
        if (hub.connected()) hub.send("boostMsg", "msg", msg);
        stopSelf();
    }

    /** AudioRecord que escucha lo que suenan las otras apps (no ChronOS). Por reflexión: es de Android 10+. */
    private AudioRecord build(MediaProjection mp) throws Exception {
        Class<?> cb = Class.forName("android.media.AudioPlaybackCaptureConfiguration$Builder");
        Object b = cb.getConstructor(MediaProjection.class).newInstance(mp);
        for (int usage : new int[]{1 /* MEDIA: música y video; los juegos (GAME) quedan fuera */})
            cb.getMethod("addMatchingUsage", int.class).invoke(b, usage);
        Object conf = cb.getMethod("build").invoke(b);
        AudioRecord.Builder rb = new AudioRecord.Builder()
                .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build())
                .setBufferSizeInBytes(RATE / 10 * 4);
        AudioRecord.Builder.class.getMethod("setAudioPlaybackCaptureConfig", conf.getClass()).invoke(rb, conf);
        return rb.build();
    }

    private void loop() {
        int frames = RATE / 50;   // 20 ms
        byte[] st = new byte[frames * 4], mono = new byte[frames * 2];
        try {
            rec.startRecording();
            while (!stop) {
                int n = rec.read(st, 0, st.length);
                if (n <= 0) continue;
                int f = n / 4;
                for (int k = 0; k < f; k++) {   // izquierda + derecha → un solo canal
                    int l = (short) ((st[k * 4] & 0xff) | (st[k * 4 + 1] << 8)), r = (short) ((st[k * 4 + 2] & 0xff) | (st[k * 4 + 3] << 8));
                    int m = (l + r) / 2;
                    mono[k * 2] = (byte) m; mono[k * 2 + 1] = (byte) (m >> 8);
                }
                long now = System.currentTimeMillis();
                if (up) { Boost.send(RATE, mono, f * 2, 0L); muteCheck(mono, f, now); continue; }
                if (now - btCheck > 1000) { btCheck = now; quiet = Boost.headphones(this) || Boost.gameInFront; }
                if (!quiet) Boost.send(RATE, mono, f * 2, 0L);   // con audífonos o en un juego no se manda
            }
        } catch (Exception ignored) { }
    }

    /**
     * Modo arriba: cuando ya llega sonido, el parlante de aquí se baja a cero (el sonido sale por el principal).
     * Si en este teléfono bajar el volumen también apaga la captura (queda en silencio), se devuelve el volumen
     * y se recuerda para no volver a intentarlo.
     */
    private void muteCheck(byte[] pcm, int frames, long now) {
        int peak = 0;
        for (int k = 0; k < frames; k += 8) peak = Math.max(peak, Math.abs((short) ((pcm[k * 2] & 0xff) | (pcm[k * 2 + 1] << 8))));
        if (peak > 120) heardAt = now;
        android.media.AudioManager am = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
        if (am == null) return;
        android.content.SharedPreferences p = getSharedPreferences("mesa", MODE_PRIVATE);
        if (!muted && heardAt == now && !p.getBoolean("upMuteBad", false)) {
            savedVol = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
            try { am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, 0, 0); muted = true; mutedAt = now; } catch (Exception ignored) { }
        } else if (muted && savedVol >= 0 && now - mutedAt > 1800 && heardAt < mutedAt && am.isMusicActive()) {
            try { am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, savedVol, 0); } catch (Exception ignored) { }
            savedVol = -1;
            p.edit().putBoolean("upMuteBad", true).apply();
            Hub hub = Hub.get(this);
            if (hub.connected()) hub.send("boostMsg", "msg", "En este teléfono el sonido sale por los dos: baja el volumen del extendido a mano");
        }
    }

    @Override public void onDestroy() {
        stop = true;
        running = false;
        if (up) {
            up = false;
            if (savedVol >= 0) {
                android.media.AudioManager am = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
                try { if (am != null) am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, savedVol, 0); } catch (Exception ignored) { }
            }
            try { if (rec != null) { rec.stop(); rec.release(); } } catch (Exception ignored) { }
            try { if (projection != null) projection.stop(); } catch (Exception ignored) { }
            Hub.get(this).send("boostUp", "on", false);
            super.onDestroy();
            return;
        }
        try { if (rec != null) { rec.stop(); rec.release(); } } catch (Exception ignored) { }
        try { if (projection != null) projection.stop(); } catch (Exception ignored) { }
        Hub hub = Hub.get(this);
        hub.send("boost", "on", false);
        Boost.syncVolume(this, hub, false);
        super.onDestroy();
    }

    @SuppressWarnings("deprecation")
    private void foreground() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null && nm.getNotificationChannel("boost") == null)
            nm.createNotificationChannel(new NotificationChannel("boost", "Boost", NotificationManager.IMPORTANCE_LOW));
        PendingIntent off = PendingIntent.getService(this, 61, new Intent(this, CaptureService.class).setAction("stop"),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, "boost")
                .setSmallIcon(getResources().getIdentifier("ic_stat", "drawable", getPackageName()))
                .setContentTitle(up ? "Sonido por el principal" : "Boost activo")
                .setContentText(up ? "El sonido de las apps de este teléfono sale por el principal" : "El sonido de las apps también sale por el extendido")
                .addAction(new Notification.Action.Builder(null, "Detener", off).build())
                .setOngoing(true)
                .build();
        try {
            if (android.os.Build.VERSION.SDK_INT >= 29)
                Service.class.getMethod("startForeground", int.class, Notification.class, int.class).invoke(this, 6, n, 32 /* MEDIA_PROJECTION */);
            else startForeground(6, n);
        } catch (Exception e) { try { startForeground(6, n); } catch (Exception ignored) { } }
    }
}
