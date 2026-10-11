package cl.mesadoble;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidDeviceAppSdpSettings;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Hace que este teléfono se presente por Bluetooth como teclado + mouse + teclas multimedia.
 * El otro teléfono lo ve como un teclado físico normal: no necesita ninguna app.
 */
@SuppressWarnings("MissingPermission")
public class Hid {

    public interface Listener {
        void onStatus(String text, boolean connected);
    }

    public static final int ID_KEYS = 1, ID_MOUSE = 2, ID_MEDIA = 3;

    // Modificadores (byte 0 del reporte de teclado)
    public static final int CTRL = 0x01, SHIFT = 0x02, ALT = 0x04, META = 0x08, ALTGR = 0x40;

    // Teclas multimedia (página "Consumer")
    public static final int MEDIA_PLAY = 0xCD, MEDIA_NEXT = 0xB5, MEDIA_PREV = 0xB6,
            VOL_UP = 0xE9, VOL_DOWN = 0xEA, MUTE = 0xE2, AC_HOME = 0x223, AC_BACK = 0x224;

    private static final byte[] DESCRIPTOR = bytes(
            // ---- Teclado (reporte 1): modificadores, reservado, 6 teclas; salida: luces
            0x05, 0x01, 0x09, 0x06, 0xA1, 0x01, 0x85, ID_KEYS,
            0x05, 0x07, 0x19, 0xE0, 0x29, 0xE7, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x08, 0x81, 0x02,
            0x95, 0x01, 0x75, 0x08, 0x81, 0x01,
            0x95, 0x05, 0x75, 0x01, 0x05, 0x08, 0x19, 0x01, 0x29, 0x05, 0x91, 0x02,
            0x95, 0x01, 0x75, 0x03, 0x91, 0x01,
            0x95, 0x06, 0x75, 0x08, 0x15, 0x00, 0x25, 0x73, 0x05, 0x07, 0x19, 0x00, 0x29, 0x73, 0x81, 0x00,
            0xC0,
            // ---- Mouse (reporte 2): 3 botones, dx, dy, rueda
            0x05, 0x01, 0x09, 0x02, 0xA1, 0x01, 0x85, ID_MOUSE, 0x09, 0x01, 0xA1, 0x00,
            0x05, 0x09, 0x19, 0x01, 0x29, 0x03, 0x15, 0x00, 0x25, 0x01, 0x95, 0x03, 0x75, 0x01, 0x81, 0x02,
            0x95, 0x01, 0x75, 0x05, 0x81, 0x01,
            0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x38, 0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x03, 0x81, 0x06,
            0xC0, 0xC0,
            // ---- Multimedia (reporte 3): un código de 16 bits
            0x05, 0x0C, 0x09, 0x01, 0xA1, 0x01, 0x85, ID_MEDIA,
            0x15, 0x00, 0x26, 0xFF, 0x03, 0x19, 0x00, 0x2A, 0xFF, 0x03, 0x75, 0x10, 0x95, 0x01, 0x81, 0x00,
            0xC0
    );

    private static byte[] bytes(int... v) {
        byte[] b = new byte[v.length];
        for (int i = 0; i < v.length; i++) b[i] = (byte) v[i];
        return b;
    }

    private final Context ctx;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Executor exec = Executors.newSingleThreadExecutor();
    private BluetoothAdapter adapter;
    private BluetoothHidDevice hid;
    private volatile BluetoothDevice host;
    private boolean registered;
    private String wanted;   // dirección del teléfono al que queremos conectarnos

    public Hid(Context ctx, Listener l) {
        this.ctx = ctx.getApplicationContext();
        this.listener = l;
    }

    public BluetoothAdapter adapter() { return adapter; }
    public boolean isConnected() { return host != null; }
    public String hostAddress() { BluetoothDevice h = host; return h != null ? h.getAddress() : null; }

