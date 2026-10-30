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
 *   undo · redo · clear · layer {a: add|del|sel|vis|up|down, i} · new {w, h}
 *   sel {k: wand|lasso, …} · selClear · selInv · selDel {l}   (selección: borrar partes y dejarlas transparentes)
 * El tamaño del dibujo se elige al empezar uno nuevo. "Mezcla" y "Difuminar" pintan directo en la capa (toman
 * los colores que van tocando); el resto pasa por el búfer.
 * Un trazo se dibuja primero en un búfer (con opacidad completa) y al soltar se pega en la capa con la
 * opacidad del pincel: así el rotulador no se oscurece donde se cruza consigo mismo.
 */
final class DrawDoc {

    int W = 1600, H = 900;
    static final int MAX_SIDE = 3000, MAX_AREA = 4_200_000;
    static final int PENCIL = 0, INK = 1, MARKER = 2, AIR = 3, ERASER = 4, BRUSH = 5, CHALK = 6, CALLI = 7, SOFT_ERASER = 8,
            MIX = 9, SMUDGE = 10, VALUE = 11;
    static final String[] BRUSH_NAMES = {"Lápiz", "Tinta", "Rotulador", "Aerógrafo", "Borrador", "Pincel", "Tiza", "Caligrafía", "Borrador suave",
            "Mezcla", "Difuminar", "Valor de trazo"};

    /** Cuántas capas caben según el tamaño (unos 100 MB para las capas). */
    /** Hasta 30 capas (menos si el dibujo es enorme: cada capa ocupa ancho × alto × 4 bytes). */
    int maxLayers() { return Math.max(3, Math.min(30, (int) (420L * 1024 * 1024 / ((long) W * H * 4)))); }

    /** Pinceles que pintan directo en la capa (para poder tomar los colores que tocan). */
    static boolean direct(int t) { return t == MIX || t == SMUDGE; }
    static final String[] BLEND_NAMES = {"Normal", "Multiplicar", "Trama", "Superponer", "Sumar"};

    static boolean eraser(int t) { return t == ERASER || t == SOFT_ERASER; }

    static final class Layer {
        Bitmap bmp; boolean visible = true; String name; float alpha = 1f; int blend;
        /** Formas vectoriales (pluma): van encima de lo pintado en la capa y siempre se pueden editar. */
        final List<VShape> shapes = new ArrayList<>();
    }

    /**
     * Forma de la pluma (como FreeHand): nodos con sus dos manillas (curvas Bézier), abierta o cerrada, con contorno
     * y relleno. pts: por nodo 6 números (x, y, entraX, entraY, saleX, saleY) en píxeles del dibujo.
     */
    static final class VShape {
        float[] pts = new float[0];
        boolean closed, strokeOn = true, fillOn;
        int stroke = 0xFF000000, fill = 0xFF2FA866;
        float width = 6;

        int nodes() { return pts.length / 6; }

        // ---- texto (herramienta Texto): un VShape con texto va en su propia capa y siempre se puede editar.
        // Posición = primer punto (esquina de arriba a la izquierda); color = fill.
        String text, font = "sans-serif";
        float tsize = 60;
        boolean bold, italic;

        boolean isText() { return text != null; }

        Paint textPaint(Paint p) {
            p.setStyle(Paint.Style.FILL);
            p.setTextAlign(Paint.Align.LEFT);
            p.setTypeface(android.graphics.Typeface.create(font, (bold ? android.graphics.Typeface.BOLD : 0) | (italic ? android.graphics.Typeface.ITALIC : 0)));
            p.setTextSize(Math.max(4, tsize));
            p.setColor(fill);
            return p;
        }

        /** Rectángulo que ocupa el texto (en píxeles del dibujo). */
        RectF textBounds(Paint p) {
            textPaint(p);
            String[] ls = text.split("\n", -1);
            float w = 0;
            for (String l : ls) w = Math.max(w, p.measureText(l));
            float x = pts.length >= 2 ? pts[0] : 0, y = pts.length >= 2 ? pts[1] : 0;
            return new RectF(x, y, x + Math.max(w, tsize * 0.3f), y + ls.length * tsize * 1.25f);
        }

        VShape copy() {
            VShape v = new VShape();
            v.pts = pts.clone(); v.closed = closed; v.strokeOn = strokeOn; v.fillOn = fillOn;
            v.stroke = stroke; v.fill = fill; v.width = width;
            v.text = text; v.font = font; v.tsize = tsize; v.bold = bold; v.italic = italic;
            return v;
        }

        void addNode(float x, float y) {
            float[] n = java.util.Arrays.copyOf(pts, pts.length + 6);
            int k = pts.length;
            n[k] = x; n[k + 1] = y; n[k + 2] = x; n[k + 3] = y; n[k + 4] = x; n[k + 5] = y;
            pts = n;
        }

        android.graphics.Path path() {
            android.graphics.Path p = new android.graphics.Path();
            int n = nodes();
            if (n == 0) return p;
            p.moveTo(pts[0], pts[1]);
            for (int i = 1; i < n; i++) {
                int a = (i - 1) * 6, b = i * 6;
                p.cubicTo(pts[a + 4], pts[a + 5], pts[b + 2], pts[b + 3], pts[b], pts[b + 1]);
            }
            if (closed && n > 1) {
                int a = (n - 1) * 6;
                p.cubicTo(pts[a + 4], pts[a + 5], pts[2], pts[3], pts[0], pts[1]);
                p.close();
            }
            return p;
        }

