package cl.mesadoble;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
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
 * Gestor de archivos de ChronOS (una ventana en cada pantalla). Las dos ventanas muestran los archivos del
 * PRINCIPAL: inicio con almacenamientos y categorías (como "Gestor de archivos+"), carpetas, y copiar o mover
 * lo elegido a la carpeta abierta en la otra pantalla.
 *   fpPath (a la otra pantalla) {path}: dónde está esta ventana ("" = inicio o una categoría)
 */
public class FilePane extends FrameLayout implements Link.Listener {

    static class Entry { String name, path, pkg; boolean dir; long size, mtime; }
    private static class Wait { final CountDownLatch l = new CountDownLatch(1); JSONObject r; }

    private final Activity a;
    private final Hub hub;
    private final float dp;
    private final KbTheme th;
    private boolean remote() { return !hub.isPrincipal(); }
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService work = Executors.newSingleThreadExecutor(), thumbs = Executors.newFixedThreadPool(2);
    private final Map<Integer, Wait> waits = new ConcurrentHashMap<>();
    private final AtomicInteger seq = new AtomicInteger();
    private final Map<String, Bitmap> thumbCache = new ConcurrentHashMap<>();

    private final TextView title, crumb, status;
    private final LinearLayout actions;
    private final FrameLayout body;
    private final ListView list;
    private final ScrollView homeScroll;
    private final LinearLayout home;
    private final List<Entry> entries = new ArrayList<>();
    private final Set<String> sel = new HashSet<>();
    private final Adapter adapter = new Adapter();

    private String mode = "home";      // home | dir | images | audio | video | docs | apps
    private String path = "", otherPath = "";
    private boolean open;

    private int dp(float v) { return Math.round(v * dp); }

    public FilePane(Activity a, Hub hub, boolean night) {
        super(a);
        this.a = a;
        this.hub = hub;
        dp = a.getResources().getDisplayMetrics().density;
        th = KbTheme.of(a, night);
        setBackgroundColor(th.bg);
        setClickable(true);

        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(14), dp(10), dp(14), dp(10));

