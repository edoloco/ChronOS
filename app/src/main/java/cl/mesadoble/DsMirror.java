package cl.mesadoble;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Base64;
import android.view.WindowManager;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Segunda pantalla de la Nintendo DS (en el PRINCIPAL): graba la zona de la pantalla de abajo del DS (RetroArch en
 * diseño "Híbrido") y la manda al extendido, que la muestra grande en Game-Asist; lo que se toca allá se repite aquí
 * con un dedo virtual (GamePalTouch). Android pide permiso para grabar la pantalla cada vez que se activa.
 *   dsOn / dsOff               → empezar / parar (el extendido)
 *   dsRect {l,t,r,b}           → zona de la pantalla de abajo (0..1 de la pantalla del principal)
 *   dsFull {on}                → mandar la pantalla entera (para marcar la zona)
 *   dsTouch {ph, u, v}         → toque dentro de la zona (0..1)
 *   dsFrame {img, full} / dsState {on, msg} → al extendido
 */
public class DsMirror extends Service {

    static volatile boolean running;
    private static volatile float L = 0.62f, T = 0.5f, R = 0.98f, B = 0.95f;
    private static volatile boolean full;

    /** Mensajes del extendido (en el principal). */
    static void handle(Context c, Hub hub, JSONObject m) {
        String t = m.optString("t");
        switch (t) {
            case "dsOn":
                if (running) { hub.send("dsState", "on", true); return; }
                // sin pasar por el inicio (eso sacaría a RetroArch de la pantalla): directo o con un aviso
                if (hub.startOrNotify(new Intent(c, Ask.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), 93, "Segunda pantalla DS",
                        "Toca para permitir la grabación de pantalla") != Hub.LAUNCHED)
                    hub.send("dsState", "on", false, "msg", "Toca el aviso «Segunda pantalla DS» en el principal");
                break;
            case "dsOff": c.stopService(new Intent(c, DsMirror.class)); break;
            case "dsRect":
                L = (float) m.optDouble("l", L); T = (float) m.optDouble("t", T);
                R = (float) m.optDouble("r", R); B = (float) m.optDouble("b", B);
                c.getSharedPreferences("mesa", MODE_PRIVATE).edit().putString("dsRect", L + "," + T + "," + R + "," + B).apply();
                break;
            case "dsFull": full = m.optBoolean("on"); break;
            case "dsTouch": {
                MesaAccess s = MesaAccess.instance;
                if (s == null) return;
                Point sc = new Point();
                ((WindowManager) c.getSystemService(WINDOW_SERVICE)).getDefaultDisplay().getRealSize(sc);
                float u = (float) m.optDouble("u"), v = (float) m.optDouble("v");
                GamePalTouch.touch(s, 77, m.optInt("ph"), (L + u * (R - L)) * sc.x, (T + v * (B - T)) * sc.y);
                break;
            }
        }
    }

    /** Pide el permiso de grabar la pantalla y arranca el servicio. */
    public static class Ask extends Activity {
        @Override protected void onCreate(Bundle b) {
            super.onCreate(b);
            MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            try { startActivityForResult(mpm.createScreenCaptureIntent(), 74); }
            catch (Exception e) { Hub.get(this).send("dsState", "on", false, "msg", "El principal no permite grabar la pantalla"); finish(); }
        }

        @Override protected void onActivityResult(int req, int res, Intent data) {
            if (res == RESULT_OK && data != null) {
                try { startForegroundService(new Intent(this, DsMirror.class).putExtra("code", res).putExtra("data", data)); }
                catch (Exception e) { Hub.get(this).send("dsState", "on", false, "msg", "No se pudo iniciar la grabación"); }
            } else Hub.get(this).send("dsState", "on", false, "msg", "No se aceptó grabar la pantalla en el principal");
            finish();
        }
    }