    /** Devuelve false si este teléfono no tiene Bluetooth o no permite el modo teclado. */
    public boolean start(String wantedAddress) {
        wanted = wantedAddress;
        adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) { status("Este teléfono no tiene Bluetooth", false); return false; }
        if (!adapter.isEnabled()) { status("Activa el Bluetooth", false); return false; }
        if (hid != null) { if (registered && wanted != null) connect(wanted); return true; }
        status("Preparando teclado…", false);
        boolean ok = adapter.getProfileProxy(ctx, new BluetoothProfile.ServiceListener() {
            @Override public void onServiceConnected(int profile, BluetoothProfile proxy) {
                hid = (BluetoothHidDevice) proxy;
                register();
            }
            @Override public void onServiceDisconnected(int profile) {
                hid = null; registered = false; host = null;
                status("Bluetooth desconectado", false);
            }
        }, BluetoothProfile.HID_DEVICE);
        if (!ok) status("Este teléfono no permite funcionar como teclado Bluetooth", false);
        return ok;
    }

    private void register() {
        BluetoothHidDeviceAppSdpSettings sdp = new BluetoothHidDeviceAppSdpSettings(
                "Mesa Doble Teclado", "Teclado y touchpad", "Mesa Doble",
                BluetoothHidDevice.SUBCLASS1_COMBO, DESCRIPTOR);
        try {
            hid.registerApp(sdp, null, null, exec, new BluetoothHidDevice.Callback() {
                @Override public void onAppStatusChanged(BluetoothDevice plugged, boolean reg) {
                    registered = reg;
                    if (!reg) { host = null; status("El modo teclado se detuvo", false); return; }
                    if (plugged != null) { hid.connect(plugged); return; }
                    if (wanted != null) connect(wanted);
                    else status("Elige el teléfono al que vas a escribir", false);
                }
                @Override public void onConnectionStateChanged(BluetoothDevice d, int state) {
                    if (state == BluetoothProfile.STATE_CONNECTED) {
                        host = d;
                        wanted = d.getAddress();
                        status("Escribiendo en " + name(d), true);
                    } else if (state == BluetoothProfile.STATE_CONNECTING) {
                        status("Conectando con " + name(d) + "…", false);
                    } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                        if (host == null || host.equals(d)) {
                            host = null;
                            status("Sin conexión con " + name(d) + ". Toca \"Teléfono\" para reconectar", false);
                        }
                    }
                }
                @Override public void onGetReport(BluetoothDevice d, byte type, byte id, int size) {
                    int len = id == ID_KEYS ? 8 : id == ID_MOUSE ? 4 : 2;
                    hid.replyReport(d, type, id, new byte[len]);
                }
                @Override public void onSetReport(BluetoothDevice d, byte type, byte id, byte[] data) {
                    hid.reportError(d, BluetoothHidDevice.ERROR_RSP_SUCCESS);
                }
            });
        } catch (Exception e) {
            status("No pude activar el modo teclado", false);
        }
    }

    public void connect(String address) {
        wanted = address;
        if (hid == null || !registered || adapter == null) return;
        try {
            BluetoothDevice d = adapter.getRemoteDevice(address);
            if (host != null && !host.equals(d)) hid.disconnect(host);
            status("Conectando con " + name(d) + "…", false);
            if (!hid.connect(d)) status("No pude conectar. ¿Están vinculados por Bluetooth?", false);
        } catch (Exception e) {
            status("No pude conectar con ese teléfono", false);
        }
    }

    public void stop() {
        try {
            if (hid != null) {
                if (host != null) hid.disconnect(host);
                hid.unregisterApp();
                if (adapter != null) adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid);
            }
        } catch (Exception ignored) { }
        hid = null; host = null; registered = false;
    }

    // ---------------- envío de reportes ----------------

    public void keys(int mods, int[] codes) {
        byte[] r = new byte[8];
        r[0] = (byte) mods;
        for (int i = 0; i < 6 && i < codes.length; i++) r[2 + i] = (byte) codes[i];
        send(ID_KEYS, r);
    }

    public void mouse(int buttons, int dx, int dy, int wheel) {
        // los desplazamientos van en pasos de -127..127
        do {
            int sx = Math.max(-127, Math.min(127, dx));
            int sy = Math.max(-127, Math.min(127, dy));
            int sw = Math.max(-127, Math.min(127, wheel));
            send(ID_MOUSE, new byte[]{(byte) buttons, (byte) sx, (byte) sy, (byte) sw});
            dx -= sx; dy -= sy; wheel -= sw;
        } while (dx != 0 || dy != 0 || wheel != 0);
    }

    public void media(int usage) {
        send(ID_MEDIA, new byte[]{(byte) (usage & 0xFF), (byte) ((usage >> 8) & 0xFF)});
    }

    private void send(final int id, final byte[] data) {
        final BluetoothDevice h = host;
        final BluetoothHidDevice d = hid;
        if (h == null || d == null) return;
        exec.execute(() -> { try { d.sendReport(h, id, data); } catch (Exception ignored) { } });
    }

    private String name(BluetoothDevice d) {
        try { String n = d.getName(); return n != null ? n : d.getAddress(); }
        catch (Exception e) { return "el otro teléfono"; }
    }

    private void status(String t, boolean c) { main.post(() -> listener.onStatus(t, c)); }
}
