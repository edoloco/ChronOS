package cl.mesadoble;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.NetworkSpecifier;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.net.Inet4Address;

/**
 * Red temporal para lo pesado (método de unión "Bluetooth"): el PRINCIPAL crea una zona Wi-Fi local, sin internet
 * (Android le pone nombre y clave), le pasa los datos al extendido por Bluetooth y el extendido se une solo.
 * Cuando ya no se usa se apaga según "netEnd": 0 a los 2 min sin uso, 1 al apagar la pantalla, 2 preguntando.
 *   netReq → (principal) crear · netCfg {ssid, pass, sec} → (extendido) unirse · netIp {ip} → (principal) conectar
 *   netDown → (extendido) soltarla · netErr {msg} · netIdleAsk / netStop / netKeep
 */
final class TempNet {

    private TempNet() { }

    static final long IDLE_MS = 120000;
    static volatile long lastUse;
    static void touch() { lastUse = System.currentTimeMillis(); }

    private static final Handler h = new Handler(Looper.getMainLooper());

    // ---------------- principal ----------------

    private static WifiManager.LocalOnlyHotspotReservation res;
    private static String ssid, pass;
    private static int sec = 1;
    private static boolean starting, asked;
    private static BroadcastReceiver screenOff;

    static boolean up() { return res != null; }

    static void start(Context c, Hub hub) {
        Context app = c.getApplicationContext();
        if (res != null && ssid != null) { hub.send("netCfg", "ssid", ssid, "pass", pass, "sec", sec); return; }
        if (starting) return;
        if (Build.VERSION.SDK_INT < 33 && !locationOn(app)) {
            err(hub, "Enciende la Ubicación en el principal: Android la pide para crear la red temporal");
            return;
        }
        WifiManager wm = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
        if (wm == null) { err(hub, "El principal no tiene Wi-Fi"); return; }
        starting = true;
        touch();
        try {
            wm.startLocalOnlyHotspot(new WifiManager.LocalOnlyHotspotCallback() {
                @Override public void onStarted(WifiManager.LocalOnlyHotspotReservation r) {
                    starting = false;
                    res = r;
                    readCreds(r);
                    if (ssid == null) { err(hub, "El principal creó la red pero no dio su clave"); stop(app, hub); return; }
                    asked = false;
                    touch();
                    hub.send("netCfg", "ssid", ssid, "pass", pass, "sec", sec);
                    watchScreen(app, hub);
                    h.removeCallbacks(idle);
                    idleHub = hub; idleCtx = app;
                    h.postDelayed(idle, 30000);
                }
                @Override public void onStopped() { starting = false; res = null; }
                @Override public void onFailed(int reason) {
                    starting = false;
                    err(hub, reason == 3 ? "El principal tiene la zona Wi-Fi encendida: apágala (o úsala en lugar de la red temporal)"
                            : reason == 4 ? "El principal no permite crear redes ahora"
                            : "El principal no pudo crear la red temporal (" + reason + ")");
                }
            }, h);
        } catch (SecurityException e) {
            starting = false;
            err(hub, "Falta el permiso de Ubicación en el principal (Ajustes → Apps → ChronOS → Permisos)");
        } catch (Exception e) {
            starting = false;
            err(hub, "El principal no pudo crear la red temporal");
        }
    }

    @SuppressWarnings("deprecation")
    private static void readCreds(WifiManager.LocalOnlyHotspotReservation r) {
        ssid = null; pass = null; sec = 1;
        try {   // Android 11+
            Object conf = r.getClass().getMethod("getSoftApConfiguration").invoke(r);
            Object s = conf.getClass().getMethod("getSsid").invoke(conf);
            Object p = conf.getClass().getMethod("getPassphrase").invoke(conf);
            sec = (int) conf.getClass().getMethod("getSecurityType").invoke(conf);
            if (s != null) { ssid = unq(s.toString()); pass = p == null ? "" : p.toString(); return; }
        } catch (Exception ignored) { }
        try {
            WifiConfiguration wc = r.getWifiConfiguration();
            if (wc != null) { ssid = unq(wc.SSID); pass = wc.preSharedKey == null ? "" : unq(wc.preSharedKey); sec = 1; }
        } catch (Exception ignored) { }
    }

