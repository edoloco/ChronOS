package cl.mesadoble;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Instalar en ambos: cuando se instala una app nueva en el PRINCIPAL, se ofrece instalarla también en el extendido.
 *   appAdded (principal → extendido) {pkg, label, play, n}: se instaló algo; el extendido pregunta
 *   installApp (principal → extendido) {pkg, label, play, n}: instalar ya (desde el aviso del principal)
 * Si vino de Play Store, se abre su página en el extendido (un toque en "Instalar").
 * Si no, se copian los APK del principal al extendido por el canal de archivos y Android pide confirmar.
 */
final class AppShare {

    private AppShare() { }

    private static final Handler main = new Handler(Looper.getMainLooper());

    /** Archivos de la app: base.apk + partes (split_*.apk). */
    private static File[] files(ApplicationInfo ai) {
        int n = ai.splitSourceDirs == null ? 0 : ai.splitSourceDirs.length;
        File[] f = new File[n + 1];
        f[0] = new File(ai.sourceDir);
        for (int i = 0; i < n; i++) f[i + 1] = new File(ai.splitSourceDirs[i]);
        return f;
    }

    @SuppressWarnings("deprecation")
    static JSONObject info(Context c, String pkg) {
        try {
            PackageManager pm = c.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            String inst = null;
            try { inst = pm.getInstallerPackageName(pkg); } catch (Exception ignored) { }
            JSONObject o = new JSONObject();
            o.put("pkg", pkg);
            o.put("label", String.valueOf(pm.getApplicationLabel(ai)));
            o.put("play", "com.android.vending".equals(inst));
            o.put("n", files(ai).length);
            return o;
        } catch (Exception e) { return null; }
    }

    // ---------------- en el principal ----------------

    /** Se instaló una app nueva aquí (principal). */
    static void added(Context c, Hub hub, String pkg) {
        if (!hub.isPrincipal() || !hub.connected() || pkg.equals(c.getPackageName())) return;
        if (c.getPackageManager().getLaunchIntentForPackage(pkg) == null) return;   // sin ícono: no es una app que se abra
        JSONObject o = info(c, pkg);
        if (o == null) return;
        try { o.put("t", "appAdded"); } catch (Exception ignored) { }
        hub.send(o);
        notifyHere(c, o);
    }

    /** Aviso en el principal con el botón "Instalar también en el extendido". */
    private static void notifyHere(Context c, JSONObject o) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (nm.getNotificationChannel("avisos") == null)
            nm.createNotificationChannel(new NotificationChannel("avisos", "Avisos del teclado", NotificationManager.IMPORTANCE_HIGH));
        String pkg = o.optString("pkg");
        Intent go = new Intent(c, Act.class).putExtra("pkg", pkg);
        PendingIntent pi = PendingIntent.getBroadcast(c, 900 + (pkg.hashCode() & 0xff), go, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        int icon = c.getResources().getIdentifier("ic_stat", "drawable", c.getPackageName());
        Notification n = new Notification.Builder(c, "avisos")
                .setSmallIcon(icon != 0 ? icon : android.R.drawable.stat_sys_download_done)
                .setContentTitle("Se instaló " + o.optString("label"))
                .setContentText("¿Instalarla también en el extendido?")
                .addAction(new Notification.Action.Builder(null, "Instalar en ambos", pi).build())
                .setAutoCancel(true)
                .build();
        try { nm.notify(tag(pkg), n); } catch (Exception ignored) { }
    }

    private static int tag(String pkg) { return 9000 + (pkg.hashCode() & 0x3ff); }

