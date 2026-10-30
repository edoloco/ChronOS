package cl.mesadoble;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;

import java.io.ByteArrayOutputStream;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Lista de apps del PRINCIPAL (en el extendido se piden al principal por la conexión):
 * dejarla en la mesa, abrirla en el principal o desinstalarla (mantener presionado).
 */
public class AppsDialog {

    public interface Host {
        void openHere(String pkg);
        void openThere(String pkg, String label);
        /** screen: 0 = principal, 1 = extendido. La app queda en modo edición para ubicarla. */
        void putOnDesk(String pkg, String label, String iconPng, int screen);
        void uninstall(String pkg, String label);
    }

    /** Apps propias de ChronOS que se ven como una más: DUAL-Draw y los PALS. */
    static List<App> extras(android.content.Context c) {
        List<App> l = new ArrayList<>();
        l.add(DualDrawActivity.asApp(c));
        l.addAll(Pals.asApps(c));
        return l;
    }

    static class App {
        final String pkg, label;
        final Drawable icon;
        String png;   // ícono ya listo (apps del principal vistas desde el extendido)
        App(String p, String l, Drawable i) { pkg = p; label = l; icon = i; }
    }

    /** Última lista recibida del principal (se muestra al tiro mientras llega la nueva). */
    private static final List<App> remoteCache = new ArrayList<>();

    // ---------------- caché: la lista con íconos se arma una vez y solo viaja si cambió ----------------

    private static final java.util.Map<String, String> pngCache = new java.util.concurrent.ConcurrentHashMap<>();   // pkg@versión → ícono
    private static volatile JSONArray builtArr;
    private static volatile String builtSig = "";
    private static String remoteSig = "";

    /** Huella de las apps instaladas (paquete y fecha de actualización): si no cambió, no se reenvía nada. */
    private static String signature(Context c, List<ResolveInfo> l) {
        StringBuilder sb = new StringBuilder();
        PackageManager pm = c.getPackageManager();
        for (ResolveInfo ri : l) {
            sb.append(ri.activityInfo.packageName).append(':');
            try { sb.append(pm.getPackageInfo(ri.activityInfo.packageName, 0).lastUpdateTime); } catch (Exception ignored) { }
            sb.append(';');
        }
        return Integer.toHexString(sb.toString().hashCode()) + "-" + l.size();
    }

