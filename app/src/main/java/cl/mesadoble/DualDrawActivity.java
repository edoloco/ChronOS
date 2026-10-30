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

    static final int ORANGE = 0xFFF57C1F, BG = 0xFF5F6369;   // fondo gris de trabajo
    static final int GREEN = 0xFF2FA866, INK_DARK = 0xFF24342B;   // paneles blancos con verde
    static final int WHITE_PANEL = 0xEBFFFFFF, ORANGE_PANEL = 0xF0F57C1F;
    static final int T_DRAW = 0, T_LINE = 1, T_RECT = 2, T_OVAL = 3, T_FILL = 4, T_PICK = 5, T_WAND = 6, T_LASSO = 7, T_XFORM = 8, T_PEN = 9, T_TEXT = 10;

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
    private Tool symBtn, steadyBtn, flipBtn, shapeBtn, penBtn;
    private Glass2 toolPanel;
    private android.widget.ScrollView toolScroll;
    private int toolCell, toolCols;
    private int shapeVar = T_LINE;     // qué forma hace el botón de formas (línea, rectángulo u óvalo)
    boolean penOff;                    // pluma: "vector distanciado" (el punto aparece unos 30 mm sobre el dedo)
    private Fader sizeF, opacF;
    private View swatch;
    private ColorWheel wheel;
    private LinearLayout recentRow, layerList;
    private Slider layerOpac;
    private TextView blendBtn;
    private Glass2 leftPanel, rightPanel;
    private boolean panelsOn = true;
    private ValueBar valueBar;          // "Valor de trazo": grosor en vivo con la otra mano
    private float valueLevel;
    private LinearLayout penBar, fillBar;   // pluma (formas vectoriales) y balde (tolerancia, huecos, qué mira)
    private TextView penHint, penStrokeL, penFillL, fillTolL, fillGapL, fillAllL;
    private int fillTol, fillGap;
    private boolean fillAll;
    private LinearLayout selBar;        // selección: borrar, invertir, quitar, tolerancia
    private LinearLayout xfBar;         // transformar: aplicar, cancelar, restablecer
    private android.graphics.Matrix xf; // transformación en curso de la capa (en el extendido)
    private int xfL;                    // la capa que se está transformando (fija desde que se empezó)
    private TextView tolLabel, sizeLabel;
    private int selTol;

    private int dp(float v) { return Math.round(v * dp); }
    private boolean editing() { return !"view".equals(mode); }
    private boolean linked() { return !"solo".equals(mode); }
    private boolean online() { return linked() && hub.connected(); }

    /** DUAL-Draw como "app" en la lista de apps y en la mesa (paquete propio de ChronOS, como los PALS). */
    static final String PKG = "dd:draw";

    static AppsDialog.App asApp(android.content.Context c) {
        return new AppsDialog.App(PKG, "DUAL-Draw", new android.graphics.drawable.BitmapDrawable(c.getResources(), icon(192)));
    }

    /** Ícono: las dos pantallas (una sobre la otra) y la punta de un lápiz asomando. Diseño propio. */
    static Bitmap icon(int s) {
        Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float u = s / 100f;
        p.setShader(new android.graphics.LinearGradient(0, 0, 0, s, 0xFF39414D, 0xFF1D232C, android.graphics.Shader.TileMode.CLAMP));
        c.drawRoundRect(new RectF(0, 0, s, s), 22 * u, 22 * u, p);
        p.setShader(null);
        // las dos pantallas (con su borde de teléfono) y el espacio entre ellas
        RectF top = new RectF(16 * u, 13 * u, 76 * u, 46 * u), bot = new RectF(16 * u, 51 * u, 76 * u, 84 * u);
        p.setColor(0xFF0E1116);
        c.drawRoundRect(new RectF(top.left - 3 * u, top.top - 3 * u, top.right + 3 * u, top.bottom + 3 * u), 7 * u, 7 * u, p);
        c.drawRoundRect(new RectF(bot.left - 3 * u, bot.top - 3 * u, bot.right + 3 * u, bot.bottom + 3 * u), 7 * u, 7 * u, p);
        p.setShader(new android.graphics.LinearGradient(0, top.top, 0, top.bottom, 0xFF4FD08F, GREEN, android.graphics.Shader.TileMode.CLAMP));
        c.drawRoundRect(top, 5 * u, 5 * u, p);
        p.setShader(new android.graphics.LinearGradient(0, bot.top, 0, bot.bottom, 0xFFFFA14D, ORANGE, android.graphics.Shader.TileMode.CLAMP));
        c.drawRoundRect(bot, 5 * u, 5 * u, p);
        p.setShader(null);
        // un trazo en la pantalla de arriba (la obra) y otro en la de abajo (donde se dibuja)
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(3.2f * u);
        p.setColor(0xE6FFFFFF);
        android.graphics.Path w = new android.graphics.Path();
        w.moveTo(24 * u, 38 * u); w.cubicTo(34 * u, 18 * u, 46 * u, 44 * u, 66 * u, 22 * u);
        c.drawPath(w, p);
        android.graphics.Path w2 = new android.graphics.Path();
        w2.moveTo(24 * u, 76 * u); w2.cubicTo(32 * u, 60 * u, 42 * u, 78 * u, 54 * u, 66 * u);
        c.drawPath(w2, p);
        p.setStyle(Paint.Style.FILL);
        // el lápiz asomando por la esquina de abajo, con la punta sobre la pantalla
        c.save();
        c.rotate(45, 58 * u, 66 * u);
        p.setColor(0xFFFFC93C);   // cuerpo
        c.drawRect(58 * u, 61 * u, 98 * u, 71 * u, p);
        p.setColor(0xFFE0A82A);
        c.drawRect(58 * u, 66 * u, 98 * u, 71 * u, p);
        p.setColor(0xFFF2D3A2);   // madera
        android.graphics.Path tip = new android.graphics.Path();
        tip.moveTo(58 * u, 61 * u); tip.lineTo(45 * u, 66 * u); tip.lineTo(58 * u, 71 * u); tip.close();
        c.drawPath(tip, p);
        p.setColor(0xFF2B2B2B);   // mina
        android.graphics.Path lead = new android.graphics.Path();
        lead.moveTo(49.5f * u, 64.3f * u); lead.lineTo(45 * u, 66 * u); lead.lineTo(49.5f * u, 67.7f * u); lead.close();
        c.drawPath(lead, p);
        c.restore();
        return b;
    }

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
        valueLevel = prefs.getFloat("dwValue", 0.5f);
        selTol = prefs.getInt("dwTol", 32);
        fillTol = prefs.getInt("dwFillTol", 40);
        fillGap = prefs.getInt("dwFillGap", 0);
        fillAll = prefs.getBoolean("dwFillAll", false);
        shapeVar = prefs.getInt("dwShapeVar", T_LINE);
        penOff = prefs.getBoolean("dwPenOff", false);
        boolean fresh = false;
        if (doc == null) { doc = new DrawDoc(); if (editing()) fresh = !load(); }

        View content;
        if ("view".equals(mode)) content = buildViewer();
        else if ("edit".equals(mode)) content = buildEditor();
        else {
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setClipChildren(false);
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
        // sin un dibujo en curso: primero se elige el tamaño
        if (fresh) h.postDelayed(this::askSize, 400);
    }

    // ======================= tamaño del dibujo =======================

    /** Pregunta el tamaño y empieza un dibujo nuevo (en los dos teléfonos). */
    private void askSize() {
        if (isFinishing()) return;
        int[][] sz = {{1600, 900}, {1920, 1080}, {2560, 1440}, {1500, 1500}, {1080, 1920}, {1240, 1754}};
        String[] nm = {"Pantalla horizontal", "HD horizontal", "Grande horizontal", "Cuadrado", "Vertical (historia)", "Hoja carta / A4"};
        Ui.Sheet sh = new Ui.Sheet(this).title("Tamaño del dibujo");
        for (int i = 0; i < sz.length; i++) {
            final int w = sz[i][0], hh = sz[i][1];
            int layersFit = Math.max(3, Math.min(10, (int) (100L * 1024 * 1024 / ((long) w * hh * 4))));
            sh.option(Ui.IMAGE, nm[i], w + " × " + hh + " px · hasta " + layersFit + " capas", () -> request(opj("new", "w", w, "h", hh)));
        }
        sh.option(Ui.GEAR, "Personalizado", "Ancho y alto a elección (hasta " + DrawDoc.MAX_SIDE + " px por lado)", this::askCustomSize);
        sh.button("Cancelar", false, null).show();
    }

    /** Tamaño personalizado con botones (sin teclado): ancho y alto con − / +, y "Cuadrado" para igualarlos. */
    private void askCustomSize() {
        final int[] v = {prefs.getInt("dwCustW", 1080), prefs.getInt("dwCustH", 1080)};
        final boolean[] square = {v[0] == v[1]};
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        final TextView[] lab = new TextView[2];
        final TextView sq = new TextView(this);
        Runnable show = () -> {
            lab[0].setText("Ancho  " + v[0] + " px");
            lab[1].setText("Alto  " + v[1] + " px");
            sq.setText(square[0] ? "■ Cuadrado (alto = ancho)" : "□ Cuadrado (alto = ancho)");
        };
        for (int k = 0; k < 2; k++) {
            final int kk = k;
            lab[k] = new TextView(this);
            lab[k].setTextSize(17);
            lab[k].setGravity(Gravity.CENTER);
            lab[k].setPadding(0, dp(8), 0, dp(2));
            box.addView(lab[k]);
            LinearLayout r = new LinearLayout(this);
            r.setGravity(Gravity.CENTER);
            for (int d : new int[]{-500, -100, -10, 10, 100, 500}) {
                TextView b = stepBtn((d > 0 ? "+" : "−") + Math.abs(d));
                b.setOnClickListener(x -> {
                    v[kk] = Math.max(200, Math.min(DrawDoc.MAX_SIDE, v[kk] + d));
                    if (square[0]) v[1 - kk] = v[kk];
                    show.run();
                });
                r.addView(b);
            }
            box.addView(r);
        }
        sq.setTextSize(15);
        sq.setGravity(Gravity.CENTER);
        sq.setPadding(0, dp(14), 0, dp(4));
        sq.setOnClickListener(x -> { square[0] = !square[0]; if (square[0]) v[1] = v[0]; show.run(); });
        box.addView(sq);
        show.run();
        new Ui.Sheet(this).title("Tamaño personalizado").view(box)
                .button("Cancelar", false, null)
                .button("Crear", true, () -> {
                    prefs.edit().putInt("dwCustW", v[0]).putInt("dwCustH", v[1]).apply();
                    request(opj("new", "w", v[0], "h", v[1]));
                    if ((long) v[0] * v[1] > DrawDoc.MAX_AREA)
                        Toast.makeText(this, "Era muy grande para la memoria: se achicó manteniendo la forma", Toast.LENGTH_LONG).show();
                }).show();
    }

    private TextView stepBtn(String t) {
        TextView b = new TextView(this);
        b.setText(t);
        b.setTextSize(13);
        b.setGravity(Gravity.CENTER);
        b.setTextColor(0xFF1B1B1B);
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(0xFFE8E2DA);
        g.setCornerRadius(dp(12));
        b.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(46), dp(38));
        lp.setMargins(dp(3), dp(3), dp(3), dp(3));
        b.setLayoutParams(lp);
        return b;
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
        v.setTextColor(INK_DARK);
        v.setTypeface(Intro.font(this));
        return v;
    }

    /** Principal: la obra a pantalla completa y, encima, los paneles de cristal. */
    private View buildViewer() {
        FrameLayout f = new FrameLayout(this);
        f.setClipChildren(false);
        viewer = new Board(false);
        f.addView(viewer, new FrameLayout.LayoutParams(-1, -1));

        // barra de arriba: título y archivo
        Glass2 top = new Glass2(viewer, WHITE_PANEL);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(14), dp(4), dp(6), dp(4));
        TextView t = label("DUAL-Draw", 17);
        t.setTextColor(ORANGE);
        top.addView(t);
        sizeLabel = label("", 12);
        sizeLabel.setPadding(dp(8), 0, dp(4), 0);
        top.addView(sizeLabel);
        top.addView(pill("◫ Paneles", v -> { panelsOn = !panelsOn; applyPanels(); }));
        top.addView(pill("Nuevo", v -> new Ui.Sheet(this).title("¿Empezar un dibujo nuevo?").message("Se borra el dibujo actual (guarda antes un PNG si lo quieres).")
                .button("Cancelar", false, null).button("Nuevo", true, this::askSize).show()));
        top.addView(pill("Guardar PNG", v -> exportPng()));
        top.addView(pill("Guardar PSD", v -> exportPsd()));
        top.addView(pill("✕", v -> closeBoth()));
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(-2, dp(46), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        tl.topMargin = dp(8);
        f.addView(top, tl);

        // izquierda: color
        leftPanel = new Glass2(viewer, WHITE_PANEL);
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
        // mantener presionado el panel (su borde o el título) y arrastrar: cambia ancho y alto; la rueda crece con él
        leftPanel.resizable("dwColorP", false, true, () -> {
            // nada se deforma: la rueda queda proporcionada y los colores se reacomodan en más o menos columnas
            int ww = leftPanel.getLayoutParams().width > 0 ? leftPanel.getLayoutParams().width : leftPanel.getWidth();
            int inner = ww - leftPanel.getPaddingLeft() - leftPanel.getPaddingRight();
            layoutPalette(Math.max(3, inner / dp(30)));
            int rows = (PALETTE.length + paletteCols - 1) / paletteCols;
            int hh = leftPanel.getLayoutParams().height;
            int wh = hh > 0 ? hh - dp(104) - rows * dp(30) : Math.round(inner * 0.55f);
            wheel.setFixedHeight(Math.max(dp(60), Math.min(wh, Math.round(inner * 0.75f))));
        });
        leftPanel.applySaved();

        // derecha: capas
        rightPanel = new Glass2(viewer, WHITE_PANEL);
        rightPanel.setPadding(dp(10), dp(10), dp(10), dp(10));
        rightPanel.addView(label("Capas", 14));
        layerList = new LinearLayout(this);
        layerList.setOrientation(LinearLayout.VERTICAL);
        // con muchas capas la lista se desliza: los controles de abajo (opacidad, mezcla, botones) siempre se ven
        android.widget.ScrollView layerScroll = new android.widget.ScrollView(this) {
            @Override protected void onMeasure(int ws, int hs) {
                int max = Math.max(dp(90), getResources().getDisplayMetrics().heightPixels - dp(300));
                super.onMeasure(ws, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST));
            }
        };
        layerScroll.setVerticalScrollBarEnabled(true);
        layerScroll.setFadingEdgeLength(dp(16));
        layerScroll.setVerticalFadingEdgeEnabled(true);
        layerScroll.addView(layerList);
        rightPanel.addView(layerScroll, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout op = new LinearLayout(this);
        op.setGravity(Gravity.CENTER_VERTICAL);
        op.addView(label("Opac.", 11));
        layerOpac = new Slider(v -> request(opj("layer", "a", "opac", "i", doc.cur, "v", (double) v)));
        op.addView(layerOpac, new LinearLayout.LayoutParams(0, dp(28), 1));
        rightPanel.addView(op);
        blendBtn = pill("Mezcla: Normal", v -> request(opj("layer", "a", "blend", "i", doc.cur, "v", (doc.layers.get(doc.cur).blend + 1) % DrawDoc.BLEND_NAMES.length)));
        rightPanel.addView(blendBtn, new LinearLayout.LayoutParams(-1, dp(30)));
        String[][] acts = {{"▤", "Nueva"}, {"⧉", "Duplicar"}, {"⤓", "Combinar"}, {"↔", "Voltear"}, {"▲", "Subir"}, {"▼", "Bajar"}, {"＋", "Importar"}, {"⌫", "Vaciar"}};
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
        // capas: ancho y alto a gusto (la lista se desliza dentro)
        final android.widget.ScrollView ls = layerScroll;
        rightPanel.resizable("dwLayersP", true, true, () -> {
            if (rightPanel.getLayoutParams().height > 0 && ((LinearLayout.LayoutParams) ls.getLayoutParams()).weight == 0)
                ls.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1));
        });
        rightPanel.applySaved();
        return f;
    }

    private void applyPanels() {
        if (leftPanel != null) leftPanel.animate().alpha(panelsOn ? 1 : 0).translationX(panelsOn ? 0 : -(leftPanel.getWidth() + dp(30))).setDuration(260).start();
        if (rightPanel != null) rightPanel.animate().alpha(panelsOn ? 1 : 0).translationX(panelsOn ? 0 : rightPanel.getWidth() + dp(30)).setDuration(260).start();
    }

    private static final int[] PALETTE = {0xFF000000, 0xFF424242, 0xFF9E9E9E, 0xFFFFFFFF, 0xFF6D4C41, 0xFFFFCCBC,
            0xFFE53935, 0xFFFB8C00, 0xFFFDD835, 0xFF7CB342, 0xFF43A047, 0xFF00897B,
            0xFF00ACC1, 0xFF1E88E5, 0xFF3949AB, 0xFF8E24AA, 0xFFD81B60, 0xFFF48FB1};
    private LinearLayout paletteBox;
    private int paletteCols;

    /** Colores fijos: círculos siempre redondos y del mismo tamaño; la grilla se acomoda al ancho del panel. */
    private View palette() {
        paletteBox = new LinearLayout(this);
        paletteBox.setOrientation(LinearLayout.VERTICAL);
        paletteBox.setPadding(0, dp(8), 0, dp(4));
        layoutPalette(6);
        return paletteBox;
    }

    private void layoutPalette(int cols) {
        if (paletteBox == null || cols == paletteCols) return;
        paletteCols = cols;
        paletteBox.removeAllViews();
        LinearLayout row = null;
        for (int i = 0; i < PALETTE.length; i++) {
            if (i % cols == 0) { row = new LinearLayout(this); paletteBox.addView(row); }
            final int col = PALETTE[i];
            View v = dot(col);
            v.setOnClickListener(x -> set("color", col));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(24), dp(24));
            lp.setMargins(dp(3), dp(3), dp(3), dp(3));
            row.addView(v, lp);
        }
    }

    private View dot(int col) {
        View v = new View(this);
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        g.setColor(col);
        g.setStroke(dp(1), 0x33000000);
        v.setBackground(g);
        return v;
    }

    private TextView pill(String t, View.OnClickListener l) {
        TextView b = new TextView(this);
        b.setText(t);
        b.setTextSize(13);
        b.setTextColor(INK_DARK);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(12), 0, dp(12), 0);
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(0x1F2FA866);
        g.setStroke(dp(1), 0x662FA866);
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
        row.setClipChildren(false);
        row.setClipToPadding(false);
        // un gesto que empieza en el lienzo es del lienzo aunque el otro dedo caiga sobre un panel (zoom sin trazos)
        row.setMotionEventSplittingEnabled(false);
        row.setPadding(dp(10), dp(8), dp(10), dp(18));   // espacio para las sombras
        boolean compact = "solo".equals(mode);
        int cell = compact ? 32 : 38;

        // herramientas: grilla de 2 o 3 columnas que se desliza; se eligen con "Editar" y el panel cambia de tamaño
        // manteniendo presionado su borde o su título
        toolCell = cell;
        Glass2 left = new Glass2(null, WHITE_PANEL);
        toolPanel = left;
        left.setPadding(dp(6), dp(6), dp(6), dp(6));
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView ht = label("Herram.", 11);
        head.addView(ht, new LinearLayout.LayoutParams(0, -2, 1));
        TextView edit = label("✎", 15);
        edit.setPadding(dp(8), dp(2), dp(6), dp(2));
        edit.setOnClickListener(v -> editTools());
        head.addView(edit);
        left.addView(head);
        toolScroll = new android.widget.ScrollView(this);
        toolScroll.setVerticalScrollBarEnabled(false);
        toolScroll.setFadingEdgeLength(dp(18));
        toolScroll.setVerticalFadingEdgeEnabled(true);
        left.addView(toolScroll, new LinearLayout.LayoutParams(-2, 0, 1));
        fillTools();
        left.resizable("dwToolsP", false, false, () -> { int c = toolColsFor(left.getWidth()); if (c != toolCols) { toolCols = c; fillTools(); } });
        LinearLayout.LayoutParams leftLp = new LinearLayout.LayoutParams(-2, -1);
        leftLp.gravity = Gravity.CENTER_VERTICAL;
        row.addView(left, leftLp);
        left.applySaved();

        // el lienzo, con la barra de "Valor de trazo" a un costado y la barra de selección arriba
        FrameLayout boardBox = new FrameLayout(this);
        boardBox.setClipChildren(false);
        editor = new Board(true);
        boardBox.addView(editor, new FrameLayout.LayoutParams(-1, -1));
        valueBar = new ValueBar();
        valueBar.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View v, android.graphics.Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(18)); }
        });
        valueBar.setElevation(dp(10));
        boardBox.addView(valueBar, valueBarLp());
        selBar = new LinearLayout(this);
        selBar.setGravity(Gravity.CENTER_VERTICAL);
        selBar.setPadding(dp(6), dp(2), dp(6), dp(2));
        android.graphics.drawable.GradientDrawable sg = new android.graphics.drawable.GradientDrawable();
        sg.setColor(0xF2FFFFFF);
        sg.setStroke(dp(1), 0x402FA866);
        sg.setCornerRadius(dp(18));
        selBar.setBackground(sg);
        selBar.setElevation(dp(10));
        selBar.addView(pill("Borrar selección", v -> {
            if (!doc.hasSel) { Toast.makeText(this, "Primero selecciona con la varita o el lazo", Toast.LENGTH_SHORT).show(); return; }
            request(opj("selDel", "l", doc.cur));
        }));
        selBar.addView(pill("Invertir", v -> request(opj("selInv"))));
        selBar.addView(pill("Quitar", v -> request(opj("selClear"))));
        selBar.addView(pill("−", v -> setTol(selTol - 8)));
        tolLabel = label("", 12);
        selBar.addView(tolLabel);
        selBar.addView(pill("+", v -> setTol(selTol + 8)));
        FrameLayout.LayoutParams sbl = new FrameLayout.LayoutParams(-2, dp(42), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        sbl.topMargin = dp(6);
        boardBox.addView(selBar, sbl);
        // transformar: un dedo mueve la capa, dos dedos la escalan y giran
        xfBar = new LinearLayout(this);
        xfBar.setGravity(Gravity.CENTER_VERTICAL);
        xfBar.setPadding(dp(6), dp(2), dp(6), dp(2));
        android.graphics.drawable.GradientDrawable xg = new android.graphics.drawable.GradientDrawable();
        xg.setColor(0xF2FFFFFF);
        xg.setStroke(dp(1), 0x402FA866);
        xg.setCornerRadius(dp(18));
        xfBar.setBackground(xg);
        xfBar.setElevation(dp(10));
        xfBar.addView(label("Transformar: 1 dedo mueve · 2 dedos escalan y giran", 12));
        xfBar.addView(pill("✓ Aplicar", v -> commitXform()));
        xfBar.addView(pill("↺", v -> { if (xf != null) { xf.reset(); sendXf(true); editor.invalidate(); } }));
        xfBar.addView(pill("✕", v -> cancelXform()));
        boardBox.addView(xfBar, new FrameLayout.LayoutParams(sbl));
        // pluma (como FreeHand): toca = punto recto · mantén y arrastra = curva · toca el primer punto = cerrar
        penBar = floatBar();
        penHint = label("", 11);
        penBar.addView(penHint);
        penBar.addView(pill("✓ Terminar", v -> editor.penFinish()));
        penStrokeL = pill("", v -> editor.penEdit(sh -> sh.strokeOn = !sh.strokeOn));
        penBar.addView(penStrokeL);
        penFillL = pill("", v -> editor.penEdit(sh -> { sh.fillOn = !sh.fillOn; if (sh.fillOn && !sh.closed) Toast.makeText(this, "El relleno se ve al cerrar la forma", Toast.LENGTH_SHORT).show(); }));
        penBar.addView(penFillL);
        penBar.addView(pill("🎨→Contorno", v -> editor.penEdit(sh -> { sh.stroke = color; sh.width = size; sh.strokeOn = true; })));
        penBar.addView(pill("🎨→Relleno", v -> editor.penEdit(sh -> { sh.fill = color; sh.fillOn = true; })));
        penBar.addView(pill("◠ Curvar punto", v -> editor.penSmooth()));
        penBar.addView(pill("Borrar punto", v -> editor.penDelNode()));
        penBar.addView(pill("Borrar forma", v -> editor.penDelShape()));
        boardBox.addView(scrollBar(penBar), new FrameLayout.LayoutParams(sbl));
        // balde
        fillBar = floatBar();
        fillBar.addView(pill("−", v -> setFill(fillTol - 8, fillGap, fillAll)));
        fillTolL = label("", 12);
        fillBar.addView(fillTolL);
        fillBar.addView(pill("+", v -> setFill(fillTol + 8, fillGap, fillAll)));
        fillBar.addView(pill("−", v -> setFill(fillTol, fillGap - 1, fillAll)));
        fillGapL = label("", 12);
        fillBar.addView(fillGapL);
        fillBar.addView(pill("+", v -> setFill(fillTol, fillGap + 1, fillAll)));
        fillAllL = pill("", v -> setFill(fillTol, fillGap, !fillAll));
        fillBar.addView(fillAllL);
        boardBox.addView(scrollBar(fillBar), new FrameLayout.LayoutParams(sbl));
        LinearLayout.LayoutParams el = new LinearLayout.LayoutParams(0, -1, 1);
        el.setMargins(dp(6), 0, dp(6), 0);
        row.addView(boardBox, el);

        Glass2 right = new Glass2(null, ORANGE_PANEL);
        right.setPadding(dp(5), dp(6), dp(5), dp(6));
        right.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout faders = new LinearLayout(this);
        sizeF = new Fader("●", 1, 150, v -> set("size", v));
        opacF = new Fader("◐", 5, 100, v -> set("opacity", v / 100f));
        LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(dp(compact ? 30 : 34), -1);
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
        un.orange = re.orange = true;
        LinearLayout.LayoutParams ul = new LinearLayout.LayoutParams(dp(compact ? 60 : 68), dp(cell));
        ul.topMargin = dp(6);
        right.addView(un, ul);
        right.addView(re, new LinearLayout.LayoutParams(ul));
        if ("edit".equals(mode)) {
            Tool x = new Tool("✕", "Cerrar", false);
            x.orange = true;
            x.setOnClickListener(v -> closeBoth());
            right.addView(x, new LinearLayout.LayoutParams(ul));
        }
        row.addView(right, new LinearLayout.LayoutParams(-2, -1));
        return row;
    }

    // ---------------- panel de herramientas ----------------

    /** Todas las herramientas: {id, símbolo, nombre}. "Editar" elige cuáles se ven en el panel. */
    private static final String[][] CATALOG = {
            {"b" + DrawDoc.PENCIL, "✎", "Lápiz"}, {"b" + DrawDoc.INK, "✒", "Tinta"}, {"b" + DrawDoc.VALUE, "✐", "Valor"},
            {"b" + DrawDoc.MARKER, "▮", "Rotulador"}, {"b" + DrawDoc.BRUSH, "🖌", "Pincel"}, {"b" + DrawDoc.MIX, "◐", "Mezcla"},
            {"b" + DrawDoc.SMUDGE, "☝", "Difuminar"}, {"b" + DrawDoc.AIR, "☁", "Aerógrafo"}, {"b" + DrawDoc.CHALK, "▒", "Tiza"},
            {"b" + DrawDoc.CALLI, "∕", "Caligrafía"}, {"b" + DrawDoc.ERASER, "⌫", "Borrador"}, {"b" + DrawDoc.SOFT_ERASER, "◌", "Borr. suave"},
            {"shape", "▭", "Formas (mantén para elegir)"}, {"pen", "✑", "Pluma (mantén para elegir)"}, {"text", "T", "Texto"}, {"fill", "◧", "Balde"},
            {"pick", "⊙", "Gotero"}, {"wand", "✦", "Varita"}, {"lasso", "➰", "Lazo"}, {"xform", "⤧", "Transformar"},
            {"import", "＋", "Importar imagen"}, {"sym", "⇹", "Simetría"}, {"steady", "〰", "Trazo estable"}, {"fit", "⤢", "Ver todo"},
            {"flip", "⇋", "Voltear vista"}};

    private java.util.Set<String> shownTools() {
        String saved = prefs.getString("dwTools", "");
        java.util.Set<String> s = new java.util.LinkedHashSet<>();
        if (saved.isEmpty()) { for (String[] t : CATALOG) s.add(t[0]); return s; }
        java.util.Collections.addAll(s, saved.split(","));
        return s;
    }

    private int toolColsFor(int widthPx) {
        int one = dp(toolCell + 4);
        return widthPx > 0 && widthPx - dp(12) < one * 3 - dp(6) ? 2 : 3;
    }

    private static String shapeGlyph(int t) { return t == T_RECT ? "▭" : t == T_OVAL ? "◯" : "╱"; }

    /** Arma la grilla con las herramientas elegidas (2 o 3 columnas; lo que no cabe se ve deslizando). */
    private void fillTools() {
        if (toolScroll == null) return;
        if (toolCols == 0) {
            int sw = prefs.getInt("dwToolsPW", 0);
            toolCols = sw > 0 ? toolColsFor(sw) : 3;
        }
        brushBtns.clear();
        toolBtns.clear();
        symBtn = steadyBtn = flipBtn = shapeBtn = penBtn = null;
        java.util.Set<String> on = shownTools();
        List<String[]> items = new ArrayList<>();
        for (String[] t : CATALOG) if (on.contains(t[0])) items.add(t);
        String[][] arr = items.toArray(new String[0][]);
        String[][] shown = new String[arr.length][];   // la grilla recibe {símbolo, nombre}; el id queda aparte
        for (int i = 0; i < arr.length; i++) shown[i] = new String[]{arr[i][1], arr[i][2]};
        toolScroll.removeAllViews();
        toolScroll.addView(grid(shown, toolCell, toolCols, (i, b) -> wireTool(arr[i][0], b)));
        refreshTools();
    }

    private void wireTool(String id, Tool b) {
        if (id.startsWith("b")) {
            int br = Integer.parseInt(id.substring(1));
            b.tag = br; brushBtns.add(b);
            b.setOnClickListener(v -> { set("brush", br); set("tool", T_DRAW); });
            return;
        }
        switch (id) {
            case "shape":   // formas agrupadas: tocar usa la elegida; mantener elige otra
                shapeBtn = b; b.glyph = shapeGlyph(shapeVar); b.tag = shapeVar; toolBtns.add(b);
                b.setOnClickListener(v -> set("tool", tool == shapeVar ? T_DRAW : shapeVar));
                b.setOnLongClickListener(v -> {
                    new Ui.Sheet(this).title("Formas")
                            .option(0, "╱  Línea", null, () -> pickShape(T_LINE))
                            .option(0, "▭  Rectángulo", null, () -> pickShape(T_RECT))
                            .option(0, "◯  Óvalo", null, () -> pickShape(T_OVAL)).show();
                    return true;
                });
                break;
            case "pen":
                penBtn = b; b.glyph = penOff ? "⌖" : "✑"; b.tag = T_PEN; toolBtns.add(b);
                b.setOnClickListener(v -> set("tool", tool == T_PEN ? T_DRAW : T_PEN));
                b.setOnLongClickListener(v -> {
                    new Ui.Sheet(this).title("Pluma")
                            .option(0, -1, "✑  Pluma", "Toca: punto recto · arrastra: curva · toca el 1er punto: cerrar", !penOff, () -> pickPen(false))
                            .option(0, -1, "⌖  Vector distanciado", "El punto aparece unos 3 cm sobre el dedo; al soltar queda ahí. "
                                    + "Apoya el dedo sobre un punto para moverlo", penOff, () -> pickPen(true)).show();
                    return true;
                });
                break;
            case "text": case "fill": case "pick": case "wand": case "lasso": case "xform": {
                final int t = "text".equals(id) ? T_TEXT : "fill".equals(id) ? T_FILL : "pick".equals(id) ? T_PICK : "wand".equals(id) ? T_WAND : "lasso".equals(id) ? T_LASSO : T_XFORM;
                b.tag = t; toolBtns.add(b);
                b.setOnClickListener(v -> set("tool", tool == t ? T_DRAW : t));
                break;
            }
            case "import": b.setOnClickListener(v -> pickImage()); break;
            case "sym": symBtn = b; b.setOnClickListener(v -> set("sym", !sym)); break;
            case "steady": steadyBtn = b; b.setOnClickListener(v -> set("steady", !steady)); break;
            case "fit": b.setOnClickListener(v -> editor.fit()); break;
            case "flip": flipBtn = b; b.setOnClickListener(v -> { editor.flip = !editor.flip; if (viewer != null) viewer.flip = editor.flip; flipBtn.on = editor.flip; flipBtn.invalidate(); editor.invalidate(); if (viewer != null) viewer.invalidate(); }); break;
        }
    }

    private void pickShape(int t) {
        shapeVar = t;
        prefs.edit().putInt("dwShapeVar", t).apply();
        if (shapeBtn != null) { shapeBtn.glyph = shapeGlyph(t); shapeBtn.tag = t; shapeBtn.invalidate(); }
        set("tool", t);
    }

    private void pickPen(boolean off) {
        penOff = off;
        prefs.edit().putBoolean("dwPenOff", off).apply();
        if (penBtn != null) { penBtn.glyph = off ? "⌖" : "✑"; penBtn.invalidate(); }
        set("tool", T_PEN);
    }

    private static final String[][] FONTS = {{"sans-serif", "Normal"}, {"sans-serif-light", "Fina"}, {"sans-serif-black", "Gruesa"},
            {"sans-serif-condensed", "Angosta"}, {"serif", "Con serifa"}, {"monospace", "Máquina"}, {"casual", "Casual"},
            {"cursive", "Manuscrita"}, {"sans-serif-smallcaps", "Versalitas"}};

    /**
     * Texto: escribir y elegir fuente, tamaño, color, negrita y cursiva. Uno nuevo va en su propia capa; uno existente
     * se cambia en su lugar (siempre se puede volver a editar, con deshacer).
     */
    private void textDialog(int layer, int idx, DrawDoc.VShape cur, float x, float y) {
        final DrawDoc.VShape v = cur != null ? cur : new DrawDoc.VShape();
        if (cur == null) {
            v.text = ""; v.fill = color; v.tsize = Math.max(24, Math.round(doc.H / 18f));
            v.pts = new float[]{x, y, x, y, x, y};
            v.strokeOn = false;
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        android.widget.EditText et = new android.widget.EditText(this);
        et.setText(v.text);
        et.setHint("Escribe aquí…");
        et.setMinLines(2);
        et.setTextSize(18);
        et.setSelection(et.getText().length());
        box.addView(et, new LinearLayout.LayoutParams(-1, -2));
        // fuente
        LinearLayout fonts = new LinearLayout(this);
        android.widget.HorizontalScrollView fs = new android.widget.HorizontalScrollView(this);
        fs.setHorizontalScrollBarEnabled(false);
        fs.addView(fonts);
        final List<TextView> fbs = new ArrayList<>();
        Runnable paintFonts = () -> {
            for (int i = 0; i < FONTS.length; i++) {
                android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
                g.setCornerRadius(dp(14));
                boolean on = FONTS[i][0].equals(v.font);
                g.setColor(on ? GREEN : 0x1F2FA866);
                fbs.get(i).setBackground(g);
                fbs.get(i).setTextColor(on ? Color.WHITE : INK_DARK);
            }
        };
        for (String[] f : FONTS) {
            TextView b = new TextView(this);
            b.setText("Aa  " + f[1]);
            b.setTypeface(android.graphics.Typeface.create(f[0], android.graphics.Typeface.NORMAL));
            b.setTextSize(15);
            b.setPadding(dp(12), dp(6), dp(12), dp(6));
            b.setOnClickListener(w -> { v.font = f[0]; paintFonts.run(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMargins(dp(3), dp(8), dp(3), dp(4));
            fonts.addView(b, lp);
            fbs.add(b);
        }
        paintFonts.run();
        box.addView(fs);
        // tamaño, color, negrita y cursiva
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView sz = label("", 14);
        Runnable showSize = () -> sz.setText("Tamaño " + Math.round(v.tsize));
        showSize.run();
        row.addView(pill("−", w -> { v.tsize = Math.max(6, v.tsize * 0.85f); showSize.run(); }));
        row.addView(sz);
        row.addView(pill("+", w -> { v.tsize = Math.min(doc.H, v.tsize / 0.85f); showSize.run(); }));
        View sw = new View(this);
        Runnable showColor = () -> {
            android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
            g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            g.setColor(v.fill);
            g.setStroke(dp(2), 0x33000000);
            sw.setBackground(g);
        };
        showColor.run();
        sw.setOnClickListener(w -> ColorWheel.pick(this, v.fill, c -> { v.fill = c | 0xFF000000; showColor.run(); }));
        LinearLayout.LayoutParams swl = new LinearLayout.LayoutParams(dp(30), dp(30));
        swl.setMargins(dp(10), 0, dp(4), 0);
        row.addView(sw, swl);
        TextView bB = pill("B", null), iB = pill("I", null);
        bB.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        iB.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.ITALIC));
        Runnable showStyle = () -> { bB.setAlpha(v.bold ? 1f : 0.45f); iB.setAlpha(v.italic ? 1f : 0.45f); };
        bB.setOnClickListener(w -> { v.bold = !v.bold; showStyle.run(); });
        iB.setOnClickListener(w -> { v.italic = !v.italic; showStyle.run(); });
        showStyle.run();
        row.addView(bB);
        row.addView(iB);
        android.widget.HorizontalScrollView rs = new android.widget.HorizontalScrollView(this);
        rs.setHorizontalScrollBarEnabled(false);
        rs.addView(row);
        box.addView(rs);
        Ui.Sheet sh = new Ui.Sheet(this).title(cur == null ? "Texto nuevo" : "Editar texto").view(box).button("Cancelar", false, null);
        if (cur != null) sh.button("Borrar", false, () -> op(opj("vec", "a", "del", "l", layer, "i", idx)));
        sh.button("Listo", true, () -> {
            v.text = et.getText().toString();
            if (v.text.trim().isEmpty()) { if (cur != null) op(opj("vec", "a", "del", "l", layer, "i", idx)); return; }
            if (cur == null) {   // texto nuevo: en una capa propia, arriba de la actual
                if (doc.layers.size() >= doc.maxLayers()) { Toast.makeText(this, "Máximo " + doc.maxLayers() + " capas", Toast.LENGTH_SHORT).show(); return; }
                op(opj("layer", "a", "add"));
                op(opj("vec", "a", "set", "l", doc.cur, "i", -1, "s", v.toJson()));
            } else op(opj("vec", "a", "set", "l", layer, "i", idx, "s", v.toJson()));
        }).show();
        et.requestFocus();
    }

    /** Editar: qué herramientas aparecen en el panel. */
    private void editTools() {
        java.util.Set<String> on = shownTools();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        List<android.widget.CheckBox> cbs = new ArrayList<>();
        for (String[] t : CATALOG) {
            android.widget.CheckBox cb = new android.widget.CheckBox(this);
            cb.setText(t[1] + "   " + t[2].replace(" (mantén para elegir)", ""));
            cb.setTextSize(15);
            cb.setChecked(on.contains(t[0]));
            cb.setTag(t[0]);
            cbs.add(cb);
            box.addView(cb);
        }
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(box);
        LinearLayout wrap = new LinearLayout(this);
        wrap.addView(sv, new LinearLayout.LayoutParams(-1, Math.min(dp(300), getResources().getDisplayMetrics().heightPixels / 2)));
        new Ui.Sheet(this).title("Herramientas del panel").message("Marca las que quieres ver. Mantén presionado el borde del panel para cambiar su tamaño.")
                .view(wrap)
                .button("Todas", false, () -> { prefs.edit().remove("dwTools").apply(); fillTools(); })
                .button("Listo", true, () -> {
                    StringBuilder sb = new StringBuilder();
                    for (android.widget.CheckBox cb : cbs) if (cb.isChecked()) sb.append(sb.length() > 0 ? "," : "").append(cb.getTag());
                    if (sb.length() == 0) { Toast.makeText(this, "Deja al menos una herramienta", Toast.LENGTH_SHORT).show(); return; }
                    prefs.edit().putString("dwTools", sb.toString()).apply();
                    fillTools();
                }).show();
    }

    private interface Each { void on(int i, Tool b); }

    /** Barrita flotante blanca sobre el lienzo (como la de selección). */
    private LinearLayout floatBar() {
        LinearLayout b = new LinearLayout(this);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setPadding(dp(6), dp(2), dp(6), dp(2));
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(0xF2FFFFFF);
        g.setStroke(dp(1), 0x402FA866);
        g.setCornerRadius(dp(18));
        b.setBackground(g);
        return b;
    }

    /** La barrita, deslizable si no cabe a lo ancho. */
    private View scrollBar(LinearLayout bar) {
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipToPadding(false);
        hs.addView(bar, new FrameLayout.LayoutParams(-2, -1));
        hs.setElevation(dp(10));
        bar.setTag(hs);
        return hs;
    }

    private static void show(LinearLayout bar, boolean on) {
        if (bar == null) return;
        View v = bar.getTag() instanceof View ? (View) bar.getTag() : bar;
        v.setVisibility(on ? View.VISIBLE : View.GONE);
    }

    private void setFill(int tol, int gap, boolean all) {
        fillTol = Math.max(0, Math.min(120, tol));
        fillGap = Math.max(0, Math.min(8, gap));
        fillAll = all;
        prefs.edit().putInt("dwFillTol", fillTol).putInt("dwFillGap", fillGap).putBoolean("dwFillAll", fillAll).apply();
        refreshTools();
    }

    private void setTol(int t) {
        selTol = Math.max(0, Math.min(120, t));
        prefs.edit().putInt("dwTol", selTol).apply();
        refreshTools();
    }

    private FrameLayout.LayoutParams valueBarLp() {
        boolean right = prefs.getBoolean("dwValRight", false);
        FrameLayout.LayoutParams l = new FrameLayout.LayoutParams(dp(66), -1, right ? Gravity.END : Gravity.START);
        l.topMargin = dp(54);
        l.bottomMargin = dp(8);
        return l;
    }

    private View grid(String[][] items, int cell, int cols, Each each) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(3), 0, dp(6));
        for (int r = 0; r * cols < items.length; r++) {
            LinearLayout row = new LinearLayout(this);
            for (int c = 0; c < cols && r * cols + c < items.length; c++) {
                int i = r * cols + c;
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
            case "tool": {
                int nt = ((Number) v).intValue();
                if (tool == T_XFORM && nt != T_XFORM) commitXform();   // al cambiar de herramienta, se aplica
                if (tool == T_PEN && nt != T_PEN && editor != null) editor.penFinish();
                tool = nt;
                if (tool == T_XFORM) { xf = new android.graphics.Matrix(); xfL = doc.cur; sendXf(true); }
                break;
            }
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
        if (valueBar != null) valueBar.setVisibility(brush == DrawDoc.VALUE && tool == T_DRAW ? View.VISIBLE : View.GONE);
        if (selBar != null) selBar.setVisibility((tool == T_WAND || tool == T_LASSO || doc.hasSel) && tool != T_XFORM && tool != T_PEN && tool != T_FILL ? View.VISIBLE : View.GONE);
        if (xfBar != null) xfBar.setVisibility(tool == T_XFORM ? View.VISIBLE : View.GONE);
        show(penBar, tool == T_PEN);
        show(fillBar, tool == T_FILL);
        if (fillTolL != null) {
            fillTolL.setText("Tolerancia " + fillTol);
            fillGapL.setText("  Huecos " + fillGap + " px");
            fillAllL.setText(fillAll ? "Mira: todas las capas" : "Mira: solo esta capa");
        }
        if (penHint != null && editor != null)
            penHint.setText(!penOff ? "Toca: recto · Arrastra: curva · 1er punto: cerrar"
                    : editor.awaitNode >= 0 ? "Ahora toca rápido = recto · desliza = curvo"
                    : "Elige dónde y suelta · 1er punto: cerrar");
        if (penStrokeL != null && editor != null) {
            DrawDoc.VShape v = editor.pv;
            penStrokeL.setText("Contorno: " + (v == null || v.strokeOn ? "sí" : "no"));
            penFillL.setText("Relleno: " + (v != null && v.fillOn ? "sí" : "no"));
        }
        if (tolLabel != null) tolLabel.setText("Tolerancia " + selTol);
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
        // cualquier otra orden (cambiar de capa, deshacer…) primero aplica la transformación pendiente en SU capa
        String o = m.optString("op");
        if (editor != null && !"vec".equals(o) && !"view".equals(o)) editor.penDrop();   // deshacer, cambiar de capa…: la forma en edición se suelta
        if (xf != null && !"xfPrev".equals(o) && !"xform".equals(o)) commitXform();
        doc.apply(m);
        // dibujo nuevo: después se manda entero, así los dos teléfonos quedan con el mismo tamaño sí o sí
        if ("new".equals(m.optString("op")) && online()) h.postDelayed(this::sendFull, 300);
        if (online()) {
            try { m.put("t", "dw"); } catch (Exception ignored) { }
            dwSend(m);
        }
        changed();
    }

    private void layerAction(String a) {
        int i = doc.cur;
        switch (a) {
            case "Nueva": request(opj("layer", "a", "add")); break;
            case "Duplicar": request(opj("layer", "a", "dup", "i", i)); break;
            case "Combinar":
                if (doc.layers.size() < 2) { Toast.makeText(this, "Hay una sola capa", Toast.LENGTH_SHORT).show(); return; }
                new Ui.Sheet(this).title("Combinar capas").message("Se respeta la opacidad y la mezcla de cada capa.")
                        .optionIf(i > 0, 0, "Con la de abajo", doc.layers.get(i).name + " + " + (i > 0 ? doc.layers.get(i - 1).name : ""), () -> request(opj("layer", "a", "merge", "i", i)))
                        .option(0, "Todas las visibles", "Quedan en una sola capa", () -> request(opj("layer", "a", "mergeVis")))
                        .option(0, "Elegir capas…", "Marca las que quieres juntar", this::pickMerge)
                        .button("Cancelar", false, null).show();
                break;
            case "Voltear": request(opj("layer", "a", "flip", "i", i)); break;
            case "Subir": request(opj("layer", "a", "up", "i", i)); break;
            case "Bajar": request(opj("layer", "a", "down", "i", i)); break;
            case "Importar": pickImage(); break;
            case "Vaciar": new Ui.Sheet(this).title("¿Vaciar la " + doc.layers.get(i).name + "?").message("Se puede deshacer con ↶.")
                    .button("Cancelar", false, null).button("Vaciar", true, () -> request(opj("clear", "i", i))).show(); break;
        }
    }

    private void pickMerge() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        List<android.widget.CheckBox> cbs = new ArrayList<>();
        for (int k = doc.layers.size() - 1; k >= 0; k--) {
            android.widget.CheckBox cb = new android.widget.CheckBox(this);
            cb.setText(doc.layers.get(k).name + (doc.layers.get(k).visible ? "" : "  (oculta)"));
            cb.setTextSize(15);
            cb.setChecked(k == doc.cur || k == doc.cur - 1);
            cb.setTag(k);
            cbs.add(cb);
            box.addView(cb);
        }
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(box);
        sv.setLayoutParams(new LinearLayout.LayoutParams(-1, Math.min(dp(260), dp(44) * cbs.size())));
        LinearLayout wrap = new LinearLayout(this);
        wrap.addView(sv, new LinearLayout.LayoutParams(-1, Math.min(dp(260), dp(44) * cbs.size())));
        new Ui.Sheet(this).title("Elegir capas").message("Se juntan en la de más abajo de las marcadas.").view(wrap)
                .button("Cancelar", false, null)
                .button("Combinar", true, () -> {
                    JSONArray ids = new JSONArray();
                    for (android.widget.CheckBox cb : cbs) if (cb.isChecked()) ids.put((int) cb.getTag());
                    if (ids.length() < 2) { Toast.makeText(this, "Marca al menos dos capas", Toast.LENGTH_SHORT).show(); return; }
                    request(opj("layer", "a", "mergeSet", "ids", ids));
                }).show();
    }

    private final Runnable refreshLater = this::refreshLayers;
    private final Runnable blurLater = () -> { if (viewer != null) viewer.rebuildBlur(); };

    /** Cambió el dibujo: se repinta, y el panel de capas y el cristal se ponen al día (sin apuro). */
    private int seenW, seenH;

    private void changed() {
        if (sizeLabel != null) sizeLabel.setText(doc.W + " × " + doc.H);
        if (doc.W != seenW || doc.H != seenH) {   // dibujo nuevo de otro tamaño: se vuelve a encuadrar
            if (seenW != 0) Toast.makeText(this, "Dibujo de " + doc.W + " × " + doc.H + " px", Toast.LENGTH_SHORT).show();
            seenW = doc.W; seenH = doc.H;
            if (viewer != null) viewer.frame = null;   // el marco del encuadre anterior ya no sirve (era de otro tamaño)
            if (editor != null && editor.getWidth() > 0) { editor.lastViewSent = 0; editor.fit(); }
            if (viewer != null && viewer.getWidth() > 0) { viewer.fit(); viewer.rebuildBlur(); }
        }
        if (selBar != null) selBar.setVisibility((tool == T_WAND || tool == T_LASSO || doc.hasSel) && tool != T_XFORM && tool != T_PEN && tool != T_FILL ? View.VISIBLE : View.GONE);
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
            g.setColor(i == doc.cur ? 0x402FA866 : 0x0D000000);
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
            if (!l.shapes.isEmpty()) { c.save(); c.scale(w / doc.W, hh / doc.H); doc.drawShapes(c, l, -2); c.restore(); }
        }
    }

    // ======================= mensajes =======================

    // ======================= tableta de dibujo (lápiz) =======================
    // Una tableta (Wacom, XP-Pen, Huion…) conectada al PRINCIPAL dibuja en el área de trabajo del extendido: el lápiz no
    // toca los paneles del principal (color, capas); toda la tableta corresponde a lo que se ve en el lienzo del
    // extendido (con su zoom). Con la tableta conectada al extendido, Android la usa directo como lápiz.
    //   dwPen {a: 0 bajar · 1 mover · 2 subir · 3 flotar · 4 dejó de flotar, pts:[x,y,p,…] (0..1), er: borrador}

    private static boolean isPen(MotionEvent e) {
        int tt = e.getPointerCount() > 0 ? e.getToolType(0) : 0;
        return tt == MotionEvent.TOOL_TYPE_STYLUS || tt == MotionEvent.TOOL_TYPE_ERASER
                || (e.getSource() & android.view.InputDevice.SOURCE_STYLUS) == android.view.InputDevice.SOURCE_STYLUS;
    }

    private boolean penHere() { return "view".equals(mode) && linked() && hub != null && hub.connected(); }

    @Override public boolean dispatchTouchEvent(MotionEvent e) {
        if (penHere() && isPen(e)) { forwardPen(e); return true; }   // el lápiz no toca los paneles del principal
        return super.dispatchTouchEvent(e);
    }

    @Override public boolean dispatchGenericMotionEvent(MotionEvent e) {
        int a = e.getActionMasked();
        if (penHere() && isPen(e) && (a == MotionEvent.ACTION_HOVER_MOVE || a == MotionEvent.ACTION_HOVER_ENTER || a == MotionEvent.ACTION_HOVER_EXIT)) {
            forwardPen(e);
            return true;
        }
        return super.dispatchGenericMotionEvent(e);
    }

    private long lastHoverSent;

    private void forwardPen(MotionEvent e) {
        View root = getWindow().getDecorView();
        float W = Math.max(1, root.getWidth()), H = Math.max(1, root.getHeight());
        int act = e.getActionMasked(), a;
        switch (act) {
            case MotionEvent.ACTION_DOWN: a = 0; break;
            case MotionEvent.ACTION_MOVE: a = 1; break;
            case MotionEvent.ACTION_UP: case MotionEvent.ACTION_CANCEL: a = 2; break;
            case MotionEvent.ACTION_HOVER_EXIT: a = 4; break;
            case MotionEvent.ACTION_HOVER_MOVE: case MotionEvent.ACTION_HOVER_ENTER: a = 3; break;
            default: return;
        }
        if (a == 3) {   // flotar: basta unas 40 veces por segundo
            long now = android.os.SystemClock.uptimeMillis();
            if (now - lastHoverSent < 25) return;
            lastHoverSent = now;
        }
        JSONArray pts = new JSONArray();
        try {
            if (a == 1) for (int i = 0; i < e.getHistorySize(); i++) {
                pts.put(e.getHistoricalX(0, i) / W); pts.put(e.getHistoricalY(0, i) / H); pts.put((double) e.getHistoricalPressure(0, i));
            }
            pts.put(e.getX() / W); pts.put(e.getY() / H); pts.put((double) e.getPressure());
        } catch (Exception ignored) { }
        boolean er = e.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER || (e.getButtonState() & MotionEvent.BUTTON_STYLUS_PRIMARY) != 0;
        hub.send("dwPen", "a", a, "pts", pts, "er", er);
    }

    @Override public void onMessage(JSONObject m) {
        String t = m.optString("t");
        switch (t) {
            case "dwPen": if (editing() && editor != null) h.post(() -> editor.pen(m)); break;
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

    /** true si había un dibujo en curso guardado. */
    private boolean load() {
        File f = file();
        if (!f.exists()) return false;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] d = new byte[(int) f.length()];
            int n = 0, r;
            while (n < d.length && (r = in.read(d, n, d.length - n)) > 0) n += r;
            doc.fromJson(new JSONObject(new String(d, 0, n, StandardCharsets.UTF_8)));
            return true;
        } catch (Exception e) { return false; }
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

    /**
     * Todo lo que el extendido manda del dibujo sale por UNA fila, en orden: así el dibujo completo (que tarda en
     * armarse) nunca llega después de una orden más nueva y la pisa (pasaba al importar una imagen: se perdía la capa).
     */
    private static final java.util.concurrent.ExecutorService outQ = java.util.concurrent.Executors.newSingleThreadExecutor();

    private void dwSend(JSONObject m) {
        try { if (!m.has("t")) m.put("t", "dw"); } catch (Exception ignored) { }
        outQ.execute(() -> hub.send(m));
    }

    private void sendFull() {
        final DrawDoc.Snap snap = doc.snapshot();
        outQ.execute(() -> {
            JSONObject o = DrawDoc.toJson(snap);
            try { o.put("t", "dwFull"); } catch (Exception ignored) { }
            hub.send(o);
        });
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

    /** Guarda con capas (.psd: lo abren Photoshop, Krita, GIMP, Photopea, Procreate…). */
    private void exportPsd() {
        if (doc.stroking) { Toast.makeText(this, "Termina el trazo y vuelve a intentar", Toast.LENGTH_SHORT).show(); return; }
        Toast.makeText(this, "Guardando con capas…", Toast.LENGTH_SHORT).show();
        final DrawDoc.Snap snap = doc.snapshot();
        new Thread(() -> {
            String name = "DUAL-Draw " + new java.text.SimpleDateFormat("yyyy-MM-dd HH.mm.ss", java.util.Locale.ROOT).format(new java.util.Date()) + ".psd";
            boolean ok = false;
            String where = "Descargas › DUAL-Draw";
            try {
                DrawDoc copy = new DrawDoc();
                copy.restore(snap);
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                    cv.put(MediaStore.MediaColumns.MIME_TYPE, "image/vnd.adobe.photoshop");
                    cv.put("relative_path", "Download/DUAL-Draw");
                    Uri u = getContentResolver().insert(Uri.parse("content://media/external/downloads"), cv);
                    if (u != null) try (OutputStream out = getContentResolver().openOutputStream(u)) { if (out != null) { copy.writePsd(out); ok = true; } }
                } else {
                    File dir = new File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "DUAL-Draw");
                    //noinspection ResultOfMethodCallIgnored
                    dir.mkdirs();
                    try (FileOutputStream out = new FileOutputStream(new File(dir, name))) { copy.writePsd(out); ok = true; }
                }
                copy.dispose();
            } catch (Throwable ignored) { }
            final boolean fok = ok;
            h.post(() -> Toast.makeText(this, fok ? "Guardado con capas en " + where : "No se pudo guardar el PSD", Toast.LENGTH_LONG).show());
        }, "dualdraw-psd").start();
    }

    private void pickImage() {
        if (doc.layers.size() >= doc.maxLayers()) { Toast.makeText(this, "Máximo " + doc.maxLayers() + " capas", Toast.LENGTH_SHORT).show(); return; }
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
                float k = Math.min(1f, Math.min(doc.W / (float) b.getWidth(), doc.H / (float) b.getHeight()));
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
        final int tint;
        final Paint tintP = new Paint(Paint.ANTI_ALIAS_FLAG);
        Glass2(Board over, int tint) {
            super(DualDrawActivity.this);
            this.over = over;
            this.tint = tint;
            tintP.setColor(tint);
            setOrientation(VERTICAL);
            setWillNotDraw(false);
            setClickable(true);
        }

        // ---- cambiar el tamaño: mantener presionado el borde o el título del panel y arrastrar
        String sizeKey;
        boolean anchorEnd, vCenter, resizing;
        Runnable onResized;
        private float rx, ry;
        private int w0, h0;
        private final Runnable startResize = () -> {
            resizing = true;
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            Toast.makeText(getContext(), "Arrastra para cambiar el tamaño del panel", Toast.LENGTH_SHORT).show();
            invalidate();
        };

        void resizable(String key, boolean anchorEnd, boolean vCenter, Runnable onResized) {
            sizeKey = key; this.anchorEnd = anchorEnd; this.vCenter = vCenter; this.onResized = onResized;
        }

        void applySaved() {
            if (sizeKey == null) return;
            int w = prefs.getInt(sizeKey + "W", 0), hh = prefs.getInt(sizeKey + "H", 0);
            android.view.ViewGroup.LayoutParams lp = getLayoutParams();
            if (lp == null) return;
            if (w > 0) lp.width = w;
            if (hh > 0) lp.height = hh;
            setLayoutParams(lp);
            if (onResized != null && (w > 0 || hh > 0)) post(onResized);
        }

        @Override public boolean onTouchEvent(android.view.MotionEvent e) {
            if (sizeKey == null) return super.onTouchEvent(e);
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    rx = e.getRawX(); ry = e.getRawY(); w0 = getWidth(); h0 = getHeight();
                    postDelayed(startResize, 550);
                    return true;
                case android.view.MotionEvent.ACTION_MOVE: {
                    float dx = e.getRawX() - rx, dy = e.getRawY() - ry;
                    if (!resizing) { if (Math.hypot(dx, dy) > 12 * dp) removeCallbacks(startResize); return true; }
                    android.view.ViewGroup.LayoutParams lp = getLayoutParams();
                    View parent = (View) getParent();
                    int maxW = parent == null ? 4000 : parent.getWidth() * 2 / 3, maxH = parent == null ? 4000 : parent.getHeight();
                    lp.width = Math.max(dp(90), Math.min(maxW, Math.round(w0 + (anchorEnd ? -dx : dx))));
                    lp.height = Math.max(dp(120), Math.min(maxH, Math.round(h0 + (vCenter ? 2 * dy : dy))));
                    setLayoutParams(lp);
                    if (onResized != null) onResized.run();
                    return true;
                }
                case android.view.MotionEvent.ACTION_UP: case android.view.MotionEvent.ACTION_CANCEL:
                    removeCallbacks(startResize);
                    if (resizing) {
                        resizing = false;
                        prefs.edit().putInt(sizeKey + "W", getLayoutParams().width).putInt(sizeKey + "H", getLayoutParams().height).apply();
                        invalidate();
                    }
                    return true;
            }
            return true;
        }

        @Override protected void onDraw(Canvas c) {
            RectF r = new RectF(0, 0, getWidth(), getHeight());
            float rad = 20 * dp;
            // sombra difuminada sobre el fondo gris (solo afuera del vidrio, para que no lo oscurezca)
            android.graphics.Path out = new android.graphics.Path();
            out.addRoundRect(r, rad, rad, android.graphics.Path.Direction.CW);
            c.save();
            c.clipOutPath(out);
            Glass.shadow(c, r, rad, dp, false);
            c.restore();
            Glass.body(c, r, rad, dp, over == null || over.blur == null ? null : () -> {
                RectF dst = new RectF(over.dst);
                dst.offset(-getLeft() - getTranslationX(), -getTop() - getTranslationY());
                c.drawColor(BG);
                c.drawBitmap(over.blur, null, dst, over.blurPaint);
            });
            // color del panel: blanco (con verde) o naranjo, sobre el vidrio
            c.drawRoundRect(r, rad, rad, tintP);
            if (resizing) {   // cambiando el tamaño: borde naranjo y esquina para arrastrar
                Paint e = new Paint(Paint.ANTI_ALIAS_FLAG);
                e.setStyle(Paint.Style.STROKE);
                e.setStrokeWidth(3 * dp);
                e.setColor(ORANGE);
                c.drawRoundRect(new RectF(1.5f * dp, 1.5f * dp, getWidth() - 1.5f * dp, getHeight() - 1.5f * dp), rad, rad, e);
            }
        }
    }

    /** Botón de herramienta: símbolo grande y nombre chico; encendido = naranja. */
    private final class Tool extends View {
        String glyph;
        final String name;
        final boolean small;
        boolean on, orange;
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
            // solo el símbolo (el nombre aparece al mantener presionado)
            float w = getWidth(), hh = getHeight();
            if (orange) {
                bg.setColor(on ? Color.WHITE : isPressed() ? 0x59FFFFFF : 0x2EFFFFFF);
                p.setColor(on ? ORANGE : Color.WHITE);
            } else {
                bg.setColor(on ? GREEN : isPressed() ? 0x332FA866 : 0x0F000000);
                p.setColor(on ? Color.WHITE : INK_DARK);
            }
            p.clearShadowLayer();
            c.drawRoundRect(new RectF(0, 0, w, hh), 11 * dp, 11 * dp, bg);
            p.setTextSize(Math.min(hh, w) * 0.5f);
            Paint.FontMetrics fm = p.getFontMetrics();
            c.drawText(glyph, w / 2, hh / 2 - (fm.ascent + fm.descent) / 2, p);
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
            t.clearShadowLayer();
            t.setTextSize(13 * dp);
            c.drawText(name, getWidth() / 2f, 13 * dp, t);
            t.setTextSize(11 * dp);
            c.drawText(String.valueOf(Math.round(val)), getWidth() / 2f, 26 * dp, t);
            p.setColor(0x40FFFFFF);
            c.drawRoundRect(tr, 5 * dp, 5 * dp, p);
            float f = (val - min) / (max - min), y = tr.bottom - f * tr.height();
            p.setColor(Color.WHITE);
            c.drawRoundRect(new RectF(tr.left, y, tr.right, tr.bottom), 5 * dp, 5 * dp, p);
            c.drawRoundRect(new RectF(getWidth() / 2f - 13 * dp, y - 5 * dp, getWidth() / 2f + 13 * dp, y + 5 * dp), 4 * dp, 4 * dp, p);
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
            p.setColor(0x26000000);
            c.drawRoundRect(new RectF(x0, y - 3 * dp, x1, y + 3 * dp), 3 * dp, 3 * dp, p);
            p.setColor(GREEN);
            c.drawRoundRect(new RectF(x0, y - 3 * dp, x, y + 3 * dp), 3 * dp, 3 * dp, p);
            c.drawCircle(x, y, 8 * dp, p);
            p.setColor(Color.WHITE);
            c.drawCircle(x, y, 5 * dp, p);
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

    // ======================= transformar =======================

    private long xfSent;

    /** Vista previa de la capa transformada en los dos teléfonos (sin aplicar todavía). */
    private void sendXf(boolean force) {
        if (xf == null) return;
        long now = SystemClock.uptimeMillis();
        if (!force && now - xfSent < 50) return;
        xfSent = now;
        op(opj("xfPrev", "l", xfL, "m", mjson(xf)));
    }

    private JSONArray mjson(android.graphics.Matrix m) {
        float[] v = new float[9];
        m.getValues(v);
        JSONArray a = new JSONArray();
        try { for (float f : v) a.put((double) f); } catch (Exception ignored) { }
        return a;
    }

    private void commitXform() {
        if (xf == null) return;
        android.graphics.Matrix m = xf;
        xf = null;
        if (m.isIdentity()) op(opj("xfPrev", "l", xfL));
        else op(opj("xform", "l", xfL, "m", mjson(m)));
        if (tool == T_XFORM) { tool = T_DRAW; refreshTools(); sendState(); }
    }

    private void cancelXform() {
        xf = null;
        op(opj("xfPrev", "l", xfL));
        tool = T_DRAW;
        refreshTools();
        sendState();
    }

    /**
     * "Valor de trazo": barra vertical a un costado del lienzo. Con un dedo se dibuja y con el otro se sube o baja
     * en la barra para engrosar o afinar la línea mientras se traza (como apretar más o menos el lápiz).
     */
    private final class ValueBar extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), t = new Paint(Paint.ANTI_ALIAS_FLAG);
        ValueBar() {
            super(DualDrawActivity.this);
            t.setTextAlign(Paint.Align.CENTER);
            t.setColor(INK_DARK);
        }
        private RectF track() { return new RectF(dp(10), dp(40), getWidth() - dp(10), getHeight() - dp(26)); }
        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), hh = getHeight();
            p.setColor(0xF2FFFFFF);
            c.drawRoundRect(new RectF(0, 0, w, hh), dp(18), dp(18), p);
            t.setTextSize(10 * dp);
            c.drawText("⇄ lado", w / 2, dp(14), t);
            t.setTextSize(12 * dp);
            c.drawText(Math.round(valueLevel * 100) + "%", w / 2, dp(32), t);
            RectF tr = track();
            // cuña: fina abajo, gruesa arriba
            android.graphics.Path wedge = new android.graphics.Path();
            wedge.moveTo(tr.centerX() - dp(1), tr.bottom);
            wedge.lineTo(tr.centerX() + dp(1), tr.bottom);
            wedge.lineTo(tr.right - dp(4), tr.top);
            wedge.lineTo(tr.left + dp(4), tr.top);
            wedge.close();
            p.setColor(0x1F000000);
            c.drawPath(wedge, p);
            float y = tr.bottom - valueLevel * tr.height();
            c.save();
            c.clipRect(tr.left, y, tr.right, tr.bottom);
            p.setColor(GREEN);
            c.drawPath(wedge, p);
            c.restore();
            p.setColor(INK_DARK);
            c.drawRoundRect(new RectF(dp(6), y - dp(3), w - dp(6), y + dp(3)), dp(3), dp(3), p);
            // muestra del grosor real a este zoom
            float r = Math.max(1, size * 0.5f * valueLevel * (editor != null && editor.zoom > 0 ? editor.zoom : 1));
            p.setColor(color);
            c.drawCircle(w / 2, hh - dp(13), Math.min(dp(11), r), p);
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN && e.getY() < dp(22)) {   // cambiar de lado
                prefs.edit().putBoolean("dwValRight", !prefs.getBoolean("dwValRight", false)).apply();
                setLayoutParams(valueBarLp());
                return true;
            }
            RectF tr = track();
            valueLevel = Math.max(0.03f, Math.min(1f, (tr.bottom - e.getY()) / tr.height()));
            invalidate();
            getParent().requestDisallowInterceptTouchEvent(true);
            if (e.getActionMasked() == MotionEvent.ACTION_UP) prefs.edit().putFloat("dwValue", valueLevel).apply();
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
        private final List<Float> lasso = new ArrayList<>();
        private float tx0, ty0, tmx, tmy, tdist, tang;
        private boolean tTwo;
        private Paint sheetShadow, info;
        private final Paint selP = new Paint(Paint.FILTER_BITMAP_FLAG), lassoP = new Paint(Paint.ANTI_ALIAS_FLAG);

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
            selP.setColorFilter(new android.graphics.PorterDuffColorFilter(0x6633A0FF, android.graphics.PorterDuff.Mode.SRC_IN));
            lassoP.setStyle(Paint.Style.STROKE);
            lassoP.setStrokeWidth(2 * dp);
            lassoP.setColor(0xFF33A0FF);
            lassoP.setPathEffect(new android.graphics.DashPathEffect(new float[]{6 * dp, 4 * dp}, 0));
        }

        float fitZoom() { return Math.min(getWidth() / (float) doc.W, getHeight() / (float) doc.H); }

        void fit() {
            zoom = fitZoom();
            ox = -(getWidth() / zoom - doc.W) / 2f;
            oy = -(getHeight() / zoom - doc.H) / 2f;
            sendView();
            invalidate();
        }

        @Override protected void onSizeChanged(int w, int hh, int ow, int oh) { if (w > 0 && hh > 0) { fit(); if (!edit) rebuildBlur(); } }

        void rebuildBlur() {
            if (edit) return;
            Bitmap s = Bitmap.createBitmap(doc.W / 10, doc.H / 10, Bitmap.Config.ARGB_8888);
            doc.draw(new Canvas(s), new RectF(0, 0, s.getWidth(), s.getHeight()));
            Glass.boxBlur(s, 3);
            blur = s;
            if (leftPanel != null) leftPanel.invalidate();
            if (rightPanel != null) rightPanel.invalidate();
        }

        private void clamp() {
            float fz = fitZoom();
            zoom = Math.max(fz * 0.25f, Math.min(fz * 12, zoom));   // se puede alejar más que "ver todo"
            float vw = getWidth() / zoom, vh = getHeight() / zoom;
            ox = vw >= doc.W ? -(vw - doc.W) / 2f : Math.max(0, Math.min(doc.W - vw, ox));
            oy = vh >= doc.H ? -(vh - doc.H) / 2f : Math.max(0, Math.min(doc.H - vh, oy));
        }

        long lastViewSent;

        private void sendView() {
            if (!edit) return;
            RectF r = new RectF(Math.max(0, ox), Math.max(0, oy), Math.min(doc.W, ox + getWidth() / zoom), Math.min(doc.H, oy + getHeight() / zoom));
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
            // la hoja (con zoom) nunca se sale de su espacio: los paneles de herramientas siempre quedan a la vista
            c.clipRect(0, 0, getWidth(), getHeight());
            c.save();
            if (flip) c.scale(-1, 1, getWidth() / 2f, 0);
            dst.set(-ox * zoom, -oy * zoom, (doc.W - ox) * zoom, (doc.H - oy) * zoom);
            // la hoja proyecta una sombra difuminada sobre el fondo gris
            if (sheetShadow == null) {
                sheetShadow = new Paint(Paint.ANTI_ALIAS_FLAG);
                sheetShadow.setColor(0x8C000000);
                sheetShadow.setMaskFilter(new android.graphics.BlurMaskFilter(16 * dp, android.graphics.BlurMaskFilter.Blur.NORMAL));
            }
            c.drawRect(dst.left + 2 * dp, dst.top + 6 * dp, dst.right - 2 * dp, dst.bottom + 9 * dp, sheetShadow);
            if (edit && pv != null && pvI >= 0) { doc.hideL = pvL; doc.hideI = pvI; }
            if (edit && tDrag != null) { doc.hideL = tL; doc.hideI = tI; }
            doc.draw(c, dst);
            doc.hideL = doc.hideI = -1;
            if (edit && pv != null) drawPen(c);
            if (edit && tool == T_TEXT) drawTextBoxes(c);
            if (sym) c.drawLine(dst.centerX(), dst.top, dst.centerX(), dst.bottom, axis);
            if (doc.hasSel && doc.sel != null) c.drawBitmap(doc.sel, null, dst, selP);
            if (doc.xfM != null) {   // marco de la capa que se está transformando
                float[] pts = {0, 0, doc.W, 0, doc.W, doc.H, 0, doc.H};
                doc.xfM.mapPoints(pts);
                android.graphics.Path bp = new android.graphics.Path();
                for (int i = 0; i < 8; i += 2) {
                    float px = dst.left + pts[i] * zoom, py = dst.top + pts[i + 1] * zoom;
                    if (i == 0) bp.moveTo(px, py); else bp.lineTo(px, py);
                }
                bp.close();
                lassoP.setColor(GREEN);
                c.drawPath(bp, lassoP);
                lassoP.setColor(0xFF33A0FF);
            }
  // lo seleccionado, en celeste
            if (edit && lasso.size() >= 4) {
                android.graphics.Path lp = new android.graphics.Path();
                lp.moveTo((lasso.get(0) - ox) * zoom, (lasso.get(1) - oy) * zoom);
                for (int i = 2; i + 1 < lasso.size(); i += 2) lp.lineTo((lasso.get(i) - ox) * zoom, (lasso.get(i + 1) - oy) * zoom);
                c.drawPath(lp, lassoP);
            }
            if (!edit && frame != null && frame.right <= doc.W + 1 && frame.bottom <= doc.H + 1 && (frame.width() < doc.W - 2 || frame.height() < doc.H - 2))
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
            if (edit) drawPenRing(c);
            if (edit && offShow) drawOffCursor(c);
            if (edit) {   // tamaño del dibujo y versión, abajo a la izquierda
                if (info == null) { info = new Paint(Paint.ANTI_ALIAS_FLAG); info.setColor(0xCCFFFFFF); info.setTextSize(11 * dp); info.setShadowLayer(2 * dp, 0, dp / 2, 0x99000000); }
                c.drawText(doc.W + " × " + doc.H + " px · " + Updater.installedVersion(getContext()), 8 * dp, getHeight() - 8 * dp, info);
            }
        }

        // ---- lápiz de una tableta conectada al principal
        private long penDown;
        private int penSavedBrush = -1;
        private boolean penHover;
        private float penHx, penHy;
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);

        /** Repite en el lienzo el trazo del lápiz (como si se dibujara aquí, con su presión). */
        void pen(JSONObject m) {
            if (!edit) return;
            int a = m.optInt("a");
            JSONArray pts = m.optJSONArray("pts");
            if (pts == null || pts.length() < 3) return;
            float W = getWidth(), H = getHeight();
            if (a >= 3) {   // flotando: solo el circulito que muestra dónde va a caer
                penHover = a == 3;
                penHx = (float) pts.optDouble(pts.length() - 3) * W;
                penHy = (float) pts.optDouble(pts.length() - 2) * H;
                invalidate();
                return;
            }
            penHover = false;
            long now = android.os.SystemClock.uptimeMillis();
            if (a == 0) {
                penDown = now;
                if (m.optBoolean("er") && !DrawDoc.eraser(brush)) { penSavedBrush = brush; brush = DrawDoc.ERASER; }   // goma del lápiz
            }
            MotionEvent.PointerProperties[] pp = {new MotionEvent.PointerProperties()};
            pp[0].id = 0;
            pp[0].toolType = MotionEvent.TOOL_TYPE_STYLUS;
            int action = a == 0 ? MotionEvent.ACTION_DOWN : a == 1 ? MotionEvent.ACTION_MOVE : MotionEvent.ACTION_UP;
            MotionEvent ev = null;
            for (int i = 0; i + 2 < pts.length(); i += 3) {
                MotionEvent.PointerCoords[] pc = {new MotionEvent.PointerCoords()};
                pc[0].x = (float) pts.optDouble(i) * W;
                pc[0].y = (float) pts.optDouble(i + 1) * H;
                pc[0].pressure = (float) pts.optDouble(i + 2);
                pc[0].size = 0.05f;
                if (ev == null) ev = MotionEvent.obtain(penDown, now, action, 1, pp, pc, 0, 0, 1, 1, 0, 0, android.view.InputDevice.SOURCE_STYLUS, 0);
                else ev.addBatch(now, pc, 0);
            }
            if (ev != null) { onTouchEvent(ev); ev.recycle(); }
            if (a == 2 && penSavedBrush >= 0) { brush = penSavedBrush; penSavedBrush = -1; }
            penHx = (float) pts.optDouble(pts.length() - 3) * W;
            penHy = (float) pts.optDouble(pts.length() - 2) * H;
        }

        /** Lápiz conectado a este mismo teléfono: el circulito mientras flota. */
        @Override public boolean onHoverEvent(MotionEvent e) {
            if (!edit || !isPen(e)) return super.onHoverEvent(e);
            penHover = e.getActionMasked() != MotionEvent.ACTION_HOVER_EXIT;
            penHx = e.getX(); penHy = e.getY();
            invalidate();
            return true;
        }

        private void drawPenRing(Canvas c) {
            if (!penHover) return;
            // del porte real de la punta: el mismo radio con que va a pintar (a presión máxima)
            float r = Math.max(2 * dp, DrawDoc.radiusFor(tool != T_DRAW ? DrawDoc.MARKER : brush, size, 1f) * zoom);
            if (tool == T_PEN || tool == T_FILL || tool == T_PICK || tool == T_WAND) r = 3 * dp;
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(2.5f * dp);
            ring.setColor(0xAA000000);
            c.drawCircle(penHx, penHy, r, ring);
            ring.setStrokeWidth(1.2f * dp);
            ring.setColor(0xFFFFFFFF);
            c.drawCircle(penHx, penHy, r, ring);
            ring.setStyle(Paint.Style.FILL);
            c.drawCircle(penHx, penHy, 1.2f * dp, ring);
        }

        private float sxv(float x) { return flip ? getWidth() - x : x; }
        private float toX(float sx) { return sxv(sx) / zoom + ox; }
        private float toY(float sy) { return sy / zoom + oy; }

        /** Presión: la del dedo si el teléfono la entrega de verdad; si no, sale de la velocidad (lento = más grueso). */
        private float pressure(MotionEvent e, float x, float y, long t) {
            if (brush == DrawDoc.VALUE) return Math.max(0.03f, valueLevel);   // el grosor lo pone la barra, en vivo
            float raw = e.getPressure(0);
            if (e.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS || e.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER) {
                // lápiz (tableta o pantalla con lápiz): su presión es de verdad, se usa tal cual (suavizada)
                ps = ps * 0.5f + Math.max(0.02f, Math.min(1f, raw)) * 0.5f;
                return ps;
            }
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
                dwSend(m);
            }
            pend.clear();
            pendStart = false;
        }

        private void cancelStroke() {
            if (!drawing) return;
            drawing = false;
            pend.clear();
            doc.cancel();
            if (online() && !pendStart) dwSend(opj("cancel"));
        }

        /** Transformar la capa: un dedo la mueve; dos dedos la escalan y giran (alrededor del punto medio). */
        private boolean xformTouch(MotionEvent e) {
            if (xf == null) { xf = new android.graphics.Matrix(); xfL = doc.cur; }
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    tTwo = false;
                    tx0 = toX(e.getX()); ty0 = toY(e.getY());
                    return true;
                case MotionEvent.ACTION_POINTER_DOWN:
                    if (e.getPointerCount() == 2) {
                        tTwo = true;
                        tmx = (toX(e.getX(0)) + toX(e.getX(1))) / 2; tmy = (toY(e.getY(0)) + toY(e.getY(1))) / 2;
                        tdist = Math.max(1, (float) Math.hypot(toX(e.getX(0)) - toX(e.getX(1)), toY(e.getY(0)) - toY(e.getY(1))));
                        tang = (float) Math.toDegrees(Math.atan2(toY(e.getY(1)) - toY(e.getY(0)), toX(e.getX(1)) - toX(e.getX(0))));
                    }
                    return true;
                case MotionEvent.ACTION_POINTER_UP: {
                    // queda un dedo: sigue moviendo desde donde está ese dedo (sin saltos)
                    int keep = e.getActionIndex() == 0 ? 1 : 0;
                    tTwo = false;
                    tx0 = toX(e.getX(keep)); ty0 = toY(e.getY(keep));
                    return true;
                }
                case MotionEvent.ACTION_MOVE:
                    if (tTwo && e.getPointerCount() >= 2) {
                        float mx = (toX(e.getX(0)) + toX(e.getX(1))) / 2, my = (toY(e.getY(0)) + toY(e.getY(1))) / 2;
                        float d = Math.max(1, (float) Math.hypot(toX(e.getX(0)) - toX(e.getX(1)), toY(e.getY(0)) - toY(e.getY(1))));
                        float a = (float) Math.toDegrees(Math.atan2(toY(e.getY(1)) - toY(e.getY(0)), toX(e.getX(1)) - toX(e.getX(0))));
                        xf.postTranslate(mx - tmx, my - tmy);
                        xf.postScale(d / tdist, d / tdist, mx, my);
                        xf.postRotate(a - tang, mx, my);
                        tmx = mx; tmy = my; tdist = d; tang = a;
                    } else if (!tTwo) {
                        float x = toX(e.getX()), y = toY(e.getY());
                        xf.postTranslate(x - tx0, y - ty0);
                        tx0 = x; ty0 = y;
                    }
                    sendXf(false);
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP: case MotionEvent.ACTION_CANCEL:
                    tTwo = false;
                    sendXf(true);
                    invalidate();
                    return true;
            }
            return true;
        }

        // ---- pluma (formas vectoriales, como la de FreeHand)
        DrawDoc.VShape pv;               // la forma que se está dibujando o editando
        int pvL = -1, pvI = -1;          // en qué capa y en qué lugar está (pvI = -1: todavía no se guarda)
        private int pvSel = -1, pDrag, pNode, pBakI, pBakSel;
        // pDrag: 1 punto nuevo (arrastrar = curva) · 2 mover punto · 3 manilla de entrada · 4 de salida · 5 cerrar en el primer punto
        private boolean pMoved, pSmooth;
        private float pdx, pdy;
        private DrawDoc.VShape pBak;
        private final Paint penP = new Paint(Paint.ANTI_ALIAS_FLAG), penUi = new Paint(Paint.ANTI_ALIAS_FLAG);

        void penEdit(java.util.function.Consumer<DrawDoc.VShape> f) {
            if (pv == null) { Toast.makeText(DualDrawActivity.this, "Primero dibuja o toca una forma", Toast.LENGTH_SHORT).show(); return; }
            f.accept(pv);
            penSave();
            refreshTools();
        }

        /** Guarda la forma en el dibujo (en los dos teléfonos). Cada cambio se puede deshacer. */
        void penSave() {
            if (pv == null || pv.nodes() < 2) { invalidate(); return; }
            if (pvL < 0 || pvL >= doc.layers.size()) pvL = doc.cur;
            op(opj("vec", "a", "set", "l", pvL, "i", pvI, "s", pv.toJson()));
            if (pvI < 0) pvI = doc.layers.get(pvL).shapes.size() - 1;
            invalidate();
        }

        /** Suelta la forma (ya quedó guardada). */
        void penFinish() {
            if (pv == null) return;
            pv = null; pvI = -1; pvSel = -1; pDrag = 0; awaitNode = -1;
            invalidate();
            refreshTools();
        }

        void penDrop() { if (pv != null) penFinish(); }

        void penDelNode() {
            if (pv == null || pvSel < 0 || pvSel >= pv.nodes()) { Toast.makeText(DualDrawActivity.this, "Toca un punto para elegirlo", Toast.LENGTH_SHORT).show(); return; }
            if (pv.nodes() <= 2) { penDelShape(); return; }
            float[] n = new float[pv.pts.length - 6];
            System.arraycopy(pv.pts, 0, n, 0, pvSel * 6);
            System.arraycopy(pv.pts, pvSel * 6 + 6, n, pvSel * 6, n.length - pvSel * 6);
            pv.pts = n;
            pvSel = -1;
            penSave();
        }

        void penDelShape() {
            if (pv == null) return;
            if (pvI >= 0) op(opj("vec", "a", "del", "l", pvL, "i", pvI));
            pvI = -1;
            penFinish();
        }

        private int nodeAt(float x, float y, float r) {
            if (pv == null) return -1;
            int best = -1;
            float bd = r;
            for (int i = 0; i < pv.nodes(); i++) {
                float d = (float) Math.hypot(pv.pts[i * 6] - x, pv.pts[i * 6 + 1] - y);
                if (d < bd) { bd = d; best = i; }
            }
            return best;
        }

        /** Manilla tocada: {punto, 0 = entrada / 1 = salida}. */
        private int[] handleAt(float x, float y, float r) {
            if (pv == null) return null;
            for (int i = 0; i < pv.nodes(); i++) {
                int k = i * 6;
                for (int h = 0; h < 2; h++) {
                    float hx = pv.pts[k + 2 + h * 2], hy = pv.pts[k + 3 + h * 2];
                    if (Math.hypot(hx - pv.pts[k], hy - pv.pts[k + 1]) < 1) continue;   // sin manilla
                    if (Math.hypot(hx - x, hy - y) < r) return new int[]{i, h};
                }
            }
            return null;
        }

        /** Forma de la capa actual bajo el dedo (la de más arriba). */
        private int shapeAt(float x, float y, float r) {
            List<DrawDoc.VShape> sh = doc.layers.get(doc.cur).shapes;
            Paint t = new Paint();
            for (int i = sh.size() - 1; i >= 0; i--) {
                DrawDoc.VShape v = sh.get(i);
                if (v.isText()) continue;   // los textos se editan con la herramienta Texto
                android.graphics.Path p = v.path();
                android.graphics.Path hit = new android.graphics.Path();
                if (v.closed) hit.addPath(p);
                t.setStyle(Paint.Style.STROKE);
                t.setStrokeWidth(Math.max(v.width, r * 2));
                android.graphics.Path st = new android.graphics.Path();
                t.getFillPath(p, st);
                hit.addPath(st);
                RectF b = new RectF();
                hit.computeBounds(b, true);
                if (!b.contains(x, y)) continue;
                android.graphics.Region rg = new android.graphics.Region();
                rg.setPath(hit, new android.graphics.Region((int) b.left - 1, (int) b.top - 1, (int) b.right + 1, (int) b.bottom + 1));
                if (rg.contains((int) x, (int) y)) return i;
            }
            return -1;
        }

        private boolean penTouch(MotionEvent e) {
            int act = e.getActionMasked();
            if (act == MotionEvent.ACTION_POINTER_DOWN) {   // dos dedos: zoom de siempre; lo que se empezaba se deshace
                if (pDrag != 0) { pv = pBak; pvI = pBakI; pvSel = pBakSel; pDrag = 0; }
                return false;
            }
            if (two) return false;
            float x = toX(e.getX()), y = toY(e.getY()), hr = 16 * dp / zoom;
            switch (act) {
                case MotionEvent.ACTION_DOWN: {
                    two = false;
                    pMoved = false;
                    pdx = e.getX(); pdy = e.getY();
                    pBak = pv == null ? null : pv.copy();
                    pBakI = pvI; pBakSel = pvSel;
                    pDrag = 0;
                    if (pv != null && pvL != doc.cur) penFinish();
                    if (pv != null) {
                        int n = pv.nodes(), a = nodeAt(x, y, hr);
                        if (a == 0 && !pv.closed && n >= 2) { pv.closed = true; pDrag = 5; pNode = 0; pvSel = 0; invalidate(); return true; }
                        if (a >= 0) { pDrag = 2; pNode = a; pvSel = a; invalidate(); return true; }
                        int[] hd = handleAt(x, y, hr);
                        if (hd != null) {
                            pDrag = hd[1] == 0 ? 3 : 4; pNode = hd[0]; pvSel = hd[0];
                            int k = pNode * 6;
                            float ix = pv.pts[k + 2] - pv.pts[k], iy = pv.pts[k + 3] - pv.pts[k + 1], qx = pv.pts[k + 4] - pv.pts[k], qy = pv.pts[k + 5] - pv.pts[k + 1];
                            float li = (float) Math.hypot(ix, iy), lo = (float) Math.hypot(qx, qy);
                            pSmooth = li > 1 && lo > 1 && (ix * qx + iy * qy) / (li * lo) < -0.97f;   // punto curvo: las manillas siguen alineadas
                            return true;
                        }
                        if (!pv.closed) {   // forma abierta: cada toque agrega un punto
                            pv.addNode(x, y);
                            pNode = pv.nodes() - 1; pvSel = pNode; pDrag = 1;
                            invalidate();
                            return true;
                        }
                    }
                    int si = shapeAt(x, y, hr);
                    if (si >= 0) {   // se toca una forma de la capa: queda elegida para editarla
                        if (pv != null) penFinish();
                        pv = doc.layers.get(doc.cur).shapes.get(si).copy();
                        pvL = doc.cur; pvI = si; pvSel = -1;
                        refreshTools();
                        invalidate();
                        return true;
                    }
                    // forma nueva, con el color y el tamaño actuales
                    if (pv != null) penFinish();
                    pv = new DrawDoc.VShape();
                    pv.stroke = color; pv.fill = color; pv.width = size; pv.strokeOn = true; pv.fillOn = false;
                    pv.addNode(x, y);
                    pvL = doc.cur; pvI = -1; pvSel = 0; pNode = 0; pDrag = 1;
                    pBak = null; pBakI = -1; pBakSel = -1;
                    refreshTools();
                    invalidate();
                    return true;
                }
                case MotionEvent.ACTION_MOVE: {
                    if (pDrag == 0 || pv == null) return true;
                    if (!pMoved && Math.hypot(e.getX() - pdx, e.getY() - pdy) < 6 * dp) return true;
                    pMoved = true;
                    float[] P = pv.pts;
                    int k = pNode * 6;
                    float ax = P[k], ay = P[k + 1];
                    if (pDrag == 1 || pDrag == 5) {   // curva: la manilla de salida va con el dedo y la de entrada, al revés
                        P[k + 4] = x; P[k + 5] = y; P[k + 2] = 2 * ax - x; P[k + 3] = 2 * ay - y;
                    } else if (pDrag == 2) {   // mover el punto con sus manillas
                        float dx = x - ax, dy = y - ay;
                        for (int j = 0; j < 6; j += 2) { P[k + j] += dx; P[k + j + 1] += dy; }
                    } else {
                        int me = pDrag == 3 ? 2 : 4, other = pDrag == 3 ? 4 : 2;
                        P[k + me] = x; P[k + me + 1] = y;
                        if (pSmooth) {
                            float ol = (float) Math.hypot(P[k + other] - ax, P[k + other + 1] - ay), ml = Math.max(0.01f, (float) Math.hypot(x - ax, y - ay));
                            P[k + other] = ax - (x - ax) / ml * ol; P[k + other + 1] = ay - (y - ay) / ml * ol;
                        }
                    }
                    invalidate();
                    return true;
                }
                case MotionEvent.ACTION_UP: {
                    int d = pDrag;
                    pDrag = 0;
                    if (d == 0 || pv == null) return true;
                    if (d == 2 && !pMoved) {
                        // tocar el último punto de una forma abierta la termina; otro punto, solo lo elige
                        if (!pv.closed && pNode == pv.nodes() - 1 && pv.nodes() >= 2) penFinish();
                        else invalidate();
                        return true;
                    }
                    if ((d == 3 || d == 4) && !pMoved) { invalidate(); return true; }
                    penSave();
                    refreshTools();
                    return true;
                }
                case MotionEvent.ACTION_CANCEL:
                    if (pDrag != 0) { pv = pBak; pvI = pBakI; pvSel = pBakSel; pDrag = 0; }
                    invalidate();
                    return true;
            }
            return true;
        }

        // ---- texto: tocar un texto lo edita; arrastrarlo lo mueve; tocar en otro lado crea uno nuevo (en su propia capa)
        private boolean palm;
        private DrawDoc.VShape tDrag;
        private int tL = -1, tI = -1;
        private float tdx, tdy, tsx, tsy;
        private boolean tMoved;
        private final Paint tBox = new Paint(Paint.ANTI_ALIAS_FLAG), tP = new Paint(Paint.ANTI_ALIAS_FLAG);

        /** {capa, índice} del texto bajo el punto: primero la capa actual, después las demás (de arriba hacia abajo). */
        private int[] textAt(float x, float y) {
            float pad = 12 * dp / zoom;
            for (int pass = 0; pass < 2; pass++)
                for (int li = doc.layers.size() - 1; li >= 0; li--) {
                    if ((pass == 0) != (li == doc.cur) || !doc.layers.get(li).visible) continue;
                    List<DrawDoc.VShape> sh = doc.layers.get(li).shapes;
                    for (int k = sh.size() - 1; k >= 0; k--) {
                        if (!sh.get(k).isText()) continue;
                        RectF b = sh.get(k).textBounds(tP);
                        b.inset(-pad, -pad);
                        if (b.contains(x, y)) return new int[]{li, k};
                    }
                }
            return null;
        }

        private boolean textTouch(MotionEvent e) {
            int act = e.getActionMasked();
            if (act == MotionEvent.ACTION_POINTER_DOWN) { tDrag = null; invalidate(); return false; }
            if (two) return false;
            float x = toX(e.getX()), y = toY(e.getY());
            switch (act) {
                case MotionEvent.ACTION_DOWN: {
                    two = false;
                    tMoved = false;
                    tsx = e.getX(); tsy = e.getY();
                    int[] hit = textAt(x, y);
                    tDrag = null;
                    if (hit != null) {
                        tL = hit[0]; tI = hit[1];
                        tDrag = doc.layers.get(tL).shapes.get(tI).copy();
                        tdx = x - tDrag.pts[0]; tdy = y - tDrag.pts[1];
                    }
                    return true;
                }
                case MotionEvent.ACTION_MOVE:
                    if (tDrag == null) return true;
                    if (!tMoved && Math.hypot(e.getX() - tsx, e.getY() - tsy) > 8 * dp) tMoved = true;
                    if (tMoved) {
                        float nx = x - tdx, ny = y - tdy;
                        for (int k = 0; k < tDrag.pts.length; k += 2) { tDrag.pts[k] = nx; tDrag.pts[k + 1] = ny; }
                        invalidate();
                    }
                    return true;
                case MotionEvent.ACTION_UP: {
                    DrawDoc.VShape d = tDrag;
                    tDrag = null;
                    if (d != null && tMoved) op(opj("vec", "a", "set", "l", tL, "i", tI, "s", d.toJson()));   // movido
                    else if (d != null) {   // tocado: se edita (y su capa queda elegida)
                        if (tL != doc.cur) op(opj("layer", "a", "sel", "i", tL));
                        textDialog(tL, tI, doc.layers.get(tL).shapes.get(tI).copy(), x, y);
                    } else textDialog(-1, -1, null, x, y);   // en otro lado: texto nuevo
                    invalidate();
                    return true;
                }
                case MotionEvent.ACTION_CANCEL: tDrag = null; invalidate(); return true;
            }
            return true;
        }

        /** Con la herramienta Texto: un marco punteado alrededor de cada texto (y el que se arrastra). */
        private void drawTextBoxes(Canvas c) {
            tBox.setStyle(Paint.Style.STROKE);
            tBox.setStrokeWidth(1.3f * dp);
            tBox.setColor(0xCC33A0FF);
            tBox.setPathEffect(new android.graphics.DashPathEffect(new float[]{5 * dp, 4 * dp}, 0));
            for (int li = 0; li < doc.layers.size(); li++) {
                if (!doc.layers.get(li).visible) continue;
                for (DrawDoc.VShape v : doc.layers.get(li).shapes) {
                    if (!v.isText()) continue;
                    RectF b = v.textBounds(tP);
                    c.drawRect(dst.left + b.left * zoom - 4 * dp, dst.top + b.top * zoom - 4 * dp, dst.left + b.right * zoom + 4 * dp, dst.top + b.bottom * zoom + 4 * dp, tBox);
                }
            }
            if (tDrag != null) {
                c.save();
                c.translate(dst.left, dst.top);
                c.scale(zoom, zoom);
                tDrag.draw(c, tP);
                c.restore();
            }
        }

        /** Forma cerrada de la capa actual que tiene adentro el punto (la de más arriba), o -1. */
        private int shapeInside(float x, float y) {
            List<DrawDoc.VShape> sh = doc.layers.get(doc.cur).shapes;
            for (int i = sh.size() - 1; i >= 0; i--) {
                DrawDoc.VShape v = sh.get(i);
                if (!v.closed) continue;
                android.graphics.Path p = v.path();
                RectF b = new RectF();
                p.computeBounds(b, true);
                if (!b.contains(x, y)) continue;
                android.graphics.Region rg = new android.graphics.Region();
                rg.setPath(p, new android.graphics.Region((int) b.left - 1, (int) b.top - 1, (int) b.right + 1, (int) b.bottom + 1));
                if (rg.contains((int) x, (int) y)) return i;
            }
            return -1;
        }

        /** Punto elegido: curvo ↔ recto (las manillas salen según los puntos vecinos). */
        void penSmooth() {
            if (pv == null || pvSel < 0 || pvSel >= pv.nodes()) { Toast.makeText(DualDrawActivity.this, "Toca un punto para elegirlo", Toast.LENGTH_SHORT).show(); return; }
            float[] P = pv.pts;
            int n = pv.nodes(), k = pvSel * 6;
            boolean curved = Math.hypot(P[k + 4] - P[k], P[k + 5] - P[k + 1]) > 1 || Math.hypot(P[k + 2] - P[k], P[k + 3] - P[k + 1]) > 1;
            if (curved) { P[k + 2] = P[k + 4] = P[k]; P[k + 3] = P[k + 5] = P[k + 1]; }
            else {
                int pi = pvSel > 0 ? pvSel - 1 : pv.closed ? n - 1 : pvSel, ni = pvSel < n - 1 ? pvSel + 1 : pv.closed ? 0 : pvSel;
                float tx = (P[ni * 6] - P[pi * 6]) / 4f, ty = (P[ni * 6 + 1] - P[pi * 6 + 1]) / 4f;
                if (Math.hypot(tx, ty) < 1) { tx = 30; ty = 0; }
                P[k + 2] = P[k] - tx; P[k + 3] = P[k + 1] - ty; P[k + 4] = P[k] + tx; P[k + 5] = P[k + 1] + ty;
            }
            penSave();
        }

        // ---- vector distanciado: el punto aparece unos 30 mm sobre el dedo; al soltar queda ahí
        private boolean offShow;
        private float offSX, offSY, offFX, offFY;
        private final Paint offP = new Paint(Paint.ANTI_ALIAS_FLAG);

        private float offPx() { return 10f / 25.4f * getResources().getDisplayMetrics().ydpi; }   // ~1 cm sobre el dedo

        /** Vector distanciado: punto recién puesto que espera el siguiente toque (rápido = recto, deslizar = curvo). */
        int awaitNode = -1;

        private boolean penTouchOff(MotionEvent e) {
            int act = e.getActionMasked();
            if (act == MotionEvent.ACTION_POINTER_DOWN) {
                if (pDrag != 0) { pv = pBak; pvI = pBakI; pvSel = pBakSel; pDrag = 0; }
                offShow = false;
                return false;
            }
            if (two) return false;
            float fx = e.getX(), fy = e.getY(), sx = fx, sy = fy - offPx();
            float cx = toX(sx), cy = toY(sy), x = toX(fx), y = toY(fy), hr = 16 * dp / zoom;
            switch (act) {
                case MotionEvent.ACTION_DOWN: {
                    two = false;
                    pMoved = false;
                    pdx = fx; pdy = fy;
                    pBak = pv == null ? null : pv.copy();
                    pBakI = pvI; pBakSel = pvSel;
                    pDrag = 0;
                    if (pv != null && pvL != doc.cur) penFinish();
                    if (pv != null && awaitNode >= 0 && awaitNode < pv.nodes()) {   // segundo toque: define si el punto es recto o curvo
                        pDrag = 20; pNode = awaitNode;
                        invalidate();
                        return true;
                    }
                    if (pv != null) {   // el dedo SOBRE un punto (o manilla): espera a que muevas el dedo para reubicarlo
                        int a = nodeAt(x, y, hr);
                        if (a >= 0) { pDrag = 12; pNode = a; pvSel = a; }
                        else {
                            int[] hd = handleAt(x, y, hr);
                            if (hd != null) {
                                pDrag = hd[1] == 0 ? 13 : 14; pNode = hd[0]; pvSel = hd[0];
                                int k = pNode * 6;
                                float ix = pv.pts[k + 2] - pv.pts[k], iy = pv.pts[k + 3] - pv.pts[k + 1], qx = pv.pts[k + 4] - pv.pts[k], qy = pv.pts[k + 5] - pv.pts[k + 1];
                                float li = (float) Math.hypot(ix, iy), lo = (float) Math.hypot(qx, qy);
                                pSmooth = li > 1 && lo > 1 && (ix * qx + iy * qy) / (li * lo) < -0.97f;
                            }
                        }
                    }
                    if (pDrag == 0) pDrag = 11;   // poner un punto donde quede el circulito al soltar
                    offShow = true; offSX = sx; offSY = sy; offFX = fx; offFY = fy;
                    invalidate();
                    return true;
                }
                case MotionEvent.ACTION_MOVE: {
                    if (pDrag == 0) return true;
                    if (!pMoved && Math.hypot(fx - pdx, fy - pdy) > 6 * dp) pMoved = true;
                    if (pDrag == 20) {   // deslizar: el punto se vuelve curvo y las manillas siguen al dedo
                        if (pMoved && pv != null) {
                            float[] P = pv.pts;
                            int k = pNode * 6;
                            float dx = (fx - pdx) / zoom * (flip ? -1 : 1), dy = (fy - pdy) / zoom;
                            P[k + 4] = P[k] + dx; P[k + 5] = P[k + 1] + dy; P[k + 2] = P[k] - dx; P[k + 3] = P[k + 1] - dy;
                        }
                        invalidate();
                        return true;
                    }
                    offSX = sx; offSY = sy; offFX = fx; offFY = fy;
                    if (pMoved && pv != null && pDrag >= 12) {
                        float[] P = pv.pts;
                        int k = pNode * 6;
                        if (pDrag == 12) {
                            float dx = cx - P[k], dy = cy - P[k + 1];
                            for (int j = 0; j < 6; j += 2) { P[k + j] += dx; P[k + j + 1] += dy; }
                        } else {
                            int me = pDrag == 13 ? 2 : 4, other = pDrag == 13 ? 4 : 2;
                            P[k + me] = cx; P[k + me + 1] = cy;
                            if (pSmooth) {
                                float ol = (float) Math.hypot(P[k + other] - P[k], P[k + other + 1] - P[k + 1]), ml = Math.max(0.01f, (float) Math.hypot(cx - P[k], cy - P[k + 1]));
                                P[k + other] = P[k] - (cx - P[k]) / ml * ol; P[k + other + 1] = P[k + 1] - (cy - P[k + 1]) / ml * ol;
                            }
                        }
                    }
                    invalidate();
                    return true;
                }
                case MotionEvent.ACTION_UP: {
                    offShow = false;
                    int d = pDrag;
                    pDrag = 0;
                    if (d == 20) {   // toque rápido: recto; deslizado: curvo (ya quedó). Luego, el siguiente punto
                        if (!pMoved && pv != null) { int k = pNode * 6; pv.pts[k + 2] = pv.pts[k + 4] = pv.pts[k]; pv.pts[k + 3] = pv.pts[k + 5] = pv.pts[k + 1]; }
                        awaitNode = -1;
                        penSave();
                        refreshTools();
                        invalidate();
                        return true;
                    }
                    if (d == 11) placeAt(cx, cy, hr);
                    else if (d >= 12 && pv != null) {
                        if (pMoved) penSave();
                        else if (d == 12 && !pv.closed && pNode == 0 && pv.nodes() >= 2) { pv.closed = true; penSave(); }   // tocar el 1er punto: cerrar
                        else if (d == 12 && !pv.closed && pNode == pv.nodes() - 1 && pv.nodes() >= 2) penFinish();   // el último: terminar
                    }
                    refreshTools();
                    invalidate();
                    return true;
                }
                case MotionEvent.ACTION_CANCEL:
                    if (pDrag != 0) { pv = pBak; pvI = pBakI; pvSel = pBakSel; pDrag = 0; }
                    offShow = false;
                    invalidate();
                    return true;
            }
            return true;
        }

        /** Vector distanciado: el punto nuevo va donde quedó el circulito. */
        private void placeAt(float x, float y, float hr) {
            if (pv != null && !pv.closed) {
                int a = nodeAt(x, y, hr);
                if (a == 0 && pv.nodes() >= 2) { pv.closed = true; pvSel = 0; awaitNode = 0; penSave(); return; }
                if (a >= 0 && a == pv.nodes() - 1 && pv.nodes() >= 2) { penFinish(); return; }
                pv.addNode(x, y);
                pvSel = pv.nodes() - 1;
                awaitNode = pvSel;
                penSave();
                return;
            }
            int si = shapeAt(x, y, hr);
            if (si >= 0) {
                if (pv != null) penFinish();
                pv = doc.layers.get(doc.cur).shapes.get(si).copy();
                pvL = doc.cur; pvI = si; pvSel = -1;
                return;
            }
            if (pv != null) penFinish();
            pv = new DrawDoc.VShape();
            pv.stroke = color; pv.fill = color; pv.width = size; pv.strokeOn = true; pv.fillOn = false;
            pv.addNode(x, y);
            pvL = doc.cur; pvI = -1; pvSel = 0;
            awaitNode = 0;
        }

        private void drawOffCursor(Canvas c) {
            offP.setStyle(Paint.Style.STROKE);
            offP.setStrokeWidth(1.2f * dp);
            offP.setColor(0x9933A0FF);
            offP.setPathEffect(new android.graphics.DashPathEffect(new float[]{4 * dp, 4 * dp}, 0));
            c.drawLine(offFX, offFY, offSX, offSY + 9 * dp, offP);
            offP.setPathEffect(null);
            offP.setStrokeWidth(2.5f * dp);
            offP.setColor(0xCC000000);
            c.drawCircle(offSX, offSY, 9 * dp, offP);
            offP.setStrokeWidth(1.4f * dp);
            offP.setColor(0xFF33A0FF);
            c.drawCircle(offSX, offSY, 9 * dp, offP);
            c.drawLine(offSX - 14 * dp, offSY, offSX - 4 * dp, offSY, offP);
            c.drawLine(offSX + 4 * dp, offSY, offSX + 14 * dp, offSY, offP);
            c.drawLine(offSX, offSY - 14 * dp, offSX, offSY - 4 * dp, offP);
            c.drawLine(offSX, offSY + 4 * dp, offSX, offSY + 14 * dp, offP);
            offP.setStyle(Paint.Style.FILL);
            c.drawCircle(offSX, offSY, 1.8f * dp, offP);
        }

        /** La forma en edición, con sus puntos (cuadrados) y manillas (círculos). */
        private void drawPen(Canvas c) {
            c.save();
            c.translate(dst.left, dst.top);
            c.scale(zoom, zoom);
            pv.draw(c, penP);
            c.restore();
            android.graphics.Path g = pv.path();
            android.graphics.Matrix m = new android.graphics.Matrix();
            m.setScale(zoom, zoom);
            m.postTranslate(dst.left, dst.top);
            g.transform(m);
            penUi.setStyle(Paint.Style.STROKE);
            penUi.setStrokeWidth(1.3f * dp);
            penUi.setColor(0xFF33A0FF);
            c.drawPath(g, penUi);
            float[] P = pv.pts;
            float hs = 4.5f * dp;
            for (int i = 0; i < pv.nodes(); i++) {
                int k = i * 6;
                float ax = dst.left + P[k] * zoom, ay = dst.top + P[k + 1] * zoom;
                for (int h = 0; h < 2; h++) {
                    float hx = dst.left + P[k + 2 + h * 2] * zoom, hy = dst.top + P[k + 3 + h * 2] * zoom;
                    if (Math.hypot(hx - ax, hy - ay) < 1.5f * dp) continue;
                    penUi.setStyle(Paint.Style.STROKE);
                    penUi.setStrokeWidth(1f * dp);
                    penUi.setColor(0xFF33A0FF);
                    c.drawLine(ax, ay, hx, hy, penUi);
                    penUi.setStyle(Paint.Style.FILL);
                    penUi.setColor(0xFFFFFFFF);
                    c.drawCircle(hx, hy, 3.5f * dp, penUi);
                    penUi.setStyle(Paint.Style.STROKE);
                    penUi.setColor(0xFF33A0FF);
                    c.drawCircle(hx, hy, 3.5f * dp, penUi);
                }
                if (i == awaitNode && penOff) {   // espera el toque: rápido = recto, deslizar = curvo
                    penUi.setStyle(Paint.Style.STROKE);
                    penUi.setStrokeWidth(2.5f * dp);
                    penUi.setColor(ORANGE);
                    c.drawCircle(ax, ay, 13 * dp, penUi);
                }
                float q = (i == 0 && !pv.closed && pv.nodes() >= 2) ? hs * 1.5f : hs;   // el primero, más grande: ahí se cierra
                penUi.setStyle(Paint.Style.FILL);
                penUi.setColor(i == pvSel ? 0xFF33A0FF : 0xFFFFFFFF);
                c.drawRect(ax - q, ay - q, ax + q, ay + q, penUi);
                penUi.setStyle(Paint.Style.STROKE);
                penUi.setStrokeWidth(1.3f * dp);
                penUi.setColor(0xFF33A0FF);
                c.drawRect(ax - q, ay - q, ax + q, ay + q, penUi);
            }
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (!edit) return true;
            if (tool == T_XFORM) return xformTouch(e);
            if (tool == T_PEN && (penOff ? penTouchOff(e) : penTouch(e))) return true;
            if (tool == T_TEXT && textTouch(e)) return true;
            // la palma o el canto de la mano (toque muy ancho) no dibuja: suele ser el origen de toques "fantasma"
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN && e.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER
                    && e.getTouchMajor() > 20f / 25.4f * getResources().getDisplayMetrics().xdpi) { palm = true; return true; }
            if (palm) { if (e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL) palm = false; return true; }
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
                    } else if (tool == T_LASSO) {
                        lasso.clear(); lasso.add(x); lasso.add(y);
                    }
                    changed();
                    return true;
                }
                case MotionEvent.ACTION_POINTER_DOWN:
                    if (e.getPointerCount() == 2) {
                        cancelStroke();
                        shaping = false;
                        lasso.clear();
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
                    if (tool == T_LASSO && !lasso.isEmpty()) {
                        float lx = toX(e.getX()), ly = toY(e.getY());
                        if (Math.hypot(lx - lasso.get(lasso.size() - 2), ly - lasso.get(lasso.size() - 1)) * zoom > 3 * dp) { lasso.add(lx); lasso.add(ly); invalidate(); }
                        return true;
                    }
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
                        // dentro de una forma de la pluma (cerrada): se cambia el color de su relleno (siempre se puede)
                        int vi = shapeInside(x, y);
                        if (vi >= 0) {
                            DrawDoc.VShape vs = doc.layers.get(doc.cur).shapes.get(vi).copy();
                            vs.fill = color; vs.fillOn = true;
                            op(opj("vec", "a", "set", "l", doc.cur, "i", vi, "s", vs.toJson()));
                            return true;
                        }
                        op(opj("fill", "x", (int) x, "y", (int) y, "c", color, "l", doc.cur, "tol", fillTol, "gap", fillGap, "all", fillAll));
                        return true;
                    }
                    if (tool == T_PICK) { set("color", doc.colorAt((int) x, (int) y)); return true; }
                    if (tool == T_WAND) { op(opj("sel", "k", "wand", "x", (int) x, "y", (int) y, "tol", selTol, "l", doc.cur)); return true; }
                    if (tool == T_LASSO) {
                        if (lasso.size() >= 6) {
                            JSONArray a = new JSONArray();
                            try { for (Float f : lasso) a.put(Math.round(f * 10) / 10.0); } catch (Exception ignored) { }
                            op(opj("sel", "k", "lasso", "p", a));
                        }
                        lasso.clear();
                        invalidate();
                        return true;
                    }
                    if (!drawing) return true;
                    drawing = false;
                    flush();
                    doc.end();
                    if (online()) dwSend(opj("end"));
                    if (++strokes % 8 == 0) save();
                    changed();
                    return true;
                }
                case MotionEvent.ACTION_CANCEL:
                    cancelStroke();
                    shaping = false;
                    lasso.clear();
                    two = false;
                    changed();
                    return true;
            }
            return true;
        }
    }
}
