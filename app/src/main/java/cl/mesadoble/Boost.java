package cl.mesadoble;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * "Boost experience": la música se decodifica en el principal; el canal DERECHO suena en el principal y el
 * IZQUIERDO viaja por WiFi y suena en el extendido. Así los dos teléfonos hacen de parlantes estéreo.
 *   boost (principal → extendido) {on}: conectarse (o no) al canal de audio, puerto 47822
 *   boostSet (extendido → principal) {on}: activar o desactivar desde la barra del extendido
 */
final class Boost {

    static final int PORT = 47822;
    /** Silencio con que parte el principal: le da tiempo al extendido para recibir y quedar a la par. */
    private static final int PREROLL_MS = 260;

    private Boost() { }

    static boolean enabled(Context c) { return c.getSharedPreferences("mesa", Context.MODE_PRIVATE).getBoolean("boost", false); }

    /** Desde cualquiera de los dos: se guarda en el principal y la música en curso cambia de modo. */
    static void setEnabled(Context c, Hub hub, boolean on) {
        c.getSharedPreferences("mesa", Context.MODE_PRIVATE).edit().putBoolean("boost", on).apply();
        if (hub.isPrincipal()) { MediaService.boostChanged(c); VideoActivity.boostChanged(c); }
        else if (hub.connected()) hub.send("boostSet", "on", on);
    }

    // ---------------- volumen: en Boost los dos teléfonos suenan al mismo volumen ----------------

    private static volatile boolean volLoop;
    private static int lastVol = -1;

    /** Mientras hay Boost, cada teléfono avisa al otro si cambió su volumen (en fracción, por si tienen escalas distintas). */
    static void syncVolume(Context c, Hub hub, boolean on) {
        if (!on) { volLoop = false; return; }
        if (volLoop) return;
        volLoop = true;
        lastVol = -1;
        android.media.AudioManager am = (android.media.AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        new Thread(() -> {
            while (volLoop) {
                int v = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
                if (v != lastVol) {
                    lastVol = v;
                    double f = v / (double) Math.max(1, am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC));
                    if (hub.connected()) hub.send("boostVol", "f", f);
                }
                try { Thread.sleep(350); } catch (InterruptedException e) { return; }
            }
        }, "boost-volumen").start();
    }

