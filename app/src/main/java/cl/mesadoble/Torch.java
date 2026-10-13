package cl.mesadoble;

import android.content.Context;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Handler;
import android.os.Looper;

/**
 * Linternas: un solo interruptor enciende o apaga la linterna de los dos teléfonos.
 *   torch      {on}  pide al otro teléfono encender/apagar la suya
 *   torchState {on}  cada teléfono avisa cómo quedó la suya (también si se cambió desde Android)
 */
final class Torch {

    static volatile boolean mine, other;
    private static String camId;
    private static boolean listening;

    private Torch() { }

    /** ¿Está prendida alguna? (así se ve el interruptor) */
    static boolean anyOn() { return mine || other; }

    static void listen(Context c, Hub hub) {
        if (listening) return;
        CameraManager cm = (CameraManager) c.getSystemService(Context.CAMERA_SERVICE);
        if (cm == null || id(c) == null) return;
        listening = true;
        cm.registerTorchCallback(new CameraManager.TorchCallback() {
            @Override public void onTorchModeChanged(String cameraId, boolean enabled) {
                if (!cameraId.equals(camId)) return;
                mine = enabled;
                if (hub.connected()) hub.send("torchState", "on", enabled);
                hub.local(state());
            }
            @Override public void onTorchModeUnavailable(String cameraId) {
                if (!cameraId.equals(camId)) return;
                mine = false;
                hub.local(state());
            }
        }, new Handler(Looper.getMainLooper()));
    }

    private static org.json.JSONObject state() {
        org.json.JSONObject m = new org.json.JSONObject();
        try { m.put("t", "torchUi"); } catch (Exception ignored) { }
        return m;
    }

    /** Cámara trasera con flash. */
    private static String id(Context c) {
        if (camId != null) return camId;
        CameraManager cm = (CameraManager) c.getSystemService(Context.CAMERA_SERVICE);
        if (cm == null) return null;
        try {
            String any = null;
            for (String i : cm.getCameraIdList()) {
                CameraCharacteristics ch = cm.getCameraCharacteristics(i);
                Boolean f = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (f == null || !f) continue;
                Integer face = ch.get(CameraCharacteristics.LENS_FACING);
                if (face != null && face == CameraCharacteristics.LENS_FACING_BACK) { camId = i; return i; }
                if (any == null) any = i;
            }
            camId = any;
        } catch (Exception ignored) { }
        return camId;
    }

    /** Enciende o apaga la de ESTE teléfono. false si no se pudo (sin flash o la cámara está en uso). */
    static boolean set(Context c, boolean on) {
        CameraManager cm = (CameraManager) c.getSystemService(Context.CAMERA_SERVICE);
        String i = id(c);
        if (cm == null || i == null) return false;
        try { cm.setTorchMode(i, on); mine = on; return true; } catch (Exception e) { return false; }
    }

    /** El interruptor: si alguna está prendida, apaga las dos; si no, prende las dos. */
    static void toggle(Context c, Hub hub) {
        listen(c, hub);
        boolean on = !anyOn();
        boolean ok = set(c, on);
        if (hub.connected()) { hub.send("torch", "on", on); other = on; }
        else if (on) android.widget.Toast.makeText(c, "El otro teléfono no está conectado: solo se prende esta linterna", android.widget.Toast.LENGTH_SHORT).show();
        if (!ok && on) android.widget.Toast.makeText(c, "No se pudo prender la linterna de este teléfono (¿la cámara está en uso?)", android.widget.Toast.LENGTH_SHORT).show();
        hub.local(state());
    }

    /** Mensajes del otro teléfono. */
    static void onMessage(Context c, Hub hub, org.json.JSONObject m) {
        listen(c, hub);
        String t = m.optString("t");
        if ("torch".equals(t)) {
            boolean on = m.optBoolean("on");
            if (!set(c, on) && on) hub.send("boostMsg", "msg", "La linterna del otro teléfono no se pudo prender (¿la cámara está en uso?)");
            hub.send("torchState", "on", mine);
        } else if ("torchState".equals(t)) other = m.optBoolean("on");
        hub.local(state());
    }
}
