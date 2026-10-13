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
    private static final int RATE = 48000;
    private MediaProjection projection;
    private AudioRecord rec;
    private volatile boolean stop;

    static void stop(Context c) { c.stopService(new Intent(c, CaptureService.class)); }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent i, int flags, int id) {
        foreground();
        if (i != null && "stop".equals(i.getAction())) { stopSelf(); return START_NOT_STICKY; }
        if (i == null || running) return START_NOT_STICKY;
        int code = i.getIntExtra("code", Activity.RESULT_CANCELED);
        Intent data = i.getParcelableExtra("data");
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        try { projection = mpm.getMediaProjection(code, data); } catch (Exception e) { projection = null; }
        if (projection == null) { stopSelf(); return START_NOT_STICKY; }
        try {
            rec = build(projection);
        } catch (Exception e) { rec = null; }
        if (rec == null || rec.getState() != AudioRecord.STATE_INITIALIZED) { stopSelf(); return START_NOT_STICKY; }
        running = true;
        Hub hub = Hub.get(this);
        Boost.serve();
        hub.send("boost", "on", true);
        Boost.syncVolume(this, hub, true);
        new Thread(this::loop, "captura").start();
        return START_NOT_STICKY;
    }

    /** AudioRecord que escucha lo que suenan las otras apps (no ChronOS). Por reflexión: es de Android 10+. */
    private AudioRecord build(MediaProjection mp) throws Exception {
        Class<?> cb = Class.forName("android.media.AudioPlaybackCaptureConfiguration$Builder");
        Object b = cb.getConstructor(MediaProjection.class).newInstance(mp);
        for (int usage : new int[]{1 /* MEDIA */, 14 /* GAME */, 0 /* UNKNOWN */})
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
                Boost.send(RATE, mono, f * 2, 0L);
            }
        } catch (Exception ignored) { }
    }

    @Override public void onDestroy() {
        stop = true;
        running = false;
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
                .setContentTitle("Boost activo")
                .setContentText("El sonido de las apps también sale por el extendido")
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