    /** Principal: arma (o reutiliza) la lista de apps con íconos chicos. */
    static synchronized JSONArray build(Context c) {
        PackageManager pm = c.getPackageManager();
        List<ResolveInfo> l = pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0);
        String sig = signature(c, l);
        if (sig.equals(builtSig) && builtArr != null) return builtArr;
        JSONArray arr = new JSONArray();
        java.util.Set<String> seen = new java.util.HashSet<>();
        int size = Math.min(144, Math.round(64 * c.getResources().getDisplayMetrics().density));
        for (ResolveInfo ri : l) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(c.getPackageName()) || !seen.add(pkg)) continue;
            String key = pkg;
            try { key = pkg + "@" + pm.getPackageInfo(pkg, 0).lastUpdateTime; } catch (Exception ignored) { }
            String png = pngCache.get(key);
            if (png == null) { png = iconPng(ri.loadIcon(pm), size); pngCache.put(key, png); }
            JSONObject o = new JSONObject();
            try { o.put("p", pkg); o.put("l", String.valueOf(ri.loadLabel(pm))); o.put("i", png); } catch (JSONException ignored) { }
            arr.put(o);
        }
        builtArr = arr;
        builtSig = sig;
        return arr;
    }

    /** Principal: manda su lista (solo si la del extendido es distinta). */
    static void sendList(Context c, Hub hub) { sendList(c, hub, ""); }

    static void sendList(Context c, Hub hub, String have) {
        new Thread(() -> {
            JSONArray arr = build(c);
            JSONObject m = new JSONObject();
            try {
                if (builtSig.equals(have)) { m.put("t", "appsSame"); m.put("sig", have); }
                else { m.put("t", "appsList"); m.put("apps", arr); m.put("sig", builtSig); }
            } catch (JSONException ignored) { }
            hub.send(m);
        }, "lista-apps").start();
    }

    /** Extendido: la última lista recibida, guardada en el teléfono (se muestra al tiro, aunque se haya cerrado la app). */
    private static File cacheFile(Context c) { return new File(c.getFilesDir(), "apps_principal.json"); }

    static void loadCache(Activity a) {
        synchronized (remoteCache) { if (!remoteCache.isEmpty()) return; }
        try {
            byte[] b = java.nio.file.Files.readAllBytes(cacheFile(a).toPath());
            JSONObject o = new JSONObject(new String(b, java.nio.charset.StandardCharsets.UTF_8));
            List<App> list = parse(a, o.optJSONArray("apps"));
            synchronized (remoteCache) { remoteCache.clear(); remoteCache.addAll(list); remoteSig = o.optString("sig"); }
        } catch (Exception ignored) { }
    }

    private static void saveCache(Context c, JSONArray arr, String sig) {
        try {
            JSONObject o = new JSONObject();
            o.put("sig", sig); o.put("apps", arr);
            java.nio.file.Files.write(cacheFile(c).toPath(), o.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception ignored) { }
    }

    private static List<App> parse(Activity a, JSONArray arr) {
        List<App> list = new ArrayList<>();
        for (int k = 0; arr != null && k < arr.length(); k++) {
            JSONObject o = arr.optJSONObject(k);
            if (o == null) continue;
            App ap = new App(o.optString("p"), o.optString("l"), fromPng(a, o.optString("i")));
            ap.png = o.optString("i");
            list.add(ap);
        }
        Collator col = Collator.getInstance(new Locale("es"));
        Collections.sort(list, (x, y) -> col.compare(x.label, y.label));
        return list;
    }

    /** Principal: lista local (con íconos de Android), guardada mientras la app esté abierta. */
    private static final List<App> localCache = new ArrayList<>();
    private static String localSig = "";

    private static Drawable fromPng(Activity a, String b64) {
        try {
            byte[] b = Base64.decode(b64, Base64.DEFAULT);
            return new android.graphics.drawable.BitmapDrawable(a.getResources(), android.graphics.BitmapFactory.decodeByteArray(b, 0, b.length));
        } catch (Exception e) { return null; }
    }

    public static void show(Activity a, boolean night, Host host) {
        final Dialog d = new Dialog(a, night ? android.R.style.Theme_Material_NoActionBar_Fullscreen
                : android.R.style.Theme_Material_Light_NoActionBar_Fullscreen);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        float dp = a.getResources().getDisplayMetrics().density;
        int bg = night ? 0xFF18212B : 0xFFDDE3E8, fg = night ? 0xFFE6EDF3 : 0xFF1D2733;

        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bg);
        int pad = Math.round(14 * dp);
        root.setPadding(pad, pad, pad, 0);

        LinearLayout top = new LinearLayout(a);
        top.setGravity(Gravity.CENTER_VERTICAL);
        EditText search = new EditText(a);
        search.setHint("Buscar app");
        search.setSingleLine(true);
        search.setTextColor(fg);
        GradientDrawable sb = new GradientDrawable();
        sb.setColor(night ? 0xFF223040 : 0xFFFFFFFF);
        sb.setCornerRadius(12 * dp);
        search.setBackground(sb);
        search.setPadding(pad, Math.round(10 * dp), pad, Math.round(10 * dp));
        top.addView(search, new LinearLayout.LayoutParams(0, -2, 1));
        TextView close = new TextView(a);
        close.setText("Cerrar");
        close.setTextColor(fg);
        close.setTextSize(15);
        close.setPadding(pad, pad, pad / 2, pad);
        close.setOnClickListener(v -> d.dismiss());
        top.addView(close);
        root.addView(top);

        TextView hint = new TextView(a);
        final Hub hub = Hub.get(a);
        final boolean remote = !hub.isPrincipal();
        hint.setText((remote ? "Apps del principal. " : "") + "Toca una app para abrirla. Mantén presionado para ponerla en una mesa o desinstalarla.");
        hint.setTextColor(night ? 0xFF93A4B5 : 0xFF5B6B7A);
        hint.setTextSize(12);
        hint.setPadding(4, Math.round(8 * dp), 4, Math.round(6 * dp));
        root.addView(hint);

        GridView grid = new GridView(a);
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setColumnWidth(Math.round(84 * dp));
        grid.setVerticalSpacing(Math.round(10 * dp));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        root.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1));

        final List<App> all = new ArrayList<>();
        final List<App> shown = new ArrayList<>();
        final BaseAdapter adapter = new BaseAdapter() {
            @Override public int getCount() { return shown.size(); }
            @Override public Object getItem(int i) { return shown.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View v, ViewGroup parent) {
                LinearLayout cell = (LinearLayout) v;
                if (cell == null) {
                    cell = new LinearLayout(a);
                    cell.setOrientation(LinearLayout.VERTICAL);
                    cell.setGravity(Gravity.CENTER_HORIZONTAL);
                    cell.setPadding(0, Math.round(6 * dp), 0, Math.round(6 * dp));
                    ImageView iv = new ImageView(a);
                    cell.addView(iv, new LinearLayout.LayoutParams(Math.round(52 * dp), Math.round(52 * dp)));
                    TextView tv = new TextView(a);
                    tv.setTextColor(fg);
                    tv.setTextSize(12);
                    tv.setMaxLines(2);
                    tv.setGravity(Gravity.CENTER_HORIZONTAL);
                    tv.setPadding(0, Math.round(4 * dp), 0, 0);
                    cell.addView(tv);
                }
                App app = shown.get(i);
                ((ImageView) cell.getChildAt(0)).setImageDrawable(app.icon);
                ((TextView) cell.getChildAt(1)).setText(app.label);
                return cell;
            }
        };
        grid.setAdapter(adapter);
        // un toque: abrir la app en el principal; mantener presionado: ponerla en una mesa o desinstalarla
        grid.setOnItemClickListener((parent, view, pos, id) -> {
            App app = shown.get(pos);
            d.dismiss();
            host.openThere(app.pkg, app.label);
        });
        grid.setOnItemLongClickListener((parent, view, pos, id) -> {
            App app = shown.get(pos);
            view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            String png = app.png != null ? app.png : iconPng(app.icon, Math.round(96 * dp));
            new Ui.Sheet(a)
                    .title(app.label)
                    .option(Ui.PIN, 0, "Poner en el principal", "Queda en la mesa de arriba, lista para ubicarla", false, () -> {
                        d.dismiss(); host.putOnDesk(app.pkg, app.label, png, 0);
                    })
                    .option(Ui.PIN, 1, "Poner en el extendido", "Queda en la mesa de abajo, lista para ubicarla", false, () -> {
                        d.dismiss(); host.putOnDesk(app.pkg, app.label, png, 1);
                    })
                    .option(Ui.TRASH, Pals.isPal(app.pkg) || DualDrawActivity.PKG.equals(app.pkg) ? "Es parte de ChronOS (no se desinstala)" : "Desinstalar", remote ? "Se confirma en el principal" : null, () -> {
                        if (!Pals.isPal(app.pkg) && !DualDrawActivity.PKG.equals(app.pkg)) host.uninstall(app.pkg, app.label);
                    })
                    .button("Cerrar", false, null)
                    .show();
            return true;
        });
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int af) { }
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) { }
            @Override public void afterTextChanged(Editable e) {
                String q = e.toString().trim().toLowerCase(Locale.ROOT);
                shown.clear();
                for (App ap : all) if (q.isEmpty() || ap.label.toLowerCase(Locale.ROOT).contains(q)) shown.add(ap);
                adapter.notifyDataSetChanged();
            }
        });

        d.setContentView(root);
        d.show();

        // la lista se actualiza si se desinstala una app (aquí o en el principal)
        final Link.Listener updates = new Link.Listener() {
            @Override public void onState(int st, String info) { }
            @Override public void onMessage(JSONObject m) {
                String t = m.optString("t");
                if ("appRemoved".equals(t)) {
                    String pkg = m.optString("pkg");
                    a.runOnUiThread(() -> {
                        for (List<App> l : java.util.Arrays.asList(all, shown, remoteCache))
                            for (int k = l.size() - 1; k >= 0; k--) if (l.get(k).pkg.equals(pkg)) l.remove(k);
                        adapter.notifyDataSetChanged();
                    });
                } else if ("appsList".equals(t) && remote) {
                    JSONArray arr = m.optJSONArray("apps");
                    if (arr == null) return;
                    String sig = m.optString("sig");
                    new Thread(() -> {
                        List<App> list = parse(a, arr);
                        saveCache(a, arr, sig);
                        a.runOnUiThread(() -> {
                            synchronized (remoteCache) { remoteCache.clear(); remoteCache.addAll(list); remoteSig = sig; }
                            all.clear(); all.addAll(extras(a)); all.addAll(list);
                            search.setText(search.getText());   // vuelve a filtrar
                            hint.setText("Apps del principal. Toca una para abrirla. Mantén presionado para ponerla en una mesa o desinstalarla.");
                        });
                    }, "apps-principal").start();
                } else if ("appsSame".equals(t) && remote) {
                    a.runOnUiThread(() -> hint.setText("Apps del principal. Toca una para abrirla. Mantén presionado para ponerla en una mesa o desinstalarla."));
                }
            }
        };
        hub.add(updates);
        d.setOnDismissListener(x -> hub.remove(updates));

        if (remote) {
            loadCache(a);
            all.addAll(extras(a)); shown.addAll(extras(a));
            synchronized (remoteCache) { all.addAll(remoteCache); shown.addAll(remoteCache); }
            adapter.notifyDataSetChanged();
            if (hub.connected()) {
                hub.send("appsReq", "sig", remoteSig);
                if (remoteCache.isEmpty()) hint.setText("Pidiendo la lista de apps al principal…");
            } else {
                hint.setText(remoteCache.isEmpty() ? "Conecta con el principal para ver sus apps."
                        : "El principal no está conectado: esta es la última lista que envió.");
            }
            return;
        }

        // la lista queda guardada: se muestra al tiro y se revisa en segundo plano por si cambió
        all.addAll(extras(a)); shown.addAll(extras(a));
        synchronized (localCache) { all.addAll(localCache); shown.addAll(localCache); }
        adapter.notifyDataSetChanged();
        new Thread(() -> {
            PackageManager pm = a.getPackageManager();
            List<ResolveInfo> ris = pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0);
            String sig = signature(a, ris);
            synchronized (localCache) { if (sig.equals(localSig) && !localCache.isEmpty()) return; }
            List<App> list = new ArrayList<>();
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (ResolveInfo ri : ris) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(a.getPackageName()) || !seen.add(pkg)) continue;
                list.add(new App(pkg, String.valueOf(ri.loadLabel(pm)), ri.loadIcon(pm)));
            }
            Collator col = Collator.getInstance(new Locale("es"));
            Collections.sort(list, (x, y) -> col.compare(x.label, y.label));
            synchronized (localCache) { localCache.clear(); localCache.addAll(list); localSig = sig; }
            a.runOnUiThread(() -> { all.clear(); all.addAll(extras(a)); all.addAll(list); search.setText(search.getText()); });
        }, "apps").start();
    }

    /** Extendido: al conectarse pide la lista (si cambió) para que el menú de apps abra al instante. */
    static void prefetch(Activity a, Hub hub) {
        new Thread(() -> {
            loadCache(a);
            if (hub.connected()) hub.send("appsReq", "sig", remoteSig);
        }, "apps-pre").start();
    }

    /** Extendido: guarda la lista que llegó aunque el menú de apps esté cerrado. */
    static void received(Activity a, JSONObject m) {
        if (!"appsList".equals(m.optString("t"))) return;
        JSONArray arr = m.optJSONArray("apps");
        String sig = m.optString("sig");
        if (arr == null) return;
        new Thread(() -> {
            List<App> list = parse(a, arr);
            saveCache(a, arr, sig);
            synchronized (remoteCache) { remoteCache.clear(); remoteCache.addAll(list); remoteSig = sig; }
        }, "apps-guardar").start();
    }

    static String iconPng(Drawable d, int size) {
        try {
            Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            d.setBounds(0, 0, size, size);
            d.draw(c);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.PNG, 100, bos);
            return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) { return ""; }
    }
}
