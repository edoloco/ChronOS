package cl.mesadoble;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

/**
 * Pantallita invisible para el chat de YouTube: elegir la cuenta de Google del teléfono y darle permiso a ChronOS
 * para escribir en los chats en vivo (YouTube Data API). Se abre sola la primera vez que se manda un mensaje.
 */
public class YtLogin extends Activity {

    static void start(Context c) {
        c.startActivity(new Intent(c, YtLogin.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        String acc = getSharedPreferences("mesa", MODE_PRIVATE).getString("ytAcc", "");
        if (acc.isEmpty()) {
            Intent i = AccountManager.newChooseAccountIntent(null, null, new String[]{"com.google"},
                    "Cuenta para escribir en el chat en vivo de YouTube", null, null, null);
            try { startActivityForResult(i, 1); } catch (Exception e) { done(null, "No se pudo abrir la elección de cuenta."); }
        } else auth(acc);
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        String name = data == null ? null : data.getStringExtra(AccountManager.KEY_ACCOUNT_NAME);
        if (res != RESULT_OK || name == null) { done(null, "No se eligió una cuenta: el chat queda solo para leer."); return; }
        getSharedPreferences("mesa", MODE_PRIVATE).edit().putString("ytAcc", name).apply();
        auth(name);
    }

    private void auth(String name) {
        AccountManager.get(this).getAuthToken(new Account(name, "com.google"), LiveChat.SCOPE, null, this, f -> {
            try {
                String t = f.getResult().getString(AccountManager.KEY_AUTHTOKEN);
                done(name, t == null ? "Google no entregó el permiso." : null);
            } catch (Exception e) {
                done(null, LiveChat.friendly(String.valueOf(e.getMessage())));
            }
        }, null);
    }

    private void done(String account, String error) {
        LiveChat.loginDone(account, error);
        finish();
    }
}
