package cl.mesadoble;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioManager;
import android.os.PowerManager;

/**
 * Bloquear al cerrar la tapa. Al cerrar la carcasa, el imán del parlante de un teléfono queda sobre la brújula
 * del otro y el campo magnético salta de ~100 a más de 1000 µT (medido con la Prueba de sensores). Cuando el salto
 * pasa de 200 µT sobre lo normal por un instante, se bloquean los dos teléfonos; al abrir, se encienden.
 * Se puede elegir qué sensores deciden (Ajustes → Configuración y pruebas → Sensores de la tapa):
 *   imán (brújula), proximidad (algo tapa el sensor) y luz (queda oscuro); con "todos" o con "cualquiera".
 *   preferencias "lidSensors" ("mag,prox,light") y "lidAll" (true = todos tienen que notarlo)
 *   lid {s: close | open}  → el otro teléfono hace lo mismo
 *   lidSens {s, all}       → se guarda igual en los dos
 */
final class LidWatch implements SensorEventListener {

    static final float ON = 200, OFF = 120;
    private static final long CONFIRM = 300;            // ms seguidos con (o sin) imán para decidir
    private static final long WAIT_OPEN = 10 * 60_000;  // tras cerrar, se sigue escuchando 10 min para encender al abrir
    private static LidWatch inst;

    private final Context app;
    private final Hub hub;
    private final SensorManager sm;
    private float base = -1;
    private int n;
    private boolean closed;
    private long pendingSince;
    private PowerManager.WakeLock wl;

    static final String[] SENSORS = {"mag", "prox", "light"};
    static final String[] SENSOR_NAMES = {"Imán (brújula)", "Proximidad", "Luz"};

    static String sensors(Context c) { return c.getSharedPreferences("mesa", Context.MODE_PRIVATE).getString("lidSensors", "mag"); }
    static boolean all(Context c) { return c.getSharedPreferences("mesa", Context.MODE_PRIVATE).getBoolean("lidAll", true); }
    static boolean uses(Context c, String k) { return ("," + sensors(c) + ",").contains("," + k + ","); }

    /** Guarda qué sensores deciden y reinicia la vigilancia. */
    static void setSensors(Context c, Hub hub, String list, boolean all) {
        c.getSharedPreferences("mesa", Context.MODE_PRIVATE).edit().putString("lidSensors", list).putBoolean("lidAll", all).apply();
        if (inst != null) { inst.stop(); inst = null; }
        update(c, hub);
    }

    static boolean enabled(Context c) { return c.getSharedPreferences("mesa", Context.MODE_PRIVATE).getBoolean("lidLock", true); }

    static void setEnabled(Context c, Hub hub, boolean on) {
        c.getSharedPreferences("mesa", Context.MODE_PRIVATE).edit().putBoolean("lidLock", on).apply();
        update(c, hub);
    }

    /** Enciende o apaga la vigilancia según el ajuste. */
    static void update(Context c, Hub hub) {
        Context app = c.getApplicationContext();
        if (enabled(app) && !sensors(app).isEmpty()) { if (inst == null) inst = new LidWatch(app, hub); }
        else if (inst != null) { inst.stop(); inst = null; }
    }

    private LidWatch(Context app, Hub hub) {
        this.app = app;
        this.hub = hub;
        sm = (SensorManager) app.getSystemService(Context.SENSOR_SERVICE);
        useMag = uses(app, "mag"); useProx = uses(app, "prox"); useLight = uses(app, "light"); needAll = all(app);
        if (sm == null) return;
        if (useMag) reg(Sensor.TYPE_MAGNETIC_FIELD);
        if (useProx) reg(Sensor.TYPE_PROXIMITY);
        if (useLight) reg(Sensor.TYPE_LIGHT);
    }

    private void reg(int type) {
        Sensor s = sm.getDefaultSensor(type);
        if (s != null) sm.registerListener(this, s, SensorManager.SENSOR_DELAY_UI);
        else if (type == Sensor.TYPE_MAGNETIC_FIELD) useMag = false;   // sin ese sensor: no cuenta
        else if (type == Sensor.TYPE_PROXIMITY) useProx = false;
        else useLight = false;
    }

    private boolean useMag, useProx, useLight, needAll;
    private boolean magC, proxC, lightC;   // lo que dice cada sensor: ¿tapa cerrada?

    private void stop() {
        if (sm != null) sm.unregisterListener(this);
        release();
    }

    @Override public void onSensorChanged(SensorEvent e) {
        int type = e.sensor.getType();
        if (type == Sensor.TYPE_MAGNETIC_FIELD) {
            float mt = (float) Math.sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]);
            if (base < 0 || n++ < 6) { base = mt; return; }   // las primeras lecturas pueden venir raras
            float dl = Math.abs(mt - base);
            magC = magC ? dl > OFF : dl > ON;
            if (!magC) base += (mt - base) * 0.03f;   // girar el teléfono mueve lo "normal" despacio
        } else if (type == Sensor.TYPE_PROXIMITY) {
            proxC = e.values[0] < Math.min(3f, e.sensor.getMaximumRange());   // algo encima del sensor
        } else if (type == Sensor.TYPE_LIGHT) {
            lightC = lightC ? e.values[0] < 8f : e.values[0] < 2f;            // oscuro (con un margen al abrir)
        }
        boolean cand;
        if (needAll) cand = (!useMag || magC) && (!useProx || proxC) && (!useLight || lightC) && (useMag || useProx || useLight);
        else cand = (useMag && magC) || (useProx && proxC) || (useLight && lightC);
        if (cand == closed) { pendingSince = 0; return; }
        long now = System.currentTimeMillis();
        if (pendingSince == 0) { pendingSince = now; return; }
        if (now - pendingSince < CONFIRM) return;
        pendingSince = 0;
        closed = cand;
        if (closed) onClose(); else onOpen();
    }

    @Override public void onAccuracyChanged(Sensor s, int a) { }

    private boolean inCall() {
        AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        int m = am == null ? 0 : am.getMode();
        return m == AudioManager.MODE_IN_CALL || m == AudioManager.MODE_IN_COMMUNICATION || m == AudioManager.MODE_RINGTONE;
    }

    private void onClose() {
        if (SensorTestActivity.open || inCall()) return;   // probando sensores o en llamada: no se bloquea
        if (hub.connected()) hub.send("lid", "s", "close");
        ScreenSync.lockNow(app, hub);
        // para notar cuando se abre hay que seguir midiendo con la pantalla apagada (un rato, no para siempre)
        try {
            PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
            release();
            wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mesadoble:tapa");
            wl.acquire(WAIT_OPEN);
        } catch (Exception ignored) { }
    }

    private void onOpen() {
        if (SensorTestActivity.open || inCall()) { release(); return; }
        boolean waiting = wl != null && wl.isHeld();
        release();
        if (!waiting) return;   // pasó mucho rato: se enciende con el botón, como siempre
        ScreenSync.wakeNow(app);
        if (hub.connected()) hub.send("lid", "s", "open");
    }

    private void release() {
        try { if (wl != null && wl.isHeld()) wl.release(); } catch (Exception ignored) { }
        wl = null;
    }
}
