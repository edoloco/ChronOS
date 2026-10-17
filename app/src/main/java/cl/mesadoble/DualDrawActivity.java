package cl.mesadoble;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
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
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * DUAL-Draw: programa de dibujo de pantalla doble, con herramientas en paneles de cristal intenso (sin deslizar).
 *  - "view" (PRINCIPAL): la obra completa en vivo + panel de COLOR (izquierda) y de CAPAS (derecha) sobre la obra.
 *  - "edit" (EXTENDIDO): el lienzo con zoom + pinceles y herramientas (izquierda) y tamaño, opacidad y deshacer (derecha).
 *  - "solo" (un teléfono): arriba la obra con sus paneles, abajo el lienzo.
 * El extendido manda: tiene el estado de las herramientas y aplica las órdenes; el principal le pide cambios.
 *   dw {op…} · dwFull · dwHello · dwView {r} · dwOpen · dwClose
 *   dwSet {k, v} (principal → extendido) · dwState {…} (extendido → principal) · dwReq {op…} (principal → extendido)
 */
public class DualDrawActivity extends Activity implements Link.Listener {

    static volatile boolean open;
    static DualDrawActivity live;
    static DrawDoc doc;   // uno por proceso

    static final int ORANGE = 0xFFF57C1F, BG = 0xFF101418;
    static final int T_DRAW = 0, T_LINE = 1, T_RECT = 2, T_OVAL = 3, T_FILL = 4, T_PICK = 5;

    private final Handler h = new Handler(Looper.getMainLooper());
    private Hub hub;
    private SharedPreferences prefs;
    private String mode;
    private boolean closing;
    private float dp;

    private Board editor, viewer;
    // estado de las herramientas (lo manda el extendido)
    private int brush, color, tool;
    private float size, opacity;
    private boolean sym, steady;

    // controles que se pintan según el estado
    private final List<Tool> brushBtns = new ArrayList<>(), toolBtns = new ArrayList<>();
    private Tool symBtn, steadyBtn, flipBtn;
    private Fader sizeF, opacF;
    private View swatch;
    private ColorWheel wheel;
    private LinearLayout recentRow, layerList;
    private Slider layerOpac;
    private TextView blendBtn;
    private Glass2 leftPanel, rightPanel;
    private boolean panelsOn = true;

    private int dp(float v) { return Math.round(v * dp); }
    private boolean editing() { return !"view".equals(mode); }
    private boolean linked() { return !"solo".equals(mode); }
    private boolean online() { return linked() && hub.connected(); }

    static Intent intent(android.content.Context c, String mode) {
        return new Intent(c, DualDrawActivity.class).putExtra("mode", mode).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        live = this;
        hub = Hub.get(this);
        dp = getResources().getDisplayMetrics().density;
        prefs = getSharedPreferences("mesa", MODE_PRIVATE);
        mode = getIntent().getStringExtra("mode");
        if (mode == null) mode = "solo";
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        brush = prefs.getInt("dwBrush", DrawDoc.INK);
        color = prefs.getInt("dwColor", 0xFF1B1B1B);
        size = prefs.getFloat("dwSize", 10);
        opacity = prefs.getFloat("dwOpacity", 1f);
        tool = T_DRAW;
        sym = prefs.getBoolean("dwSym", false);
        steady = prefs.getBoolean("dwSteady", false);
        if (doc == null) { doc = new DrawDoc(); if (editing()) load(); }

        View content;
        if ("view".equals(mode)) content = buildViewer();
        else if ("edit".equals(mode)) content = buildEditor();
        else {
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.addView(buildViewer(), new LinearLayout.LayoutParams(-1, 0, 1));
            col.addView(buildEditor(), new LinearLayout.LayoutParams(-1, 0, 1));
            content = col;
            panelsOn = false;
            applyPanels();
        }
        content.setBackgroundColor(BG);
        setContentView(content);
        refreshTools();
        immersive();
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
        if (online()) { if (editing()) { sendFull(); sendState(); } else hub.send("dwHello"); }
        changed();
    }

    @Override protected void onPause() {
        open = false;
        hub.remove(this);
        if (editing()) save();
        if (isFinishing() && !closing && online()) hub.send("dwClose");
        super.onPause();
    }

    @Override protected void onDestroy() { if (live == this) live = null; super.onDestroy(); }

    private void closeBoth() {
        if (online()) hub.send("dwClose");
        closing = true;
        finish();
    }

    // ======================= armado de pantallas =======================

