package cl.mesadoble;

import android.app.Application;

public class MesaApp extends Application {
    @Override public void onCreate() {
        super.onCreate();
        CardTheme.load(this);   // colores de tarjetas y menús para todas las pantallas
        // Conecta apenas arranca la app, también cuando la abre el teclado receptor.
        Hub.get(this).ensureStarted();
        try { ClockWidget.refresh(this); } catch (Exception ignored) { }   // los relojes ya puestos usan el toque nuevo
    }
}
