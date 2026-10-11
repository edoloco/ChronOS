package cl.mesadoble;

import android.app.Activity;
import android.app.Dialog;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextWatcher;
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

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Lista de los widgets de Android instalados en este teléfono. */
public class WidgetsDialog {

    public interface Host { void pick(AppWidgetProviderInfo info, String label); }

    static class W {
        final AppWidgetProviderInfo info;
        final String label, app;
        final Drawable icon;
        W(AppWidgetProviderInfo i, String l, String a, Drawable d) { info = i; label = l; app = a; icon = d; }
    }

    public static void show(Activity a, boolean night, Host host) {
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
        hint.setText("Los widgets de tus apps (reloj, clima, calendario, música...). Se mueven tomándolos por su franja superior.");
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
                int cw = Math.max(1, Math.round((w.info.minWidth / dp + 30) / 70f)), chh = Math.max(1, Math.round((w.info.minHeight / dp + 30) / 70f));
                ((TextView) col.getChildAt(1)).setText(w.app + " · " + cw + "×" + chh);
                return row;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, pos, id) -> {
            W w = shown.get(pos);
            d.dismiss();
            host.pick(w.info, w.label);
        });
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int af) { }
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) { }
            @Override public void afterTextChanged(Editable e) {
                String q = e.toString().trim().toLowerCase(Locale.ROOT);
                shown.clear();
                for (W w : all) if (q.isEmpty() || w.label.toLowerCase(Locale.ROOT).contains(q) || w.app.toLowerCase(Locale.ROOT).contains(q)) shown.add(w);
                adapter.notifyDataSetChanged();
            }
        });

        d.setContentView(root);
        d.show();

        new Thread(() -> {
            PackageManager pm = a.getPackageManager();
            List<W> out = new ArrayList<>();
            for (AppWidgetProviderInfo info : AppWidgetManager.getInstance(a).getInstalledProviders()) {
                try {
                    String label = info.loadLabel(pm);
                    ApplicationInfo ai = pm.getApplicationInfo(info.provider.getPackageName(), 0);
                    String app = String.valueOf(pm.getApplicationLabel(ai));
                    Drawable icon = info.loadIcon(a, a.getResources().getDisplayMetrics().densityDpi);
                    if (icon == null) icon = pm.getApplicationIcon(ai);
                    out.add(new W(info, label != null ? label : app, app, icon));
                } catch (Exception ignored) { }
            }
            Collator col = Collator.getInstance(new Locale("es"));
            Collections.sort(out, (x, y) -> { int c = col.compare(x.app, y.app); return c != 0 ? c : col.compare(x.label, y.label); });
            a.runOnUiThread(() -> { all.addAll(out); shown.addAll(out); adapter.notifyDataSetChanged(); });
        }, "widgets").start();
    }
}
