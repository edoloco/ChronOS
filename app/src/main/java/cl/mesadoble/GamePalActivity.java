package cl.mesadoble;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * Game Pal (EXTENDIDO): control en pantalla para un juego del principal. Por ahora, Brawl Stars.
 * De fondo, la imagen del juego en Google Play desenfocada. Encima: joystick de movimiento (izquierda, aparece donde
 * se apoya el dedo), joysticks de ataque y súper (tocar = disparo automático, arrastrar = apuntar y soltar = disparar),
 * y botones de gadget, hipercarga y emote. Cada control toca el principal en el lugar del botón real del juego;
 * con "Ajustar" se ubican sobre una captura de la pantalla de arriba.
 */
public class GamePalActivity extends Activity implements Link.Listener {

    static volatile boolean open;
    static final String BRAWL = "com.supercell.brawlstars";
    static final int ORANGE = 0xFFF57C1F;

    /** Controles: id, nombre, x, y por defecto (0..1 de la pantalla del principal), joystick? */
    static final String[] IDS = {"move", "attack", "super", "gadget", "hyper", "emote"};
    static final String[] NAMES = {"Mover", "Atacar", "Súper", "Gadget", "Hipercarga", "Emote"};
    static final double[][] DEF = {{0.12, 0.74}, {0.885, 0.74}, {0.775, 0.85}, {0.775, 0.63}, {0.885, 0.52}, {0.95, 0.12}};

    private final Handler h = new Handler(Looper.getMainLooper());
    private Hub hub;
    private SharedPreferences prefs;
    private String pkg;
    private Pad pad;
    private Calib calib;
    private Bitmap bg;
    private TextView cutsView, connView;
    private FrameLayout root;
    private Checker checker;

    private float dp;
    private int dp(float v) { return Math.round(v * dp); }

    static Intent intent(android.content.Context c, String pkg) {
        return new Intent(c, GamePalActivity.class).putExtra("pkg", pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }

    static boolean enabled(android.content.Context c) { return c.getSharedPreferences("mesa", MODE_PRIVATE).getBoolean("gamePal", true); }
    static boolean supported(String pkg) { return BRAWL.equals(pkg); }

    double[] pos(int i) {
        String v = prefs.getString("gp_" + pkg + "_" + IDS[i], null);
        if (v != null) try { String[] a = v.split(","); return new double[]{Double.parseDouble(a[0]), Double.parseDouble(a[1])}; } catch (Exception ignored) { }
        return DEF[i].clone();
    }

    /** Alcance de los joysticks en el principal (fracción del ancho). */
    float reach() { return prefs.getFloat("gp_" + pkg + "_reach", 0.085f); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        hub = Hub.get(this);
        prefs = getSharedPreferences("mesa", MODE_PRIVATE);
        dp = getResources().getDisplayMetrics().density;
        pkg = getIntent().getStringExtra("pkg");
        if (pkg == null) pkg = BRAWL;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0B0F14);
        pad = new Pad();
        root.addView(pad, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = new TextView(this);
        t.setText("Game Pal · Brawl Stars");
        t.setTypeface(Intro.font(this));
        t.setTextSize(15);
        t.setTextColor(Color.WHITE);
        t.setShadowLayer(3 * dp, 0, dp, 0xCC000000);
        t.setPadding(dp(12), 0, dp(10), 0);
        top.addView(t);
        cutsView = new TextView(this);
        cutsView.setTextSize(11);
        cutsView.setTextColor(0xFFFFC107);
        cutsView.setPadding(0, 0, dp(8), 0);
        top.addView(cutsView);
        connView = pill("", v -> openChecker());
        top.addView(connView);
        showConn();
        top.addView(pill("Revisar", v -> openChecker()));
        top.addView(pill("Ajustar", v -> startCalib()));
        top.addView(pill("✕", v -> finish()));
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(-2, dp(40), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        tl.topMargin = dp(6);
        root.addView(top, tl);
        setContentView(root);
        immersive();
        loadBg();
        // sin conexión al abrir: directo al revisor, que explica qué falta
        h.postDelayed(() -> { if (!hub.connected() && checker == null && !isFinishing()) openChecker(); }, 2500);
        if (!prefs.getBoolean("gpHelp", false)) {
            prefs.edit().putBoolean("gpHelp", true).apply();
            android.widget.Toast.makeText(this, "Si los botones no calzan con los de Brawl Stars, toca \"Ajustar\"", android.widget.Toast.LENGTH_LONG).show();
        }
    }

    private TextView pill(String s, View.OnClickListener l) {
        TextView b = new TextView(this);
        b.setText(s);
        b.setTextSize(13);
        b.setTextColor(Color.WHITE);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(14), 0, dp(14), 0);
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(0x40FFFFFF);
        g.setStroke(dp(1), 0x66FFFFFF);
        g.setCornerRadius(dp(16));
        b.setBackground(g);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(32));
        lp.setMargins(dp(4), 0, dp(4), 0);
        b.setLayoutParams(lp);
        return b;
    }

    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override public void onWindowFocusChanged(boolean f) { super.onWindowFocusChanged(f); if (f) immersive(); }

