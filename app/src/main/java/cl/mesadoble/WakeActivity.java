package cl.mesadoble;

import android.app.Activity;
import android.app.KeyguardManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

/** Invisible: enciende la pantalla del extendido y, si el principal se desbloqueó, quita el bloqueo. */
public class WakeActivity extends Activity {

    private final Handler h = new Handler(Looper.getMainLooper());

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        ScreenSync.cancelNotice(this);
        KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        boolean unlock = getIntent().getBooleanExtra("unlock", false);
        if (unlock && km != null && km.isKeyguardLocked()) {
            km.requestDismissKeyguard(this, new KeyguardManager.KeyguardDismissCallback() {
                @Override public void onDismissSucceeded() { finish(); }
                @Override public void onDismissCancelled() { finish(); }
                @Override public void onDismissError() { finish(); }
            });
            h.postDelayed(this::finish, 30000);   // si tiene PIN, da tiempo a escribirlo
        } else {
            h.postDelayed(this::finish, 600);
        }
    }

    @Override protected void onDestroy() { h.removeCallbacksAndMessages(null); super.onDestroy(); }
}