    private MediaProjection projection;
    private VirtualDisplay vd;
    private ImageReader reader;
    private HandlerThread thread;
    private long lastSent;
    private int lastHash;
    private int sw, sh;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent i, int flags, int id) {
        foreground();
        if (i != null && "stop".equals(i.getAction())) { stopSelf(); return START_NOT_STICKY; }
        if (i == null || running) return START_NOT_STICKY;
        String saved = getSharedPreferences("mesa", MODE_PRIVATE).getString("dsRect", "");
        String[] p = saved.split(",");
        if (p.length == 4) try { L = Float.parseFloat(p[0]); T = Float.parseFloat(p[1]); R = Float.parseFloat(p[2]); B = Float.parseFloat(p[3]); } catch (Exception ignored) { }
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        try { projection = mpm.getMediaProjection(i.getIntExtra("code", Activity.RESULT_CANCELED), i.getParcelableExtra("data")); }
        catch (Exception e) { projection = null; }
        Hub hub = Hub.get(this);
        if (projection == null) { hub.send("dsState", "on", false, "msg", "Android no dio el permiso de grabar"); stopSelf(); return START_NOT_STICKY; }
        Point sc = new Point();
        ((WindowManager) getSystemService(WINDOW_SERVICE)).getDefaultDisplay().getRealSize(sc);
        // resolución completa: así la pantallita del DS en el principal puede ser bien chica (diseño Híbrido, proporción 3)
        // y aun así llega nítida (más que los 256 × 192 del DS)
        sw = Math.max(2, sc.x); sh = Math.max(2, sc.y);
        thread = new HandlerThread("ds");
        thread.start();
        Handler th = new Handler(thread.getLooper());
        reader = ImageReader.newInstance(sw, sh, PixelFormat.RGBA_8888, 2);
        reader.setOnImageAvailableListener(this::frame, th);
        try {
            projection.registerCallback(new MediaProjection.Callback() { @Override public void onStop() { stopSelf(); } }, th);
            vd = projection.createVirtualDisplay("chronos-ds", sw, sh, getResources().getDisplayMetrics().densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, th);
        } catch (Exception e) { hub.send("dsState", "on", false, "msg", "No se pudo grabar la pantalla"); stopSelf(); return START_NOT_STICKY; }
        running = true;
        hub.send("dsState", "on", true);
        return START_NOT_STICKY;
    }

    private Bitmap buf;

    private void frame(ImageReader r) {
        Image img = null;
        try {
            img = r.acquireLatestImage();
            if (img == null) return;
            long now = System.currentTimeMillis();
            boolean f = full;
            if (now - lastSent < (f ? 250 : 55)) return;   // ~18 cuadros por segundo (4 al marcar la zona)
            Image.Plane pl = img.getPlanes()[0];
            int ps = pl.getPixelStride(), rs = pl.getRowStride(), w = img.getWidth(), h = img.getHeight();
            int bw = rs / ps;
            if (buf == null || buf.getWidth() != bw || buf.getHeight() != h) buf = Bitmap.createBitmap(bw, h, Bitmap.Config.ARGB_8888);
            ByteBuffer bb = pl.getBuffer();
            bb.rewind();
            buf.copyPixelsFromBuffer(bb);
            img.close();
            img = null;
            Bitmap out;
            if (f) out = Bitmap.createScaledBitmap(Bitmap.createBitmap(buf, 0, 0, w, h), 640, Math.max(1, 640 * h / w), true);
            else {
                int x0 = clamp(Math.round(L * w), w - 2), y0 = clamp(Math.round(T * h), h - 2);
                int x1 = Math.max(x0 + 2, clamp(Math.round(R * w), w)), y1 = Math.max(y0 + 2, clamp(Math.round(B * h), h));
                out = Bitmap.createBitmap(buf, x0, y0, x1 - x0, y1 - y0);
                if (out.getWidth() > 512) out = Bitmap.createScaledBitmap(out, 512, Math.max(1, 512 * out.getHeight() / out.getWidth()), true);
            }
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            out.compress(Bitmap.CompressFormat.JPEG, f ? 60 : 72, bo);
            byte[] data = bo.toByteArray();
            int hash = Arrays.hashCode(data);
            if (hash == lastHash && !f) return;   // la imagen no cambió: no se manda de nuevo
            lastHash = hash;
            lastSent = now;
            Hub hub = Hub.get(this);
            if (hub.connected()) hub.send("dsFrame", "img", Base64.encodeToString(data, Base64.NO_WRAP), "full", f);
        } catch (Exception ignored) {
        } finally { if (img != null) img.close(); }
    }

    private static int clamp(int v, int max) { return Math.max(0, Math.min(max, v)); }

    @Override public void onDestroy() {
        running = false;
        try { if (vd != null) vd.release(); } catch (Exception ignored) { }
        try { if (projection != null) projection.stop(); } catch (Exception ignored) { }
        try { if (reader != null) reader.close(); } catch (Exception ignored) { }
        if (thread != null) thread.quitSafely();
        Hub hub = Hub.get(this);
        if (hub.connected()) hub.send("dsState", "on", false);
        super.onDestroy();
    }

    @SuppressWarnings("deprecation")
    private void foreground() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null && nm.getNotificationChannel("ds") == null)
            nm.createNotificationChannel(new NotificationChannel("ds", "Segunda pantalla DS", NotificationManager.IMPORTANCE_LOW));
        PendingIntent off = PendingIntent.getService(this, 63, new Intent(this, DsMirror.class).setAction("stop"),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, "ds")
                .setSmallIcon(getResources().getIdentifier("ic_stat", "drawable", getPackageName()))
                .setContentTitle("Segunda pantalla DS")
                .setContentText("La pantalla de abajo del DS se ve en el extendido")
                .addAction(new Notification.Action.Builder(null, "Detener", off).build())
                .setOngoing(true)
                .build();
        try {
            if (android.os.Build.VERSION.SDK_INT >= 29)
                Service.class.getMethod("startForeground", int.class, Notification.class, int.class).invoke(this, 9, n, 32 /* MEDIA_PROJECTION */);
            else startForeground(9, n);
        } catch (Exception e) { try { startForeground(9, n); } catch (Exception ignored) { } }
    }
}
