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
        // pantalla apagada / encendida: se avisa enseguida al otro teléfono con un latido
        android.content.IntentFilter f = new android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_OFF);
        f.addAction(android.content.Intent.ACTION_SCREEN_ON);
        ctx.getApplicationContext().registerReceiver(new android.content.BroadcastReceiver() {
            @Override public void onReceive(Context c, android.content.Intent i) {
                asleep = android.content.Intent.ACTION_SCREEN_OFF.equals(i.getAction());
                beat();
            }
        }, f);
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
        if (asHost) { startServer(); main.postDelayed(autoCall, 3000); }
        else { startDiscovery(); startCallListener(); }
    }

    // ---------- llamada: el extendido le pide al principal que se conecte ----------
    // El extendido espera la conexión; si el principal no lo encuentra solo, el extendido "llama": manda un aviso
    // por el WiFi (a todos o a una IP) y el principal, al recibirlo, se conecta a quien llamó.

    public static final int CALL_PORT = 47821;
    private static final String CALL = "MESADOBLE-CALL";
    private volatile java.net.DatagramSocket callSock;
    private volatile boolean connecting;

    /** Mientras espera, el extendido llama solo cada 8 s (por si la búsqueda del WiFi falla). */
    private final Runnable autoCall = new Runnable() {
        @Override public void run() {
            if (!running || !host) return;
            if (conn == null) call(null);
            main.postDelayed(this, 8000);
        }
    };

    /** Extendido: llama al principal (ip = null: a todo el WiFi). */
    public void call(final String ip) {
        if (!host) return;
        new Thread(() -> {
            try (java.net.DatagramSocket ds = new java.net.DatagramSocket()) {
                ds.setBroadcast(true);
                byte[] b = CALL.getBytes(StandardCharsets.UTF_8);
                java.util.List<InetAddress> to = new java.util.ArrayList<>();
                if (ip != null && !ip.isEmpty()) to.add(InetAddress.getByName(ip));
                else {
                    to.add(InetAddress.getByName("255.255.255.255"));
                    for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                        if (!ni.isUp() || ni.isLoopback()) continue;
                        for (java.net.InterfaceAddress ia : ni.getInterfaceAddresses())
                            if (ia.getBroadcast() != null) to.add(ia.getBroadcast());
                    }
                }
                for (int k = 0; k < 4; k++) {
                    for (InetAddress a : to) {
                        try { ds.send(new java.net.DatagramPacket(b, b.length, a, CALL_PORT)); } catch (Exception ignored) { }
                    }
                    Thread.sleep(400);
                }
            } catch (Exception ignored) { }
        }, "llamar").start();
    }

    /**
     * Principal: busca al extendido a la fuerza. Primero en su última IP; si no, prueba todas las direcciones del WiFi
     * (192.168.x.1 a .254) a ver cuál responde en el puerto de ChronOS. Tarda unos segundos.
     */
    public void force(final String lastIp) {
        if (host || !running) return;
        stopDiscovery();
        startDiscovery();
        if (lastIp != null && !lastIp.isEmpty()) connectTo(lastIp);
        final String me = localIp();
        int dot = me.lastIndexOf('.');
        if (dot < 0 || !me.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) return;
        final String base = me.substring(0, dot + 1);
        java.util.concurrent.ExecutorService pool = Executors.newFixedThreadPool(32);
        for (int i = 1; i < 255; i++) {
            final String ip = base + i;
            if (ip.equals(me)) continue;
            pool.execute(() -> {
                if (conn != null || !running) return;
                Socket s = new Socket();
                try {
                    s.connect(new InetSocketAddress(ip, PORT), 350);
                    synchronized (Link.this) {
                        if (conn == null && running) { attach(s); return; }
                    }
                } catch (IOException ignored) { }
                try { s.close(); } catch (IOException ignored) { }
            });
        }
        pool.shutdown();
    }

    /** Principal: escucha las llamadas del extendido y se conecta a quien llamó. */
    private void startCallListener() {
        new Thread(() -> {
            try (java.net.DatagramSocket ds = new java.net.DatagramSocket(null)) {
                ds.setReuseAddress(true);
                ds.setBroadcast(true);
                ds.bind(new InetSocketAddress(CALL_PORT));
                callSock = ds;
                byte[] buf = new byte[64];
                while (running && !host) {
                    java.net.DatagramPacket p = new java.net.DatagramPacket(buf, buf.length);
                    ds.receive(p);
                    String msg = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                    if (CALL.equals(msg) && conn == null && !connecting) connectTo(p.getAddress().getHostAddress());
                }
            } catch (Exception ignored) {
            } finally { callSock = null; }
        }, "escuchar-llamadas").start();
    }

    public void stop() {
        running = false;
        main.removeCallbacks(autoCall);
        java.net.DatagramSocket cs = callSock;
        if (cs != null) cs.close();
        if (regL != null) { try { nsd.unregisterService(regL); } catch (Exception ignored) { } regL = null; }
        stopDiscovery();
        if (server != null) { try { server.close(); } catch (IOException ignored) { } server = null; }
        Conn c = conn; conn = null;
        if (c != null) c.close();
        if (lock != null && lock.isHeld()) lock.release();
        state(IDLE, null);
    }

    private volatile boolean asleep;

    /** Latido: con la pantalla apagada lleva "z" (duermo: no me des por perdido). */
    private void beat() {
        final String line = asleep ? "{\"t\":\"hb\",\"z\":1}" : "{\"t\":\"hb\"}";
        writer.execute(() -> {
            Conn c = conn;
            if (c == null) return;
            try { c.w.write(line); c.w.write('\n'); c.w.flush(); } catch (IOException e) { drop(c); }
        });
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
        if (!running || connecting) return;
        connecting = true;
        new Thread(() -> {
            try {
                Socket s = new Socket();
                s.connect(new InetSocketAddress(ip, PORT), 4000);
                attach(s);
            } catch (IOException e) {
                state(WAITING, "No pude conectar con " + ip);
            } finally { connecting = false; }
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
        try { s.setKeepAlive(true); } catch (Exception ignored) { }
        state(CONNECTED, null);
        // latido: cada 5 s un mensaje corto. Si el otro teléfono se apaga o pierde el WiFi de golpe, sin esto la
        // conexión parecería abierta para siempre; con él, a los 20 s sin noticias se da por perdida.
        // Con la pantalla apagada Android duerme el teléfono y los latidos se detienen: por eso cada teléfono avisa
        // "me voy a dormir" (z) y el otro deja de contar el silencio hasta que vuelva a latir normal.
        main.postDelayed(new Runnable() {
            @Override public void run() {
                if (conn != c) return;
                beat();
                main.postDelayed(this, 5000);
            }
        }, 1000);
        new Thread(() -> {
            try {
                BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                String line;
                boolean beats = false;
                while ((line = in.readLine()) != null) {
                    if (line.startsWith("{\"t\":\"hb\"")) {
                        // el otro teléfono late: 20 s de silencio = conexión perdida; si avisó que duerme, sin límite
                        beats = true;
                        boolean napping = line.contains("\"z\"");
                        try { s.setSoTimeout(napping ? 0 : 20000); } catch (Exception ignored) { }
                        continue;
                    }
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
