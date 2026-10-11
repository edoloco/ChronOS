package cl.mesadoble;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidDeviceAppSdpSettings;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Este teléfono se presenta por Bluetooth como CONTROL DE JUEGO + MOUSE ante el principal.
 * Los emuladores (Nostalgia.NES, RetroArch, etc.) solo reconocen controles reales, y Android solo
 * permite crearlos por Bluetooth. Solo se enciende en los modos gamepad o touchpad Bluetooth;
 * el teclado y la mesa siguen por WiFi.
 */
@SuppressWarnings("MissingPermission")
public class BtPad {

    public interface Listener { void onStatus(String text, boolean connected); }

    static final int ID_PAD = 1, ID_MOUSE = 2;

    // Botones (bit = número de botón - 1), en el orden que Android traduce para mandos:
    // 1 A, 2 B, 3 C, 4 X, 5 Y, 6 Z, 7 L1, 8 R1, 9 L2, 10 R2, 11 Select, 12 Start, 13 Modo, 14 L3, 15 R3
    static int bit(String id) {
        switch (id) {
            case "a": return 0; case "b": return 1; case "x": return 3; case "y": return 4;
            case "l": return 6; case "r": return 7; case "l2": return 8; case "r2": return 9;
            case "select": return 10; case "start": return 11; case "l3": return 13; case "r3": return 14;
        }
        return -1;
    }

    private static final byte[] DESCRIPTOR = bytes(
            // ---- Control de juego (reporte 1): 16 botones, cruceta (hat), 2 joysticks
            0x05, 0x01, 0x09, 0x05, 0xA1, 0x01, 0x85, ID_PAD,
            0x05, 0x09, 0x19, 0x01, 0x29, 0x10, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x10, 0x81, 0x02,
            0x05, 0x01, 0x09, 0x39, 0x15, 0x00, 0x25, 0x07, 0x35, 0x00, 0x46, 0x3B, 0x01, 0x65, 0x14,
            0x75, 0x04, 0x95, 0x01, 0x81, 0x42,
            0x65, 0x00, 0x75, 0x04, 0x95, 0x01, 0x81, 0x01,
            0x09, 0x30, 0x09, 0x31, 0x09, 0x32, 0x09, 0x35, 0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x04, 0x81, 0x02,
            0xC0,
            // ---- Mouse (reporte 2): 3 botones, dx, dy, rueda
            0x05, 0x01, 0x09, 0x02, 0xA1, 0x01, 0x85, ID_MOUSE, 0x09, 0x01, 0xA1, 0x00,
            0x05, 0x09, 0x19, 0x01, 0x29, 0x03, 0x15, 0x00, 0x25, 0x01, 0x95, 0x03, 0x75, 0x01, 0x81, 0x02,
            0x95, 0x01, 0x75, 0x05, 0x81, 0x01,
            0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x38, 0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x03, 0x81, 0x06,
            0xC0, 0xC0
    );

    private static byte[] bytes(int... v) {
        byte[] b = new byte[v.length];
        for (int i = 0; i < v.length; i++) b[i] = (byte) v[i];
        return b;
    }

    private static BtPad inst;
    public static synchronized BtPad get(Context c) { if (inst == null) inst = new BtPad(c.getApplicationContext()); return inst; }

    private final Context ctx;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Executor exec = Executors.newSingleThreadExecutor();
    private Listener listener;
    private BluetoothAdapter adapter;
    private BluetoothHidDevice hid;
    private volatile BluetoothDevice host;
    private boolean registered, starting;
    private String lastStatus = "Bluetooth apagado";
    private boolean lastConnected;

    // estado del control
    private int buttons = 0, hat = 8;   // 8 = cruceta suelta
    private int lx, ly, rx, ry;
    private boolean up, down, left, right;

    private BtPad(Context c) {
        ctx = c;
        prefs = c.getSharedPreferences("mesa", Context.MODE_PRIVATE);
    }

    public void setListener(Listener l) { listener = l; if (l != null) l.onStatus(lastStatus, lastConnected); }
    public boolean connected() { return host != null; }