        void draw(Canvas c, Paint paint) {
            if (text != null) {   // texto: renglón por renglón
                textPaint(paint);
                String[] ls = text.split("\n", -1);
                float x = pts.length >= 2 ? pts[0] : 0, y = pts.length >= 2 ? pts[1] : 0;
                for (int i = 0; i < ls.length; i++) c.drawText(ls[i], x, y + tsize * 0.95f + i * tsize * 1.25f, paint);
                return;
            }
            android.graphics.Path p = path();
            if (fillOn && closed) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(fill);
                c.drawPath(p, paint);
            }
            if (strokeOn && width > 0) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(width);
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setStrokeJoin(Paint.Join.ROUND);
                paint.setColor(stroke);
                c.drawPath(p, paint);
            }
        }

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                JSONArray a = new JSONArray();
                for (float f : pts) a.put(Math.round(f * 10) / 10.0);
                o.put("p", a); o.put("cl", closed); o.put("so", strokeOn); o.put("fo", fillOn);
                o.put("sc", stroke); o.put("fc", fill); o.put("w", (double) width);
                if (text != null) { o.put("tx", text); o.put("tf", font); o.put("ts", (double) tsize); o.put("tb", bold); o.put("ti", italic); }
            } catch (Exception ignored) { }
            return o;
        }

        static VShape fromJson(JSONObject o) {
            VShape v = new VShape();
            if (o == null) return v;
            JSONArray a = o.optJSONArray("p");
            int n = a == null ? 0 : a.length() / 6 * 6;
            v.pts = new float[n];
            for (int i = 0; i < n; i++) v.pts[i] = (float) a.optDouble(i);
            v.closed = o.optBoolean("cl"); v.strokeOn = o.optBoolean("so", true); v.fillOn = o.optBoolean("fo");
            v.stroke = o.optInt("sc", 0xFF000000); v.fill = o.optInt("fc", 0xFF2FA866); v.width = (float) o.optDouble("w", 6);
            if (o.has("tx")) {
                v.text = o.optString("tx"); v.font = o.optString("tf", "sans-serif"); v.tsize = (float) o.optDouble("ts", 60);
                v.bold = o.optBoolean("tb"); v.italic = o.optBoolean("ti");
            }
            return v;
        }
    }

    private final Paint vecP = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** Forma que se está editando en el lienzo (se dibuja aparte, encima): no se dibuja dos veces. */
    int hideL = -1, hideI = -1;

    /** Dibuja las formas de una capa (en píxeles del dibujo). */
    void drawShapes(Canvas c, Layer l, int li) {
        for (int k = 0; k < l.shapes.size(); k++) {
            if (li == hideL && k == hideI) continue;
            l.shapes.get(k).draw(c, vecP);
        }
    }

    static String shapesJson(Layer l) {
        JSONArray a = new JSONArray();
        for (VShape v : l.shapes) a.put(v.toJson());
        return a.toString();
    }

    static void shapesFrom(Layer l, String json) {
        l.shapes.clear();
        if (json == null || json.isEmpty()) return;
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) l.shapes.add(VShape.fromJson(a.optJSONObject(i)));
        } catch (Exception ignored) { }
    }

    /** Pasa las formas de la capa a pintura (al combinar capas). */
    private void rasterize(Layer l) {
        if (l.shapes.isEmpty()) return;
        drawShapes(new Canvas(l.bmp), l, -2);
        l.shapes.clear();
    }

    /** Lo que hay en una capa (pintura + formas), para el balde. */
    private Bitmap layerImage(int i) {
        Layer l = layers.get(i);
        Bitmap b = l.bmp.copy(Bitmap.Config.ARGB_8888, true);
        if (!l.shapes.isEmpty()) drawShapes(new Canvas(b), l, -2);
        return b;
    }

    /** Pluma: agregar/cambiar (i = -1: nueva) o borrar una forma. Se puede deshacer. */
    private void vec(JSONObject m) {
        int li = m.optInt("l", cur);
        if (li < 0 || li >= layers.size()) return;
        Layer l = layers.get(li);
        Step st = new Step();
        st.layer = li;
        st.r = new Rect(0, 0, 1, 1);
        st.vb = shapesJson(l);
        String a = m.optString("a");
        int i = m.optInt("i", -1);
        if ("del".equals(a)) { if (i >= 0 && i < l.shapes.size()) l.shapes.remove(i); else return; }
        else {
            VShape v = VShape.fromJson(m.optJSONObject("s"));
            if (v.nodes() < (v.isText() ? 1 : 2)) return;
            if (i >= 0 && i < l.shapes.size()) l.shapes.set(i, v); else l.shapes.add(v);
        }
        st.va = shapesJson(l);
        push(st);
        renameLayers();   // una capa de texto se llama como su texto
    }

    final List<Layer> layers = new ArrayList<>();
    int cur = 0;
    Bitmap buf = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);   // trazo en curso
    private Canvas bufC = new Canvas(buf);

    // pinceles directos: la capa antes del trazo (para deshacer), el color que lleva "Mezcla" y la muestra de "Difuminar"
    private Bitmap before;
    private Canvas layC;
    private final float[][] mixCol = new float[2][];
    private final Bitmap[] smPatch = new Bitmap[2];
    private Bitmap smTmp, smTip;
    private int smR;

    // transformar: vista previa de la capa movida/escalada/girada (aún sin aplicar)
    int xfLayer = -1;
    android.graphics.Matrix xfM;

    // selección (máscara: negro opaco = seleccionado)
    Bitmap sel;
    boolean hasSel;

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
    private static final class Step { int layer; Rect r; Bitmap before, after; String vb, va; }   // vb/va: formas antes y después
    private final List<Step> undo = new ArrayList<>(), redo = new ArrayList<>();
    private long undoBytes;

    DrawDoc() { addLayer(); }

    // ---------------- capas ----------------

    void addLayer() {
        if (layers.size() >= maxLayers()) return;
        Layer l = new Layer();
        l.bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        l.name = "Capa " + (layers.size() + 1);
        int at = cur + (layers.isEmpty() ? 0 : 1);
        shiftSteps(at);
        layers.add(at, l);
        cur = layers.indexOf(l);
        renameLayers();
    }

    private void renameLayers() {
        for (int i = 0; i < layers.size(); i++) {
            Layer l = layers.get(i);
            String t = l.shapes.size() == 1 && l.shapes.get(0).isText() ? l.shapes.get(0).text.replace('\n', ' ').trim() : null;
            l.name = t != null ? "T  " + (t.length() > 14 ? t.substring(0, 14) + "…" : t) : "Capa " + (i + 1);
        }
    }

    /** Al meter una capa en la posición idx, los pasos de deshacer de las capas de arriba se corren uno. */
    private void shiftSteps(int idx) {
        for (Step s : undo) if (s.layer >= idx) s.layer++;
        for (Step s : redo) if (s.layer >= idx) s.layer++;
    }

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
        if (sType == SMUDGE) return Math.max(2f, smR > 0 ? smR : sSize * 0.6f);
        return radiusFor(sType, sSize, p);
    }

    /** El mismo radio, para un pincel y tamaño cualquiera (lo usa el círculo del lápiz). */
    static float radiusFor(int sType, float sSize, float p) {
        switch (sType) {
            case PENCIL: return Math.max(0.6f, sSize * 0.5f * (0.45f + 0.55f * p));
            case INK: return Math.max(0.6f, sSize * 0.5f * (0.15f + 0.85f * p));
            case AIR: return Math.max(2f, sSize * (0.7f + 0.3f * p));
            case BRUSH: return Math.max(1f, sSize * 0.6f * (0.4f + 0.6f * p));
            case CHALK: return Math.max(1.5f, sSize * 0.55f * (0.6f + 0.4f * p));
            case CALLI: return Math.max(1f, sSize * 0.6f * (0.5f + 0.5f * p));
            case SOFT_ERASER: return Math.max(2f, sSize * 0.8f);
            case MIX: return Math.max(1.5f, sSize * 0.6f * (0.5f + 0.5f * p));
            case SMUDGE: return Math.max(2f, sSize * 0.6f);
            case VALUE: return Math.max(0.4f, sSize * 0.5f * p);   // p = el grosor elegido en vivo con la barra
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
            case MIX: mixDab(x, y, r, p, mirrored ? 1 : 0); break;
            case SMUDGE: smudgeDab(x, y, r, p, mirrored ? 1 : 0); break;
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
        if (sType == MIX) return Math.max(0.8f, r * 0.15f);
        if (sType == SMUDGE) return Math.max(1f, r * 0.12f);
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
        if (direct(type)) {
            Bitmap lb = layers.get(sLayer).bmp;
            before = lb.copy(Bitmap.Config.ARGB_8888, false);
            layC = new Canvas(lb);
            mixCol[0] = mixCol[1] = null;
            smR = type == SMUDGE ? Math.max(3, Math.round(size * 0.6f)) : 0;
            for (int i = 0; i < 2; i++) { if (smPatch[i] != null) smPatch[i].recycle(); smPatch[i] = null; }
        }
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
        if (direct(sType)) {
            Layer l = layers.get(Math.min(sLayer, layers.size() - 1));
            if (before != null && r.intersect(0, 0, W, H) && !r.isEmpty()) {
                Step st = new Step();
                st.layer = layers.indexOf(l);
                st.r = r;
                st.before = Bitmap.createBitmap(before, r.left, r.top, r.width(), r.height());
                st.after = Bitmap.createBitmap(l.bmp, r.left, r.top, r.width(), r.height());
                push(st);
            }
            dropDirect();
            return;
        }
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

    private void dropDirect() {
        if (before != null) before.recycle();
        before = null;
        layC = null;
        for (int i = 0; i < 2; i++) { if (smPatch[i] != null) smPatch[i].recycle(); smPatch[i] = null; }
    }

    /** Se cancela el trazo en curso (por ejemplo, se pusieron dos dedos para hacer zoom). */
    void cancel() {
        if (stroking && direct(sType) && before != null && sLayer < layers.size()) {
            Paint p = new Paint();
            p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC));
            new Canvas(layers.get(sLayer).bmp).drawBitmap(before, 0, 0, p);
        }
        stroking = false;
        if (direct(sType)) dropDirect();
        buf.eraseColor(Color.TRANSPARENT);
    }

    // ---------------- mezcla y difuminado ----------------

    /** "Mezcla": pinta con el color que lleva, y ese color se va mezclando con lo que toca (como pintura húmeda). */
    private void mixDab(float x, float y, float r, float p, int k) {
        Bitmap lb = layers.get(sLayer).bmp;
        float[] c = mixCol[k];
        if (c == null) c = mixCol[k] = new float[]{Color.red(sColor), Color.green(sColor), Color.blue(sColor)};
        // muestra de lo que hay debajo (5 puntos dentro de la pincelada)
        float sr = 0, sg = 0, sb = 0, sa = 0;
        float[][] off = {{0, 0}, {-0.5f, 0}, {0.5f, 0}, {0, -0.5f}, {0, 0.5f}};
        for (float[] o : off) {
            int px = Math.round(x + o[0] * r), py = Math.round(y + o[1] * r);
            if (px < 0 || py < 0 || px >= W || py >= H) continue;
            int v = lb.getPixel(px, py);
            float a = Color.alpha(v) / 255f;
            sr += Color.red(v) * a; sg += Color.green(v) * a; sb += Color.blue(v) * a; sa += a;
        }
        if (sa > 0.01f) {
            float k2 = 0.32f * (sa / off.length);   // cuánto color toma: más si debajo hay pintura
            c[0] += (sr / sa - c[0]) * k2; c[1] += (sg / sa - c[1]) * k2; c[2] += (sb / sa - c[2]) * k2;
        }
        int col = Color.rgb(Math.round(c[0]), Math.round(c[1]), Math.round(c[2]));
        soft.setColorFilter(new PorterDuffColorFilter(col, PorterDuff.Mode.SRC_IN));
        soft.setAlpha((int) (255 * Math.max(0.05f, Math.min(1f, (0.22f + 0.45f * p) * sOpacity))));
        layC.drawBitmap(tip(), null, new RectF(x - r, y - r, x + r, y + r), soft);
    }

    private final Paint smOut = new Paint(Paint.FILTER_BITMAP_FLAG), smAdd = new Paint(Paint.FILTER_BITMAP_FLAG), smIn = new Paint();

    /** "Difuminar": arrastra la pintura que hay (sin color propio), como pasar el dedo. */
    private void smudgeDab(float x, float y, float r, float p, int k) {
        Bitmap lb = layers.get(sLayer).bmp;
        int R = Math.max(2, smR), S = R * 2;
        int lx0 = Math.round(x) - R, ly0 = Math.round(y) - R;
        Rect src = new Rect(lx0, ly0, lx0 + S, ly0 + S);
        Rect all = new Rect(0, 0, S, S);
        if (smTip == null || smTip.getWidth() != S) {
            if (smTip != null) smTip.recycle();
            smTip = Bitmap.createScaledBitmap(tip(), S, S, true);
            if (smTmp != null) smTmp.recycle();
            smTmp = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888);
        }
        if (smPatch[k] == null) {   // primera pincelada: toma lo que hay debajo
            smPatch[k] = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888);
            new Canvas(smPatch[k]).drawBitmap(lb, src, all, null);
            return;
        }
        float a = Math.max(0.05f, Math.min(1f, (0.45f + 0.45f * p) * sOpacity));
        // capa = capa·(1 − m·a) + muestra·m·a   (m = la punta suave)
        smOut.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
        smOut.setAlpha((int) (255 * a));
        layC.drawBitmap(smTip, null, src, smOut);
        smTmp.eraseColor(Color.TRANSPARENT);
        Canvas tc = new Canvas(smTmp);
        tc.drawBitmap(smPatch[k], 0, 0, null);
        smIn.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        tc.drawBitmap(smTip, 0, 0, smIn);
        smAdd.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
        smAdd.setAlpha((int) (255 * a));
        layC.drawBitmap(smTmp, null, src, smAdd);
        // la muestra se va pareciendo a lo que hay ahora debajo (la pintura se "gasta")
        Canvas pc = new Canvas(smPatch[k]);
        pc.drawColor(Color.argb((int) (255 * 0.8f), 0, 0, 0), PorterDuff.Mode.DST_IN);
        smAdd.setAlpha((int) (255 * 0.2f));
        pc.drawBitmap(lb, src, all, smAdd);
    }

    private void push(Step st) {
        undo.add(st);
        undoBytes += (long) st.r.width() * st.r.height() * 8;
        for (Step s : redo) free(s);
        redo.clear();
        while (undo.size() > 40 || (undoBytes > 90L * 1024 * 1024 && undo.size() > 1)) {
            Step s = undo.remove(0);
            undoBytes -= (long) s.r.width() * s.r.height() * 8;
            free(s);
        }
    }

    private static void free(Step s) { if (s.before != null) s.before.recycle(); if (s.after != null) s.after.recycle(); }

    boolean undo() {
        if (undo.isEmpty()) return false;
        Step s = undo.remove(undo.size() - 1);
        undoBytes -= (long) s.r.width() * s.r.height() * 8;
        paste(s, s.before);
        if (s.vb != null && s.layer < layers.size()) shapesFrom(layers.get(s.layer), s.vb);
        redo.add(s);
        return true;
    }

    boolean redo() {
        if (redo.isEmpty()) return false;
        Step s = redo.remove(redo.size() - 1);
        paste(s, s.after);
        if (s.va != null && s.layer < layers.size()) shapesFrom(layers.get(s.layer), s.va);
        undo.add(s);
        undoBytes += (long) s.r.width() * s.r.height() * 8;
        return true;
    }

    private void paste(Step s, Bitmap b) {
        if (b == null || s.layer >= layers.size()) return;
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
        st.vb = shapesJson(l);
        l.bmp.eraseColor(Color.TRANSPARENT);
        l.shapes.clear();
        st.after = l.bmp.copy(Bitmap.Config.ARGB_8888, false);
        st.va = "[]";
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
            case "cancel": cancel(); break;
            case "undo": end(); undo(); break;
            case "redo": end(); redo(); break;
            case "clear": end(); clearLayer(m.optInt("i", cur)); break;
            case "shape": end(); shape(m); break;
            case "fill": end(); fill(m.optInt("l", cur), m.optInt("x"), m.optInt("y"), m.optInt("c", 0xFF000000), m.optInt("tol", 40),
                    m.optInt("gap", 0), m.optBoolean("all", !m.has("gap"))); break;
            case "vec": end(); vec(m); break;
            case "import": end(); importImage(m.optString("png")); break;
            case "new": end(); reset(m.optInt("w", W), m.optInt("h", H)); break;
            case "sel": end(); select(m); break;
            case "xfPrev": {
                JSONArray a = m.optJSONArray("m");
                if (a == null || a.length() < 9) { xfLayer = -1; xfM = null; break; }
                xfLayer = m.optInt("l", cur);
                xfM = matrix(a);
                break;
            }
            case "xform": {
                end();
                JSONArray a = m.optJSONArray("m");
                xfLayer = -1; xfM = null;
                if (a != null && a.length() >= 9) transformLayer(m.optInt("l", cur), matrix(a));
                break;
            }
            case "selClear": clearSel(); break;
            case "selInv": invertSel(); break;
            case "selDel": end(); deleteSel(m.optInt("l", cur)); break;
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
                else if ("mergeVis".equals(a)) mergeSet(null);
                else if ("mergeSet".equals(a)) mergeSet(m.optJSONArray("ids"));
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
            if (stroking && i == sLayer && !direct(sType)) {
                // la capa con el trazo en curso se arma aparte y después se mezcla como la capa
                int save = c.saveLayer(dst, lp0);
                c.drawBitmap(l.bmp, null, dst, null);
                bufP.setXfermode(eraser(sType) ? new PorterDuffXfermode(PorterDuff.Mode.DST_OUT) : null);
                bufP.setAlpha((int) (255 * Math.max(0.05f, Math.min(1f, sOpacity))));
                c.drawBitmap(buf, null, dst, bufP);
                if (!l.shapes.isEmpty()) { c.save(); c.translate(dst.left, dst.top); c.scale(dst.width() / W, dst.height() / H); drawShapes(c, l, i); c.restore(); }
                c.restoreToCount(save);
            } else if (i == xfLayer && xfM != null) {
                android.graphics.Matrix mm = new android.graphics.Matrix(xfM);
                mm.postScale(dst.width() / W, dst.height() / H);
                mm.postTranslate(dst.left, dst.top);
                lp0.setAntiAlias(true);
                c.drawBitmap(l.bmp, mm, lp0);
                if (!l.shapes.isEmpty()) { c.save(); c.concat(mm); drawShapes(c, l, i); c.restore(); }
            } else if (!l.shapes.isEmpty()) {
                // capa con formas de pluma: pintura + formas, y la capa se mezcla entera
                int save = c.saveLayer(dst, lp0);
                c.drawBitmap(l.bmp, null, dst, null);
                c.save();
                c.translate(dst.left, dst.top);
                c.scale(dst.width() / W, dst.height() / H);
                drawShapes(c, l, i);
                c.restore();
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

    /**
     * Balde: rellena la zona parecida al punto tocado.
     *  - all = false: mira SOLO lo que hay en la capa (así una foto en otra capa no ensucia el relleno).
     *  - tol: tolerancia de color (0..120).
     *  - gap: cierra huecos de hasta ese grosor (px) en el contorno, para formas que no quedaron bien cerradas.
     */
    private void fill(int layer, int x, int y, int color, int tol, int gap, boolean all) {
        if (layer < 0 || layer >= layers.size() || x < 0 || y < 0 || x >= W || y >= H) return;
        Bitmap view;
        if (all) { view = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888); draw(new Canvas(view), new RectF(0, 0, W, H)); }
        else view = layerImage(layer);
        int[] px = new int[W * H];
        view.getPixels(px, 0, W, 0, 0, W, H);
        view.recycle();
        int N = W * H, seed = y * W + x, target = px[seed];
        boolean[] ok = new boolean[N];
        for (int i = 0; i < N; i++) ok[i] = all ? near(px[i], target, tol) : nearA(px[i], target, tol);
        px = null;
        boolean[] allowed = ok;
        if (gap > 0) {
            // el contorno se engrosa "gap" px para tapar los huecos; después el relleno vuelve a llegar hasta la línea
            boolean[] wall = new boolean[N];
            for (int i = 0; i < N; i++) wall[i] = !ok[i];
            wall = grow(wall, gap);
            boolean[] a2 = new boolean[N];
            for (int i = 0; i < N; i++) a2[i] = ok[i] && !wall[i];
            if (a2[seed]) allowed = a2;   // si se tocó justo junto a la línea, sin cerrar huecos
        }
        boolean[] mark = new boolean[N];
        int[] stack = new int[N];
        int sp = 0;
        if (!allowed[seed]) return;
        stack[sp++] = seed;
        mark[seed] = true;
        while (sp > 0) {
            int i = stack[--sp], cx = i % W;
            if (cx > 0 && !mark[i - 1] && allowed[i - 1]) { mark[i - 1] = true; stack[sp++] = i - 1; }
            if (cx < W - 1 && !mark[i + 1] && allowed[i + 1]) { mark[i + 1] = true; stack[sp++] = i + 1; }
            if (i >= W && !mark[i - W] && allowed[i - W]) { mark[i - W] = true; stack[sp++] = i - W; }
            if (i + W < N && !mark[i + W] && allowed[i + W]) { mark[i + W] = true; stack[sp++] = i + W; }
        }
        stack = null;
        if (allowed != ok) {   // el relleno crece de vuelta hasta la línea (sin pasar por los huecos tapados)
            boolean[] g = grow(mark, gap + 1);
            for (int i = 0; i < N; i++) mark[i] = mark[i] || (g[i] && ok[i]);
        }
        int minX = W, maxX = -1, minY = H, maxY = -1;
        for (int i = 0; i < N; i++) if (mark[i]) { int cx = i % W, cy = i / W; if (cx < minX) minX = cx; if (cx > maxX) maxX = cx; if (cy < minY) minY = cy; if (cy > maxY) maxY = cy; }
        if (maxX < 0) return;
        Rect r = new Rect(Math.max(0, minX - 1), Math.max(0, minY - 1), Math.min(W, maxX + 2), Math.min(H, maxY + 2));
        int rw = r.width(), rh = r.height();
        int[] out = new int[rw * rh], ring = new int[rw * rh];
        int col = color | 0xFF000000;
        for (int yy = 0; yy < rh; yy++) for (int xx = 0; xx < rw; xx++) {
            int gx = r.left + xx, gy = r.top + yy;
            if (mark[gy * W + gx]) { out[yy * rw + xx] = col; continue; }   // la zona: se REEMPLAZA (así se puede volver a pintar de otro color)
            // el borde de 1 px va por debajo del trazo, para que no quede una línea blanca contra él
            boolean in = (gx > 0 && mark[gy * W + gx - 1]) || (gx < W - 1 && mark[gy * W + gx + 1])
                    || (gy > 0 && mark[(gy - 1) * W + gx]) || (gy < H - 1 && mark[(gy + 1) * W + gx]);
            ring[yy * rw + xx] = in ? col : 0;
        }
        Bitmap patch = Bitmap.createBitmap(out, rw, rh, Bitmap.Config.ARGB_8888), edge = Bitmap.createBitmap(ring, rw, rh, Bitmap.Config.ARGB_8888);
        Layer l = layers.get(layer);
        Step st = new Step();
        st.layer = layer; st.r = r;
        st.before = Bitmap.createBitmap(l.bmp, r.left, r.top, rw, rh);
        Canvas lc = new Canvas(l.bmp);
        lc.drawBitmap(patch, r.left, r.top, null);
        Paint under = new Paint();
        under.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OVER));
        lc.drawBitmap(edge, r.left, r.top, under);
        patch.recycle();
        edge.recycle();
        st.after = Bitmap.createBitmap(l.bmp, r.left, r.top, rw, rh);
        push(st);
    }

    /** Engrosa la zona marcada "r" píxeles (cuadrado, en dos pasadas). */
    private boolean[] grow(boolean[] in, int r) {
        int N = W * H;
        boolean[] h = new boolean[N], v = new boolean[N];
        for (int y = 0; y < H; y++) {
            int base = y * W, last = -100000;
            for (int x = 0; x < W; x++) { if (in[base + x]) last = x; if (x - last <= r) h[base + x] = true; }
            last = 100000;
            for (int x = W - 1; x >= 0; x--) { if (in[base + x]) last = x; if (last - x <= r) h[base + x] = true; }
        }
        for (int x = 0; x < W; x++) {
            int last = -100000;
            for (int y = 0; y < H; y++) { if (h[y * W + x]) last = y; if (y - last <= r) v[y * W + x] = true; }
            last = 100000;
            for (int y = H - 1; y >= 0; y--) { if (h[y * W + x]) last = y; if (last - y <= r) v[y * W + x] = true; }
        }
        return v;
    }

    private static boolean near(int a, int b, int tol) {
        return Math.abs(((a >> 16) & 255) - ((b >> 16) & 255)) + Math.abs(((a >> 8) & 255) - ((b >> 8) & 255)) + Math.abs((a & 255) - (b & 255)) <= tol * 3;
    }

    private void importImage(String b64) {
        if (layers.size() >= maxLayers()) return;
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
        if (layers.size() >= maxLayers() || i < 0 || i >= layers.size()) return;
        Layer src = layers.get(i), l = new Layer();
        l.bmp = src.bmp.copy(Bitmap.Config.ARGB_8888, true);
        l.alpha = src.alpha; l.blend = src.blend;
        for (VShape v : src.shapes) l.shapes.add(v.copy());
        layers.add(i + 1, l);
        cur = i + 1;
        undo.clear(); redo.clear(); undoBytes = 0;
        renameLayers();
    }

    private void mergeDown(int i) {
        if (i <= 0 || i >= layers.size()) return;
        Layer top = layers.get(i), below = layers.get(i - 1);
        rasterize(below);
        rasterize(top);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        p.setAlpha((int) (255 * top.alpha));
        if (top.blend != 0) p.setXfermode(new PorterDuffXfermode(BLENDS[top.blend]));
        if (top.visible) new Canvas(below.bmp).drawBitmap(top.bmp, 0, 0, p);
        layers.remove(i).bmp.recycle();
        cur = i - 1;
        undo.clear(); redo.clear(); undoBytes = 0;
        renameLayers();
    }

    /**
     * Combina varias capas en una (la de más abajo de las elegidas), respetando su opacidad y su mezcla.
     * ids = null: todas las visibles.
     */
    private void mergeSet(JSONArray ids) {
        List<Integer> pick = new ArrayList<>();
        if (ids == null) { for (int i = 0; i < layers.size(); i++) if (layers.get(i).visible) pick.add(i); }
        else for (int k = 0; k < ids.length(); k++) { int i = ids.optInt(k, -1); if (i >= 0 && i < layers.size() && !pick.contains(i)) pick.add(i); }
        if (pick.size() < 2) return;
        java.util.Collections.sort(pick);
        Layer base = layers.get(pick.get(0));
        rasterize(base);
        // la de abajo con su opacidad ya puesta (queda al 100 %)
        if (base.alpha < 0.999f) {
            Bitmap t = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
            Paint pa = new Paint();
            pa.setAlpha((int) (255 * base.alpha));
            new Canvas(t).drawBitmap(base.bmp, 0, 0, pa);
            base.bmp.recycle();
            base.bmp = t;
            base.alpha = 1f;
        }
        Canvas c = new Canvas(base.bmp);
        for (int k = 1; k < pick.size(); k++) {
            Layer l = layers.get(pick.get(k));
            rasterize(l);
            if (!l.visible) continue;
            Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
            p.setAlpha((int) (255 * l.alpha));
            if (l.blend != 0) p.setXfermode(new PorterDuffXfermode(BLENDS[l.blend]));
            c.drawBitmap(l.bmp, 0, 0, p);
        }
        base.visible = true;
        for (int k = pick.size() - 1; k >= 1; k--) layers.remove((int) pick.get(k)).bmp.recycle();
        cur = layers.indexOf(base);
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
        st.vb = shapesJson(l);
        for (VShape v : l.shapes) {   // las formas también se voltean (el texto cambia de lado, pero se sigue leyendo)
            if (v.isText()) { RectF tb = v.textBounds(vecP); float w = tb.width(); v.pts[0] = W - v.pts[0] - w; for (int k = 2; k < v.pts.length; k += 2) v.pts[k] = v.pts[0]; continue; }
            for (int k = 0; k < v.pts.length; k += 2) v.pts[k] = W - v.pts[k];
        }
        st.va = shapesJson(l);
        st.after = l.bmp.copy(Bitmap.Config.ARGB_8888, false);
        push(st);
    }

    static android.graphics.Matrix matrix(JSONArray a) {
        float[] v = new float[9];
        for (int i = 0; i < 9; i++) v[i] = (float) a.optDouble(i);
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.setValues(v);
        return m;
    }

    /** Transformar: la capa se mueve, escala y gira según la matriz (se puede deshacer). */
    private void transformLayer(int i, android.graphics.Matrix mx) {
        if (i < 0 || i >= layers.size()) return;
        Layer l = layers.get(i);
        Step st = new Step();
        st.layer = i;
        st.r = new Rect(0, 0, W, H);
        st.before = l.bmp.copy(Bitmap.Config.ARGB_8888, false);
        Bitmap nb = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        new Canvas(nb).drawBitmap(l.bmp, mx, new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG));
        l.bmp.eraseColor(Color.TRANSPARENT);
        new Canvas(l.bmp).drawBitmap(nb, 0, 0, null);
        nb.recycle();
        st.vb = shapesJson(l);
        for (VShape v : l.shapes) { mx.mapPoints(v.pts); v.width *= mx.mapRadius(1f); v.tsize *= mx.mapRadius(1f); }   // las formas se transforman igual
        st.va = shapesJson(l);
        st.after = l.bmp.copy(Bitmap.Config.ARGB_8888, false);
        push(st);
    }

    /** Dibujo nuevo, del tamaño pedido: una capa en blanco. */
    void reset(int w, int h) {
        stroking = false;
        xfLayer = -1; xfM = null;
        dropDirect();
        for (Layer l : layers) l.bmp.recycle();
        layers.clear();
        for (Step s : undo) free(s);
        for (Step s : redo) free(s);
        undo.clear(); redo.clear(); undoBytes = 0;
        resize(w, h);
        cur = 0;
        addLayer();
    }

    /** Ajusta el tamaño del lienzo (sin capas): límites para que quepa en la memoria. */
    private void resize(int w, int h) {
        w = Math.max(200, Math.min(MAX_SIDE, w));
        h = Math.max(200, Math.min(MAX_SIDE, h));
        if ((long) w * h > MAX_AREA) { double k = Math.sqrt(MAX_AREA / (double) ((long) w * h)); w = (int) (w * k); h = (int) (h * k); }
        clearSel();
        if (sel != null) { sel.recycle(); sel = null; }
        if (w == W && h == H) return;
        W = w; H = h;
        buf.recycle();
        buf = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        bufC = new Canvas(buf);
    }

    // ---------------- selección: borrar partes y dejarlas transparentes ----------------

    private Bitmap selMask() {
        if (sel == null) sel = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        return sel;
    }

    void clearSel() { if (sel != null) sel.eraseColor(Color.TRANSPARENT); hasSel = false; }

    private void invertSel() {
        Bitmap m = selMask();
        Bitmap inv = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(inv);
        c.drawColor(0xFF000000);
        Paint p = new Paint();
        p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
        c.drawBitmap(m, 0, 0, p);
        m.eraseColor(Color.TRANSPARENT);
        new Canvas(m).drawBitmap(inv, 0, 0, null);
        inv.recycle();
        hasSel = true;
    }

    /** Agrega a la selección: varita (zona de color parecido, tocando un punto) o lazo (zona dibujada a mano). */
    private void select(JSONObject m) {
        Bitmap mask = selMask();
        String k = m.optString("k");
        if ("lasso".equals(k)) {
            JSONArray p = m.optJSONArray("p");
            if (p == null || p.length() < 6) return;
            android.graphics.Path path = new android.graphics.Path();
            path.moveTo((float) p.optDouble(0), (float) p.optDouble(1));
            for (int i = 2; i + 1 < p.length(); i += 2) path.lineTo((float) p.optDouble(i), (float) p.optDouble(i + 1));
            path.close();
            Paint f = new Paint(Paint.ANTI_ALIAS_FLAG);
            f.setColor(0xFF000000);
            new Canvas(mask).drawPath(path, f);
            hasSel = true;
            return;
        }
        // varita mágica sobre la capa elegida (cuenta también lo transparente)
        int layer = m.optInt("l", cur), x = m.optInt("x"), y = m.optInt("y"), tol = m.optInt("tol", 32);
        if (layer < 0 || layer >= layers.size() || x < 0 || y < 0 || x >= W || y >= H) return;
        int[] px = new int[W * H];
        layers.get(layer).bmp.getPixels(px, 0, W, 0, 0, W, H);
        int target = px[y * W + x];
        boolean[] mark = new boolean[W * H];
        int[] stack = new int[W * H];
        int sp = 0, minX = x, maxX = x, minY = y, maxY = y;
        stack[sp++] = y * W + x;
        mark[y * W + x] = true;
        while (sp > 0) {
            int i = stack[--sp], cx = i % W, cy = i / W;
            if (cx < minX) minX = cx; if (cx > maxX) maxX = cx; if (cy < minY) minY = cy; if (cy > maxY) maxY = cy;
            if (cx > 0 && !mark[i - 1] && nearA(px[i - 1], target, tol)) { mark[i - 1] = true; stack[sp++] = i - 1; }
            if (cx < W - 1 && !mark[i + 1] && nearA(px[i + 1], target, tol)) { mark[i + 1] = true; stack[sp++] = i + 1; }
            if (cy > 0 && !mark[i - W] && nearA(px[i - W], target, tol)) { mark[i - W] = true; stack[sp++] = i - W; }
            if (cy < H - 1 && !mark[i + W] && nearA(px[i + W], target, tol)) { mark[i + W] = true; stack[sp++] = i + W; }
        }
        // se agranda 1 px para no dejar un borde del fondo alrededor del dibujo
        Rect r = new Rect(Math.max(0, minX - 1), Math.max(0, minY - 1), Math.min(W, maxX + 2), Math.min(H, maxY + 2));
        int rw = r.width(), rh = r.height();
        int[] out = new int[rw * rh];
        for (int yy = 0; yy < rh; yy++) for (int xx = 0; xx < rw; xx++) {
            int gx = r.left + xx, gy = r.top + yy, i = gy * W + gx;
            boolean in = mark[i] || (gx > 0 && mark[i - 1]) || (gx < W - 1 && mark[i + 1]) || (gy > 0 && mark[i - W]) || (gy < H - 1 && mark[i + W]);
            out[yy * rw + xx] = in ? 0xFF000000 : 0;
        }
        Bitmap patch = Bitmap.createBitmap(out, rw, rh, Bitmap.Config.ARGB_8888);
        new Canvas(mask).drawBitmap(patch, r.left, r.top, null);
        patch.recycle();
        hasSel = true;
    }

    /** Parecido de color contando la transparencia. */
    private static boolean nearA(int a, int b, int tol) {
        int aa = (a >>> 24), ba = (b >>> 24);
        if (aa < 8 && ba < 8) return true;   // los dos transparentes
        return Math.abs(aa - ba) + Math.abs(((a >> 16) & 255) - ((b >> 16) & 255)) + Math.abs(((a >> 8) & 255) - ((b >> 8) & 255))
                + Math.abs((a & 255) - (b & 255)) <= tol * 4;
    }

    /** Borra lo seleccionado en la capa: queda transparente (se puede deshacer). */
    private void deleteSel(int layer) {
        if (!hasSel || sel == null || layer < 0 || layer >= layers.size()) return;
        Layer l = layers.get(layer);
        Step st = new Step();
        st.layer = layer;
        st.r = new Rect(0, 0, W, H);
        st.before = l.bmp.copy(Bitmap.Config.ARGB_8888, false);
        Paint p = new Paint();
        p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
        new Canvas(l.bmp).drawBitmap(sel, 0, 0, p);
        st.after = l.bmp.copy(Bitmap.Config.ARGB_8888, false);
        push(st);
    }

    /** Imagen plana (fondo blanco) para exportar. */
    // ---------------- PSD (Photoshop) con capas ----------------

    private static final String[] PSD_BLEND = {"norm", "mul ", "scrn", "over", "lddg"};
    private static final int PSD_RES = 4242;   // recurso propio: textos y formas editables

    /**
     * Abre un .psd (RGB de 8 bits) con sus capas: nombre, visibilidad, opacidad y mezcla. Lee capas sin comprimir y
     * RLE (lo normal); los grupos se aplanan en capas sueltas; efectos, máscaras y ajustes de Photoshop no se leen.
     * Si el PSD lo hizo DUAL-Draw, los textos y formas vuelven a ser editables.
     */
    static DrawDoc readPsd(java.io.InputStream is, int maxSide) throws java.io.IOException {
        java.io.DataInputStream in = new java.io.DataInputStream(new java.io.BufferedInputStream(is, 1 << 16));
        byte[] sig = new byte[4];
        in.readFully(sig);
        if (!"8BPS".equals(new String(sig, java.nio.charset.StandardCharsets.US_ASCII))) throw new java.io.IOException("No es un PSD");
        if (in.readUnsignedShort() != 1) throw new java.io.IOException("PSB (archivo grande) no soportado");
        in.skipBytes(6);
        int nch = in.readUnsignedShort(), h = in.readInt(), w = in.readInt(), depth = in.readUnsignedShort(), mode = in.readUnsignedShort();
        if (depth != 8) throw new java.io.IOException("Solo PSD de 8 bits");
        if (mode != 3) throw new java.io.IOException("Solo PSD en RGB");
        if (w <= 0 || h <= 0 || w > maxSide || h > maxSide) throw new java.io.IOException("Imagen demasiado grande (" + w + " × " + h + ")");
        skipFully(in, in.readInt() & 0xFFFFFFFFL);   // modo de color
        // recursos: buscar el nuestro
        JSONObject dd = null;
        long rl = in.readInt() & 0xFFFFFFFFL, rread = 0;
        while (rread + 12 <= rl) {
            in.readFully(sig); int id = in.readUnsignedShort(); rread += 6;
            int nl = in.readUnsignedByte(); int np = (nl + 1) % 2 == 0 ? nl : nl + 1; skipFully(in, np); rread += 1 + np;
            long sz = in.readInt() & 0xFFFFFFFFL; rread += 4;
            long padded = sz + (sz % 2);
            if (id == PSD_RES && sz < 400_000_000L) {
                byte[] d = new byte[(int) sz]; in.readFully(d); skipFully(in, padded - sz);
                try { dd = new JSONObject(new String(d, java.nio.charset.StandardCharsets.UTF_8)); } catch (Exception ignored) { }
            } else skipFully(in, padded);
            rread += padded;
        }
        if (rread < rl) skipFully(in, rl - rread);
        DrawDoc doc = new DrawDoc();
        doc.dispose();
        doc.W = w; doc.H = h;
        int max = doc.maxLayers();
        long lmLen = in.readInt() & 0xFFFFFFFFL;
        boolean layered = false;
        if (lmLen > 0) {
            long liLen = in.readInt() & 0xFFFFFFFFL;
            if (liLen > 0) {
                int count = Math.abs(in.readShort());
                int[][] rect = new int[count][4];
                int[][] chId = new int[count][];
                long[][] chLen = new long[count][];
                Layer[] ls = new Layer[count];
                boolean[] skip = new boolean[count];
                for (int i = 0; i < count; i++) {
                    for (int k = 0; k < 4; k++) rect[i][k] = in.readInt();
                    int cn = in.readUnsignedShort();
                    chId[i] = new int[cn]; chLen[i] = new long[cn];
                    for (int c = 0; c < cn; c++) { chId[i][c] = in.readShort(); chLen[i][c] = in.readInt() & 0xFFFFFFFFL; }
                    in.readFully(sig);
                    byte[] key = new byte[4]; in.readFully(key);
                    String k = new String(key, java.nio.charset.StandardCharsets.US_ASCII);
                    int opacity = in.readUnsignedByte(); in.readUnsignedByte(); int flags = in.readUnsignedByte(); in.readUnsignedByte();
                    long extra = in.readInt() & 0xFFFFFFFFL, er = 0;
                    long mk = in.readInt() & 0xFFFFFFFFL; skipFully(in, mk); er += 4 + mk;
                    long br = in.readInt() & 0xFFFFFFFFL; skipFully(in, br); er += 4 + br;
                    int nl = in.readUnsignedByte(); byte[] nb = new byte[nl]; in.readFully(nb);
                    int np = ((nl + 1 + 3) / 4) * 4 - (nl + 1); skipFully(in, np); er += 1 + nl + np;
                    while (er + 12 <= extra) {   // información adicional: grupos (lsct) se saltan
                        in.readFully(sig); byte[] kk = new byte[4]; in.readFully(kk);
                        long bl = in.readInt() & 0xFFFFFFFFL; er += 12;
                        String kn = new String(kk, java.nio.charset.StandardCharsets.US_ASCII);
                        if ("lsct".equals(kn) && bl >= 4) { int t = in.readInt(); skipFully(in, bl - 4); if (t == 1 || t == 2 || t == 3) skip[i] = true; }
                        else skipFully(in, bl);
                        er += bl;
                    }
                    if (er < extra) skipFully(in, extra - er);
                    Layer l = new Layer();
                    l.visible = (flags & 2) == 0;
                    l.alpha = opacity / 255f;
                    int bi = 0;
                    for (int b = 0; b < PSD_BLEND.length; b++) if (PSD_BLEND[b].equals(k)) bi = b;
                    l.blend = bi;
                    l.name = new String(nb, java.nio.charset.StandardCharsets.ISO_8859_1);
                    ls[i] = l;
                }
                // los pixeles de cada canal, capa por capa
                for (int i = 0; i < count; i++) {
                    int top = rect[i][0], left = rect[i][1], lw = rect[i][3] - left, lh = rect[i][2] - top;
                    byte[][] planes = new byte[4][];   // A R G B
                    for (int c = 0; c < chId[i].length; c++) {
                        long len = chLen[i][c];
                        int comp = in.readUnsignedShort();
                        long data = len - 2;
                        int idc = chId[i][c];
                        boolean want = idc >= -1 && idc <= 2 && lw > 0 && lh > 0 && !skip[i];
                        if (!want || (comp != 0 && comp != 1)) { skipFully(in, data); continue; }
                        byte[] plane = new byte[lw * lh];
                        if (comp == 0) in.readFully(plane);
                        else {
                            int[] rows = new int[lh];
                            for (int r = 0; r < lh; r++) rows[r] = in.readUnsignedShort();
                            for (int r = 0; r < lh; r++) {
                                byte[] packed = new byte[rows[r]];
                                in.readFully(packed);
                                unpack(packed, plane, r * lw, lw);
                            }
                        }
                        planes[idc + 1] = plane;
                    }
                    if (skip[i] || ls[i] == null) continue;
                    if (lw <= 0 || lh <= 0) { ls[i].bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); continue; }   // capa vacía
                    int[] px = new int[lw * lh];
                    for (int p = 0; p < px.length; p++) {
                        int a = planes[0] == null ? 255 : planes[0][p] & 255;
                        int r = planes[1] == null ? 0 : planes[1][p] & 255, g = planes[2] == null ? 0 : planes[2][p] & 255, b = planes[3] == null ? 0 : planes[3][p] & 255;
                        px[p] = (a << 24) | (r << 16) | (g << 8) | b;
                    }
                    Bitmap part = Bitmap.createBitmap(px, lw, lh, Bitmap.Config.ARGB_8888);
                    Bitmap full = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                    new Canvas(full).drawBitmap(part, left, top, null);
                    part.recycle();
                    ls[i].bmp = full;
                }
                for (int i = 0; i < count && doc.layers.size() < max; i++) if (!skip[i] && ls[i] != null && ls[i].bmp != null) doc.layers.add(ls[i]);
                layered = !doc.layers.isEmpty();
            }
        }
        if (!layered) {   // PSD sin capas: la imagen combinada, como una sola capa
            if (lmLen > 0) { /* ya se leyó lo que había */ }
            Layer l = new Layer();
            l.bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            l.name = "Capa 1";
            try {
                int comp = in.readUnsignedShort();
                byte[][] planes = new byte[Math.max(3, nch)][];
                if (comp == 1) {
                    int[] rows = new int[h * nch];
                    for (int r = 0; r < rows.length; r++) rows[r] = in.readUnsignedShort();
                    for (int c = 0; c < nch; c++) {
                        planes[c] = new byte[w * h];
                        for (int r = 0; r < h; r++) { byte[] pk = new byte[rows[c * h + r]]; in.readFully(pk); unpack(pk, planes[c], r * w, w); }
                    }
                } else if (comp == 0) for (int c = 0; c < nch; c++) { planes[c] = new byte[w * h]; in.readFully(planes[c]); }
                if (planes[0] != null && planes[1] != null && planes[2] != null) {
                    int[] row = new int[w];
                    for (int y = 0; y < h; y++) {
                        for (int x = 0; x < w; x++) { int p = y * w + x; row[x] = 0xFF000000 | ((planes[0][p] & 255) << 16) | ((planes[1][p] & 255) << 8) | (planes[2][p] & 255); }
                        l.bmp.setPixels(row, 0, w, 0, y, w, 1);
                    }
                }
            } catch (java.io.EOFException ignored) { }
            doc.layers.add(l);
        }
        // hecho por DUAL-Draw: la pintura sin los textos/formas y los textos/formas aparte (editables)
        if (dd != null && dd.optInt("n") == doc.layers.size()) {
            JSONArray dl = dd.optJSONArray("layers");
            for (int k = 0; dl != null && k < dl.length(); k++) {
                JSONObject j = dl.optJSONObject(k);
                int i = j == null ? -1 : j.optInt("i", -1);
                if (i < 0 || i >= doc.layers.size()) continue;
                Layer l = doc.layers.get(i);
                try {
                    byte[] d = android.util.Base64.decode(j.optString("png"), android.util.Base64.DEFAULT);
                    Bitmap b = BitmapFactory.decodeByteArray(d, 0, d.length);
                    if (b != null) { l.bmp.eraseColor(Color.TRANSPARENT); new Canvas(l.bmp).drawBitmap(b, 0, 0, null); b.recycle(); }
                } catch (Exception ignored) { }
                shapesFrom(l, j.optString("vec", ""));
            }
        }
        doc.cur = doc.layers.size() - 1;
        return doc;
    }

    private static void skipFully(java.io.DataInputStream in, long n) throws java.io.IOException {
        while (n > 0) { long s = in.skip(n); if (s <= 0) { if (in.read() < 0) throw new java.io.EOFException(); s = 1; } n -= s; }
    }

    /** PackBits → bytes (una fila). */
    private static void unpack(byte[] src, byte[] dst, int off, int len) {
        int i = 0, o = off, end = off + len;
        while (i < src.length && o < end) {
            int n = src[i++];
            if (n >= 0) { int c = Math.min(n + 1, end - o); System.arraycopy(src, i, dst, o, Math.min(c, src.length - i)); i += n + 1; o += c; }
            else if (n != -128) { int c = Math.min(1 - n, end - o); byte v = i < src.length ? src[i] : 0; i++; for (int k = 0; k < c; k++) dst[o++] = v; }
        }
    }

    /**
     * Escribe el dibujo como .psd con sus capas (nombre, visibilidad, opacidad y mezcla); las formas de la pluma van
     * pintadas en su capa. Compresión RLE (PackBits), que deja chicas las capas casi vacías.
     */
    void writePsd(java.io.OutputStream os) throws java.io.IOException {
        java.io.DataOutputStream o = new java.io.DataOutputStream(new java.io.BufferedOutputStream(os, 1 << 16));
        o.writeBytes("8BPS"); o.writeShort(1); o.write(new byte[6]);
        o.writeShort(3); o.writeInt(H); o.writeInt(W); o.writeShort(8); o.writeShort(3);
        o.writeInt(0);   // modo de color
        // recursos: una copia de los textos y formas de la pluma (con la pintura de su capa sin ellos), que solo lee
        // DUAL-Draw: así al reabrir el PSD siguen siendo editables; para los demás programas es un dato que ignoran
        java.io.ByteArrayOutputStream res = new java.io.ByteArrayOutputStream();
        try {
            JSONArray dl = new JSONArray();
            for (int li = 0; li < layers.size(); li++) {
                Layer l = layers.get(li);
                if (l.shapes.isEmpty()) continue;
                ByteArrayOutputStream pb = new ByteArrayOutputStream();
                l.bmp.compress(Bitmap.CompressFormat.PNG, 100, pb);
                dl.put(new JSONObject().put("i", li).put("vec", shapesJson(l))
                        .put("png", android.util.Base64.encodeToString(pb.toByteArray(), android.util.Base64.NO_WRAP)));
            }
            if (dl.length() > 0) {
                byte[] data = new JSONObject().put("v", 1).put("n", layers.size()).put("layers", dl).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                java.io.DataOutputStream ro = new java.io.DataOutputStream(res);
                ro.writeBytes("8BIM"); ro.writeShort(PSD_RES); ro.writeShort(0);
                ro.writeInt(data.length); ro.write(data);
                if (data.length % 2 != 0) ro.writeByte(0);
            }
        } catch (org.json.JSONException ignored) { }
        o.writeInt(res.size());
        res.writeTo(o);
        // capas: primero se comprimen (los registros llevan el largo de cada canal)
        int n = layers.size();
        byte[][][] ch = new byte[n][4][];
        int[] row = new int[W];
        for (int li = 0; li < n; li++) {
            Bitmap b = layerImage(li);
            for (int c = 0; c < 4; c++) ch[li][c] = rleChannel(b, c, row);
            b.recycle();
        }
        java.io.ByteArrayOutputStream info = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream io = new java.io.DataOutputStream(info);
        io.writeShort(n);
        for (int li = 0; li < n; li++) {   // de la capa de más abajo a la de más arriba
            Layer l = layers.get(li);
            io.writeInt(0); io.writeInt(0); io.writeInt(H); io.writeInt(W);
            io.writeShort(4);
            int[] ids = {-1, 0, 1, 2};
            for (int c = 0; c < 4; c++) { io.writeShort(ids[c]); io.writeInt(2 + ch[li][c].length); }
            io.writeBytes("8BIM");
            io.writeBytes(PSD_BLEND[Math.max(0, Math.min(PSD_BLEND.length - 1, l.blend))]);
            io.writeByte(Math.round(255 * l.alpha));
            io.writeByte(0);
            io.writeByte(l.visible ? 0 : 2);
            io.writeByte(0);
            byte[] nm = (l.name == null ? "Capa " + (li + 1) : l.name).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            int nl = Math.min(255, nm.length), padded = ((nl + 1 + 3) / 4) * 4;
            io.writeInt(4 + 4 + padded);
            io.writeInt(0); io.writeInt(0);
            io.writeByte(nl); io.write(nm, 0, nl);
            for (int k = nl + 1; k < padded; k++) io.writeByte(0);
        }
        for (int li = 0; li < n; li++) for (int c = 0; c < 4; c++) { io.writeShort(1); io.write(ch[li][c]); }
        if (info.size() % 2 != 0) io.writeByte(0);
        o.writeInt(4 + info.size() + 4);
        o.writeInt(info.size());
        info.writeTo(o);
        o.writeInt(0);   // máscara global
        // imagen combinada (para los programas que no leen capas)
        Bitmap flat = flatten();
        byte[][] fc = new byte[3][];
        for (int c = 0; c < 3; c++) fc[c] = rleChannel(flat, c + 1, row);
        flat.recycle();
        o.writeShort(1);
        for (int c = 0; c < 3; c++) o.write(fc[c], 0, H * 2);   // primero todos los largos de fila…
        for (int c = 0; c < 3; c++) o.write(fc[c], H * 2, fc[c].length - H * 2);   // …después los datos
        o.flush();
        ch = null;
    }

    /** Un canal (0 = alfa, 1 R, 2 G, 3 B) en PackBits: largos de fila (2 bytes cada uno) y luego los datos. */
    private byte[] rleChannel(Bitmap b, int c, int[] row) {
        java.io.ByteArrayOutputStream counts = new java.io.ByteArrayOutputStream(H * 2), data = new java.io.ByteArrayOutputStream();
        byte[] line = new byte[W];
        int shift = c == 0 ? 24 : c == 1 ? 16 : c == 2 ? 8 : 0;
        for (int y = 0; y < H; y++) {
            b.getPixels(row, 0, W, 0, y, W, 1);
            for (int x = 0; x < W; x++) {
                int p = row[x];
                if (c != 0) {   // getPixels da colores sin premultiplicar: se guardan tal cual
                    line[x] = (byte) ((p >>> shift) & 0xFF);
                } else line[x] = (byte) (p >>> 24);
            }
            int before = data.size();
            packBits(line, data);
            int len = data.size() - before;
            counts.write(len >> 8); counts.write(len);
        }
        byte[] cb = counts.toByteArray(), db = data.toByteArray();
        byte[] outB = new byte[cb.length + db.length];
        System.arraycopy(cb, 0, outB, 0, cb.length);
        System.arraycopy(db, 0, outB, cb.length, db.length);
        return outB;
    }

    private static void packBits(byte[] in, java.io.ByteArrayOutputStream out) {
        int i = 0, n = in.length;
        while (i < n) {
            int run = 1;
            while (i + run < n && run < 128 && in[i + run] == in[i]) run++;
            if (run >= 3) { out.write(257 - run); out.write(in[i]); i += run; continue; }
            int start = i, lit = 0;
            while (i < n && lit < 128) {
                if (i + 2 < n && in[i] == in[i + 1] && in[i] == in[i + 2]) break;
                i++; lit++;
            }
            out.write(lit - 1);
            out.write(in, start, lit);
        }
    }

    Bitmap flatten() {
        end();
        Bitmap out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        draw(new Canvas(out), new RectF(0, 0, W, H));
        return out;
    }

    // ---------------- todo el dibujo (para guardar y para el otro teléfono) ----------------

    /** Copia del dibujo (en el hilo de la pantalla); se convierte a texto después, en otro hilo. */
    static final class Snap { final List<Bitmap> bmps = new ArrayList<>(); final List<Boolean> vis = new ArrayList<>(); final List<float[]> ab = new ArrayList<>(); final List<String> vec = new ArrayList<>(), names = new ArrayList<>(); int cur, w, h; }

    Snap snapshot() {
        Snap s = new Snap();
        for (Layer l : layers) { s.bmps.add(l.bmp.copy(Bitmap.Config.ARGB_8888, false)); s.vis.add(l.visible); s.ab.add(new float[]{l.alpha, l.blend}); s.vec.add(shapesJson(l)); s.names.add(l.name); }
        s.cur = cur;
        s.w = W; s.h = H;
        return s;
    }

    /** Un dibujo armado desde una copia (para guardar el PSD en otro hilo sin tocar el que se está dibujando). */
    void restore(Snap s) {
        W = s.w; H = s.h;
        layers.clear();
        for (int i = 0; i < s.bmps.size(); i++) {
            Layer l = new Layer();
            l.bmp = s.bmps.get(i);
            l.visible = s.vis.get(i);
            l.alpha = s.ab.get(i)[0]; l.blend = (int) s.ab.get(i)[1];
            l.name = i < s.names.size() ? s.names.get(i) : "Capa " + (i + 1);
            if (i < s.vec.size()) shapesFrom(l, s.vec.get(i));
            layers.add(l);
        }
        cur = Math.max(0, Math.min(layers.size() - 1, s.cur));
    }

    void dispose() { for (Layer l : layers) if (l.bmp != null) l.bmp.recycle(); layers.clear(); }

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
                if (i < s.vec.size() && !"[]".equals(s.vec.get(i))) j.put("vec", s.vec.get(i));
                arr.put(j);
            }
            o.put("layers", arr);
            o.put("cur", s.cur);
            o.put("w", s.w);
            o.put("h", s.h);
        } catch (Exception ignored) { }
        return o;
    }

    void fromJson(JSONObject o) {
        JSONArray arr = o.optJSONArray("layers");
        if (arr == null || arr.length() == 0) return;
        stroking = false;
        dropDirect();
        for (Layer l : layers) l.bmp.recycle();
        layers.clear();
        undo.clear(); redo.clear(); undoBytes = 0;
        resize(o.optInt("w", 1600), o.optInt("h", 900));
        for (int i = 0; i < arr.length() && i < maxLayers(); i++) {
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
                shapesFrom(l, j.optString("vec", ""));
            }
            layers.add(l);
        }
        cur = Math.max(0, Math.min(layers.size() - 1, o.optInt("cur", 0)));
        renameLayers();
    }
}