    /** Fondo: la imagen de Google Play del juego (la misma del fondo de juegos), chica y desenfocada. */
    private void loadBg() {
        Bitmap src = pkg.equals(GameBg.currentPkg) ? GameBg.current : null;
        if (src == null) return;
        RecentApps.worker.execute(() -> {
            try {
                float k = 160f / Math.max(src.getWidth(), src.getHeight());
                Bitmap s = Bitmap.createScaledBitmap(src, Math.max(1, (int) (src.getWidth() * k)), Math.max(1, (int) (src.getHeight() * k)), true)
                        .copy(Bitmap.Config.ARGB_8888, true);
                Glass.boxBlur(s, 3);
                h.post(() -> { bg = s; pad.invalidate(); });
            } catch (Exception ignored) { }
        });
    }

    @Override protected void onResume() {
        super.onResume();
        open = true;
        hub.add(this);
        showConn();
        if (checker != null) checker.refresh();
    }

    @Override protected void onPause() {
        open = false;
        pad.releaseAll();
        hub.remove(this);
        super.onPause();
    }

    @Override public void onMessage(JSONObject m) {
        String t = m.optString("t");
        if ("gameBg".equals(t)) h.post(() -> { if (m.optString("pkg").isEmpty()) finish(); else loadBg(); });
        else if ("pcRes".equals(t)) h.post(() -> { JSONObject st = m.optJSONObject("s"); if (checker != null && st != null) checker.got(st); });
        else if ("gpCut".equals(t)) h.post(() -> cutsView.setText("⚠ " + m.optInt("n") + " cortes"));
        else if ("gpImg".equals(t)) h.post(() -> {
            if (calib == null) return;
            if (m.has("err")) { android.widget.Toast.makeText(this, m.optString("err"), android.widget.Toast.LENGTH_LONG).show(); return; }
            byte[] d = android.util.Base64.decode(m.optString("img"), android.util.Base64.DEFAULT);
            calib.img = BitmapFactory.decodeByteArray(d, 0, d.length);
            calib.invalidate();
        });
    }

    @Override public void onState(int state, String info) {
        h.post(() -> {
            showConn();
            if (!hub.connected()) {
                pad.releaseAll();
                h.postDelayed(() -> hub.reconnect(), 1500);   // se intenta volver solo
            }
            if (checker != null) checker.refresh();
        });
    }

    @Override public void onBackPressed() { if (checker != null) checker.close(); else super.onBackPressed(); }

    private void showConn() {
        if (connView == null) return;
        boolean on = hub.connected();
        connView.setText(on ? "● Conectado" : "● Sin conexión");
        connView.setTextColor(on ? 0xFF7CFC9A : 0xFFFF6B6B);
    }

    private void openChecker() {
        if (checker != null) return;
        checker = new Checker();
        root.addView(checker.view, new FrameLayout.LayoutParams(-1, -1));
        checker.refresh();
    }

    // ======================= revisor de permisos =======================

    /** Panel que revisa los dos teléfonos, con instructivo y botones que abren cada ajuste. */
    private final class Checker {
        final FrameLayout view = new FrameLayout(GamePalActivity.this);
        final LinearLayout colMe = new LinearLayout(GamePalActivity.this), colOther = new LinearLayout(GamePalActivity.this);
        JSONObject other;
        long askedAt, gotAt;
        final Runnable tick = new Runnable() {
            @Override public void run() { if (checker != Checker.this) return; refresh(); h.postDelayed(this, 3000); }
        };

