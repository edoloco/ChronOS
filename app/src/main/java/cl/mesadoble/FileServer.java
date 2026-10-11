package cl.mesadoble;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Archivos de ESTE teléfono para el gestor de archivos del otro.
 * Las listas y operaciones van por la conexión de la mesa; el contenido de los archivos por un canal
 * aparte (puerto 47821) para no trabar la mesa ni el teclado. Solo responde a quien tenga la clave
 * que se intercambia por la conexión de la mesa.
 */
public class FileServer {

    static final int PORT = 47821;
    private static FileServer inst;
    static synchronized FileServer get(Context c) { if (inst == null) inst = new FileServer(c.getApplicationContext()); return inst; }

    final String token;
    private final Context app;
    private final ExecutorService work = Executors.newCachedThreadPool();
    private volatile ServerSocket server;
    volatile String remoteToken;
    volatile int remotePort = PORT;

    private FileServer(Context c) {
        app = c;
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        token = sb.toString();
    }

    void setRemote(String k, int port) { remoteToken = k; remotePort = port > 0 ? port : PORT; }

    // ---------------- acceso ----------------

    static boolean hasAccess(Context c) {
        if (Build.VERSION.SDK_INT >= 30) {
            try { return (Boolean) Environment.class.getMethod("isExternalStorageManager").invoke(null); }
            catch (Exception e) { return false; }
        }
        return c.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    static Intent accessIntent(Context c) {
        if (Build.VERSION.SDK_INT >= 30)
            return new Intent("android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION", Uri.parse("package:" + c.getPackageName()));
        return new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + c.getPackageName()));
    }

    private void askAccess() {
        NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (nm.getNotificationChannel("avisos") == null)
            nm.createNotificationChannel(new NotificationChannel("avisos", "Avisos del teclado", NotificationManager.IMPORTANCE_HIGH));
        PendingIntent pi = PendingIntent.getActivity(app, 21, accessIntent(app).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        int icon = app.getResources().getIdentifier("ic_stat", "drawable", app.getPackageName());
        try {
            nm.notify(21, new Notification.Builder(app, "avisos")
                    .setSmallIcon(icon != 0 ? icon : android.R.drawable.stat_notify_more)
                    .setContentTitle("Mesa Doble quiere ver tus archivos")
                    .setContentText("Toca y activa \"Permitir acceso a todos los archivos\"")
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build());
        } catch (Exception ignored) { }
    }

    // ---------------- archivos locales ----------------

    static File root() { return Environment.getExternalStorageDirectory(); }

    static File resolve(String path) { return path == null || path.isEmpty() ? root() : new File(path); }

    static JSONArray list(File dir) throws IOException {
        File[] fs = dir.listFiles();
        if (fs == null) throw new IOException("No se puede abrir esta carpeta");
        JSONArray a = new JSONArray();
        for (File f : fs) {
            JSONObject o = new JSONObject();
            try {
                o.put("n", f.getName()); o.put("d", f.isDirectory());
                o.put("s", f.isDirectory() ? 0 : f.length()); o.put("m", f.lastModified());
            } catch (JSONException ignored) { }
            a.put(o);
        }
        return a;
    }

    /** Nombre libre en la carpeta: "foto.jpg" → "foto (2).jpg". */
    static File unique(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name, ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 2; ; i++) {
            f = new File(dir, base + " (" + i + ")" + ext);
            if (!f.exists()) return f;
        }
    }

    static boolean delete(File f) {
        if (f.isDirectory()) {
            File[] cs = f.listFiles();
            if (cs != null) for (File c : cs) delete(c);
        }
        return f.delete() || !f.exists();
    }

    static File copy(File src, File dstDir, boolean move) throws IOException {
        File dst = unique(dstDir, src.getName());
        if (move && src.renameTo(dst)) return dst;
        if (src.getCanonicalPath().equals(dstDir.getCanonicalPath()) || dstDir.getCanonicalPath().startsWith(src.getCanonicalPath() + "/"))
            throw new IOException("No se puede copiar una carpeta dentro de sí misma");
        if (src.isDirectory()) {
            if (!dst.mkdirs()) throw new IOException("No pude crear " + dst.getName());
            File[] cs = src.listFiles();
            if (cs != null) for (File c : cs) copy(c, dst, false);
        } else {
            try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst)) { pipe(in, out, src.length(), null); }
            //noinspection ResultOfMethodCallIgnored
            dst.setLastModified(src.lastModified());
        }
        if (move) delete(src);
        return dst;
    }

    interface Progress { boolean add(long bytes); }   // false = cancelar

    static void pipe(InputStream in, OutputStream out, long size, Progress p) throws IOException {
        byte[] buf = new byte[64 * 1024];
        long left = size;
        while (left > 0) {
            int n = in.read(buf, 0, (int) Math.min(buf.length, left));
            if (n < 0) throw new IOException("Se cortó la transferencia");
            out.write(buf, 0, n);
            left -= n;
            if (p != null && !p.add(n)) throw new IOException("Cancelado");
        }
        out.flush();
    }

    // ---------------- pedidos por la conexión de la mesa ----------------

    void handle(JSONObject m, Hub hub) {
        work.execute(() -> {
            String t = m.optString("t");
            JSONObject r = new JSONObject();
            try {
                r.put("req", m.optInt("req"));
                if (!hasAccess(app)) { r.put("err", "permiso"); askAccess(); }
                else if ("fsList".equals(t)) {
                    File d = resolve(m.optString("path"));
                    r.put("path", d.getAbsolutePath());
                    r.put("root", root().getAbsolutePath());
                    try { r.put("entries", list(d)); } catch (IOException e) { r.put("err", e.getMessage()); }
                } else if ("fsOp".equals(t)) {
                    op(m, r);
                }
            } catch (JSONException ignored) { }
            try { r.put("t", "fsList".equals(t) ? "fsListR" : "fsOpR"); } catch (JSONException ignored) { }
            hub.send(r);
        });
    }

    /** mkdir, delete, rename, copy, move (dentro de este teléfono). */
    static void op(JSONObject m, JSONObject r) throws JSONException {
        String op = m.optString("op");
        File f = resolve(m.optString("path"));
        try {
            switch (op) {
                case "mkdir": {
                    File d = m.optBoolean("unique") ? unique(f, m.optString("name")) : new File(f, m.optString("name"));
                    if (!d.isDirectory() && !d.mkdirs()) throw new IOException("No pude crear la carpeta");
                    r.put("path", d.getAbsolutePath());
                    break;
                }
                case "delete":
                    if (!delete(f)) throw new IOException("No pude borrar " + f.getName());
                    break;
                case "rename": {
                    File to = new File(f.getParentFile(), m.optString("name"));
                    if (to.exists()) throw new IOException("Ya existe " + to.getName());
                    if (!f.renameTo(to)) throw new IOException("No pude cambiar el nombre");
                    break;
                }
                case "copy": case "move":
                    r.put("path", copy(f, resolve(m.optString("to")), "move".equals(op)).getAbsolutePath());
                    break;
            }
            r.put("ok", true);
        } catch (IOException e) {
            r.put("ok", false);
            r.put("err", e.getMessage());
        }
    }

    // ---------------- canal de contenido ----------------

    synchronized void start() {
        if (server != null) return;
        try {
            ServerSocket ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(PORT));
            server = ss;
        } catch (IOException e) { return; }
        work.execute(() -> {
            while (server != null) {
                try {
                    Socket s = server.accept();
                    work.execute(() -> serve(s));
                } catch (IOException e) { break; }
            }
        });
    }

    static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1 && c != '\n') { b.write(c); if (b.size() > 65536) throw new IOException("Encabezado inválido"); }
        if (c == -1 && b.size() == 0) throw new IOException("Conexión cerrada");
        return new String(b.toByteArray(), StandardCharsets.UTF_8);
    }

    static void writeLine(OutputStream out, JSONObject o) throws IOException {
        out.write((o.toString() + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private void serve(Socket s) {
        try (Socket sock = s) {
            sock.setSoTimeout(60000);
            InputStream in = new BufferedInputStream(sock.getInputStream());
            OutputStream out = new BufferedOutputStream(sock.getOutputStream());
            JSONObject h = new JSONObject(readLine(in));
            JSONObject r = new JSONObject();
            if (!Arrays.equals(token.getBytes(StandardCharsets.UTF_8), h.optString("k").getBytes(StandardCharsets.UTF_8))) {
                r.put("ok", false); r.put("err", "clave"); writeLine(out, r); return;
            }
            if (!hasAccess(app)) { askAccess(); r.put("ok", false); r.put("err", "permiso"); writeLine(out, r); return; }
            if ("get".equals(h.optString("op"))) {
                File f = resolve(h.optString("path"));
                if (!f.isFile()) { r.put("ok", false); r.put("err", "No existe " + f.getName()); writeLine(out, r); return; }
                long size = f.length();
                r.put("ok", true); r.put("size", size); r.put("m", f.lastModified());
                writeLine(out, r);
                try (InputStream fin = new FileInputStream(f)) { pipe(fin, out, size, null); }
            } else if ("put".equals(h.optString("op"))) {
                File dir = resolve(h.optString("path"));
                File dst = unique(dir, h.optString("name"));
                File part = new File(dir, "." + dst.getName() + ".parte");
                long size = h.optLong("size");
                boolean ok = false;
                try (OutputStream fout = new FileOutputStream(part)) { pipe(in, fout, size, null); ok = true; }
                finally { if (!ok) //noinspection ResultOfMethodCallIgnored
                    part.delete(); }
                if (!part.renameTo(dst)) throw new IOException("No pude guardar " + dst.getName());
                if (h.has("m")) //noinspection ResultOfMethodCallIgnored
                    dst.setLastModified(h.optLong("m"));
                r.put("ok", true); r.put("path", dst.getAbsolutePath());
                writeLine(out, r);
            }
        } catch (Exception ignored) { }
    }
}