        LinearLayout head = new LinearLayout(a);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = chip("←", v -> goUp());
        TextView homeBtn = chip("⌂", v -> goHome());
        LinearLayout titles = new LinearLayout(a);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(10), 0, dp(6), 0);
        title = new TextView(a);
        title.setTextSize(17);
        title.setTextColor(ChronoBarInk());
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        crumb = new TextView(a);
        crumb.setTextSize(11);
        crumb.setTextColor((ChronoBarInk() & 0x00FFFFFF) | 0x99000000);
        crumb.setSingleLine(true);
        crumb.setEllipsize(TextUtils.TruncateAt.START);
        titles.addView(title);
        titles.addView(crumb);
        head.addView(back, new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(dp(40), dp(40));
        hp.setMarginStart(dp(6));
        head.addView(homeBtn, hp);
        head.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        actions = new LinearLayout(a);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(actions);
        col.addView(head);

        status = new TextView(a);
        status.setTextSize(12);
        status.setTextColor(th.accent);
        status.setPadding(dp(4), dp(4), 0, 0);
        status.setVisibility(GONE);
        col.addView(status);

        body = new FrameLayout(a);
        GradientDrawable card = new GradientDrawable();
        card.setColor(th.key);
        card.setCornerRadius(dp(20));
        if (th.stroke != 0) card.setStroke(dp(1), th.stroke);
        body.setBackground(card);
        body.setPadding(dp(6), dp(6), dp(6), dp(6));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, 0, 1);
        bp.topMargin = dp(8);
        col.addView(body, bp);

        list = new ListView(a);
        list.setDivider(null);
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, i, id) -> tap(entries.get(i)));
        list.setOnItemLongClickListener((p, v, i, id) -> { toggle(entries.get(i)); return true; });
        body.addView(list, new LayoutParams(-1, -1));
        homeScroll = new ScrollView(a);
        home = new LinearLayout(a);
        home.setOrientation(LinearLayout.VERTICAL);
        homeScroll.addView(home);
        body.addView(homeScroll, new LayoutParams(-1, -1));
        addView(col, new LayoutParams(-1, -1));
    }

    private int ChronoBarInk() { return CardTheme.dark(th.bg) ? 0xFFF2F5F8 : 0xFF1D2733; }

    private TextView chip(String t, View.OnClickListener l) {
        TextView b = new TextView(a);
        b.setText(t);
        b.setTextSize(15);
        b.setGravity(Gravity.CENTER);
        b.setTextColor(th.fg);
        b.setPadding(dp(10), 0, dp(10), 0);
        GradientDrawable g = new GradientDrawable();
        g.setColor(th.key);
        g.setCornerRadius(dp(20));
        if (th.stroke != 0) g.setStroke(dp(1), th.stroke);
        b.setBackground(g);
        b.setOnClickListener(l);
        return b;
    }

    // ---------------- abrir y cerrar ----------------

    public void start() {
        if (open) return;
        open = true;
        hub.add(this);
        if (mode.equals("home")) goHome(); else reload();
    }

    public void stop() {
        open = false;
        hub.remove(this);
    }

    // ---------------- navegación ----------------

    private void goHome() { mode = "home"; path = ""; sel.clear(); reload(); }

    private void goUp() {
        if (!sel.isEmpty()) { sel.clear(); refresh(); return; }
        if (!"dir".equals(mode)) { goHome(); return; }
        File f = new File(path);
        String root = FileServer.root().getAbsolutePath();
        if (path.equals(root) || f.getParent() == null || path.length() <= 1) { goHome(); return; }
        openDir(f.getParent());
    }

    private void openDir(String p) { mode = "dir"; path = p; sel.clear(); reload(); }

    private void openCat(String c) { mode = c; path = ""; sel.clear(); reload(); }

    private String catTitle() {
        switch (mode) {
            case "images": return "Imágenes";
            case "audio": return "Audio";
            case "video": return "Videos";
            case "docs": return "Documentos";
            case "apps": return "Aplicaciones";
            case "dir": {
                String root = FileServer.root().getAbsolutePath();
                return path.equals(root) ? "Almacenamiento" : new File(path).getName();
            }
            default: return "Archivos del teléfono principal";
        }
    }

    private void reload() {
        final String m = mode, p = path;
        title.setText(catTitle());
        String root = FileServer.root().getAbsolutePath();
        crumb.setText("dir".equals(m) ? "/ RAÍZ" + (p.startsWith(root) ? p.substring(root.length()) : p).replace("/", " / ").replace(" /  / ", " / ")
                : "home".equals(m) ? "Inicio" : "/ " + catTitle().toUpperCase(Locale.ROOT));
        if (hub.connected()) hub.send("fpPath", "path", "dir".equals(m) ? p : "");
        homeScroll.setVisibility("home".equals(m) ? VISIBLE : GONE);
        list.setVisibility("home".equals(m) ? GONE : VISIBLE);
        work.execute(() -> {
            String err = null;
            JSONObject res = null;
            List<Entry> out = new ArrayList<>();
            try {
                if ("dir".equals(m)) {
                    JSONObject r = remote() ? request("fsList", "path", p) : localList(p);
                    if (r.has("err")) throw new IOException(r.optString("err"));
                    JSONArray arr = r.optJSONArray("entries");
                    String base = r.optString("path", p);
                    for (int i = 0; arr != null && i < arr.length(); i++) {
                        JSONObject o = arr.getJSONObject(i);
                        if (o.optString("n").startsWith(".")) continue;
                        Entry e = entry(o);
                        e.path = base.endsWith("/") ? base + e.name : base + "/" + e.name;
                        out.add(e);
                    }
                    Collator col = Collator.getInstance(new Locale("es"));
                    col.setStrength(Collator.PRIMARY);
                    Collections.sort(out, (x, y) -> x.dir != y.dir ? (x.dir ? -1 : 1) : col.compare(x.name, y.name));
                } else {
                    JSONObject r = remote() ? request("fsCat", "cat", m) : wrap(FileServer.category(a, m));
                    if (r.has("err")) throw new IOException(r.optString("err"));
                    res = r.optJSONObject("res");
                    JSONArray arr = res == null ? null : res.optJSONArray("entries");
                    for (int i = 0; arr != null && i < arr.length(); i++) out.add(entry(arr.getJSONObject(i)));
                }
            } catch (Exception e) { err = e.getMessage() == null ? "No pude leer" : e.getMessage(); }
            final String fe = err;
            final JSONObject fres = res;
            main.post(() -> {
                if (!m.equals(mode) || !p.equals(path)) return;
                entries.clear();
                if (fe == null) entries.addAll(out);
                if ("home".equals(m)) buildHome(fres, fe);
                adapter.notifyDataSetChanged();
                list.setSelection(0);
                showStatus(fe);
                refreshActions();
            });
        });
    }

    private JSONObject localList(String p) throws JSONException {
        JSONObject r = new JSONObject();
        if (!FileServer.hasAccess(a)) { r.put("err", "Falta el permiso de archivos en este teléfono"); return r; }
        File d = FileServer.resolve(p);
        r.put("path", d.getAbsolutePath());
        try { r.put("entries", FileServer.list(d)); } catch (IOException e) { r.put("err", e.getMessage()); }
        return r;
    }

    private static JSONObject wrap(JSONObject res) throws JSONException { JSONObject r = new JSONObject(); r.put("res", res); return r; }

    private static Entry entry(JSONObject o) {
        Entry e = new Entry();
        e.name = o.optString("n"); e.dir = o.optBoolean("d"); e.size = o.optLong("s"); e.mtime = o.optLong("m");
        e.path = o.optString("p", ""); e.pkg = o.optString("k", "");
        return e;
    }

    private void showStatus(String s) {
        status.setVisibility(s == null || s.isEmpty() ? GONE : VISIBLE);
        status.setText(s == null ? "" : s);
    }

    // ---------------- inicio: almacenamientos y categorías ----------------

    private void buildHome(JSONObject res, String err) {
        home.removeAllViews();
        // siempre se ve de qué teléfono son los archivos: del principal
        TextView who = new TextView(a);
        String model = res == null ? "" : res.optString("model");
        who.setText("📱  Teléfono principal" + (model.isEmpty() ? "" : "  ·  " + model) + (remote() ? "  (visto desde el extendido)" : ""));
        who.setTextSize(12.5f);
        who.setTextColor(th.muted);
        who.setPadding(dp(8), dp(6), dp(8), dp(2));
        home.addView(who);
        List<View> tiles = new ArrayList<>();
        JSONArray st = res == null ? null : res.optJSONArray("storages");
        for (int i = 0; st != null && i < st.length(); i++) {
            JSONObject o = st.optJSONObject(i);
            if (o == null) continue;
            final String p = o.optString("p");
            tiles.add(tile(i == 0 ? "💾" : "💳", i == 0 ? 0xFF607D8B : 0xFF8D6E63, o.optString("n"),
                    size(o.optLong("used")) + " / " + size(o.optLong("total")), true, v -> openDir(p)));
        }
        if (st == null || st.length() == 0)
            tiles.add(tile("💾", 0xFF607D8B, "Almacenamiento", "", true, v -> openDir(FileServer.root().getAbsolutePath())));
        final String dl = res == null ? "" : res.optString("downloads");
        tiles.add(tile("⬇", 0xFF1E88E5, "Descargas", res == null ? "" : size(res.optLong("dlSize")), true,
                v -> openDir(dl.isEmpty() ? FileServer.root().getAbsolutePath() + "/Download" : dl)));
        tiles.add(tile("🖼", 0xFF8E24AA, "Imágenes", count(res, "imagesN"), true, v -> openCat("images")));
        tiles.add(tile("🎵", 0xFF43A047, "Audio", count(res, "audioN"), true, v -> openCat("audio")));
        tiles.add(tile("🎬", 0xFFE53935, "Videos", count(res, "videoN"), true, v -> openCat("video")));
        tiles.add(tile("📄", 0xFFFB8C00, "Documentos", count(res, "docsN"), true, v -> openCat("docs")));
        tiles.add(tile("🤖", 0xFF00897B, "Aplicaciones", count(res, "appsN"), true, v -> openCat("apps")));
        tiles.add(tile("☁", 0xFF90A4AE, "Nube", "Próximamente", false, null));
        int cols = Math.max(3, Math.min(6, (int) ((getWidth() > 0 ? getWidth() : a.getResources().getDisplayMetrics().widthPixels) / (118 * dp))));
        LinearLayout row = null;
        for (int i = 0; i < tiles.size(); i++) {
            if (i % cols == 0) { row = new LinearLayout(a); home.addView(row); }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            row.addView(tiles.get(i), lp);
        }
        int rest = (cols - tiles.size() % cols) % cols;
        for (int i = 0; i < rest && row != null; i++) row.addView(new View(a), new LinearLayout.LayoutParams(0, 1, 1));
        if (err != null) showStatus(err);
    }

    private static String count(JSONObject res, String k) {
        if (res == null || !res.has(k)) return "";
        int n = res.optInt(k);
        return n + (n == 1 ? " elemento" : " elementos");
    }

    private View tile(String icon, int color, String name, String sub, boolean enabled, View.OnClickListener l) {
        LinearLayout t = new LinearLayout(a);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        t.setPadding(dp(4), dp(12), dp(4), dp(10));
        TextView ic = new TextView(a);
        ic.setText(icon);
        ic.setTextSize(26);
        ic.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setColor(enabled ? color : 0xFFB0B8C0);
        g.setCornerRadius(dp(16));
        ic.setBackground(g);
        t.addView(ic, new LinearLayout.LayoutParams(dp(58), dp(58)));
        TextView n = new TextView(a);
        n.setText(name);
        n.setTextSize(13.5f);
        n.setTextColor(th.fg);
        n.setGravity(Gravity.CENTER);
        n.setSingleLine(true);
        n.setEllipsize(TextUtils.TruncateAt.END);
        n.setPadding(0, dp(6), 0, 0);
        t.addView(n);
        TextView s = new TextView(a);
        s.setText(sub);
        s.setTextSize(11);
        s.setTextColor(th.muted);
        s.setGravity(Gravity.CENTER);
        s.setSingleLine(true);
        t.addView(s);
        if (!enabled) t.setAlpha(0.5f);
        if (l != null) {
            t.setOnClickListener(l);
            t.setBackground(a.getDrawable(android.R.drawable.list_selector_background));
        }
        return t;
    }

    static String size(long b) {
        if (b <= 0) return "0 B";
        String[] u = {"B", "KB", "MB", "GB", "TB"};
        int i = (int) Math.min(u.length - 1, Math.floor(Math.log(b) / Math.log(1024)));
        double v = b / Math.pow(1024, i);
        return (v >= 100 || i == 0 ? String.format(Locale.ROOT, "%.0f", v) : String.format(Locale.ROOT, "%.1f", v)) + " " + u[i];
    }

    // ---------------- lista ----------------

    private static String icon(Entry e) {
        if (e.dir) return "📁";
        if (!e.pkg.isEmpty()) return "🤖";
        String t = FilesProvider.mime(e.name);
        if (t.startsWith("image/")) return "🖼";
        if (t.startsWith("video/")) return "🎬";
        if (t.startsWith("audio/")) return "🎵";
        if (t.contains("pdf")) return "📕";
        String n = e.name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".apk")) return "📦";
        if (t.contains("zip") || t.contains("compressed") || t.contains("rar")) return "🗜";
        return "📄";
    }

    private class Adapter extends BaseAdapter {
        @Override public int getCount() { return entries.size(); }
        @Override public Object getItem(int i) { return entries.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override public View getView(int i, View v, ViewGroup parent) {
            Holder h;
            if (v == null) {
                LinearLayout row = new LinearLayout(a);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(10), dp(6), dp(10), dp(6));
                h = new Holder();
                h.check = new TextView(a);
                h.check.setTextSize(16);
                h.check.setTextColor(th.accent);
                row.addView(h.check, new LinearLayout.LayoutParams(dp(28), -2));
                FrameLayout box = new FrameLayout(a);
                h.emoji = new TextView(a);
                h.emoji.setTextSize(20);
                h.emoji.setGravity(Gravity.CENTER);
                h.img = new ImageView(a);
                h.img.setScaleType(ImageView.ScaleType.CENTER_CROP);
                box.addView(h.emoji, new LayoutParams(-1, -1));
                box.addView(h.img, new LayoutParams(-1, -1));
                row.addView(box, new LinearLayout.LayoutParams(dp(40), dp(40)));
                LinearLayout texts = new LinearLayout(a);
                texts.setOrientation(LinearLayout.VERTICAL);
                texts.setPadding(dp(10), 0, 0, 0);
                h.name = new TextView(a);
                h.name.setTextSize(15);
                h.name.setTextColor(th.fg);
                h.name.setSingleLine(true);
                h.name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                h.sub = new TextView(a);
                h.sub.setTextSize(11.5f);
                h.sub.setTextColor(th.muted);
                h.sub.setSingleLine(true);
                texts.addView(h.name);
                texts.addView(h.sub);
                row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
                row.setTag(h);
                v = row;
            }
            h = (Holder) v.getTag();
            Entry e = entries.get(i);
            boolean on = sel.contains(e.path);
            h.check.setText(sel.isEmpty() ? "" : on ? "☑" : "☐");
            h.check.setVisibility(sel.isEmpty() ? GONE : VISIBLE);
            h.emoji.setText(icon(e));
            h.name.setText(e.name);
            String date = e.mtime > 0 ? java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT).format(new java.util.Date(e.mtime)) : "";
            h.sub.setText(e.dir ? date : size(e.size) + (date.isEmpty() ? "" : "  ·  " + date));
            v.setBackgroundColor(on ? (th.accent & 0x00FFFFFF) | 0x33000000 : 0);
            h.img.setImageBitmap(null);
            h.path = e.path;
            if (!e.dir && FilesProvider.mime(e.name).startsWith("image/")) loadThumb(h, e.path);
            return v;
        }
    }

    private static class Holder { TextView check, emoji, name, sub; ImageView img; String path; }

    private void loadThumb(Holder h, String p) {
        Bitmap c = thumbCache.get(p);
        if (c != null) { h.img.setImageBitmap(c); return; }
        thumbs.execute(() -> {
            Bitmap b = null;
            try {
                if (!remote()) b = FileServer.thumb(new File(p), 120);
                else {
                    JSONObject r = request("fsThumb", "path", p, "size", 120);
                    byte[] d = android.util.Base64.decode(r.optString("img"), android.util.Base64.DEFAULT);
                    if (d.length > 0) b = BitmapFactory.decodeByteArray(d, 0, d.length);
                }
            } catch (Exception ignored) { }
            final Bitmap fb = b;
            if (fb != null) {
                thumbCache.put(p, fb);
                main.post(() -> { if (p.equals(h.path)) h.img.setImageBitmap(fb); });
            }
        });
    }

    // ---------------- tocar y elegir ----------------

    private void tap(Entry e) {
        if (!sel.isEmpty()) { toggle(e); return; }
        if (e.dir) { openDir(e.path); return; }
        if (!e.pkg.isEmpty()) {   // apps: se abren en el principal
            if (remote()) hub.send("launch", "pkg", e.pkg, "label", e.name); else hub.launchHere(e.pkg, e.name);
            return;
        }
        String kind = MediaHub.kindOf(e.name);
        if (kind != null) {   // fotos, música y videos: en el principal; la música queda en la barra
            ArrayList<String> paths = new ArrayList<>();
            int idx = 0;
            for (Entry x : entries) if (!x.dir && kind.equals(MediaHub.kindOf(x.name))) { if (x == e) idx = paths.size(); paths.add(x.path); }
            if (!remote()) MediaHub.open(a, kind, paths, idx);
            else if (hub.connected()) MediaHub.requestPlay(hub, kind, paths, idx);
            return;
        }
        if (!remote()) FileServer.openHere(a, hub, e.path);
        else if (hub.connected()) { hub.send("fmOpenFile", "path", e.path); toast("Abriendo " + e.name + " en el principal"); }
    }

    private void toggle(Entry e) {
        if (!sel.remove(e.path)) sel.add(e.path);
        refresh();
    }

    private void refresh() { adapter.notifyDataSetChanged(); refreshActions(); }

    private void refreshActions() {
        actions.removeAllViews();
        boolean inDir = "dir".equals(mode);
        if (!sel.isEmpty()) {
            boolean canSend = !otherPath.isEmpty();
            addAction("Copiar ⇄", canSend, v -> transfer(false));
            if (inDir) addAction("Mover ⇄", canSend, v -> transfer(true));
            if (sel.size() == 1 && inDir) addAction("Renombrar", true, v -> rename());
            addAction("Eliminar", true, v -> delete());
            addAction("✕", true, v -> { sel.clear(); refresh(); });
        } else if (inDir) addAction("+ Carpeta", true, v -> newFolder());
    }

    private void addAction(String t, boolean enabled, View.OnClickListener l) {
        TextView b = chip(t, l);
        b.setTextSize(13);
        b.setAlpha(enabled ? 1f : 0.4f);
        b.setEnabled(enabled);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(36));
        lp.setMarginStart(dp(6));
        actions.addView(b, lp);
    }

    private void toast(String s) { Toast.makeText(a, s, Toast.LENGTH_SHORT).show(); }

    /** Copiar o mover a la carpeta abierta en la otra pantalla (todo dentro del principal). */
    private void transfer(boolean move) {
        if (otherPath.isEmpty()) { toast("Abre una carpeta en la otra pantalla"); return; }
        if (!hub.connected() && remote()) { toast("El principal no está conectado"); return; }
        JSONArray arr = new JSONArray();
        for (String p : sel) arr.put(p);
        String what = sel.size() == 1 ? new File(sel.iterator().next()).getName() : sel.size() + " elementos";
        JSONObject job = new JSONObject();
        try {
            job.put("t", "fmJob"); job.put("items", arr); job.put("to", otherPath); job.put("move", move); job.put("src", remote() ? "e" : "p");
            job.put("title", (move ? "Moviendo " : "Copiando ") + what + " a " + new File(otherPath).getName());
        } catch (JSONException ignored) { }
        if (remote()) hub.send(job); else FileServer.get(a).runJob(job, hub);
        sel.clear();
        refresh();
    }

    private void op(String op, Object... kv) throws IOException {
        JSONObject m = new JSONObject(), r = new JSONObject();
        try {
            m.put("op", op);
            for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
            if (remote()) {
                Object[] all = new Object[kv.length + 2];
                all[0] = "op"; all[1] = op;
                System.arraycopy(kv, 0, all, 2, kv.length);
                r = request("fsOp", all);
            } else FileServer.op(m, r);
        } catch (JSONException ignored) { }
        if (!r.optBoolean("ok")) throw new IOException(r.optString("err", "No se pudo"));
    }

    private interface IoTask { void run() throws IOException; }

    private void background(IoTask t) {
        work.execute(() -> {
            String err = null;
            try { t.run(); } catch (IOException e) { err = e.getMessage(); }
            final String fe = err;
            main.post(() -> { if (fe != null) toast(fe); sel.clear(); reload(); });
        });
    }

    private void delete() {
        List<String> items = new ArrayList<>(sel);
        new Ui.Sheet(a).title("Eliminar " + (items.size() == 1 ? "\"" + new File(items.get(0)).getName() + "\"" : items.size() + " elementos"))
                .message("Se borran del teléfono principal. No se puede deshacer.")
                .button("Cancelar", false, null)
                .button("Eliminar", true, () -> background(() -> { for (String p : items) op("delete", "path", p); }))
                .show();
    }

    private EditText field(String text) {
        EditText e = new EditText(a);
        e.setText(text);
        e.setSingleLine(true);
        return e;
    }

    private void rename() {
        String p = sel.iterator().next();
        String old = new File(p).getName();
        EditText f = field(old);
        new Ui.Sheet(a).title("Cambiar nombre").view(f).button("Cancelar", false, null)
                .button("Guardar", true, () -> {
                    String nn = f.getText().toString().trim();
                    if (nn.isEmpty() || nn.contains("/") || nn.equals(old)) return;
                    background(() -> op("rename", "path", p, "name", nn));
                }).show();
    }

    private void newFolder() {
        EditText f = field("");
        f.setHint("Nombre de la carpeta");
        final String d = path;
        new Ui.Sheet(a).title("Nueva carpeta").view(f).button("Cancelar", false, null)
                .button("Crear", true, () -> {
                    String nn = f.getText().toString().trim();
                    if (nn.isEmpty() || nn.contains("/")) return;
                    background(() -> op("mkdir", "path", d, "name", nn));
                }).show();
    }

    // ---------------- pedidos al principal ----------------

    private JSONObject request(String t, Object... kv) throws IOException {
        if (!hub.connected()) throw new IOException("El principal no está conectado");
        int id = seq.incrementAndGet() + 100000;
        JSONObject m = new JSONObject();
        try {
            m.put("t", t); m.put("req", id);
            for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        } catch (JSONException ignored) { }
        Wait w = new Wait();
        waits.put(id, w);
        hub.send(m);
        try {
            if (!w.l.await(25, TimeUnit.SECONDS)) throw new IOException("El principal no responde");
        } catch (InterruptedException e) { throw new IOException("Cancelado"); }
        finally { waits.remove(id); }
        if ("permiso".equals(w.r.optString("err"))) throw new IOException("Falta el permiso de archivos en el principal: toca el aviso que apareció allá");
        return w.r;
    }

    @Override public void onState(int state, String info) {
        if (state == Link.CONNECTED) main.post(() -> { if (open) reload(); });
    }

    @Override public void onMessage(JSONObject m) {
        String t = m.optString("t");
        if (t.startsWith("fs") && t.endsWith("R")) {
            Wait w = waits.get(m.optInt("req"));
            if (w != null) { w.r = m; w.l.countDown(); }
        } else if ("fpPath".equals(t)) {
            main.post(() -> { otherPath = m.optString("path"); refreshActions(); });
        } else if ("fmAnim".equals(t)) {
            main.post(() -> {
                String s = m.optString("s");
                if ("begin".equals(s)) showStatus(m.optString("title") + "…");
                else if ("prog".equals(s)) {
                    double p = m.optDouble("p", -1);
                    showStatus(m.optString("name") + (p >= 0 ? "  " + Math.round(p * 100) + "%" : ""));
                } else if ("end".equals(s)) {
                    String msg = m.optString("msg");
                    showStatus(msg.isEmpty() ? null : msg);
                    if (open) reload();
                }
            });
        }
    }
}
