package cl.mesadoble;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.input.InputManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.InputDevice;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Baterías de los accesorios conectados (audífonos, gamepad, teclado, mouse, reloj…) en cualquiera de los dos
 * teléfonos: salen junto a las de P y E, bajo el reloj, con un iconito del tipo de accesorio.
 * Cada teléfono mira los suyos (Bluetooth: getBatteryLevel; gamepads y teclados: InputDevice.getBatteryState en
 * Android 12+) y se los pasa al otro ("peri" {l:[{n, k, p}]}). Tipos k: h audífonos, g gamepad, k teclado,
 * m mouse, w reloj, o otro. Solo se muestran los que informan su carga.
 */
final class Peri {

    private Peri() { }

    static final class Item {
        final String name, kind; final int pct;
        Item(String n, String k, int p) { name = n; kind = k; pct = p; }
    }

    static volatile List<Item> mine = new ArrayList<>(), other = new ArrayList<>();
    private static final Handler h = new Handler(Looper.getMainLooper());
    private static Context app;
    private static Hub hub;
    private static String lastSig = "";

    static void start(Context c, Hub hb) {
        if (app != null) return;
        app = c.getApplicationContext();
        hub = hb;
        IntentFilter f = new IntentFilter();
        f.addAction("android.bluetooth.device.action.BATTERY_LEVEL_CHANGED");
        f.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        f.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        BroadcastReceiver r = new BroadcastReceiver() {
            @Override public void onReceive(Context cx, Intent i) { h.removeCallbacks(scan); h.postDelayed(scan, 1500); }
        };
        try {
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(r, f, 2 /* RECEIVER_EXPORTED: avisos del sistema */);
            else app.registerReceiver(r, f);
        } catch (Exception ignored) { }
        InputManager im = (InputManager) app.getSystemService(Context.INPUT_SERVICE);
        if (im != null) im.registerInputDeviceListener(new InputManager.InputDeviceListener() {
            @Override public void onInputDeviceAdded(int id) { h.postDelayed(scan, 1500); }
            @Override public void onInputDeviceRemoved(int id) { h.postDelayed(scan, 500); }
            @Override public void onInputDeviceChanged(int id) { h.removeCallbacks(scan); h.postDelayed(scan, 1500); }
        }, h);
        h.postDelayed(scan, 3000);
    }

    /** Se revisa cada minuto (los audífonos avisan solos; los gamepads no siempre). */
    private static final Runnable scan = new Runnable() {
        @Override public void run() {
            h.removeCallbacks(this);
            List<Item> l = collect();
            mine = l;
            String sig = sig(l);
            if (!sig.equals(lastSig)) { lastSig = sig; share(); }
            h.postDelayed(this, 60000);
        }
    };

    /** Al conectarse los teléfonos: se manda la lista aunque no haya cambiado. */
    static void share() {
        if (hub == null) return;
        try {
            JSONArray a = new JSONArray();
            for (Item i : mine) a.put(new JSONObject().put("n", i.name).put("k", i.kind).put("p", i.pct));
            if (hub.connected()) hub.send("peri", "l", a);
            hub.local(new JSONObject().put("t", "periNow"));
        } catch (Exception ignored) { }
    }

    static void received(JSONObject m) {
        List<Item> l = new ArrayList<>();
        JSONArray a = m.optJSONArray("l");
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null) l.add(new Item(o.optString("n"), o.optString("k", "o"), o.optInt("p", -1)));
        }
        other = l;
    }

    /** Lo que se dibuja: los de los dos teléfonos (sin repetir), con carga conocida. */
    static List<Item> all() {
        Map<String, Item> m = new LinkedHashMap<>();
        for (Item i : mine) if (i.pct >= 0) m.put(i.name, i);
        for (Item i : other) if (i.pct >= 0 && !m.containsKey(i.name)) m.put(i.name, i);
        return new ArrayList<>(m.values());
    }

    private static String sig(List<Item> l) {
        StringBuilder b = new StringBuilder();
        for (Item i : l) b.append(i.name).append(i.kind).append(i.pct / 5).append(';');   // cambios de 5 en 5 %
        return b.toString();
    }

    private static List<Item> collect() {
        Map<String, Item> out = new LinkedHashMap<>();
        // gamepads, teclados y mouse (Android 12+ informa su batería)
        if (Build.VERSION.SDK_INT >= 31) {
            for (int id : InputDevice.getDeviceIds()) {
                InputDevice d = InputDevice.getDevice(id);
                if (d == null || d.isVirtual()) continue;
                try {
                    Object bs = InputDevice.class.getMethod("getBatteryState").invoke(d);
                    boolean present = (boolean) bs.getClass().getMethod("isPresent").invoke(bs);
                    if (!present) continue;
                    float cap = (float) bs.getClass().getMethod("getCapacity").invoke(bs);
                    if (Float.isNaN(cap) || cap < 0) continue;
                    out.put(d.getName(), new Item(d.getName(), inputKind(d), Math.round(cap * 100)));
                } catch (Exception ignored) { }
            }
        }
        // Bluetooth: audífonos, relojes y lo demás conectado
        try {
            BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
            if (ad != null && ad.isEnabled()) {
                for (BluetoothDevice d : ad.getBondedDevices()) {
                    boolean conn;
                    try { conn = (boolean) BluetoothDevice.class.getMethod("isConnected").invoke(d); } catch (Exception e) { conn = false; }
                    if (!conn) continue;
                    int lvl;
                    try { lvl = (int) BluetoothDevice.class.getMethod("getBatteryLevel").invoke(d); } catch (Exception e) { lvl = -1; }
                    String n = d.getName() == null ? d.getAddress() : d.getName();
                    Item prev = out.get(n);
                    if (prev != null && prev.pct >= 0) continue;
                    out.put(n, new Item(n, btKind(d.getBluetoothClass()), lvl));
                }
            }
        } catch (SecurityException ignored) {   // sin permiso de Bluetooth: solo lo de arriba
        } catch (Exception ignored) { }
        return new ArrayList<>(out.values());
    }

    private static String inputKind(InputDevice d) {
        int s = d.getSources();
        if ((s & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD || (s & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) return "g";
        if ((s & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE) return "m";
        if (d.getKeyboardType() == InputDevice.KEYBOARD_TYPE_ALPHABETIC) return "k";
        return "o";
    }

    private static String btKind(BluetoothClass bc) {
        if (bc == null) return "o";
        int dev = bc.getDeviceClass(), maj = bc.getMajorDeviceClass();
        if (maj == BluetoothClass.Device.Major.AUDIO_VIDEO) return "h";
        if (maj == BluetoothClass.Device.Major.WEARABLE) return "w";
        if (maj == BluetoothClass.Device.Major.PERIPHERAL) {
            int sub = dev & 0x3C, kp = dev & 0xC0;
            if (sub == 0x04 || sub == 0x08) return "g";   // joystick / gamepad
            if ((kp & 0x40) != 0) return "k";              // teclado (con o sin mouse)
            if (kp == 0x80) return "m";                    // mouse
            return "g";
        }
        return "o";
    }
}
