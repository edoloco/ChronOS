package cl.mesadoble;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Conexión única entre los dos teléfonos, compartida por toda la app:
 * la mesa, el teclado que escribe y el teclado que recibe usan la misma.
 * Vive mientras el proceso de la app vive (lo sostiene LinkService).
 */
public class Hub implements Link.Listener {

    private static Hub inst;

    public static synchronized Hub get(Context c) {
        if (inst == null) inst = new Hub(c.getApplicationContext());
        return inst;
    }

    private final Context app;
    private final SharedPreferences prefs;
    public final Link link;
    private final List<Link.Listener> listeners = new CopyOnWriteArrayList<>();
    private int state = Link.IDLE, side;
    private String info;
    private boolean started, imeReady, remoteImeReady, remoteImeSelected, remotePointer;
    private float accX, accY;   // touchpad sin puntero (versión liviana): mueve el cursor del texto
    private long lastNotify = 0;
    public int acksOk = 0, acksFail = 0;
    /** Hay una pantalla de Mesa Doble visible en este teléfono (permite abrir apps directo). */
    public boolean foreground;
    /** La pantalla de teclado está abierta en este teléfono. */
    public boolean keyboardVisible;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable restoreIme = () -> { if (!connected() && MesaAccess.instance != null) MesaAccess.instance.restorePrevious(); };

