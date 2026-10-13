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
    /** Lo que el principal adelanta su propio sonido para quedar a la par con el extendido (la red y su búfer). */
    private static final int PREROLL_MS = 140;

    private Boost() { }

    static boolean enabled(Context c) { return c.getSharedPreferences("mesa", Context.MODE_PRIVATE).getBoolean("boost", false); }

    /** Desde cualquiera de los dos: se guarda en el principal y la música en curso cambia de modo. */
    static void setEnabled(Context c, Hub hub, boolean on) {
        c.getSharedPreferences("mesa", Context.MODE_PRIVATE).edit().putBoolean("boost", on).apply();
        if (hub.isPrincipal()) MediaService.boostChanged(c);
        else if (hub.connected()) hub.send("boostSet", "on", on);
    }

    // ---------------- principal: servidor del canal izquierdo ----------------

    private static ServerSocket server;
    private static volatile Socket client;
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
                    client = s;
                    if (old != null) try { old.close(); } catch (Exception ignored) { }
                    out.clear();
                } catch (Exception e) { break; }
            }
        }, "boost-server").start();
        new Thread(() -> {
            while (true) {
                try {
                    byte[] b = out.take();
                    Socket s = client;
                    if (s == null) continue;
                    try { s.getOutputStream().write(b); } catch (Exception e) { try { s.close(); } catch (Exception ignored) { } client = null; }
                } catch (InterruptedException e) { return; }
            }
        }, "boost-send").start();
    }

    /** Un trozo del canal izquierdo (sampleRate -1 = vaciar el búfer del extendido, tras saltar o pausar). */
    static void send(int sampleRate, byte[] pcm, int len) {
        if (client == null) return;
        if (out.size() > 60) out.clear();   // el extendido no alcanza: mejor saltar que atrasarse
        ByteBuffer b = ByteBuffer.allocate(8 + len).order(ByteOrder.BIG_ENDIAN);
        b.putInt(sampleRate).putInt(len);
        if (len > 0) b.put(pcm, 0, len);
        out.offer(b.array());
    }

    // ---------------- extendido: recibe y toca el canal izquierdo ----------------

    private static volatile Thread sink;
    private static volatile Socket sinkSocket;

    static void listen(Context c, Hub hub, boolean on) {
        Thread cur = sink;
        if (on && cur != null && cur.isAlive()) return;   // ya está escuchando
        stopSink();
        if (!on) return;
        String ip = hub.link.peerIp();
        if (ip == null) return;
        Thread t = new Thread(() -> {
            AudioTrack track = null;
            int rate = 0;
            Socket s = null;
            try {
                for (int k = 0; ; k++) {   // el principal puede estar recién abriendo el canal
                    s = new Socket();
                    sinkSocket = s;
                    try { s.connect(new InetSocketAddress(ip, PORT), 3000); break; }
                    catch (Exception e) { try { s.close(); } catch (Exception ignored) { } if (k >= 4 || sink != Thread.currentThread()) throw e; Thread.sleep(400); }
                }
                s.setTcpNoDelay(true);
                DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream(), 32768));
                byte[] buf = new byte[0];
                while (sink == Thread.currentThread()) {
                    int sr = in.readInt(), len = in.readInt();
                    if (len > buf.length) buf = new byte[len];
                    in.readFully(buf, 0, len);
                    if (sr < 0) { if (track != null) { track.pause(); track.flush(); track.play(); } continue; }
                    if (track == null || sr != rate) {
                        if (track != null) track.release();
                        rate = sr;
                        track = track(sr);
                        track.play();
                    }
                    track.write(buf, 0, len);
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

    static AudioTrack track(int sr) {
        int min = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        return new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(sr).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(Math.max(min * 2, sr / 5 * 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
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
                track.write(new byte[sr / 1000 * PREROLL_MS * 2], 0, sr / 1000 * PREROLL_MS * 2);   // a la par con el extendido
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                boolean inEos = false, wasPlaying = true;
                byte[] right = new byte[0], left = new byte[0];
                while (!stop) {
                    if (seekMs >= 0) {
                        ex.seekTo(seekMs * 1000, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                        codec.flush();
                        track.pause(); track.flush(); track.play();
                        send(-1, null, 0);
                        posMs = seekMs;
                        seekMs = -1;
                        inEos = false;
                    }
                    if (!playing) {
                        if (wasPlaying) { track.pause(); send(-1, null, 0); wasPlaying = false; }
                        Thread.sleep(40);
                        continue;
                    }
                    if (!wasPlaying) { track.play(); wasPlaying = true; }
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
                        if (nsr != sr) { sr = nsr; track.release(); track = track(sr); track.play(); }
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
                            send(sr, left, frames * 2);
                            track.write(right, 0, frames * 2);   // marca el ritmo
                            posMs = info.presentationTimeUs / 1000;
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