    private static String unq(String s) { return s != null && s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"") ? s.substring(1, s.length() - 1) : s; }

    static void stop(Context c, Hub hub) {
        h.removeCallbacks(idle);
        WifiManager.LocalOnlyHotspotReservation r = res;
        res = null; ssid = null;
        try { if (r != null) r.close(); } catch (Exception ignored) { }
        if (screenOff != null) { try { c.getApplicationContext().unregisterReceiver(screenOff); } catch (Exception ignored) { } screenOff = null; }
        if (hub.connected()) hub.send("netDown");
    }

    static void keep() { asked = false; touch(); }

    private static Hub idleHub;
    private static Context idleCtx;

    /** ¿Se está usando algo pesado? */
    private static boolean busy() {
        long now = System.currentTimeMillis();
        return CaptureService.running || DsMirror.running || now - lastUse < IDLE_MS || now - Link.bigAt < IDLE_MS;
    }

    private static final Runnable idle = new Runnable() {
        @Override public void run() {
            if (res == null || idleHub == null) return;
            int end = idleCtx.getSharedPreferences("mesa", Context.MODE_PRIVATE).getInt("netEnd", 0);
            if (CaptureService.running || DsMirror.running) touch();
            if (!busy()) {
                if (end == 0) { stop(idleCtx, idleHub); idleHub.toast("Red temporal apagada: vuelven a Bluetooth"); return; }
                if (end == 2 && !asked) { asked = true; idleHub.send("netIdleAsk"); }
            }
            h.postDelayed(this, 30000);
        }
    };

    private static void watchScreen(Context app, Hub hub) {
        if (screenOff != null) return;
        screenOff = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                if (res != null && app.getSharedPreferences("mesa", Context.MODE_PRIVATE).getInt("netEnd", 0) == 1) stop(app, hub);
            }
        };
        app.registerReceiver(screenOff, new IntentFilter(Intent.ACTION_SCREEN_OFF));
    }

    private static boolean locationOn(Context c) {
        try {
            LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
            return lm == null || lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        } catch (Exception e) { return true; }
    }

    private static void err(Hub hub, String msg) {
        hub.toast(msg);
        if (hub.connected()) hub.send("netErr", "msg", msg);
    }

    // ---------------- extendido ----------------

    private static ConnectivityManager.NetworkCallback cb;
    private static volatile Network joined;

    static void join(Context c, Hub hub, String ssid, String pass, int sec) {
        Context app = c.getApplicationContext();
        ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return;
        leave(app);
        if (Build.VERSION.SDK_INT < 29) { hub.send("netErr", "msg", "El extendido necesita Android 10 para unirse solo"); return; }
        try {
            Class<?> b = Class.forName("android.net.wifi.WifiNetworkSpecifier$Builder");
            Object bb = b.getConstructor().newInstance();
            b.getMethod("setSsid", String.class).invoke(bb, ssid);
            if (pass != null && !pass.isEmpty()) {
                if (sec == 3) b.getMethod("setWpa3Passphrase", String.class).invoke(bb, pass);   // solo WPA3
                else b.getMethod("setWpa2Passphrase", String.class).invoke(bb, pass);
            }
            NetworkSpecifier spec = (NetworkSpecifier) b.getMethod("build").invoke(bb);
            NetworkRequest req = new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).setNetworkSpecifier(spec).build();
            cb = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network n) {
                    joined = n;
                    cm.bindProcessToNetwork(n);   // lo de ChronOS (archivos, Boost) va por esta red
                    String ip = ipOf(cm, n);
                    if (ip != null) hub.send("netIp", "ip", ip);
                    else h.postDelayed(() -> { String i2 = ipOf(cm, n); if (i2 != null) hub.send("netIp", "ip", i2); }, 1500);
                }
                @Override public void onLost(Network n) { if (n.equals(joined)) { joined = null; cm.bindProcessToNetwork(null); } }
                @Override public void onUnavailable() {
                    hub.toast("El extendido no se unió a la red del principal");
                    hub.send("netFail");
                    cb = null;
                }
            };
            cm.requestNetwork(req, cb, 60000);
            hub.toast("Uniéndose a la red del principal… (si Android pregunta, toca «Conectar»)");
        } catch (Exception e) {
            hub.toast("El extendido no pudo unirse a la red del principal");
            hub.send("netFail");
        }
    }

    static void leave(Context c) {
        ConnectivityManager cm = (ConnectivityManager) c.getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return;
        if (cb != null) { try { cm.unregisterNetworkCallback(cb); } catch (Exception ignored) { } cb = null; }
        if (joined != null) { joined = null; try { cm.bindProcessToNetwork(null); } catch (Exception ignored) { } }
    }

    private static String ipOf(ConnectivityManager cm, Network n) {
        try {
            LinkProperties lp = cm.getLinkProperties(n);
            if (lp == null) return null;
            for (LinkAddress a : lp.getLinkAddresses()) if (a.getAddress() instanceof Inet4Address) return a.getAddress().getHostAddress();
        } catch (Exception ignored) { }
        return null;
    }
}
