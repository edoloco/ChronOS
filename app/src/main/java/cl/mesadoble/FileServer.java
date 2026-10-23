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
    /** Carpeta abierta en el gestor de archivos del otro teléfono (destino al copiar o mover). */
    static volatile String remoteDir = "";
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
                    .setContentTitle("ChronOS quiere ver tus archivos")
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
        int dirsChecked = 0;
        for (File f : fs) {
            JSONObject o = new JSONObject();
            try {
                o.put("n", f.getName()); o.put("d", f.isDirectory());
                o.put("s", f.isDirectory() ? 0 : f.length()); o.put("m", f.lastModified());
                // carpetas con música: cuántos temas tienen (para mostrarlas como álbum)
                if (f.isDirectory() && dirsChecked++ < 200) { int au = audioCount(f); if (au > 0) o.put("au", au); }
            } catch (JSONException ignored) { }
            a.put(o);
        }
        return a;
    }

    static boolean isAudio(String name) { return FilesProvider.mime(name).startsWith("audio/"); }

    /** Temas de música directamente dentro de una carpeta. */
    static int audioCount(File d) {
        String[] ns = d.list();
        int n = 0;
        if (ns != null) for (String x : ns) if (isAudio(x)) n++;
        return n;
    }

    // ---------------- música: datos de cada tema (artista, título, álbum, número, año) ----------------

    /** Datos de los temas de una carpeta: {nombre: {ar, ti, al, tr, yr}} (como mucho 400). */
    static JSONObject meta(File dir) {
        JSONObject out = new JSONObject();
        File[] fs = dir.listFiles();
        if (fs == null) return out;
        int n = 0;
        for (File f : fs) {
            if (!f.isFile() || !isAudio(f.getName()) || n++ >= 400) continue;
            android.media.MediaMetadataRetriever r = new android.media.MediaMetadataRetriever();
            try {
                r.setDataSource(f.getAbsolutePath());
                JSONObject o = new JSONObject();
                put(o, "ar", r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST));
                put(o, "aa", r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST));
                put(o, "ti", r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE));
                put(o, "al", r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM));
                put(o, "tr", r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER));
                put(o, "yr", r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_YEAR));
                put(o, "du", r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION));
                out.put(f.getName(), o);
            } catch (Exception ignored) {
            } finally { try { r.release(); } catch (Exception ignored) { } }
        }
        return out;
    }

    private static void put(JSONObject o, String k, String v) throws JSONException { if (v != null && !v.trim().isEmpty()) o.put(k, v.trim()); }

    /** Carátula: una imagen de la carpeta (cover, folder…) o la que viene dentro del primer tema. */
    static android.graphics.Bitmap cover(File f, int size) {
        try {
            if (f.isFile()) return art(f, size);
            File[] fs = f.listFiles();
            if (fs == null) return null;
            File img = null, firstAudio = null;
            for (File x : fs) {
                String n = x.getName().toLowerCase(java.util.Locale.ROOT);
                if (x.isFile() && FilesProvider.mime(n).startsWith("image/")) {
                    if (n.startsWith("cover") || n.startsWith("folder") || n.startsWith("front") || n.startsWith("album")) { img = x; break; }
                    if (img == null) img = x;
                }
                if (firstAudio == null && x.isFile() && isAudio(n)) firstAudio = x;
            }
            if (img != null) return thumb(img, size);
            return firstAudio == null ? null : art(firstAudio, size);
        } catch (Exception e) { return null; }
    }

    /** La carátula que trae el archivo de música. */
    private static android.graphics.Bitmap art(File f, int size) {
        android.media.MediaMetadataRetriever r = new android.media.MediaMetadataRetriever();
        try {
            r.setDataSource(f.getAbsolutePath());
            byte[] b = r.getEmbeddedPicture();
            if (b == null) return null;
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeByteArray(b, 0, b.length, o);
            int sample = 1;
            while (Math.min(o.outWidth, o.outHeight) / (sample * 2) >= size) sample *= 2;
            o = new android.graphics.BitmapFactory.Options();
            o.inSampleSize = sample;
            android.graphics.Bitmap src = android.graphics.BitmapFactory.decodeByteArray(b, 0, b.length, o);
            return src == null ? null : android.media.ThumbnailUtils.extractThumbnail(src, size, size);
        } catch (Exception e) { return null; }
        finally { try { r.release(); } catch (Exception ignored) { } }
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

    /** Miniatura cuadrada de una foto o un video (null si no se puede). */
    @SuppressWarnings("deprecation")
    static android.graphics.Bitmap thumb(File f, int size) {
        try {
            String type = FilesProvider.mime(f.getName());
            if (f.isDirectory() || type.startsWith("audio/")) return cover(f, Math.min(size, 640));   // álbum o tema: carátula
            android.graphics.Bitmap src;
            if (type.startsWith("video/")) {
                src = android.media.ThumbnailUtils.createVideoThumbnail(f.getAbsolutePath(), android.provider.MediaStore.Images.Thumbnails.MINI_KIND);
            } else {
                android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath(), o);
                int sample = 1;
                while (Math.min(o.outWidth, o.outHeight) / (sample * 2) >= size) sample *= 2;
                o = new android.graphics.BitmapFactory.Options();
                o.inSampleSize = sample;
                src = android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath(), o);
                if (src != null) {
                    int rot = PhotoActivity.exifRotation(f.getAbsolutePath());
                    if (rot != 0) {
                        android.graphics.Matrix mx = new android.graphics.Matrix();
                        mx.postRotate(rot);
                        src = android.graphics.Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), mx, true);
                    }
                }
            }
            if (src == null) return null;
            if (size > 320) {   // vista grande: la foto entera (sin recortar al cuadrado)
                float k = Math.min(1f, size / (float) Math.max(src.getWidth(), src.getHeight()));
                return k >= 1f ? src : android.graphics.Bitmap.createScaledBitmap(src, Math.round(src.getWidth() * k), Math.round(src.getHeight() * k), true);
            }
            return android.media.ThumbnailUtils.extractThumbnail(src, size, size);
        } catch (Exception e) { return null; }
    }

    static boolean delete(File f) {
        if (f.isDirectory()) {
            File[] cs = f.listFiles();
            if (cs != null) for (File c : cs) delete(c);
        }
        return f.delete() || !f.exists();
    }

    static File copy(File src, File dstDir, boolean move) throws IOException { return copy(src, dstDir, move, null, null); }

    interface Step { void on(String name, long done, long size); }

    /** Copia (o mueve) un archivo o carpeta; informa el avance y se puede cancelar. */
    static File copy(File src, File dstDir, boolean move, Step st, java.util.function.BooleanSupplier cancel) throws IOException {
        if (cancel != null && cancel.getAsBoolean()) throw new IOException("Cancelado");
        File dst = unique(dstDir, src.getName());
        if (move && src.renameTo(dst)) return dst;
        if (src.getCanonicalPath().equals(dstDir.getCanonicalPath()) || dstDir.getCanonicalPath().startsWith(src.getCanonicalPath() + "/"))
            throw new IOException("No se puede copiar una carpeta dentro de sí misma");
        if (src.isDirectory()) {
            if (!dst.mkdirs()) throw new IOException("No pude crear " + dst.getName());
            File[] cs = src.listFiles();
            if (cs != null) for (File c : cs) copy(c, dst, false, st, cancel);
        } else {
            final long size = src.length();
            final long[] done = {0};
            if (st != null) st.on(src.getName(), 0, size);
            try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst)) {
                pipe(in, out, size, n -> {
                    done[0] += n;
                    if (st != null) st.on(src.getName(), done[0], size);
                    return cancel == null || !cancel.getAsBoolean();
                });
            } catch (IOException e) { //noinspection ResultOfMethodCallIgnored
                dst.delete(); throw e; }
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

    // ---------------- categorías del gestor de archivos (inicio, imágenes, audio, videos, documentos, apps) ----------------

    static final String[] DOC_EXT = {".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx", ".txt", ".odt", ".ods", ".odp", ".rtf", ".csv", ".epub"};

    /** Almacenamientos (interno y tarjeta SD) con espacio usado y total. */
    static JSONArray storages(Context c) throws JSONException {
        JSONArray a = new JSONArray();
        java.util.LinkedHashSet<String> roots = new java.util.LinkedHashSet<>();
        roots.add(root().getAbsolutePath());
        File[] ext = c.getExternalFilesDirs(null);
        for (int i = 1; ext != null && i < ext.length; i++) {
            if (ext[i] == null) continue;
            String p = ext[i].getAbsolutePath();
            int k = p.indexOf("/Android/");
            if (k > 0) roots.add(p.substring(0, k));
        }
        int n = 0;
        for (String p : roots) {
            try {
                android.os.StatFs st = new android.os.StatFs(p);
                long total = st.getTotalBytes(), free = st.getAvailableBytes();
                JSONObject o = new JSONObject();
                o.put("n", n == 0 ? "Almacenamiento" : "Tarjeta SD"); o.put("p", p); o.put("used", total - free); o.put("total", total);
                a.put(o);
                n++;
            } catch (Exception ignored) { }
        }
        return a;
    }

    static JSONObject category(Context c, String cat) throws JSONException {
        JSONObject r = new JSONObject();
        JSONArray e = new JSONArray();
        switch (cat) {
            case "home": {
                r.put("storages", storages(c));
                r.put("model", android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL);
                r.put("downloads", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).getAbsolutePath());
                r.put("dlSize", dirSize(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), 3));
                for (String k : new String[]{"images", "audio", "video", "docs"}) r.put(k + "N", media(c, k, null));
                r.put("appsN", apps(c, null));
                return r;
            }
            case "apps": apps(c, e); break;
            default: media(c, cat, e);
        }
        r.put("entries", e);
        return r;
    }

    private static long dirSize(File d, int depth) {
        long s = 0;
        File[] l = d.listFiles();
        if (l == null) return 0;
        for (File f : l) s += f.isDirectory() ? (depth > 0 ? dirSize(f, depth - 1) : 0) : f.length();
        return s;
    }

    /** Archivos de un tipo, del más nuevo al más viejo (o solo cuántos hay, si out es null). */
    @SuppressWarnings("deprecation")
    private static int media(Context c, String cat, JSONArray out) {
        android.net.Uri uri = android.provider.MediaStore.Files.getContentUri("external");
        String sel;
        switch (cat) {
            case "images": sel = "media_type=1"; break;
            case "audio": sel = "media_type=2"; break;
            case "video": sel = "media_type=3"; break;
            default: {
                StringBuilder b = new StringBuilder("(");
                for (int i = 0; i < DOC_EXT.length; i++) b.append(i > 0 ? " OR " : "").append("_data LIKE '%").append(DOC_EXT[i]).append("'");
                sel = b.append(")").toString();
            }
        }
        int n = 0;
        try (android.database.Cursor cur = c.getContentResolver().query(uri,
                new String[]{"_data", "_size", "date_modified"}, sel, null, "date_modified DESC")) {
            if (cur == null) return 0;
            if (out == null) return cur.getCount();
            while (cur.moveToNext() && n < 1500) {
                String p = cur.getString(0);
                if (p == null) continue;
                File f = new File(p);
                JSONObject o = new JSONObject();
                o.put("n", f.getName()); o.put("p", p); o.put("d", false); o.put("s", cur.getLong(1)); o.put("m", cur.getLong(2) * 1000);
                out.put(o);
                n++;
            }
        } catch (Exception ignored) { }
        return n;
    }

    private static int apps(Context c, JSONArray out) {
        PackageManager pm = c.getPackageManager();
        int n = 0;
        for (android.content.pm.ApplicationInfo ai : pm.getInstalledApplications(0)) {
            if ((ai.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0 && (ai.flags & android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0) continue;
            if (pm.getLaunchIntentForPackage(ai.packageName) == null) continue;
            n++;
            if (out == null) continue;
            try {
                JSONObject o = new JSONObject();
                File f = new File(ai.sourceDir);
                o.put("n", String.valueOf(pm.getApplicationLabel(ai))); o.put("p", ai.sourceDir); o.put("k", ai.packageName);
                o.put("d", false); o.put("s", f.length()); o.put("m", f.lastModified());
                out.put(o);
            } catch (Exception ignored) { }
        }
        return n;
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
                } else if ("fsCat".equals(t)) {
                    r.put("res", category(app, m.optString("cat")));
                } else if ("fsOp".equals(t)) {
                    op(m, r);
                } else if ("fsMeta".equals(t)) {
                    r.put("meta", meta(resolve(m.optString("path"))));
                } else if ("fsThumb".equals(t)) {
                    android.graphics.Bitmap b = thumb(resolve(m.optString("path")), Math.max(64, Math.min(1600, m.optInt("size", 160))));   // grande: ver la foto desde el otro
                    if (b != null) {
                        ByteArrayOutputStream bos = new ByteArrayOutputStream();
                        b.compress(android.graphics.Bitmap.CompressFormat.JPEG, m.optInt("size", 160) > 320 ? 85 : 72, bos);
                        r.put("img", android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP));
                    }
                }
            } catch (Exception e) {   // nunca dejar al otro teléfono esperando
                try { r.put("err", e.getMessage() == null ? "No pude leer en el otro teléfono" : e.getMessage()); } catch (JSONException ignored) { }
            }
            try { r.put("t", t + "R"); } catch (JSONException ignored) { }
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

    // ---------------- copiar o mover dentro del principal ----------------
    // Las dos ventanas del gestor (una en cada pantalla) muestran carpetas del PRINCIPAL:
    // el trabajo siempre corre aquí, en el principal, y avisa el avance a las dos pantallas.

    private volatile boolean jobCancel, jobRunning;

    void cancelJob() { jobCancel = true; }

    /** Abre un archivo del principal en el principal (pedido desde la ventana del extendido). */
    static void openHere(Context c, Hub hub, String path) {
        File f = new File(path);
        if (!f.isFile()) return;
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(FilesProvider.uriFor(c, f), FilesProvider.mime(f.getName()));
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        hub.startOrNotify(i, path.hashCode(), "Abrir " + f.getName(), "Toca para abrirlo en este teléfono");
    }

    void runJob(JSONObject m, Hub hub) {
        if (jobRunning) return;
        jobRunning = true;
        jobCancel = false;
        work.execute(() -> {
            String src = m.optString("src");
            JSONArray items = m.optJSONArray("items");
            boolean move = m.optBoolean("move");
            int total = items != null ? items.length() : 0;
            emit(hub, "begin", src, m.optString("title"), "", -1, "", null);
            String err = null;
            int done = 0;
            try {
                if (!hasAccess(app)) { askAccess(); throw new IOException("Falta el permiso de archivos en el principal"); }
                File dst = resolve(m.optString("to"));
                final long[] last = {0};
                for (int i = 0; i < total && !jobCancel; i++) {
                    File f = new File(items.optString(i));
                    final int idx = i;
                    try {
                        if (move && f.getParentFile() != null && f.getParentFile().getCanonicalPath().equals(dst.getCanonicalPath())) continue;
                        copy(f, dst, move, (name, d, size) -> {
                            long now = System.currentTimeMillis();
                            if (now - last[0] < 150 && d < size) return;
                            last[0] = now;
                            float p = size > 0 ? d / (float) size : -1;
                            emit(hub, "prog", src, null, name, p, (p >= 0 ? Math.round(p * 100) + "% · " : "") + (idx + 1) + " de " + total, null);
                        }, () -> jobCancel);
                        done++;
                    } catch (IOException e) { if (!jobCancel) err = f.getName() + ": " + e.getMessage(); }
                }
            } catch (IOException e) { err = e.getMessage(); }
            String msg = jobCancel ? "Cancelado" : err != null ? err
                    : (move ? "Movido" : "Copiado") + (done == 1 ? "" : ": " + done + " elementos");
            emit(hub, "end", src, null, "", 1, "", msg);
            jobRunning = false;
        });
    }

    // ---------------- túnel entre los dos teléfonos ----------------
    // El teléfono dueño de los archivos los manda, uno por uno, por el canal de contenido del otro (puerto 47821,
    // con su clave). Encabezados:  {k, put:"top", to, name, dir} → {ok, name}  (nombre libre en el destino)
    //                              {k, put:"file", to, rel, size, m} + bytes → {ok}
    //                              {k, put:"dir", to, rel} → {ok}

    /** Copia (o mueve) lo elegido de ESTE teléfono a una carpeta del otro. */
    void pushJob(JSONObject m, Hub hub) {
        if (jobRunning) return;
        jobRunning = true;
        jobCancel = false;
        work.execute(() -> {
            String src = m.optString("src");
            JSONArray items = m.optJSONArray("items");
            boolean move = m.optBoolean("move");
            String to = m.optString("to");
            int total = items != null ? items.length() : 0;
            emit(hub, "begin", src, m.optString("title"), "", -1, "", null);
            String err = null;
            int done = 0;
            try {
                String ip = hub.peerIp();
                if (ip == null || remoteToken == null) throw new IOException("El otro teléfono no está conectado");
                if (!hasAccess(app)) { askAccess(); throw new IOException("Falta el permiso de archivos en este teléfono"); }
                for (int i = 0; i < total && !jobCancel; i++) {
                    File f = new File(items.optString(i));
                    final int idx = i;
                    try {
                        JSONObject h = new JSONObject();
                        h.put("put", "top"); h.put("to", to); h.put("name", f.getName()); h.put("dir", f.isDirectory());
                        JSONObject r = ask(ip, h, null, 0);
                        String top = r.optString("name", f.getName());
                        final long[] last = {0};
                        pushTree(ip, f, to, top, (name, d, size) -> {
                            long now = System.currentTimeMillis();
                            if (now - last[0] < 150 && d < size) return;
                            last[0] = now;
                            float p = size > 0 ? d / (float) size : -1;
                            emit(hub, "prog", src, null, name, p, (p >= 0 ? Math.round(p * 100) + "% · " : "") + (idx + 1) + " de " + total, null);
                        });
                        if (move && !jobCancel) delete(f);
                        done++;
                    } catch (IOException | JSONException e) { if (!jobCancel) err = f.getName() + ": " + e.getMessage(); }
                }
            } catch (IOException e) { err = e.getMessage(); }
            String msg = jobCancel ? "Cancelado" : err != null ? err
                    : (move ? "Movido" : "Copiado") + " al otro teléfono" + (done == 1 ? "" : ": " + done + " elementos");
            emit(hub, "end", src, null, "", 1, "", msg);
            jobRunning = false;
        });
    }

    private void pushTree(String ip, File f, String to, String rel, Step st) throws IOException, JSONException {
        if (jobCancel) throw new IOException("Cancelado");
        JSONObject h = new JSONObject();
        h.put("to", to); h.put("rel", rel);
        if (f.isDirectory()) {
            h.put("put", "dir");
            ask(ip, h, null, 0);
            File[] cs = f.listFiles();
            if (cs != null) for (File c : cs) pushTree(ip, c, to, rel + "/" + c.getName(), st);
        } else {
            h.put("put", "file"); h.put("size", f.length()); h.put("m", f.lastModified());
            ask(ip, h, f, f.length());
            if (st != null) st.on(f.getName(), f.length(), f.length());
        }
    }

    /** Una conexión: encabezado, contenido (si hay) y respuesta. */
    private JSONObject ask(String ip, JSONObject h, File body, long size) throws IOException, JSONException {
        h.put("k", remoteToken);
        try (Socket sock = new Socket()) {
            sock.connect(new InetSocketAddress(ip, remotePort), 4000);
            sock.setSoTimeout(60000);
            OutputStream out = new BufferedOutputStream(sock.getOutputStream());
            InputStream in = new BufferedInputStream(sock.getInputStream());
            writeLine(out, h);
            if (body != null) try (InputStream fin = new FileInputStream(body)) {
                pipe(fin, out, size, n -> !jobCancel);
            }
            JSONObject r = new JSONObject(readLine(in));
            if (!r.optBoolean("ok")) throw new IOException("err".equals(r.optString("err")) ? "No se pudo" : r.optString("err", "No se pudo"));
            return r;
        }
    }

    /** Ruta segura dentro de la carpeta destino (sin "..", sin salir de ella). */
    private static File inside(String to, String rel) throws IOException {
        File base = resolve(to).getCanonicalFile(), f = new File(base, rel).getCanonicalFile();
        if (!f.getPath().startsWith(base.getPath() + "/") && !f.equals(base)) throw new IOException("Ruta inválida");
        return f;
    }

    /** Lado que recibe: crea carpetas y guarda los archivos que manda el otro teléfono. */
    private void receive(JSONObject h, InputStream in, OutputStream out) throws IOException, JSONException {
        JSONObject r = new JSONObject();
        String kind = h.optString("put");
        if ("top".equals(kind)) {
            File dst = resolve(h.optString("to"));
            if (!dst.isDirectory()) throw new IOException("La carpeta de destino no existe");
            String nm = new File(h.optString("name")).getName();
            if (nm.isEmpty() || nm.equals("..")) throw new IOException("Nombre inválido");
            File u = unique(dst, nm);
            if (h.optBoolean("dir") && !u.mkdirs()) throw new IOException("No pude crear " + u.getName());
            r.put("name", u.getName());
        } else if ("dir".equals(kind)) {
            File d = inside(h.optString("to"), h.optString("rel"));
            if (!d.isDirectory() && !d.mkdirs()) throw new IOException("No pude crear " + d.getName());
        } else if ("file".equals(kind)) {
            File f = inside(h.optString("to"), h.optString("rel"));
            File parent = f.getParentFile();
            if (parent != null && !parent.isDirectory()) //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            try (OutputStream fo = new FileOutputStream(f)) { pipe(in, fo, h.optLong("size"), null); }
            catch (IOException e) { //noinspection ResultOfMethodCallIgnored
                f.delete(); throw e; }
            if (h.optLong("m") > 0) //noinspection ResultOfMethodCallIgnored
                f.setLastModified(h.optLong("m"));
        } else throw new IOException("no disponible");
        r.put("ok", true);
        writeLine(out, r);
    }

    /** Avance para las dos pantallas: la de este teléfono (oyentes locales) y la del otro. */
    private static void emit(Hub hub, String s, String src, String title, String name, float p, String d, String msg) {
        JSONObject o = new JSONObject();
        try {
            o.put("t", "fmAnim"); o.put("s", s); o.put("src", src);
            if (title != null) o.put("title", title);
            o.put("name", name); o.put("p", (double) p); o.put("d", d);
            if (msg != null) o.put("msg", msg);
        } catch (JSONException ignored) { }
        hub.send(o);
        hub.local(o);
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
            if (h.has("apk")) { AppShare.serve(app, h, out); return; }   // instalar en ambos: APK de una app del principal
            if (!hasAccess(app)) { askAccess(); r.put("ok", false); r.put("err", "permiso"); writeLine(out, r); return; }
            if (h.has("put")) {   // túnel: el otro teléfono manda archivos aquí
                try { receive(h, in, out); }
                catch (IOException e) { r.put("ok", false); r.put("err", e.getMessage() == null ? "No se pudo" : e.getMessage()); writeLine(out, r); }
                return;
            }
            r.put("ok", false); r.put("err", "no disponible"); writeLine(out, r);
        } catch (Exception ignored) { }
    }
}