    private TextView label(String t, float sp) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(sp);
        v.setTextColor(0xF2FFFFFF);
        v.setShadowLayer(3 * dp, 0, dp, 0xB3000000);
        v.setTypeface(Intro.font(this));
        return v;
    }

    /** Principal: la obra a pantalla completa y, encima, los paneles de cristal. */
    private View buildViewer() {
        FrameLayout f = new FrameLayout(this);
        viewer = new Board(false);
        f.addView(viewer, new FrameLayout.LayoutParams(-1, -1));

        // barra de arriba: título y archivo
        Glass2 top = new Glass2(viewer);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(14), dp(4), dp(6), dp(4));
        TextView t = label("DUAL-Draw", 17);
        t.setTextColor(ORANGE);
        top.addView(t);
        top.addView(pill("◫ Paneles", v -> { panelsOn = !panelsOn; applyPanels(); }));
        top.addView(pill("Nuevo", v -> new Ui.Sheet(this).title("¿Empezar un dibujo nuevo?").message("Se borra el dibujo actual (guarda antes un PNG si lo quieres).")
                .button("Cancelar", false, null).button("Nuevo", true, () -> request(opj("new"))).show()));
        top.addView(pill("Guardar PNG", v -> exportPng()));
        top.addView(pill("✕", v -> closeBoth()));
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(-2, dp(46), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        tl.topMargin = dp(8);
        f.addView(top, tl);

        // izquierda: color
        leftPanel = new Glass2(viewer);
        leftPanel.setPadding(dp(12), dp(10), dp(12), dp(12));
        leftPanel.addView(label("Color", 14));
        wheel = new ColorWheel(this, color);
        wheel.setFixedHeight(dp(118));
        wheel.onPick = c -> set("color", c);
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(-1, -2);
        wl.topMargin = dp(6);
        leftPanel.addView(wheel, wl);
        leftPanel.addView(palette());
        leftPanel.addView(label("Recientes", 11));
        recentRow = new LinearLayout(this);
        leftPanel.addView(recentRow, new LinearLayout.LayoutParams(-1, dp(26)));
        FrameLayout.LayoutParams ll = new FrameLayout.LayoutParams(dp(214), -2, Gravity.START | Gravity.CENTER_VERTICAL);
        ll.leftMargin = dp(10);
        f.addView(leftPanel, ll);

        // derecha: capas
        rightPanel = new Glass2(viewer);
        rightPanel.setPadding(dp(10), dp(10), dp(10), dp(10));
        rightPanel.addView(label("Capas", 14));
        layerList = new LinearLayout(this);
        layerList.setOrientation(LinearLayout.VERTICAL);
        rightPanel.addView(layerList);
        LinearLayout op = new LinearLayout(this);
        op.setGravity(Gravity.CENTER_VERTICAL);
        op.addView(label("Opac.", 11));
        layerOpac = new Slider(v -> request(opj("layer", "a", "opac", "i", doc.cur, "v", (double) v)));
        op.addView(layerOpac, new LinearLayout.LayoutParams(0, dp(28), 1));
        rightPanel.addView(op);
        blendBtn = pill("Mezcla: Normal", v -> request(opj("layer", "a", "blend", "i", doc.cur, "v", (doc.layers.get(doc.cur).blend + 1) % DrawDoc.BLEND_NAMES.length)));
        rightPanel.addView(blendBtn, new LinearLayout.LayoutParams(-1, dp(30)));
        String[][] acts = {{"＋", "Nueva"}, {"⧉", "Duplicar"}, {"⤓", "Combinar"}, {"↔", "Voltear"}, {"▲", "Subir"}, {"▼", "Bajar"}, {"⬚", "Importar"}, {"⌫", "Vaciar"}};
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        for (int r = 0; r < 2; r++) {
            LinearLayout row = new LinearLayout(this);
            for (int c = 0; c < 4; c++) {
                final String[] a = acts[r * 4 + c];
                Tool b = new Tool(a[0], a[1], false);
                b.setOnClickListener(v -> layerAction(a[1]));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(42), 1);
                lp.setMargins(dp(2), dp(2), dp(2), dp(2));
                row.addView(b, lp);
            }
            grid.addView(row);
        }
        rightPanel.addView(grid);
        FrameLayout.LayoutParams rl = new FrameLayout.LayoutParams(dp(226), -2, Gravity.END | Gravity.CENTER_VERTICAL);
        rl.rightMargin = dp(10);
        f.addView(rightPanel, rl);
        return f;
    }

    private void applyPanels() {
        if (leftPanel != null) leftPanel.animate().alpha(panelsOn ? 1 : 0).translationX(panelsOn ? 0 : -dp(240)).setDuration(260).start();
        if (rightPanel != null) rightPanel.animate().alpha(panelsOn ? 1 : 0).translationX(panelsOn ? 0 : dp(240)).setDuration(260).start();
    }

    private View palette() {
        int[] cols = {0xFF000000, 0xFF424242, 0xFF9E9E9E, 0xFFFFFFFF, 0xFF6D4C41, 0xFFFFCCBC,
                0xFFE53935, 0xFFFB8C00, 0xFFFDD835, 0xFF7CB342, 0xFF43A047, 0xFF00897B,
                0xFF00ACC1, 0xFF1E88E5, 0xFF3949AB, 0xFF8E24AA, 0xFFD81B60, 0xFFF48FB1};
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(8), 0, dp(4));
        for (int r = 0; r < 3; r++) {
            LinearLayout row = new LinearLayout(this);
            for (int c = 0; c < 6; c++) {
                final int col = cols[r * 6 + c];
                View v = dot(col);
                v.setOnClickListener(x -> set("color", col));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(24), 1);
                lp.setMargins(dp(2), dp(2), dp(2), dp(2));
                row.addView(v, lp);
            }
            box.addView(row);
        }
        return box;
    }

    private View dot(int col) {
        View v = new View(this);
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        g.setColor(col);
        g.setStroke(dp(1), 0x66FFFFFF);
        v.setBackground(g);
        return v;
    }

    private TextView pill(String t, View.OnClickListener l) {
        TextView b = new TextView(this);
        b.setText(t);
        b.setTextSize(13);
        b.setTextColor(Color.WHITE);
        b.setShadowLayer(2 * dp, 0, dp / 2, 0x99000000);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(12), 0, dp(12), 0);
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(0x26FFFFFF);
        g.setStroke(dp(1), 0x40FFFFFF);
        g.setCornerRadius(dp(16));
        b.setBackground(g);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(34));
        lp.setMargins(dp(4), dp(3), dp(4), dp(3));
        b.setLayoutParams(lp);
        return b;
    }

    /** Extendido: pinceles y herramientas | lienzo | tamaño, opacidad, color y deshacer. Todo a la vista. */
    private View buildEditor() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(6), dp(6), dp(6), dp(6));
        boolean compact = "solo".equals(mode);
        int cell = compact ? 36 : 44;

        Glass2 left = new Glass2(null);
        left.setPadding(dp(8), dp(6), dp(8), dp(8));
        left.addView(label("Pinceles", 11));
        String[][] br = {{"✎", "Lápiz"}, {"✒", "Tinta"}, {"▮", "Rotulador"}, {"☁", "Aerógrafo"}, {"🖌", "Pincel"}, {"▒", "Tiza"},
                {"∕", "Caligrafía"}, {"⌫", "Borrador"}, {"◌", "Borr. suave"}};
        int[] brIds = {DrawDoc.PENCIL, DrawDoc.INK, DrawDoc.MARKER, DrawDoc.AIR, DrawDoc.BRUSH, DrawDoc.CHALK, DrawDoc.CALLI, DrawDoc.ERASER, DrawDoc.SOFT_ERASER};
        left.addView(grid3(br, cell, (i, b) -> { b.tag = brIds[i]; brushBtns.add(b); b.setOnClickListener(v -> { set("brush", brIds[i]); set("tool", T_DRAW); }); }));
        left.addView(label("Herramientas", 11));
        String[][] tl = {{"╱", "Línea"}, {"▭", "Rectáng."}, {"◯", "Óvalo"}, {"◧", "Balde"}, {"⊙", "Gotero"}, {"⇹", "Simetría"},
                {"〰", "Estable"}, {"⤢", "Ver todo"}, {"⇋", "Voltear"}};
        left.addView(grid3(tl, cell, (i, b) -> {
            switch (i) {
                case 0: case 1: case 2: case 3: case 4: {
                    final int t = new int[]{T_LINE, T_RECT, T_OVAL, T_FILL, T_PICK}[i];
                    b.tag = t;
                    toolBtns.add(b);
                    b.setOnClickListener(v -> set("tool", tool == t ? T_DRAW : t));
                    break;
                }
                case 5: symBtn = b; b.setOnClickListener(v -> set("sym", !sym)); break;
                case 6: steadyBtn = b; b.setOnClickListener(v -> set("steady", !steady)); break;
                case 7: b.setOnClickListener(v -> editor.fit()); break;
                case 8: flipBtn = b; b.setOnClickListener(v -> { editor.flip = !editor.flip; if (viewer != null) viewer.flip = editor.flip; flipBtn.on = editor.flip; flipBtn.invalidate(); editor.invalidate(); if (viewer != null) viewer.invalidate(); }); break;
            }
        }));
        row.addView(left, new LinearLayout.LayoutParams(-2, -1));

        editor = new Board(true);
        LinearLayout.LayoutParams el = new LinearLayout.LayoutParams(0, -1, 1);
        el.setMargins(dp(6), 0, dp(6), 0);
        row.addView(editor, el);

        Glass2 right = new Glass2(null);
        right.setPadding(dp(8), dp(6), dp(8), dp(8));
        right.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout faders = new LinearLayout(this);
        sizeF = new Fader("Tamaño", 1, 150, v -> set("size", v));
        opacF = new Fader("Opac.", 5, 100, v -> set("opacity", v / 100f));
        LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(dp(compact ? 34 : 44), -1);
        fl.setMargins(dp(2), 0, dp(2), 0);
        faders.addView(sizeF, fl);
        faders.addView(opacF, new LinearLayout.LayoutParams(fl));
        right.addView(faders, new LinearLayout.LayoutParams(-2, 0, 1));
        swatch = new View(this);
        swatch.setOnClickListener(v -> ColorWheel.pick(this, color, c -> set("color", c)));
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(dp(compact ? 32 : 40), dp(compact ? 32 : 40));
        sl.topMargin = dp(8);
        right.addView(swatch, sl);
        Tool un = new Tool("↶", "Deshacer", false), re = new Tool("↷", "Rehacer", false);
        un.setOnClickListener(v -> request(opj("undo")));
        re.setOnClickListener(v -> request(opj("redo")));
        LinearLayout.LayoutParams ul = new LinearLayout.LayoutParams(dp(compact ? 72 : 92), dp(cell));
        ul.topMargin = dp(6);
        right.addView(un, ul);
        right.addView(re, new LinearLayout.LayoutParams(ul));
        if ("edit".equals(mode)) {
            Tool x = new Tool("✕", "Cerrar", false);
            x.setOnClickListener(v -> closeBoth());
            right.addView(x, new LinearLayout.LayoutParams(ul));
        }
        row.addView(right, new LinearLayout.LayoutParams(-2, -1));
        return row;
    }

    private interface Each { void on(int i, Tool b); }

    private View grid3(String[][] items, int cell, Each each) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(3), 0, dp(6));
        for (int r = 0; r * 3 < items.length; r++) {
            LinearLayout row = new LinearLayout(this);
            for (int c = 0; c < 3 && r * 3 + c < items.length; c++) {
                int i = r * 3 + c;
                Tool b = new Tool(items[i][0], items[i][1], true);
                each.on(i, b);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(cell), dp(cell));
                lp.setMargins(dp(2), dp(2), dp(2), dp(2));
                row.addView(b, lp);
            }
            box.addView(row);
        }
        return box;
    }

    // ======================= estado de las herramientas =======================

    /** Cambiar una herramienta: en el extendido se aplica; en el principal se le pide al extendido. */
    private void set(String k, Object v) {
        if (editing()) applySet(k, v);
        else if (online()) hub.send("dwSet", "k", k, "v", v);
        else Toast.makeText(this, "El extendido no está conectado", Toast.LENGTH_SHORT).show();
    }

    private void applySet(String k, Object v) {
        switch (k) {
            case "brush": brush = ((Number) v).intValue(); prefs.edit().putInt("dwBrush", brush).apply(); break;
            case "tool": tool = ((Number) v).intValue(); break;
            case "size": size = ((Number) v).floatValue(); prefs.edit().putFloat("dwSize", size).apply(); break;
            case "opacity": opacity = ((Number) v).floatValue(); prefs.edit().putFloat("dwOpacity", opacity).apply(); break;
            case "sym": sym = (Boolean) v; prefs.edit().putBoolean("dwSym", sym).apply(); break;
            case "steady": steady = (Boolean) v; prefs.edit().putBoolean("dwSteady", steady).apply(); break;
            case "color": {
                color = ((Number) v).intValue() | 0xFF000000;
                prefs.edit().putInt("dwColor", color).apply();
                String rec = prefs.getString("dwRecent", "");
                List<String> l = new ArrayList<>();
                l.add(Integer.toHexString(color));
                for (String s : rec.split(",")) if (!s.isEmpty() && !s.equals(Integer.toHexString(color)) && l.size() < 8) l.add(s);
                prefs.edit().putString("dwRecent", android.text.TextUtils.join(",", l)).apply();
                if (DrawDoc.eraser(brush)) { brush = DrawDoc.INK; prefs.edit().putInt("dwBrush", brush).apply(); }
                if (tool == T_PICK) tool = T_DRAW;
                break;
            }
        }
        refreshTools();
        sendState();
    }

    private void sendState() {
        if (!online() || !editing()) return;
        hub.send("dwState", "brush", brush, "tool", tool, "size", (double) size, "opacity", (double) opacity, "color", color,
                "sym", sym, "steady", steady, "recent", prefs.getString("dwRecent", ""));
    }

    private String recent = "";

    private void refreshTools() {
        for (Tool b : brushBtns) { b.on = tool == T_DRAW && b.tag == brush; b.invalidate(); }
        for (Tool b : toolBtns) { b.on = b.tag == tool; b.invalidate(); }
        if (symBtn != null) { symBtn.on = sym; symBtn.invalidate(); }
        if (steadyBtn != null) { steadyBtn.on = steady; steadyBtn.invalidate(); }
        if (sizeF != null) sizeF.set(size);
        if (opacF != null) opacF.set(opacity * 100);
        if (swatch != null) {
            android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
            g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            g.setColor(color);
            g.setStroke(dp(2), Color.WHITE);
            swatch.setBackground(g);
        }
        if (wheel != null) wheel.setColor(color);
        if (recentRow != null) {
            recentRow.removeAllViews();
            String rec = editing() ? prefs.getString("dwRecent", "") : recent;
            for (String s : rec.split(",")) {
                if (s.isEmpty() || recentRow.getChildCount() >= 7) continue;
                int c;
                try { c = (int) Long.parseLong(s, 16); } catch (Exception e) { continue; }
                View v = dot(c);
                v.setOnClickListener(x -> set("color", c));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(22), dp(22));
                lp.setMargins(dp(2), dp(2), dp(2), dp(2));
                recentRow.addView(v, lp);
            }
        }
        if (editor != null) editor.invalidate();
        if (viewer != null) viewer.invalidate();
    }

    // ======================= órdenes =======================

    private JSONObject opj(String op, Object... kv) {
        JSONObject m = new JSONObject();
        try {
            m.put("op", op);
            for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        } catch (Exception ignored) { }
        return m;
    }

    /** Una orden sobre el dibujo: el extendido la aplica y la reparte; el principal se la pide. */
    private void request(JSONObject m) {
        if (editing()) op(m);
        else if (online()) { try { m.put("t", "dwReq"); } catch (Exception ignored) { } hub.send(m); }
        else Toast.makeText(this, "El extendido no está conectado", Toast.LENGTH_SHORT).show();
    }

    private void op(JSONObject m) {
        doc.apply(m);
        if (online()) {
            try { m.put("t", "dw"); } catch (Exception ignored) { }
            hub.send(m);
        }
        changed();
    }

    private void layerAction(String a) {
        int i = doc.cur;
        switch (a) {
            case "Nueva": request(opj("layer", "a", "add")); break;
            case "Duplicar": request(opj("layer", "a", "dup", "i", i)); break;
            case "Combinar":
                if (i == 0) { Toast.makeText(this, "No hay capa debajo para combinar", Toast.LENGTH_SHORT).show(); return; }
                request(opj("layer", "a", "merge", "i", i));
                break;
            case "Voltear": request(opj("layer", "a", "flip", "i", i)); break;
            case "Subir": request(opj("layer", "a", "up", "i", i)); break;
            case "Bajar": request(opj("layer", "a", "down", "i", i)); break;
            case "Importar": pickImage(); break;
            case "Vaciar": new Ui.Sheet(this).title("¿Vaciar la " + doc.layers.get(i).name + "?").message("Se puede deshacer con ↶.")
                    .button("Cancelar", false, null).button("Vaciar", true, () -> request(opj("clear", "i", i))).show(); break;
        }
    }

    private final Runnable refreshLater = this::refreshLayers;
    private final Runnable blurLater = () -> { if (viewer != null) viewer.rebuildBlur(); };

    /** Cambió el dibujo: se repinta, y el panel de capas y el cristal se ponen al día (sin apuro). */
    private void changed() {
        if (editor != null) editor.invalidate();
        if (viewer != null) viewer.invalidate();
        if (doc.stroking) return;   // mientras se dibuja solo se repinta; capas y cristal, al terminar el trazo
        h.removeCallbacks(refreshLater);
        h.postDelayed(refreshLater, 120);
        h.removeCallbacks(blurLater);
        h.postDelayed(blurLater, 300);
    }

    private void refreshLayers() {
        if (layerList == null) return;
        layerList.removeAllViews();
        for (int i = doc.layers.size() - 1; i >= 0; i--) {
            final int k = i;
            DrawDoc.Layer l = doc.layers.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(4), dp(2), dp(4), dp(2));
            android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
            g.setColor(i == doc.cur ? 0x66F57C1F : 0x1AFFFFFF);
            g.setCornerRadius(dp(10));
            row.setBackground(g);
            TextView eye = label(l.visible ? "👁" : "◌", 14);
            eye.setGravity(Gravity.CENTER);
            eye.setOnClickListener(v -> request(opj("layer", "a", "vis", "i", k, "v", !doc.layers.get(k).visible)));
            row.addView(eye, new LinearLayout.LayoutParams(dp(30), dp(34)));
            row.addView(new Thumb(l), new LinearLayout.LayoutParams(dp(54), dp(30)));
            TextView name = label(l.name + (l.blend != 0 ? " · " + DrawDoc.BLEND_NAMES[l.blend] : "") + (l.alpha < 0.99f ? " · " + Math.round(l.alpha * 100) + "%" : ""), 12);
            name.setPadding(dp(8), 0, 0, 0);
            name.setSingleLine(true);
            row.addView(name, new LinearLayout.LayoutParams(0, dp(34), 1));
            name.setGravity(Gravity.CENTER_VERTICAL);
            row.setOnClickListener(v -> request(opj("layer", "a", "sel", "i", k)));
            row.setOnLongClickListener(v -> {
                if (doc.layers.size() <= 1) return true;
                new Ui.Sheet(this).title("¿Eliminar la " + doc.layers.get(k).name + "?").message("No se puede deshacer.")
                        .button("Cancelar", false, null).button("Eliminar", true, () -> request(opj("layer", "a", "del", "i", k))).show();
                return true;
            });
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
            rl.topMargin = dp(4);
            layerList.addView(row, rl);
        }
        DrawDoc.Layer c = doc.layers.get(doc.cur);
        if (layerOpac != null) layerOpac.set(c.alpha);
        if (blendBtn != null) blendBtn.setText("Mezcla: " + DrawDoc.BLEND_NAMES[c.blend]);
    }

    /** Miniatura de una capa (sobre cuadritos, para ver lo transparente). */
    private final class Thumb extends View {
        final DrawDoc.Layer l;
        final Paint chk = new Paint(), bm = new Paint(Paint.FILTER_BITMAP_FLAG);
        Thumb(DrawDoc.Layer l) { super(DualDrawActivity.this); this.l = l; }
        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), hh = getHeight(), s = 5 * dp;
            for (int y = 0; y * s < hh; y++) for (int x = 0; x * s < w; x++) {
                chk.setColor((x + y) % 2 == 0 ? 0xFFEDEDED : 0xFFCFCFCF);
                c.drawRect(x * s, y * s, Math.min(w, x * s + s), Math.min(hh, y * s + s), chk);
            }
            if (!l.bmp.isRecycled()) c.drawBitmap(l.bmp, null, new RectF(0, 0, w, hh), bm);
        }
    }

    // ======================= mensajes =======================

    @Override public void onMessage(JSONObject m) {
        String t = m.optString("t");
        switch (t) {
            case "dw": h.post(() -> { doc.apply(m); changed(); }); break;
            case "dwFull": h.post(() -> { doc.fromJson(m); changed(); }); break;
            case "dwHello": if (editing()) h.post(() -> { sendFull(); sendState(); }); break;
            case "dwReq": if (editing()) h.post(() -> op(m)); break;
            case "dwSet": if (editing()) h.post(() -> applySet(m.optString("k"), m.opt("v"))); break;
            case "dwState":
                if (!editing()) h.post(() -> {
                    brush = m.optInt("brush", brush); tool = m.optInt("tool", tool); color = m.optInt("color", color);
                    size = (float) m.optDouble("size", size); opacity = (float) m.optDouble("opacity", opacity);
                    sym = m.optBoolean("sym", sym); steady = m.optBoolean("steady", steady); recent = m.optString("recent", recent);
                    refreshTools();
                });
                break;
            case "dwView": {
                JSONArray r = m.optJSONArray("r");
                if (r != null && viewer != null) h.post(() -> { viewer.frame = new RectF((float) r.optDouble(0), (float) r.optDouble(1), (float) r.optDouble(2), (float) r.optDouble(3)); viewer.invalidate(); });
                break;
            }
            case "dwClose": h.post(() -> { closing = true; finish(); }); break;
        }
    }

    @Override public void onState(int state, String info) {
        if (state == Link.CONNECTED && open && linked()) h.post(() -> { if (editing()) { sendFull(); sendState(); } else hub.send("dwHello"); });
    }

    // ======================= guardar, exportar, importar =======================

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
            String data = DrawDoc.toJson(snap).toString();
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

    private void pickImage() {
        if (doc.layers.size() >= DrawDoc.MAX_LAYERS) { Toast.makeText(this, "Máximo " + DrawDoc.MAX_LAYERS + " capas", Toast.LENGTH_SHORT).show(); return; }
        try { startActivityForResult(new Intent(Intent.ACTION_GET_CONTENT).setType("image/*"), 51); }
        catch (Exception e) { Toast.makeText(this, "No hay galería para elegir", Toast.LENGTH_SHORT).show(); }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != 51 || res != RESULT_OK || data == null || data.getData() == null) return;
        final Uri u = data.getData();
        new Thread(() -> {
            try (InputStream in = getContentResolver().openInputStream(u)) {
                Bitmap b = BitmapFactory.decodeStream(in);
                if (b == null) return;
                float k = Math.min(1f, Math.min(DrawDoc.W / (float) b.getWidth(), DrawDoc.H / (float) b.getHeight()));
                Bitmap s = k < 1 ? Bitmap.createScaledBitmap(b, Math.max(1, (int) (b.getWidth() * k)), Math.max(1, (int) (b.getHeight() * k)), true) : b;
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                s.compress(Bitmap.CompressFormat.PNG, 100, bos);
                String b64 = android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP);
                h.post(() -> request(opj("import", "png", b64)));
            } catch (Exception e) { h.post(() -> Toast.makeText(this, "No se pudo abrir la imagen", Toast.LENGTH_SHORT).show()); }
        }, "dualdraw-importar").start();
    }

    // ======================= piezas de la interfaz =======================

    /** Panel de cristal intenso. Si 'over' es el visor, detrás se ve la obra desenfocada. */
    private final class Glass2 extends LinearLayout {
        final Board over;
        Glass2(Board over) {
            super(DualDrawActivity.this);
            this.over = over;
            setOrientation(VERTICAL);
            setWillNotDraw(false);
            setClickable(true);
        }
        @Override protected void onDraw(Canvas c) {
            RectF r = new RectF(0, 0, getWidth(), getHeight());
            float rad = 20 * dp;
            Glass.body(c, r, rad, dp, over == null || over.blur == null ? null : () -> {
                RectF dst = new RectF(over.dst);
                dst.offset(-getLeft() - getTranslationX(), -getTop() - getTranslationY());
                c.drawColor(BG);
                c.drawBitmap(over.blur, null, dst, over.blurPaint);
            });
        }
    }

    /** Botón de herramienta: símbolo grande y nombre chico; encendido = naranja. */
    private final class Tool extends View {
        final String glyph, name;
        final boolean small;
        boolean on;
        int tag = -1;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        Tool(String glyph, String name, boolean small) {
            super(DualDrawActivity.this);
            this.glyph = glyph; this.name = name; this.small = small;
            p.setTextAlign(Paint.Align.CENTER);
            p.setColor(Color.WHITE);
            p.setShadowLayer(2 * dp, 0, dp / 2, 0x99000000);
            setOnLongClickListener(v -> { Toast.makeText(getContext(), name, Toast.LENGTH_SHORT).show(); return true; });
        }
        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), hh = getHeight();
            bg.setColor(on ? ORANGE : isPressed() ? 0x40FFFFFF : 0x1FFFFFFF);
            c.drawRoundRect(new RectF(0, 0, w, hh), 12 * dp, 12 * dp, bg);
            boolean wide = w > hh * 1.4f;
            if (wide) {   // botón ancho: símbolo y nombre en una línea
                p.setTextSize(15 * dp);
                c.drawText(glyph + "  " + name, w / 2, hh / 2 + 5 * dp, p);
                return;
            }
            p.setTextSize(Math.min(hh, w) * 0.42f);
            c.drawText(glyph, w / 2, hh * 0.56f, p);
            p.setTextSize(Math.max(7 * dp, Math.min(hh, w) * 0.19f));
            c.drawText(name, w / 2, hh - 4 * dp, p);
        }
        @Override protected void drawableStateChanged() { super.drawableStateChanged(); invalidate(); }
    }

    private interface FSet { void set(float v); }

    /** Perilla vertical (como una mesa de audio). */
    private final class Fader extends View {
        final String name;
        final float min, max;
        final FSet cb;
        float val;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), t = new Paint(Paint.ANTI_ALIAS_FLAG);
        Fader(String name, float min, float max, FSet cb) {
            super(DualDrawActivity.this);
            this.name = name; this.min = min; this.max = max; this.cb = cb;
            t.setTextAlign(Paint.Align.CENTER);
            t.setColor(Color.WHITE);
            t.setShadowLayer(2 * dp, 0, dp / 2, 0x99000000);
        }
        void set(float v) { val = Math.max(min, Math.min(max, v)); invalidate(); }
        private RectF track() { return new RectF(getWidth() / 2f - 5 * dp, 30 * dp, getWidth() / 2f + 5 * dp, getHeight() - 22 * dp); }
        @Override protected void onDraw(Canvas c) {
            RectF tr = track();
            t.setTextSize(10 * dp);
            c.drawText(name, getWidth() / 2f, 12 * dp, t);
            t.setTextSize(12 * dp);
            c.drawText(String.valueOf(Math.round(val)), getWidth() / 2f, 26 * dp, t);
            p.setColor(0x33FFFFFF);
            c.drawRoundRect(tr, 5 * dp, 5 * dp, p);
            float f = (val - min) / (max - min), y = tr.bottom - f * tr.height();
            p.setColor(ORANGE);
            c.drawRoundRect(new RectF(tr.left, y, tr.right, tr.bottom), 5 * dp, 5 * dp, p);
            p.setColor(Color.WHITE);
            c.drawRoundRect(new RectF(getWidth() / 2f - 14 * dp, y - 5 * dp, getWidth() / 2f + 14 * dp, y + 5 * dp), 4 * dp, 4 * dp, p);
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            RectF tr = track();
            float f = Math.max(0, Math.min(1, (tr.bottom - e.getY()) / tr.height()));
            val = min + f * (max - min);
            invalidate();
            getParent().requestDisallowInterceptTouchEvent(true);
            if (e.getActionMasked() == MotionEvent.ACTION_UP) cb.set(val);
            return true;
        }
    }

    /** Deslizador horizontal (opacidad de la capa). */
    private final class Slider extends View {
        final FSet cb;
        float val = 1;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Slider(FSet cb) { super(DualDrawActivity.this); this.cb = cb; }
        void set(float v) { val = Math.max(0, Math.min(1, v)); invalidate(); }
        @Override protected void onDraw(Canvas c) {
            float y = getHeight() / 2f, x0 = 10 * dp, x1 = getWidth() - 10 * dp, x = x0 + val * (x1 - x0);
            p.setColor(0x33FFFFFF);
            c.drawRoundRect(new RectF(x0, y - 3 * dp, x1, y + 3 * dp), 3 * dp, 3 * dp, p);
            p.setColor(ORANGE);
            c.drawRoundRect(new RectF(x0, y - 3 * dp, x, y + 3 * dp), 3 * dp, 3 * dp, p);
            p.setColor(Color.WHITE);
            c.drawCircle(x, y, 8 * dp, p);
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            float x0 = 10 * dp, x1 = getWidth() - 10 * dp;
            val = Math.max(0, Math.min(1, (e.getX() - x0) / (x1 - x0)));
            invalidate();
            getParent().requestDisallowInterceptTouchEvent(true);
            if (e.getActionMasked() == MotionEvent.ACTION_UP) cb.set(val);
            return true;
        }
    }

    // ======================= el lienzo =======================

    private int strokes;

    /** Lienzo: en el visor muestra todo; en el editor tiene zoom y se dibuja (o se usa la herramienta elegida). */
    private final class Board extends View {
        final boolean edit;
        float zoom = -1, ox, oy;
        boolean flip;
        RectF frame;                      // visor: zona que se ve en el editor
        final RectF dst = new RectF();    // dónde quedó dibujada la obra
        Bitmap blur;                      // visor: la obra chica y desenfocada (fondo de los paneles)
        final Paint blurPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Paint framePaint = new Paint(Paint.ANTI_ALIAS_FLAG), axis = new Paint(Paint.ANTI_ALIAS_FLAG), prev = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean drawing, two, shaping;
        private final List<Float> pend = new ArrayList<>();
        private boolean pendStart;
        private float lastX, lastY, ps = 0.6f, pMin = 2, pMax = -1, sx0, sy0, sx1, sy1, smX, smY;
        private long lastT;
        private float z0, ox0, oy0, mx0, my0, dist0;

        Board(boolean edit) {
            super(DualDrawActivity.this);
            this.edit = edit;
            framePaint.setStyle(Paint.Style.STROKE);
            framePaint.setStrokeWidth(2.5f * dp);
            framePaint.setColor(ORANGE);
            axis.setColor(0x8800B8FF);
            axis.setStrokeWidth(1.5f * dp);
            axis.setPathEffect(new android.graphics.DashPathEffect(new float[]{8 * dp, 6 * dp}, 0));
            prev.setStyle(Paint.Style.STROKE);
            prev.setStrokeCap(Paint.Cap.ROUND);
        }

        float fitZoom() { return Math.min(getWidth() / (float) DrawDoc.W, getHeight() / (float) DrawDoc.H); }

        void fit() {
            zoom = fitZoom();
            ox = -(getWidth() / zoom - DrawDoc.W) / 2f;
            oy = -(getHeight() / zoom - DrawDoc.H) / 2f;
            sendView();
            invalidate();
        }

        @Override protected void onSizeChanged(int w, int hh, int ow, int oh) { if (w > 0 && hh > 0) { fit(); if (!edit) rebuildBlur(); } }

        void rebuildBlur() {
            if (edit) return;
            Bitmap s = Bitmap.createBitmap(DrawDoc.W / 10, DrawDoc.H / 10, Bitmap.Config.ARGB_8888);
            doc.draw(new Canvas(s), new RectF(0, 0, s.getWidth(), s.getHeight()));
            Glass.boxBlur(s, 3);
            blur = s;
            if (leftPanel != null) leftPanel.invalidate();
            if (rightPanel != null) rightPanel.invalidate();
        }

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
            if (online() && SystemClock.uptimeMillis() - lastViewSent > 60) {
                lastViewSent = SystemClock.uptimeMillis();
                JSONArray a = new JSONArray();
                try { a.put(r.left).put(r.top).put(r.right).put(r.bottom); } catch (Exception ignored) { }
                hub.send("dwView", "r", a);
            }
        }

        @Override protected void onDraw(Canvas c) {
            if (zoom <= 0) fit();
            c.save();
            if (flip) c.scale(-1, 1, getWidth() / 2f, 0);
            dst.set(-ox * zoom, -oy * zoom, (DrawDoc.W - ox) * zoom, (DrawDoc.H - oy) * zoom);
            doc.draw(c, dst);
            if (sym) c.drawLine(dst.centerX(), dst.top, dst.centerX(), dst.bottom, axis);
            if (!edit && frame != null && (frame.width() < DrawDoc.W - 2 || frame.height() < DrawDoc.H - 2))
                c.drawRect(dst.left + frame.left * zoom, dst.top + frame.top * zoom, dst.left + frame.right * zoom, dst.top + frame.bottom * zoom, framePaint);
            if (edit && shaping) {   // vista previa de la forma
                prev.setColor(DrawDoc.eraser(brush) ? 0x88FF4444 : color);
                prev.setStrokeWidth(Math.max(1.5f * dp, size * zoom * 0.6f));
                float ax = (sx0 - ox) * zoom, ay = (sy0 - oy) * zoom, bx = (sx1 - ox) * zoom, by = (sy1 - oy) * zoom;
                if (tool == T_LINE) c.drawLine(ax, ay, bx, by, prev);
                else if (tool == T_RECT) c.drawRect(Math.min(ax, bx), Math.min(ay, by), Math.max(ax, bx), Math.max(ay, by), prev);
                else c.drawOval(new RectF(Math.min(ax, bx), Math.min(ay, by), Math.max(ax, bx), Math.max(ay, by)), prev);
            }
            c.restore();
        }

        private float sxv(float x) { return flip ? getWidth() - x : x; }
        private float toX(float sx) { return sxv(sx) / zoom + ox; }
        private float toY(float sy) { return sy / zoom + oy; }

        /** Presión: la del dedo si el teléfono la entrega de verdad; si no, sale de la velocidad (lento = más grueso). */
        private float pressure(MotionEvent e, float x, float y, long t) {
            float raw = e.getPressure(0);
            pMin = Math.min(pMin, raw); pMax = Math.max(pMax, raw);
            float p;
            if (pMax - pMin > 0.12f) p = Math.max(0f, Math.min(1f, (raw - pMin) / Math.max(0.05f, pMax - pMin)));
            else {
                float dt = Math.max(1, t - lastT), v = (float) Math.hypot(x - lastX, y - lastY) / dt / dp;
                p = Math.max(0.2f, Math.min(1f, 1.05f - v * 0.45f));
            }
            ps = ps * 0.75f + p * 0.25f;
            return ps;
        }

        private void addPt(float sx, float sy, float p) {
            float x = toX(sx), y = toY(sy);
            if (steady) {   // trazo estable: el punto sigue al dedo con retraso (líneas más suaves)
                smX += (x - smX) * 0.28f; smY += (y - smY) * 0.28f;
                x = smX; y = smY;
            }
            doc.point(x, y, p);
            pend.add(x); pend.add(y); pend.add(p);
        }

        private final Runnable flush = this::flush;

        private JSONObject brushJson() {
            JSONObject b = new JSONObject();
            try { b.put("t", brush); b.put("s", (double) size); b.put("c", color); b.put("o", (double) opacity); b.put("m", sym); } catch (Exception ignored) { }
            return b;
        }

        private void flush() {
            removeCallbacks(flush);
            if (pend.isEmpty()) return;
            if (online()) {
                JSONObject m = new JSONObject();
                try {
                    m.put("t", "dw"); m.put("op", "seg"); m.put("l", doc.cur);
                    if (pendStart) { m.put("b", brushJson()); m.put("st", true); }
                    JSONArray a = new JSONArray();
                    for (Float f : pend) a.put(Math.round(f * 100) / 100.0);
                    m.put("p", a);
                } catch (Exception ignored) { }
                hub.send(m);
            }
            pend.clear();
            pendStart = false;
        }

        private void cancelStroke() {
            if (!drawing) return;
            drawing = false;
            pend.clear();
            doc.stroking = false;
            doc.buf.eraseColor(Color.TRANSPARENT);
            if (online() && !pendStart) hub.send("dw", "op", "cancel");
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (!edit) return true;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    two = false;
                    float x = toX(e.getX()), y = toY(e.getY());
                    if (tool == T_DRAW) {
                        drawing = true;
                        doc.begin(doc.cur, brush, size, color, opacity, sym);
                        pend.clear();
                        pendStart = true;
                        lastX = e.getX(); lastY = e.getY(); lastT = e.getEventTime(); ps = 0.6f;
                        smX = x; smY = y;
                        addPt(e.getX(), e.getY(), pressure(e, e.getX(), e.getY(), e.getEventTime()));
                        postDelayed(flush, 40);
                    } else if (tool == T_LINE || tool == T_RECT || tool == T_OVAL) {
                        shaping = true; sx0 = sx1 = x; sy0 = sy1 = y;
                    }
                    changed();
                    return true;
                }
                case MotionEvent.ACTION_POINTER_DOWN:
                    if (e.getPointerCount() == 2) {
                        cancelStroke();
                        shaping = false;
                        two = true;
                        z0 = zoom; ox0 = ox; oy0 = oy;
                        mx0 = (sxv(e.getX(0)) + sxv(e.getX(1))) / 2; my0 = (e.getY(0) + e.getY(1)) / 2;
                        dist0 = Math.max(1, (float) Math.hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1)));
                        changed();
                    }
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (two && e.getPointerCount() >= 2) {
                        float mx = (sxv(e.getX(0)) + sxv(e.getX(1))) / 2, my = (e.getY(0) + e.getY(1)) / 2;
                        float dd = Math.max(1, (float) Math.hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1)));
                        float cx = mx0 / z0 + ox0, cy = my0 / z0 + oy0;
                        zoom = z0 * dd / dist0;
                        ox = cx - mx / zoom; oy = cy - my / zoom;
                        clamp();
                        sendView();
                        invalidate();
                        return true;
                    }
                    if (shaping) { sx1 = toX(e.getX()); sy1 = toY(e.getY()); invalidate(); return true; }
                    if (!drawing) return true;
                    for (int i = 0; i < e.getHistorySize(); i++) {
                        float hx = e.getHistoricalX(i), hy = e.getHistoricalY(i);
                        long ht = e.getHistoricalEventTime(i);
                        float pr = pressure(e, hx, hy, ht);
                        lastX = hx; lastY = hy; lastT = ht;
                        addPt(hx, hy, pr);
                    }
                    float pr = pressure(e, e.getX(), e.getY(), e.getEventTime());
                    lastX = e.getX(); lastY = e.getY(); lastT = e.getEventTime();
                    addPt(e.getX(), e.getY(), pr);
                    if (pend.size() > 600) flush(); else if (!pend.isEmpty()) postDelayed(flush, 40);
                    invalidate();
                    if (viewer != null) viewer.invalidate();
                    return true;
                case MotionEvent.ACTION_UP: {
                    if (two) { two = false; return true; }
                    float x = toX(e.getX()), y = toY(e.getY());
                    if (shaping) {
                        shaping = false;
                        if (Math.hypot(sx1 - sx0, sy1 - sy0) > 2) {
                            JSONArray r = new JSONArray();
                            try { r.put((double) sx0).put((double) sy0).put((double) sx1).put((double) sy1); } catch (Exception ignored) { }
                            op(opj("shape", "k", tool == T_LINE ? "line" : tool == T_RECT ? "rect" : "oval", "r", r, "l", doc.cur, "b", brushJson()));
                        }
                        invalidate();
                        return true;
                    }
                    if (tool == T_FILL) {
                        if (DrawDoc.eraser(brush)) set("brush", DrawDoc.INK);
                        op(opj("fill", "x", (int) x, "y", (int) y, "c", color, "l", doc.cur, "tol", 40));
                        return true;
                    }
                    if (tool == T_PICK) { set("color", doc.colorAt((int) x, (int) y)); return true; }
                    if (!drawing) return true;
                    drawing = false;
                    flush();
                    doc.end();
                    if (online()) hub.send("dw", "op", "end");
                    if (++strokes % 8 == 0) save();
                    changed();
                    return true;
                }
                case MotionEvent.ACTION_CANCEL:
                    cancelStroke();
                    shaping = false;
                    two = false;
                    changed();
                    return true;
            }
            return true;
        }
    }
}
