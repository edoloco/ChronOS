package cl.mesadoble;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.widget.Toast;

/** Pide los permisos para Boost en apps: micrófono (Android lo exige para capturar) y "capturar lo que se reproduce". */
public class CaptureActivity extends Activity {

    /** Avisa en los dos teléfonos qué pasó (el que toca BOOST suele estar mirando el extendido). */
    private void tell(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        Hub hub = Hub.get(this);
        if (hub.connected()) hub.send("boostMsg", "msg", msg);
    }

    /** Modo "sonido arriba": el extendido captura el sonido de la app lanzada allá y lo toca el principal. */
    private boolean up;
    private String then;

    /** Después del permiso (aceptado o no) se abre la app lanzada desde el principal. */
    private void next() {
        if (then == null) return;
        try { Handoff.open(this, Hub.get(this), new org.json.JSONObject(then)); } catch (Exception ignored) { }
        then = null;
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        up = getIntent().getBooleanExtra("up", false);
        then = getIntent().getStringExtra("then");
        if (up) Toast.makeText(this, "Para que el sonido salga por el principal, acepta la captura (elige \"Pantalla completa\" si pregunta)", Toast.LENGTH_LONG).show();
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 71);
        else ask();
    }

    private void ask() {
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        try { startActivityForResult(mpm.createScreenCaptureIntent(), 72); }
        catch (Exception e) { tell(up ? "El extendido no permite capturar el sonido de las apps" : "El principal no permite capturar el sonido de las apps"); next(); finish(); }
    }

    @Override public void onRequestPermissionsResult(int req, String[] p, int[] r) {
        if (r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED) ask();
        else { tell(up ? "Para pasar el sonido al principal hace falta el permiso de micrófono en el extendido" : "Boost necesita el permiso de micrófono en el principal"); next(); finish(); }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        if (req == 72 && res == RESULT_OK && data != null) {
            Intent i = new Intent(this, CaptureService.class).putExtra("code", res).putExtra("data", data).putExtra("up", up);
            try { startForegroundService(i); } catch (Exception e) { tell(up ? "No pude pasar el sonido al principal" : "No pude iniciar Boost en el principal"); }
        } else if (req == 72) tell(up ? "El sonido seguirá saliendo por el extendido" : "Boost: no se aceptó capturar el sonido en el principal");
        next();
        finish();
    }
}
