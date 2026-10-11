package cl.mesadoble;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Conexión directa entre los dos teléfonos por la red WiFi local.
 * El teléfono de la izquierda espera conexiones y se anuncia en la red;
 * el de la derecha lo busca y se conecta. Mensajes: una línea JSON por mensaje.
 */
public class Link {
    public interface Listener {
        void onMessage(JSONObject msg);
        void onState(int state, String info);
    }

    public static final int IDLE = 0, WAITING = 1, CONNECTED = 2;
    public static final int PORT = 47820;
    static final String TYPE = "_mesadoble._tcp.";

    private static class Conn {
        final Socket s; final BufferedWriter w;
        Conn(Socket s) throws IOException {
            this.s = s;
            this.w = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
        }
        void close() { try { s.close(); } catch (IOException ignored) { } }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService writer = Executors.newSingleThreadExecutor();
    private final NsdManager nsd;
    private final WifiManager.MulticastLock lock;
    private final Listener listener;

    private volatile boolean running, host, resolving;
    private volatile Conn conn;
    private ServerSocket server;
    private NsdManager.RegistrationListener regL;
    private NsdManager.DiscoveryListener discL;

    public Link(Context ctx, Listener l) {
        listener = l;
        nsd = (NsdManager) ctx.getSystemService(Context.NSD_SERVICE);
        WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        lock = wm != null ? wm.createMulticastLock("mesadoble") : null;
        if (lock != null) lock.setReferenceCounted(false);
    }

    public boolean isConnected() { return conn != null; }
    public boolean isHost() { return host; }
    /** IP del otro teléfono (para el canal de archivos). */
    public String peerIp() {
        Conn c = conn;
        try { return c != null ? c.s.getInetAddress().getHostAddress() : null; } catch (Exception e) { return null; }
    }

    public void start(boolean asHost) {
        stop();
        running = true;
        host = asHost;
        if (lock != null) lock.acquire();
        state(WAITING, null);
        if (asHost) startServer(); else startDiscovery();
    }

    public void stop() {
        running = false;
        if (regL != null) { try { nsd.unregisterService(regL); } catch (Exception ignored) { } regL = null; }
        stopDiscovery();
        if (server != null) { try { server.close(); } catch (IOException ignored) { } server = null; }
        Conn c = conn; conn = null;
        if (c != null) c.close();
        if (lock != null && lock.isHeld()) lock.release();
        state(IDLE, null);
    }

    public void send(JSONObject msg) {
        final String line = msg.toString();
        writer.execute(() -> {
            Conn c = conn;
            if (c == null) return;
            try {
                c.w.write(line);
                c.w.write('\n');
                c.w.flush();
            } catch (IOException e) {
                drop(c);
            }
        });
    }

    /** Conexión manual por IP (por si la búsqueda automática no funciona en esa red). */
    public void connectTo(final String ip) {
        if (!running) return;
        new Thread(() -> {
            try {
                Socket s = new Socket();
                s.connect(new InetSocketAddress(ip, PORT), 4000);
                attach(s);
            } catch (IOException e) {
                state(WAITING, "No pude conectar con " + ip);
            }
        }, "connect").start();
    }

    // ---------- servidor (teléfono izquierdo) ----------

    private void startServer() {
        new Thread(() -> {
            try {
                ServerSocket ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(PORT));
                server = ss;
                main.post(this::register);
                while (running) {
                    Socket s = ss.accept();
                    attach(s);
                }
            } catch (IOException e) {
                if (running) state(WAITING, "No pude abrir la conexión. Reinicia la app.");
            }
        }, "server").start();
    }

    private void register() {
        if (!running || !host) return;
        NsdServiceInfo info = new NsdServiceInfo();
        info.setServiceName("MesaDoble " + Build.MODEL);
        info.setServiceType(TYPE);
        info.setPort(PORT);
        regL = new NsdManager.RegistrationListener() {
            @Override public void onRegistrationFailed(NsdServiceInfo s, int e) { }
            @Override public void onUnregistrationFailed(NsdServiceInfo s, int e) { }
            @Override public void onServiceRegistered(NsdServiceInfo s) { }
            @Override public void onServiceUnregistered(NsdServiceInfo s) { }
        };
        try { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, regL); } catch (Exception ignored) { }
    }

    // ---------- cliente (teléfono derecho) ----------

    private void startDiscovery() {
        if (!running || host || discL != null) return;
        discL = new NsdManager.DiscoveryListener() {
            @Override public void onStartDiscoveryFailed(String t, int e) { discL = null; retryDiscovery(); }
            @Override public void onStopDiscoveryFailed(String t, int e) { }
            @Override public void onDiscoveryStarted(String t) { }
            @Override public void onDiscoveryStopped(String t) { }
            @Override public void onServiceLost(NsdServiceInfo s) { }
            @Override public void onServiceFound(NsdServiceInfo s) {
                if (conn != null || resolving) return;
                if (s.getServiceType() == null || !s.getServiceType().contains("mesadoble")) return;
                resolving = true;
                try {
                    nsd.resolveService(s, new NsdManager.ResolveListener() {
                        @Override public void onResolveFailed(NsdServiceInfo i, int e) { resolving = false; }
                        @Override public void onServiceResolved(NsdServiceInfo i) {
                            resolving = false;
                            InetAddress a = i.getHost();
                            if (a != null && conn == null) connectTo(a.getHostAddress());
                        }
                    });
                } catch (Exception e) { resolving = false; }
            }
        };
        try { nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, discL); }
        catch (Exception e) { discL = null; retryDiscovery(); }
    }

    private void stopDiscovery() {
        if (discL != null) { try { nsd.stopServiceDiscovery(discL); } catch (Exception ignored) { } discL = null; }
        resolving = false;
    }

    private void retryDiscovery() {
        main.postDelayed(() -> {
            if (running && !host && conn == null) { stopDiscovery(); startDiscovery(); }
        }, 2500);
    }

    // ---------- conexión ----------

    private void attach(Socket s) throws IOException {
        s.setTcpNoDelay(true);
        final Conn c = new Conn(s);
        Conn old = conn;
        conn = c;
        if (old != null) old.close();
        state(CONNECTED, null);
        new Thread(() -> {
            try {
                BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                String line;
                while ((line = in.readLine()) != null) {
                    try {
                        final JSONObject o = new JSONObject(line);
                        main.post(() -> listener.onMessage(o));
                    } catch (Exception ignored) { }
                }
            } catch (IOException ignored) { }
            drop(c);
        }, "reader").start();
    }

    private void drop(Conn c) {
        if (conn != c) return;
        conn = null;
        c.close();
        if (running) {
            state(WAITING, null);
            if (!host) main.post(() -> { stopDiscovery(); retryDiscovery(); });
        }
    }

    private void state(final int st, final String info) {
        main.post(() -> listener.onState(st, info));
    }

    public static String localIp() {
        String fallback = null;
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                String name = ni.getName() == null ? "" : ni.getName();
                boolean wifi = name.startsWith("wlan") || name.startsWith("swlan") || name.startsWith("ap");
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || a.isLoopbackAddress()) continue;
                    if (wifi) return a.getHostAddress();
                    if (fallback == null) fallback = a.getHostAddress();
                }
            }
        } catch (Exception ignored) { }
        return fallback != null ? fallback : "sin WiFi";
    }
}
