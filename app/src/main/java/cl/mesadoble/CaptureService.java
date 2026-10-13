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
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/**
 * Captura del PRINCIPAL (un solo permiso de Android para las dos cosas, porque Android permite una captura a la vez):
 *  - "audio": Boost para apps (YouTube, Netflix…): el sonido de las apps sale también por el extendido;
 *  - "video": Modo APP: la pantalla del principal se ve derecha en el extendido (apps que no giran, como Instagram).
 * Se detiene sola cuando ninguna de las dos está activa.
 */
public class CaptureService extends Service {

    static volatile boolean running, audioOn, videoOn;
    private static final int RATE = 48000;
    private MediaProjection projection;
    private AudioRecord rec;
    private volatile Thread audioThread;
    private long btCheck;
    private volatile boolean quiet;

    /** Enciende o apaga una parte ("audio" o "video"). Si no hay captura todavía, pide el permiso en el principal. */
    static boolean want(Context c, String what, boolean on) {
        if (running) {
            Intent i = new Intent(c, CaptureService.class).setAction("set").putExtra("what", what).putExtra("on", on);
            try { c.startService(i); return true; } catch (Exception e) { return false; }
        }
        return !on;   // sin captura: hay que pedir permiso (lo hace quien llama)
    }

    static void stop(Context c) { c.stopService(new Intent(c, CaptureService.class)); }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent i, int flags, int id) {
        foreground();
        if (i != null && "stop".equals(i.getAction())) { stopSelf(); return START_NOT_STICKY; }
        if (i != null && "set".equals(i.getAction())) {
            if (projection != null) set(i.getStringExtra("what"), i.getBooleanExtra("on", false));
            return START_NOT_STICKY;
        }
        if (i == null || running) { if (i != null && running) set(i.getStringExtra("want"), true); return START_NOT_STICKY; }
        int code = i.getIntExtra("code", Activity.RESULT_CANCELED);
        Intent data = i.getParcelableExtra("data");
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        try { projection = mpm.getMediaProjection(code, data); } catch (Exception e) { projection = null; }
        if (projection == null) { fail("Android no dio el permiso de captura en el principal"); return START_NOT_STICKY; }
        // Android 14+ exige registrar esto antes de mostrar la pantalla en otro lado
        projection.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() { new Handler(Looper.getMainLooper()).post(CaptureService.this::stopSelf); }
        }, new Handler(Looper.getMainLooper()));
        running = true;
        set(i.getStringExtra("want"), true);
        return START_NOT_STICKY;
    }

    private void set(String what, boolean on) {
        Hub hub = Hub.get(this);
        if ("video".equals(what)) {
            if (on == videoOn) { if (on) hub.send("appState", "on", true); return; }
            videoOn = on;
            if (on) ScreenCast.start(this, projection); else ScreenCast.stop();
            hub.appModeOn = on;
            hub.send("appState", "on", on);
            if (on && hub.connected()) hub.send("boostMsg", "msg", "Modo APP activo: las apps que no giran se verán derechas aquí");
        } else if ("audio".equals(what)) {
            if (on == audioOn) { if (on) hub.send("boost", "on", true); return; }
            if (on) startAudio(); else stopAudio();
        }
        if (!audioOn && !videoOn) stopSelf();
        else updateNotification();
    }

    private void fail(String msg) {
        Hub hub = Hub.get(this);
        if (hub.connected()) hub.send("boostMsg", "msg", msg);
        if (!running) stopSelf();
    }

    // ---------------- sonido (Boost para apps) ----------------

    private void startAudio() {
        try { rec = build(projection); } catch (Exception e) { rec = null; }
        if (rec == null || rec.getState() != AudioRecord.STATE_INITIALIZED) {
            if (rec != null) rec.release();
            rec = null;
            fail("Este teléfono no deja capturar el sonido de las apps");
            return;
        }
        audioOn = true;
        Hub hub = Hub.get(this);
        Boost.serve();
        hub.send("boost", "on", true);
        Boost.syncVolume(this, hub, true);
        if (hub.connected()) hub.send("boostMsg", "msg", "Boost activo: el sonido sale por los dos teléfonos");
        Thread t = new Thread(this::loop, "captura");
        audioThread = t;
        t.start();
    }

    private void stopAudio() {
        if (!audioOn) return;
        audioOn = false;
        audioThread = null;
        try { if (rec != null) { rec.stop(); rec.release(); } } catch (Exception ignored) { }
        rec = null;
        Hub hub = Hub.get(this);
        hub.send("boost", "on", false);
        Boost.syncVolume(this, hub, false);
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
        Thread me = Thread.currentThread();
        AudioRecord r = rec;
        int frames = RATE / 50;   // 20 ms
        byte[] st = new byte[frames * 4], mono = new byte[frames * 2];
        try {
            r.startRecording();
            while (audioThread == me) {
                int n = r.read(st, 0, st.length);
                if (n <= 0) continue;
                int f = n / 4;
                for (int k = 0; k < f; k++) {   // izquierda + derecha → un solo canal
                    int l = (short) ((st[k * 4] & 0xff) | (st[k * 4 + 1] << 8)), rr = (short) ((st[k * 4 + 2] & 0xff) | (st[k * 4 + 3] << 8));
                    int m = (l + rr) / 2;
                    mono[k * 2] = (byte) m; mono[k * 2 + 1] = (byte) (m >> 8);
                }
                long now = System.currentTimeMillis();
                if (now - btCheck > 1000) { btCheck = now; quiet = Boost.headphones(this) || Boost.gameInFront; }
                if (!quiet) Boost.send(RATE, mono, f * 2, 0L);   // con audífonos o en un juego no se manda
            }
        } catch (Exception ignored) { }
    }

    @Override public void onDestroy() {
        running = false;
        stopAudio();
        if (videoOn) {
            videoOn = false;
            ScreenCast.stop();
            Hub.get(this).appModeOn = false;
            Hub.get(this).send("appState", "on", false);
        }
        try { if (projection != null) projection.stop(); } catch (Exception ignored) { }
        projection = null;
        super.onDestroy();
    }

    // ---------------- aviso permanente ----------------

    private Notification notification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null && nm.getNotificationChannel("boost") == null)
            nm.createNotificationChannel(new NotificationChannel("boost", "Boost y Modo APP", NotificationManager.IMPORTANCE_LOW));
        PendingIntent off = PendingIntent.getService(this, 61, new Intent(this, CaptureService.class).setAction("stop"),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String title = audioOn && videoOn ? "Boost y Modo APP activos" : videoOn ? "Modo APP activo" : "Boost activo";
        String text = videoOn ? "La pantalla del principal también se ve en el extendido" : "El sonido de las apps también sale por el extendido";
        return new Notification.Builder(this, "boost")
                .setSmallIcon(getResources().getIdentifier("ic_stat", "drawable", getPackageName()))
                .setContentTitle(title)
                .setContentText(text)
                .addAction(new Notification.Action.Builder(null, "Detener", off).build())
                .setOngoing(true)
                .build();
    }

    private void updateNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) try { nm.notify(6, notification()); } catch (Exception ignored) { }
    }

    private void foreground() {
        Notification n = notification();
        try {
            if (android.os.Build.VERSION.SDK_INT >= 29)
                Service.class.getMethod("startForeground", int.class, Notification.class, int.class).invoke(this, 6, n, 32 /* MEDIA_PROJECTION */);
            else startForeground(6, n);
        } catch (Exception e) { try { startForeground(6, n); } catch (Exception ignored) { } }
    }
}
