package cl.mesadoble;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * DUAL-Draw: programa de dibujo de pantalla doble.
 *  - "view" (PRINCIPAL): el dibujo completo, en vivo, con un marco que muestra la zona en que se trabaja abajo.
 *  - "edit" (EXTENDIDO): el lienzo con zoom (dos dedos), pinceles con presión, colores, capas, deshacer y guardar.
 *  - "solo" (un teléfono): las dos cosas, arriba y abajo.
 * Los dos teléfonos tienen el mismo dibujo (DrawDoc) y se mandan las mismas órdenes:
 *   dw {op…} · dwFull {layers, cur} · dwHello (el visor pide el dibujo) · dwView {r} · dwOpen · dwClose
 */
public class DualDrawActivity extends Activity implements Link.Listener {

    static volatile boolean open;
    static DualDrawActivity live;

    private static final int ORANGE = 0xFFF57C1F, BG = 0xFF14181C, CARD = 0xFF222A31;

    private final Handler h = new Handler(Looper.getMainLooper());
    private Hub hub;
    private SharedPreferences prefs;
    private String mode;
    private boolean closing;
    static DrawDoc doc;   // uno por proceso (sobrevive a girar la pantalla)

    private Board editor, viewer;
    // pincel elegido
    private int brush = DrawDoc.INK, color = 0xFF1B1B1B;
    private float size = 10, opacity = 1f;
    private final List<TextView> brushBtns = new ArrayList<>();
    private View swatch;
    private LinearLayout recentRow;
    private TextView layersBtn;

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private boolean editing() { return !"view".equals(mode); }
    private boolean linked() { return !"solo".equals(mode); }

