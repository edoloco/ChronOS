package cl.mesadoble;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;

/**
 * Pantalla invisible que abre la lista de teclados del sistema.
 * Android solo muestra esa lista cuando una ventana de la app tiene el foco,
 * por eso se espera a tenerlo antes de pedirla (antes se pedía desde un diálogo
 * que se estaba cerrando y a veces Android la ignoraba).
 */
public class ImePickerActivity extends Activity {

    private final Handler h = new Handler(Looper.getMainLooper());
    private int focusCount = 0;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (!Hub.imeEnabled(this)) {
            Toast.makeText(this, "Activa \"ChronOS\" en la lista de teclados y vuelve", Toast.LENGTH_LONG).show();
            try { startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)); } catch (Exception ignored) { }
            finish();
        }
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus) return;
        focusCount++;
        if (focusCount == 1) {
            h.postDelayed(() -> {
                InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) imm.showInputMethodPicker();
                else finish();
            }, 150);
        } else {
            // la lista se cerró: avisar el resultado y salir
            h.postDelayed(() -> {
                if (Hub.imeSelected(this)) Toast.makeText(this, "Listo: ahora escribes desde el otro teléfono", Toast.LENGTH_SHORT).show();
                Hub.get(this).refreshImeState();
                finish();
            }, 300);
        }
    }
}
