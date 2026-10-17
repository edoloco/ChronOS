package cl.mesadoble;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Mesa de dibujo en el EXTENDIDO (con Sketchbook abierto en el principal).
 * Abajo se ve una zona del dibujo con zoom; el dedo traza aquí y, al soltar, el trazo se dibuja en Sketchbook
 * dentro de esa zona (más chico = más fino). Después llega una imagen de cómo quedó.
 * Dos dedos: mover la zona y cambiar el zoom. El trazo de vista previa imita el pincel elegido en Sketchbook
 * (grosor, color y opacidad): se aprende comparando la imagen antes y después de cada trazo.
 */
public class SketchActivity extends Activity implements Link.Listener {

    static volatile boolean open;
    /** Se cerró a mano: no se vuelve a abrir sola hasta que Sketchbook se cierre y se abra de nuevo. */
    static volatile boolean dismissed;
    static SketchActivity live;


    private final Handler h = new Handler(Looper.getMainLooper());
    private Hub hub;
    private Pad pad;
    private TextView banner;
    private SharedPreferences prefs;
    private boolean pickView;           // eligiendo la zona en la vista completa

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        live = this;
        hub = Hub.get(this);
        prefs = getSharedPreferences("mesa", MODE_PRIVATE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(0xFF0E1418);

        // tablero
        android.widget.FrameLayout area = new android.widget.FrameLayout(this);
        pad = new Pad(this);
        area.addView(pad);
        banner = new TextView(this);
        banner.setTextColor(Color.WHITE);
        banner.setTextSize(14);
        banner.setPadding(dp(14), dp(8), dp(14), dp(8));
        banner.setBackground(round(0xE61E5BC6));
        banner.setVisibility(View.GONE);
        android.widget.FrameLayout.LayoutParams bl = new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        bl.topMargin = dp(10);
        area.addView(banner, bl);
        // dos botones chicos en la esquina: vista completa y salir
        LinearLayout corner = new LinearLayout(this);
        corner.setOrientation(LinearLayout.HORIZONTAL);
        TextView view = btn("⤢", v -> pickView());
        view.setTextSize(18);
        view.setBackground(circle(0xCC1E5BC6));
        TextView turn = btn("⟳", v -> {
            int rf = (prefs.getInt("skRot", 0) + 90) % 360;
            prefs.edit().putInt("skRot", rf).apply();
            pad.resetImage();
            say("Giro corregido: el dibujo debe verse igual que arriba", 2500);
            requestZoom();
        });
        turn.setTextSize(18);
        turn.setBackground(circle(0xCC3A4A55));
        TextView exit = btn("✕", v -> { dismissed = true; finish(); });
        exit.setTextSize(16);
        exit.setBackground(circle(0xCC7A2430));
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(dp(42), dp(42));
        cl.leftMargin = dp(8);
        corner.addView(turn, cl);
        corner.addView(view, new LinearLayout.LayoutParams(cl));
        corner.addView(exit, new LinearLayout.LayoutParams(cl));
        android.widget.FrameLayout.LayoutParams kl = new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        kl.topMargin = dp(8); kl.rightMargin = dp(8);
        area.addView(corner, kl);
        root.addView(area, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1));
        setContentView(root);
        immersive();
        if (!prefs.getBoolean("skHelp", false)) {
            prefs.edit().putBoolean("skHelp", true).apply();
            say("Dibuja con un dedo · dos dedos mueven la zona y el zoom · ⤢ para ver el dibujo completo", 7000);
        }
    }

    private GradientDrawable round(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(12));
        return g;
    }

    private TextView btn(String label, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(Color.WHITE);
        t.setTextSize(13);
        t.setGravity(Gravity.CENTER);
        t.setBackground(round(0xFF22313A));
        t.setOnClickListener(l);
        return t;
    }

    private GradientDrawable circle(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        return g;
    }

    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override public void onWindowFocusChanged(boolean f) { super.onWindowFocusChanged(f); if (f) immersive(); }

    private void say(String s, long ms) {
        banner.setText(s);
        banner.setVisibility(View.VISIBLE);
        h.removeCallbacks(hideBanner);
        if (ms > 0) h.postDelayed(hideBanner, ms);
    }

    private final Runnable hideBanner = () -> banner.setVisibility(View.GONE);

    @Override protected void onResume() {
        super.onResume();
        open = true;
        hub.add(this);
        h.postDelayed(this::requestZoom, 300);
        h.postDelayed(refresh, 5000);
    }

    @Override protected void onPause() {
        open = false;
        hub.remove(this);
        h.removeCallbacks(refresh);
        if (isFinishing() && hub.connected()) hub.send("skEnd");
        super.onPause();
    }

    @Override protected void onDestroy() { if (live == this) live = null; super.onDestroy(); }

    /** Cada tanto se pide la imagen otra vez (por si se cambió algo arriba), si no se está dibujando. */
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (!pad.busy() && !pickView) requestZoom();
            h.postDelayed(this, 5000);
        }
    };

    // ---------------- hacia el principal ----------------

    private JSONArray rectJson() {
        double[] r = pad.rect;
        JSONArray a = new JSONArray();
        try { a.put(r[0]).put(r[1]).put(r[2]).put(r[3]); } catch (Exception ignored) { }
        return a;
    }

    void requestZoom() {
        if (!hub.connected()) return;
        if (pad.getWidth() == 0) { h.postDelayed(this::requestZoom, 200); return; }
        hub.send("skShot", "r", rectJson(), "w", pad.getWidth(), "h", pad.getHeight(), "rf", prefs.getInt("skRot", 0));
    }

    private void requestFull() { if (hub.connected()) hub.send("skShot", "full", true, "w", pad.getWidth(), "h", pad.getHeight(), "rf", prefs.getInt("skRot", 0)); }

    void sendStroke(float[] pts, int n, long dur) {
        if (!hub.connected()) { Toast.makeText(this, "El principal no está conectado", Toast.LENGTH_SHORT).show(); return; }
        JSONArray a = new JSONArray();
        try { for (int i = 0; i < n; i++) a.put(Math.round(pts[i] * 100000) / 100000.0); } catch (Exception ignored) { }
        hub.send("skStroke", "p", a, "d", dur, "r", rectJson(), "w", pad.getWidth(), "h", pad.getHeight(), "rf", prefs.getInt("skRot", 0));
    }

    void sendTap(double x, double y) {
        if (hub.connected()) hub.send("skTap", "x", x, "y", y, "r", rectJson(), "w", pad.getWidth(), "h", pad.getHeight(), "rf", prefs.getInt("skRot", 0));
    }

    // ---------------- vista completa ----------------

    private void pickView() {
        pickView = true;
        say("Toca dónde quieres dibujar", 0);
        pad.setFullMode(true);
        requestFull();
    }

    /** Toque en la vista completa (nx, ny de la pantalla del principal). */
    void picked(double nx, double ny) {
        if (pickView) {
            pad.centerOn(nx, ny);
            pickView = false;
        }
        banner.setVisibility(View.GONE);
        pad.setFullMode(false);
        requestZoom();
    }

    // ---------------- mensajes ----------------

    @Override public void onMessage(JSONObject m) {
        String t = m.optString("t");
        if ("skImg".equals(t)) {
            if (m.has("err")) { h.post(() -> say(m.optString("err"), 6000)); return; }
            byte[] d = android.util.Base64.decode(m.optString("img"), android.util.Base64.DEFAULT);
            Bitmap b = BitmapFactory.decodeByteArray(d, 0, d.length);
            JSONArray r = m.optJSONArray("r");
            if (b == null || r == null) return;
            double[] rr = {r.optDouble(0), r.optDouble(1), r.optDouble(2), r.optDouble(3)};
            int sw = m.optInt("sw"), sh = m.optInt("sh");
            boolean full = m.optBoolean("full");
            h.post(() -> pad.image(b, rr, sw, sh, full));
        } else if ("skNote".equals(t)) {
            h.post(() -> say(m.optString("msg"), 4000));
        } else if ("sketchOff".equals(t)) {
            h.post(this::finish);
        }
    }

    @Override public void onState(int state, String info) {
        if (state != Link.CONNECTED) h.post(() -> say("El principal no está conectado", 0));
        else h.post(() -> { if (!pickView) banner.setVisibility(View.GONE); });
    }

    // ======================= el tablero =======================

    private static final class Pad extends View {
        final SketchActivity a;
        final double[] rect = {0.25, 0.25, 0.75, 0.75};
        private int sw = 2400, sh = 1080;          // pantalla del principal (llega con la primera imagen)
        private Bitmap img, fullImg;
        private double[] imgRect;
        private boolean fullMode;
        private final Paint bmp = new Paint(Paint.FILTER_BITMAP_FLAG), ink = new Paint(Paint.ANTI_ALIAS_FLAG), grid = new Paint(), txt = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float dp;
        // trazos ya mandados (se ven hasta que llega la imagen nueva) y el actual
        private final List<Path> sent = new ArrayList<>();
        private long sentAt;
        private Path cur;
        private float[] pts = new float[4096];
        private int n;
        private long downAt;
        private float sx, sy;
        private boolean moved, two;
        // dos dedos
        private double[] r0;
        private float mx0, my0, d0;
        // pincel aprendido: grosor (fracción del ancho de la pantalla del principal) y color, de comparar antes/después
        private double brushW = -1;
        private int brushColor = 0xE600E5FF;
        private Bitmap beforeImg;
        private double[] beforeRect;
        private float[] lastPts;
        private long lastSentAt;

        Pad(SketchActivity a) {
            super(a);
            this.a = a;
            dp = a.getResources().getDisplayMetrics().density;
            ink.setStyle(Paint.Style.STROKE);
            ink.setStrokeCap(Paint.Cap.ROUND);
            ink.setStrokeJoin(Paint.Join.ROUND);
            ink.setStrokeWidth(2.5f * dp);
            ink.setColor(0xE600E5FF);
            grid.setColor(0x1AFFFFFF);
            grid.setStrokeWidth(1);
            txt.setColor(0x99FFFFFF);
            txt.setTextSize(14 * dp);
            txt.setTextAlign(Paint.Align.CENTER);
        }

        boolean busy() { return cur != null || two; }

        void setFullMode(boolean f) { fullMode = f; invalidate(); }

        /** Cambió el giro: la imagen vieja ya no sirve. */
        void resetImage() { img = null; imgRect = null; fullImg = null; beforeImg = null; lastPts = null; sent.clear(); invalidate(); }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) { fit(); }

        /** La zona tiene la misma forma que el tablero (si no, el dibujo se deformaría). */
        private void fit() {
            float vw = Math.max(1, getWidth()), vh = Math.max(1, getHeight());
            double w = rect[2] - rect[0], cx = (rect[0] + rect[2]) / 2, cy = (rect[1] + rect[3]) / 2;
            w = Math.max(0.06, Math.min(1, w));
            double hh = w * sw * vh / (sh * vw);
            if (hh > 1) { hh = 1; w = hh * sh * vw / (sw * vh); }
            cx = Math.max(w / 2, Math.min(1 - w / 2, cx));
            cy = Math.max(hh / 2, Math.min(1 - hh / 2, cy));
            rect[0] = cx - w / 2; rect[2] = cx + w / 2; rect[1] = cy - hh / 2; rect[3] = cy + hh / 2;
        }

        void centerOn(double nx, double ny) {
            double w = rect[2] - rect[0], hh = rect[3] - rect[1];
            rect[0] = nx - w / 2; rect[2] = nx + w / 2; rect[1] = ny - hh / 2; rect[3] = ny + hh / 2;
            fit();
            sent.clear();
            invalidate();
        }

        void image(Bitmap b, double[] r, int w, int h, boolean full) {
            boolean sizeChanged = w > 0 && h > 0 && (w != sw || h != sh);
            if (w > 0 && h > 0) { sw = w; sh = h; }
            if (full) fullImg = b;
            else {
                if (lastPts != null && beforeImg != null && SystemClock.uptimeMillis() - lastSentAt > 150
                        && java.util.Arrays.equals(r, beforeRect) && b.getWidth() == beforeImg.getWidth() && b.getHeight() == beforeImg.getHeight()) {
                    if (learn(beforeImg, b, r, lastPts)) lastPts = null;
                    else if (SystemClock.uptimeMillis() - lastSentAt > 3000) lastPts = null;   // no se vio el trazo: se deja como estaba
                }
                img = b;
                imgRect = r;
                if (SystemClock.uptimeMillis() - sentAt > 200) sent.clear();
            }
            if (sizeChanged) { fit(); a.requestZoom(); }
            invalidate();
        }

        private static int diff(int a, int b) {
            return Math.abs(((a >> 16) & 255) - ((b >> 16) & 255)) + Math.abs(((a >> 8) & 255) - ((b >> 8) & 255)) + Math.abs((a & 255) - (b & 255));
        }

        /**
         * Compara la imagen antes y después del trazo a lo largo del recorrido: donde cambió, se mide el ancho
         * (de lado a lado, perpendicular al trazo) y se toma el color. Así la vista previa imita el pincel elegido.
         */
        private boolean learn(Bitmap before, Bitmap after, double[] r, float[] p) {
            int bw = after.getWidth(), bh = after.getHeight(), np = p.length / 2;
            if (np < 3) return false;
            double rw = r[2] - r[0], rh = r[3] - r[1];
            List<Float> widths = new ArrayList<>();
            long sr = 0, sg = 0, sb = 0;
            int hits = 0, tries = 0, K = Math.min(28, np);
            for (int k = 0; k < K; k++) {
                int i = (int) ((long) k * (np - 1) / Math.max(1, K - 1));
                float x = (float) ((p[i * 2] - r[0]) / rw * bw), y = (float) ((p[i * 2 + 1] - r[1]) / rh * bh);
                int i0 = Math.max(0, i - 2), i1 = Math.min(np - 1, i + 2);
                float dx = (float) ((p[i1 * 2] - p[i0 * 2]) / rw * bw), dy = (float) ((p[i1 * 2 + 1] - p[i0 * 2 + 1]) / rh * bh);
                float len = (float) Math.hypot(dx, dy);
                if (len < 0.5f) continue;
                float px = -dy / len, py = dx / len;   // perpendicular
                int cx = Math.round(x), cy = Math.round(y);
                if (cx < 1 || cy < 1 || cx >= bw - 1 || cy >= bh - 1) continue;
                tries++;
                int na = after.getPixel(cx, cy);
                if (diff(na, before.getPixel(cx, cy)) < 45) continue;
                hits++;
                sr += (na >> 16) & 255; sg += (na >> 8) & 255; sb += na & 255;
                float w = 0;
                for (int side = -1; side <= 1; side += 2) {
                    for (float t = 0.5f; t < 90; t += 0.5f) {
                        int qx = Math.round(x + side * px * t), qy = Math.round(y + side * py * t);
                        if (qx < 0 || qy < 0 || qx >= bw || qy >= bh || diff(after.getPixel(qx, qy), before.getPixel(qx, qy)) < 45) { w += t; break; }
                    }
                }
                widths.add(w);
            }
            if (tries == 0 || hits < Math.max(3, tries * 0.35f) || widths.isEmpty()) return false;
            java.util.Collections.sort(widths);
            float wpx = widths.get(widths.size() / 2);
            brushW = wpx / bw * rw;
            brushColor = 0xF0000000 | ((int) (sr / hits) << 16) | ((int) (sg / hits) << 8) | (int) (sb / hits);
            return true;
        }

        private void applyBrush() {
            float w = brushW > 0 ? (float) (brushW / (rect[2] - rect[0]) * getWidth()) : 2.5f * dp;
            ink.setStrokeWidth(Math.max(1f, w));
            ink.setColor(brushColor);
        }

        private RectF fullBox() {
            float vw = getWidth(), vh = getHeight();
            float k = Math.min(vw / sw, vh / sh);
            float w = sw * k, hh = sh * k;
            return new RectF((vw - w) / 2, (vh - hh) / 2, (vw + w) / 2, (vh + hh) / 2);
        }

        @Override protected void onDraw(Canvas c) {
            float vw = getWidth(), vh = getHeight();
            if (fullMode) {
                RectF box = fullBox();
                if (fullImg != null) c.drawBitmap(fullImg, null, box, bmp);
                else c.drawText("Cargando la pantalla del principal…", vw / 2, vh / 2, txt);
                return;
            }
            if (img != null && imgRect != null) {
                double w = rect[2] - rect[0], hh = rect[3] - rect[1];
                RectF dst = new RectF((float) ((imgRect[0] - rect[0]) / w * vw), (float) ((imgRect[1] - rect[1]) / hh * vh),
                        (float) ((imgRect[2] - rect[0]) / w * vw), (float) ((imgRect[3] - rect[1]) / hh * vh));
                c.drawBitmap(img, null, dst, bmp);
            } else {
                for (int i = 1; i < 8; i++) { c.drawLine(vw * i / 8, 0, vw * i / 8, vh, grid); c.drawLine(0, vh * i / 8, vw, vh * i / 8, grid); }
                c.drawText("Esperando la imagen de Sketchbook…", vw / 2, vh / 2, txt);
            }
            applyBrush();
            for (Path p : sent) c.drawPath(p, ink);
            if (cur != null) c.drawPath(cur, ink);
        }

        private void add(float x, float y) {
            if (n + 2 > pts.length) {   // trazos larguísimos: se toma uno de cada dos puntos
                int k = 0;
                for (int i = 0; i < n; i += 4) { pts[k++] = pts[i]; pts[k++] = pts[i + 1]; }
                n = k;
            }
            pts[n++] = (float) (rect[0] + x / getWidth() * (rect[2] - rect[0]));
            pts[n++] = (float) (rect[1] + y / getHeight() * (rect[3] - rect[1]));
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (fullMode) {
                if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                    RectF box = fullBox();
                    if (box.contains(e.getX(), e.getY()))
                        a.picked((e.getX() - box.left) / box.width(), (e.getY() - box.top) / box.height());
                }
                return true;
            }
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    two = false;
                    cur = new Path();
                    cur.moveTo(e.getX(), e.getY());
                    n = 0;
                    add(e.getX(), e.getY());
                    sx = e.getX(); sy = e.getY();
                    downAt = e.getEventTime();
                    moved = false;
                    invalidate();
                    return true;
                case MotionEvent.ACTION_POINTER_DOWN:
                    if (e.getPointerCount() == 2) {   // dos dedos: se deja el trazo y se mueve la zona
                        cur = null;
                        two = true;
                        r0 = rect.clone();
                        mx0 = (e.getX(0) + e.getX(1)) / 2; my0 = (e.getY(0) + e.getY(1)) / 2;
                        d0 = Math.max(1, (float) Math.hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1)));
                        invalidate();
                    }
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (two && e.getPointerCount() >= 2) {
                        float mx = (e.getX(0) + e.getX(1)) / 2, my = (e.getY(0) + e.getY(1)) / 2;
                        float d = Math.max(1, (float) Math.hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1)));
                        double w0 = r0[2] - r0[0], h0 = r0[3] - r0[1];
                        double w = w0 * d0 / d, hh = h0 * d0 / d;
                        double cx = (r0[0] + r0[2]) / 2 - (mx - mx0) / getWidth() * w0, cy = (r0[1] + r0[3]) / 2 - (my - my0) / getHeight() * h0;
                        rect[0] = cx - w / 2; rect[2] = cx + w / 2; rect[1] = cy - hh / 2; rect[3] = cy + hh / 2;
                        fit();
                        sent.clear();
                        invalidate();
                        return true;
                    }
                    if (cur == null) return true;
                    for (int i = 0; i < e.getHistorySize(); i++) { cur.lineTo(e.getHistoricalX(i), e.getHistoricalY(i)); add(e.getHistoricalX(i), e.getHistoricalY(i)); }
                    cur.lineTo(e.getX(), e.getY());
                    add(e.getX(), e.getY());
                    if (Math.hypot(e.getX() - sx, e.getY() - sy) > 8 * dp) moved = true;
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                    if (two) { two = false; a.requestZoom(); return true; }
                    if (cur == null) return true;
                    long dur = e.getEventTime() - downAt;
                    if (!moved && dur < 350) a.sendTap(pts[0], pts[1]);
                    else {
                        a.sendStroke(pts, n, dur);
                        sent.add(cur);
                        sentAt = SystemClock.uptimeMillis();
                        if (img != null && imgRect != null) { beforeImg = img; beforeRect = imgRect.clone(); lastPts = java.util.Arrays.copyOf(pts, n); lastSentAt = sentAt; }
                    }
                    cur = null;
                    invalidate();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    cur = null;
                    two = false;
                    invalidate();
                    return true;
            }
            return true;
        }
    }
}
