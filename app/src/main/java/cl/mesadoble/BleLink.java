package cl.mesadoble;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;

import java.util.Collections;

/**
 * Método de unión "Bluetooth (ahorro)": los dos teléfonos se hablan por Bluetooth de bajo consumo (canal L2CAP de
 * BLE, Android 10+), sin zona Wi-Fi. Alcanza para lo liviano (teclado, controles, avisos, menús); para lo pesado se
 * ofrece la red temporal del principal (TempNet).
 *  - PRINCIPAL: abre el canal y lo anuncia (el anuncio lleva el número del canal, "PSM"). Anunciar no pide Ubicación.
 *  - EXTENDIDO: busca el anuncio y se conecta (Android 12+: no necesita la Ubicación encendida).
 * Sin emparejar (canal "inseguro"): lo que viaja ya va dentro de ChronOS.
 */
final class BleLink {

    /** UUID corto (16 bits) para que el anuncio quepa: datos = 'C', PSM alto, PSM bajo. */
    static final ParcelUuid SVC = ParcelUuid.fromString("0000c4a0-0000-1000-8000-00805f9b34fb");
    private static final byte MAGIC = 0x43;

    interface Sink { void attachBt(BluetoothSocket s); }

    private final Sink sink;
    private final Handler h = new Handler(Looper.getMainLooper());
    private volatile boolean on, server;
    private volatile BluetoothServerSocket ss;
    private volatile int psm = -1;
    private BluetoothLeAdvertiser adv;
    private AdvertiseCallback advCb;
    private BluetoothLeScanner sc;
    private ScanCallback scCb;
    private volatile boolean connecting, linked;
    volatile String status = "";

    BleLink(Sink s) { sink = s; }

    static boolean supported() { return Build.VERSION.SDK_INT >= 29; }

    /** principal = abre el canal y anuncia; si no, busca y se conecta. */
    synchronized void start(boolean principal) {
        stop();
        on = true;
        server = principal;
        linked = false;
        if (!supported()) { status = "este Android no lo permite (hace falta Android 10)"; return; }
        if (server) new Thread(this::serve, "ble-server").start();
        else h.post(this::scan);
    }

    synchronized void stop() {
        on = false;
        h.removeCallbacksAndMessages(null);
        BluetoothServerSocket s = ss; ss = null; psm = -1;
        try { if (s != null) s.close(); } catch (Exception ignored) { }
        stopAdv();
        stopScan();
        status = "";
    }

    boolean running() { return on; }

    /** El canal quedó unido: se deja de anunciar / buscar (ahorra). */
    void onUp() { linked = true; h.post(() -> { stopAdv(); stopScan(); }); status = "unidos"; }

    /** Se cortó: a anunciar / buscar de nuevo. */
    void onDown() {
        linked = false;
        if (!on) return;
        status = server ? "esperando al extendido" : "buscando al principal";
        h.postDelayed(() -> { if (!on || linked) return; if (server) { if (psm > 0) advertise(psm); } else scan(); }, 800);
    }

    private BluetoothAdapter adapter() {
        BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
        if (ad == null) { status = "este teléfono no tiene Bluetooth"; return null; }
        if (!ad.isEnabled()) { status = "Bluetooth apagado"; retry(); return null; }
        return ad;
    }

    private void retry() { h.postDelayed(() -> { if (on && !linked) start(server); }, 15000); }

    // ---------------- principal ----------------

    private void serve() {
        BluetoothAdapter ad = adapter();
        if (ad == null) return;
        try {
            BluetoothServerSocket s = (BluetoothServerSocket) BluetoothAdapter.class.getMethod("listenUsingInsecureL2capChannel").invoke(ad);
            ss = s;
            psm = (int) BluetoothServerSocket.class.getMethod("getPsm").invoke(s);
            h.post(() -> advertise(psm));
            status = "esperando al extendido";
            while (on && ss == s) {
                BluetoothSocket c = s.accept();
                if (c != null) sink.attachBt(c);
            }
        } catch (SecurityException e) {
            status = "falta el permiso de Bluetooth";
        } catch (Exception e) {
            if (on) { status = "se reinicia el canal"; h.postDelayed(() -> { if (on) start(server); }, 3000); }
        }
    }