    private Hub(Context c) {
        app = c;
        prefs = c.getSharedPreferences("mesa", Context.MODE_PRIVATE);
        side = prefs.getInt("side", -1);
        link = new Link(c, this);
        ScreenSync.listen(c, this);
        // batería: la de este teléfono se comparte con el otro para el widget de baterías
        c.registerReceiver(new android.content.BroadcastReceiver() {
            @Override public void onReceive(Context cx, Intent i) {
                int lvl = i.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1), scale = i.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100);
                int st = i.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1);
                int pct = lvl >= 0 && scale > 0 ? Math.round(lvl * 100f / scale) : -1;
                boolean chg = st == android.os.BatteryManager.BATTERY_STATUS_CHARGING || st == android.os.BatteryManager.BATTERY_STATUS_FULL;
                if (pct == myBatt && chg == myChg) return;
                myBatt = pct; myChg = chg;
                sendBattery();
            main.removeCallbacks(musicPoll);
            musicActive = false;
            videoActive = false;
            main.postDelayed(musicPoll, 2000);
            }
        }, new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        // al desinstalar una app, la lista de apps del otro teléfono se actualiza
        android.content.IntentFilter pf = new android.content.IntentFilter(Intent.ACTION_PACKAGE_REMOVED);
        pf.addDataScheme("package");
        c.registerReceiver(new android.content.BroadcastReceiver() {
            @Override public void onReceive(Context cx, Intent i) {
                if (i.getBooleanExtra(Intent.EXTRA_REPLACING, false) || i.getData() == null) return;
                JSONObject m = new JSONObject();
                try { m.put("t", "appRemoved"); m.put("pkg", i.getData().getSchemeSpecificPart()); } catch (JSONException ignored) { }
                for (Link.Listener l : listeners) l.onMessage(m);
                if (isPrincipal()) send(m);
            }
        }, pf);
    }

    public int side() { return side; }
    /** Lado 1 = derecha = PRINCIPAL (donde corren las apps). Lado 0 = izquierda = EXTENDIDO. */
    public boolean isPrincipal() { return side == 1; }
    public boolean autoKeyboard() { return prefs.getBoolean("autoKb", true); }
    public int state() { return state; }
    public boolean connected() { return state == Link.CONNECTED; }
    public boolean remoteImeReady() { return remoteImeReady; }
    public boolean remoteImeSelected() { return remoteImeSelected; }
    /** El principal tiene puntero real (versión completa con accesibilidad activa). */
    public boolean remotePointer() { return remotePointer; }

    /** ¿El teclado Mesa Doble está activado en la lista de teclados de ESTE teléfono? */
    public static boolean imeEnabled(Context c) {
        InputMethodManager imm = (InputMethodManager) c.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm == null) return false;
        for (InputMethodInfo i : imm.getEnabledInputMethodList())
            if (c.getPackageName().equals(i.getPackageName())) return true;
        return false;
    }

    /** ¿El teclado Mesa Doble es el teclado elegido ahora en ESTE teléfono? */
    public static boolean imeSelected(Context c) {
        try {
            String cur = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
            return cur != null && cur.startsWith(c.getPackageName() + "/");
        } catch (Exception e) { return false; }
    }

    public void refreshImeState() {
        if (imeSelected(app)) cancelNotice();
        sendImeState();
    }

    public void ensureStarted() {
        if (side >= 0 && !started) { started = true; link.start(side == 0); }
    }

    public void setSide(int s) {
        boolean changed = s != side;
        side = s;
        prefs.edit().putInt("side", s).apply();
        if (changed || !started) { started = true; link.start(s == 0); }
    }

    /** Agrega un oyente y le informa de inmediato el estado actual. */
    public void add(Link.Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
        l.onState(state, info);
    }

    public void remove(Link.Listener l) { listeners.remove(l); }

    public volatile int myBatt = -1, otherBatt = -1;
    public volatile boolean myChg, otherChg;

    private void sendBattery() {
        JSONObject m = new JSONObject();
        try { m.put("t", "batt"); m.put("l", myBatt); m.put("c", myChg); } catch (JSONException ignored) { }
        if (connected()) send(m);
        local(m);
    }

    /** Entrega un mensaje a las pantallas de ESTE teléfono, como si hubiera llegado del otro. */
    public void local(JSONObject m) { for (Link.Listener l : listeners) l.onMessage(m); }

    public void send(JSONObject m) { link.send(m); }

    public void send(String type, Object... kv) {
        JSONObject m = new JSONObject();
        try {
            m.put("t", type);
            for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        } catch (JSONException ignored) { }
        link.send(m);
    }

    // ---------------- teclado receptor ----------------

    /** El teclado Mesa Doble de ESTE teléfono está (o no) listo para recibir texto. */
    public void setImeReady(boolean r) {
        if (r) cancelNotice();
        if (imeReady == r) return;
        imeReady = r;
        sendImeState();
    }

    private void sendImeState() { send("kbState", "ready", imeReady, "sel", imeSelected(app), "ptr", MesaAccess.instance != null); }

    /** Aviso en el teléfono que recibe: un toque y queda elegido el teclado Mesa Doble. */
    private void notifySwitch() {
        long now = System.currentTimeMillis();
        if (now - lastNotify < 15000 || imeSelected(app)) return;
        lastNotify = now;
        NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (nm.getNotificationChannel("avisos") == null)
            nm.createNotificationChannel(new NotificationChannel("avisos", "Avisos del teclado", NotificationManager.IMPORTANCE_HIGH));
        Intent i = new Intent(app, ImePickerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(app, 1, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        int icon = app.getResources().getIdentifier("ic_stat", "drawable", app.getPackageName());
        Notification n = new Notification.Builder(app, "avisos")
                .setSmallIcon(icon != 0 ? icon : android.R.drawable.stat_notify_more)
                .setContentTitle("El otro teléfono quiere escribir aquí")
                .setContentText("Toca para usar el teclado Mesa Doble")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        try { nm.notify(2, n); } catch (Exception ignored) { }
    }

    private void cancelNotice() {
        NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(2);
    }

    // ---------------- Link.Listener ----------------

    @Override public void onState(int st, String inf) {
        state = st;
        info = inf;
        if (st == Link.CONNECTED) {
            sendImeState();
            sendBattery();
            FileServer fs = FileServer.get(app);
            fs.start();
            send("fsTok", "k", fs.token, "port", FileServer.PORT);
            main.removeCallbacks(restoreIme);
            if (isPrincipal() && MesaAccess.instance != null) MesaAccess.instance.switchToMesa();
            else if (isPrincipal() && !imeSelected(app)) notifySwitch();   // versión liviana: un toque en el aviso
        } else {
            remoteImeReady = false;
            // sin el extendido no hay teclado: si la conexión no vuelve pronto, devolver el teclado normal
            if (isPrincipal()) { main.removeCallbacks(restoreIme); main.postDelayed(restoreIme, 6000); }
        }
        for (Link.Listener l : listeners) l.onState(st, inf);
    }

    @Override public void onMessage(JSONObject m) {
        String t = m.optString("t");
        if ("media".equals(t)) { media(m.optString("k")); return; }
        if ("screen".equals(t)) { if (!isPrincipal()) ScreenSync.apply(app, this, m.optString("s")); return; }
        if ("scrSync".equals(t)) { prefs.edit().putBoolean("syncScreen", m.optBoolean("on", true)).apply(); }
        if ("launch".equals(t)) {
            int r = launchHere(m.optString("pkg"), m.optString("label"));
            send("launchAck", "r", r, "label", m.optString("label"));
            return;
        }
        if ("uninstall".equals(t)) {
            int r = uninstallHere(m.optString("pkg"), m.optString("label"));
            send("uninstallAck", "r", r, "label", m.optString("label"));
            return;
        }
        if ("alarms".equals(t)) { openAlarms(); return; }
        if ("game".equals(t)) { if (!isPrincipal()) GameBg.handle(app, this, m); return; }
        // cámara del principal, controlada desde el extendido
        if ("camCmd".equals(t)) { if (isPrincipal()) CameraActivity.command(app, m); return; }

        // widget de apps recientes: la lista es del principal
        if ("recReq".equals(t)) {
            if (!isPrincipal()) return;
            final String have = m.optString("sig", "\u0000");
            RecentApps.worker.execute(() -> {
                JSONObject r = RecentApps.message(app);
                if (!r.optString("sig").equals(have)) send(r);
            });
            return;
        }
        if ("recAccess".equals(t)) {
            if (isPrincipal()) startOrNotify(RecentApps.accessIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), 44,
                    "Apps recientes", "Toca y activa Mesa Doble en \"Acceso a datos de uso\"");
            return;
        }
        if ("update".equals(t)) { Updater.updateFromRemote(app, this); return; }
        if ("fmOpen".equals(t)) {
            startOrNotify(new Intent(app, FilesActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra("remote", true), 22, "Archivos", "Toca para abrir el gestor de archivos aquí");
            return;
        }
        if ("fmPath".equals(t)) FileServer.remoteDir = m.optString("path");
        if ("batt".equals(t)) { otherBatt = m.optInt("l", -1); otherChg = m.optBoolean("c"); }
        // fotos, música y video: se ven en el principal; los controles, en el extendido
        if ("mPlay".equals(t)) { if (isPrincipal()) MediaHub.open(app, m.optString("kind"), MediaHub.paths(m), m.optInt("idx")); return; }
        if ("mCmd".equals(t)) { if (isPrincipal()) MediaHub.command(app, m); return; }
        // un video empezó en el principal: el control de video se abre UNA vez (si se cierra, no vuelve hasta otro video)
        if ("mActive".equals(t) && !isPrincipal() && m.optBoolean("on") && m.optBoolean("video") && m.optBoolean("fresh")
                && !KeyboardActivity.showingMedia)
            MediaHub.openControls(app, MediaHub.VIDEO);
        if ("mOpen".equals(t)) { if (!isPrincipal()) MediaHub.openControls(app, m.optString("kind")); return; }
        // widgets: todos viven en el principal
        if ("wList".equals(t)) { if (isPrincipal()) WidgetHub.get(app).sendProviders(this); return; }
        if ("wWant".equals(t)) { if (isPrincipal()) WidgetHub.get(app).want(m, this); return; }
        if ("wTap".equals(t)) { if (isPrincipal()) WidgetHub.get(app).tap(m); return; }
        if ("wBind".equals(t)) {
            if (isPrincipal()) startOrNotify(new Intent(app, MainActivity.class).putExtra("bindWidget", m.optString("id"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP), 24,
                    "Activar widget", "Toca para permitir el widget en el principal");
            return;
        }
        if ("fmJob".equals(t)) { if (isPrincipal()) FileServer.get(app).runJob(m, this); return; }
        if ("fmOpenFile".equals(t)) { if (isPrincipal()) FileServer.openHere(app, this, m.optString("path")); return; }
        if ("fmCancel".equals(t)) { FileServer.get(app).cancelJob(); return; }
        if ("appsReq".equals(t)) { AppsDialog.sendList(app, this); return; }
        if (t.startsWith("fs") && !t.endsWith("R") && !"fsTok".equals(t)) { FileServer.get(app).handle(m, this); return; }
        if ("fsTok".equals(t)) FileServer.get(app).setRemote(m.optString("k"), m.optInt("port"));
        if ("kbAsk".equals(t)) { sendImeState(); if (!imeSelected(app)) notifySwitch(); return; }
        if ("mouse".equals(t) || "click".equals(t) || "scroll".equals(t) || "nav".equals(t) || "pad".equals(t)) {
            if (MesaAccess.instance != null && MesaAccess.instance.handle(m)) return;
            pointerFallback(m);
            return;
        }
        if ("kbState".equals(t)) {
            remotePointer = m.optBoolean("ptr");
            boolean wasReady = remoteImeReady;
            remoteImeReady = m.optBoolean("ready"); remoteImeSelected = m.optBoolean("sel");
            // el principal tocó un campo de texto: mostrar el teclado aquí, en el extendido
            if (!isPrincipal() && remoteImeReady && !wasReady && autoKeyboard()) openKeyboard();
        }
        if ("openKb".equals(t) && !isPrincipal() && autoKeyboard()) openKeyboard();
        if ("kbAck".equals(t)) { if (m.optBoolean("ok")) acksOk++; else acksFail++; }
        for (Link.Listener l : listeners) l.onMessage(m);
        if ("kb".equals(t)) {
            // confirma cada envío: así el que escribe sabe si llegó al campo de texto
            boolean sel = imeSelected(app);
            send("kbAck", "ok", imeReady, "sel", sel);
            if (!imeReady && !sel) notifySwitch();
        }
    }

    /** Abre la pantalla de teclado en este teléfono (extendido). */
    public void openKeyboard() {
        if (keyboardVisible) return;
        Intent i = new Intent(app, KeyboardActivity.class)
                .putExtra("auto", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (foreground || Settings.canDrawOverlays(app)) {
            try { app.startActivity(i); return; } catch (Exception ignored) { }
        }
        NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (nm.getNotificationChannel("avisos") == null)
            nm.createNotificationChannel(new NotificationChannel("avisos", "Avisos del teclado", NotificationManager.IMPORTANCE_HIGH));
        PendingIntent pi = PendingIntent.getActivity(app, 4, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        int icon = app.getResources().getIdentifier("ic_stat", "drawable", app.getPackageName());
        try {
            nm.notify(4, new Notification.Builder(app, "avisos")
                    .setSmallIcon(icon != 0 ? icon : android.R.drawable.stat_notify_more)
                    .setContentTitle("Teclado listo")
                    .setContentText("Toca para escribir en el principal")
                    .setContentIntent(pi).setAutoCancel(true).build());
        } catch (Exception ignored) { }
    }

    public static final int LAUNCHED = 0, NOTIFIED = 1, NOT_INSTALLED = 2;

    /**
     * Abre una app en ESTE teléfono. Si Mesa Doble no está a la vista, Android no deja abrir apps
     * desde segundo plano (salvo con el permiso "mostrar sobre otras apps"); en ese caso deja un aviso
     * que se abre con un toque.
     */
    /**
     * "Desktop" del widget de apps recientes: la MESA de Mesa Doble en este teléfono (no la pantalla de inicio).
     * Igual que con las apps: inicio sin animación y enseguida la mesa; se reintenta hasta que la mesa esté al frente.
     */
    public void goDesk() {
        Intent desk = new Intent(app, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        startOwn(desk, () -> foreground, 46, "Volver a la mesa", "Toca para ver la mesa de Mesa Doble");
    }

    /**
     * Abre una pantalla de Mesa Doble aunque otra app esté al frente: inicio sin animación y enseguida la pantalla,
     * reintentando hasta que 'shown' sea verdadero; si no se logra, queda un aviso de un toque.
     */
    public void startOwn(Intent i, java.util.function.BooleanSupplier shown, int code, String title, String text) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (shown.getAsBoolean()) return;
        if (foreground || Settings.canDrawOverlays(app)) { try { app.startActivity(i); return; } catch (Exception ignored) { } }
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        try { app.startActivity(home); } catch (Exception ignored) { }
        for (int ms : new int[]{60, 250, 600, 1100})
            main.postDelayed(() -> { if (!shown.getAsBoolean()) try { app.startActivity(i); } catch (Exception ignored) { } }, ms);
        main.postDelayed(() -> { if (!shown.getAsBoolean()) startOrNotify(i, code, title, text); }, 1700);
    }

    public int launchHere(String pkg, String label) {
        if (pkg.equals(app.getPackageName())) { goDesk(); return LAUNCHED; }
        Intent i = app.getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) return NOT_INSTALLED;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        return startExternal(i, pkg, label);
    }

    /** Abre una pantalla de OTRA app aunque haya otra al frente (truco del inicio; aviso si no resulta). */
    public int startExternal(Intent i, String pkg, String label) { return startExternal(i, pkg, label, true); }

    /** notify=false: si no resulta, no deja aviso (para órdenes repetidas como las de la cámara). */
    public int startExternal(Intent i, String pkg, String label, boolean notify) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (foreground || Settings.canDrawOverlays(app)) { try { app.startActivity(i); } catch (Exception ignored) { } return LAUNCHED; }
        if (foreground || Settings.canDrawOverlays(app)) return startOrNotify(i, pkg.hashCode(), "Abrir " + label, "Toca para abrirla");
        // Con otra app al frente Android no deja abrir apps desde el fondo, pero sí ir al inicio; justo después
        // de eso da unos segundos de gracia: se va al inicio (sin animación) y enseguida se abre la app.
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        try { app.startActivity(home); } catch (Exception ignored) { }
        main.postDelayed(() -> { try { app.startActivity(i); } catch (Exception ignored) { } }, 70);
        // si igual no se abrió (se puede comprobar con el acceso a datos de uso), queda el aviso de un toque
        if (notify && !pkg.isEmpty() && RecentApps.hasAccess(app) && !pkg.equals(app.getPackageName()))
            main.postDelayed(() -> RecentApps.worker.execute(() -> {
                if (!pkg.equals(RecentApps.active(app)))
                    main.post(() -> startOrNotify(i, pkg.hashCode(), "Abrir " + (label.isEmpty() ? "la app" : label), "Toca para abrirla en este teléfono"));
            }), 1800);
        return LAUNCHED;
    }

    /** Alarmas del reloj de ESTE teléfono (aunque otra app esté al frente). */
    public void openAlarms() {
        Intent i = ClockWidget.alarmsIntent(app).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        String pkg = i.getPackage() != null ? i.getPackage() : i.getComponent() != null ? i.getComponent().getPackageName() : "";
        if (i.resolveActivity(app.getPackageManager()) == null) {
            main.post(() -> android.widget.Toast.makeText(app, "No encontré la app de alarmas", android.widget.Toast.LENGTH_SHORT).show());
            if (connected()) send("alarmsAck", "ok", false);
            return;
        }
        startExternal(i, pkg, "Alarmas", false);
    }

    /** Desinstalar una app de ESTE teléfono (Android siempre pide confirmación). */
    public int uninstallHere(String pkg, String label) {
        try { app.getPackageManager().getPackageInfo(pkg, 0); } catch (Exception e) { return NOT_INSTALLED; }
        Intent i = new Intent(Intent.ACTION_DELETE, android.net.Uri.parse("package:" + pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return startOrNotify(i, ("del" + pkg).hashCode(), "Desinstalar " + (label.isEmpty() ? "la app" : label), "Toca para confirmar en este teléfono");
    }

    /** Abre una pantalla; si Android no lo permite con la app de fondo, deja un aviso de un toque. */
    public int startOrNotify(Intent i, int reqCode, String title, String text) {
        if (foreground || Settings.canDrawOverlays(app)) {
            try { app.startActivity(i); return LAUNCHED; } catch (Exception ignored) { }
        }
        NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return NOTIFIED;
        if (nm.getNotificationChannel("avisos") == null)
            nm.createNotificationChannel(new NotificationChannel("avisos", "Avisos del teclado", NotificationManager.IMPORTANCE_HIGH));
        PendingIntent pi = PendingIntent.getActivity(app, reqCode, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        int icon = app.getResources().getIdentifier("ic_stat", "drawable", app.getPackageName());
        Notification n = new Notification.Builder(app, "avisos")
                .setSmallIcon(icon != 0 ? icon : android.R.drawable.stat_notify_more)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        try { nm.notify(3, n); } catch (Exception ignored) { }
        return NOTIFIED;
    }

    /**
     * Sin accesibilidad (versión liviana) no hay puntero: el touchpad mueve el cursor del texto
     * y el gamepad manda flechas y teclas al campo donde se escribe.
     */
    private void pointerFallback(JSONObject m) {
        String t = m.optString("t");
        if ("mouse".equals(t)) {
            accX += m.optInt("dx"); accY += m.optInt("dy");
            while (accX > 28) { key(KeyEvent.KEYCODE_DPAD_RIGHT); accX -= 28; }
            while (accX < -28) { key(KeyEvent.KEYCODE_DPAD_LEFT); accX += 28; }
            while (accY > 36) { key(KeyEvent.KEYCODE_DPAD_DOWN); accY -= 36; }
            while (accY < -36) { key(KeyEvent.KEYCODE_DPAD_UP); accY += 36; }
        } else if ("scroll".equals(t)) {
            int d = m.optInt("d");
            for (int i = 0; i < Math.abs(d); i++) key(d > 0 ? KeyEvent.KEYCODE_DPAD_UP : KeyEvent.KEYCODE_DPAD_DOWN);
        } else if ("nav".equals(t)) {
            String k = m.optString("k");
            if ("back".equals(k)) key(KeyEvent.KEYCODE_BACK);          // llega a la app por el teclado Mesa Doble
            else if ("home".equals(k)) goHome();
            else send("navAck", "k", k, "ok", false);                 // Recientes: Android no lo permite sin accesibilidad
        } else if ("pad".equals(t) && "btn".equals(m.optString("a")) && m.optBoolean("down")) {
            switch (m.optString("id")) {
                case "dpad_up": key(KeyEvent.KEYCODE_DPAD_UP); break;
                case "dpad_down": key(KeyEvent.KEYCODE_DPAD_DOWN); break;
                case "dpad_left": key(KeyEvent.KEYCODE_DPAD_LEFT); break;
                case "dpad_right": key(KeyEvent.KEYCODE_DPAD_RIGHT); break;
                case "a": case "start": key(KeyEvent.KEYCODE_ENTER); break;
                case "b": key(KeyEvent.KEYCODE_DEL); break;
                case "x": key(KeyEvent.KEYCODE_SPACE); break;
                case "y": key(KeyEvent.KEYCODE_TAB); break;
            }
        }
    }

    /** Ir a la pantalla de inicio del principal. Si Android lo impide (app de fondo), deja un aviso de un toque. */
    private void goHome() {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { app.startActivity(i); return; } catch (Exception ignored) { }
        NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (nm.getNotificationChannel("avisos") == null)
            nm.createNotificationChannel(new NotificationChannel("avisos", "Avisos del teclado", NotificationManager.IMPORTANCE_HIGH));
        PendingIntent pi = PendingIntent.getActivity(app, 5, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        int icon = app.getResources().getIdentifier("ic_stat", "drawable", app.getPackageName());
        try {
            nm.notify(5, new Notification.Builder(app, "avisos")
                    .setSmallIcon(icon != 0 ? icon : android.R.drawable.stat_notify_more)
                    .setContentTitle("Ir al inicio").setContentText("Toca para ir a la pantalla de inicio")
                    .setContentIntent(pi).setAutoCancel(true).build());
        } catch (Exception ignored) { }
    }

    /** Entrega una tecla al teclado Mesa Doble de este teléfono, como si llegara del extendido. */
    private void key(int code) {
        JSONObject k = new JSONObject();
        try { k.put("t", "kb"); k.put("a", "key"); k.put("k", code); k.put("m", 0); } catch (JSONException ignored) { }
        for (Link.Listener l : listeners) l.onMessage(k);
    }

    /** Multimedia y volumen: funcionan aunque el teclado receptor no esté activo. */
    void media(String k) {
        AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        switch (k) {
            case "vup": am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI); break;
            case "vdown": am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI); break;
            case "mute": am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI); break;
            case "play": mediaKey(am, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE); break;
            case "next": mediaKey(am, KeyEvent.KEYCODE_MEDIA_NEXT); break;
            case "prev": mediaKey(am, KeyEvent.KEYCODE_MEDIA_PREVIOUS); break;
            case "ff": mediaKey(am, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD); break;
            case "rw": mediaKey(am, KeyEvent.KEYCODE_MEDIA_REWIND); break;
        }
    }

    // ---------------- video en otras apps del principal ----------------
    // Sin permisos especiales Android no dice si un video está en pantalla completa; sí dice qué TIPO de sonido
    // se reproduce. Las apps de video (YouTube, Netflix, reproductores) lo marcan como "película": con eso el
    // extendido abre el control de video una sola vez por video (vuelve a ofrecerse tras 1 minuto sin video).

    private boolean musicActive, videoActive, videoSession;
    private long lastVideoAt;
    private final Runnable musicPoll = new Runnable() {
        @Override public void run() {
            if (!isPrincipal() || !connected()) return;
            RecentApps.worker.execute(() -> GameBg.poll(app, Hub.this));   // ¿empezó un juego? → fondo en el extendido
            RecentApps.worker.execute(Hub.this::pollNotifs);                // apps con notificaciones nuevas → punto rojo
            AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
            boolean on = am != null && am.isMusicActive() && MediaHub.current == null;   // los visores propios ya tienen sus controles
            boolean video = on && moviePlaying(am);
            long now = SystemClock.uptimeMillis();
            boolean fresh = false;
            if (video) {
                lastVideoAt = now;
                if (!videoSession) { videoSession = true; fresh = true; }
            } else if (videoSession && now - lastVideoAt > 60000) videoSession = false;
            if (on != musicActive || video != videoActive || fresh) {
                musicActive = on;
                videoActive = video;
                send("mActive", "on", on, "video", video, "fresh", fresh);
            }
            main.postDelayed(this, 1500);
        }
    };

    /** ¿Algo suena como película (video)? */
    private static boolean moviePlaying(AudioManager am) {
        try {
            for (android.media.AudioPlaybackConfiguration pc : am.getActivePlaybackConfigurations()) {
                android.media.AudioAttributes a = pc.getAudioAttributes();
                if (a != null && a.getContentType() == android.media.AudioAttributes.CONTENT_TYPE_MOVIE) return true;
            }
        } catch (Exception ignored) { }
        return false;
    }

    // ---------------- apps con notificaciones (punto rojo en la mesa) ----------------
    private String notifSig = "";
    private long notifSentAt;

    private void pollNotifs() {
        if (!RecentApps.hasAccess(app)) return;
        java.util.List<String> l = RecentApps.unseen(app);
        String sig = android.text.TextUtils.join(",", l);
        long now = SystemClock.uptimeMillis();
        if (sig.equals(notifSig) && now - notifSentAt < 30000) return;
        notifSig = sig;
        notifSentAt = now;
        JSONObject m = new JSONObject();
        try { m.put("t", "notifs"); m.put("p", new JSONArray(l)); } catch (JSONException ignored) { }
        send(m);
        main.post(() -> local(m));
    }

    private void mediaKey(AudioManager am, int code) {
        long t = SystemClock.uptimeMillis();
        am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0));
        am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0));
    }
}