        Checker() {
            view.setBackgroundColor(0xF00B0F14);
            view.setClickable(true);
            android.widget.ScrollView sc = new android.widget.ScrollView(GamePalActivity.this);
            LinearLayout box = new LinearLayout(GamePalActivity.this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(dp(16), dp(10), dp(16), dp(24));
            LinearLayout head = new LinearLayout(GamePalActivity.this);
            head.setGravity(Gravity.CENTER_VERTICAL);
            TextView t = new TextView(GamePalActivity.this);
            t.setText("Revisor de Game Pal");
            t.setTypeface(Intro.font(GamePalActivity.this));
            t.setTextSize(18);
            t.setTextColor(ORANGE);
            head.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
            head.addView(pill("Volver a revisar", v -> { other = null; refresh(); }));
            head.addView(pill("Reconectar", v -> { hub.reconnect(); toast("Buscando al otro teléfono…"); }));
            head.addView(pill("Cerrar", v -> close()));
            box.addView(head);
            TextView how = new TextView(GamePalActivity.this);
            how.setTextSize(13);
            how.setTextColor(0xDDFFFFFF);
            how.setLineSpacing(dp(2), 1);
            how.setPadding(dp(14), dp(12), dp(14), dp(12));
            how.setText("Instructivo, en orden:\n"
                    + "1. Los dos teléfonos en el mismo WiFi, con ChronOS abierto.\n"
                    + "2. En el PRINCIPAL (arriba): versión completa de ChronOS y su accesibilidad activada. Es la que \"toca\" Brawl Stars por ti.\n"
                    + "3. En AMBOS: batería \"Sin restricciones\" e Inicio automático (Xiaomi/Poco). Si falta, Android corta la conexión en pleno juego.\n"
                    + "4. Abre Brawl Stars en el principal: Game Pal aparece aquí. Si los botones no calzan, toca \"Ajustar\".\n"
                    + "Cada punto rojo trae un botón que abre el ajuste justo en el teléfono que corresponde.");
            how.setBackground(card(0x22F57C1F));
            LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(-1, -2);
            hl.setMargins(0, dp(10), 0, dp(10));
            box.addView(how, hl);
            LinearLayout cols = new LinearLayout(GamePalActivity.this);
            colOther.setOrientation(LinearLayout.VERTICAL);
            colMe.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, -2, 1);
            cl.setMargins(0, 0, dp(6), 0);
            cols.addView(hub.isPrincipal() ? colMe : colOther, cl);
            LinearLayout.LayoutParams cr = new LinearLayout.LayoutParams(0, -2, 1);
            cr.setMargins(dp(6), 0, 0, 0);
            cols.addView(hub.isPrincipal() ? colOther : colMe, cr);
            box.addView(cols);
            sc.addView(box);
            view.addView(sc, new FrameLayout.LayoutParams(-1, -1));
            h.postDelayed(tick, 3000);
        }

        void close() { root.removeView(view); checker = null; }

        void got(JSONObject s) { other = s; gotAt = System.currentTimeMillis(); draw(); }

        void refresh() {
            if (hub.connected() && System.currentTimeMillis() - askedAt > 1500) { askedAt = System.currentTimeMillis(); hub.send("pcAsk"); }
            if (!hub.connected()) other = null;
            draw();
        }

        void draw() {
            JSONObject me = PermCheck.status(GamePalActivity.this, hub);
            fill(colMe, me, true);
            colOther.removeAllViews();
            colOther.addView(heading(hub.isPrincipal() ? "Extendido (abajo)" : "Principal (arriba)"));
            if (other == null) {
                boolean waiting = hub.connected() && System.currentTimeMillis() - askedAt < 4000;
                colOther.addView(row(waiting ? 2 : 0, waiting ? "Preguntando…" : "Sin respuesta",
                        waiting ? "Revisando el otro teléfono." :
                        "No hay conexión con el otro teléfono, así que no se puede revisar. Mira el punto \"Conexión\" a este lado"
                                + " y, en el otro teléfono, abre ChronOS. Cuando vuelva la conexión esta columna se llena sola.", null, null, false));
            } else fill(colOther, other, false);
        }

