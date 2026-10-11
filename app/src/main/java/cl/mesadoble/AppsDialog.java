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
        void putOnDesk(String pkg, String label, String iconPng);
        void uninstall(String pkg, String label);
    }

    static class App {
        final String pkg, label;
        final Drawable icon;
        String png;   // ícono ya listo (apps del principal vistas desde el extendido)
        App(String p, String l, Drawable i) { pkg = p; label = l; icon = i; }
    }

    /** Última lista recibida del principal (se muestra al tiro mientras llega la nueva). */
    private static final List<App> remoteCache = new ArrayList<>();

    /** Principal: manda su lista de apps con íconos chicos. */
    static void sendList(Context c, Hub hub) {
        new Thread(() -> {
            PackageManager pm = c.getPackageManager();
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            JSONArray arr = new JSONArray();
            java.util.Set<String> seen = new java.util.HashSet<>();
            int size = Math.round(64 * c.getResources().getDisplayMetrics().density / 2.5f * 2.5f);
            for (ResolveInfo ri : pm.queryIntentActivities(i, 0)) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(c.getPackageName()) || !seen.add(pkg)) continue;
                JSONObject o = new JSONObject();
                try { o.put("p", pkg); o.put("l", String.valueOf(ri.loadLabel(pm))); o.put("i", iconPng(ri.loadIcon(pm), Math.min(144, size))); }
                catch (JSONException ignored) { }
                arr.put(o);
            }
            JSONObject m = new JSONObject();
            try { m.put("t", "appsList"); m.put("apps", arr); } catch (JSONException ignored) { }
            hub.send(m);
        }, "lista-apps").start();
    }

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
        hint.setText((remote ? "Apps del principal. " : "") + "Toca una app para dejarla en la mesa o abrirla en el principal. "
                + "Mantén presionado para desinstalarla.");
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
        grid.setOnItemClickListener((parent, view, pos, id) -> {
            App app = shown.get(pos);
            new Ui.Sheet(a)
                    .title(app.label)
                    .option(Ui.PIN, "Poner acceso directo en la mesa", "Al tocarlo se abre en el principal", () -> {
                        d.dismiss();
                        host.putOnDesk(app.pkg, app.label, app.png != null ? app.png : iconPng(app.icon, Math.round(96 * dp)));
                    })
                    .option(Ui.OPEN, "Abrir ahora en el principal", null, () -> { d.dismiss(); host.openThere(app.pkg, app.label); })
                    .option(Ui.TRASH, "Desinstalar", remote ? "Se confirma en el principal" : null, () -> host.uninstall(app.pkg, app.label))
                    .button("Cerrar", false, null)
                    .show();
        });
        grid.setOnItemLongClickListener((parent, view, pos, id) -> {
            App app = shown.get(pos);
            view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            host.uninstall(app.pkg, app.label);
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
                    new Thread(() -> {
                        List<App> list = new ArrayList<>();
                        for (int k = 0; k < arr.length(); k++) {
                            JSONObject o = arr.optJSONObject(k);
                            if (o == null) continue;
                            App ap = new App(o.optString("p"), o.optString("l"), fromPng(a, o.optString("i")));
                            ap.png = o.optString("i");
                            list.add(ap);
                        }
                        Collator col = Collator.getInstance(new Locale("es"));
                        Collections.sort(list, (x, y) -> col.compare(x.label, y.label));
                        a.runOnUiThread(() -> {
                            synchronized (remoteCache) { remoteCache.clear(); remoteCache.addAll(list); }
                            all.clear(); all.addAll(list);
                            search.setText(search.getText());   // vuelve a filtrar
                            hint.setText("Apps del principal. Toca una para dejarla en la mesa o abrirla allá. Mantén presionado para desinstalarla.");
                        });
                    }, "apps-principal").start();
                }
            }
        };
        hub.add(updates);
        d.setOnDismissListener(x -> hub.remove(updates));

        if (remote) {
            synchronized (remoteCache) { all.addAll(remoteCache); shown.addAll(remoteCache); }
            adapter.notifyDataSetChanged();
            if (hub.connected()) {
                hub.send("appsReq");
                if (remoteCache.isEmpty()) hint.setText("Pidiendo la lista de apps al principal…");
            } else {
                hint.setText(remoteCache.isEmpty() ? "Conecta con el principal para ver sus apps."
                        : "El principal no está conectado: esta es la última lista que envió.");
            }
            return;
        }

        // cargar la lista sin trabar la pantalla
        new Thread(() -> {
            PackageManager pm = a.getPackageManager();
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            List<App> list = new ArrayList<>();
            for (ResolveInfo ri : pm.queryIntentActivities(i, 0)) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(a.getPackageName())) continue;
                boolean dup = false;
                for (App x : list) if (x.pkg.equals(pkg)) { dup = true; break; }
                if (dup) continue;
                list.add(new App(pkg, String.valueOf(ri.loadLabel(pm)), ri.loadIcon(pm)));
            }
            Collator col = Collator.getInstance(new Locale("es"));
            Collections.sort(list, (x, y) -> col.compare(x.label, y.label));
            a.runOnUiThread(() -> { all.addAll(list); shown.addAll(list); adapter.notifyDataSetChanged(); });
        }, "apps").start();
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
