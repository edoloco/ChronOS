package cl.mesadoble;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.util.Locale;
import java.util.function.Consumer;

/**
 * Lee proximidad, luz y campo magnético de ESTE teléfono y entrega una foto de los valores
 * (al instante cuando cambia la proximidad; si no, como mucho 5 veces por segundo).
 */
final class SensorProbe implements SensorEventListener {

    private final SensorManager sm;
    private final Consumer<JSONObject> out;
    private final Handler h = new Handler(Looper.getMainLooper());
    private Sensor prox, light, mag;
    private float px = -1, lx = -1, mt = -1;
    private long last;
    private boolean pending;

    SensorProbe(Context c, Consumer<JSONObject> out) {
        this.out = out;
        sm = (SensorManager) c.getSystemService(Context.SENSOR_SERVICE);
        if (sm == null) return;
        prox = sm.getDefaultSensor(Sensor.TYPE_PROXIMITY, true);   // la versión que despierta al teléfono, si existe
        if (prox == null) prox = sm.getDefaultSensor(Sensor.TYPE_PROXIMITY);
        light = sm.getDefaultSensor(Sensor.TYPE_LIGHT);
        mag = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        for (Sensor s : new Sensor[]{prox, light, mag}) if (s != null) sm.registerListener(this, s, SensorManager.SENSOR_DELAY_UI);
        h.postDelayed(this::emit, 300);
    }

    void stop() { if (sm != null) sm.unregisterListener(this); h.removeCallbacksAndMessages(null); }

    /** Sensor de proximidad "virtual" (ultrasonido o por la pantalla): suele fallar fuera de las llamadas. */
    static boolean virtual(Sensor s) {
        if (s == null) return false;
        String n = (s.getName() + " " + s.getVendor()).toLowerCase(Locale.ROOT);
        return n.contains("virtual") || n.contains("elliptic") || n.contains("ultras") || n.contains("fake") || n.contains("tp ") || n.contains("touch");
    }

    @Override public void onSensorChanged(SensorEvent e) {
        int t = e.sensor.getType();
        if (t == Sensor.TYPE_PROXIMITY) { px = e.values[0]; emit(); return; }
        if (t == Sensor.TYPE_LIGHT) lx = e.values[0];
        else if (t == Sensor.TYPE_MAGNETIC_FIELD) mt = (float) Math.sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]);
        long now = System.currentTimeMillis();
        if (now - last >= 200) emit();
        else if (!pending) { pending = true; h.postDelayed(this::emit, 200 - (now - last)); }
    }

    @Override public void onAccuracyChanged(Sensor s, int a) { }

    private void emit() {
        pending = false;
        last = System.currentTimeMillis();
        JSONObject o = new JSONObject();
        try {
            o.put("hasP", prox != null);
            if (prox != null) {
                float max = prox.getMaximumRange();
                o.put("px", px);
                o.put("pmax", max);
                o.put("near", px >= 0 && px < Math.min(5f, max));
                o.put("pn", prox.getName());
                o.put("pv", prox.getVendor());
                o.put("pw", prox.isWakeUpSensor());
                o.put("virt", virtual(prox));
            }
            o.put("hasL", light != null);
            o.put("lx", lx);
            o.put("hasM", mag != null);
            o.put("mt", mt);
        } catch (Exception ignored) { }
        out.accept(o);
    }
}