        private void fill(LinearLayout col, JSONObject s, boolean local) {
            if (local) { col.removeAllViews(); col.addView(heading((s.optBoolean("p") ? "Principal (arriba)" : "Extendido (abajo)") + " · este")); }
            boolean principal = s.optBoolean("p");
            String model = s.optString("model");
            if (!model.isEmpty()) col.addView(small(model + " · Android " + sdkName(s.optInt("sdk")) + " · ChronOS " + s.optString("ver")));
            if (!principal && local)
                col.addView(row(hub.connected() ? 1 : 0, "Conexión con el principal",
                        hub.connected() ? "Conectados." : "Sin conexión. Revisa que ambos estén en el mismo WiFi y que ChronOS esté abierto en el principal."
                                + " Toca Reconectar. Si se corta seguido, revisa la batería y el Inicio automático en AMBOS teléfonos.",
                        "reconnect", null, true));
            if (principal) {
                col.addView(row(s.optBoolean("full") ? 1 : 0, "Versión completa",
                        s.optBoolean("full") ? "Instalada." : "Game Pal necesita la versión completa en el principal: instala MesaDoble-completa.apk"
                                + " (Ajustes de ChronOS → Actualizar). La liviana no puede tocar la pantalla.", null, null, local));
                if (s.optBoolean("full")) {
                    boolean acc = s.optBoolean("acc");
                    col.addView(row(acc ? 1 : 0, "Accesibilidad de ChronOS",
                            acc ? "Activada." : "Ajustes → Accesibilidad → Apps descargadas (o Servicios instalados) → ChronOS → Activar."
                                    + " Si sale \"ajuste restringido\": toca \"Ficha de ChronOS\" → ⋮ (arriba a la derecha) →"
                                    + " Permitir ajustes restringidos, y vuelve a activarla.", "acc", acc ? null : "app", local));
                    if (acc) col.addView(row(s.optBoolean("alive") ? 1 : 0, "Accesibilidad funcionando",
                            s.optBoolean("alive") ? "Lista para tocar la pantalla." : "Figura activada pero Android la detuvo. Desactívala y vuelve a"
                                    + " activarla; en Xiaomi/Poco activa además el Inicio automático.", "acc", null, local));
                }
                boolean shot = s.optInt("sdk") >= 30;
                col.addView(row(shot ? 1 : 2, "Captura para \"Ajustar\"",
                        shot ? "Disponible." : "Este teléfono tiene Android " + sdkName(s.optInt("sdk")) + ": Game Pal funciona, pero \"Ajustar\""
                                + " no puede mostrar la pantalla del juego. Ubica los botones a ojo.", null, null, local));
            }
            col.addView(row(s.optBoolean("wifi") ? 1 : 0, "WiFi",
                    s.optBoolean("wifi") ? "Conectado." : "Conéctalo al mismo WiFi que el otro teléfono (o a la zona WiFi del otro).", "wifi", null, local));
            col.addView(row(s.optBoolean("batt") ? 1 : 0, "Batería sin restricciones",
                    s.optBoolean("batt") ? "Android no frena a ChronOS." : "Android está ahorrando batería con ChronOS y puede cortar la conexión."
                            + (s.optBoolean("mi") ? " Ficha de ChronOS → Ahorro de batería → Sin restricciones."
                            : " Elige ChronOS → No optimizar / Sin restricciones."), "batt", null, local));
            if (s.optBoolean("mi")) {
                col.addView(row(2, "Inicio automático (Xiaomi/Poco)", "Android no deja comprobarlo desde aquí: ábrelo y deja ChronOS activado."
                        + " Sin esto MIUI/HyperOS cierra ChronOS y se pierde la conexión.", "auto", null, local));
                col.addView(row(2, "Candado en Recientes", "Abre las apps recientes, mantén presionada ChronOS y toca el candado."
                        + " Así no se cierra al limpiar la memoria.", null, null, local));
            }
            if (!local && other != null) {
                String a = me().optString("ver"), b = s.optString("ver");
                if (!a.equals(b)) col.addView(row(2, "Versiones distintas", "Ese teléfono: " + b + " · este: " + a
                        + ". Actualiza ambos a la misma para que se entiendan bien.", null, null, false));
            }
        }