    private void advertise(int p) {
        if (!on || linked || p <= 0) return;
        stopAdv();
        try {
            BluetoothAdapter ad = adapter();
            if (ad == null) return;
            adv = ad.getBluetoothLeAdvertiser();
            if (adv == null) { status = "el Bluetooth no puede anunciar"; return; }
            advCb = new AdvertiseCallback() {
                @Override public void onStartFailure(int code) { status = "no pudo anunciarse (" + code + ")"; }
                @Override public void onStartSuccess(AdvertiseSettings x) { status = "anunciándose, esperando al extendido"; }
            };
            // conectable: si no, el extendido ve el anuncio pero no puede abrir el canal (era el error de la 1ª versión)
            AdvertiseSettings st = new AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM).setConnectable(true).build();
            AdvertiseData d = new AdvertiseData.Builder().addServiceData(SVC, new byte[]{MAGIC, (byte) (p >> 8), (byte) p}).build();
            adv.startAdvertising(st, d, advCb);
        } catch (SecurityException e) { status = "falta el permiso de Bluetooth"; adv = null; advCb = null; }
        catch (Exception e) { adv = null; advCb = null; }
    }

    private void stopAdv() {
        try { if (adv != null && advCb != null) adv.stopAdvertising(advCb); } catch (Exception ignored) { }
        adv = null; advCb = null;
    }

    // ---------------- extendido ----------------

    private boolean seen, plainScan;
    private int tries;

    private void scan() {
        if (!on || linked || scCb != null) return;
        seen = false;
        try {
            BluetoothAdapter ad = adapter();
            if (ad == null) return;
            sc = ad.getBluetoothLeScanner();
            if (sc == null) { status = "el Bluetooth no puede buscar"; return; }
            status = "buscando al principal";
            scCb = new ScanCallback() {
                @Override public void onScanResult(int type, ScanResult r) {
                    if (connecting || linked || r.getScanRecord() == null) return;
                    byte[] d = r.getScanRecord().getServiceData(SVC);
                    if (d == null || d.length < 3 || d[0] != MAGIC) return;
                    seen = true;
                    int p = ((d[1] & 0xff) << 8) | (d[2] & 0xff);
                    tries++;
                    status = "principal visto, uniendo (intento " + tries + ")";
                    connect(r.getDevice(), p);
                }
                @Override public void onScanFailed(int code) { scCb = null; status = "no pudo buscar (" + code + ")"; retry(); }
            };
            ScanSettings st = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();   // solo mientras busca
            if (plainScan) sc.startScan(null, st, scCb);   // algunos Bluetooth no filtran bien: se revisa cada anuncio aquí
            else sc.startScan(Collections.singletonList(new ScanFilter.Builder().setServiceData(SVC, new byte[]{MAGIC, 0, 0}, new byte[]{(byte) 0xff, 0, 0}).build()), st, scCb);
            h.postDelayed(() -> {   // 12 s sin ver al principal: se alterna entre buscar con filtro y sin filtro
                if (!on || linked || seen) return;
                stopScan();
                plainScan = !plainScan;
                status = "no veo al principal (¿Bluetooth encendido y ChronOS abierto allá?)";
                scan();
            }, 12000);
        } catch (SecurityException e) { status = "falta el permiso de Bluetooth"; sc = null; scCb = null; }
        catch (Exception e) { sc = null; scCb = null; }
    }

    private void stopScan() {
        try { if (sc != null && scCb != null) sc.stopScan(scCb); } catch (Exception ignored) { }
        sc = null; scCb = null;
    }

    private void connect(BluetoothDevice dev, int p) {
        connecting = true;
        new Thread(() -> {
            try {
                BluetoothSocket s = (BluetoothSocket) BluetoothDevice.class.getMethod("createInsecureL2capChannel", int.class).invoke(dev, p);
                s.connect();
                sink.attachBt(s);
            } catch (Exception e) {
                status = "lo vi pero no se pudo unir (intento " + tries + "): " + e.getClass().getSimpleName();
            } finally { connecting = false; }
        }, "ble-connect").start();
    }
}
