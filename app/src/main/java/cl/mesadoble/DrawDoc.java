package cl.mesadoble;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * DUAL-Draw: el dibujo (capas, pinceles y deshacer). Los dos teléfonos tienen una copia y aplican las mismas
 * órdenes en el mismo orden, así se ve igual arriba y abajo:
 *   seg {l, b:{t,s,c,o}, p:[x,y,presión,…], st}  tramo de un trazo (st = empieza)
 *   end                                          el trazo se pega en la capa (se puede deshacer)
 *   undo · redo · clear · layer {a: add|del|sel|vis|up|down, i}
 * Un trazo se dibuja primero en un búfer (con opacidad completa) y al soltar se pega en la capa con la
 * opacidad del pincel: así el rotulador no se oscurece donde se cruza consigo mismo.
 */
final class DrawDoc {

    static final int W = 1600, H = 900, MAX_LAYERS = 6;
    static final int PENCIL = 0, INK = 1, MARKER = 2, AIR = 3, ERASER = 4, BRUSH = 5, CHALK = 6, CALLI = 7, SOFT_ERASER = 8;
    static final String[] BRUSH_NAMES = {"Lápiz", "Tinta", "Rotulador", "Aerógrafo", "Borrador", "Pincel", "Tiza", "Caligrafía", "Borrador suave"};
    static final String[] BLEND_NAMES = {"Normal", "Multiplicar", "Trama", "Superponer", "Sumar"};

    static boolean eraser(int t) { return t == ERASER || t == SOFT_ERASER; }

    static final class Layer { Bitmap bmp; boolean visible = true; String name; float alpha = 1f; int blend; }

