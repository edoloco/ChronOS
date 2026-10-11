package cl.mesadoble;

import android.app.Application;

public class MesaApp extends Application {
    @Override public void onCreate() {
        super.onCreate();
        // Conecta apenas arranca la app, también cuando la abre el teclado receptor.
        Hub.get(this).ensureStarted();
    }
}
