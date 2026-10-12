package cl.mesadoble;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
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
 * Gestor de archivos a pantalla completa, uno por teléfono, como las dos ventanas de Total Commander:
 * el principal maneja sus archivos y el extendido los suyos. Lo elegido se copia o mueve a la carpeta que
 * está abierta en el otro teléfono, con botones flotantes o lanzándolo hacia el otro teléfono.
 */
public class FilesActivity extends Activity implements Link.Listener {

    static class Entry { String name; boolean dir; long size, mtime; }
    private static class Wait { final CountDownLatch l = new CountDownLatch(1); JSONObject r; }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private final Map<Integer, Wait> waits = new ConcurrentHashMap<>();
    private final AtomicInteger seq = new AtomicInteger();
    private Hub hub;
    private boolean night, showHidden;
    private int fg, muted, accent;
    private float dirX, dirY = 1;           // hacia dónde está el otro teléfono
    private String path = "", root = "", error;
    private final List<Entry> entries = new ArrayList<>();
    private final Set<String> sel = new HashSet<>();
    private BaseAdapter adapter;
    private android.widget.AbsListView list;      // la vista activa: lista o miniaturas
    private ListView listView;
    private android.widget.GridView gridView;
    private boolean grid;
    private TextView viewChip;
    private final android.util.LruCache<String, android.graphics.Bitmap> thumbs = new android.util.LruCache<>(120);
    private final java.util.Set<String> thumbLoading = new HashSet<>();
    private final ExecutorService thumbWork = Executors.newSingleThreadExecutor();
    private TextView phoneChip, pathText, destText, countText;
    private LinearLayout fab;
    private TextView copyFab, moveFab, renameFab;
    private DragLayer dragLayer;
    private TransferView transfer;
    private boolean remote;   // true en el extendido: muestra los archivos del principal a distancia

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    @Override protected void onCreate(Bundle b) {
        night = Ui.night(this);
        setTheme(night ? android.R.style.Theme_Material_NoActionBar : android.R.style.Theme_Material_Light_NoActionBar);
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        MainActivity.applyOrientation(this);
        hub = Hub.get(this);
        hub.ensureStarted();
        remote = !hub.isPrincipal();
        fg = night ? 0xFFE6EDF3 : 0xFF1D2733;
        muted = night ? 0xFF93A4B5 : 0xFF5B6B7A;
        accent = night ? 0xFF5CC3CF : 0xFF1F7F8C;

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(night ? 0xFF121920 : 0xFFE4E9ED);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(buildHeader(), new LinearLayout.LayoutParams(-1, -2));
        destText = new TextView(this);
        destText.setTextColor(muted);
        destText.setTextSize(12);
        destText.setSingleLine(true);
        destText.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        destText.setPadding(dp(14), 0, dp(14), dp(4));
        col.addView(destText);

        grid = getSharedPreferences("mesa", MODE_PRIVATE).getBoolean("fmGrid", false);
        adapter = buildAdapter();
        listView = new ListView(this);
        listView.setDivider(null);
        gridView = new android.widget.GridView(this);
        gridView.setNumColumns(android.widget.GridView.AUTO_FIT);
        gridView.setColumnWidth(dp(112));
        gridView.setStretchMode(android.widget.GridView.STRETCH_COLUMN_WIDTH);
        gridView.setVerticalSpacing(dp(6));
        gridView.setHorizontalSpacing(dp(6));
        dragLayer = new DragLayer();
        for (android.widget.AbsListView lv : new android.widget.AbsListView[]{listView, gridView}) {
            lv.setFastScrollEnabled(true);
            lv.setClipToPadding(false);
            lv.setPadding(dp(4), 0, dp(4), dp(90));
            lv.setAdapter(adapter);
            lv.setOnItemClickListener((p, v, pos, id) -> {
                if (error != null) { load(); return; }
                Entry e = entries.get(pos);
                if (!sel.isEmpty()) { toggle(e); return; }
                if (e.dir) { path = child(dir(), e.name); load(); }
                else openEntry(e);
            });
            lv.setOnItemLongClickListener((p, v, pos, id) -> {
                if (error == null) { toggle(entries.get(pos)); v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); }
                return true;
            });
            dragLayer.addView(lv, new FrameLayout.LayoutParams(-1, -1));
        }
        setGrid(grid);
        col.addView(dragLayer, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));

        root.addView(buildFab(), fabParams());

        transfer = new TransferView(this, night);
        transfer.setListener(this::cancelTransfer);
        root.addView(transfer, new FrameLayout.LayoutParams(-1, -1));

        setContentView(root);
        updateDirection();
        hub.add(this);
        if (!remote && !FileServer.hasAccess(this)) askAccess(); else load();
    }

    @Override protected void onResume() {
        super.onResume();
        immersive();
        if ((remote || FileServer.hasAccess(this)) && error != null) load();
    }

    @Override public void onWindowFocusChanged(boolean f) { super.onWindowFocusChanged(f); if (f) immersive(); }

    @SuppressWarnings("deprecation")
    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    @Override protected void onDestroy() {
        hub.remove(this);
        work.shutdownNow();
        thumbWork.shutdownNow();
        super.onDestroy();
    }

    @Override public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        updateDirection();
    }

    /** Regla fija: horizontal → principal arriba; vertical → principal a la izquierda. */
    private void updateDirection() {
        boolean landscape = getResources().getConfiguration().orientation != Configuration.ORIENTATION_PORTRAIT;
        boolean p = hub.isPrincipal();
        if (landscape) { dirX = 0; dirY = p ? 1 : -1; } else { dirX = p ? 1 : -1; dirY = 0; }
        transfer.setDirection(dirX, dirY);
        refreshTexts();
    }

    private String arrow() { return dirY > 0 ? "↓" : dirY < 0 ? "↑" : dirX > 0 ? "→" : "←"; }
    private String here() { return hub.isPrincipal() ? "principal" : "extendido"; }
    private String other() { return hub.isPrincipal() ? "extendido" : "principal"; }

    private void askAccess() {
        if (Build.VERSION.SDK_INT < 30) {
            requestPermissions(new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE, android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 41);
            return;
        }
        new Ui.Sheet(this)
                .title("Acceso a los archivos")
                .message("Para administrar los archivos del principal, Android pide activar \"Permitir acceso a todos los archivos\" "
                        + "para Mesa Doble en este teléfono (solo en el principal).")
                .button("Después", false, this::load)
                .button("Activar", true, () -> {
                    try { startActivity(FileServer.accessIntent(this)); } catch (Exception e) { Toast.makeText(this, "No pude abrir ese permiso", Toast.LENGTH_SHORT).show(); }
                })
                .show();
    }

    @Override public void onRequestPermissionsResult(int req, String[] p, int[] r) { super.onRequestPermissionsResult(req, p, r); load(); }

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

    private View buildHeader() {
        HorizontalScrollView sc = new HorizontalScrollView(this);
        sc.setHorizontalScrollBarEnabled(false);
        sc.setFillViewport(true);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(10), dp(10), dp(6));
        phoneChip = chip("", null);
        phoneChip.setTypeface(Typeface.DEFAULT_BOLD);
        phoneChip.setTextColor(night ? 0xFF18212B : 0xFFFFFFFF);
        phoneChip.setBackground(round(accent, 12));
        row.addView(phoneChip, new LinearLayout.LayoutParams(-2, dp(38)));
        TextView up = chip("↑", v -> goUp());
        LinearLayout.LayoutParams ul = new LinearLayout.LayoutParams(dp(46), dp(38));
        ul.setMarginStart(dp(6));
        row.addView(up, ul);
        pathText = new TextView(this);
        pathText.setTextColor(fg);
        pathText.setTextSize(14);
        pathText.setSingleLine(true);
        pathText.setEllipsize(TextUtils.TruncateAt.START);
        pathText.setPadding(dp(10), 0, dp(8), 0);
        row.addView(pathText, new LinearLayout.LayoutParams(0, -2, 1));
        countText = new TextView(this);
        countText.setTextColor(muted);
        countText.setTextSize(12);
        countText.setPadding(0, 0, dp(8), 0);
        row.addView(countText);
        viewChip = chip("", v -> setGrid(!grid));
        for (TextView t : new TextView[]{viewChip, chip("+ Carpeta", v -> newFolder()), chip("Ocultos", v -> { showHidden = !showHidden; load(); }),
                chip("Cerrar", v -> { if (hub.connected()) hub.send("fmClose"); finish(); })}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(38));
            lp.setMarginStart(dp(6));
            row.addView(t, lp);
        }
        sc.addView(row, new FrameLayout.LayoutParams(-1, -2));
        return sc;
    }

    /** Lista (con detalles) o miniaturas (cuadrícula con fotos y videos en pequeño). */
    private void setGrid(boolean g) {
        grid = g;
        getSharedPreferences("mesa", MODE_PRIVATE).edit().putBoolean("fmGrid", g).apply();
        listView.setVisibility(g ? View.GONE : View.VISIBLE);
        gridView.setVisibility(g ? View.VISIBLE : View.GONE);
        list = g ? gridView : listView;
        if (viewChip != null) viewChip.setText(g ? "☰ Lista" : "▦ Miniaturas");
        adapter.notifyDataSetChanged();
    }

    private static boolean thumbable(String n) {
        String t = FilesProvider.mime(n);
        return t.startsWith("image/") || t.startsWith("video/");
    }

    /** Miniatura de una foto o video del principal (aquí mismo, o pedida al principal). */
    private void thumbInto(android.widget.ImageView iv, TextView emoji, Entry e) {
        String p = child(dir(), e.name);
        iv.setTag(p);
        android.graphics.Bitmap b = thumbs.get(p);
        if (b != null) { iv.setImageBitmap(b); iv.setVisibility(View.VISIBLE); emoji.setVisibility(View.INVISIBLE); return; }
        iv.setImageDrawable(null); iv.setVisibility(View.INVISIBLE); emoji.setVisibility(View.VISIBLE);
        if (!thumbable(e.name) || !thumbLoading.add(p)) return;
        final int size = dp(160);
        thumbWork.execute(() -> {
            android.graphics.Bitmap r = null;
            try {
                if (!remote) r = FileServer.thumb(new File(p), size);
                else {
                    JSONObject res = request("fsThumb", "path", p, "size", size);
                    byte[] bytes = android.util.Base64.decode(res.optString("img"), android.util.Base64.DEFAULT);
                    if (bytes.length > 0) r = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                }
            } catch (Exception ignored) { }
            final android.graphics.Bitmap fr = r;
            main.post(() -> {
                thumbLoading.remove(p);
                if (fr != null) { thumbs.put(p, fr); adapter.notifyDataSetChanged(); }
            });
        });
    }

    private BaseAdapter buildAdapter() {
        return new BaseAdapter() {
            @Override public int getCount() { return error != null ? 1 : entries.size(); }
            @Override public Object getItem(int i) { return i; }
            @Override public long getItemId(int i) { return i; }
            @Override public int getViewTypeCount() { return 2; }
            @Override public int getItemViewType(int i) { return grid && error == null ? 1 : 0; }
            @Override public View getView(int i, View v, ViewGroup parent) {
                if (getItemViewType(i) == 1) return cell(i, v);
                LinearLayout row = (LinearLayout) v;
                if (row == null) {
                    row = new LinearLayout(FilesActivity.this);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(dp(10), dp(8), dp(14), dp(8));
                    FrameLayout icBox = new FrameLayout(FilesActivity.this);
                    TextView ic = new TextView(FilesActivity.this);
                    ic.setTextSize(24);
                    ic.setGravity(Gravity.CENTER);
                    icBox.addView(ic, new FrameLayout.LayoutParams(-1, -1));
                    android.widget.ImageView th = new android.widget.ImageView(FilesActivity.this);
                    th.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
                    th.setClipToOutline(true);
                    th.setBackground(round(0x22000000, 8));
                    icBox.addView(th, new FrameLayout.LayoutParams(-1, -1));
                    row.addView(icBox, new LinearLayout.LayoutParams(dp(46), dp(46)));
                    LinearLayout c = new LinearLayout(FilesActivity.this);
                    c.setOrientation(LinearLayout.VERTICAL);
                    c.setPadding(dp(12), 0, 0, 0);
                    TextView t1 = new TextView(FilesActivity.this); t1.setTextSize(16); t1.setSingleLine(true); t1.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                    TextView t2 = new TextView(FilesActivity.this); t2.setTextSize(12);
                    c.addView(t1); c.addView(t2);
                    row.addView(c, new LinearLayout.LayoutParams(0, -2, 1));
                    TextView check = new TextView(FilesActivity.this);
                    check.setTextSize(18);
                    row.addView(check);
                }
                FrameLayout icBox = (FrameLayout) row.getChildAt(0);
                TextView ic = (TextView) icBox.getChildAt(0);
                android.widget.ImageView th = (android.widget.ImageView) icBox.getChildAt(1);
                LinearLayout c = (LinearLayout) row.getChildAt(1);
                TextView t1 = (TextView) c.getChildAt(0), t2 = (TextView) c.getChildAt(1), check = (TextView) row.getChildAt(2);
                t1.setTextColor(fg); t2.setTextColor(muted); check.setTextColor(accent);
                if (error != null) {
                    ic.setText("⚠"); ic.setVisibility(View.VISIBLE); th.setVisibility(View.INVISIBLE);
                    t1.setSingleLine(false); t1.setText(error); t2.setText("Toca para reintentar"); check.setText("");
                    row.setBackground(null);
                    return row;
                }
                t1.setSingleLine(true);
                Entry e = entries.get(i);
                ic.setText(e.dir ? "📁" : icon(e.name));
                if (e.dir) { th.setVisibility(View.INVISIBLE); ic.setVisibility(View.VISIBLE); } else thumbInto(th, ic, e);
                t1.setText(e.name);
                String date = e.mtime > 0 ? DateFormat.getDateInstance(DateFormat.MEDIUM).format(new Date(e.mtime)) : "";
                t2.setText(e.dir ? date : Formatter.formatShortFileSize(FilesActivity.this, e.size) + " · " + date);
                boolean s = sel.contains(e.name);
                check.setText(s ? "✓" : "");
                row.setBackground(s ? round((accent & 0x00FFFFFF) | 0x3A000000, 12) : null);
                return row;
            }

            /** Celda de miniaturas: imagen grande (o ícono) y el nombre abajo. */
            private View cell(int i, View v) {
                LinearLayout cell = (LinearLayout) v;
                if (cell == null) {
                    cell = new LinearLayout(FilesActivity.this);
                    cell.setOrientation(LinearLayout.VERTICAL);
                    cell.setGravity(Gravity.CENTER_HORIZONTAL);
                    cell.setPadding(dp(6), dp(6), dp(6), dp(6));
                    FrameLayout box = new FrameLayout(FilesActivity.this);
                    box.setBackground(round(night ? 0xFF1E2934 : 0xFFF4F7F9, 12));
                    TextView ic = new TextView(FilesActivity.this);
                    ic.setTextSize(40);
                    ic.setGravity(Gravity.CENTER);
                    box.addView(ic, new FrameLayout.LayoutParams(-1, -1));
                    android.widget.ImageView th = new android.widget.ImageView(FilesActivity.this);
                    th.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
                    th.setClipToOutline(true);
                    th.setBackground(round(0x00000000, 12));
                    box.addView(th, new FrameLayout.LayoutParams(-1, -1));
                    TextView check = new TextView(FilesActivity.this);
                    check.setTextSize(16);
                    check.setGravity(Gravity.CENTER);
                    check.setTextColor(night ? 0xFF18212B : 0xFFFFFFFF);
                    FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(dp(26), dp(26), Gravity.TOP | Gravity.END);
                    cl.setMargins(dp(4), dp(4), dp(4), dp(4));
                    box.addView(check, cl);
                    cell.addView(box, new LinearLayout.LayoutParams(-1, dp(96)));
                    TextView name = new TextView(FilesActivity.this);
                    name.setTextSize(12);
                    name.setMaxLines(2);
                    name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                    name.setGravity(Gravity.CENTER_HORIZONTAL);
                    name.setPadding(0, dp(4), 0, 0);
                    cell.addView(name, new LinearLayout.LayoutParams(-1, -2));
                }
                FrameLayout box = (FrameLayout) cell.getChildAt(0);
                TextView ic = (TextView) box.getChildAt(0), check = (TextView) box.getChildAt(2), name = (TextView) cell.getChildAt(1);
                android.widget.ImageView th = (android.widget.ImageView) box.getChildAt(1);
                Entry e = entries.get(i);
                ic.setText(e.dir ? "📁" : icon(e.name));
                if (e.dir) { th.setVisibility(View.INVISIBLE); ic.setVisibility(View.VISIBLE); } else thumbInto(th, ic, e);
                name.setText(e.name);
                name.setTextColor(fg);
                boolean s = sel.contains(e.name);
                check.setText(s ? "✓" : "");
                check.setBackground(s ? round(accent, 13) : null);
                cell.setBackground(s ? round((accent & 0x00FFFFFF) | 0x3A000000, 14) : null);
                return cell;
            }
        };
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

    /** Botones flotantes: aparecen al elegir uno o más archivos. */
    private View buildFab() {
        fab = new LinearLayout(this);
        fab.setGravity(Gravity.CENTER_VERTICAL);
        fab.setPadding(dp(8), dp(8), dp(8), dp(8));
        fab.setBackground(round(night ? 0xF2223040 : 0xF7FFFFFF, 26));
        fab.setElevation(dp(10));
        copyFab = chip("", v -> startTransfer(false));
        moveFab = chip("", v -> startTransfer(true));
        copyFab.setTextColor(night ? 0xFF18212B : 0xFFFFFFFF);
        copyFab.setBackground(round(accent, 18));
        moveFab.setBackground(round((accent & 0x00FFFFFF) | 0x33000000, 18));
        moveFab.setTextColor(accent);
        TextView del = chip("Eliminar", v -> deleteSel());
        del.setTextColor(night ? 0xFFF0A26B : 0xFFB4541A);
        del.setBackground(round(night ? 0x33F0A26B : 0x22B4541A, 18));
        renameFab = chip("Renombrar", v -> renameSel());
        TextView clear = chip("✕", v -> { sel.clear(); adapter.notifyDataSetChanged(); refreshTexts(); });
        for (TextView t : new TextView[]{copyFab, moveFab, del, renameFab, clear}) {
            t.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(46));
            lp.setMarginStart(dp(4)); lp.setMarginEnd(dp(4));
            fab.addView(t, lp);
        }
        fab.setVisibility(View.GONE);
        return fab;
    }

    private FrameLayout.LayoutParams fabParams() {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = dp(18);
        return lp;
    }

    private void showFab(boolean show) {
        if (show == (fab.getVisibility() == View.VISIBLE)) return;
        if (show) {
            fab.setVisibility(View.VISIBLE);
            fab.setAlpha(0); fab.setTranslationY(dp(30));
            fab.animate().alpha(1).translationY(0).setDuration(180).setInterpolator(new DecelerateInterpolator()).setListener(null).start();
        } else {
            fab.animate().alpha(0).translationY(dp(30)).setDuration(150).withEndAction(() -> fab.setVisibility(View.GONE)).start();
        }
    }

    private void refreshTexts() {
        if (phoneChip == null) return;
        // las dos ventanas muestran el teléfono PRINCIPAL; cada pantalla maneja una
        boolean landscape = dirY != 0;
        String mine = hub.isPrincipal() ? (landscape ? "arriba" : "izquierda") : (landscape ? "abajo" : "derecha");
        String theirs = hub.isPrincipal() ? (landscape ? "abajo" : "derecha") : (landscape ? "arriba" : "izquierda");
        phoneChip.setText("Archivos del principal · ventana " + mine);
        String shown = dir();
        if (!root.isEmpty() && shown.startsWith(root)) shown = "Almacenamiento" + shown.substring(root.length());
        pathText.setText(shown);
        countText.setText(error != null ? "" : sel.isEmpty() ? entries.size() + "" : sel.size() + " elegidos");
        String rd = FileServer.remoteDir;
        String rn = rd == null || rd.isEmpty() ? "Almacenamiento" : nameOf(rd);
        destText.setText(hub.connected()
                ? "Ventana de " + theirs + " " + arrow() + " " + rn + "  ·  mantén presionado para elegir; arrastra o lanza hacia allá para mover"
                : (remote ? "El principal no está conectado" : "El extendido no está conectado: la otra ventana no está disponible"));
        copyFab.setText("Copiar " + arrow());
        moveFab.setText("Mover " + arrow());
        renameFab.setVisibility(sel.size() == 1 ? View.VISIBLE : View.GONE);
        showFab(!sel.isEmpty());
    }

    private void toggle(Entry e) {
        if (!sel.remove(e.name)) sel.add(e.name);
        adapter.notifyDataSetChanged();
        refreshTexts();
    }

    private static String child(String dir, String name) { return dir.endsWith("/") ? dir + name : dir + "/" + name; }
    private static String nameOf(String p) { int i = p.lastIndexOf('/'); return i >= 0 ? p.substring(i + 1) : p; }
    private String dir() { return path.isEmpty() ? root : path; }

    private void goUp() {
        String cur = dir();
        if (cur.isEmpty() || cur.equals(root)) return;
        int i = cur.lastIndexOf('/');
        path = i > 0 ? cur.substring(0, i) : root;
        load();
    }

    // ---------------- lista ----------------

    /** Lista una carpeta del PRINCIPAL: directo si este es el principal, o pidiéndola por la conexión. */
    private void load() {
        final String p = path;
        work.execute(() -> {
            List<Entry> out = new ArrayList<>();
            String err = null, resolved = p, rt = "";
            try {
                JSONArray arr;
                if (!remote) {
                    if (!FileServer.hasAccess(this)) throw new IOException("Falta el permiso de archivos en este teléfono. Toca para reintentar después de activarlo.");
                    File d = FileServer.resolve(p);
                    resolved = d.getAbsolutePath();
                    rt = FileServer.root().getAbsolutePath();
                    arr = FileServer.list(d);
                } else {
                    JSONObject r = request("fsList", "path", p);
                    if (r.has("err")) throw new IOException(r.optString("err"));
                    resolved = r.optString("path");
                    rt = r.optString("root");
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
            } catch (Exception e) { err = e.getMessage() != null ? e.getMessage() : "No pude leer la carpeta"; }
            final String fe = err, fr = resolved, frt = rt;
            main.post(() -> {
                error = fe;
                entries.clear();
                if (fe == null) { entries.addAll(out); path = fr; root = frt; }
                Set<String> names = new HashSet<>();
                for (Entry e : entries) names.add(e.name);
                sel.retainAll(names);
                adapter.notifyDataSetChanged();
                refreshTexts();
                if (fe == null && hub.connected()) hub.send("fmPath", "path", path);   // la otra ventana sabe dónde dejar lo que se copie
            });
        });
    }

    private List<Entry> selected() {
        List<Entry> l = new ArrayList<>();
        for (Entry e : entries) if (sel.contains(e.name)) l.add(e);
        return l;
    }

    // ---------------- mensajes ----------------

    @Override public void onState(int state, String info) {
        main.post(() -> {
            refreshTexts();
            if (state == Link.CONNECTED) { if (remote) load(); else if (error == null) hub.send("fmPath", "path", path); }
        });
    }

    private String mySide() { return hub.isPrincipal() ? "p" : "e"; }

    @Override public void onMessage(JSONObject m) {
        String t = m.optString("t");
        switch (t) {
            case "fsListR": case "fsOpR": case "fsThumbR": {
                Wait w = waits.get(m.optInt("req"));
                if (w != null) { w.r = m; w.l.countDown(); }
                break;
            }
            case "fmPath": main.post(this::refreshTexts); break;
            case "fmClose": main.post(this::finish); break;
            case "fmAnim":   // avance de una copia (corre en el principal): las hojas vuelan de una ventana a la otra
                main.post(() -> {
                    String s = m.optString("s");
                    boolean mine = mySide().equals(m.optString("src"));
                    if ("begin".equals(s)) transfer.begin(mine, m.optString("title"), dirX, dirY, true);
                    else if ("prog".equals(s)) transfer.update(m.optString("name"), (float) m.optDouble("p", -1), m.optString("d"));
                    else if ("end".equals(s)) {
                        transfer.end();
                        sel.clear();
                        load();
                        String msg = m.optString("msg");
                        if (mine && !msg.isEmpty()) Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                    }
                });
                break;
        }
    }

    // ---------------- pedidos al principal ----------------

    private JSONObject request(String t, Object... kv) throws IOException {
        if (!hub.connected()) throw new IOException("El principal no está conectado");
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
            if (!w.l.await(25, TimeUnit.SECONDS)) throw new IOException("El principal no responde");
        } catch (InterruptedException e) { throw new IOException("Cancelado"); }
        finally { waits.remove(id); }
        if ("permiso".equals(w.r.optString("err")))
            throw new IOException("Falta el permiso de archivos en el principal: toca el aviso que apareció allá");
        if ("fsOpR".equals(w.r.optString("t")) && !w.r.optBoolean("ok")) throw new IOException(w.r.optString("err", "No se pudo"));
        return w.r;
    }

    /** mkdir, delete, rename en el principal (aquí mismo o pidiéndolo). */
    private void op(String op, Object... kv) throws IOException {
        if (remote) {
            Object[] all = new Object[kv.length + 2];
            all[0] = "op"; all[1] = op;
            System.arraycopy(kv, 0, all, 2, kv.length);
            request("fsOp", all);
            return;
        }
        JSONObject m = new JSONObject(), r = new JSONObject();
        try {
            m.put("op", op);
            for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
            FileServer.op(m, r);
        } catch (JSONException ignored) { }
        if (!r.optBoolean("ok")) throw new IOException(r.optString("err", "No se pudo"));
    }

    /** Copiar o mover lo elegido a la carpeta abierta en la otra ventana (las dos son del principal). */
    private void startTransfer(boolean move) {
        List<Entry> items = selected();
        if (items.isEmpty()) return;
        if (!hub.connected()) { Toast.makeText(this, "El otro teléfono no está conectado", Toast.LENGTH_SHORT).show(); return; }
        if (transfer.isActive()) return;
        String to = FileServer.remoteDir == null ? "" : FileServer.remoteDir;
        if (move && to.equals(dir())) { Toast.makeText(this, "La otra ventana está en la misma carpeta", Toast.LENGTH_SHORT).show(); return; }
        JSONArray arr = new JSONArray();
        for (Entry e : items) arr.put(child(dir(), e.name));
        String what = items.size() == 1 ? items.get(0).name : items.size() + " elementos";
        String dest = to.isEmpty() ? "Almacenamiento" : nameOf(to);
        JSONObject job = new JSONObject();
        try {
            job.put("t", "fmJob"); job.put("items", arr); job.put("to", to); job.put("move", move); job.put("src", mySide());
            job.put("title", (move ? "Moviendo " : "Copiando ") + what + " a " + dest);
        } catch (JSONException ignored) { }
        showFab(false);
        if (remote) hub.send(job); else FileServer.get(this).runJob(job, hub);
    }

    private void cancelTransfer() {
        if (remote) hub.send("fmCancel"); else FileServer.get(this).cancelJob();
        transfer.update("Cancelando…", -1, "");
    }

    /** Abrir: los archivos son del principal y se abren en el principal (como las apps). */
    private void openEntry(Entry e) {
        String p = child(dir(), e.name);
        String kind = MediaHub.kindOf(e.name);
        if (kind != null) {   // fotos, música y videos: visores propios de la app, en el principal
            java.util.ArrayList<String> paths = new java.util.ArrayList<>();
            int idx = 0;
            for (Entry x : entries) if (!x.dir && kind.equals(MediaHub.kindOf(x.name))) { if (x == e) idx = paths.size(); paths.add(child(dir(), x.name)); }
            if (!remote) MediaHub.open(this, kind, paths, idx);
            else if (hub.connected()) { MediaHub.requestPlay(hub, kind, paths, idx); Toast.makeText(this, "Abriendo en el principal", Toast.LENGTH_SHORT).show(); }
            else Toast.makeText(this, "El principal no está conectado", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!remote) { view(new File(p)); return; }
        if (!hub.connected()) { Toast.makeText(this, "El principal no está conectado", Toast.LENGTH_SHORT).show(); return; }
        hub.send("fmOpenFile", "path", p);
        Toast.makeText(this, "Abriendo " + e.name + " en el principal", Toast.LENGTH_SHORT).show();
    }

    // ---------------- eliminar, renombrar, nueva carpeta ----------------

    private void background(Runnable done, IoTask task) {
        work.execute(() -> {
            String err = null;
            try { task.run(); } catch (IOException e) { err = e.getMessage(); }
            final String fe = err;
            main.post(() -> { if (fe != null) Toast.makeText(this, fe, Toast.LENGTH_LONG).show(); if (done != null) done.run(); });
        });
    }

    private interface IoTask { void run() throws IOException; }

    private void deleteSel() {
        List<Entry> items = selected();
        if (items.isEmpty()) return;
        new Ui.Sheet(this)
                .title("Eliminar " + (items.size() == 1 ? "\"" + items.get(0).name + "\"" : items.size() + " elementos"))
                .message("Se borran del teléfono principal. No se puede deshacer.")
                .button("Cancelar", false, null)
                .button("Eliminar", true, () -> {
                    final String d = dir();
                    background(() -> { sel.clear(); load(); }, () -> {
                        for (Entry e : items) op("delete", "path", child(d, e.name));
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
        List<Entry> items = selected();
        if (items.size() != 1) return;
        Entry e = items.get(0);
        EditText f = field(e.name);
        new Ui.Sheet(this).title("Cambiar nombre").view(f)
                .button("Cancelar", false, null)
                .button("Guardar", true, () -> {
                    String nn = f.getText().toString().trim();
                    if (nn.isEmpty() || nn.contains("/") || nn.equals(e.name)) return;
                    final String d = dir();
                    background(() -> { sel.clear(); load(); }, () -> op("rename", "path", child(d, e.name), "name", nn));
                })
                .show();
    }

    private void newFolder() {
        EditText f = field("");
        f.setHint("Nombre de la carpeta");
        new Ui.Sheet(this).title("Nueva carpeta").view(f)
                .button("Cancelar", false, null)
                .button("Crear", true, () -> {
                    String nn = f.getText().toString().trim();
                    if (nn.isEmpty() || nn.contains("/")) return;
                    final String d = dir();
                    background(this::load, () -> op("mkdir", "path", d, "name", nn));
                })
                .show();
    }

    private void view(File f) {
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(FilesProvider.uriFor(this, f), FilesProvider.mime(f.getName()));
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivity(i); }
        catch (Exception ex) { Toast.makeText(this, "No hay una app para abrir " + f.getName(), Toast.LENGTH_SHORT).show(); }
    }

    // ---------------- arrastrar y lanzar ----------------

    /**
     * Sobre la lista: si se toca un archivo elegido y se arrastra, los elegidos siguen al dedo como un montón
     * de hojas. Soltarlos contra el borde del otro teléfono, o lanzarlos hacia allá, los MUEVE.
     */
    private class DragLayer extends FrameLayout {
        private final int slop = ViewConfiguration.get(FilesActivity.this).getScaledTouchSlop();
        private float downX, downY, gx, gy, homeX, homeY;
        private int downPos = -1;
        private boolean dragging, flying;
        private VelocityTracker vt;
        private final Paint paper = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG),
                badge = new Paint(Paint.ANTI_ALIAS_FLAG), badgeText = new Paint(Paint.ANTI_ALIAS_FLAG), glow = new Paint();

        DragLayer() {
            super(FilesActivity.this);
            setWillNotDraw(false);
            paper.setColor(0xFFFFFFFF);
            paper.setShadowLayer(6 * getResources().getDisplayMetrics().density, 0, 3, 0x55000000);
            line.setColor(0xFFB8C4CE);
            line.setStrokeWidth(dp(1.6f));
            badge.setColor(accent);
            badgeText.setColor(night ? 0xFF18212B : 0xFFFFFFFF);
            badgeText.setTextSize(dp(13));
            badgeText.setTextAlign(Paint.Align.CENTER);
            badgeText.setTypeface(Typeface.DEFAULT_BOLD);
            setLayerType(LAYER_TYPE_SOFTWARE, null);
        }

        private boolean onSelected(int pos) { return error == null && pos >= 0 && pos < entries.size() && sel.contains(entries.get(pos).name); }

        @Override public boolean onInterceptTouchEvent(MotionEvent e) {
            if (flying) return true;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getX(); downY = e.getY(); dragging = false;
                    downPos = list.pointToPosition((int) downX, (int) downY);
                    if (vt != null) vt.recycle();
                    vt = VelocityTracker.obtain();
                    vt.addMovement(e);
                    return false;
                case MotionEvent.ACTION_MOVE:
                    if (vt != null) vt.addMovement(e);
                    // se revisa en cada movimiento: mantener presionado elige el archivo y, sin soltar, ya se puede arrastrar
                    if (!dragging && onSelected(downPos) && Math.hypot(e.getX() - downX, e.getY() - downY) > slop * 1.5f) {
                        dragging = true;
                        homeX = downX; homeY = downY;
                        gx = e.getX(); gy = e.getY();
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                        invalidate();
                        return true;
                    }
                    return false;
            }
            return false;
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (!dragging) return false;
            if (vt != null) vt.addMovement(e);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_MOVE:
                    gx = e.getX(); gy = e.getY();
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP: case MotionEvent.ACTION_CANCEL: {
                    float vx = 0, vy = 0;
                    if (vt != null) { vt.computeCurrentVelocity(1000); vx = vt.getXVelocity(); vy = vt.getYVelocity(); vt.recycle(); vt = null; }
                    float toward = vx * dirX + vy * dirY;   // px/s hacia el otro teléfono
                    float edgeDist = dirY > 0 ? getHeight() - gy : dirY < 0 ? gy : dirX > 0 ? getWidth() - gx : gx;
                    float extent = dirY != 0 ? getHeight() : getWidth();
                    boolean go = e.getActionMasked() == MotionEvent.ACTION_UP && hub.connected()
                            && (toward > dp(900) || edgeDist < extent * 0.14f);
                    if (go) flyOut(); else flyBack();
                    return true;
                }
            }
            return true;
        }

        private void flyOut() {
            flying = true;
            final float sx = gx, sy = gy;
            final float tx = dirX > 0 ? getWidth() + dp(80) : dirX < 0 ? -dp(80) : gx;
            final float ty = dirY > 0 ? getHeight() + dp(80) : dirY < 0 ? -dp(80) : gy;
            ValueAnimator a = ValueAnimator.ofFloat(0, 1);
            a.setDuration(260);
            a.addUpdateListener(v -> { float t = (float) v.getAnimatedValue(); gx = sx + (tx - sx) * t; gy = sy + (ty - sy) * t; invalidate(); });
            a.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(android.animation.Animator an) {
                    flying = false; dragging = false; invalidate();
                    startTransfer(true);
                }
            });
            a.start();
        }

        private void flyBack() {
            flying = true;
            final float sx = gx, sy = gy;
            ValueAnimator a = ValueAnimator.ofFloat(0, 1);
            a.setDuration(220);
            a.setInterpolator(new DecelerateInterpolator());
            a.addUpdateListener(v -> { float t = (float) v.getAnimatedValue(); gx = sx + (homeX - sx) * t; gy = sy + (homeY - sy) * t; invalidate(); });
            a.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(android.animation.Animator an) { flying = false; dragging = false; invalidate(); }
            });
            a.start();
        }

        @Override protected void dispatchDraw(Canvas c) {
            super.dispatchDraw(c);
            if (!dragging) return;
            int W = getWidth(), H = getHeight();
            // brillo en el borde del otro teléfono: más fuerte mientras más cerca
            float edgeDist = dirY > 0 ? H - gy : dirY < 0 ? gy : dirX > 0 ? W - gx : gx;
            float extent = dirY != 0 ? H : W;
            int al = (int) (80 + 175 * Math.max(0, 1 - edgeDist / (extent * 0.5f)));
            int col = (accent & 0x00FFFFFF) | (al << 24);
            float band = dp(36);
            if (dirY > 0) { glow.setShader(new LinearGradient(0, H, 0, H - band, col, 0, Shader.TileMode.CLAMP)); c.drawRect(0, H - band, W, H, glow); }
            else if (dirY < 0) { glow.setShader(new LinearGradient(0, 0, 0, band, col, 0, Shader.TileMode.CLAMP)); c.drawRect(0, 0, W, band, glow); }
            else if (dirX > 0) { glow.setShader(new LinearGradient(W, 0, W - band, 0, col, 0, Shader.TileMode.CLAMP)); c.drawRect(W - band, 0, W, H, glow); }
            else { glow.setShader(new LinearGradient(0, 0, band, 0, col, 0, Shader.TileMode.CLAMP)); c.drawRect(0, 0, band, H, glow); }
            // montón de hojas bajo el dedo
            int n = Math.min(3, sel.size());
            for (int i = n - 1; i >= 0; i--) drawPaper(c, gx + i * dp(5), gy - dp(40) + i * dp(4), (i - 1) * 7f);
            c.drawCircle(gx + dp(22), gy - dp(62), dp(13), badge);
            c.drawText(String.valueOf(sel.size()), gx + dp(22), gy - dp(57), badgeText);
        }

        private void drawPaper(Canvas c, float x, float y, float rot) {
            float w = dp(38), h = dp(48), f = dp(11);
            c.save();
            c.translate(x, y);
            c.rotate(rot);
            Path p = new Path();
            p.moveTo(-w / 2, -h / 2); p.lineTo(w / 2 - f, -h / 2); p.lineTo(w / 2, -h / 2 + f); p.lineTo(w / 2, h / 2); p.lineTo(-w / 2, h / 2); p.close();
            c.drawPath(p, paper);
            for (int i = 0; i < 4; i++) {
                float ly = -h / 2 + h * (0.3f + i * 0.17f);
                c.drawLine(-w / 2 + dp(6), ly, w / 2 - dp(6), ly, line);
            }
            c.restore();
        }
    }
}