        private JSONObject me() { return PermCheck.status(GamePalActivity.this, hub); }

        private String sdkName(int sdk) {
            switch (sdk) { case 28: return "9"; case 29: return "10"; case 30: return "11"; case 31: return "12"; case 32: return "12L";
                case 33: return "13"; case 34: return "14"; case 35: return "15"; case 36: return "16"; default: return "API " + sdk; }
        }

        private TextView heading(String s) {
            TextView t = new TextView(GamePalActivity.this);
            t.setText(s);
            t.setTextSize(15);
            t.setTextColor(Color.WHITE);
            t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            t.setPadding(dp(4), dp(4), 0, dp(2));
            return t;
        }

        private TextView small(String s) {
            TextView t = new TextView(GamePalActivity.this);
            t.setText(s);
            t.setTextSize(11);
            t.setTextColor(0x99FFFFFF);
            t.setPadding(dp(4), 0, 0, dp(6));
            return t;
        }

        /** st: 1 bien, 0 falta, 2 revisar a mano. */
        private View row(int st, String title, String desc, String fix, String fix2, boolean local) {
            LinearLayout r = new LinearLayout(GamePalActivity.this);
            r.setOrientation(LinearLayout.VERTICAL);
            r.setPadding(dp(12), dp(10), dp(12), dp(10));
            r.setBackground(card(st == 1 ? 0x1A4CAF50 : st == 0 ? 0x33E53935 : 0x26FFC107));
            TextView t = new TextView(GamePalActivity.this);
            t.setText((st == 1 ? "✓  " : st == 0 ? "✗  " : "!  ") + title);
            t.setTextSize(14);
            t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            t.setTextColor(st == 1 ? 0xFF7CFC9A : st == 0 ? 0xFFFF8A80 : 0xFFFFD54F);
            r.addView(t);
            TextView d = new TextView(GamePalActivity.this);
            d.setText(desc);
            d.setTextSize(12);
            d.setTextColor(0xCCFFFFFF);
            d.setPadding(0, dp(3), 0, 0);
            r.addView(d);
            if (st != 1 && (fix != null || fix2 != null)) {
                LinearLayout bs = new LinearLayout(GamePalActivity.this);
                bs.setPadding(0, dp(8), 0, 0);
                if (fix != null) bs.addView(pill(label(fix), v -> doFix(fix, local)));
                if (fix2 != null) bs.addView(pill(label(fix2), v -> doFix(fix2, local)));
                r.addView(bs);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.setMargins(0, dp(4), 0, dp(4));
            r.setLayoutParams(lp);
            return r;
        }

        private String label(String w) {
            switch (w) {
                case "reconnect": return "Reconectar";
                case "acc": return "Abrir Accesibilidad";
                case "app": return "Ficha de ChronOS";
                case "auto": return "Abrir Inicio automático";
                case "wifi": return "Abrir WiFi";
                case "batt": return "Abrir batería";
                default: return "Abrir ajuste";
            }
        }

        private void doFix(String w, boolean local) {
            if ("reconnect".equals(w)) { hub.reconnect(); toast("Buscando al otro teléfono…"); return; }
            if (local) {
                try { startActivity(PermCheck.fix(GamePalActivity.this, w)); }
                catch (Exception e) { toast("No se pudo abrir ese ajuste"); }
            } else if (hub.connected()) {
                hub.send("pcFix", "w", w);
                toast("Se abrió en el " + (hub.isPrincipal() ? "extendido" : "principal") + ": hazlo allá y vuelve a Brawl Stars");
            } else toast("Sin conexión con el otro teléfono");
        }
    }

    private android.graphics.drawable.GradientDrawable card(int color) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(color);
        g.setStroke(dp(1), 0x33FFFFFF);
        g.setCornerRadius(dp(14));
        return g;
    }

