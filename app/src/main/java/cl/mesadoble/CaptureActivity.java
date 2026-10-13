package cl.mesadoble;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.widget.Toast;

/** Pide los permisos de captura: Boost en apps (micrófono + "capturar lo que se reproduce") o Modo APP (pantalla). */
public class CaptureActivity extends Activity {

    /** Avisa en los dos teléfonos qué pasó (el que toca BOOST suele estar mirando el extendido). */
    private void tell(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        Hub hub = Hub.get(this);
        if (hub.connected()) hub.send("boostMsg", "msg", msg);
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        want = getIntent().getStringExtra("want");
        if (want == null) want = "audio";
        if (!"audio".equals(want)) { ask(); return; }   // la pantalla no necesita micrófono
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 71);
        else ask();
    }

    private String want;

    private void ask() {
        if (CaptureService.running) { CaptureService.want(this, want, true); finish(); return; }   // ya hay captura: se suma
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        try { startActivityForResult(mpm.createScreenCaptureIntent(), 72); }
        catch (Exception e) { tell("El principal no permite capturar la pantalla ni el sonido"); finish(); }
    }

    @Override public void onRequestPermissionsResult(int req, String[] p, int[] r) {
        if (r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED) ask();
        else { tell("Boost necesita el permiso de micrófono en el principal"); finish(); }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        if (req == 72 && res == RESULT_OK && data != null) {
            Intent i = new Intent(this, CaptureService.class).putExtra("code", res).putExtra("data", data).putExtra("want", want);
            try { startForegroundService(i); } catch (Exception e) { tell("No pude iniciar la captura en el principal"); }
        } else if (req == 72) tell("audio".equals(want) ? "Boost: no se aceptó capturar el sonido en el principal" : "Modo APP: no se aceptó compartir la pantalla en el principal");
        finish();
    }
}
