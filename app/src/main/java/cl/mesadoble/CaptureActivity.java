package cl.mesadoble;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.widget.Toast;

/** Pide los permisos para Boost en apps: micrófono (Android lo exige para capturar) y "capturar lo que se reproduce". */
public class CaptureActivity extends Activity {

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 71);
        else ask();
    }

    private void ask() {
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        try { startActivityForResult(mpm.createScreenCaptureIntent(), 72); }
        catch (Exception e) { Toast.makeText(this, "Este teléfono no permite capturar el sonido", Toast.LENGTH_LONG).show(); finish(); }
    }

    @Override public void onRequestPermissionsResult(int req, String[] p, int[] r) {
        if (r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED) ask();
        else { Toast.makeText(this, "Boost para apps necesita el permiso de micrófono", Toast.LENGTH_LONG).show(); finish(); }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        if (req == 72 && res == RESULT_OK && data != null) {
            Intent i = new Intent(this, CaptureService.class).putExtra("code", res).putExtra("data", data);
            try { startForegroundService(i); } catch (Exception ignored) { }
        }
        finish();
    }
}
