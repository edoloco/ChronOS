package cl.mesadoble;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;

/**
 * Pregunta de la red temporal (método de unión "Bluetooth"): "offer" = algo pesado necesita la red del principal
 * (Sí / Siempre sí / No); "idle" = la red no se usa hace rato, ¿volver a Bluetooth?
 */
public class NetAsk extends Activity {

    private boolean answered;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Hub hub = Hub.get(this);
        boolean idle = "idle".equals(getIntent().getStringExtra("k"));
        String why = getIntent().getStringExtra("why");
        AlertDialog.Builder d = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert);
        if (idle) {
            d.setTitle("¿Volver a Bluetooth?")
             .setMessage("La red rápida del principal no se usa hace 2 minutos. ¿La apago para ahorrar batería?")
             .setPositiveButton("Sí, apagar", (x, y) -> hub.send("netStop"))
             .setNegativeButton("Dejarla", (x, y) -> hub.send("netKeep"));
        } else {
            d.setTitle("Conectar la red del principal")
             .setMessage((why != null && !why.isEmpty() ? why : "Esto") + " necesita una conexión más rápida que Bluetooth. "
                     + "¿Uno los teléfonos por una red Wi-Fi temporal del principal? Se conecta sola y se apaga cuando ya no se usa.")
             .setPositiveButton("Sí", (x, y) -> { answered = true; hub.netGo(); })
             .setNeutralButton("Siempre sí", (x, y) -> {
                 answered = true;
                 getSharedPreferences("mesa", MODE_PRIVATE).edit().putBoolean("netAlways", true).apply();
                 hub.send("prefSet", "k", "netAlways", "b", true);
                 hub.netGo();
             })
             .setNegativeButton("No", (x, y) -> hub.netDeclined());
        }
        d.setOnDismissListener(x -> { if (!idle && !answered) hub.netDeclined(); finish(); });   // cerrar sin elegir = "No"
        d.show();
    }
}
