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
    static final int PENCIL = 0, INK = 1, MARKER = 2, AIR = 3, ERASER = 4;
    static final String[] BRUSH_NAMES = {"Lápiz", "Tinta", "Rotulador", "Aerógrafo", "Borrador"};

    static final class Layer { Bitmap bmp; boolean visible = true; String name; }

    final List<Layer> layers = new ArrayList<>();
    int cur = 0;
    final Bitmap buf = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);   // trazo en curso
    private final Canvas bufC = new Canvas(buf);

    // trazo en curso
    boolean stroking;
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
            default: return Math.max(0.6f, sSize * 0.5f);   // rotulador y borrador: parejos
        }
    }

    private void stamp(float x, float y, float p) {
        float r = radius(p);
        if (sType == AIR) {
            soft.setColorFilter(new PorterDuffColorFilter(sColor | 0xFF000000, PorterDuff.Mode.SRC_IN));
            soft.setAlpha((int) (255 * (0.05f + 0.16f * p)));
            bufC.drawBitmap(tip(), null, new RectF(x - r, y - r, x + r, y + r), soft);
        } else {
            dab.setColor(sType == ERASER ? 0xFF000000 : (sColor | 0xFF000000));
            if (sType == PENCIL) dab.setAlpha((int) (255 * (0.55f + 0.45f * p)));
            bufC.drawCircle(x, y, r, dab);
        }
        int ir = (int) Math.ceil(r) + 2;
        dirty.union((int) x - ir, (int) y - ir, (int) x + ir, (int) y + ir);
    }

    private float spacing(float p) {
        float r = radius(p);
        return sType == AIR ? Math.max(1f, r * 0.18f) : Math.max(0.5f, r * 0.28f);
    }

    /** Empieza un trazo. */
    void begin(int layer, int type, float size, int color, float opacity) {
        if (stroking) end();
        stroking = true;
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
        comp.setXfermode(sType == ERASER ? new PorterDuffXfermode(PorterDuff.Mode.DST_OUT) : null);
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
                    begin(m.optInt("l", cur), b.optInt("t"), (float) b.optDouble("s", 8), b.optInt("c", 0xFF000000), (float) b.optDouble("o", 1));
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
        c.drawRect(dst, white);
        for (int i = 0; i < layers.size(); i++) {
            Layer l = layers.get(i);
            if (!l.visible) continue;
            if (stroking && i == sLayer) {
                int save = c.saveLayer(dst, null);
                c.drawBitmap(l.bmp, null, dst, lp0);
                bufP.setXfermode(sType == ERASER ? new PorterDuffXfermode(PorterDuff.Mode.DST_OUT) : null);
                bufP.setAlpha((int) (255 * Math.max(0.05f, Math.min(1f, sOpacity))));
                c.drawBitmap(buf, null, dst, bufP);
                c.restoreToCount(save);
            } else c.drawBitmap(l.bmp, null, dst, lp0);
        }
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
    static final class Snap { final List<Bitmap> bmps = new ArrayList<>(); final List<Boolean> vis = new ArrayList<>(); int cur; }

    Snap snapshot() {
        Snap s = new Snap();
        for (Layer l : layers) { s.bmps.add(l.bmp.copy(Bitmap.Config.ARGB_8888, false)); s.vis.add(l.visible); }
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
            }
            layers.add(l);
        }
        cur = Math.max(0, Math.min(layers.size() - 1, o.optInt("cur", 0)));
        renameLayers();
    }
}