    final List<Layer> layers = new ArrayList<>();
    int cur = 0;
    final Bitmap buf = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);   // trazo en curso
    private final Canvas bufC = new Canvas(buf);

    // trazo en curso
    boolean stroking, sSym;
    private int stampN;
    int sType, sColor, sLayer;
    float sSize, sOpacity;
    private float lx, ly, lp, carry;
    private boolean hasLast;
    final Rect dirty = new Rect();

    private final Paint dab = new Paint(Paint.ANTI_ALIAS_FLAG), soft = new Paint(Paint.FILTER_BITMAP_FLAG), comp = new Paint(Paint.FILTER_BITMAP_FLAG);
    private static Bitmap softTip;

    // deshacer / rehacer (solo la zona que cambió)
    private static final class Step { int layer; Rect r; Bitmap before, after; }
    private final List<Step> undo = new ArrayList<>(), redo = new ArrayList<>();
    private long undoBytes;

    DrawDoc() { addLayer(); }

    // ---------------- capas ----------------

    void addLayer() {
        if (layers.size() >= MAX_LAYERS) return;
        Layer l = new Layer();
        l.bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        l.name = "Capa " + (layers.size() + 1);
        layers.add(cur + (layers.isEmpty() ? 0 : 1), l);
        cur = layers.indexOf(l);
        renameLayers();
    }

    private void renameLayers() { for (int i = 0; i < layers.size(); i++) layers.get(i).name = "Capa " + (i + 1); }

    void deleteLayer(int i) {
        if (layers.size() <= 1 || i < 0 || i >= layers.size()) return;
        layers.remove(i).bmp.recycle();
        undo.clear(); redo.clear(); undoBytes = 0;   // los pasos guardados ya no calzan con las capas
        cur = Math.max(0, Math.min(cur, layers.size() - 1));
        renameLayers();
    }

    void moveLayer(int i, int d) {
        int j = i + d;
        if (i < 0 || j < 0 || i >= layers.size() || j >= layers.size()) return;
        Layer a = layers.get(i);
        layers.set(i, layers.get(j));
        layers.set(j, a);
        for (Step s : undo) s.layer = s.layer == i ? j : s.layer == j ? i : s.layer;
        for (Step s : redo) s.layer = s.layer == i ? j : s.layer == j ? i : s.layer;
        if (cur == i) cur = j; else if (cur == j) cur = i;
        renameLayers();
    }

    // ---------------- pinceles ----------------

    private static Bitmap tip() {
        if (softTip != null) return softTip;
        Bitmap b = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new RadialGradient(32, 32, 32, new int[]{0xFFFFFFFF, 0x88FFFFFF, 0x00FFFFFF}, new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        new Canvas(b).drawCircle(32, 32, 32, p);
        softTip = b;
        return b;
    }

    /** Radio de una pincelada según el pincel y la presión (0..1). */
    private float radius(float p) {
        switch (sType) {
            case PENCIL: return Math.max(0.6f, sSize * 0.5f * (0.45f + 0.55f * p));
            case INK: return Math.max(0.6f, sSize * 0.5f * (0.15f + 0.85f * p));
            case AIR: return Math.max(2f, sSize * (0.7f + 0.3f * p));
            case BRUSH: return Math.max(1f, sSize * 0.6f * (0.4f + 0.6f * p));
            case CHALK: return Math.max(1.5f, sSize * 0.55f * (0.6f + 0.4f * p));
            case CALLI: return Math.max(1f, sSize * 0.6f * (0.5f + 0.5f * p));
            case SOFT_ERASER: return Math.max(2f, sSize * 0.8f);
            default: return Math.max(0.6f, sSize * 0.5f);   // rotulador y borrador: parejos
        }
    }

    private void stamp(float x, float y, float p) {
        stamp1(x, y, p, false);
        if (sSym) stamp1(W - x, y, p, true);   // simetría: el mismo trazo reflejado en el eje vertical
    }

    private final java.util.Random rnd = new java.util.Random();
    private final Paint chalk = new Paint(Paint.ANTI_ALIAS_FLAG);

    private void stamp1(float x, float y, float p, boolean mirrored) {
        float r = radius(p);
        stampN++;
        int col = sColor | 0xFF000000;
        switch (sType) {
            case AIR: case SOFT_ERASER: case BRUSH: {
                soft.setColorFilter(new PorterDuffColorFilter(sType == SOFT_ERASER ? 0xFF000000 : col, PorterDuff.Mode.SRC_IN));
                float a = sType == AIR ? 0.05f + 0.16f * p : sType == BRUSH ? 0.35f + 0.5f * p : 0.5f;
                soft.setAlpha((int) (255 * a));
                bufC.drawBitmap(tip(), null, new RectF(x - r, y - r, x + r, y + r), soft);
                break;
            }
            case CHALK: {
                // granos al azar, pero el mismo azar en los dos teléfonos (semilla según la pincelada)
                rnd.setSeed(stampN * 7919L + (mirrored ? 1 : 0));
                chalk.setColor(col);
                for (int i = 0; i < 7; i++) {
                    double ang = rnd.nextDouble() * Math.PI * 2, dist = Math.sqrt(rnd.nextDouble()) * r;
                    chalk.setAlpha(90 + rnd.nextInt(120));
                    bufC.drawCircle(x + (float) (Math.cos(ang) * dist), y + (float) (Math.sin(ang) * dist), Math.max(0.6f, r * 0.2f), chalk);
                }
                break;
            }
            case CALLI: {
                // punta plana en diagonal: más gruesa según la dirección
                dab.setColor(col);
                bufC.save();
                bufC.rotate(mirrored ? 45 : -45, x, y);
                bufC.drawOval(new RectF(x - r, y - r * 0.22f, x + r, y + r * 0.22f), dab);
                bufC.restore();
                break;
            }
            default:
                dab.setColor(sType == ERASER ? 0xFF000000 : col);
                if (sType == PENCIL) dab.setAlpha((int) (255 * (0.55f + 0.45f * p)));
                bufC.drawCircle(x, y, r, dab);
        }
        int ir = (int) Math.ceil(r) + 2;
        dirty.union((int) x - ir, (int) y - ir, (int) x + ir, (int) y + ir);
    }

    private float spacing(float p) {
        float r = radius(p);
        return sType == AIR || sType == SOFT_ERASER ? Math.max(1f, r * 0.18f) : sType == BRUSH ? Math.max(0.5f, r * 0.12f)
                : sType == CHALK ? Math.max(1f, r * 0.45f) : sType == CALLI ? Math.max(0.4f, r * 0.08f) : Math.max(0.5f, r * 0.28f);
    }

    /** Empieza un trazo. */
    void begin(int layer, int type, float size, int color, float opacity) { begin(layer, type, size, color, opacity, false); }

    void begin(int layer, int type, float size, int color, float opacity, boolean sym) {
        if (stroking) end();
        stroking = true;
        sSym = sym;
        stampN = 0;
        sLayer = Math.max(0, Math.min(layers.size() - 1, layer));
        sType = type; sSize = size; sColor = color; sOpacity = opacity;
        buf.eraseColor(Color.TRANSPARENT);
        dirty.setEmpty();
        hasLast = false;
        carry = 0;
    }

    /** Agrega un punto (x, y en el lienzo; p presión 0..1). */
    void point(float x, float y, float p) {
        if (!stroking) return;
        if (!hasLast) { stamp(x, y, p); lx = x; ly = y; lp = p; hasLast = true; return; }
        float dx = x - lx, dy = y - ly, d = (float) Math.hypot(dx, dy);
        if (d < 0.01f) return;
        float t = carry;
        while (true) {
            float pp = lp + (p - lp) * Math.min(1f, t / d);
            float sp = spacing(pp);
            if (t == 0) t = sp;
            if (t > d) { carry = t - d; break; }
            float f = t / d;
            stamp(lx + dx * f, ly + dy * f, lp + (p - lp) * f);
            t += spacing(lp + (p - lp) * f);
        }
        lx = x; ly = y; lp = p;
    }

    /** El trazo se pega en su capa (y queda guardado para deshacer). */
    void end() {
        if (!stroking) return;
        stroking = false;
        Rect r = new Rect(dirty);
        if (!r.intersect(0, 0, W, H) || r.isEmpty()) return;
        Layer l = layers.get(Math.min(sLayer, layers.size() - 1));
        Step st = new Step();
        st.layer = layers.indexOf(l);
        st.r = r;
        st.before = Bitmap.createBitmap(l.bmp, r.left, r.top, r.width(), r.height());
        Canvas c = new Canvas(l.bmp);
        comp.setXfermode(eraser(sType) ? new PorterDuffXfermode(PorterDuff.Mode.DST_OUT) : null);
        comp.setAlpha((int) (255 * Math.max(0.05f, Math.min(1f, sOpacity))));
        c.drawBitmap(buf, r, r, comp);
        comp.setXfermode(null);
        st.after = Bitmap.createBitmap(l.bmp, r.left, r.top, r.width(), r.height());
        push(st);
        buf.eraseColor(Color.TRANSPARENT);
    }

    private void push(Step st) {
        undo.add(st);
        undoBytes += (long) st.r.width() * st.r.height() * 8;
        for (Step s : redo) { s.before.recycle(); s.after.recycle(); }
        redo.clear();
        while (undo.size() > 40 || (undoBytes > 90L * 1024 * 1024 && undo.size() > 1)) {
            Step s = undo.remove(0);
            undoBytes -= (long) s.r.width() * s.r.height() * 8;
            s.before.recycle(); s.after.recycle();
        }
    }

    boolean undo() {
        if (undo.isEmpty()) return false;
        Step s = undo.remove(undo.size() - 1);
        undoBytes -= (long) s.r.width() * s.r.height() * 8;
        paste(s, s.before);
        redo.add(s);
        return true;
    }

    boolean redo() {
        if (redo.isEmpty()) return false;
        Step s = redo.remove(redo.size() - 1);
        paste(s, s.after);
        undo.add(s);
        undoBytes += (long) s.r.width() * s.r.height() * 8;
        return true;
    }

    private void paste(Step s, Bitmap b) {
        if (s.layer >= layers.size()) return;
        Bitmap lb = layers.get(s.layer).bmp;
        Canvas c = new Canvas(lb);
        Paint p = new Paint();
        p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC));
        c.drawBitmap(b, null, s.r, p);
    }

    /** Borra la capa actual (se puede deshacer). */
    void clearLayer(int i) {
        if (i < 0 || i >= layers.size()) return;
        Layer l = layers.get(i);
        Step st = new Step();
        st.layer = i;
        st.r = new Rect(0, 0, W, H);
        st.before = l.bmp.copy(Bitmap.Config.ARGB_8888, false);
        l.bmp.eraseColor(Color.TRANSPARENT);
        st.after = l.bmp.copy(Bitmap.Config.ARGB_8888, false);
        push(st);
    }

    // ---------------- órdenes (las mismas en los dos teléfonos) ----------------

    void apply(JSONObject m) {
        String op = m.optString("op");
        switch (op) {
            case "seg": {
                JSONObject b = m.optJSONObject("b");
                if (m.optBoolean("st") || !stroking) {
                    if (b == null) return;
                    begin(m.optInt("l", cur), b.optInt("t"), (float) b.optDouble("s", 8), b.optInt("c", 0xFF000000), (float) b.optDouble("o", 1), b.optBoolean("m"));
                }
                JSONArray p = m.optJSONArray("p");
                for (int i = 0; p != null && i + 2 < p.length(); i += 3)
                    point((float) p.optDouble(i), (float) p.optDouble(i + 1), (float) p.optDouble(i + 2));
                break;
            }
            case "end": end(); break;
            case "cancel": stroking = false; buf.eraseColor(Color.TRANSPARENT); break;
            case "undo": end(); undo(); break;
            case "redo": end(); redo(); break;
            case "clear": end(); clearLayer(m.optInt("i", cur)); break;
            case "shape": end(); shape(m); break;
            case "fill": end(); fill(m.optInt("l", cur), m.optInt("x"), m.optInt("y"), m.optInt("c", 0xFF000000), m.optInt("tol", 40)); break;
            case "import": end(); importImage(m.optString("png")); break;
            case "new": end(); reset(); break;
            case "layer": {
                end();
                String a = m.optString("a");
                int i = m.optInt("i", cur);
                if ("add".equals(a)) addLayer();
                else if ("del".equals(a)) deleteLayer(i);
                else if ("sel".equals(a)) cur = Math.max(0, Math.min(layers.size() - 1, i));
                else if ("vis".equals(a) && i < layers.size()) layers.get(i).visible = m.optBoolean("v", !layers.get(i).visible);
                else if ("up".equals(a)) moveLayer(i, 1);
                else if ("down".equals(a)) moveLayer(i, -1);
                else if ("opac".equals(a) && i < layers.size()) layers.get(i).alpha = (float) Math.max(0, Math.min(1, m.optDouble("v", 1)));
                else if ("blend".equals(a) && i < layers.size()) layers.get(i).blend = Math.max(0, Math.min(BLEND_NAMES.length - 1, m.optInt("v")));
                else if ("dup".equals(a)) duplicate(i);
                else if ("merge".equals(a)) mergeDown(i);
                else if ("flip".equals(a)) flipLayer(i);
                break;
            }
        }
    }

    // ---------------- dibujar el conjunto ----------------

    private final Paint lp0 = new Paint(Paint.FILTER_BITMAP_FLAG), bufP = new Paint(Paint.FILTER_BITMAP_FLAG);

    /** Dibuja el lienzo completo (fondo blanco + capas + trazo en curso) en el rectángulo dst. */
    void draw(Canvas c, RectF dst) {
        Paint white = new Paint();
        white.setColor(Color.WHITE);
        int base = c.saveLayer(dst, null);
        c.drawRect(dst, white);
        for (int i = 0; i < layers.size(); i++) {
            Layer l = layers.get(i);
            if (!l.visible || l.alpha <= 0.01f) continue;
            lp0.setAlpha((int) (255 * l.alpha));
            lp0.setXfermode(l.blend == 0 ? null : new PorterDuffXfermode(BLENDS[l.blend]));
            if (stroking && i == sLayer) {
                // la capa con el trazo en curso se arma aparte y después se mezcla como la capa
                int save = c.saveLayer(dst, lp0);
                c.drawBitmap(l.bmp, null, dst, null);
                bufP.setXfermode(eraser(sType) ? new PorterDuffXfermode(PorterDuff.Mode.DST_OUT) : null);
                bufP.setAlpha((int) (255 * Math.max(0.05f, Math.min(1f, sOpacity))));
                c.drawBitmap(buf, null, dst, bufP);
                c.restoreToCount(save);
            } else c.drawBitmap(l.bmp, null, dst, lp0);
        }
        lp0.setXfermode(null);
        lp0.setAlpha(255);
        c.restoreToCount(base);
    }

    private static final PorterDuff.Mode[] BLENDS = {PorterDuff.Mode.SRC_OVER, PorterDuff.Mode.MULTIPLY, PorterDuff.Mode.SCREEN,
            PorterDuff.Mode.OVERLAY, PorterDuff.Mode.ADD};

    /** Color que se ve en un punto del lienzo (gotero). */
    int colorAt(int x, int y) {
        Bitmap one = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(one);
        c.translate(-Math.max(0, Math.min(W - 1, x)), -Math.max(0, Math.min(H - 1, y)));
        draw(c, new RectF(0, 0, W, H));
        int px = one.getPixel(0, 0);
        one.recycle();
        return px | 0xFF000000;
    }

    // ---------------- formas, relleno, importar, capas ----------------

    /** Línea, rectángulo u óvalo, trazados con el pincel elegido. */
    private void shape(JSONObject m) {
        JSONObject b = m.optJSONObject("b");
        JSONArray r = m.optJSONArray("r");
        if (b == null || r == null || r.length() < 4) return;
        float x1 = (float) r.optDouble(0), y1 = (float) r.optDouble(1), x2 = (float) r.optDouble(2), y2 = (float) r.optDouble(3);
        begin(m.optInt("l", cur), b.optInt("t"), (float) b.optDouble("s", 8), b.optInt("c", 0xFF000000), (float) b.optDouble("o", 1), b.optBoolean("m"));
        String k = m.optString("k");
        float p = 0.85f;
        if ("line".equals(k)) { point(x1, y1, p); point(x2, y2, p); }
        else if ("rect".equals(k)) { point(x1, y1, p); point(x2, y1, p); point(x2, y2, p); point(x1, y2, p); point(x1, y1, p); }
        else {
            float cx = (x1 + x2) / 2, cy = (y1 + y2) / 2, rx = Math.abs(x2 - x1) / 2, ry = Math.abs(y2 - y1) / 2;
            int n = Math.max(24, (int) ((rx + ry) / 3));
            for (int i = 0; i <= n; i++) {
                double t = i * Math.PI * 2 / n;
                point(cx + (float) (Math.cos(t) * rx), cy + (float) (Math.sin(t) * ry), p);
            }
        }
        end();
    }

    /** Balde: rellena la zona del mismo color (según lo que se ve) en la capa elegida. */
    private void fill(int layer, int x, int y, int color, int tol) {
        if (layer < 0 || layer >= layers.size() || x < 0 || y < 0 || x >= W || y >= H) return;
        Bitmap view = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        draw(new Canvas(view), new RectF(0, 0, W, H));
        int[] px = new int[W * H];
        view.getPixels(px, 0, W, 0, 0, W, H);
        view.recycle();
        int target = px[y * W + x];
        boolean[] mark = new boolean[W * H];
        int[] stack = new int[W * H];
        int sp = 0, minX = x, maxX = x, minY = y, maxY = y;
        stack[sp++] = y * W + x;
        mark[y * W + x] = true;
        while (sp > 0) {
            int i = stack[--sp], cx = i % W, cy = i / W;
            if (cx < minX) minX = cx; if (cx > maxX) maxX = cx; if (cy < minY) minY = cy; if (cy > maxY) maxY = cy;
            int[] nb = {i - 1, i + 1, i - W, i + W};
            for (int k = 0; k < 4; k++) {
                int j = nb[k];
                if (j < 0 || j >= W * H || mark[j]) continue;
                if (k == 0 && cx == 0 || k == 1 && cx == W - 1) continue;
                if (near(px[j], target, tol)) { mark[j] = true; stack[sp++] = j; }
            }
        }
        Rect r = new Rect(Math.max(0, minX - 1), Math.max(0, minY - 1), Math.min(W, maxX + 2), Math.min(H, maxY + 2));
        int rw = r.width(), rh = r.height();
        int[] out = new int[rw * rh];
        int col = color | 0xFF000000;
        for (int yy = 0; yy < rh; yy++) for (int xx = 0; xx < rw; xx++) {
            int gx = r.left + xx, gy = r.top + yy;
            // también el borde de 1 px, para que no quede una línea blanca contra el trazo
            boolean in = mark[gy * W + gx] || (gx > 0 && mark[gy * W + gx - 1]) || (gx < W - 1 && mark[gy * W + gx + 1])
                    || (gy > 0 && mark[(gy - 1) * W + gx]) || (gy < H - 1 && mark[(gy + 1) * W + gx]);
            out[yy * rw + xx] = in ? col : 0;
        }
        Bitmap patch = Bitmap.createBitmap(out, rw, rh, Bitmap.Config.ARGB_8888);
        Layer l = layers.get(layer);
        Step st = new Step();
        st.layer = layer; st.r = r;
        st.before = Bitmap.createBitmap(l.bmp, r.left, r.top, rw, rh);
        new Canvas(l.bmp).drawBitmap(patch, r.left, r.top, null);
        patch.recycle();
        st.after = Bitmap.createBitmap(l.bmp, r.left, r.top, rw, rh);
        push(st);
    }

    private static boolean near(int a, int b, int tol) {
        return Math.abs(((a >> 16) & 255) - ((b >> 16) & 255)) + Math.abs(((a >> 8) & 255) - ((b >> 8) & 255)) + Math.abs((a & 255) - (b & 255)) <= tol * 3;
    }

    private void importImage(String b64) {
        if (layers.size() >= MAX_LAYERS) return;
        try {
            byte[] d = android.util.Base64.decode(b64, android.util.Base64.DEFAULT);
            Bitmap b = BitmapFactory.decodeByteArray(d, 0, d.length);
            if (b == null) return;
            addLayer();
            Layer l = layers.get(cur);
            float k = Math.min(W / (float) b.getWidth(), H / (float) b.getHeight());
            float w = b.getWidth() * k, h = b.getHeight() * k;
            new Canvas(l.bmp).drawBitmap(b, null, new RectF((W - w) / 2, (H - h) / 2, (W + w) / 2, (H + h) / 2), new Paint(Paint.FILTER_BITMAP_FLAG));
            b.recycle();
        } catch (Exception ignored) { }
    }

    private void duplicate(int i) {
        if (layers.size() >= MAX_LAYERS || i < 0 || i >= layers.size()) return;
        Layer src = layers.get(i), l = new Layer();
        l.bmp = src.bmp.copy(Bitmap.Config.ARGB_8888, true);
        l.alpha = src.alpha; l.blend = src.blend;
        layers.add(i + 1, l);
        cur = i + 1;
        undo.clear(); redo.clear(); undoBytes = 0;
        renameLayers();
    }

    private void mergeDown(int i) {
        if (i <= 0 || i >= layers.size()) return;
        Layer top = layers.get(i), below = layers.get(i - 1);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        p.setAlpha((int) (255 * top.alpha));
        if (top.blend != 0) p.setXfermode(new PorterDuffXfermode(BLENDS[top.blend]));
        if (top.visible) new Canvas(below.bmp).drawBitmap(top.bmp, 0, 0, p);
        layers.remove(i).bmp.recycle();
        cur = i - 1;
        undo.clear(); redo.clear(); undoBytes = 0;
        renameLayers();
    }

    private void flipLayer(int i) {
        if (i < 0 || i >= layers.size()) return;
        Layer l = layers.get(i);
        Step st = new Step();
        st.layer = i; st.r = new Rect(0, 0, W, H);
        st.before = l.bmp.copy(Bitmap.Config.ARGB_8888, false);
        Bitmap f = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(f);
        c.scale(-1, 1, W / 2f, 0);
        c.drawBitmap(l.bmp, 0, 0, null);
        l.bmp.eraseColor(Color.TRANSPARENT);
        new Canvas(l.bmp).drawBitmap(f, 0, 0, null);
        f.recycle();
        st.after = l.bmp.copy(Bitmap.Config.ARGB_8888, false);
        push(st);
    }

    /** Dibujo nuevo: una capa en blanco. */
    void reset() {
        stroking = false;
        for (Layer l : layers) l.bmp.recycle();
        layers.clear();
        undo.clear(); redo.clear(); undoBytes = 0;
        cur = 0;
        addLayer();
    }

    /** Imagen plana (fondo blanco) para exportar. */
    Bitmap flatten() {
        end();
        Bitmap out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        draw(new Canvas(out), new RectF(0, 0, W, H));
        return out;
    }

    // ---------------- todo el dibujo (para guardar y para el otro teléfono) ----------------

    /** Copia del dibujo (en el hilo de la pantalla); se convierte a texto después, en otro hilo. */
    static final class Snap { final List<Bitmap> bmps = new ArrayList<>(); final List<Boolean> vis = new ArrayList<>(); final List<float[]> ab = new ArrayList<>(); int cur; }

    Snap snapshot() {
        Snap s = new Snap();
        for (Layer l : layers) { s.bmps.add(l.bmp.copy(Bitmap.Config.ARGB_8888, false)); s.vis.add(l.visible); s.ab.add(new float[]{l.alpha, l.blend}); }
        s.cur = cur;
        return s;
    }

    static JSONObject toJson(Snap s) {
        JSONObject o = new JSONObject();
        try {
            JSONArray arr = new JSONArray();
            for (int i = 0; i < s.bmps.size(); i++) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                s.bmps.get(i).compress(Bitmap.CompressFormat.PNG, 100, bos);
                s.bmps.get(i).recycle();
                JSONObject j = new JSONObject();
                j.put("png", android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP));
                j.put("vis", s.vis.get(i));
                j.put("a", (double) s.ab.get(i)[0]);
                j.put("bl", (int) s.ab.get(i)[1]);
                arr.put(j);
            }
            o.put("layers", arr);
            o.put("cur", s.cur);
        } catch (Exception ignored) { }
        return o;
    }

    void fromJson(JSONObject o) {
        JSONArray arr = o.optJSONArray("layers");
        if (arr == null || arr.length() == 0) return;
        stroking = false;
        for (Layer l : layers) l.bmp.recycle();
        layers.clear();
        undo.clear(); redo.clear(); undoBytes = 0;
        for (int i = 0; i < arr.length() && i < MAX_LAYERS; i++) {
            JSONObject j = arr.optJSONObject(i);
            Layer l = new Layer();
            l.bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
            if (j != null) {
                try {
                    byte[] d = android.util.Base64.decode(j.optString("png"), android.util.Base64.DEFAULT);
                    Bitmap b = BitmapFactory.decodeByteArray(d, 0, d.length);
                    if (b != null) { new Canvas(l.bmp).drawBitmap(b, null, new RectF(0, 0, W, H), null); b.recycle(); }
                } catch (Exception ignored) { }
                l.visible = j.optBoolean("vis", true);
                l.alpha = (float) j.optDouble("a", 1);
                l.blend = j.optInt("bl", 0);
            }
            layers.add(l);
        }
        cur = Math.max(0, Math.min(layers.size() - 1, o.optInt("cur", 0)));
        renameLayers();
    }
}
