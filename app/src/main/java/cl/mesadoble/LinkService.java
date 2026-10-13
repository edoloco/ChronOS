package cl.mesadoble;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

/** Mantiene viva la conexión entre teléfonos aunque la mesa esté en segundo plano. */
public class LinkService extends Service {

    private static final String CHANNEL = "conexion";

    public static void start(Context c) {
        try { c.startForegroundService(new Intent(c, LinkService.class)); } catch (Exception ignored) { }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Conexión entre teléfonos", NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        int icon = getResources().getIdentifier("ic_stat", "drawable", getPackageName());
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(icon != 0 ? icon : android.R.drawable.stat_notify_sync)
                .setContentTitle("ChronOS")
                .setContentText("Manteniendo unidos los dos teléfonos")
                .setContentIntent(open)
                .setOngoing(true)
                .build();
        try { startForeground(1, n); } catch (Exception e) { stopSelf(); return START_NOT_STICKY; }
        Hub.get(this).ensureStarted();
        holdWifi();
        return START_STICKY;
    }

    private android.net.wifi.WifiManager.WifiLock wifiLock;

    /** Mantiene el WiFi despierto con la pantalla apagada, para que el principal pueda encender al extendido. */
    @SuppressWarnings("deprecation")
    private void holdWifi() {
        if (wifiLock != null) return;
        try {
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            wifiLock = wm.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "mesadoble:wifi");
            wifiLock.setReferenceCounted(false);
            wifiLock.acquire();
        } catch (Exception ignored) { }
    }

    @Override public void onDestroy() {
        try { if (wifiLock != null) wifiLock.release(); } catch (Exception ignored) { }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
