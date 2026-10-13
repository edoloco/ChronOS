package cl.mesadoble;

import android.content.Context;
import android.graphics.Point;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.os.Bundle;
import android.view.Surface;
import android.view.WindowManager;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Modo APP: las apps que no giran (Instagram…) quedan de lado en el principal; su imagen se manda al extendido,
 * que la muestra derecha en una columna. Video H.264 por el puerto 47823.
 *   cuadro: [tipo 1=config 2=cuadro 3=cuadro clave][ancho][alto][largo][tiempo][datos]
 *   appCast (principal → extendido) {on, w, h}: se muestra / se esconde la columna
 *   appKey  (extendido → principal): pide un cuadro clave (al abrir la columna)
 *   appTouch (extendido → principal) {p:[x,y,…] de 0 a 1, ms}: un gesto (solo con la versión completa)
 */
final class ScreenCast {

    static final int PORT = 47823;
    private static final int CONFIG = 1, FRAME = 2, KEY = 3;

    private ScreenCast() { }

    // =============== principal: captura y envía ===============

    private static volatile MediaProjection proj;
    private static VirtualDisplay vd;
    private static volatile ServerSocket server;
    private static volatile Socket client;
    private static volatile DataOutputStream out;
    private static volatile boolean active;
    static volatile boolean keyReq;
    private static volatile Thread enc;
    private static byte[] csd;
    private static int cw, ch;
    private static Context app;
    private static final Object lock = new Object();

    static boolean running() { return proj != null; }
    static boolean active() { return active; }

    static void start(Context c, MediaProjection p) {
        app = c.getApplicationContext();
        proj = p;
        if (server == null) new Thread(ScreenCast::serve, "appcast-server").start();
    }

    static void stop() {
        proj = null;
        setActive(false);
        try { if (server != null) server.close(); } catch (Exception ignored) { }
        closeClient();
        Thread e = enc;
        enc = null;
        if (e != null) try { e.join(1500); } catch (Exception ignored) { }
        synchronized (lock) {
            if (vd != null) try { vd.release(); } catch (Exception ignored) { }
            vd = null;
            csd = null;
        }
    }