    private void toast(String s) { android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_LONG).show(); }

    private void send(int finger, int ph, double x, double y) {
        if (hub.connected()) hub.send("gp", "f", finger, "ph", ph, "x", Math.round(x * 10000) / 10000.0, "y", Math.round(y * 10000) / 10000.0);
    }

    // ======================= el control =======================

    private final class Pad extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG), txt = new Paint(Paint.ANTI_ALIAS_FLAG), bmp = new Paint(Paint.FILTER_BITMAP_FLAG);
        // estado por dedo de la pantalla: qué control maneja, dónde empezó
        final Map<Integer, int[]> owner = new HashMap<>();   // pointerId → {control, dedo virtual}
        final Map<Integer, float[]> start = new HashMap<>();
        final float[][] knob = new float[6][];               // posición actual del dedo en cada joystick (para dibujar)
        int nextFinger = 1;

        Pad() {
            super(GamePalActivity.this);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(2.5f * dp);
            txt.setTextAlign(Paint.Align.CENTER);
            txt.setColor(Color.WHITE);
            txt.setShadowLayer(3 * dp, 0, dp, 0xCC000000);
            txt.setTypeface(Intro.font(GamePalActivity.this));
        }

        /** Dónde va cada control en ESTA pantalla (centro y radio). */
        RectF area(int i) {
            float W = getWidth(), H = getHeight(), m = Math.min(W, H);
            switch (i) {
                case 1: return circle(W - m * 0.30f, H - m * 0.33f, m * 0.25f);     // atacar: grande, abajo a la derecha
                case 2: return circle(W - m * 0.78f, H - m * 0.22f, m * 0.17f);     // súper
                case 3: return circle(W - m * 0.80f, H - m * 0.62f, m * 0.11f);     // gadget
                case 4: return circle(W - m * 0.30f, m * 0.27f, m * 0.12f);         // hipercarga
                case 5: return circle(W - m * 0.58f, m * 0.18f, m * 0.08f);         // emote
                default: return new RectF(0, 0, W * 0.48f, H);                       // mover: toda la mitad izquierda
            }
        }

        RectF circle(float cx, float cy, float r) { return new RectF(cx - r, cy - r, cx + r, cy + r); }

        int controlAt(float x, float y) {
            for (int i = 1; i < 6; i++) {
                RectF a = area(i);
                float r = a.width() / 2 + 10 * dp;
                if (Math.hypot(x - a.centerX(), y - a.centerY()) <= r) return i;
            }
            return area(0).contains(x, y) ? 0 : -1;
        }

        boolean joystick(int i) { return i <= 2; }

        @Override protected void onDraw(Canvas c) {
            float W = getWidth(), H = getHeight();
            if (bg != null) {
                float k = Math.max(W / bg.getWidth(), H / bg.getHeight());
                float bw = bg.getWidth() * k, bh = bg.getHeight() * k;
                c.drawBitmap(bg, null, new RectF((W - bw) / 2, (H - bh) / 2, (W + bw) / 2, (H + bh) / 2), bmp);
                c.drawColor(0x66000000);
            }
            // zona de movimiento
            RectF mv = area(0);
            p.setColor(0x14FFFFFF);
            c.drawRoundRect(new RectF(mv.left + 10 * dp, mv.top + 54 * dp, mv.right - 10 * dp, mv.bottom - 10 * dp), 28 * dp, 28 * dp, p);
            txt.setTextSize(13 * dp);
            if (knob[0] == null) c.drawText("Mover: apoya el dedo aquí", mv.centerX(), mv.centerY(), txt);
            else drawStick(c, start.get(-1), knob[0], 0x55FFFFFF);
            for (int i = 1; i < 6; i++) {
                RectF a = area(i);
                boolean down = knob[i] != null;
                int col = i == 1 ? 0xFFE53935 : i == 2 ? 0xFFFDD835 : i == 3 ? 0xFF43A047 : i == 4 ? 0xFF8E24AA : 0xFF1E88E5;
                p.setColor((col & 0x00FFFFFF) | (down ? 0xCC000000 : 0x77000000));
                c.drawOval(a, p);
                ring.setColor(down ? Color.WHITE : 0xAAFFFFFF);
                c.drawOval(a, ring);
                txt.setTextSize(Math.max(11 * dp, a.width() * 0.13f));
                c.drawText(NAMES[i], a.centerX(), a.centerY() + txt.getTextSize() * 0.35f, txt);
                if (down && joystick(i)) {
                    p.setColor(0xEEFFFFFF);
                    c.drawCircle(knob[i][0], knob[i][1], a.width() * 0.16f, p);
                }
            }
        }

        private void drawStick(Canvas c, float[] s, float[] k, int col) {
            if (s == null || k == null) return;
            float r = Math.min(getWidth(), getHeight()) * 0.2f;
            ring.setColor(0x99FFFFFF);
            c.drawCircle(s[0], s[1], r, ring);
            p.setColor(0xDDFFFFFF);
            c.drawCircle(k[0], k[1], r * 0.35f, p);
        }

        /** Vector del joystick (−1..1) según el dedo y el centro. */
        private float[] vec(int ctl, float x, float y, float[] s) {
            float r = ctl == 0 ? Math.min(getWidth(), getHeight()) * 0.2f : area(ctl).width() / 2;
            float dx = (x - s[0]) / r, dy = (y - s[1]) / r, len = (float) Math.hypot(dx, dy);
            if (len > 1) { dx /= len; dy /= len; }
            return new float[]{dx, dy};
        }

        /** Punto en el principal: centro del botón real + vector × alcance. */
        private double[] target(int ctl, float[] v) {
            double[] c = pos(ctl);
            if (!joystick(ctl) || v == null) return c;
            float rr = reach();
            return new double[]{c[0] + v[0] * rr, c[1] + v[1] * rr * 2.2f};
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_POINTER_DOWN) {
                int i = e.getActionIndex(), pid = e.getPointerId(i);
                float x = e.getX(i), y = e.getY(i);
                int ctl = controlAt(x, y);
                if (ctl < 0 || isBusy(ctl)) return true;
                int f = nextFinger++;
                owner.put(pid, new int[]{ctl, f});
                float[] s = ctl == 0 ? new float[]{x, y} : new float[]{area(ctl).centerX(), area(ctl).centerY()};
                start.put(pid, s);
                if (ctl == 0) start.put(-1, s);
                knob[ctl] = new float[]{x, y};
                double[] t = target(ctl, ctl == 0 ? new float[]{0, 0} : vec(ctl, x, y, s));
                send(f, 0, t[0], t[1]);
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                invalidate();
            } else if (a == MotionEvent.ACTION_MOVE) {
                long now = SystemClock.uptimeMillis();
                for (int i = 0; i < e.getPointerCount(); i++) {
                    int pid = e.getPointerId(i);
                    int[] o = owner.get(pid);
                    if (o == null || !joystick(o[0])) continue;
                    float x = e.getX(i), y = e.getY(i);
                    knob[o[0]] = new float[]{x, y};
                    if (now - lastMove[o[0]] < 25) continue;   // ~40 por segundo es suficiente
                    lastMove[o[0]] = now;
                    double[] t = target(o[0], vec(o[0], x, y, start.get(pid)));
                    send(o[1], 1, t[0], t[1]);
                }
                invalidate();
            } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_POINTER_UP || a == MotionEvent.ACTION_CANCEL) {
                if (a == MotionEvent.ACTION_CANCEL) { releaseAll(); return true; }
                int i = e.getActionIndex(), pid = e.getPointerId(i);
                int[] o = owner.remove(pid);
                if (o == null) return true;
                float[] s = start.remove(pid);
                double[] t = target(o[0], joystick(o[0]) && s != null ? vec(o[0], e.getX(i), e.getY(i), s) : null);
                send(o[1], 2, t[0], t[1]);
                knob[o[0]] = null;
                if (o[0] == 0) start.remove(-1);
                invalidate();
            }
            return true;
        }

        final long[] lastMove = new long[6];

        boolean isBusy(int ctl) { for (int[] o : owner.values()) if (o[0] == ctl) return true; return false; }

        void releaseAll() {
            for (int[] o : owner.values()) { double[] t = pos(o[0]); send(o[1], 2, t[0], t[1]); }
            owner.clear(); start.clear();
            for (int i = 0; i < 6; i++) knob[i] = null;
            invalidate();
        }
    }

    // ======================= ajustar: ubicar los botones del juego =======================

    private void startCalib() {
        if (!hub.connected()) { android.widget.Toast.makeText(this, "El principal no está conectado", android.widget.Toast.LENGTH_SHORT).show(); return; }
        calib = new Calib();
        FrameLayout root = (FrameLayout) pad.getParent();
        root.addView(calib, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(pill("− Alcance", v -> { prefs.edit().putFloat("gp_" + pkg + "_reach", Math.max(0.03f, reach() - 0.01f)).apply(); calib.invalidate(); }));
        bar.addView(pill("+ Alcance", v -> { prefs.edit().putFloat("gp_" + pkg + "_reach", Math.min(0.2f, reach() + 0.01f)).apply(); calib.invalidate(); }));
        bar.addView(pill("Por defecto", v -> {
            SharedPreferences.Editor ed = prefs.edit();
            for (String id : IDS) ed.remove("gp_" + pkg + "_" + id);
            ed.remove("gp_" + pkg + "_reach").apply();
            calib.invalidate();
        }));
        bar.addView(pill("Nueva captura", v -> hub.send("gpShot")));
        bar.addView(pill("Listo", v -> { root.removeView(calib); root.removeView(bar); calib = null; pad.invalidate(); }));
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(-2, dp(40), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        bl.bottomMargin = dp(8);
        root.addView(bar, bl);
        hub.send("gpShot");
        android.widget.Toast.makeText(this, "Arrastra cada círculo sobre su botón en Brawl Stars", android.widget.Toast.LENGTH_LONG).show();
    }

    /** Captura del principal con un círculo por control: se arrastran a su botón real. */
    private final class Calib extends View {
        Bitmap img;
        int drag = -1;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG), txt = new Paint(Paint.ANTI_ALIAS_FLAG), bmp = new Paint(Paint.FILTER_BITMAP_FLAG);

        Calib() {
            super(GamePalActivity.this);
            setBackgroundColor(0xF0101418);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(2.5f * dp);
            txt.setTextAlign(Paint.Align.CENTER);
            txt.setColor(Color.WHITE);
            txt.setTextSize(12 * dp);
            txt.setShadowLayer(3 * dp, 0, dp, 0xCC000000);
        }

        RectF box() {
            float W = getWidth(), H = getHeight() - 56 * dp, ar = img != null ? img.getWidth() / (float) img.getHeight() : 20f / 9f;
            float w = Math.min(W - 16 * dp, H * ar), hh = w / ar;
            return new RectF((W - w) / 2, (H - hh) / 2, (W + w) / 2, (H + hh) / 2);
        }

        @Override protected void onDraw(Canvas c) {
            RectF b = box();
            if (img != null) c.drawBitmap(img, null, b, bmp);
            else { p.setColor(0x22FFFFFF); c.drawRect(b, p); c.drawText("Capturando la pantalla del principal…", b.centerX(), b.centerY(), txt); }
            float rr = reach() * b.width();
            for (int i = 0; i < 6; i++) {
                double[] q = pos(i);
                float x = b.left + (float) q[0] * b.width(), y = b.top + (float) q[1] * b.height();
                ring.setColor(i == drag ? ORANGE : Color.WHITE);
                c.drawCircle(x, y, 16 * dp, ring);
                if (i <= 2) { ring.setColor(0x88FFFFFF); c.drawCircle(x, y, rr, ring); }
                c.drawText(NAMES[i], x, y - 20 * dp, txt);
            }
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            RectF b = box();
            float x = e.getX(), y = e.getY();
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                drag = -1;
                float best = 40 * dp;
                for (int i = 0; i < 6; i++) {
                    double[] q = pos(i);
                    float d = (float) Math.hypot(x - (b.left + q[0] * b.width()), y - (b.top + q[1] * b.height()));
                    if (d < best) { best = d; drag = i; }
                }
            } else if (drag >= 0 && (e.getActionMasked() == MotionEvent.ACTION_MOVE || e.getActionMasked() == MotionEvent.ACTION_UP)) {
                double nx = Math.max(0, Math.min(1, (x - b.left) / b.width())), ny = Math.max(0, Math.min(1, (y - b.top) / b.height()));
                prefs.edit().putString("gp_" + pkg + "_" + IDS[drag], nx + "," + ny).apply();
                if (e.getActionMasked() == MotionEvent.ACTION_UP) drag = -1;
            }
            invalidate();
            return true;
        }
    }
}
