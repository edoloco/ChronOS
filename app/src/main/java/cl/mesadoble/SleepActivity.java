package cl.mesadoble;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

/**
 * Sin permiso para bloquear: pantalla negra al mínimo de brillo que deja que el teléfono se apague solo.
 * Se cierra al tocarla o cuando el principal vuelve.
 */
public class SleepActivity extends Activity {

    private static SleepActivity current;

    static void close() { if (current != null) current.finish(); }

    @SuppressWarnings("deprecation")
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        current = this;
        TextView t = new TextView(this);
        t.setText("Principal bloqueado · toca para volver");
        t.setTextColor(0x33FFFFFF);
        t.setGravity(Gravity.CENTER);
        t.setBackgroundColor(Color.BLACK);
        t.setOnClickListener(v -> finish());
        setContentView(t);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = 0f;
        getWindow().setAttributes(lp);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
    }

    @Override protected void onDestroy() { if (current == this) current = null; super.onDestroy(); }
}
