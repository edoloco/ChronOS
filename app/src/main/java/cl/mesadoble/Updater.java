package cl.mesadoble;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Actualizar desde la app: busca la última versión publicada en GitHub (Releases), descarga el APK
 * de la misma versión que está instalada (liviana o completa) y abre el instalador de Android.
 */
public class Updater {

    static class Release { String tag, url, name; long size; }

    interface Progress { void on(long done, long total); }

    private static final Handler main = new Handler(Looper.getMainLooper());

    static String repo(Context c) {
        String p = c.getSharedPreferences("mesa", Context.MODE_PRIVATE).getString("repo", "");
        if (!p.isEmpty()) return p;
        int id = c.getResources().getIdentifier("repo", "string", c.getPackageName());
        return id != 0 ? c.getString(id) : "";
    }

    static String installedVersion(Context c) {
        try { return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName; } catch (Exception e) { return "?"; }
    }

    static boolean isLiviana(Context c) { return installedVersion(c).endsWith("-liviana") || !MesaAccess.available(c); }

    private static String apkName(Context c) { return isLiviana(c) ? "MesaDoble-liviana.apk" : "MesaDoble-completa.apk"; }

    /** "v1.261011.1704" y "1.261011.1704-liviana" → "1.261011.1704". */
    static String clean(String v) {
        if (v == null) return "";
        if (v.startsWith("v")) v = v.substring(1);
        int d = v.indexOf('-');
        return d > 0 ? v.substring(0, d) : v;
    }

    static boolean newer(String latest, String installed) {
        String[] a = clean(latest).split("\\."), b = clean(installed).split("\\.");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            long x = i < a.length ? parse(a[i]) : 0, y = i < b.length ? parse(b[i]) : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    private static long parse(String s) { try { return Long.parseLong(s); } catch (Exception e) { return 0; } }

    static Release latest(Context c) throws IOException {
        String repo = repo(c);
        if (repo.isEmpty()) throw new IOException("Esta versión no sabe de qué repositorio viene. Escribe el repositorio en Actualizar → Repositorio.");
        // se revisan las últimas versiones publicadas y se elige la de número MÁS ALTO
        // (no la marcada "Latest": si dos compilaciones terminan desordenadas, esa puede ser una más vieja)
        HttpURLConnection h = (HttpURLConnection) new URL("https://api.github.com/repos/" + repo + "/releases?per_page=30").openConnection();
        h.setRequestProperty("Accept", "application/vnd.github+json");
        h.setRequestProperty("User-Agent", "MesaDoble");
        h.setConnectTimeout(10000); h.setReadTimeout(15000);
        int code = h.getResponseCode();
        if (code == 404) throw new IOException("No encontré el repositorio " + repo + " (si es privado, la app no puede verlo)");
        if (code != 200) throw new IOException("GitHub respondió " + code + ". Prueba en un rato.");
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (InputStream in = h.getInputStream()) { byte[] buf = new byte[16384]; int n; while ((n = in.read(buf)) > 0) b.write(buf, 0, n); }
        try {
            JSONArray rels = new JSONArray(new String(b.toByteArray(), StandardCharsets.UTF_8));
            String want = apkName(c);
            Release best = null;
            JSONArray notes = new JSONArray();
            for (int k = 0; k < rels.length(); k++) {
                JSONObject o = rels.getJSONObject(k);
                if (o.optBoolean("draft") || o.optBoolean("prerelease")) continue;
                if (notes.length() < 25) {
                    JSONObject n = new JSONObject();
                    n.put("tag", o.optString("tag_name")); n.put("body", o.optString("body")); n.put("date", o.optString("published_at"));
                    notes.put(n);
                }
                Release r = new Release();
                r.tag = o.optString("tag_name");
                JSONArray as = o.optJSONArray("assets");
                for (int i = 0; as != null && i < as.length(); i++) {
                    JSONObject a = as.getJSONObject(i);
                    if (want.equals(a.optString("name"))) { r.url = a.optString("browser_download_url"); r.size = a.optLong("size"); r.name = want; }
                }
                if (r.url == null) continue;
                if (best == null || newer(r.tag, best.tag)) best = r;
            }
            c.getSharedPreferences("mesa", Context.MODE_PRIVATE).edit().putString("relNotes", notes.toString()).apply();
            if (best == null) throw new IOException("No encontré versiones publicadas con " + want);
            return best;
        } catch (org.json.JSONException e) { throw new IOException("Respuesta inválida de GitHub"); }
    }

    static File download(Context c, Release r, Progress p) throws IOException {
        File dir = new File(c.getCacheDir(), "actualizar");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File f = new File(dir, "MesaDoble-" + clean(r.tag) + ".apk");
        URL u = new URL(r.url);
        for (int hop = 0; hop < 5; hop++) {   // GitHub redirige al servidor de descargas
            HttpURLConnection h = (HttpURLConnection) u.openConnection();
            h.setInstanceFollowRedirects(false);
            h.setRequestProperty("User-Agent", "MesaDoble");
            h.setConnectTimeout(10000); h.setReadTimeout(30000);
            int code = h.getResponseCode();
            if (code >= 300 && code < 400) { u = new URL(u, h.getHeaderField("Location")); continue; }
            if (code != 200) throw new IOException("La descarga respondió " + code);
            long total = h.getContentLengthLong() > 0 ? h.getContentLengthLong() : r.size;
            try (InputStream in = h.getInputStream(); OutputStream out = new FileOutputStream(f)) {
                byte[] buf = new byte[65536];
                long done = 0, last = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    done += n;
                    long now = System.currentTimeMillis();
                    if (p != null && now - last > 150) { last = now; p.on(done, total); }
                }
            }
            return f;
        }
        throw new IOException("Demasiadas redirecciones");
    }