    /** Enciende el control Bluetooth. Devuelve false si el teléfono no lo permite. */
    public boolean start() {
        adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) { status("Este teléfono no tiene Bluetooth", false); return false; }
        if (!adapter.isEnabled()) { status("Activa el Bluetooth", false); return false; }
        if (hid != null || starting) { reconnect(); return true; }
        starting = true;
        status("Preparando control Bluetooth…", false);
        boolean ok = adapter.getProfileProxy(ctx, new BluetoothProfile.ServiceListener() {
            @Override public void onServiceConnected(int profile, BluetoothProfile proxy) { hid = (BluetoothHidDevice) proxy; starting = false; register(); }
            @Override public void onServiceDisconnected(int profile) { hid = null; registered = false; host = null; starting = false; status("Bluetooth desconectado", false); }
        }, BluetoothProfile.HID_DEVICE);
        if (!ok) { starting = false; status("Este teléfono no permite funcionar como control Bluetooth", false); }
        return ok;
    }

    private void register() {
        BluetoothHidDeviceAppSdpSettings sdp = new BluetoothHidDeviceAppSdpSettings(
                "Mesa Doble Control", "Gamepad y mouse", "Mesa Doble", BluetoothHidDevice.SUBCLASS2_GAMEPAD, DESCRIPTOR);
        try {
            hid.registerApp(sdp, null, null, exec, new BluetoothHidDevice.Callback() {
                @Override public void onAppStatusChanged(BluetoothDevice plugged, boolean reg) {
                    registered = reg;
                    if (!reg) { host = null; status("El control Bluetooth se detuvo", false); return; }
                    if (plugged != null) hid.connect(plugged); else reconnect();
                }
                @Override public void onConnectionStateChanged(BluetoothDevice d, int state) {
                    if (state == BluetoothProfile.STATE_CONNECTED) {
                        host = d;
                        prefs.edit().putString("btHost", d.getAddress()).apply();
                        status("● Control conectado a " + name(d), true);
                    } else if (state == BluetoothProfile.STATE_CONNECTING) {
                        status("Conectando con " + name(d) + "…", false);
                    } else if (state == BluetoothProfile.STATE_DISCONNECTED && (host == null || host.equals(d))) {
                        host = null;
                        status("Control sin conexión. Toca \"Vincular\" si es la primera vez", false);
                    }
                }
                @Override public void onGetReport(BluetoothDevice d, byte type, byte id, int size) {
                    hid.replyReport(d, type, id, id == ID_PAD ? padReport() : new byte[4]);
                }
                @Override public void onSetReport(BluetoothDevice d, byte type, byte id, byte[] data) {
                    hid.reportError(d, BluetoothHidDevice.ERROR_RSP_SUCCESS);
                }
            });
        } catch (Exception e) { status("No pude activar el control Bluetooth", false); }
    }

    /** Se conecta al principal recordado (el que se vinculó la última vez). */
    private void reconnect() {
        String addr = prefs.getString("btHost", null);
        if (hid == null || !registered) return;
        if (addr == null) { status("Toca \"Vincular\" para emparejar con el principal", false); return; }
        try {
            BluetoothDevice d = adapter.getRemoteDevice(addr);
            status("Conectando con " + name(d) + "…", false);
            hid.connect(d);
        } catch (Exception e) { status("No pude conectar. Toca \"Vincular\"", false); }
    }

    public void stop() {
        try {
            if (hid != null) {
                if (host != null) hid.disconnect(host);
                hid.unregisterApp();
                if (adapter != null) adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid);
            }
        } catch (Exception ignored) { }
        hid = null; host = null; registered = false; starting = false;
        status("Bluetooth apagado", false);
    }

    // ---------------- control de juego ----------------

    public void button(String id, boolean pressed) {
        if (id.startsWith("dpad_")) {
            String d = id.substring(5);
            if ("up".equals(d)) up = pressed; else if ("down".equals(d)) down = pressed;
            else if ("left".equals(d)) left = pressed; else if ("right".equals(d)) right = pressed;
            hat = up ? (right ? 1 : left ? 7 : 0) : down ? (right ? 3 : left ? 5 : 4) : right ? 2 : left ? 6 : 8;
        } else {
            int b = bit(id);
            if (b < 0) return;
            buttons = pressed ? buttons | (1 << b) : buttons & ~(1 << b);
        }
        sendPad();
    }

    private long lastJoy;
    public void joy(String id, float nx, float ny, boolean end) {
        int vx = Math.round(nx * 127), vy = Math.round(ny * 127);
        if ("joyR".equals(id)) { rx = vx; ry = vy; } else { lx = vx; ly = vy; }
        long now = System.currentTimeMillis();
        if (!end && now - lastJoy < 12) return;   // ~80 reportes por segundo como máximo
        lastJoy = now;
        sendPad();
    }

    private byte[] padReport() {
        return new byte[]{(byte) (buttons & 0xFF), (byte) ((buttons >> 8) & 0xFF), (byte) (hat & 0x0F),
                (byte) lx, (byte) ly, (byte) rx, (byte) ry};
    }

    private void sendPad() { send(ID_PAD, padReport()); }

    // ---------------- mouse ----------------

    private int mouseButtons = 0;
    public void mouse(int dx, int dy, int wheel) {
        do {
            int sx = Math.max(-127, Math.min(127, dx)), sy = Math.max(-127, Math.min(127, dy)), sw = Math.max(-127, Math.min(127, wheel));
            send(ID_MOUSE, new byte[]{(byte) mouseButtons, (byte) sx, (byte) sy, (byte) sw});
            dx -= sx; dy -= sy; wheel -= sw;
        } while (dx != 0 || dy != 0 || wheel != 0);
    }
    public void click(int button) {   // 1 izquierdo, 2 derecho (en Android: atrás)
        send(ID_MOUSE, new byte[]{(byte) (mouseButtons | button), 0, 0, 0});
        send(ID_MOUSE, new byte[]{(byte) mouseButtons, 0, 0, 0});
    }

    private void send(final int id, final byte[] data) {
        final BluetoothDevice h = host;
        final BluetoothHidDevice d = hid;
        if (h == null || d == null) return;
        exec.execute(() -> { try { d.sendReport(h, id, data); } catch (Exception ignored) { } });
    }

    private String name(BluetoothDevice d) {
        try { String n = d.getName(); return n != null ? n : d.getAddress(); } catch (Exception e) { return "el principal"; }
    }

    private void status(String t, boolean c) {
        lastStatus = t; lastConnected = c;
        main.post(() -> { if (listener != null) listener.onStatus(t, c); });
    }
}