    /** Botón del aviso del principal: pedirle al extendido que la instale. */
    public static class Act extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            String pkg = i.getStringExtra("pkg");
            if (pkg == null) return;
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(tag(pkg));
            Hub hub = Hub.get(c);
            if (!hub.connected()) { Toast.makeText(c, "El extendido no está conectado", Toast.LENGTH_SHORT).show(); return; }
            JSONObject o = info(c, pkg);
            if (o == null) return;
            try { o.put("t", "installApp"); } catch (Exception ignored) { }
            hub.send(o);
            Toast.makeText(c, "Instalando también en el extendido…", Toast.LENGTH_SHORT).show();
        }
    }

    /** Canal de archivos: entrega el APK número i de la app (pedido por el extendido, con la clave). */
    static void serve(Context c, JSONObject h, OutputStream out) throws Exception {
        JSONObject r = new JSONObject();
        File f = null;
        try {
            File[] fs = files(c.getPackageManager().getApplicationInfo(h.optString("apk"), 0));
            int i = h.optInt("i");
            if (i >= 0 && i < fs.length) f = fs[i];
        } catch (Exception ignored) { }
        if (f == null || !f.canRead()) { r.put("ok", false); r.put("err", "No está la app en el principal"); FileServer.writeLine(out, r); return; }
        r.put("ok", true); r.put("name", f.getName()); r.put("s", f.length());
        FileServer.writeLine(out, r);
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
        }
        out.flush();
    }

    // ---------------- en el extendido ----------------

    static boolean installedHere(Context c, String pkg) {
        try { c.getPackageManager().getApplicationInfo(pkg, 0); return true; } catch (Exception e) { return false; }
    }

    /** Instala aquí (extendido) la app que se instaló en el principal. */
    static void install(Context c, Hub hub, JSONObject o) {
        String pkg = o.optString("pkg"), label = o.optString("label");
        if (installedHere(c, pkg)) { toast(c, label + " ya está instalada en el extendido"); return; }
        if (o.optBoolean("play")) {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + pkg)).setPackage("com.android.vending")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (i.resolveActivity(c.getPackageManager()) != null) {
                hub.startExternal(i, "com.android.vending", "Play Store");
                toast(c, "Toca \"Instalar\" en Play Store");
                return;
            }
        }
        if (!c.getPackageManager().canRequestPackageInstalls()) {
            toast(c, "Permite a ChronOS instalar apps y vuelve a intentarlo");
            hub.startOrNotify(Updater.allowInstallIntent(c), 79, "Instalar apps", "Toca y permite a ChronOS instalar apps");
            return;
        }
        String ip = hub.peerIp();
        FileServer fs = FileServer.get(c);
        if (ip == null || fs.remoteToken == null) { toast(c, "El principal no está conectado"); return; }
        toast(c, "Copiando " + label + " desde el principal…");
        RecentApps.worker.execute(() -> {
            try { copyAndInstall(c, ip, fs, pkg, label, Math.max(1, o.optInt("n", 1))); }
            catch (Exception e) { toast(c, "No se pudo copiar " + label + (e.getMessage() != null ? ": " + e.getMessage() : "")); }
        });
    }

    private static void copyAndInstall(Context c, String ip, FileServer fs, String pkg, String label, int parts) throws Exception {
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        sp.setAppPackageName(pkg);
        int id = pi.createSession(sp);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            for (int i = 0; i < parts; i++) {
                try (Socket sock = new Socket()) {
                    sock.connect(new InetSocketAddress(ip, fs.remotePort), 4000);
                    sock.setSoTimeout(30000);
                    OutputStream req = sock.getOutputStream();
                    JSONObject h = new JSONObject();
                    h.put("k", fs.remoteToken); h.put("apk", pkg); h.put("i", i);
                    FileServer.writeLine(req, h);
                    InputStream in = new BufferedInputStream(sock.getInputStream());
                    JSONObject r = new JSONObject(FileServer.readLine(in));
                    if (!r.optBoolean("ok")) throw new Exception(r.optString("err", "el principal no la entregó"));
                    long size = r.optLong("s");
                    String name = r.optString("name", "part" + i + ".apk");
                    if (i > 0 && "base.apk".equals(name)) name = "part" + i + ".apk";
                    try (OutputStream out = s.openWrite(name, 0, size)) {
                        byte[] b = new byte[65536];
                        long left = size;
                        while (left > 0) {
                            int n = in.read(b, 0, (int) Math.min(b.length, left));
                            if (n < 0) throw new Exception("la copia se cortó");
                            out.write(b, 0, n);
                            left -= n;
                        }
                        s.fsync(out);
                    }
                }
            }
            Intent cb = new Intent(c, Done.class).putExtra("label", label);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (android.os.Build.VERSION.SDK_INT >= 31 ? 0x02000000 /* FLAG_MUTABLE */ : 0);
            s.commit(PendingIntent.getBroadcast(c, 95, cb, flags).getIntentSender());
        } catch (Exception e) {
            try { pi.abandonSession(id); } catch (Exception ignored) { }
            throw e;
        }
    }

    /** Respuesta del instalador de Android en el extendido. */
    public static class Done extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            String label = i.getStringExtra("label");
            int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, -999);
            if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    Hub.get(c).startOrNotify(confirm, 96, "Instalar " + label, "Toca para confirmar la instalación");
                }
            } else if (st == PackageInstaller.STATUS_SUCCESS) {
                Toast.makeText(c, label + " quedó instalada en el extendido", Toast.LENGTH_SHORT).show();
                Hub h = Hub.get(c);
                if (h.connected()) h.send("boostMsg", "msg", label + " quedó instalada en los dos teléfonos");
            } else {
                String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                Toast.makeText(c, "No se pudo instalar " + label + (msg != null ? ": " + msg : ""), Toast.LENGTH_LONG).show();
            }
        }
    }

    private static void toast(Context c, String s) { main.post(() -> Toast.makeText(c, s, Toast.LENGTH_LONG).show()); }
}