    /** ¿El archivo descargado es un APK completo de ChronOS? (si se cortó la descarga, no lo es) */
    static String check(Context c, File apk, long expected) {
        if (!apk.isFile() || apk.length() == 0) return "La descarga quedó vacía";
        if (expected > 0 && apk.length() != expected) return "La descarga quedó incompleta (" + apk.length() + " de " + expected + " bytes)";
        android.content.pm.PackageInfo pi = c.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), 0);
        if (pi == null) return "El archivo descargado no es un APK válido";
        if (!c.getPackageName().equals(pi.packageName)) return "El archivo no es de ChronOS";
        return null;
    }

    /**
     * Instala con el instalador de paquetes de Android (más confiable que abrir el APK, sobre todo en Xiaomi):
     * Android muestra su ventana de "¿Actualizar esta app?" y se confirma ahí.
     */
    static void installSession(Context c, File apk) throws IOException {
        android.content.pm.PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        android.content.pm.PackageInstaller.SessionParams sp = new android.content.pm.PackageInstaller.SessionParams(
                android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        sp.setAppPackageName(c.getPackageName());
        int id = pi.createSession(sp);
        try (android.content.pm.PackageInstaller.Session s = pi.openSession(id)) {
            try (java.io.InputStream in = new java.io.FileInputStream(apk); OutputStream out = s.openWrite("base.apk", 0, apk.length())) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                s.fsync(out);
            }
            Intent cb = new Intent(c, InstallReceiver.class);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (android.os.Build.VERSION.SDK_INT >= 31 ? 0x02000000 /* FLAG_MUTABLE */ : 0);
            s.commit(PendingIntent.getBroadcast(c, 77, cb, flags).getIntentSender());
        }
    }

    /** Respuesta del instalador: pedir confirmación al usuario o avisar si falló. */
    public static class InstallReceiver extends android.content.BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            int st = i.getIntExtra(android.content.pm.PackageInstaller.EXTRA_STATUS, -999);
            if (st == android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    Hub.get(c).startOrNotify(confirm, 78, "Actualizar ChronOS", "Toca para confirmar la actualización");
                }
            } else if (st != android.content.pm.PackageInstaller.STATUS_SUCCESS) {
                String msg = i.getStringExtra(android.content.pm.PackageInstaller.EXTRA_STATUS_MESSAGE);
                Toast.makeText(c, "No se pudo actualizar" + (msg != null ? ": " + msg : ""), Toast.LENGTH_LONG).show();
            }
        }
    }

    /** Instala el APK descargado: primero con el instalador de paquetes; si no se puede, abriendo el archivo. */
    static void install(Context c, File apk) {
        try { installSession(c, apk); }
        catch (Exception e) { Hub.get(c).startOrNotify(installIntent(c, apk), 32, "Actualización de ChronOS lista", "Toca para instalarla"); }
    }

    static Intent installIntent(Context c, File apk) {
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(FilesProvider.uriFor(c, apk), "application/vnd.android.package-archive");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }

    static boolean canInstall(Context c) { return c.getPackageManager().canRequestPackageInstalls(); }

    static Intent allowInstallIntent(Context c) {
        return new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + c.getPackageName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    // ---------------- aviso de versión nueva ----------------

    /** ¿Ya se sabe que hay una versión publicada más nueva que la instalada? */
    static boolean updateAvailable(Context c) {
        String t = c.getSharedPreferences("mesa", Context.MODE_PRIVATE).getString("latestTag", "");
        return !t.isEmpty() && newer(t, installedVersion(c));
    }

    static String latestKnown(Context c) { return clean(c.getSharedPreferences("mesa", Context.MODE_PRIVATE).getString("latestTag", "")); }

    /** Revisa en silencio (como mucho cada 10 minutos, o ya si force) y avisa en el hilo principal. */
    static void checkQuiet(Context c, boolean force, Runnable done) {
        android.content.SharedPreferences p = c.getSharedPreferences("mesa", Context.MODE_PRIVATE);
        if (!force && System.currentTimeMillis() - p.getLong("latestAt", 0) < 10 * 60_000L) { main.post(done); return; }
        Context app = c.getApplicationContext();
        new Thread(() -> {
            try {
                Release r = latest(app);
                p.edit().putString("latestTag", r.tag).putLong("latestAt", System.currentTimeMillis()).apply();
            } catch (Exception ignored) { }
            main.post(done);
        }, "buscar-version").start();
    }

    // ---------------- novedades de cada versión ----------------

    /** Lo que trae una versión, en puntos (sacado de la descripción publicada). */
    static java.util.List<String> changes(String body) {
        java.util.List<String> out = new java.util.ArrayList<>();
        String first = body == null ? "" : body.split("\n", 2)[0];
        int i = first.indexOf("Incluye:");
        String list = i >= 0 ? first.substring(i + 8).trim() : first.trim();
        for (String part : list.split(";\\s*")) {
            String t = part.trim();
            if (t.endsWith(".")) t = t.substring(0, t.length() - 1);
            if (!t.isEmpty()) out.add(Character.toUpperCase(t.charAt(0)) + t.substring(1));
        }
        return out;
    }

    /** Novedades guardadas: [{tag, body, date}], de la más nueva a la más antigua. */
    static JSONArray notes(Context c) {
        try { return new JSONArray(c.getSharedPreferences("mesa", Context.MODE_PRIVATE).getString("relNotes", "[]")); }
        catch (org.json.JSONException e) { return new JSONArray(); }
    }

    /** Texto de novedades: la versión instalada primero (y las más nuevas si hay), luego las anteriores. */
    static String notesText(Context c, int max) {
        JSONArray a = notes(c);
        String cur = clean(installedVersion(c));
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < a.length() && k < max; k++) {
            JSONObject o = a.optJSONObject(k);
            if (o == null) continue;
            String v = clean(o.optString("tag"));
            String date = o.optString("date");
            if (date.length() >= 16) date = date.substring(8, 10) + "/" + date.substring(5, 7) + " " + date.substring(11, 16) + " UTC";
            sb.append(v.equals(cur) ? "▶ " : newer(v, cur) ? "★ " : "• ").append(v)
              .append(v.equals(cur) ? "  (instalada)" : newer(v, cur) ? "  (nueva, sin instalar)" : "").append("   ").append(date).append("\n");
            for (String ch : changes(o.optString("body"))) sb.append("     – ").append(ch).append("\n");
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    // ---------------- en pantalla ----------------

    /** Herramientas → Actualizar: revisa, muestra la versión y ofrece actualizar este teléfono o ambos. */
    static void check(Activity a, Hub hub) {
        Dialog wait = new Ui.Sheet(a).title("Buscando actualización…").message("Revisando la última versión publicada.").show();
        new Thread(() -> {
            Release r = null; String err = null;
            try { r = latest(a); } catch (IOException e) { err = e.getMessage(); }
            final Release fr = r; final String fe = err;
            main.post(() -> {
                try { wait.dismiss(); } catch (Exception ignored) { }
                if (a.isFinishing()) return;
                String cur = clean(installedVersion(a));
                Ui.Sheet sh = new Ui.Sheet(a).title("Actualizar ChronOS");
                if (fe != null) {
                    sh.message("Instalada: " + cur + "\n\n" + fe);
                } else {
                    boolean nw = newer(fr.tag, cur);
                    a.getSharedPreferences("mesa", Context.MODE_PRIVATE).edit().putString("latestTag", fr.tag).putLong("latestAt", System.currentTimeMillis()).apply();
                    sh.message("Instalada: " + cur + "\nÚltima: " + clean(fr.tag) + " (" + fr.name + ")\n\n"
                            + (nw ? "Hay una versión nueva." : "Ya tienes la última versión. Puedes reinstalarla igual."));
                    sh.option(Ui.RECEIVE, -1, nw ? "Actualizar este teléfono" : "Reinstalar en este teléfono", null, nw, () -> install(a, fr));
                    if (hub.connected())
                        sh.option(Ui.OPEN, "Actualizar también el otro teléfono", "Allá se descarga y aparece el instalador (o un aviso)", () -> {
                            hub.send("update");
                            install(a, fr);
                        });
                }
                sh.option(Ui.GEAR, "Repositorio: " + (repo(a).isEmpty() ? "(sin definir)" : repo(a)), "Cambiar de dónde se descargan las versiones", () -> editRepo(a, hub))
                  .button("Cerrar", false, null)
                  .show();
            });
        }, "actualizar").start();
    }

    private static void editRepo(Activity a, Hub hub) {
        android.widget.EditText e = new android.widget.EditText(a);
        e.setText(repo(a));
        e.setHint("usuario/mesa-doble");
        e.setSingleLine(true);
        new Ui.Sheet(a).title("Repositorio de GitHub").message("El que tiene las versiones en Releases, como usuario/mesa-doble.").view(e)
                .button("Cancelar", false, null)
                .button("Guardar", true, () -> {
                    a.getSharedPreferences("mesa", Context.MODE_PRIVATE).edit().putString("repo", e.getText().toString().trim()).apply();
                    check(a, hub);
                })
                .show();
    }

    private static void install(Activity a, Release r) {
        if (!canInstall(a)) {
            new Ui.Sheet(a).title("Permitir instalar")
                    .message("Android pide permitir que ChronOS instale apps (solo para instalar sus propias actualizaciones). "
                            + "Actívalo, vuelve y toca Actualizar otra vez.")
                    .button("Cancelar", false, null)
                    .button("Abrir ajustes", true, () -> { try { a.startActivity(allowInstallIntent(a)); } catch (Exception ignored) { } })
                    .show();
            return;
        }
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(a);
        t.setText("Descargando " + clean(r.tag) + "…");
        t.setTextColor(Ui.night(a) ? 0xFFE6EDF3 : 0xFF1D2733);
        box.addView(t);
        ProgressBar pb = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
        pb.setMax(1000);
        box.addView(pb, new LinearLayout.LayoutParams(-1, -2));
        Dialog d = new Ui.Sheet(a).title("Actualizando").view(box).cancelable(false).show();
        new Thread(() -> {
            try {
                File f = download(a, r, (done, total) -> main.post(() -> {
                    if (total > 0) pb.setProgress((int) (done * 1000 / total));
                    t.setText("Descargando " + clean(r.tag) + " · " + android.text.format.Formatter.formatShortFileSize(a, done)
                            + (total > 0 ? " de " + android.text.format.Formatter.formatShortFileSize(a, total) : ""));
                }));
                main.post(() -> {
                    try { d.dismiss(); } catch (Exception ignored) { }
                    String bad = check(a, f, r.size);
                    if (bad != null) {
                        new Ui.Sheet(a).title("No se pudo actualizar").message(bad + ". Prueba otra vez o descárgala desde la página de versiones.")
                                .button("Cerrar", false, null)
                                .button("Abrir versiones", true, () -> { try { a.startActivity(new Intent(Intent.ACTION_VIEW,
                                        Uri.parse("https://github.com/" + repo(a) + "/releases"))); } catch (Exception ignored) { } })
                                .show();
                        return;
                    }
                    install(a, f);
                });
            } catch (IOException e) {
                main.post(() -> {
                    try { d.dismiss(); } catch (Exception ignored) { }
                    Toast.makeText(a, "No se pudo descargar: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }, "descarga").start();
    }

    /** Pedido desde el otro teléfono: descarga en segundo plano y deja el instalador (o un aviso) listo. */
    static void updateFromRemote(Context c, Hub hub) {
        new Thread(() -> {
            try {
                if (!canInstall(c)) {
                    hub.startOrNotify(allowInstallIntent(c), 31, "ChronOS quiere actualizarse",
                            "Toca y permite instalar apps; después actualiza desde Herramientas");
                    return;
                }
                Release r = latest(c);
                File f = download(c, r, null);
                String bad = check(c, f, r.size);
                if (bad != null) throw new IOException(bad);
                install(c, f);
            } catch (IOException e) {
                hub.startOrNotify(new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), 33,
                        "No se pudo actualizar", e.getMessage() != null ? e.getMessage() : "Error de descarga");
            }
        }, "actualizar-remoto").start();
    }
}