    static Intent intent(android.content.Context c, String mode) {
        return new Intent(c, DualDrawActivity.class).putExtra("mode", mode).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        live = this;
        hub = Hub.get(this);
        prefs = getSharedPreferences("mesa", MODE_PRIVATE);
        mode = getIntent().getStringExtra("mode");
        if (mode == null) mode = "solo";
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        brush = prefs.getInt("dwBrush", DrawDoc.INK);
        color = prefs.getInt("dwColor", 0xFF1B1B1B);
        size = prefs.getFloat("dwSize", 10);
        opacity = prefs.getFloat("dwOpacity", 1f);
        if (doc == null) { doc = new DrawDoc(); if (editing()) load(); }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        if ("view".equals(mode)) {
            viewer = new Board(this, false);
            root.addView(viewer, new FrameLayout.LayoutParams(-1, -1));
            root.addView(title(), new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START));
            TextView close = chip("✕", v -> closeBoth());
            FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(dp(40), dp(40), Gravity.TOP | Gravity.END);
            cl.setMargins(0, dp(8), dp(8), 0);
            root.addView(close, cl);
        } else {
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            if ("solo".equals(mode)) {
                viewer = new Board(this, false);
                col.addView(viewer, new LinearLayout.LayoutParams(-1, 0, 1));
            }
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            editor = new Board(this, true);
            row.addView(editor, new LinearLayout.LayoutParams(0, -1, 1));
            row.addView(toolbar(), new LinearLayout.LayoutParams(dp(150), -1));
            col.addView(row, new LinearLayout.LayoutParams(-1, 0, 1));
            root.addView(col, new FrameLayout.LayoutParams(-1, -1));
        }
        setContentView(root);
        immersive();
    }

    private TextView title() {
        TextView t = new TextView(this);
        t.setText("DUAL-Draw");
        t.setTypeface(Intro.font(this));
        t.setTextSize(18);
        t.setTextColor(0xCCFFFFFF);
        t.setPadding(dp(14), dp(10), dp(14), dp(10));
        return t;
    }

    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override public void onWindowFocusChanged(boolean f) { super.onWindowFocusChanged(f); if (f) immersive(); }

    @Override protected void onResume() {
        super.onResume();
        open = true;
        hub.add(this);
        if (linked() && hub.connected()) {
            if (editing()) sendFull(); else hub.send("dwHello");
        }
        refreshAll();
    }

    @Override protected void onPause() {
        open = false;
        hub.remove(this);
        if (editing()) save();
        if (isFinishing() && !closing && linked() && hub.connected()) hub.send("dwClose");
        super.onPause();
    }

    @Override protected void onDestroy() { if (live == this) live = null; super.onDestroy(); }

    private void closeBoth() {
        if (linked() && hub.connected()) hub.send("dwClose");
        closing = true;
        finish();
    }

    // ---------------- guardar (el dibujo vive en el extendido) ----------------

    private File file() { return new File(getFilesDir(), "dualdraw.json"); }

    private void load() {
        File f = file();
        if (!f.exists()) return;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] d = new byte[(int) f.length()];
            int n = 0, r;
            while (n < d.length && (r = in.read(d, n, d.length - n)) > 0) n += r;
            doc.fromJson(new JSONObject(new String(d, 0, n, StandardCharsets.UTF_8)));
        } catch (Exception ignored) { }
    }

    private void save() {
        final DrawDoc.Snap snap = doc.snapshot();
        new Thread(() -> {
            final String data = DrawDoc.toJson(snap).toString();
            File tmp = new File(getFilesDir(), "dualdraw.json.tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) { out.write(data.getBytes(StandardCharsets.UTF_8)); }
            catch (Exception e) { return; }
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(file());
        }, "dualdraw-guardar").start();
    }

    private void sendFull() {
        final DrawDoc.Snap snap = doc.snapshot();
        new Thread(() -> {
            JSONObject o = DrawDoc.toJson(snap);
            try { o.put("t", "dwFull"); } catch (Exception ignored) { }
            hub.send(o);
        }, "dualdraw-enviar").start();
    }

    /** Una orden: se aplica aquí y se manda al otro teléfono. */
    private void op(JSONObject m) {
        doc.apply(m);
        if (linked() && hub.connected()) {
            try { m.put("t", "dw"); } catch (Exception ignored) { }
            hub.send(m);
        }
        refreshAll();
    }

    private void op(String op, Object... kv) {
        JSONObject m = new JSONObject();
        try {
            m.put("op", op);
            for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        } catch (Exception ignored) { }
        op(m);
    }

    private void refreshAll() {
        if (editor != null) editor.invalidate();
        if (viewer != null) viewer.invalidate();
        if (layersBtn != null) layersBtn.setText("Capas · " + (doc.cur + 1) + "/" + doc.layers.size());
    }

    @Override public void onMessage(JSONObject m) {
        String t = m.optString("t");
        switch (t) {
            case "dw": h.post(() -> { doc.apply(m); refreshAll(); }); break;
            case "dwFull": h.post(() -> { doc.fromJson(m); refreshAll(); }); break;
            case "dwHello": if (editing()) sendFull(); break;
            case "dwView":
                JSONArray r = m.optJSONArray("r");
                if (r != null && viewer != null) h.post(() -> { viewer.frame = new RectF((float) r.optDouble(0), (float) r.optDouble(1), (float) r.optDouble(2), (float) r.optDouble(3)); viewer.invalidate(); });
                break;
            case "dwClose": h.post(() -> { closing = true; finish(); }); break;
        }
    }

    @Override public void onState(int state, String info) {
        if (state == Link.CONNECTED && open && linked()) h.post(() -> { if (editing()) sendFull(); else hub.send("dwHello"); });
    }

    // ---------------- barra de herramientas (extendido) ----------------

    private TextView chip(String t, View.OnClickListener l) {
        TextView b = new TextView(this);
        b.setText(t);
        b.setTextColor(Color.WHITE);
        b.setTextSize(13);
        b.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setColor(CARD);
        g.setCornerRadius(dp(12));
        b.setBackground(g);
        b.setOnClickListener(l);
        return b;
    }

    private TextView small(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(11);
        v.setTextColor(0x99FFFFFF);
        v.setPadding(dp(2), dp(8), 0, dp(2));
        return v;
    }

    private View toolbar() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(8), dp(8), dp(8), dp(8));
        TextView t = title();
        t.setPadding(dp(2), 0, 0, dp(6));
        t.setTextColor(ORANGE);
        c.addView(t);
        String[] icons = {"✎ Lápiz", "✒ Tinta", "▮ Rotulador", "☁ Aerógrafo", "⌫ Borrador"};
        for (int i = 0; i < icons.length; i++) {
            final int k = i;
            TextView b = chip(icons[i], v -> setBrush(k));
            b.setGravity(Gravity.CENTER_VERTICAL);
            b.setPadding(dp(10), 0, 0, 0);
            brushBtns.add(b);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(36));
            lp.bottomMargin = dp(4);
            c.addView(b, lp);
        }
        setBrush(brush);
        c.addView(small("Tamaño"));
        c.addView(slider(1, 120, (int) size, v -> { size = v; prefs.edit().putFloat("dwSize", size).apply(); }));
        c.addView(small("Opacidad"));
        c.addView(slider(5, 100, (int) (opacity * 100), v -> { opacity = v / 100f; prefs.edit().putFloat("dwOpacity", opacity).apply(); }));
        c.addView(small("Color"));
        LinearLayout colRow = new LinearLayout(this);
        colRow.setGravity(Gravity.CENTER_VERTICAL);
        swatch = new View(this);
        swatch.setOnClickListener(v -> ColorWheel.pick(this, color, this::setColor));
        colRow.addView(swatch, new LinearLayout.LayoutParams(dp(40), dp(40)));
        recentRow = new LinearLayout(this);
        recentRow.setPadding(dp(6), 0, 0, 0);
        colRow.addView(recentRow);
        c.addView(colRow);
        paintSwatch();
        LinearLayout ur = new LinearLayout(this);
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, dp(40), 1);
        half.setMargins(0, dp(10), dp(3), 0);
        ur.addView(chip("↶", v -> op("undo")), half);
        LinearLayout.LayoutParams half2 = new LinearLayout.LayoutParams(0, dp(40), 1);
        half2.setMargins(dp(3), dp(10), 0, 0);
        ur.addView(chip("↷", v -> op("redo")), half2);
        c.addView(ur);
        layersBtn = chip("Capas", v -> layersSheet());
        c.addView(layersBtn, block());
        c.addView(chip("⤢ Ver todo", v -> { if (editor != null) editor.fit(); }), block());
        c.addView(chip("Guardar PNG", v -> exportPng()), block());
        c.addView(chip("Borrar capa", v -> new Ui.Sheet(this).title("¿Borrar la " + doc.layers.get(doc.cur).name + "?")
                .message("Se puede deshacer con ↶.").button("Cancelar", false, null)
                .button("Borrar", true, () -> op("clear", "i", doc.cur)).show()), block());
        TextView close = chip("Cerrar", v -> closeBoth());
        ((GradientDrawable) close.getBackground()).setColor(0xFF7A2430);
        c.addView(close, block());
        ScrollView sv = new ScrollView(this);
        sv.addView(c);
        sv.setBackgroundColor(0xFF1A2026);
        return sv;
    }

    private LinearLayout.LayoutParams block() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(38));
        lp.topMargin = dp(6);
        return lp;
    }

    private interface IntSet { void set(int v); }

    private SeekBar slider(int min, int max, int val, IntSet s) {
        SeekBar b = new SeekBar(this);
        b.setMax(max - min);
        b.setProgress(Math.max(0, Math.min(max - min, val - min)));
        b.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean u) { if (u) s.set(p + min); }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) { }
        });
        return b;
    }

    private void setBrush(int k) {
        brush = k;
        prefs.edit().putInt("dwBrush", k).apply();
        for (int i = 0; i < brushBtns.size(); i++)
            ((GradientDrawable) brushBtns.get(i).getBackground()).setColor(i == k ? ORANGE : CARD);
    }

    private void setColor(int c) {
        color = c | 0xFF000000;
        prefs.edit().putInt("dwColor", color).apply();
        // colores recientes (6)
        String rec = prefs.getString("dwRecent", "");
        List<String> l = new ArrayList<>();
        l.add(Integer.toHexString(color));
        for (String s : rec.split(",")) if (!s.isEmpty() && !s.equals(Integer.toHexString(color)) && l.size() < 6) l.add(s);
        prefs.edit().putString("dwRecent", android.text.TextUtils.join(",", l)).apply();
        if (brush == DrawDoc.ERASER) setBrush(DrawDoc.INK);
        paintSwatch();
    }

    private void paintSwatch() {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        g.setStroke(dp(2), Color.WHITE);
        swatch.setBackground(g);
        recentRow.removeAllViews();
        for (String s : prefs.getString("dwRecent", "").split(",")) {
            if (s.isEmpty()) continue;
            int c;
            try { c = (int) Long.parseLong(s, 16); } catch (Exception e) { continue; }
            if (c == color || recentRow.getChildCount() >= 3) continue;
            View v = new View(this);
            GradientDrawable gg = new GradientDrawable();
            gg.setShape(GradientDrawable.OVAL);
            gg.setColor(c);
            v.setBackground(gg);
            final int cc = c;
            v.setOnClickListener(x -> setColor(cc));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(26), dp(26));
            lp.setMarginStart(dp(4));
            recentRow.addView(v, lp);
        }
    }

    // ---------------- capas ----------------

    private Ui.Sheet layerSheet;

    private void layersSheet() {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        fillLayers(list);
        ScrollView sv = new ScrollView(this);
        sv.addView(list);
        layerSheet = new Ui.Sheet(this).title("Capas").view(sv);
        layerSheet.button("Cerrar", false, null).show();
    }

    private void fillLayers(LinearLayout list) {
        list.removeAllViews();
        // la de arriba primero (como en los programas de dibujo)
        for (int i = doc.layers.size() - 1; i >= 0; i--) {
            final int k = i;
            DrawDoc.Layer l = doc.layers.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(8), dp(4), dp(4), dp(4));
            GradientDrawable g = new GradientDrawable();
            g.setColor(i == doc.cur ? 0x33F57C1F : 0x14000000);
            g.setCornerRadius(dp(12));
            row.setBackground(g);
            TextView eye = rowBtn(l.visible ? "👁" : "◌", v -> { op("layer", "a", "vis", "i", k, "v", !doc.layers.get(k).visible); fillLayers(list); });
            row.addView(eye, new LinearLayout.LayoutParams(dp(40), dp(40)));
            TextView name = new TextView(this);
            name.setText(l.name + (i == doc.cur ? "  · en uso" : ""));
            name.setTextSize(15);
            name.setTextColor(i == doc.cur ? ORANGE : 0xFF8A8A8A);
            name.setTypeface(i == doc.cur ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            name.setPadding(dp(8), 0, 0, 0);
            name.setOnClickListener(v -> { op("layer", "a", "sel", "i", k); fillLayers(list); });
            row.addView(name, new LinearLayout.LayoutParams(0, dp(40), 1));
            row.addView(rowBtn("▲", v -> { op("layer", "a", "up", "i", k); fillLayers(list); }), new LinearLayout.LayoutParams(dp(36), dp(40)));
            row.addView(rowBtn("▼", v -> { op("layer", "a", "down", "i", k); fillLayers(list); }), new LinearLayout.LayoutParams(dp(36), dp(40)));
            row.addView(rowBtn("🗑", v -> {
                if (doc.layers.size() <= 1) { Toast.makeText(this, "Tiene que quedar al menos una capa", Toast.LENGTH_SHORT).show(); return; }
                new Ui.Sheet(this).title("¿Eliminar la " + doc.layers.get(k).name + "?").message("No se puede deshacer.")
                        .button("Cancelar", false, null).button("Eliminar", true, () -> { op("layer", "a", "del", "i", k); fillLayers(list); }).show();
            }), new LinearLayout.LayoutParams(dp(36), dp(40)));
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
            rl.bottomMargin = dp(6);
            list.addView(row, rl);
        }
        if (doc.layers.size() < DrawDoc.MAX_LAYERS) {
            TextView add = chip("+ Capa nueva", v -> { op("layer", "a", "add"); fillLayers(list); });
            ((GradientDrawable) add.getBackground()).setColor(ORANGE);
            list.addView(add, new LinearLayout.LayoutParams(-1, dp(42)));
        }
    }

    private TextView rowBtn(String t, View.OnClickListener l) {
        TextView b = new TextView(this);
        b.setText(t);
        b.setTextSize(16);
        b.setGravity(Gravity.CENTER);
        b.setTextColor(0xFF8A8A8A);
        b.setOnClickListener(l);
        return b;
    }

    // ---------------- exportar ----------------

    private void exportPng() {
        Toast.makeText(this, "Guardando…", Toast.LENGTH_SHORT).show();
        final Bitmap flat = doc.flatten();
        new Thread(() -> {
            String name = "DUAL-Draw " + new java.text.SimpleDateFormat("yyyy-MM-dd HH.mm.ss", java.util.Locale.ROOT).format(new java.util.Date()) + ".png";
            boolean ok = false;
            try {
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                    cv.put(MediaStore.MediaColumns.MIME_TYPE, "image/png");
                    cv.put("relative_path", "Pictures/DUAL-Draw");
                    Uri u = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
                    if (u != null) try (OutputStream out = getContentResolver().openOutputStream(u)) { ok = out != null && flat.compress(Bitmap.CompressFormat.PNG, 100, out); }
                } else {
                    File dir = new File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES), "DUAL-Draw");
                    //noinspection ResultOfMethodCallIgnored
                    dir.mkdirs();
                    try (FileOutputStream out = new FileOutputStream(new File(dir, name))) { ok = flat.compress(Bitmap.CompressFormat.PNG, 100, out); }
                }
            } catch (Exception ignored) { }
            final boolean fok = ok;
            h.post(() -> Toast.makeText(this, fok ? "Guardado en Imágenes › DUAL-Draw" : "No se pudo guardar la imagen", Toast.LENGTH_LONG).show());
        }, "dualdraw-png").start();
    }

    // ======================= el lienzo =======================

    /** Lienzo: en el visor muestra todo; en el editor tiene zoom y se dibuja con un dedo. */
    private final class Board extends View {
        final boolean edit;
        final float d = getResources().getDisplayMetrics().density;
        // vista: px de pantalla = (x lienzo - ox) * zoom
        float zoom = -1, ox, oy;
        RectF frame;   // visor: zona que se ve en el editor
        private final Paint framePaint = new Paint(Paint.ANTI_ALIAS_FLAG), shade = new Paint();
        // trazo
        private boolean drawing, two;
        private final List<Float> pend = new ArrayList<>();
        private boolean pendStart;
        private float lastX, lastY, ps = 0.6f, pMin = 2, pMax = -1;
        private long lastT;
        // dos dedos
        private float z0, ox0, oy0, mx0, my0, dist0;

        Board(Activity a, boolean edit) {
            super(a);
            this.edit = edit;
            framePaint.setStyle(Paint.Style.STROKE);
            framePaint.setStrokeWidth(2.5f * d);
            framePaint.setColor(ORANGE);
            shade.setColor(0x55000000);
        }

        float fitZoom() { return Math.min(getWidth() / (float) DrawDoc.W, getHeight() / (float) DrawDoc.H); }

        void fit() {
            zoom = fitZoom();
            ox = -(getWidth() / zoom - DrawDoc.W) / 2f;
            oy = -(getHeight() / zoom - DrawDoc.H) / 2f;
            sendView();
            invalidate();
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) { if (w > 0 && h > 0) fit(); }

        private void clamp() {
            float fz = fitZoom();
            zoom = Math.max(fz, Math.min(fz * 10, zoom));
            float vw = getWidth() / zoom, vh = getHeight() / zoom;
            ox = vw >= DrawDoc.W ? -(vw - DrawDoc.W) / 2f : Math.max(0, Math.min(DrawDoc.W - vw, ox));
            oy = vh >= DrawDoc.H ? -(vh - DrawDoc.H) / 2f : Math.max(0, Math.min(DrawDoc.H - vh, oy));
        }

        private long lastViewSent;

        private void sendView() {
            if (!edit) return;
            RectF r = new RectF(Math.max(0, ox), Math.max(0, oy), Math.min(DrawDoc.W, ox + getWidth() / zoom), Math.min(DrawDoc.H, oy + getHeight() / zoom));
            if (viewer != null) { viewer.frame = r; viewer.invalidate(); }
            if (linked() && hub.connected() && SystemClock.uptimeMillis() - lastViewSent > 60) {
                lastViewSent = SystemClock.uptimeMillis();
                JSONArray a = new JSONArray();
                try { a.put(r.left).put(r.top).put(r.right).put(r.bottom); } catch (Exception ignored) { }
                hub.send("dwView", "r", a);
            }
        }

        @Override protected void onDraw(Canvas c) {
            if (zoom <= 0) fit();
            RectF dst = new RectF(-ox * zoom, -oy * zoom, (DrawDoc.W - ox) * zoom, (DrawDoc.H - oy) * zoom);
            doc.draw(c, dst);
            if (!edit && frame != null && (frame.width() < DrawDoc.W - 2 || frame.height() < DrawDoc.H - 2)) {
                RectF f = new RectF(dst.left + frame.left * zoom, dst.top + frame.top * zoom, dst.left + frame.right * zoom, dst.top + frame.bottom * zoom);
                c.drawRect(f, framePaint);
            }
        }

        private float toX(float sx) { return sx / zoom + ox; }
        private float toY(float sy) { return sy / zoom + oy; }

        /** Presión: la del dedo si el teléfono la entrega de verdad; si no, sale de la velocidad (lento = más grueso). */
        private float pressure(MotionEvent e, int i, float x, float y, long t) {
            float raw = e.getPressure(i);
            pMin = Math.min(pMin, raw); pMax = Math.max(pMax, raw);
            float p;
            if (pMax - pMin > 0.12f) p = Math.max(0f, Math.min(1f, (raw - pMin) / Math.max(0.05f, pMax - pMin)));
            else {
                float dt = Math.max(1, t - lastT), v = (float) Math.hypot(x - lastX, y - lastY) / dt / d;   // dp por ms
                p = Math.max(0.2f, Math.min(1f, 1.05f - v * 0.45f));
            }
            ps = ps * 0.75f + p * 0.25f;
            return ps;
        }

        private void addPt(float sx, float sy, float p) {
            float x = toX(sx), y = toY(sy);
            doc.point(x, y, p);
            pend.add(x); pend.add(y); pend.add(p);
        }

        private final Runnable flush = this::flush;

        private void flush() {
            removeCallbacks(flush);
            if (pend.isEmpty()) return;
            if (linked() && hub.connected()) {
                JSONObject m = new JSONObject();
                try {
                    m.put("t", "dw"); m.put("op", "seg"); m.put("l", doc.cur);
                    if (pendStart) {
                        JSONObject b = new JSONObject();
                        b.put("t", brush); b.put("s", (double) size); b.put("c", color); b.put("o", (double) opacity);
                        m.put("b", b); m.put("st", true);
                    }
                    JSONArray a = new JSONArray();
                    for (Float f : pend) a.put(Math.round(f * 100) / 100.0);
                    m.put("p", a);
                } catch (Exception ignored) { }
                hub.send(m);
            }
            pend.clear();
            pendStart = false;
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (!edit) return true;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    two = false;
                    drawing = true;
                    doc.begin(doc.cur, brush, size, color, opacity);
                    pend.clear();
                    pendStart = true;
                    lastX = e.getX(); lastY = e.getY(); lastT = e.getEventTime(); ps = 0.6f;
                    addPt(e.getX(), e.getY(), pressure(e, 0, e.getX(), e.getY(), e.getEventTime()));
                    postDelayed(flush, 40);
                    invalidate();
                    if (viewer != null) viewer.invalidate();
                    return true;
                }
                case MotionEvent.ACTION_POINTER_DOWN:
                    if (e.getPointerCount() == 2) {
                        // dos dedos: se cancela el trazo recién empezado y se mueve/acerca el lienzo
                        if (drawing) {
                            drawing = false;
                            pend.clear();
                            doc.stroking = false;
                            doc.buf.eraseColor(Color.TRANSPARENT);
                            if (linked() && hub.connected() && !pendStart) hub.send("dw", "op", "cancel");
                        }
                        two = true;
                        z0 = zoom; ox0 = ox; oy0 = oy;
                        mx0 = (e.getX(0) + e.getX(1)) / 2; my0 = (e.getY(0) + e.getY(1)) / 2;
                        dist0 = Math.max(1, (float) Math.hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1)));
                        invalidate();
                        if (viewer != null) viewer.invalidate();
                    }
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (two && e.getPointerCount() >= 2) {
                        float mx = (e.getX(0) + e.getX(1)) / 2, my = (e.getY(0) + e.getY(1)) / 2;
                        float dd = Math.max(1, (float) Math.hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1)));
                        float cx = mx0 / z0 + ox0, cy = my0 / z0 + oy0;   // punto del lienzo bajo los dedos al empezar
                        zoom = z0 * dd / dist0;
                        ox = cx - mx / zoom; oy = cy - my / zoom;
                        clamp();
                        sendView();
                        invalidate();
                        return true;
                    }
                    if (!drawing) return true;
                    for (int i = 0; i < e.getHistorySize(); i++) {
                        float hx = e.getHistoricalX(i), hy = e.getHistoricalY(i);
                        long ht = e.getHistoricalEventTime(i);
                        float pr = pressure(e, 0, hx, hy, ht);
                        lastX = hx; lastY = hy; lastT = ht;
                        addPt(hx, hy, pr);
                    }
                    float pr = pressure(e, 0, e.getX(), e.getY(), e.getEventTime());
                    lastX = e.getX(); lastY = e.getY(); lastT = e.getEventTime();
                    addPt(e.getX(), e.getY(), pr);
                    if (pend.size() > 600) flush(); else if (!pend.isEmpty()) postDelayed(flush, 40);
                    invalidate();
                    if (viewer != null) viewer.invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                    if (two) { two = false; return true; }
                    if (!drawing) return true;
                    drawing = false;
                    flush();
                    doc.end();
                    if (linked() && hub.connected()) hub.send("dw", "op", "end");
                    if (++strokes % 8 == 0) save();
                    refreshAll();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    drawing = false;
                    two = false;
                    pend.clear();
                    doc.stroking = false;
                    doc.buf.eraseColor(Color.TRANSPARENT);
                    refreshAll();
                    return true;
            }
            return true;
        }
    }

    private int strokes;
}