    private static void serve() {
        try (ServerSocket ss = new ServerSocket()) {
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(PORT));
            server = ss;
            while (proj != null) {
                Socket s = ss.accept();
                s.setTcpNoDelay(true);
                try { s.setSendBufferSize(1 << 19); } catch (Exception ignored) { }
                closeClient();
                synchronized (lock) {
                    client = s;
                    out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream(), 1 << 16));
                    if (csd != null) write(CONFIG, cw, ch, csd, csd.length, 0);
                }
                keyReq = true;
            }
        } catch (Exception ignored) {
        } finally { server = null; }
    }

    private static void closeClient() {
        synchronized (lock) {
            try { if (client != null) client.close(); } catch (Exception ignored) { }
            client = null;
            out = null;
        }
    }

    /** Llamar con el lock tomado. */
    private static void write(int type, int w, int h, byte[] d, int len, long pts) {
        DataOutputStream o = out;
        if (o == null) return;
        try {
            o.writeByte(type); o.writeInt(w); o.writeInt(h); o.writeInt(len); o.writeLong(pts);
            o.write(d, 0, len);
            o.flush();
        } catch (Exception e) {
            try { if (client != null) client.close(); } catch (Exception ignored) { }
            client = null;
            out = null;
        }
    }

    /** ¿Hay que mostrar la pantalla en el extendido? (una app quedó de lado). Hub lo revisa cada 1,5 s. */
    static void setActive(boolean on) {
        if (proj == null) on = false;
        if (on == active) return;
        active = on;
        if (on) {
            Thread t = new Thread(ScreenCast::encodeLoop, "appcast-enc");
            enc = t;
            t.start();
        } else if (app != null) Hub.get(app).send("appCast", "on", false);
    }

    static Point screen(Context c) {
        Point p = new Point();
        WindowManager wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
        if (wm != null) wm.getDefaultDisplay().getRealSize(p);
        return p;
    }

    private static int even16(float v) { return Math.max(16, Math.round(v / 16f) * 16); }

    private static void encodeLoop() {
        Thread me = Thread.currentThread();
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        byte[] buf = new byte[1 << 16];
        while (active && enc == me && proj != null) {
            Point sz = screen(app);
            float s = Math.min(1f, 1280f / Math.max(sz.x, sz.y));   // lado largo: 1280 (nítido y liviano para el WiFi)
            int w = even16(sz.x * s), h = even16(sz.y * s);
            int dpi = Math.max(120, Math.round(app.getResources().getDisplayMetrics().densityDpi * s));
            MediaCodec codec = null;
            Surface surf = null;
            try {
                MediaFormat f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h);
                f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
                f.setInteger(MediaFormat.KEY_BIT_RATE, 6_000_000);
                f.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
                f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
                f.setLong("repeat-previous-frame-after", 100_000);   // la imagen quieta se sigue mandando
                codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
                codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                surf = codec.createInputSurface();
                codec.start();
                synchronized (lock) {
                    MediaProjection p = proj;
                    if (p == null) break;
                    if (vd == null) vd = p.createVirtualDisplay("ChronOS Modo APP", w, h, dpi, 16 /* AUTO_MIRROR */, surf, null, null);
                    else { vd.resize(w, h, dpi); vd.setSurface(surf); }
                }
                Hub.get(app).send("appCast", "on", true, "w", w, "h", h);
                long lastCheck = System.currentTimeMillis();
                while (active && enc == me) {
                    if (keyReq) {
                        keyReq = false;
                        Bundle b = new Bundle();
                        b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
                        try { codec.setParameters(b); } catch (Exception ignored) { }
                    }
                    int i = codec.dequeueOutputBuffer(info, 100_000);
                    if (i >= 0) {
                        ByteBuffer bb = codec.getOutputBuffer(i);
                        if (bb != null && info.size > 0) {
                            if (buf.length < info.size) buf = new byte[info.size + 4096];
                            bb.position(info.offset);
                            bb.get(buf, 0, info.size);
                            boolean cfg = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                            boolean key = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                            synchronized (lock) {
                                if (cfg) { csd = Arrays.copyOf(buf, info.size); cw = w; ch = h; }
                                write(cfg ? CONFIG : key ? KEY : FRAME, w, h, buf, info.size, info.presentationTimeUs);
                            }
                        }
                        codec.releaseOutputBuffer(i, false);
                    }
                    long now = System.currentTimeMillis();
                    if (now - lastCheck > 400) {   // ¿giró la pantalla del principal? se vuelve a armar con el nuevo tamaño
                        lastCheck = now;
                        Point n = screen(app);
                        if (n.x != sz.x || n.y != sz.y) break;
                    }
                }
            } catch (Exception e) {
                try { Thread.sleep(500); } catch (Exception ignored) { }
            } finally {
                synchronized (lock) { if (vd != null) try { vd.setSurface(null); } catch (Exception ignored) { } }
                if (codec != null) { try { codec.stop(); } catch (Exception ignored) { } try { codec.release(); } catch (Exception ignored) { } }
                if (surf != null) surf.release();
            }
        }
    }

    // =============== extendido: recibe y muestra ===============

    private static volatile Thread rx;
    private static volatile Socket rxSock;
    /** Superficie donde se dibuja (la columna de la mesa o la del teclado, la que esté a la vista). */
    static volatile Surface surface;
    static volatile int showW, showH;
    static volatile boolean shown;

    static void setSurface(Surface s, Hub hub) {
        surface = s;
        if (s != null && hub.connected()) hub.send("appKey");
    }

    static void clearSurface(Surface s) { if (surface == s) surface = null; }

    /** El principal avisó si la columna va o no. */
    static void onCast(Hub hub, boolean on, int w, int h) {
        shown = on;
        if (on) { showW = w; showH = h; receive(hub); }
    }

    static void receive(Hub hub) {
        Thread cur = rx;
        if (cur != null && cur.isAlive()) return;
        String ip = hub.link.peerIp();
        if (ip == null) return;
        Thread t = new Thread(() -> rxLoop(hub, ip), "appcast-rx");
        rx = t;
        t.start();
    }

    static void stopReceive() {
        rx = null;
        shown = false;
        try { if (rxSock != null) rxSock.close(); } catch (Exception ignored) { }
    }

    private static void rxLoop(Hub hub, String ip) {
        Thread me = Thread.currentThread();
        MediaCodec dec = null;
        Surface decSurf = null;
        byte[] cfg = null, decCfg = null;
        int dw = 0, dh = 0;
        boolean waitKey = true;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        Socket s = null;
        try {
            for (int k = 0; ; k++) {
                s = new Socket();
                rxSock = s;
                try { s.connect(new InetSocketAddress(ip, PORT), 3000); break; }
                catch (Exception e) { try { s.close(); } catch (Exception ignored) { } if (k >= 5 || rx != me) throw e; Thread.sleep(500); }
            }
            s.setTcpNoDelay(true);
            DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream(), 1 << 17));
            byte[] data = new byte[1 << 16];
            hub.send("appKey");
            while (rx == me) {
                int type = in.readByte(), w = in.readInt(), h = in.readInt(), len = in.readInt();
                long pts = in.readLong();
                if (len > data.length) data = new byte[len + 4096];
                in.readFully(data, 0, len);
                if (type == CONFIG) {
                    cfg = Arrays.copyOf(data, len);
                    if (dec != null && !Arrays.equals(cfg, decCfg)) { dec = release(dec); }
                    continue;
                }
                Surface sf = surface;
                if (sf == null || !sf.isValid()) { if (dec != null) dec = release(dec); decSurf = null; waitKey = true; continue; }
                if (dec == null || sf != decSurf || w != dw || h != dh) {
                    if (dec != null) dec = release(dec);
                    if (cfg == null) continue;
                    try {
                        MediaFormat f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h);
                        f.setByteBuffer("csd-0", ByteBuffer.wrap(cfg));
                        f.setInteger("low-latency", 1);
                        dec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
                        dec.configure(f, sf, null, 0);
                        dec.start();
                        decSurf = sf; dw = w; dh = h; decCfg = cfg;
                        waitKey = true;
                    } catch (Exception e) { if (dec != null) dec = release(dec); continue; }
                }
                if (waitKey && type != KEY) { if (Math.random() < 0.1) hub.send("appKey"); continue; }
                waitKey = false;
                try {
                    int ii = dec.dequeueInputBuffer(30_000);
                    if (ii >= 0) {
                        ByteBuffer ib = dec.getInputBuffer(ii);
                        if (ib != null) { ib.clear(); ib.put(data, 0, len); dec.queueInputBuffer(ii, 0, len, pts, 0); }
                    }
                    int o;
                    while ((o = dec.dequeueOutputBuffer(info, 0)) != MediaCodec.INFO_TRY_AGAIN_LATER) {
                        if (o >= 0) dec.releaseOutputBuffer(o, true);
                    }
                } catch (Exception e) { dec = release(dec); waitKey = true; }
            }
        } catch (Exception ignored) {
        } finally {
            if (dec != null) release(dec);
            try { if (s != null) s.close(); } catch (Exception ignored) { }
            if (rx == me) rx = null;
        }
    }

    private static MediaCodec release(MediaCodec d) {
        try { d.stop(); } catch (Exception ignored) { }
        try { d.release(); } catch (Exception ignored) { }
        return null;
    }
}
