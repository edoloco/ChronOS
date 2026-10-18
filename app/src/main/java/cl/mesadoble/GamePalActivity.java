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
        FrameLayout root = new FrameLayout(this);
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
        top.addView(pill("Ajustar", v -> startCalib()));
        top.addView(pill("✕", v -> finish()));
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(-2, dp(40), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        tl.topMargin = dp(6);
        root.addView(top, tl);
        setContentView(root);
        immersive();
        loadBg();
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
        else if ("gpImg".equals(t)) h.post(() -> {
            if (calib == null) return;
            if (m.has("err")) { android.widget.Toast.makeText(this, m.optString("err"), android.widget.Toast.LENGTH_LONG).show(); return; }
            byte[] d = android.util.Base64.decode(m.optString("img"), android.util.Base64.DEFAULT);
            calib.img = BitmapFactory.decodeByteArray(d, 0, d.length);
            calib.invalidate();
        });
    }

    @Override public void onState(int state, String info) { }

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
