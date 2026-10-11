package cl.mesadoble;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
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
        HttpURLConnection h = (HttpURLConnection) new URL("https://api.github.com/repos/" + repo + "/releases/latest").openConnection();
        h.setRequestProperty("Accept", "application/vnd.github+json");
        h.setRequestProperty("User-Agent", "MesaDoble");
        h.setConnectTimeout(10000); h.setReadTimeout(15000);
        int code = h.getResponseCode();
        if (code == 404) throw new IOException("No encontré versiones publicadas en " + repo + " (si el repositorio es privado, la app no puede verlo)");
        if (code != 200) throw new IOException("GitHub respondió " + code + ". Prueba en un rato.");
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (InputStream in = h.getInputStream()) { byte[] buf = new byte[16384]; int n; while ((n = in.read(buf)) > 0) b.write(buf, 0, n); }
        try {
            JSONObject o = new JSONObject(new String(b.toByteArray(), StandardCharsets.UTF_8));
            Release r = new Release();
            r.tag = o.optString("tag_name");
            JSONArray as = o.optJSONArray("assets");
            String want = apkName(c);
            for (int i = 0; as != null && i < as.length(); i++) {
                JSONObject a = as.getJSONObject(i);
                if (want.equals(a.optString("name"))) { r.url = a.optString("browser_download_url"); r.size = a.optLong("size"); r.name = want; }
            }
            if (r.url == null) throw new IOException("La última versión no trae " + want);
            return r;
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
                Ui.Sheet sh = new Ui.Sheet(a).title("Actualizar Mesa Doble");
                if (fe != null) {
                    sh.message("Instalada: " + cur + "\n\n" + fe);
                } else {
                    boolean nw = newer(fr.tag, cur);
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
                    .message("Android pide permitir que Mesa Doble instale apps (solo para instalar sus propias actualizaciones). "
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
                    try { a.startActivity(installIntent(a, f)); }
                    catch (Exception e) { Toast.makeText(a, "No pude abrir el instalador", Toast.LENGTH_LONG).show(); }
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
                    hub.startOrNotify(allowInstallIntent(c), 31, "Mesa Doble quiere actualizarse",
                            "Toca y permite instalar apps; después actualiza desde Herramientas");
                    return;
                }
                Release r = latest(c);
                File f = download(c, r, null);
                hub.startOrNotify(installIntent(c, f), 32, "Actualización de Mesa Doble lista", "Toca para instalar la versión " + clean(r.tag));
            } catch (IOException e) {
                hub.startOrNotify(new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), 33,
                        "No se pudo actualizar", e.getMessage() != null ? e.getMessage() : "Error de descarga");
            }
        }, "actualizar-remoto").start();
    }
}
