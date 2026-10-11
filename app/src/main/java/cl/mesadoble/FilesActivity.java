package cl.mesadoble;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.text.Collator;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Gestor de archivos de dos ventanas, como Total Commander: arriba el PRINCIPAL y abajo el EXTENDIDO
 * (cada ventana se puede cambiar al otro teléfono). Se copian o mueven archivos de una ventana a la otra.
 */
public class FilesActivity extends Activity implements Link.Listener {

    static class Entry { String name; boolean dir; long size, mtime; }

    private class Panel {
        boolean principal;            // qué teléfono muestra
        String path = "", root = "";
        String error;
        final List<Entry> entries = new ArrayList<>();
        final Set<String> sel = new HashSet<>();
        LinearLayout box;
        TextView phone, pathText, info;
        BaseAdapter adapter;
        boolean remote() { return principal != hub.isPrincipal(); }
        String name() { return principal ? "PRINCIPAL" : "EXTENDIDO"; }
    }

    private static class Wait { final CountDownLatch l = new CountDownLatch(1); JSONObject r; }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private final Map<Integer, Wait> waits = new ConcurrentHashMap<>();
    private final AtomicInteger seq = new AtomicInteger();
    private Hub hub;
    private boolean night, showHidden;
    private int fg, muted, accent, cardBg;
    private Panel top, bottom, active;
    private TextView copyBtn, moveBtn;
    private volatile boolean cancelled;

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    @Override protected void onCreate(Bundle b) {
        night = Ui.night(this);
        setTheme(night ? android.R.style.Theme_Material_NoActionBar : android.R.style.Theme_Material_Light_NoActionBar);
        super.onCreate(b);
        MainActivity.applyOrientation(this);
        hub = Hub.get(this);
        hub.ensureStarted();
        fg = night ? 0xFFE6EDF3 : 0xFF1D2733;
        muted = night ? 0xFF93A4B5 : 0xFF5B6B7A;
        accent = night ? 0xFF5CC3CF : 0xFF1F7F8C;
        cardBg = night ? 0xFF1E2934 : 0xFFF4F7F9;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(night ? 0xFF121920 : 0xFFCDD5DC);
        root.setPadding(dp(8), dp(8), dp(8), dp(8));

        top = new Panel(); top.principal = true;
        bottom = new Panel(); bottom.principal = false;
        active = top;
        root.addView(buildPanel(top), new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(buildActions(), new LinearLayout.LayoutParams(-1, -2));
        root.addView(buildPanel(bottom), new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        hub.add(this);
        refreshActive();

        if (!FileServer.hasAccess(this)) askAccess();
        else { load(top); load(bottom); }
    }

    @Override protected void onResume() {
        super.onResume();
        if (FileServer.hasAccess(this) && (top.error != null || bottom.error != null)) { load(top); load(bottom); }
    }

    @Override protected void onDestroy() {
        hub.remove(this);
        cancelled = true;
        work.shutdownNow();
        super.onDestroy();
    }

    private void askAccess() {
        if (Build.VERSION.SDK_INT < 30) {
            requestPermissions(new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE, android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 41);
            return;
        }
        new Ui.Sheet(this)
                .title("Acceso a los archivos")
                .message("Para ver y pasar archivos, Android pide activar \"Permitir acceso a todos los archivos\" para Mesa Doble. "
                        + "Hazlo también en el otro teléfono (allá aparecerá un aviso la primera vez).")
                .button("Después", false, null)
                .button("Activar", true, () -> {
                    try { startActivity(FileServer.accessIntent(this)); } catch (Exception e) { Toast.makeText(this, "No pude abrir ese permiso", Toast.LENGTH_SHORT).show(); }
                })
                .show();
    }

    @Override public void onRequestPermissionsResult(int req, String[] p, int[] r) {
        super.onRequestPermissionsResult(req, p, r);
        load(top); load(bottom);
    }

    // ---------------- interfaz ----------------

    private GradientDrawable round(int color, float r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(r));
        return g;
    }

    private TextView chip(String text, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(14);
        t.setTextColor(fg);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(14), 0, dp(14), 0);
        t.setBackground(round(night ? 0xFF2A3848 : 0xFFFFFFFF, 12));
        t.setOnClickListener(l);
        return t;
    }

