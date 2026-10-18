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
    /** Hay una pantalla de ChronOS visible en este teléfono (permite abrir apps directo). */
    public boolean foreground;
    /** La pantalla de teclado está abierta en este teléfono. */
    public boolean keyboardVisible;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable restoreIme = () -> { if (!connected() && MesaAccess.instance != null) MesaAccess.instance.restorePrevious(); };

    private Hub(Context c) {
        app = c;
        prefs = c.getSharedPreferences("mesa", Context.MODE_PRIVATE);
        SystemSetup.preset(c, prefs);   // ChronOS instalado en el sistema (J7 Prime): papel de fábrica y ajustes
        side = prefs.getInt("side", -1);
        link = new Link(c, this);
        ScreenSync.listen(c, this);
        LidWatch.update(c, this);
        CallHub.listen(c, this);   // llamadas del principal → controles en el extendido
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
        // instalar en ambos: al instalarse una app nueva en el principal, se ofrece también en el extendido
        android.content.IntentFilter af = new android.content.IntentFilter(Intent.ACTION_PACKAGE_ADDED);
        af.addDataScheme("package");
        c.registerReceiver(new android.content.BroadcastReceiver() {
            @Override public void onReceive(Context cx, Intent i) {
                if (i.getBooleanExtra(Intent.EXTRA_REPLACING, false) || i.getData() == null) return;
                AppShare.added(app, Hub.this, i.getData().getSchemeSpecificPart());
            }
        }, af);
    }

    public String peerIp() { return link.peerIp(); }

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

    /** ¿El teclado ChronOS está activado en la lista de teclados de ESTE teléfono? */
    public static boolean imeEnabled(Context c) {
        InputMethodManager imm = (InputMethodManager) c.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm == null) return false;
        for (InputMethodInfo i : imm.getEnabledInputMethodList())
            if (c.getPackageName().equals(i.getPackageName())) return true;
        return false;
    }

    /** ¿El teclado ChronOS es el teclado elegido ahora en ESTE teléfono? */
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

    private boolean battOffered, lostOffered, lostPending;
    private boolean brPermAsked;

    private SensorProbe senProbe;
    private final Runnable senStop = () -> { if (senProbe != null) senProbe.stop(); senProbe = null; };

    /** Reintentar la conexión ya: el principal busca al extendido; el extendido llama al principal. */
    public void reconnect() {
        if (connected()) return;
        if (isPrincipal()) forceLink();
        else link.call(null);
    }

    /** Principal: buscar al extendido a la fuerza (última IP + todo el WiFi). */
    public void forceLink() {
        if (!isPrincipal() || connected()) return;
        lostOffered = false;
        link.force(app.getSharedPreferences("mesa", Context.MODE_PRIVATE).getString("extIp", ""));
        main.removeCallbacks(lostCheck);
        lostPending = true;
        main.postDelayed(lostCheck, 15_000);   // si no aparece, se vuelve a ofrecer
    }
    private final Runnable lostCheck = () -> {
        lostPending = false;
        if (connected() || lostOffered || !isPrincipal() || MainActivity.soloMode(Hub.this.app)) return;   // en una pantalla no se ofrece nada
        lostOffered = true;
        PhoneMode.offer(Hub.this.app, this, PhoneMode.LOST);
    };

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

    /** El teclado ChronOS de ESTE teléfono está (o no) listo para recibir texto. */
    public void setImeReady(boolean r) {
        if (r) cancelNotice();
        if (imeReady == r) return;
        imeReady = r;
        sendImeState();
    }

    private void sendImeState() { send("kbState", "ready", imeReady, "sel", imeSelected(app), "ptr", MesaAccess.instance != null); }

    /** Aviso en el teléfono que recibe: un toque y queda elegido el teclado ChronOS. */
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
                .setContentText("Toca para usar el teclado ChronOS")
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
            // revisión periódica del principal (video, juegos, notificaciones): arranca al conectarse
            main.removeCallbacks(musicPoll);
            musicActive = false;
            videoActive = false;
            videoAppWas = false;
            main.postDelayed(musicPoll, 1500);
            if (isPrincipal()) main.postDelayed(this::resendTicker, 1500);
            sendImeState();
            sendBattery();
            FileServer fs = FileServer.get(app);
            fs.start();
            send("fsTok", "k", fs.token, "port", FileServer.PORT);
            if (isPrincipal()) main.postDelayed(() -> Sketch.resend(this), 2500);
            main.removeCallbacks(restoreIme);
            if (isPrincipal() && link.peerIp() != null) app.getSharedPreferences("mesa", Context.MODE_PRIVATE).edit().putString("extIp", link.peerIp()).apply();
            app.getSharedPreferences("mesa", Context.MODE_PRIVATE).edit().putLong("lastLinkAt", System.currentTimeMillis()).apply();
            main.removeCallbacks(lostCheck);
            lostPending = false;
            lostOffered = false;
            Torch.listen(app, this);
            send("brNow", "f", (double) Bright.mine(app), "ok", Bright.canWrite(app));
            send("torchState", "on", Torch.mine);
            // si la conexión volvió justo al desbloquear el principal, el extendido igual se entera
            if (isPrincipal() && ScreenSync.enabled(app)) {
                android.os.PowerManager pm = (android.os.PowerManager) app.getSystemService(Context.POWER_SERVICE);
                android.app.KeyguardManager km = (android.app.KeyguardManager) app.getSystemService(Context.KEYGUARD_SERVICE);
                if (pm != null && pm.isInteractive() && km != null && !km.isKeyguardLocked()) send("screen", "s", "unlock");
            }
            if (isPrincipal() && PhoneMode.active(app)) PhoneMode.extendedBack(app);   // en modo normal: solo un aviso
            else if (isPrincipal() && MesaAccess.instance != null) MesaAccess.instance.switchToMesa();
            else if (isPrincipal() && !imeSelected(app)) notifySwitch();   // versión liviana: un toque en el aviso
        } else {
            remoteImeReady = false;
            main.post(senStop);
            // Game Pal: sin conexión no llegará el "soltar": los dedos virtuales del principal se levantan ya
            if (isPrincipal() && MesaAccess.instance != null) GamePalTouch.releaseAll(MesaAccess.instance);
            // sin el extendido no hay teclado: si la conexión no vuelve pronto, devolver el teclado normal
            if (isPrincipal()) { main.removeCallbacks(restoreIme); main.postDelayed(restoreIme, 6000); }
            // el extendido no aparece por un minuto: se ofrece volver al teléfono normal
            Torch.other = false;
            if (isPrincipal() && !lostOffered && !lostPending) { lostPending = true; main.postDelayed(lostCheck, 10_000); }
        }
        for (Link.Listener l : listeners) l.onState(st, inf);
    }

    @Override public void onMessage(JSONObject m) {
        String t = m.optString("t");
        if ("media".equals(t)) { media(m.optString("k")); return; }
        if ("installApp".equals(t)) { if (!isPrincipal()) main.post(() -> AppShare.install(app, this, m)); return; }
        // centro de apagado: el otro teléfono pide abrir aquí el menú de apagado o bloquear
        if ("power".equals(t)) {
            String a = m.optString("a");
            boolean ok = Power.run(app, a);
            String why = Power.why(app);
            send("powerR", "a", a, "ok", ok, "why", why == null ? "" : why);
            return;
        }
        // widget de llamadas: todo pasa en el principal
        if ("callLogReq".equals(t)) { if (isPrincipal()) RecentApps.worker.execute(() -> send(Phone.callLog(app))); return; }
        if ("contactsReq".equals(t)) { if (isPrincipal()) { String q = m.optString("q"); RecentApps.worker.execute(() -> send(Phone.contacts(app, q))); } return; }
        if ("dial".equals(t)) { if (isPrincipal()) Phone.dial(app, this, m.optString("num")); return; }
        if ("callPerm".equals(t)) {
            if (isPrincipal()) startOrNotify(new Intent(app, MainActivity.class).putExtra("callPerm", true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP), 88, "Llamadas", "Toca para permitir llamadas y contactos");
            return;
        }
        // corrector del propio principal para la palabra que se escribe en el extendido
        if ("spell".equals(t)) {
            if (isPrincipal()) {
                final int id = m.optInt("id");
                final String w = m.optString("w");
                Speller.check(app, w, list -> send("spellR", "id", id, "w", w, "s", new JSONArray(list)));
            }
            return;
        }
        if ("userDict".equals(t) && !isPrincipal()) { Dict d = Dict.get(app); d.attach(app); d.setUser(m.optJSONArray("w")); }
        if ("openSettings".equals(t)) {   // Ajustes de Android de este teléfono, pedidos desde el otro
            Intent si = new Intent(m.optString("a", Settings.ACTION_SETTINGS));
            if (si.resolveActivity(app.getPackageManager()) == null) si = new Intent(Settings.ACTION_SETTINGS);
            startExternal(si, "com.android.settings", "Ajustes", true);
            return;
        }
        if ("screen".equals(t)) { if (!isPrincipal()) ScreenSync.apply(app, this, m.optString("s")); return; }
        if ("scrSync".equals(t)) { prefs.edit().putBoolean("syncScreen", m.optBoolean("on", true)).apply(); }
        // lista de notificaciones (desde la banda): borrar o abrir, siempre en el principal
        if ("notifDel".equals(t)) { if (isPrincipal()) notifDel(m.optString("key")); return; }
        if ("notifOpen".equals(t)) { if (isPrincipal()) notifOpen(m.optString("key"), m.optString("pkg")); return; }
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
        if ("boost".equals(t)) {
            if (!isPrincipal()) Boost.listen(app, this, m.optBoolean("on"));
            else Boost.syncVolume(app, this, m.optBoolean("on"));
            return;
        }
        if ("boostSet".equals(t)) { if (isPrincipal()) Boost.setEnabled(app, this, m.optBoolean("on")); return; }
        if ("boostVol".equals(t)) { Boost.applyVolume(app, m.optDouble("f")); return; }
        if ("boostApp".equals(t)) { if (isPrincipal()) boostApp(m.optBoolean("on")); return; }
        // volumen del principal desde el extendido (barra deslizante del control de video)
        if (("setVol".equals(t) || "volAsk".equals(t)) && isPrincipal()) {
            AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                if ("setVol".equals(t)) am.setStreamVolume(AudioManager.STREAM_MUSIC, (int) Math.round(m.optDouble("f") * max), AudioManager.FLAG_SHOW_UI);
                send("volNow", "f", am.getStreamVolume(AudioManager.STREAM_MUSIC) / (double) Math.max(1, max));
            }
            return;
        }
        if ("call".equals(t)) { if (!isPrincipal()) CallHub.onCall(app, this, m); }
        if ("callCmd".equals(t)) { if (isPrincipal()) CallHub.command(app, this, m); return; }
        if ("game".equals(t)) {
            if (!isPrincipal()) {
                GameBg.handle(app, this, m);
                // Game Pal: control en pantalla para los juegos que lo tienen (por ahora, Brawl Stars)
                String gp = m.optString("pkg");
                if (GamePalActivity.supported(gp) && GamePalActivity.enabled(app) && !GamePalActivity.open)
                    main.postDelayed(() -> startOwn(GamePalActivity.intent(app, gp), () -> GamePalActivity.open, 99,
                            "Game Pal", "Toca para abrir el control de Brawl Stars"), 600);
            }
            return;
        }
        // tapa: el otro teléfono notó que se cerró (o abrió) la carcasa
        if ("lid".equals(t)) {
            String ls = m.optString("s");
            main.post(() -> { if ("close".equals(ls)) ScreenSync.lockNow(app, this); else ScreenSync.wakeNow(app); });
            return;
        }
        if ("lockFlip".equals(t)) { prefs.edit().putBoolean("lockFlip", m.optBoolean("on")).apply(); main.post(() -> LockArt.apply(app, this)); return; }
        if ("lockArt".equals(t)) {
            prefs.edit().putBoolean("lockArt", m.optBoolean("on")).putInt("lockMethod", m.optInt("m")).apply();
            main.post(() -> LockArt.apply(app, this));
            return;
        }
        if ("lidPref".equals(t)) { boolean on = m.optBoolean("on", true); main.post(() -> LidWatch.setEnabled(app, this, on)); return; }
        // prueba de sensores (para detectar la tapa cerrada): el otro teléfono pide los valores de este
        if ("senOn".equals(t)) {
            main.post(() -> {
                if (senProbe == null) senProbe = new SensorProbe(app, s -> { if (connected()) send("sen", "s", s); });
                main.removeCallbacks(senStop);
                main.postDelayed(senStop, 10 * 60_000);   // por si nunca llega el senOff
            });
            return;
        }
        if ("senOff".equals(t)) { main.post(senStop); return; }
        if ("senOpen".equals(t)) {
            main.post(() -> startOwn(SensorTestActivity.intent(app), () -> SensorTestActivity.open, 98,
                    "Prueba de sensores", "Toca para abrir la prueba de sensores"));
            return;
        }
        // revisor de permisos de Game Pal: el otro teléfono pregunta el estado de este, o pide abrir un ajuste aquí
        if ("pcAsk".equals(t)) { send("pcRes", "s", PermCheck.status(app, this)); return; }
        if ("pcFix".equals(t)) {
            Intent fi = PermCheck.fix(app, m.optString("w"));
            main.post(() -> startExternal(fi, PermCheck.pkgOf(fi), "Ajustes", true));
            return;
        }
        if ("gpPref".equals(t)) { prefs.edit().putBoolean("gamePal", m.optBoolean("on", true)).apply(); return; }
        if ("gp".equals(t) || "gpShot".equals(t)) {
            if (isPrincipal() && MesaAccess.instance != null) main.post(() -> {
                if (MesaAccess.instance == null) return;
                if ("gp".equals(t)) GamePalTouch.onMessage(MesaAccess.instance, m); else GamePalTouch.shot(MesaAccess.instance, this);
            });
            else if (isPrincipal() && "gpShot".equals(t)) send("gpImg", "err", "Activa la accesibilidad de ChronOS en el principal (versión completa)");
            return;
        }
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
                    "Apps recientes", "Toca y activa ChronOS en \"Acceso a datos de uso\"");
            return;
        }
        if ("update".equals(t)) { Updater.updateFromRemote(app, this); return; }
        if ("fmOpen".equals(t)) {
            startOrNotify(new Intent(app, FilesActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra("remote", true), 22, "Archivos", "Toca para abrir el gestor de archivos aquí");
            return;
        }
        if ("fmPath".equals(t)) FileServer.remoteDir = m.optString("path");
        if ("batt".equals(t)) {
            otherBatt = m.optInt("l", -1); otherChg = m.optBoolean("c");
            // el extendido se está quedando sin batería: se ofrece (una vez) volver al teléfono normal
            if (isPrincipal()) {
                if (otherBatt >= 0 && otherBatt <= 15 && !otherChg && !battOffered) {
                    battOffered = true;
                    PhoneMode.offer(app, this, "El extendido tiene poca batería (" + otherBatt + "%)");
                } else if (otherChg || otherBatt >= 25) battOffered = false;
            }
        }
        // brillo pedido desde el otro teléfono (widget de brillo)
        if ("brSet".equals(t)) {
            boolean ok = Bright.set(app, (float) m.optDouble("f"));
            if (!ok && m.optBoolean("done") && !brPermAsked) {
                brPermAsked = true;
                startOrNotify(Bright.permIntent(app), 87, "Brillo desde el otro teléfono", "Toca y activa \"Modificar ajustes del sistema\" para ChronOS");
            }
            if (m.optBoolean("done")) send("brNow", "f", (double) Bright.mine(app), "ok", ok);
            return;
        }
        if ("brAsk".equals(t)) { send("brNow", "f", (double) Bright.mine(app), "ok", Bright.canWrite(app)); return; }
        if ("torch".equals(t) || "torchState".equals(t)) { Torch.onMessage(app, this, m); return; }
        if ("notifTest".equals(t)) { if (isPrincipal()) NotifTest.run(app, this); return; }
        // el extendido pide activar las notificaciones: si ChronOS no está a la vista en el principal, un aviso para abrirlo
        if ("notifSetup".equals(t) && isPrincipal() && !foreground && !NotifService.enabled(app)) {
            startOrNotify(new Intent(app, MainActivity.class).putExtra("notifSetup", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    83, "Notificaciones en la barra", "Toca para activarlas en este teléfono");
            return;
        }
        // pantalla de inicio pedida desde el otro teléfono: se abre ChronOS aquí y aparece la confirmación de Android
        if ("homeSet".equals(t)) {
            startOrNotify(new Intent(app, MainActivity.class).putExtra("homeSet", m.optBoolean("on"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP), 84,
                    "Pantalla de inicio", "Toca para " + (m.optBoolean("on") ? "poner" : "quitar") + " ChronOS como inicio en este teléfono");
            return;
        }
        if ("homeState".equals(t) || "homeAsk".equals(t)) { for (Link.Listener l : listeners) l.onMessage(m); return; }
        if ("phoneOffer".equals(t) && isPrincipal()) { lostOffered = false; PhoneMode.offer(app, this, m.optString("title", "Modo teléfono normal")); return; }
        // fotos, música y video: se ven en el principal; los controles, en el extendido
        if ("mPlay".equals(t)) { if (isPrincipal()) MediaHub.open(app, m.optString("kind"), MediaHub.paths(m), m.optInt("idx")); return; }
        if ("mCmd".equals(t)) { if (isPrincipal()) MediaHub.command(app, m); return; }
        // un video empezó en el principal: el control de video se abre UNA vez (si se cierra, no vuelve hasta otro video)
        // (si ChronOS está a la vista, los controles salen en su action bar)
        if ("mActive".equals(t) && !isPrincipal() && m.optBoolean("video") && m.optBoolean("fresh")
                && !KeyboardActivity.showingMedia && !foreground)
            MediaHub.openControls(app, MediaHub.VIDEO);
        if ("mOpen".equals(t)) {
            // la música se controla desde la action bar cuando ChronOS está a la vista
            String k = m.optString("kind");
            if (!isPrincipal() && !((MediaHub.AUDIO.equals(k) || MediaHub.VIDEO.equals(k)) && foreground)) MediaHub.openControls(app, k);
            return;
        }
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
        if ("appsReq".equals(t)) { AppsDialog.sendList(app, this, m.optString("sig")); return; }
        if (t.startsWith("fs") && !t.endsWith("R") && !"fsTok".equals(t)) { FileServer.get(app).handle(m, this); return; }
        if ("fsTok".equals(t)) FileServer.get(app).setRemote(m.optString("k"), m.optInt("port"));
        if ("kbAsk".equals(t)) { sendImeState(); if (!imeSelected(app)) notifySwitch(); return; }
        // mesa de dibujo (Sketchbook)
        if ("skStroke".equals(t) || "skTap".equals(t) || "skShot".equals(t) || "skEnd".equals(t)) {
            if (!isPrincipal()) return;
            if (MesaAccess.instance != null) { final JSONObject fm = m; main.post(() -> { if (MesaAccess.instance != null) Sketch.handle(MesaAccess.instance, this, fm); }); }
            else if (!"skEnd".equals(t)) send("skImg", "err", "Activa la accesibilidad de ChronOS en el principal (versión completa) para dibujar desde aquí");
            return;
        }
        if ("dwOpen".equals(t)) {   // DUAL-Draw se abrió en el otro teléfono: aquí la otra mitad
            main.post(() -> startOwn(DualDrawActivity.intent(app, isPrincipal() ? "view" : "edit"), () -> DualDrawActivity.open, 98,
                    "DUAL-Draw", "Toca para abrir la otra mitad de DUAL-Draw"));
            return;
        }
        if ("sketchOn".equals(t)) {
            if (!isPrincipal() && !SketchActivity.dismissed && !SketchActivity.open)
                main.post(() -> startOwn(new Intent(app, SketchActivity.class), () -> SketchActivity.open, 97, "Mesa de dibujo", "Toca para dibujar en Sketchbook desde aquí"));
            return;
        }
        if ("sketchOff".equals(t)) {
            SketchActivity.dismissed = false;
            for (Link.Listener l : listeners) l.onMessage(m);
            return;
        }
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
     * Abre una app en ESTE teléfono. Si ChronOS no está a la vista, Android no deja abrir apps
     * desde segundo plano (salvo con el permiso "mostrar sobre otras apps"); en ese caso deja un aviso
     * que se abre con un toque.
     */
    /**
     * "Desktop" del widget de apps recientes: la MESA de ChronOS en este teléfono (no la pantalla de inicio).
     * Igual que con las apps: inicio sin animación y enseguida la mesa; se reintenta hasta que la mesa esté al frente.
     */
    public void goDesk() {
        Intent desk = new Intent(app, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        startOwn(desk, () -> foreground, 46, "Volver a la mesa", "Toca para ver la mesa de ChronOS");
    }

    /**
     * Abre una pantalla de ChronOS aunque otra app esté al frente: inicio sin animación y enseguida la pantalla,
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
        if (Pals.isPal(pkg)) {   // un PAL: se abre en el principal como una app más
            if (Pals.get(pkg.substring(4)) == null) return NOT_INSTALLED;
            return startExternal(Pals.intent(app, pkg.substring(4)), app.getPackageName(), label);
        }
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
            if ("back".equals(k)) key(KeyEvent.KEYCODE_BACK);          // llega a la app por el teclado ChronOS
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

    /** Entrega una tecla al teclado ChronOS de este teléfono, como si llegara del extendido. */
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
            main.removeCallbacks(this);
            if (!isPrincipal() || !connected()) { main.postDelayed(this, 4000); return; }   // sigue revisando, por si se conecta
            RecentApps.worker.execute(() -> GameBg.poll(app, Hub.this));   // ¿empezó un juego? → fondo en el extendido
            RecentApps.worker.execute(Hub.this::pollNotifs);                // apps con notificaciones nuevas → punto rojo
            RecentApps.worker.execute(() -> {                                 // ¿hay una app de video al frente? (YouTube, Netflix…)
                if (RecentApps.hasAccess(app)) videoApp = isVideoApp(app, RecentApps.active(app));
            });
            // audífonos o Bluetooth conectados/desconectados: la música y el video cambian de modo (Boost no aplica)
            boolean hp = Boost.headphones(app);
            if (hp != lastHeadphones) {
                lastHeadphones = hp;
                if (Boost.enabled(app)) {
                    MediaService.boostChanged(app);
                    VideoActivity.boostChanged(app);
                    send("boostMsg", "msg", hp ? "Boost en pausa: el principal está con audífonos o Bluetooth" : "Boost activo otra vez");
                }
            }
            AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
            boolean on = am != null && am.isMusicActive() && MediaHub.current == null;   // los visores propios ya tienen sus controles
            boolean appNow = videoApp && MediaHub.current == null;
            boolean video = (on && moviePlaying(am)) || appNow;
            long now = SystemClock.uptimeMillis();
            boolean fresh = false;
            if (appNow && !videoAppWas) fresh = true;   // se abrió una app de video: los controles salen ya
            videoAppWas = appNow;
            if (video) {
                lastVideoAt = now;
                if (!videoSession) { videoSession = true; fresh = true; }
            } else if (videoSession && now - lastVideoAt > 60000) videoSession = false;
            if (on != musicActive || video != videoActive || fresh) {
                musicActive = on;
                videoActive = video;
                send("mActive", "on", on, "video", video, "fresh", fresh, "app", appNow);
            }
            main.postDelayed(this, 1500);
        }
    };

    /** Boost para apps: en el principal se pide el permiso de captura (con un toque allá) o se detiene. */
    void boostApp(boolean on) {
        if (!on) { CaptureService.stop(app); return; }
        if (CaptureService.running) { send("boost", "on", true); return; }
        int r = startOrNotify(new Intent(app, CaptureActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), 62,
                "Boost: toca aquí", "Para que el sonido salga también por el extendido");
        send("boostMsg", "msg", r == LAUNCHED ? "Acepta en el principal la captura de sonido"
                : "Toca en el principal el aviso \"Boost: toca aquí\" y acepta la captura de sonido");
    }

    private volatile boolean videoApp;
    private boolean lastHeadphones;
    private boolean videoAppWas;

    // apps de video conocidas (además de las que Android marca como "video")
    private static final String[] VIDEO_APPS = {"com.google.android.youtube", "com.google.android.apps.youtube.kids", "app.revanced.android.youtube",
            "com.netflix.mediaclient", "com.amazon.avod", "com.disney.disneyplus", "com.hbo.hbonow", "com.wbd.stream", "com.hbo.max",
            "tv.twitch.android.app", "org.videolan.vlc", "com.mxtech.videoplayer", "com.google.android.videos", "com.plexapp.android",
            "com.crunchyroll.crunchyroid", "com.star.plus", "com.paramount.android", "com.vimeo.android", "com.dailymotion.dailymotion",
            "com.mubi", "com.pluto.tv", "tv.pluto.android", "com.viki.android", "com.iqiyi.i18n", "com.kick.mobile"};

    static boolean isVideoApp(Context c, String pkg) {
        if (pkg == null || pkg.isEmpty() || pkg.startsWith("@")) return false;
        if (pkg.contains("youtube.music")) return false;
        for (String v : VIDEO_APPS) if (pkg.startsWith(v)) return true;
        try { return c.getPackageManager().getApplicationInfo(pkg, 0).category == android.content.pm.ApplicationInfo.CATEGORY_VIDEO; }
        catch (Exception e) { return false; }
    }

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

    /** Cambió algo en las notificaciones: se revisa ya (punto rojo de las apps). */
    void notifsChanged() {
        if (!isPrincipal()) return;
        notifSentAt = 0; notifSig = "?";
        RecentApps.worker.execute(this::pollNotifs);
        RecentApps.worker.execute(this::sendTicker);
    }

    /** Al conectarse el extendido, que reciba la banda aunque nada haya cambiado. */
    void resendTicker() { tickerSig = ""; notifsChanged(); }

    private void pollNotifs() {
        java.util.List<String> l = new java.util.ArrayList<>();
        NotifService ns = NotifService.instance;
        if (ns != null) {   // con acceso a notificaciones: las que siguen pendientes
            JSONArray a = ns.pending();
            for (int i = 0; i < a.length(); i++) l.add(a.optString(i));
        } else if (RecentApps.hasAccess(app)) l = RecentApps.unseen(app);
        else return;
        String sig = android.text.TextUtils.join(",", l);
        long now = SystemClock.uptimeMillis();
        if (sig.equals(notifSig) && now - notifSentAt < 30000) return;
        notifSig = sig;
        notifSentAt = now;
        JSONObject m = new JSONObject();
        try { m.put("t", "notifs"); m.put("p", new JSONArray(l)); } catch (JSONException ignored) { }
        if (connected()) send(m);
        main.post(() -> local(m));
    }

    void notifDel(String key) {
        NotifService ns = NotifService.instance;
        if (ns != null) ns.dismiss(key);
        main.postDelayed(this::notifsChanged, 300);
    }

    void notifOpen(String key, String pkg) {
        NotifService ns = NotifService.instance;
        if (ns != null && ns.open(key)) return;
        launchHere(pkg, "");
    }

    private String tickerSig = "";

    /** Banda tipo noticiero: las pendientes del principal, a la barra del reloj (o arriba del extendido si el principal está en otra app). */
    private void sendTicker() {
        NotifService ns = NotifService.instance;
        JSONArray items = ns != null ? ns.items() : new JSONArray();
        StringBuilder sb = new StringBuilder().append(foreground).append('|');
        for (int i = 0; i < items.length(); i++) sb.append(items.optJSONObject(i).optString("key")).append(items.optJSONObject(i).optString("text").hashCode()).append(',');
        String sig = sb.toString();
        if (sig.equals(tickerSig)) return;
        tickerSig = sig;
        JSONObject m = new JSONObject();
        try { m.put("t", "ticker"); m.put("items", items); m.put("here", foreground); } catch (JSONException ignored) { }
        if (connected()) send(m);
        main.post(() -> local(m));
    }

    private void mediaKey(AudioManager am, int code) {
        long t = SystemClock.uptimeMillis();
        am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0));
        am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0));
    }
}
