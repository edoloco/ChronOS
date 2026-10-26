package cl.mesadoble;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;

import java.util.Collections;
import java.util.UUID;

/**
 * Separar al alejarlos (Ajustes → Configuración y pruebas → "Al separar los teléfonos").
 * El PRINCIPAL decide si los teléfonos siguen juntos:
 *  - por imán: mide el campo magnético; con los dos juntos se nota el imán del extendido (se calibra una vez);
 *  - por Bluetooth: el extendido emite una señal (BLE) y el principal mide su fuerza (lejos ≈ 3 a 5 m o sin señal).
 * Separados unos segundos → teléfono normal (preguntando o directo, según el ajuste). Juntos otra vez → vuelve ChronOS
 * (si fue la separación la que lo sacó).
 * Prefs: sepMode (0 no, 1 imán, 2 Bluetooth), sepAsk (preguntar), sepMagJ (imán medido con los dos juntos, µT).
 */
final class Proximity implements SensorEventListener {

    static final UUID SVC = UUID.fromString("6d2c3a10-5e1f-4c68-8f00-4368726f4f53");
    static final int OFF = 0, MAG = 1, BT = 2;
    private static final long FAR_MS = 8000, NEAR_MS = 5000;

    private static Proximity inst;

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("mesa", Context.MODE_PRIVATE); }
    static int mode(Context c) { return prefs(c).getInt("sepMode", OFF); }
    static boolean ask(Context c) { return prefs(c).getBoolean("sepAsk", true); }

    private final Context app;
    private final Hub hub;
    private final Handler h = new Handler(Looper.getMainLooper());
    private int running = -1;
    private boolean principal;

    private Proximity(Context c, Hub hub) { app = c.getApplicationContext(); this.hub = hub; }

    /** Arranca o detiene lo que corresponda según el ajuste y el papel del teléfono. */
    static synchronized void update(Context c, Hub hub) {
        if (inst == null) inst = new Proximity(c, hub);
        inst.h.post(inst::apply);
    }

    private void apply() {
        int m = mode(app);
        boolean pr = hub.isPrincipal();
        if (m == running && pr == principal) return;
        stopAll();
        running = m;
        principal = pr;
        armed = false;
        state = 0;
        since = 0;
        if (m == MAG && pr) startMag();
        else if (m == BT) { if (pr) startScan(); else startAdvertise(); }
        if (m != OFF && pr) h.post(tick);
    }

    private void stopAll() {
        h.removeCallbacks(tick);
        SensorManager sm = (SensorManager) app.getSystemService(Context.SENSOR_SERVICE);
        if (sm != null) sm.unregisterListener(this);
        try { if (scanner != null && scanCb != null) scanner.stopScan(scanCb); } catch (Exception ignored) { }
        try { if (advertiser != null && advCb != null) advertiser.stopAdvertising(advCb); } catch (Exception ignored) { }
        scanner = null; scanCb = null; advertiser = null; advCb = null;
    }

    // ---------------- ¿juntos o separados? ----------------

    private boolean armed;         // se vieron juntos al menos una vez (si no, no se decide nada)
    private int state;             // 0 juntos, 1 separados
    private long since;            // desde cuándo la lectura dice lo contrario del estado

    /** null = no se sabe todavía. */
    private Boolean near() {
        long now = System.currentTimeMillis();
        if (running == MAG) {
            if (magAt == 0 || now - magAt > 3000) return null;
            float j = prefs(app).getFloat("sepMagJ", 0);
            if (j <= 0) return null;
            float thr = Math.max(70f, (j + 50f) / 2f);
            return state == 0 ? magAvg > thr - 8 : magAvg > thr + 8;   // un margen para que no titubee
        }
        if (running == BT) {
            if (seenAt == 0) return null;
            if (now - seenAt > 15000) return false;   // sin señal: muy lejos (o el extendido se apagó)
            return state == 0 ? rssi > -82 : rssi > -72;
        }
        return null;
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            Boolean n = near();
            long now = System.currentTimeMillis();
            if (n != null) {
                if (n) armed = true;
                boolean wantFar = !n;
                if (armed && wantFar != (state == 1)) {
                    if (since == 0) since = now;
                    else if (now - since >= (wantFar ? FAR_MS : NEAR_MS)) { since = 0; state = wantFar ? 1 : 0; if (wantFar) separated(); else together(); }
                } else since = 0;
            }
            h.postDelayed(this, 1000);
        }
    };

    private void separated() {
        if (PhoneMode.active(app)) return;
        prefs(app).edit().putBoolean("sepEntered", true).apply();
        if (ask(app)) { PhoneMode.offer(app, hub, "Los teléfonos se separaron"); return; }
        hub.startOwn(new Intent(app, MainActivity.class).putExtra("phoneNow", true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                () -> PhoneMode.active(app), 87, "Teléfonos separados", "Toca para pasar a teléfono normal");
    }

    private void together() {
        if (!PhoneMode.active(app) || !prefs(app).getBoolean("sepEntered", false)) return;
        prefs(app).edit().putBoolean("sepEntered", false).apply();
        if (ask(app)) { PhoneMode.extendedBack(app); return; }
        hub.startOwn(new Intent(app, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                () -> !PhoneMode.active(app), 82, "Teléfonos juntos", "Toca para volver a ChronOS");
    }

    // ---------------- imán ----------------

    private float magAvg;
    private long magAt;
    private Runnable calDone;
    private float calSum;
    private int calN;

    private void startMag() {
        SensorManager sm = (SensorManager) app.getSystemService(Context.SENSOR_SERVICE);
        Sensor s = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        if (s != null) sm.registerListener(this, s, SensorManager.SENSOR_DELAY_NORMAL);
    }

    @Override public void onSensorChanged(SensorEvent e) {
        float mt = (float) Math.sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]);
        magAvg = magAt == 0 ? mt : magAvg + (mt - magAvg) * 0.15f;
        magAt = System.currentTimeMillis();
        if (calDone != null) { calSum += mt; calN++; }
    }

    @Override public void onAccuracyChanged(Sensor s, int a) { }

    /** Calibrar (en el principal, con los dos teléfonos juntos y abiertos): mide 3 s y guarda el valor. */
    static void calibrate(Context c, Hub hub, java.util.function.Consumer<String> result) {
        if (inst == null) inst = new Proximity(c, hub);
        Proximity p = inst;
        p.h.post(() -> {
            SensorManager sm = (SensorManager) p.app.getSystemService(Context.SENSOR_SERVICE);
            Sensor s = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
            if (s == null) { result.accept("Este teléfono no tiene brújula: usa Bluetooth."); return; }
            p.calSum = 0; p.calN = 0;
            p.calDone = () -> { };
            sm.registerListener(p, s, SensorManager.SENSOR_DELAY_GAME);
            p.h.postDelayed(() -> {
                p.calDone = null;
                if (p.running != MAG) sm.unregisterListener(p);
                float j = p.calN == 0 ? 0 : p.calSum / p.calN;
                prefs(p.app).edit().putFloat("sepMagJ", j).apply();
                int v = Math.round(j);
                result.accept(j < 85 ? "Imán juntos: " + v + " µT. Es muy poco (la Tierra ya da 25 a 65): con esta carcasa conviene Bluetooth."
                        : "Listo: con los dos juntos se mide " + v + " µT. Separados se notará la diferencia.");
            }, 3000);
        });
    }

    // ---------------- Bluetooth (BLE) ----------------

    private BluetoothLeScanner scanner;
    private ScanCallback scanCb;
    private BluetoothLeAdvertiser advertiser;
    private AdvertiseCallback advCb;
    private volatile float rssi = -100;
    private volatile long seenAt;

    private void startScan() {
        try {
            BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
            if (ad == null || !ad.isEnabled()) { h.postDelayed(this::retry, 30000); return; }
            scanner = ad.getBluetoothLeScanner();
            if (scanner == null) return;
            scanCb = new ScanCallback() {
                @Override public void onScanResult(int type, ScanResult r) {
                    int v = r.getRssi();
                    rssi = seenAt == 0 || System.currentTimeMillis() - seenAt > 15000 ? v : rssi + (v - rssi) * 0.3f;
                    seenAt = System.currentTimeMillis();
                }
            };
            ScanFilter f = new ScanFilter.Builder().setServiceUuid(new ParcelUuid(SVC)).build();
            ScanSettings ss = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build();
            scanner.startScan(Collections.singletonList(f), ss, scanCb);
        } catch (SecurityException e) { scanner = null; scanCb = null; }
        catch (Exception e) { scanner = null; scanCb = null; }
    }

    private void startAdvertise() {
        try {
            BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
            if (ad == null || !ad.isEnabled()) { h.postDelayed(this::retry, 30000); return; }
            advertiser = ad.getBluetoothLeAdvertiser();
            if (advertiser == null) return;
            advCb = new AdvertiseCallback() { };
            AdvertiseSettings s = new AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM).setConnectable(false).build();
            AdvertiseData d = new AdvertiseData.Builder().addServiceUuid(new ParcelUuid(SVC)).build();
            advertiser.startAdvertising(s, d, advCb);
        } catch (SecurityException e) { advertiser = null; advCb = null; }
        catch (Exception e) { advertiser = null; advCb = null; }
    }

    /** Bluetooth estaba apagado: se vuelve a intentar más tarde. */
    private void retry() { if (running == BT) { running = -1; apply(); } }

    /** Para el menú: qué está pasando ahora. */
    static String status(Context c) {
        Proximity p = inst;
        if (p == null || p.running == OFF) return "";
        if (p.running == MAG) return p.principal ? (p.magAt == 0 ? "sin lectura" : "imán " + Math.round(p.magAvg) + " µT") : "lo mide el principal";
        if (!p.principal) return p.advertiser != null ? "emitiendo señal" : "sin Bluetooth o sin permiso";
        if (p.scanner == null) return "sin Bluetooth o sin permiso";
        return p.seenAt == 0 ? "buscando al extendido" : "señal " + Math.round(p.rssi) + " dBm";
    }
}