    private View buildPanel(Panel p) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(4), dp(4), dp(4), dp(4));
        p.box = box;

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        p.phone = chip("", v -> { p.principal = !p.principal; p.path = ""; p.sel.clear(); setActive(p); load(p); });
        p.phone.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        head.addView(p.phone, new LinearLayout.LayoutParams(-2, dp(36)));
        TextView up = chip("↑", v -> { setActive(p); goUp(p); });
        LinearLayout.LayoutParams ul = new LinearLayout.LayoutParams(dp(44), dp(36));
        ul.setMarginStart(dp(6));
        head.addView(up, ul);
        p.pathText = new TextView(this);
        p.pathText.setTextColor(fg);
        p.pathText.setTextSize(13);
        p.pathText.setSingleLine(true);
        p.pathText.setEllipsize(TextUtils.TruncateAt.START);
        p.pathText.setPadding(dp(10), 0, dp(6), 0);
        head.addView(p.pathText, new LinearLayout.LayoutParams(0, -2, 1));
        p.info = new TextView(this);
        p.info.setTextColor(muted);
        p.info.setTextSize(12);
        p.info.setPadding(0, 0, dp(6), 0);
        head.addView(p.info);
        box.addView(head, new LinearLayout.LayoutParams(-1, -2));

        ListView list = new ListView(this);
        list.setDivider(null);
        list.setFastScrollEnabled(true);
        p.adapter = new BaseAdapter() {
            @Override public int getCount() { return p.error != null ? 1 : p.entries.size(); }
            @Override public Object getItem(int i) { return i; }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View v, ViewGroup parent) {
                LinearLayout row = (LinearLayout) v;
                if (row == null) {
                    row = new LinearLayout(FilesActivity.this);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(dp(8), dp(7), dp(8), dp(7));
                    TextView ic = new TextView(FilesActivity.this);
                    ic.setTextSize(20);
                    ic.setGravity(Gravity.CENTER);
                    row.addView(ic, new LinearLayout.LayoutParams(dp(36), -2));
                    LinearLayout col = new LinearLayout(FilesActivity.this);
                    col.setOrientation(LinearLayout.VERTICAL);
                    col.setPadding(dp(8), 0, 0, 0);
                    TextView t1 = new TextView(FilesActivity.this); t1.setTextSize(15); t1.setSingleLine(true); t1.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                    TextView t2 = new TextView(FilesActivity.this); t2.setTextSize(12);
                    col.addView(t1); col.addView(t2);
                    row.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
                }
                TextView ic = (TextView) row.getChildAt(0);
                LinearLayout col = (LinearLayout) row.getChildAt(1);
                TextView t1 = (TextView) col.getChildAt(0), t2 = (TextView) col.getChildAt(1);
                t1.setTextColor(fg); t2.setTextColor(muted);
                if (p.error != null) {
                    ic.setText("⚠"); t1.setText(p.error); t1.setSingleLine(false); t2.setText("Toca para reintentar");
                    row.setBackground(null);
                    return row;
                }
                t1.setSingleLine(true);
                Entry e = p.entries.get(i);
                ic.setText(e.dir ? "📁" : icon(e.name));
                t1.setText(e.name);
                String date = e.mtime > 0 ? DateFormat.getDateInstance(DateFormat.MEDIUM).format(new Date(e.mtime)) : "";
                t2.setText(e.dir ? date : Formatter.formatShortFileSize(FilesActivity.this, e.size) + " · " + date);
                boolean s = p.sel.contains(e.name);
                row.setBackground(s ? round((accent & 0x00FFFFFF) | 0x40000000, 10) : null);
                return row;
            }
        };
        list.setAdapter(p.adapter);
        list.setOnItemClickListener((parent, view, pos, id) -> {
            setActive(p);
            if (p.error != null) { load(p); return; }
            Entry e = p.entries.get(pos);
            if (!p.sel.isEmpty()) { toggle(p, e); return; }
            if (e.dir) { p.path = child(p.path.isEmpty() ? p.root : p.path, e.name); p.sel.clear(); load(p); }
            else openFile(p, e);
        });
        list.setOnItemLongClickListener((parent, view, pos, id) -> {
            setActive(p);
            if (p.error == null) toggle(p, p.entries.get(pos));
            return true;
        });
        list.setOnTouchListener((v, ev) -> { if (active != p) setActive(p); return false; });
        box.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        return box;
    }

    private static String icon(String n) {
        String t = FilesProvider.mime(n);
        if (t.startsWith("image/")) return "🖼";
        if (t.startsWith("video/")) return "🎬";
        if (t.startsWith("audio/")) return "🎵";
        if (t.contains("pdf")) return "📕";
        if (n.toLowerCase(Locale.ROOT).endsWith(".apk")) return "📦";
        if (t.contains("zip") || t.contains("compressed") || t.contains("rar")) return "🗜";
        return "📄";
    }

    private View buildActions() {
        HorizontalScrollView sc = new HorizontalScrollView(this);
        sc.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4), dp(6), dp(4), dp(6));
        copyBtn = chip("Copiar ↓", v -> transfer(false));
        moveBtn = chip("Mover ↓", v -> transfer(true));
        copyBtn.setTextColor(night ? 0xFF18212B : 0xFFFFFFFF);
        copyBtn.setBackground(round(accent, 12));
        View[] all = {copyBtn, moveBtn, chip("Borrar", v -> deleteSel()), chip("Renombrar", v -> renameSel()),
                chip("Nueva carpeta", v -> newFolder()), chip("Todo", v -> selectAll()),
                chip("Ocultos", v -> { showHidden = !showHidden; load(top); load(bottom); }),
                chip("Cerrar", v -> finish())};
        for (View v : all) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(40));
            lp.setMarginEnd(dp(6));
            row.addView(v, lp);
        }
        sc.addView(row);
        return sc;
    }

    private void setActive(Panel p) { active = p; refreshActive(); }

    private void refreshActive() {
        for (Panel p : new Panel[]{top, bottom}) {
            GradientDrawable g = round(cardBg, 16);
            if (p == active) g.setStroke(dp(2), accent);
            p.box.setBackground(g);
            boolean here = !p.remote();
            p.phone.setText(p.name() + (here ? " · este" : "") + "  ⇅");
            p.phone.setTextColor(p.principal ? accent : fg);
        }
        String arrow = active == top ? "↓" : "↑";
        copyBtn.setText("Copiar " + arrow);
        moveBtn.setText("Mover " + arrow);
    }

    private void toggle(Panel p, Entry e) {
        if (!p.sel.remove(e.name)) p.sel.add(e.name);
        p.adapter.notifyDataSetChanged();
        updateInfo(p);
    }

    private void selectAll() {
        Panel p = active;
        if (p.sel.size() == p.entries.size()) p.sel.clear();
        else for (Entry e : p.entries) p.sel.add(e.name);
        p.adapter.notifyDataSetChanged();
        updateInfo(p);
    }

    private void updateInfo(Panel p) {
        p.info.setText(!p.sel.isEmpty() ? p.sel.size() + " elegidos" : p.error == null ? p.entries.size() + "" : "");
        String shown = p.path.isEmpty() ? p.root : p.path;
        if (!p.root.isEmpty() && shown.startsWith(p.root)) shown = "Almacenamiento" + shown.substring(p.root.length());
        p.pathText.setText(shown);
    }

    private static String child(String dir, String name) { return dir.endsWith("/") ? dir + name : dir + "/" + name; }
    private static String nameOf(String path) { int i = path.lastIndexOf('/'); return i >= 0 ? path.substring(i + 1) : path; }
    private String dirOf(Panel p) { return p.path.isEmpty() ? p.root : p.path; }

    private void goUp(Panel p) {
        String cur = dirOf(p);
        if (cur.isEmpty() || cur.equals(p.root)) return;
        int i = cur.lastIndexOf('/');
        p.path = i > 0 ? cur.substring(0, i) : p.root;
        p.sel.clear();
        load(p);
    }

    // ---------------- cargar listas ----------------

    private void load(Panel p) {
        final boolean remote = p.remote();
        final String path = p.path;
        p.info.setText("…");
        work.execute(() -> {
            List<Entry> out = new ArrayList<>();
            String err = null, resolved = path, root = "";
            try {
                JSONArray arr;
                if (!remote) {
                    if (!FileServer.hasAccess(this)) throw new IOException("Falta el permiso de archivos en este teléfono");
                    File d = FileServer.resolve(path);
                    resolved = d.getAbsolutePath();
                    root = FileServer.root().getAbsolutePath();
                    arr = FileServer.list(d);
                } else {
                    JSONObject r = request("fsList", "path", path);
                    if (r.has("err")) throw new IOException(r.optString("err"));
                    resolved = r.optString("path");
                    root = r.optString("root");
                    arr = r.optJSONArray("entries");
                }
                for (int i = 0; arr != null && i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    Entry e = new Entry();
                    e.name = o.optString("n"); e.dir = o.optBoolean("d"); e.size = o.optLong("s"); e.mtime = o.optLong("m");
                    if (!showHidden && e.name.startsWith(".")) continue;
                    out.add(e);
                }
                Collator col = Collator.getInstance(new Locale("es"));
                col.setStrength(Collator.PRIMARY);
                Collections.sort(out, (a, b) -> a.dir != b.dir ? (a.dir ? -1 : 1) : col.compare(a.name, b.name));
            } catch (Exception e) {
                err = e.getMessage() != null ? e.getMessage() : "No pude leer la carpeta";
            }
            final String fe = err, fr = resolved, fro = root;
            main.post(() -> {
                if (remote != p.remote()) return;   // se cambió de teléfono mientras cargaba
                p.error = fe;
                p.entries.clear();
                if (fe == null) { p.entries.addAll(out); p.path = fr; p.root = fro; }
                p.sel.retainAll(names(p));
                p.adapter.notifyDataSetChanged();
                updateInfo(p);
            });
        });
    }

    private Set<String> names(Panel p) {
        Set<String> s = new HashSet<>();
        for (Entry e : p.entries) s.add(e.name);
        return s;
    }

    // ---------------- pedidos al otro teléfono ----------------

    private JSONObject request(String t, Object... kv) throws IOException {
        if (!hub.connected()) throw new IOException("El otro teléfono no está conectado");
        int id = seq.incrementAndGet();
        JSONObject m = new JSONObject();
        try {
            m.put("t", t); m.put("req", id);
            for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        } catch (JSONException ignored) { }
        Wait w = new Wait();
        waits.put(id, w);
        hub.send(m);
        try {
            if (!w.l.await(25, TimeUnit.SECONDS)) throw new IOException("El otro teléfono no responde");
        } catch (InterruptedException e) { throw new IOException("Cancelado"); }
        finally { waits.remove(id); }
        if ("permiso".equals(w.r.optString("err")))
            throw new IOException("Falta el permiso de archivos en el otro teléfono: toca el aviso que apareció allá");
        return w.r;
    }

    private JSONObject remoteOp(String op, Object... kv) throws IOException {
        Object[] all = new Object[kv.length + 2];
        all[0] = "op"; all[1] = op;
        System.arraycopy(kv, 0, all, 2, kv.length);
        JSONObject r = request("fsOp", all);
        if (!r.optBoolean("ok")) throw new IOException(r.optString("err", "No se pudo"));
        return r;
    }

    @Override public void onState(int state, String info) {
        if (state == Link.CONNECTED) main.postDelayed(() -> {
            if (top.remote()) load(top);
            if (bottom.remote()) load(bottom);
        }, 800);
    }

    @Override public void onMessage(JSONObject m) {
        String t = m.optString("t");
        if ("fsListR".equals(t) || "fsOpR".equals(t)) {
            Wait w = waits.get(m.optInt("req"));
            if (w != null) { w.r = m; w.l.countDown(); }
        }
    }

    // ---------------- canal de contenido ----------------

    private Socket dataSocket() throws IOException {
        FileServer fs = FileServer.get(this);
        String ip = hub.link.peerIp();
        if (ip == null || fs.remoteToken == null) throw new IOException("Reconecta los teléfonos para pasar archivos");
        Socket s = new Socket();
        s.connect(new InetSocketAddress(ip, fs.remotePort), 6000);
        s.setSoTimeout(60000);
        return s;
    }

    private void download(String remotePath, File dst, FileServer.Progress pr) throws IOException {
        try (Socket s = dataSocket()) {
            InputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = new BufferedOutputStream(s.getOutputStream());
            JSONObject h = new JSONObject();
            try { h.put("k", FileServer.get(this).remoteToken); h.put("op", "get"); h.put("path", remotePath); } catch (JSONException ignored) { }
            FileServer.writeLine(out, h);
            JSONObject r = new JSONObject(FileServer.readLine(in));
            if (!r.optBoolean("ok")) throw new IOException("permiso".equals(r.optString("err"))
                    ? "Falta el permiso de archivos en el otro teléfono" : r.optString("err", "No se pudo leer"));
            File part = new File(dst.getParentFile(), "." + dst.getName() + ".parte");
            boolean ok = false;
            try (OutputStream fo = new FileOutputStream(part)) { FileServer.pipe(in, fo, r.optLong("size"), pr); ok = true; }
            finally { if (!ok) //noinspection ResultOfMethodCallIgnored
                part.delete(); }
            if (!part.renameTo(dst)) throw new IOException("No pude guardar " + dst.getName());
            if (r.has("m")) //noinspection ResultOfMethodCallIgnored
                dst.setLastModified(r.optLong("m"));
        } catch (JSONException e) { throw new IOException("Respuesta inválida"); }
    }

    private void upload(File src, String remoteDir, FileServer.Progress pr) throws IOException {
        try (Socket s = dataSocket()) {
            InputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = new BufferedOutputStream(s.getOutputStream());
            JSONObject h = new JSONObject();
            try {
                h.put("k", FileServer.get(this).remoteToken); h.put("op", "put"); h.put("path", remoteDir);
                h.put("name", src.getName()); h.put("size", src.length()); h.put("m", src.lastModified());
            } catch (JSONException ignored) { }
            FileServer.writeLine(out, h);
            try (InputStream fi = new FileInputStream(src)) { FileServer.pipe(fi, out, src.length(), pr); }
            JSONObject r = new JSONObject(FileServer.readLine(in));
            if (!r.optBoolean("ok")) throw new IOException(r.optString("err", "No se pudo guardar"));
        } catch (JSONException e) { throw new IOException("Respuesta inválida"); }
    }

    // ---------------- progreso ----------------

    private Dialog progressDlg;
    private TextView progressText;
    private ProgressBar progressBar;

    private void showProgress(String title) {
        cancelled = false;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        progressText = new TextView(this);
        progressText.setTextColor(fg);
        progressText.setTextSize(14);
        progressText.setSingleLine(true);
        progressText.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        box.addView(progressText);
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(1000);
        box.addView(progressBar, new LinearLayout.LayoutParams(-1, dp(24)));
        progressDlg = new Ui.Sheet(this).title(title).view(box).cancelable(false)
                .button("Cancelar", false, () -> cancelled = true).show();
    }

    private void progress(String text, long done, long total) {
        main.post(() -> {
            if (progressText == null) return;
            progressText.setText(text);
            progressBar.setIndeterminate(total <= 0);
            if (total > 0) progressBar.setProgress((int) (done * 1000 / total));
        });
    }

    private void endProgress(String msg) {
        main.post(() -> {
            if (progressDlg != null) try { progressDlg.dismiss(); } catch (Exception ignored) { }
            progressDlg = null; progressText = null;
            if (msg != null) Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
            top.sel.clear(); bottom.sel.clear();
            load(top); load(bottom);
        });
    }

    private FileServer.Progress counter(String name, long size) {
        final long[] done = {0};
        final long[] last = {0};
        return n -> {
            done[0] += n;
            long now = System.currentTimeMillis();
            if (now - last[0] > 120 || done[0] >= size) { last[0] = now; progress(name, done[0], size); }
            return !cancelled;
        };
    }

    // ---------------- operaciones ----------------

    private List<Entry> selected(Panel p) {
        List<Entry> l = new ArrayList<>();
        for (Entry e : p.entries) if (p.sel.contains(e.name)) l.add(e);
        return l;
    }

    private void transfer(boolean move) {
        Panel from = active, to = active == top ? bottom : top;
        List<Entry> items = selected(from);
        if (items.isEmpty()) { Toast.makeText(this, "Mantén presionado un archivo o carpeta para elegirlo", Toast.LENGTH_SHORT).show(); return; }
        if (to.error != null) { Toast.makeText(this, "La otra ventana no está lista", Toast.LENGTH_SHORT).show(); return; }
        final boolean fr = from.remote(), tr = to.remote();
        final String srcDir = dirOf(from), dstDir = dirOf(to);
        if (fr == tr && srcDir.equals(dstDir) && move) { Toast.makeText(this, "Es la misma carpeta", Toast.LENGTH_SHORT).show(); return; }
        showProgress((move ? "Moviendo " : "Copiando ") + items.size() + (items.size() == 1 ? " elemento" : " elementos")
                + " al " + to.name().toLowerCase(Locale.ROOT));
        work.execute(() -> {
            String err = null;
            int n = 0;
            for (Entry e : items) {
                if (cancelled) break;
                try {
                    copyEntry(fr, child(srcDir, e.name), e.dir, e.size, tr, dstDir, move);
                    n++;
                } catch (Exception ex) { err = e.name + ": " + ex.getMessage(); if (cancelled) break; }
            }
            endProgress(cancelled ? "Cancelado" : err != null ? err : (n == 1 ? "Listo" : "Listo: " + n + " elementos"));
        });
    }

    private void copyEntry(boolean srcRemote, String srcPath, boolean dir, long size, boolean dstRemote, String dstDir, boolean move) throws IOException {
        if (cancelled) throw new IOException("Cancelado");
        String name = nameOf(srcPath);
        progress(name, 0, dir ? 0 : size);
        if (!srcRemote && !dstRemote) { FileServer.copy(new File(srcPath), new File(dstDir), move); return; }
        if (srcRemote && dstRemote) { remoteOp(move ? "move" : "copy", "path", srcPath, "to", dstDir); return; }
        if (!srcRemote) {   // de este teléfono al otro
            File f = new File(srcPath);
            if (dir) {
                String nd = remoteOp("mkdir", "path", dstDir, "name", name, "unique", true).optString("path");
                File[] cs = f.listFiles();
                if (cs != null) for (File c : cs) copyEntry(false, c.getAbsolutePath(), c.isDirectory(), c.length(), true, nd, false);
            } else {
                upload(f, dstDir, counter(name, size));
            }
            if (move) FileServer.delete(f);
        } else {            // del otro teléfono a este
            if (dir) {
                File nd = FileServer.unique(new File(dstDir), name);
                if (!nd.mkdirs()) throw new IOException("No pude crear " + nd.getName());
                JSONObject r = request("fsList", "path", srcPath);
                if (r.has("err")) throw new IOException(r.optString("err"));
                JSONArray arr = r.optJSONArray("entries");
                for (int i = 0; arr != null && i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    copyEntry(true, child(srcPath, o.optString("n")), o.optBoolean("d"), o.optLong("s"), false, nd.getAbsolutePath(), false);
                }
            } else {
                download(srcPath, FileServer.unique(new File(dstDir), name), counter(name, size));
            }
            if (move) remoteOp("delete", "path", srcPath);
        }
    }

    private void deleteSel() {
        Panel p = active;
        List<Entry> items = selected(p);
        if (items.isEmpty()) { Toast.makeText(this, "Mantén presionado para elegir qué borrar", Toast.LENGTH_SHORT).show(); return; }
        new Ui.Sheet(this)
                .title("Borrar " + (items.size() == 1 ? "\"" + items.get(0).name + "\"" : items.size() + " elementos"))
                .message("Se borran del " + p.name().toLowerCase(Locale.ROOT) + ". No se puede deshacer.")
                .button("Cancelar", false, null)
                .button("Borrar", true, () -> {
                    final boolean remote = p.remote();
                    final String dir = dirOf(p);
                    showProgress("Borrando");
                    work.execute(() -> {
                        String err = null;
                        for (Entry e : items) {
                            if (cancelled) break;
                            progress(e.name, 0, 0);
                            try {
                                if (remote) remoteOp("delete", "path", child(dir, e.name));
                                else if (!FileServer.delete(new File(child(dir, e.name)))) throw new IOException("No se pudo borrar");
                            } catch (IOException ex) { err = e.name + ": " + ex.getMessage(); }
                        }
                        endProgress(err);
                    });
                })
                .show();
    }

    private EditText field(String text) {
        EditText e = new EditText(this);
        e.setText(text);
        e.setSingleLine(true);
        e.setTextColor(fg);
        e.setPadding(dp(16), dp(12), dp(16), dp(12));
        GradientDrawable g = round(night ? 0xFF253445 : 0xFFFFFFFF, 16);
        g.setStroke(dp(1), night ? 0xFF33465A : 0xFFD8E0E7);
        e.setBackground(g);
        return e;
    }

    private void renameSel() {
        Panel p = active;
        List<Entry> items = selected(p);
        if (items.size() != 1) { Toast.makeText(this, "Elige un solo elemento para cambiarle el nombre", Toast.LENGTH_SHORT).show(); return; }
        Entry e = items.get(0);
        EditText f = field(e.name);
        new Ui.Sheet(this).title("Cambiar nombre").view(f)
                .button("Cancelar", false, null)
                .button("Guardar", true, () -> {
                    String nn = f.getText().toString().trim();
                    if (nn.isEmpty() || nn.contains("/") || nn.equals(e.name)) return;
                    simpleOp(p, () -> {
                        String path = child(dirOf(p), e.name);
                        if (p.remote()) remoteOp("rename", "path", path, "name", nn);
                        else {
                            File to = new File(dirOf(p), nn);
                            if (to.exists()) throw new IOException("Ya existe " + nn);
                            if (!new File(path).renameTo(to)) throw new IOException("No pude cambiar el nombre");
                        }
                    });
                })
                .show();
    }

    private void newFolder() {
        Panel p = active;
        EditText f = field("");
        f.setHint("Nombre de la carpeta");
        new Ui.Sheet(this).title("Nueva carpeta en el " + p.name().toLowerCase(Locale.ROOT)).view(f)
                .button("Cancelar", false, null)
                .button("Crear", true, () -> {
                    String nn = f.getText().toString().trim();
                    if (nn.isEmpty() || nn.contains("/")) return;
                    simpleOp(p, () -> {
                        if (p.remote()) remoteOp("mkdir", "path", dirOf(p), "name", nn);
                        else { File d = new File(dirOf(p), nn); if (!d.isDirectory() && !d.mkdirs()) throw new IOException("No pude crear la carpeta"); }
                    });
                })
                .show();
    }

    private interface Op { void run() throws IOException; }

    private void simpleOp(Panel p, Op op) {
        work.execute(() -> {
            String err = null;
            try { op.run(); } catch (IOException e) { err = e.getMessage(); }
            final String fe = err;
            main.post(() -> {
                if (fe != null) Toast.makeText(this, fe, Toast.LENGTH_LONG).show();
                p.sel.clear();
                load(p);
            });
        });
    }

    /** Abrir: si está en el otro teléfono, primero se trae una copia temporal. */
    private void openFile(Panel p, Entry e) {
        String path = child(dirOf(p), e.name);
        if (!p.remote()) { view(new File(path)); return; }
        File dir = new File(getCacheDir(), "abrir");
        File[] old = dir.listFiles();
        if (old != null) for (File o : old) FileServer.delete(o);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File dst = new File(dir, e.name);
        showProgress("Trayendo " + e.name);
        work.execute(() -> {
            try {
                download(path, dst, counter(e.name, e.size));
                main.post(() -> {
                    if (progressDlg != null) try { progressDlg.dismiss(); } catch (Exception ignored) { }
                    progressDlg = null; progressText = null;
                    view(dst);
                });
            } catch (IOException ex) { endProgress(ex.getMessage()); }
        });
    }

    private void view(File f) {
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(FilesProvider.uriFor(this, f), FilesProvider.mime(f.getName()));
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivity(i); }
        catch (Exception ex) { Toast.makeText(this, "No hay una app para abrir " + f.getName(), Toast.LENGTH_SHORT).show(); }
    }
}