    /** Llegó el volumen del otro teléfono: este queda igual. */
    static void applyVolume(Context c, double f) {
        android.media.AudioManager am = (android.media.AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        int max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
        int v = (int) Math.round(f * max);
        if (v == am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)) return;
        lastVol = v;   // no rebota de vuelta
        try { am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, v, 0); } catch (Exception ignored) { }
    }

    // ---------------- reloj y sincronía ----------------
    // Cada trozo viaja con la HORA (reloj del principal) en que su primer sonido sale por el parlante del principal.
    // El extendido conoce la diferencia entre los dos relojes (pings ida y vuelta, se queda con el más rápido) y
    // ajusta su propio sonido: si va atrasado salta un poco, si va adelantado espera con silencio.

    static final byte AUDIO = 1, FLUSH = 2, PONG = 3, PING = 4;

    /** Hora (System.nanoTime) en que se oirá el cuadro 'frame' de esta pista. */
    static long timeOf(AudioTrack t, long frame, int sr) {
        android.media.AudioTimestamp ts = new android.media.AudioTimestamp();
        try {
            if (t.getTimestamp(ts) && ts.framePosition > 0) return ts.nanoTime + (frame - ts.framePosition) * 1_000_000_000L / sr;
        } catch (Exception ignored) { }
        long head = t.getPlaybackHeadPosition() & 0xffffffffL;
        return System.nanoTime() + (frame - head) * 1_000_000_000L / sr + 40_000_000L;   // aún sin hora exacta: se estima
    }

    // ---------------- principal: servidor del canal izquierdo ----------------

    private static ServerSocket server;
    private static volatile Socket client;
    private static volatile java.io.DataOutputStream clientOut;
    private static final Object lock = new Object();
    private static final LinkedBlockingQueue<byte[]> out = new LinkedBlockingQueue<>();

    static synchronized void serve() {
        if (server != null) return;
        try {
            ServerSocket ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(PORT));
            server = ss;
        } catch (Exception e) { return; }
        new Thread(() -> {
            while (server != null) {
                try {
                    Socket s = server.accept();
                    s.setTcpNoDelay(true);
                    Socket old = client;
                    synchronized (lock) {
                        client = s;
                        clientOut = new java.io.DataOutputStream(new java.io.BufferedOutputStream(s.getOutputStream(), 16384));
                    }
                    if (old != null) try { old.close(); } catch (Exception ignored) { }
                    out.clear();
                    // pings del extendido: se contestan al tiro con la hora de aquí
                    new Thread(() -> {
                        try {
                            DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
                            while (true) {
                                byte t = in.readByte();
                                long t0 = in.readLong();
                                if (t != PING) continue;
                                synchronized (lock) {
                                    if (client != s) return;
                                    clientOut.writeByte(PONG); clientOut.writeLong(t0); clientOut.writeLong(System.nanoTime());
                                    clientOut.flush();
                                }
                            }
                        } catch (Exception ignored) { }
                    }, "boost-ping").start();
                } catch (Exception e) { break; }
            }
        }, "boost-server").start();
        new Thread(() -> {
            while (true) {
                try {
                    byte[] b = out.take();
                    synchronized (lock) {
                        if (clientOut == null) continue;
                        try { clientOut.write(b); clientOut.flush(); }
                        catch (Exception e) { try { client.close(); } catch (Exception ignored) { } client = null; clientOut = null; }
                    }
                } catch (InterruptedException e) { return; }
            }
        }, "boost-send").start();
    }

    /** Un trozo del canal izquierdo, con la hora en que suena en el principal. */
    static void send(int sampleRate, byte[] pcm, int len, long when) {
        if (client == null) return;
        if (out.size() > 80) out.clear();   // el extendido no alcanza: mejor saltar que atrasarse
        ByteBuffer b = ByteBuffer.allocate(1 + 4 + 4 + 8 + len).order(ByteOrder.BIG_ENDIAN);
        b.put(AUDIO).putInt(sampleRate).putInt(len).putLong(when);
        b.put(pcm, 0, len);
        out.offer(b.array());
    }

    /** Vaciar el búfer del extendido (al saltar o pausar). */
    static void flush() {
        if (client == null) return;
        out.clear();
        out.offer(new byte[]{FLUSH});
    }

    // ---------------- extendido: recibe y toca el canal izquierdo, a la par del principal ----------------

    private static volatile Thread sink;
    private static volatile Socket sinkSocket;

    static void listen(Context c, Hub hub, boolean on) {
        Thread cur = sink;
        syncVolume(c, hub, on);
        if (on && cur != null && cur.isAlive()) return;   // ya está escuchando
        stopSink();
        if (!on) return;
        String ip = hub.link.peerIp();
        if (ip == null) return;
        Thread t = new Thread(() -> {
            AudioTrack track = null;
            int rate = 0;
            long written = 0;
            boolean liveTrack = false;
            Socket s = null;
            final long[] clock = {Long.MIN_VALUE, Long.MAX_VALUE};   // {diferencia de relojes, mejor ida y vuelta}
            try {
                for (int k = 0; ; k++) {   // el principal puede estar recién abriendo el canal
                    s = new Socket();
                    sinkSocket = s;
                    try { s.connect(new InetSocketAddress(ip, PORT), 3000); break; }
                    catch (Exception e) { try { s.close(); } catch (Exception ignored) { } if (k >= 4 || sink != Thread.currentThread()) throw e; Thread.sleep(400); }
                }
                s.setTcpNoDelay(true);
                final Socket fs = s;
                final java.io.DataOutputStream po = new java.io.DataOutputStream(s.getOutputStream());
                final Thread me = Thread.currentThread();
                new Thread(() -> {   // pings: muchos al principio, después uno cada 2 s (los relojes se separan de a poco)
                    try {
                        for (int i = 0; sink == me && !fs.isClosed(); i++) {
                            synchronized (po) { po.writeByte(PING); po.writeLong(System.nanoTime()); po.flush(); }
                            Thread.sleep(i < 20 ? 150 : 2000);
                            if (i == 60) synchronized (clock) { clock[1] = Long.MAX_VALUE; }   // cada tanto se vuelve a medir desde cero
                            if (i > 60 && i % 60 == 0) synchronized (clock) { clock[1] += clock[1] / 2; }
                        }
                    } catch (Exception ignored) { }
                }, "boost-pings").start();
                DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream(), 65536));
                byte[] buf = new byte[0], silence = new byte[0];
                while (sink == me) {
                    byte type = in.readByte();
                    if (type == PONG) {
                        long t0 = in.readLong(), tp = in.readLong(), t1 = System.nanoTime(), rtt = t1 - t0;
                        synchronized (clock) {
                            if (rtt <= clock[1]) { clock[1] = rtt; clock[0] = tp - (t0 + t1) / 2; }
                        }
                        continue;
                    }
                    if (type == FLUSH) {
                        if (track != null) { track.pause(); track.flush(); track.play(); }
                        written = 0;
                        continue;
                    }
                    int sr = in.readInt(), len = in.readInt();
                    long when = in.readLong();
                    if (len > buf.length) buf = new byte[len];
                    in.readFully(buf, 0, len);
                    boolean live = when == 0;   // sonido capturado de otra app (YouTube…): lo antes posible, sin esperar
                    if (track == null || sr != rate || live != liveTrack) {
                        if (track != null) track.release();
                        rate = sr;
                        liveTrack = live;
                        track = live ? liveTrack(sr) : track(sr);
                        track.play();
                        written = 0;
                    }
                    if (live) {
                        long queued = written - (track.getPlaybackHeadPosition() & 0xffffffffL);
                        if (queued > sr / 8) continue;   // se acumuló retraso: se salta este trozo para alcanzar
                        track.write(buf, 0, len);
                        written += len / 2;
                        continue;
                    }
                    int off = 0, n = len;
                    long offset;
                    synchronized (clock) { offset = clock[0]; }
                    if (offset != Long.MIN_VALUE) {
                        long target = when - offset;                       // cuándo debe sonar aquí
                        long err = timeOf(track, written, sr) - target;    // > 0: vamos atrasados
                        if (err > 12_000_000L) {
                            int skip = (int) Math.min(n / 2, err * sr / 1_000_000_000L) * 2;
                            off += skip; n -= skip;
                        } else if (err < -12_000_000L) {
                            int pad = (int) Math.min(sr, -err * sr / 1_000_000_000L) * 2;
                            if (silence.length < pad) silence = new byte[pad];
                            track.write(silence, 0, pad);
                            written += pad / 2;
                        }
                    }
                    if (n > 0) { track.write(buf, off, n); written += n / 2; }
                }
            } catch (Exception ignored) {
            } finally {
                if (track != null) try { track.release(); } catch (Exception ignored) { }
                if (s != null) try { s.close(); } catch (Exception ignored) { }
            }
        }, "boost-sink");
        sink = t;
        t.start();
    }

    static void stopSink() {
        sink = null;
        Socket s = sinkSocket;
        if (s != null) try { s.close(); } catch (Exception ignored) { }
    }

    /** Pista con el búfer más chico posible (para el sonido capturado en vivo). */
    static AudioTrack liveTrack(int sr) {
        int min = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioTrack.Builder b = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(sr).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(Math.max(min, sr / 25 * 2))
                .setTransferMode(AudioTrack.MODE_STREAM);
        try { AudioTrack.Builder.class.getMethod("setPerformanceMode", int.class).invoke(b, 1 /* PERFORMANCE_MODE_LOW_LATENCY */); } catch (Exception ignored) { }
        return b.build();
    }

    static AudioTrack track(int sr) {
        int min = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        return new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(sr).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(Math.max(min * 2, sr / 5 * 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
    }

    /** Escribe el silencio inicial y devuelve cuántos cuadros lleva la pista. */
    private static long preroll(AudioTrack t, int sr) {
        int n = sr / 1000 * PREROLL_MS;
        t.write(new byte[n * 2], 0, n * 2);
        return n;
    }

    // ---------------- principal: reproductor que separa los canales ----------------

    /** Decodifica un archivo y reparte los canales. Lo maneja MediaService igual que un MediaPlayer. */
    static class Engine implements Runnable {
        interface Done { void onDone(boolean error); }

        private final String path;
        private final Done done;
        private volatile boolean playing = true, stop;
        private volatile long seekMs = -1, posMs, durMs;
        private Thread thread;

        Engine(String path, long startMs, Done done) {
            this.path = path;
            this.done = done;
            if (startMs > 0) seekMs = startMs;
        }

        void start() { serve(); thread = new Thread(this, "boost-engine"); thread.start(); }
        void play() { playing = true; }
        void pause() { playing = false; }
        boolean playing() { return playing; }
        long pos() { return posMs; }
        /** Lo que se está oyendo ahora (lo decodificado menos lo que aún espera en el parlante). */
        private volatile AudioTrack curTrack;
        private volatile long curWritten;
        private volatile int curRate = 44100;
        long heard() {
            AudioTrack t = curTrack;
            if (t == null) return posMs;
            try {
                long head = t.getPlaybackHeadPosition() & 0xffffffffL;
                return Math.max(0, posMs - Math.max(0, curWritten - head) * 1000 / Math.max(1, curRate));
            } catch (Exception e) { return posMs; }
        }
        long dur() { return durMs; }
        void seek(long ms) { seekMs = Math.max(0, ms); }
        void release() { stop = true; }

        @Override public void run() {
            MediaExtractor ex = new MediaExtractor();
            MediaCodec codec = null;
            AudioTrack track = null;
            boolean error = false, ended = false;
            try {
                ex.setDataSource(path);
                int ti = -1;
                MediaFormat fmt = null;
                for (int i = 0; i < ex.getTrackCount(); i++) {
                    MediaFormat f = ex.getTrackFormat(i);
                    String mime = f.getString(MediaFormat.KEY_MIME);
                    if (mime != null && mime.startsWith("audio/")) { ti = i; fmt = f; break; }
                }
                if (ti < 0) throw new IllegalStateException("sin audio");
                ex.selectTrack(ti);
                durMs = fmt.containsKey(MediaFormat.KEY_DURATION) ? fmt.getLong(MediaFormat.KEY_DURATION) / 1000 : 0;
                int sr = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE), ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
                codec.configure(fmt, null, null, 0);
                codec.start();
                track = track(sr);
                track.play();
                long written = preroll(track, sr);
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                boolean inEos = false, wasPlaying = true;
                byte[] right = new byte[0], left = new byte[0];
                while (!stop) {
                    if (seekMs >= 0) {
                        ex.seekTo(seekMs * 1000, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                        codec.flush();
                        track.pause(); track.flush(); track.play();
                        flush();
                        written = preroll(track, sr);
                        posMs = seekMs;
                        seekMs = -1;
                        inEos = false;
                    }
                    if (!playing) {
                        if (wasPlaying) { track.pause(); track.flush(); flush(); wasPlaying = false; }
                        Thread.sleep(40);
                        continue;
                    }
                    if (!wasPlaying) { track.play(); written = preroll(track, sr); wasPlaying = true; }
                    if (!inEos) {
                        int ii = codec.dequeueInputBuffer(5000);
                        if (ii >= 0) {
                            ByteBuffer ib = codec.getInputBuffer(ii);
                            int n = ib == null ? -1 : ex.readSampleData(ib, 0);
                            if (n < 0) { codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inEos = true; }
                            else { codec.queueInputBuffer(ii, 0, n, ex.getSampleTime(), 0); ex.advance(); }
                        }
                    }
                    int oi = codec.dequeueOutputBuffer(info, 5000);
                    if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat of = codec.getOutputFormat();
                        int nsr = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                        ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                        if (nsr != sr) { sr = nsr; track.release(); track = track(sr); track.play(); written = preroll(track, sr); }
                    } else if (oi >= 0) {
                        ByteBuffer ob = codec.getOutputBuffer(oi);
                        if (ob != null && info.size > 0) {
                            ob.position(info.offset);
                            ob.limit(info.offset + info.size);
                            int frames = info.size / 2 / Math.max(1, ch);
                            if (right.length < frames * 2) { right = new byte[frames * 2]; left = new byte[frames * 2]; }
                            ob.order(ByteOrder.LITTLE_ENDIAN);
                            for (int f = 0; f < frames; f++) {
                                short l = ob.getShort(), r = l;
                                if (ch >= 2) { r = ob.getShort(); for (int k = 2; k < ch; k++) ob.getShort(); }
                                left[f * 2] = (byte) l; left[f * 2 + 1] = (byte) (l >> 8);
                                right[f * 2] = (byte) r; right[f * 2 + 1] = (byte) (r >> 8);
                            }
                            send(sr, left, frames * 2, timeOf(track, written, sr));   // con la hora en que suena aquí
                            track.write(right, 0, frames * 2);   // marca el ritmo
                            written += frames;
                            curTrack = track; curWritten = written; curRate = sr;
                            posMs = info.presentationTimeUs / 1000 + frames * 1000L / Math.max(1, sr);
                        }
                        codec.releaseOutputBuffer(oi, false);
                        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) { ended = true; break; }
                    }
                }
            } catch (Exception e) {
                error = !stop;
            } finally {
                try { if (codec != null) { codec.stop(); codec.release(); } } catch (Exception ignored) { }
                try { ex.release(); } catch (Exception ignored) { }
                try { if (track != null) { if (ended) Thread.sleep(300); track.release(); } } catch (Exception ignored) { }
            }
            if (!stop && done != null) done.onDone(error || !ended);
        }
    }
}
