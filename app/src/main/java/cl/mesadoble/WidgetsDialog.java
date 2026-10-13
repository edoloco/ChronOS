package cl.mesadoble;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
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
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Los widgets de Android del teléfono PRINCIPAL (en el extendido se piden por la conexión). */
public class WidgetsDialog {

    public interface Host { void pick(String provider, String label, int minWdp, int minHdp); }

    static class W {
        String provider, label, app;
        Drawable icon;
        int mw, mh;
    }

    public static void show(Activity a, boolean night, Host host) {
        final Hub hub = Hub.get(a);
        final boolean remote = !hub.isPrincipal();
        final Dialog d = new Dialog(a, night ? android.R.style.Theme_Material_NoActionBar_Fullscreen
                : android.R.style.Theme_Material_Light_NoActionBar_Fullscreen);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        float dp = a.getResources().getDisplayMetrics().density;
        int bg = night ? 0xFF18212B : 0xFFDDE3E8, fg = night ? 0xFFE6EDF3 : 0xFF1D2733, muted = night ? 0xFF93A4B5 : 0xFF5B6B7A;
        int pad = Math.round(14 * dp);

        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bg);
        root.setPadding(pad, pad, pad, 0);

        LinearLayout top = new LinearLayout(a);
        top.setGravity(Gravity.CENTER_VERTICAL);
        EditText search = new EditText(a);
        search.setHint("Buscar widget");
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
        hint.setText(remote ? "Pidiendo los widgets al principal…"
                : "Widgets del principal (reloj, clima, calendario, música...). Se mueven tomándolos por su franja superior.");
        hint.setTextColor(muted);
        hint.setTextSize(12);
        hint.setPadding(4, Math.round(8 * dp), 4, Math.round(6 * dp));
        root.addView(hint);

        ListView list = new ListView(a);
        list.setDivider(null);
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));

        final List<W> all = new ArrayList<>(), shown = new ArrayList<>();
        final BaseAdapter adapter = new BaseAdapter() {
            @Override public int getCount() { return shown.size(); }
            @Override public Object getItem(int i) { return shown.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View v, ViewGroup parent) {
                LinearLayout row = (LinearLayout) v;
                if (row == null) {
                    row = new LinearLayout(a);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(Math.round(6 * dp), Math.round(8 * dp), Math.round(6 * dp), Math.round(8 * dp));
                    ImageView iv = new ImageView(a);
                    row.addView(iv, new LinearLayout.LayoutParams(Math.round(44 * dp), Math.round(44 * dp)));
                    LinearLayout col = new LinearLayout(a);
                    col.setOrientation(LinearLayout.VERTICAL);
                    col.setPadding(Math.round(12 * dp), 0, 0, 0);
                    TextView t1 = new TextView(a); t1.setTextColor(fg); t1.setTextSize(15);
                    TextView t2 = new TextView(a); t2.setTextColor(muted); t2.setTextSize(12);
                    col.addView(t1); col.addView(t2);
                    row.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
                }
                W w = shown.get(i);
                ((ImageView) row.getChildAt(0)).setImageDrawable(w.icon);
                LinearLayout col = (LinearLayout) row.getChildAt(1);
                ((TextView) col.getChildAt(0)).setText(w.label);
                int cw = Math.max(1, Math.round((w.mw + 30) / 70f)), ch = Math.max(1, Math.round((w.mh + 30) / 70f));
                ((TextView) col.getChildAt(1)).setText(w.app + " · " + cw + "×" + ch);
                return row;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, pos, id) -> {
            W w = shown.get(pos);
            d.dismiss();
            host.pick(w.provider, w.label, w.mw, w.mh);
        });
        Runnable filter = () -> {
            String q = search.getText().toString().trim().toLowerCase(Locale.ROOT);
            shown.clear();
            for (W w : all) if (q.isEmpty() || w.label.toLowerCase(Locale.ROOT).contains(q) || w.app.toLowerCase(Locale.ROOT).contains(q)) shown.add(w);
            adapter.notifyDataSetChanged();
        };
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int af) { }
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) { }
            @Override public void afterTextChanged(Editable e) { filter.run(); }
        });

        d.setContentView(root);
        d.show();

        // la lista viene del principal: directo, o pedida por la conexión
        final Link.Listener[] l = new Link.Listener[1];
        java.util.function.Consumer<JSONArray> load = arr -> new Thread(() -> {
            List<W> out = new ArrayList<>();
            for (int i = 0; arr != null && i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                W w = new W();
                w.provider = o.optString("p"); w.label = o.optString("l"); w.app = o.optString("a");
                w.mw = o.optInt("mw"); w.mh = o.optInt("mh");
                try {
                    byte[] b = Base64.decode(o.optString("i"), Base64.DEFAULT);
                    w.icon = new BitmapDrawable(a.getResources(), BitmapFactory.decodeByteArray(b, 0, b.length));
                } catch (Exception ignored) { }
                out.add(w);
            }
            Collator col = Collator.getInstance(new Locale("es"));
            final String own = a.getPackageName();
            Collections.sort(out, (x, y) -> {
                boolean ox = x.provider.startsWith(own + "/"), oy = y.provider.startsWith(own + "/");
                if (ox != oy) return ox ? -1 : 1;   // los de ChronOS primero
                int c = col.compare(x.app, y.app);
                return c != 0 ? c : col.compare(x.label, y.label);
            });
            a.runOnUiThread(() -> {
                all.clear(); all.addAll(out);
                filter.run();
                hint.setText("Widgets del principal (reloj, clima, calendario, música...). Se mueven tomándolos por su franja superior.");
            });
        }, "widgets").start();

        if (!remote) {
            new Thread(() -> { JSONArray arr = WidgetHub.providers(a); load.accept(arr); }, "widgets-locales").start();
            return;
        }
        if (!hub.connected()) { hint.setText("Conecta con el principal para ver sus widgets."); return; }
        l[0] = new Link.Listener() {
            @Override public void onState(int s, String i) { }
            @Override public void onMessage(JSONObject m) { if ("wListR".equals(m.optString("t"))) load.accept(m.optJSONArray("list")); }
        };
        hub.add(l[0]);
        d.setOnDismissListener(x -> hub.remove(l[0]));
        hub.send("wList");
    }
}
