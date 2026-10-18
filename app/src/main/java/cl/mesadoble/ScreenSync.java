package cl.mesadoble;

import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.PowerManager;

/**
 * Pantalla unida: el extendido se enciende, desbloquea y bloquea junto con el principal.
 * El principal avisa cuando su pantalla se apaga ("off"), se enciende ("on") o se desbloquea ("unlock").
 */
public class ScreenSync {

    static final String CHANNEL = "despertar";
    static final int NOTIF = 7;

    static boolean enabled(Context c) { return c.getSharedPreferences("mesa", Context.MODE_PRIVATE).getBoolean("syncScreen", true); }

    static ComponentName admin(Context c) { return new ComponentName(c, LockAdmin.class); }

    static boolean adminActive(Context c) {
        DevicePolicyManager dpm = (DevicePolicyManager) c.getSystemService(Context.DEVICE_POLICY_SERVICE);
        return dpm != null && dpm.isAdminActive(admin(c));
    }

    /** ¿Puede encender la pantalla con una notificación de pantalla completa? (Android 14 lo pide aparte) */
    static boolean canWake(Context c) {
        if (Build.VERSION.SDK_INT < 34) return true;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return true;
        try { return (Boolean) NotificationManager.class.getMethod("canUseFullScreenIntent").invoke(nm); }
        catch (Exception e) { return true; }
    }

    /** Intenta que la conexión WiFi siga viva con la pantalla apagada. */
    static boolean ignoringBattery(Context c) {
        PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        return pm == null || pm.isIgnoringBatteryOptimizations(c.getPackageName());
    }

    /** Principal: escucha su propia pantalla y se la informa al extendido. */
    static void listen(Context app, Hub hub) {
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_USER_PRESENT);
        app.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                if (!hub.isPrincipal() || !enabled(c) || !hub.connected()) return;
                String a = i.getAction();
                if (Intent.ACTION_SCREEN_OFF.equals(a)) hub.send("screen", "s", "off");
                else if (Intent.ACTION_USER_PRESENT.equals(a)) hub.send("screen", "s", "unlock");
                else if (Intent.ACTION_SCREEN_ON.equals(a)) {
                    KeyguardManager km = (KeyguardManager) c.getSystemService(Context.KEYGUARD_SERVICE);
                    hub.send("screen", "s", km != null && km.isKeyguardLocked() ? "on" : "unlock");
                }
            }
        }, f);
    }

    /** Extendido: hace lo mismo que el principal. */
    static void apply(Context app, Hub hub, String s) {
        if (!enabled(app)) return;
        if ("off".equals(s)) { MainActivity.dropOverLock(); lock(app, hub); return; }
        SleepActivity.close();
        wake(app, "unlock".equals(s));
    }

    /** Bloquear este teléfono ahora (tapa cerrada), aunque la sincronización de pantallas esté apagada. */
    static void lockNow(Context app, Hub hub) { MainActivity.dropOverLock(); lock(app, hub); }

    /** Encender este teléfono (tapa abierta): queda en la pantalla de bloqueo. */
    static void wakeNow(Context app) { SleepActivity.close(); wake(app, false); }

    private static void lock(Context app, Hub hub) {
        PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
        if (pm != null && !pm.isInteractive()) return;   // ya está apagada
        // 1) versión completa: bloqueo como el botón de encendido (permite huella al volver)
        if (MesaAccess.instance != null && MesaAccess.instance.performGlobalAction(8 /* GLOBAL_ACTION_LOCK_SCREEN */)) return;
        // 2) "Bloquear pantalla" de administrador del dispositivo
        if (adminActive(app)) {
            try { ((DevicePolicyManager) app.getSystemService(Context.DEVICE_POLICY_SERVICE)).lockNow(); return; } catch (Exception ignored) { }
        }
        // 3) sin permisos: pantalla negra que deja que se apague sola
        if (hub.foreground || hub.keyboardVisible) {
            try { app.startActivity(new Intent(app, SleepActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception ignored) { }
        }
    }

    @SuppressWarnings("deprecation")
    private static void wake(Context app, boolean unlock) {
        PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
        KeyguardManager km = (KeyguardManager) app.getSystemService(Context.KEYGUARD_SERVICE);
        boolean off = pm != null && !pm.isInteractive();
        boolean lockedNow = km != null && km.isKeyguardLocked();
        if (!off && !lockedNow) return;   // ya está activo
        if (pm != null) {
            try {
                PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP
                        | PowerManager.ON_AFTER_RELEASE, "mesadoble:despertar");
                wl.acquire(3000);
            } catch (Exception ignored) { }
        }
        if (!unlock && !off) return;
        // con PIN o huella el extendido no se puede desbloquear desde una app: ChronOS se muestra encima del bloqueo
        boolean secure = km != null && km.isKeyguardSecure();
        Intent i = unlock && secure
                ? new Intent(app, MainActivity.class).putExtra("overLock", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                : new Intent(app, WakeActivity.class).putExtra("unlock", unlock)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
        // la notificación de pantalla completa es la forma permitida de abrir algo con la pantalla bloqueada
        NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            if (nm.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL, "Encender junto al principal", NotificationManager.IMPORTANCE_HIGH);
                ch.setSound(null, null);
                ch.enableVibration(false);
                nm.createNotificationChannel(ch);
            }
            PendingIntent pi = PendingIntent.getActivity(app, 5, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification n = new Notification.Builder(app, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                    .setContentTitle("ChronOS")
                    .setContentText("Encendiendo junto al principal")
                    .setCategory(Notification.CATEGORY_ALARM)
                    .setFullScreenIntent(pi, true)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .setTimeoutAfter(15000)
                    .build();
            try { nm.notify(NOTIF, n); } catch (Exception ignored) { }
        }
        try { app.startActivity(i); } catch (Exception ignored) { }
    }

    static void cancelNotice(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(NOTIF);
    }
}
