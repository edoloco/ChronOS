package cl.mesadoble;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.Base64;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Dibuja la mitad del escritorio que corresponde a este teléfono.
 * Toques: arrastrar mueve; mantener presionado muestra controles para girar y cambiar tamaño;
 * con dos dedos sobre un elemento también se gira y se escala.
 */
public class DeskView extends View {

    public interface Host {
        void onItemChanged(Item it);
        void onItemDeleted(Item it);
        void onDrag(Item it, double x, double y);
        void onDragEnd(Item it);
        void onEditNote(Item it);
        void onOpenLink(Item it);
        void onOpenApp(Item it);
        /** Se cambió de escritorio (deslizando o llevando un elemento al borde). */
        void onPageChange(int page, int pages);
        /** Widgets de Android: la vista real va encima de la mesa, donde la mesa dibuja su tarjeta. */
        void onWidgetFrame(Item it, float left, float top, float width, float height, float rot, float pivotX, float pivotY, boolean shown);
        boolean widgetBound(Item it);
        /** En el extendido: la imagen en vivo del widget que corre en el principal (null en el principal). */
        Bitmap widgetMirror(Item it);
        void onWidgetTap(Item it);
        /** En el extendido: un toque sobre el widget (x, y de 0 a 1), para hacerlo en el principal. */
        void onWidgetTouch(Item it, float fx, float fy);
        void onOpenAlarms();
        /** {nivel principal, cargando principal, nivel extendido, cargando extendido}; -1 = sin dato. */
        int[] battery();
        /** Widget de apps recientes: abrir esa app en el principal (pkg null = pedir el permiso de uso). */
        void onRecent(String pkg, String label);
    }

    // ---------------- apps recientes del principal ----------------

    static final class Recent { String pkg, label; Bitmap icon; }
    private final List<Recent> strip = new ArrayList<>();   // [0] = la mesa ("Desktop"); luego las apps, en orden estable
    private boolean recentsAccess = true, recentsLoaded, scrubbing;
    private String recentsSig = "", recActive = "";
    private float recScroll, recTarget, scrubStart, recSlot = 1;
    private long userUntil, recPressAt;
    private final Paint recHi = new Paint(Paint.ANTI_ALIAS_FLAG), scope = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Item scrubItem;

    public String recentsSig() { return recentsLoaded ? recentsSig : "?"; }

    public boolean hasRecentsWidget() {
        for (Item it : items) if (Item.RECENTS.equals(it.kind) && onPage(it)) return true;
        return false;
    }

    private int indexOf(String pkg) {
        for (int i = 0; i < strip.size(); i++) if (strip.get(i).pkg.equals(pkg)) return i;
        return -1;
    }

    /** Lista recibida del principal (o calculada aquí si este es el principal). */
    public void setRecents(org.json.JSONObject m) {
        recentsLoaded = true;
        recentsAccess = m.optBoolean("access", true);
        String sig = m.optString("sig");
        if (!sig.equals(recentsSig)) {
            recentsSig = sig;
            if (strip.isEmpty()) { Recent d = new Recent(); d.pkg = ""; d.label = "Desktop"; strip.add(d); }
            // orden estable: las que siguen quedan donde estaban, las nuevas van al final
            java.util.Set<String> now = new java.util.HashSet<>();
            org.json.JSONArray a = m.optJSONArray("apps");
            for (int i = 0; a != null && i < a.length(); i++) {
                org.json.JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                String p = o.optString("p");
                now.add(p);
                int k = indexOf(p);
                Recent x = k >= 0 ? strip.get(k) : new Recent();
                x.label = o.optString("l");
                if (k < 0) {
                    x.pkg = p;
                    try {
                        byte[] b = Base64.decode(o.optString("i"), Base64.DEFAULT);
                        x.icon = BitmapFactory.decodeByteArray(b, 0, b.length);
                    } catch (Exception ignored) { }
                    strip.add(x);
                }
            }
            String cur = strip.size() > (int) (recScroll + 0.5f) && recScroll >= 0 ? strip.get(Math.round(recScroll)).pkg : "";
            for (int i = strip.size() - 1; i > 0; i--) if (!now.contains(strip.get(i).pkg)) strip.remove(i);
            int ci = indexOf(cur);   // que lo que estaba bajo la mira no salte
            if (ci >= 0 && !scrubbing) { recScroll += ci - Math.round(recScroll); recTarget = ci; }
        }
        recActive = m.optString("active");
        if (!scrubbing && android.os.SystemClock.uptimeMillis() > userUntil) {
            int k = indexOf(recActive);
            if (k >= 0) recTarget = k;
        }
        invalidate();
    }

    private float recTop() { return 26 * dp; }

    /**
     * Barra deslizante: los íconos pasan bajo una mira fija al centro. Lo que queda en la mira es la app que está
     * al frente en el principal ("Desktop" = la mesa). Se desliza con el dedo y al soltar se abre esa app.
     */
    private void drawRecents(Canvas c, Item it, float width, float h) {
        // alfiler: fijar o soltar el widget
        drawPin(c, width - 22 * dp, 15 * dp, it.pin);
        if (!recentsAccess || !recentsLoaded || strip.size() <= 1) {
            small.setColor(muted);
            small.setTextSize(13 * dp);
            String t = !recentsLoaded ? "Buscando apps recientes del principal…" : !recentsAccess
                    ? "Toca para permitir \"Acceso a datos de uso\" en el principal" : "Aún no hay apps recientes";
            t = ellipsize(t, small, width - 20 * dp);
            c.drawText(t, width / 2 - small.measureText(t) / 2, (26 * dp + h) / 2 + 4 * dp, small);
            return;
        }
        float top = recTop(), labelY = h - 9 * dp, bottom = h - 22 * dp;
        float icon = Math.min(44 * dp, bottom - top - 10 * dp);
        float slot = icon + 22 * dp;
        recSlot = slot;
        float cy = (top + bottom) / 2, cx0 = width / 2;
        boolean again = false;
        if (!scrubbing) {
            recScroll += (recTarget - recScroll) * 0.2f;
            if (Math.abs(recTarget - recScroll) > 0.003f) again = true; else recScroll = recTarget;
        }
        c.save();
        c.clipRect(6 * dp, top - 4 * dp, width - 6 * dp, h - 4 * dp);
        long now = android.os.SystemClock.uptimeMillis();
        for (int i = 0; i < strip.size(); i++) {
            float d = Math.abs(i - recScroll);
            float x = cx0 + (i - recScroll) * slot;
            if (x < -slot || x > width + slot) continue;
            float k = 1 - Math.min(d, 3) * 0.13f;
            float s = icon * k;
            if (d < 0.5f && now - recPressAt < 380) { s *= 1 - 0.15f * (float) Math.sin(Math.PI * (now - recPressAt) / 380f); again = true; }
            int alpha = Math.round(255 * Math.max(0.25f, 1 - Math.min(d, 4) * 0.2f));
            Recent r = strip.get(i);
            RectF dst = new RectF(x - s / 2, cy - s / 2, x + s / 2, cy + s / 2);
            if (r.pkg.isEmpty()) drawDesktopTile(c, dst, alpha);
            else if (r.icon != null) { bmpPaint.setAlpha(alpha); c.drawBitmap(r.icon, null, dst, bmpPaint); }
        }
        bmpPaint.setAlpha(255);
        c.restore();
        // la mira: un cuadradito fijo al centro
        float sh = icon / 2 + 7 * dp;
        RectF sc = new RectF(cx0 - sh, cy - sh, cx0 + sh, cy + sh);
        scope.setStyle(Paint.Style.STROKE);
        scope.setStrokeWidth(5 * dp);
        scope.setColor((accent & 0x00FFFFFF) | 0x40000000);
        c.drawRoundRect(sc, 14 * dp, 14 * dp, scope);
        scope.setStrokeWidth(2.4f * dp);
        scope.setColor(accent);
        c.drawRoundRect(sc, 14 * dp, 14 * dp, scope);
        // nombre de lo que está en la mira; punto verde si es lo que está al frente en el principal
        int ci = Math.max(0, Math.min(strip.size() - 1, Math.round(recScroll)));
        Recent cur = strip.get(ci);
        small.setColor(fg);
        small.setTextSize(12 * dp);
        String name = ellipsize(cur.label, small, width * 0.6f);
        float tw = small.measureText(name);
        c.drawText(name, cx0 - tw / 2, labelY, small);
        if (cur.pkg.equals(recActive)) {
            recHi.setColor(0xFF34C759);
            c.drawCircle(cx0 - tw / 2 - 8 * dp, labelY - 4 * dp, 3 * dp, recHi);
        }
        if (again) postInvalidateOnAnimation();
    }

    /** "Desktop": un cuadradito con la cuadrícula de la mesa. */
    private void drawDesktopTile(Canvas c, RectF r, int alpha) {
        recHi.setColor((accent & 0x00FFFFFF) | (Math.round(alpha * 0.9f) << 24));
        float rad = r.width() * 0.24f;
        c.drawRoundRect(r, rad, rad, recHi);
        recHi.setColor((0x00FFFFFF) | (alpha << 24));
        float g = r.width() * 0.13f, cell = (r.width() - g * 4) / 3 * 0.9f;
        for (int a = 0; a < 2; a++) for (int b = 0; b < 3; b++) {
            float x = r.left + g + b * (cell + g * 0.9f) + r.width() * 0.04f, y = r.top + g + a * (cell + g * 0.8f);
            c.drawRoundRect(new RectF(x, y, x + cell, y + cell), cell * 0.25f, cell * 0.25f, recHi);
        }
        small.setColor((0x00FFFFFF) | (alpha << 24));
        float ts = r.width() * 0.2f;
        small.setTextSize(ts);
        String t = "Desktop";
        c.drawText(t, r.centerX() - small.measureText(t) / 2, r.bottom - r.width() * 0.1f, small);
    }

    private void drawPin(Canvas c, float x, float y, boolean on) {
        recHi.setColor(on ? accent : (muted & 0x00FFFFFF) | 0x99000000);
        c.save();
        c.rotate(on ? 0 : 35, x, y);
        c.drawCircle(x, y - 3 * dp, 4.5f * dp, recHi);
        c.drawRect(x - 1 * dp, y, x + 1 * dp, y + 7 * dp, recHi);
        c.restore();
    }

    private boolean onPin(float lx, float ly, float width) { return lx > width - 44 * dp && ly < recTop(); }

    /** Al soltar: la barra se acomoda en el ícono más cercano y esa app se abre en el principal. */
    private void scrubEnd(boolean moved, float lx, float width, float vx) {
        scrubbing = false;
        int n = strip.size();
        if (n == 0) return;
        float t = moved ? recScroll - vx / recSlot * 0.12f : recScroll + (lx - width / 2) / recSlot;
        int i = Math.max(0, Math.min(n - 1, Math.round(t)));
        recTarget = i;
        userUntil = android.os.SystemClock.uptimeMillis() + 3500;
        recPressAt = android.os.SystemClock.uptimeMillis();
        invalidate();
        Recent x = strip.get(i);
        if (host != null && !x.pkg.equals(recActive)) host.onRecent(x.pkg, x.label);
    }

    /** Alto de la franja superior de un widget (nombre y botones); el widget va debajo. */
    public float widgetBar() { return 38 * dp; }

    public final List<Item> items = new ArrayList<>();
    private final Map<String, double[]> remoteDrags = new HashMap<>();
    private static final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private Host host;
    private int side = 0;
    /**
     * true: teléfonos uno sobre otro (mesa horizontal), se cruza por arriba/abajo, y va de 0 a 2.
     * false: teléfonos lado a lado (mesa vertical), se cruza por izquierda/derecha, x va de 0 a 2.
     * En ambos casos los teléfonos se unen por su lado largo.
     */
    private boolean vertical = true;
    private boolean connected = false;
    private float glyphRot = 0;

    private final float dp;
    private final int touchSlop;
    private final Paint bg = new Paint(), dot = new Paint(Paint.ANTI_ALIAS_FLAG), glow = new Paint(),
            edge = new Paint(), card = new Paint(Paint.ANTI_ALIAS_FLAG), btn = new Paint(Paint.ANTI_ALIAS_FLAG),
            icon = new Paint(Paint.ANTI_ALIAS_FLAG), bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG),
            outline = new Paint(Paint.ANTI_ALIAS_FLAG), handle = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint hand = new TextPaint(Paint.ANTI_ALIAS_FLAG), small = new TextPaint(Paint.ANTI_ALIAS_FLAG),
            label = new TextPaint(Paint.ANTI_ALIAS_FLAG), empty = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private int deskColor, deskColor2;
    private final int accent;
    private int fg, muted, linkBg;
    private final boolean night;
    private final Paint glassEdge = new Paint(Paint.ANTI_ALIAS_FLAG), glassShine = new Paint(Paint.ANTI_ALIAS_FLAG);

    // ---- estado del toque
    private static final int NONE = 0, DRAG = 1, ROTATE = 2, RESIZE = 3, PINCH = 4, SWIPE = 5, SCRUB = 6;

    // ---- escritorios a los lados (páginas) y apps
    private int page = 0, pages = 1;
    private float slide = 0;            // desplazamiento horizontal al cambiar de escritorio
    private ValueAnimator slideAnim;
    private boolean lockApps, autoApps;
    private int edgeDir = 0;
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    { ring.setStyle(Paint.Style.STROKE); ring.setStrokeWidth(1.6f * getResources().getDisplayMetrics().density); }
    private boolean snapGrid;
    private float[] downLocal;
    private final Paint battBody = new Paint(Paint.ANTI_ALIAS_FLAG), battFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean clockTick;
    private String alarmText = "";
    private long alarmAt = 0;
    private final Paint clockPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    { clockPaint.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL)); }
    private final Runnable minuteTick = this::invalidate;
    private final Runnable edgeFlip = this::onEdgeFlip;
    private int mode = NONE;
    private Item pressed, dragging, editing;
    private String pressedBtn;
    private float downX, downY;
    private double ox, oy, dragX, dragY;
    private boolean moved, longPressed;
    private VelocityTracker vt;
    private double startW, startDist, startAngle;
    private float startRot;
    private final Runnable longPress = this::onLongPress;

    public DeskView(Context c, boolean night) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        touchSlop = ViewConfiguration.get(c).getScaledTouchSlop();
        deskColor = night ? 0xFF18212B : 0xFFDDE3E8;
        deskColor2 = night ? 0xFF243346 : 0xFFC6D2DC;
        accent = night ? 0xFF5CC3CF : 0xFF1F7F8C;
        fg = night ? 0xFFE6EDF3 : 0xFF1D2733;
        muted = night ? 0xFF93A4B5 : 0xFF5B6B7A;
        linkBg = night ? 0xFF223040 : 0xFFFFFFFF;
        this.night = night;
        glassEdge.setStyle(Paint.Style.STROKE);
        glassEdge.setStrokeWidth(1.2f * dp);
        setCardTheme();

        bg.setColor(deskColor);
        dot.setColor(night ? 0x1ABED7EB : 0x29283C50);
        icon.setStyle(Paint.Style.STROKE);
        icon.setStrokeWidth(2.2f * dp);
        icon.setStrokeCap(Paint.Cap.ROUND);
        icon.setStrokeJoin(Paint.Join.ROUND);
        outline.setStyle(Paint.Style.STROKE);
        outline.setStrokeWidth(2 * dp);
        outline.setColor(accent);
        outline.setPathEffect(new DashPathEffect(new float[]{8 * dp, 6 * dp}, 0));
        handle.setColor(accent);

        hand.setTypeface(Typeface.create("casual", Typeface.NORMAL));
        hand.setColor(0xFF2B2A22);
        small.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        label.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        label.setLetterSpacing(0.12f);
        label.setTextSize(10 * dp);
        empty.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        empty.setColor(muted);
        empty.setTextSize(16 * dp);
    }

    public void setHost(Host h) { host = h; }

    /** Color de fondo elegido por el usuario (0 = el del tema). Se ajustan el brillo y los puntos para que se vean. */
    public void setDeskColor(int c, boolean night) {
        if (c == 0) c = night ? 0xFF18212B : 0xFFDDE3E8;
        deskColor = c;
        int r = Color.red(c), g = Color.green(c), b = Color.blue(c);
        boolean dark = (0.299 * r + 0.587 * g + 0.114 * b) < 140;
        float k = 0.10f;
        deskColor2 = dark ? Color.rgb((int) (r + (255 - r) * k), (int) (g + (255 - g) * k), (int) (b + (255 - b) * k))
                          : Color.rgb((int) (r * (1 - k)), (int) (g * (1 - k)), (int) (b * (1 - k)));
        bg.setColor(deskColor);
        CardTheme.desk = c;
        dot.setColor(dark ? 0x1FFFFFFF : 0x29000000);
        empty.setColor(dark ? 0xFFB0BCC8 : 0xFF4B5966);
        setCardTheme();   // el texto sobre cristal depende del fondo
    }
    /** Colores de las tarjetas (apps, enlaces, widgets, reloj, batería) según el tema elegido. */
    public void setCardTheme() {
        linkBg = CardTheme.fill(night);
        fg = CardTheme.fg(night);
        muted = CardTheme.muted(night);
        glassEdge.setColor(CardTheme.stroke());
        blurDirty = true;
        invalidate();
    }

    public boolean isDragging(Item it) { return dragging == it || (editing == it && mode != NONE); }
    public void setSide(int s) { side = s; blurDirty = true; invalidate(); }
    public void setVertical(boolean v) { vertical = v; blurDirty = true; invalidate(); }
    public boolean isVertical() { return vertical; }

    // ---- geometría según cómo están unidos los teléfonos
    private float leftOf(Item it, int W) { return vertical ? it.dx * W : (it.dx - side) * W; }
    private float topOf(Item it, int H) { return vertical ? (it.dy - side) * H : it.dy * H; }
    /** Tamaño del elemento en la dirección en que se cruza al otro teléfono (en "pantallas"). */
    private double alongSize(Item it) { return vertical ? it.hPx / Math.max(1, getHeight()) : it.w; }
    private boolean onFirst(Item it) { return (vertical ? it.y : it.x) + alongSize(it) / 2 < 1; }
    private static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(Math.max(lo, hi), v)); }

    /** Mantiene el elemento dentro de la mesa doble. */
    public void normalize(Item it) {
        // holgado: un elemento puede quedar en cualquier parte mientras se vea al menos un trozo
        if (vertical) { it.x = clamp(it.x, -0.6 * it.w, 1 - 0.4 * it.w); it.y = clamp(it.y, -0.15, 1.9); }
        else { it.x = clamp(it.x, -0.6 * it.w, 2 - 0.4 * it.w); it.y = clamp(it.y, -0.15, 0.9); }
    }

    /** Pasa todos los elementos de "lado a lado" a "uno sobre otro" (o al revés), conservando su pantalla. */
    /**
     * Cambia entre vertical (x 0..2: principal a la izquierda, extendido a la derecha) y
     * horizontal (y 0..2: principal arriba, extendido abajo). La pantalla 0 es siempre el principal,
     * así cada elemento queda en el mismo teléfono.
     */
    public void convertAll(boolean toVertical) {
        for (Item it : items) {
            if (toVertical) {
                int scr = it.x + it.w / 2 >= 1 ? 1 : 0;
                double u = it.x - scr, v = it.y;
                it.x = u; it.y = scr + v;
            } else {
                int scr = it.y + 0.15 >= 1 ? 1 : 0;
                double u = it.x, v = it.y - scr;
                it.x = scr + u; it.y = v;
            }
            it.dx = Float.NaN; it.dy = Float.NaN;
        }
        vertical = toVertical;
        for (Item it : items) normalize(it);
        invalidate();
    }
    /** Migración única: antes el principal estaba a la derecha (x 1..2); ahora es la pantalla 0. */
    public void swapHalves() {
        for (Item it : items) {
            if (it.x + it.w / 2 >= 1) it.x -= 1; else it.x += 1;
            it.dx = Float.NaN; it.dy = Float.NaN;
        }
        invalidate();
    }

    // ---------------- escritorios ----------------

    public int page() { return page; }
    public int pages() { return pages; }

    /** Cambia la cantidad de escritorios y el actual. dir: de qué lado entra (1 desde la derecha, -1 desde la izquierda, 0 sin animación). */
    public void setPages(int n, int cur, int dir) {
        pages = Math.max(1, Math.min(50, n));
        cur = Math.max(0, Math.min(pages - 1, cur));
        if (cur != page && dir != 0) animateSlide(dir * getWidth() * 0.6f);
        page = cur;
        if (editing != null && editing.page != page) editing = null;
        invalidate();
    }

    private void goTo(int np, float from) {
        page = np;
        if (editing != null && editing.page != page) editing = null;
        animateSlide(from);
        if (host != null) host.onPageChange(page, pages);
    }

    private void animateSlide(float from) {
        if (slideAnim != null) slideAnim.cancel();
        slide = from;
        slideAnim = ValueAnimator.ofFloat(from, 0);
        slideAnim.setDuration(240);
        slideAnim.setInterpolator(new android.view.animation.DecelerateInterpolator());
        slideAnim.addUpdateListener(a -> { slide = (float) a.getAnimatedValue(); invalidate(); });
        slideAnim.start();
    }

    public void setAppsMode(boolean lock, boolean auto) {
        lockApps = lock; autoApps = auto;
        if (editing != null && locked(editing)) editing = null;
        invalidate();
    }

    private boolean locked(Item it) { return it.pin || (lockApps && Item.APP.equals(it.kind)); }
    private boolean visible(Item it) { return onPage(it) || it == dragging || remoteDrags.containsKey(it.id); }
    /** ¿Está en el escritorio actual? (los fijos están en todos) */
    private boolean onPage(Item it) { return it.page == page || it.pin; }
    private int screenOf(Item it) { return onFirst(it) ? 0 : 1; }

    /**
     * Ordena las apps del escritorio actual en cuadrícula, por nombre, cada una en la pantalla donde está.
     * Deja libre el lado donde va la barra de opciones.
     */
    public void arrangeApps() {
        int W = getWidth(), H = getHeight();
        if (W == 0 || H == 0) return;
        final Collator col = Collator.getInstance(new Locale("es"));
        col.setStrength(Collator.PRIMARY);
        for (int s = 0; s < 2; s++) {
            List<Item> apps = new ArrayList<>();
            for (Item it : items) if (Item.APP.equals(it.kind) && it.page == page && screenOf(it) == s && it != dragging) apps.add(it);
            if (apps.isEmpty()) continue;
            Collections.sort(apps, (p, q) -> col.compare(p.title, q.title));
            float gap = 10 * dp, mx = 14 * dp, top, bottom;
            // horizontal: la barra está arriba en el principal y abajo en el extendido; vertical: abajo en ambos
            if (vertical) { top = s == 0 ? 86 * dp : 16 * dp; bottom = s == 0 ? 30 * dp : 86 * dp; }
            else { top = 16 * dp; bottom = 86 * dp; }
            double w = vertical ? 0.12 : 0.24;
            float cellW = 0, cellH = 0;
            int cols = 1;
            for (int tries = 0; tries < 8; tries++) {
                cellW = (float) (w * W);
                cellH = Math.max(96 * dp, cellW * 0.9f);
                cols = Math.max(1, (int) ((W - 2 * mx + gap) / (cellW + gap)));
                int rows = Math.max(1, (int) ((H - top - bottom + gap) / (cellH + gap)));
                if (cols * rows >= apps.size() || w < 0.09) break;
                w *= 0.86;
            }
            for (int i = 0; i < apps.size(); i++) {
                Item it = apps.get(i);
                double ux = (mx + (i % cols) * (cellW + gap)) / W, uy = (top + (i / cols) * (cellH + gap)) / H;
                double nx = vertical ? ux : s + ux, ny = vertical ? s + uy : uy;
                if (Math.abs(nx - it.x) < 1e-4 && Math.abs(ny - it.y) < 1e-4 && Math.abs(w - it.w) < 1e-4 && it.rot == 0) continue;
                it.x = nx; it.y = ny; it.w = w; it.rot = 0;
                it.touch();
                if (host != null) host.onItemChanged(it);
            }
        }
        invalidate();
    }

    // ---------------- ordenar y alinear ----------------

    public void setSnap(boolean s) { snapGrid = s; }

    private float gridStep() { return 22 * dp; }   // la misma separación que los puntos de la mesa

    /** Ajusta la esquina del elemento al punto más cercano de la cuadrícula, en su pantalla. */
    private void snapItem(Item it) {
        int W = getWidth(), H = getHeight();
        if (W == 0 || H == 0) return;
        int s = screenOf(it);
        float step = gridStep();
        double lx = (vertical ? it.x : it.x - s) * W, ly = (vertical ? it.y - s : it.y) * H;
        lx = Math.round(lx / step) * step;
        ly = Math.round(ly / step) * step;
        it.x = vertical ? lx / W : s + lx / W;
        it.y = vertical ? s + ly / H : ly / H;
    }

    private List<Item> onThisPage() {
        List<Item> l = new ArrayList<>();
        for (Item it : items) if (it.page == page && it != dragging && !locked(it)) l.add(it);
        return l;
    }

    /** Alinea todos los elementos del escritorio a los puntos. */
    public void alignAll() {
        for (Item it : onThisPage()) {
            double ox = it.x, oy = it.y;
            snapItem(it);
            if (ox != it.x || oy != it.y) { it.touch(); if (host != null) host.onItemChanged(it); }
        }
        invalidate();
    }

    /** Deja todos los elementos derechos (sin giro). */
    public void straightenAll() {
        for (Item it : onThisPage()) if (it.rot != 0) { it.rot = 0; it.touch(); if (host != null) host.onItemChanged(it); }
        invalidate();
    }

    private static int kindOrder(String k) {
        switch (k) { case Item.APP: return 0; case Item.CLOCK: return 1; case Item.BATTERY: return 2; case Item.WIDGET: return 3;
            case Item.NOTE: return 4; case Item.LINK: return 5; default: return 6; }
    }

    /**
     * Ordena TODO el escritorio en filas, cada elemento en su pantalla: primero apps (por nombre),
     * luego reloj, batería, widgets, notas, enlaces e imágenes. Deja libre el lado de la barra.
     */
    public void arrangeAll() {
        int W = getWidth(), H = getHeight();
        if (W == 0 || H == 0) return;
        final Collator col = Collator.getInstance(new Locale("es"));
        col.setStrength(Collator.PRIMARY);
        for (int s = 0; s < 2; s++) {
            List<Item> list = new ArrayList<>();
            for (Item it : onThisPage()) if (screenOf(it) == s) list.add(it);
            if (list.isEmpty()) continue;
            Collections.sort(list, (p, q) -> {
                int k = Integer.compare(kindOrder(p.kind), kindOrder(q.kind));
                if (k != 0) return k;
                String a = p.title.isEmpty() ? p.text : p.title, b = q.title.isEmpty() ? q.text : q.title;
                return col.compare(a, b);
            });
            float gap = 12 * dp, mx = 14 * dp, top;
            if (vertical) top = s == 0 ? 86 * dp : 16 * dp; else top = 16 * dp;
            float x = mx, y = top, rowH = 0;
            for (Item it : list) {
                if (it.w * W > W - 2 * mx) it.w = (W - 2 * mx) / W;
                float wpx = (float) (it.w * W), hpx = measure(it, wpx);
                if (x > mx && x + wpx > W - mx) { x = mx; y += rowH + gap; rowH = 0; }
                it.x = vertical ? x / W : s + x / W;
                it.y = vertical ? s + y / H : y / H;
                it.rot = 0;
                x += wpx + gap;
                rowH = Math.max(rowH, hpx);
                it.touch();
                if (host != null) host.onItemChanged(it);
            }
        }
        invalidate();
    }

    /** Un elemento arrastrado contra el borde izquierdo o derecho pasa al escritorio de ese lado (y lo crea si no existe). */
    private void onEdgeFlip() {
        if (dragging == null || edgeDir == 0) return;
        int np = page + edgeDir;
        if (np < 0 || np >= 50) return;
        if (np >= pages) pages = np + 1;
        dragging.page = np;
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        goTo(np, edgeDir * getWidth() * 0.6f);
        postDelayed(edgeFlip, 900);   // si sigue en el borde, avanza otro escritorio
    }

    public void setConnected(boolean c) { connected = c; invalidate(); }
    public void setGlyphRotation(float r) { glyphRot = r; invalidate(); }

    public Item find(String id) {
        for (Item it : items) if (it.id.equals(id)) return it;
        return null;
    }

    public void setRemoteDrag(String id, double x, double y) { remoteDrags.put(id, new double[]{x, y}); invalidate(); }
    public void clearRemoteDrag(String id) { remoteDrags.remove(id); invalidate(); }
    public void clearAllRemoteDrags() { remoteDrags.clear(); invalidate(); }

    /** Lugar libre aproximado dentro de la pantalla de este teléfono. */
    public double[] spot(double w) {
        if (vertical) return new double[]{0.06 + Math.random() * Math.max(0.01, 0.86 - w), side + 0.12 + Math.random() * 0.3};
        return new double[]{side + 0.08 + Math.random() * Math.max(0.01, 0.84 - w), 0.14 + Math.random() * 0.4};
    }

    public void sendAcross(Item it) {
        double sz = alongSize(it);
        double a = onFirst(it) ? 1.04 + Math.random() * 0.08 : 1 - 0.04 - sz - Math.random() * 0.08;
        if (vertical) it.y = a; else it.x = a;
        it.z = System.currentTimeMillis();
        it.touch();
        invalidate();
        if (host != null) host.onItemChanged(it);
    }

    /** Decodifica la imagen fuera del hilo principal para no trabar la pantalla. */
    private Bitmap bitmapOf(Item it) {
        if (it.bmp != null || it.img.isEmpty() || it.decoding) return it.bmp;
        it.decoding = true;
        final String data = it.img;
        decoder.execute(() -> {
            Bitmap b = null;
            try {
                byte[] bytes = Base64.decode(data, Base64.DEFAULT);
                b = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            } catch (Exception ignored) { }
            final Bitmap res = b;
            post(() -> {
                if (data.equals(it.img)) it.bmp = res;
                it.decoding = false;
                invalidate();
            });
        });
        return null;
    }

    // ---------------- dibujo ----------------

    private boolean blurDirty = true;
    private final Paint blob = new Paint(Paint.ANTI_ALIAS_FLAG);

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) { super.onSizeChanged(w, h, ow, oh); blurDirty = true; }

    /** Fondo de la mesa: color, brillo en la unión, manchas de luz (cristal intenso) y puntos. */
    private void drawDesk(Canvas c, int W, int H) {
        c.drawRect(0, 0, W, H, bg);
        // brillo centrado en la unión de los teléfonos: se ve continuo entre ambos
        float gx = vertical ? W / 2f : (1 - side) * W, gy = vertical ? (1 - side) * H : H * 0.45f;
        glow.setShader(new RadialGradient(gx, gy, Math.max(W, H) * 0.95f, deskColor2, deskColor, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, H, glow);
        if (Glass.on()) {
            // manchas de luz de colores, continuas entre los dos teléfonos: el vidrio las desenfoca
            float ox = vertical ? 0 : side * W, oy = vertical ? side * H : 0;
            float GW = vertical ? W : 2f * W, GH = vertical ? 2f * H : H;
            float[][] spots = {{0.18f, 0.28f, 40}, {0.78f, 0.72f, -50}, {0.52f, 0.12f, 160}, {0.35f, 0.85f, 95}, {0.92f, 0.2f, -120}};
            float[] hsv = new float[3];
            Color.colorToHSV(deskColor, hsv);
            boolean darkD = CardTheme.dark(deskColor);
            for (float[] sp : spots) {
                float[] h2 = {(hsv[0] + sp[2] + 360) % 360, Math.max(0.45f, Math.min(0.8f, hsv[1] + 0.35f)), darkD ? 0.75f : 0.98f};
                int col = Color.HSVToColor(darkD ? 0x8C : 0x99, h2);
                float cx = sp[0] * GW - ox, cy = sp[1] * GH - oy, rad = Math.max(W, H) * 0.42f;
                blob.setShader(new RadialGradient(cx, cy, rad, col, col & 0x00FFFFFF, Shader.TileMode.CLAMP));
                c.drawCircle(cx, cy, rad, blob);
            }
        }
        float step = 22 * dp;
        float offX = vertical ? 0 : (side * W) % step, offY = vertical ? (side * H) % step : 0;
        for (float x = step / 2 - offX; x < W; x += step)
            for (float y = step / 2 - offY; y < H; y += step) c.drawCircle(x, y, 1.2f * dp, dot);
    }

    /** Copia desenfocada del fondo: es lo que se ve a través del cristal intenso. */
    private void rebuildBlur(int W, int H) {
        blurDirty = false;
        int bw = Math.max(1, W / 4), bh = Math.max(1, H / 4);
        Bitmap b = Glass.blur != null && Glass.blur.getWidth() == bw && Glass.blur.getHeight() == bh && Glass.desk.get() == this
                ? Glass.blur : Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        Canvas bc = new Canvas(b);
        bc.scale(bw / (float) W, bh / (float) H);
        drawDesk(bc, W, H);
        Glass.boxBlur(b, Math.max(2, Math.round(7 * dp)));
        Glass.setBlur(b, this);
    }

    @Override protected void onDraw(Canvas c) {
        int W = getWidth(), H = getHeight();
        clockTick = false;
        drawDesk(c, W, H);
        if (Glass.on() && (blurDirty || Glass.blur == null || Glass.desk.get() != this) && W > 0 && H > 0) rebuildBlur(W, H);

        boolean hot = !remoteDrags.isEmpty();
        if (dragging != null) {
            double a = vertical ? dragY : dragX, sz = alongSize(dragging);
            hot |= side == 0 ? a + sz > 0.97 : a < 1.03;
        }
        float ew = (hot ? 18 : 7) * dp;
        int ac = Color.argb(hot ? 255 : connected ? 200 : 90, Color.red(accent), Color.green(accent), Color.blue(accent));
        // franja en el borde que toca al otro teléfono
        if (vertical && side == 0) {
            edge.setShader(new LinearGradient(0, H, 0, H - ew, ac, 0x00000000, Shader.TileMode.CLAMP));
            c.drawRect(0, H - ew, W, H, edge);
        } else if (vertical) {
            edge.setShader(new LinearGradient(0, 0, 0, ew, ac, 0x00000000, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, W, ew, edge);
        } else if (side == 0) {
            edge.setShader(new LinearGradient(W, 0, W - ew, 0, ac, 0x00000000, Shader.TileMode.CLAMP));
            c.drawRect(W - ew, 0, W, H, edge);
        } else {
            edge.setShader(new LinearGradient(0, 0, ew, 0, ac, 0x00000000, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, ew, H, edge);
        }

        boolean none = true;
        for (Item it : items) if (visible(it)) { none = false; break; }
        if (none) {
            String t = pages > 1 ? "Escritorio " + (page + 1) + " vacío. Agrega algo o desliza a los lados."
                                 : "Escritorio vacío. Agrega una nota, una imagen o una app.";
            int lw = (int) (Math.min(W, H) * 0.8f);
            StaticLayout l = StaticLayout.Builder.obtain(t, 0, t.length(), empty, lw)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER).build();
            c.save();
            c.rotate(glyphRot, W / 2f, H / 2f);
            c.translate(W / 2f - lw / 2f, H / 2f - l.getHeight() / 2f);
            l.draw(c);
            c.restore();
        }

        List<Item> sorted = new ArrayList<>(items);
        Collections.sort(sorted, (p, q) -> Long.compare(p.z, q.z));
        if (dragging != null) { sorted.remove(dragging); sorted.add(dragging); }
        boolean again = false;
        java.util.Set<Item> framed = new java.util.HashSet<>();
        for (Item it : sorted) {
            if (!visible(it)) { it.dx = Float.NaN; continue; }
            // al deslizar en vertical (lado a lado) no se muestran los elementos del otro teléfono
            if (slide != 0 && !vertical && it != dragging && screenOf(it) != side) continue;
            double tx = it.x, ty = it.y;
            float k = 0.26f;
            double[] rd = remoteDrags.get(it.id);
            if (it == dragging) { tx = dragX; ty = dragY; k = 1f; }
            else if (rd != null) { tx = rd[0]; ty = rd[1]; k = 0.55f; }
            if (Float.isNaN(it.dx)) { it.dx = (float) tx; it.dy = (float) ty; }
            it.dx += (float) (tx - it.dx) * k;
            it.dy += (float) (ty - it.dy) * k;
            if (Math.abs(tx - it.dx) > 0.0004 || Math.abs(ty - it.dy) > 0.0004) again = true;
            else { it.dx = (float) tx; it.dy = (float) ty; }
            float left = leftOf(it, W) + (it == dragging ? 0 : slide), top = topOf(it, H), width = (float) (it.w * W);
            float h = measure(it, width);
            float reach = Math.max(width, h);   // un elemento girado puede asomar más allá de su ancho
            if (left > W + reach || left + width < -reach || top > H + reach || top + h < -reach) continue;
            drawItem(c, it, left, top, width, h, it == dragging || rd != null);
            if (Item.WIDGET.equals(it.kind) && host != null) {
                float bar = widgetBar();
                host.onWidgetFrame(it, left, top + bar, width, h - bar, it.rot, width / 2, h / 2 - bar, true);
                framed.add(it);
            }
        }
        if (host != null) for (Item it : items)
            if (Item.WIDGET.equals(it.kind) && !framed.contains(it)) host.onWidgetFrame(it, 0, 0, 0, 0, 0, 0, 0, false);
        if (again) postInvalidateOnAnimation();

        // relojes: se vuelve a dibujar al cambiar el minuto
        removeCallbacks(minuteTick);
        if (clockTick) postDelayed(minuteTick, 60_000 - System.currentTimeMillis() % 60_000 + 50);

        // puntos de los escritorios
        if (pages > 1) {
            float r = 3.6f * dp, g = 14 * dp, y = H - 14 * dp, x0 = W / 2f - (pages - 1) * g / 2;
            for (int i = 0; i < pages; i++) {
                btn.setColor(i == page ? accent : (muted & 0x00FFFFFF) | 0x77000000);
                c.drawCircle(x0 + i * g, y, i == page ? r * 1.25f : r, btn);
            }
        }
    }

    private float measure(Item it, float width) {
        float pad = 14 * dp;
        if (Item.NOTE.equals(it.kind)) {
            hand.setTextSize(Math.min(26 * dp, Math.max(15 * dp, width * 0.075f)));
            String t = it.text.isEmpty() ? "Toca para escribir" : it.text;
            String key = t + "|" + (int) width + "|" + hand.getTextSize();
            if (!key.equals(it.layoutKey)) {
                it.layout = StaticLayout.Builder.obtain(t, 0, t.length(), hand, (int) Math.max(10, width - 2 * pad))
                        .setLineSpacing(0, 1.1f).build();
                it.layoutKey = key;
            }
            it.hPx = Math.max(96 * dp, 34 * dp + it.layout.getHeight() + pad);
        } else if (Item.IMG.equals(it.kind)) {
            Bitmap b = bitmapOf(it);
            float inner = width - 16 * dp;
            float ih = b != null ? inner * b.getHeight() / (float) b.getWidth() : inner * 0.7f;
            it.hPx = 8 * dp + ih + 36 * dp;
        } else if (Item.APP.equals(it.kind)) {
            it.hPx = Math.max(96 * dp, width * 0.9f);
        } else if (Item.WIDGET.equals(it.kind)) {
            it.hPx = widgetBar() + (float) (width * it.ar);
        } else if (Item.CLOCK.equals(it.kind)) {
            it.hPx = Math.max(118 * dp, width * 0.52f);
        } else if (Item.BATTERY.equals(it.kind)) {
            it.hPx = Math.max(128 * dp, width * 0.42f);
        } else if (Item.RECENTS.equals(it.kind)) {
            it.hPx = 96 * dp;
        } else {
            it.hPx = 132 * dp;
        }
        return it.hPx;
    }

    private void drawItem(Canvas c, Item it, float left, float top, float width, float h, boolean lifted) {
        float pad = 14 * dp, r = 6 * dp;
        c.save();
        c.translate(left, top);
        c.rotate(it.rot, width / 2, h / 2);
        if (lifted) c.scale(1.03f, 1.03f, width / 2, h / 2);
        RectF box = new RectF(0, 0, width, h);

        boolean isNote = Item.NOTE.equals(it.kind), isImg = Item.IMG.equals(it.kind), isApp = Item.APP.equals(it.kind),
                isWidget = Item.WIDGET.equals(it.kind), isClock = Item.CLOCK.equals(it.kind), isBatt = Item.BATTERY.equals(it.kind), isRec = Item.RECENTS.equals(it.kind);
        boolean glassCard = CardTheme.isGlass() && !isNote && !isImg;
        boolean aeroCard = glassCard && CardTheme.isAero();
        boolean intense = glassCard && CardTheme.isIntense();
        // Aero: texto oscuro con halo blanco (como los títulos de Windows 7)
        for (Paint tp : new Paint[]{small, label, clockPaint}) {
            if (aeroCard) Aero.glowText(tp, dp); else if (intense) Glass.text(tp, dp); else tp.clearShadowLayer();
        }
        float rr = isApp ? 18 * dp : glassCard ? 14 * dp : r;
        card.setColor(isNote ? it.color : isImg ? 0xFFFFFFFF : linkBg);
        if (glassCard) card.clearShadowLayer();   // una sombra se vería como mancha detrás del vidrio
        else card.setShadowLayer((lifted ? 18 : 10) * dp, 0, (lifted ? 10 : 5) * dp, 0x55000000);
        if (intense) {
            // cristal intenso: sombra profunda y, dentro, el fondo desenfocado que queda justo detrás
            Glass.shadow(c, box, rr, dp, lifted);
            final float W = getWidth(), H = getHeight(), cx = width / 2, cy = h / 2, rot = it.rot;
            Glass.body(c, box, rr, dp, Glass.blur == null ? null : () -> {
                c.save();
                if (lifted) c.scale(1 / 1.03f, 1 / 1.03f, cx, cy);
                c.rotate(-rot, cx, cy);
                c.translate(-left, -top);
                Glass.drawBlur(c, W, H);
                c.restore();
            });
        } else if (aeroCard) Aero.draw(c, box, rr, CardTheme.aeroTint(), dp);
        else c.drawRoundRect(box, rr, rr, card);
        if (glassCard && !aeroCard && !intense) {
            // cristal: brillo arriba y un borde fino claro
            glassShine.setShader(new LinearGradient(0, 0, 0, h * 0.55f,
                    CardTheme.glass == CardTheme.GLASS_LIGHT ? 0x40FFFFFF : 0x22FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            c.drawRoundRect(box, rr, rr, glassShine);
            c.drawRoundRect(new RectF(0.6f * dp, 0.6f * dp, width - 0.6f * dp, h - 0.6f * dp), rr, rr, glassEdge);
        }

        int ink = isNote || isImg ? 0xFF2B2A22 : fg;
        label.setColor((ink & 0x00FFFFFF) | 0x77000000);
        String tag = isNote ? "NOTA" : isImg ? "IMAGEN" : isApp ? "APP" : isWidget ? "WIDGET" : isClock ? "RELOJ" : isBatt ? "BATERÍA" : isRec ? "APPS RECIENTES" : "ENLACE";
        if (isRec) {
            c.drawText(tag, 10 * dp, 18 * dp, label);
            drawRecents(c, it, width, h);
        } else if (isBatt) {
            c.drawText(tag, 10 * dp, 18 * dp, label);
            int[] b = host != null ? host.battery() : new int[]{-1, 0, -1, 0};
            drawBattery(c, "Principal", b[0], b[1] == 1, width, h * 0.40f);
            drawBattery(c, "Extendido", b[2], b[3] == 1, width, h * 0.70f);
        } else if (isClock) {
            java.util.Date now = new java.util.Date();
            boolean is24 = android.text.format.DateFormat.is24HourFormat(getContext());
            String time = new java.text.SimpleDateFormat(is24 ? "HH:mm" : "h:mm", Locale.getDefault()).format(now);
            String date = new java.text.SimpleDateFormat("EEEE, d 'de' MMMM", new Locale("es")).format(now);
            long nowMs = System.currentTimeMillis();
            if (nowMs - alarmAt > 30_000) { alarmText = ClockWidget.nextAlarm(getContext()); alarmAt = nowMs; }
            String alarm = alarmText;
            clockPaint.setColor(fg);
            clockPaint.setTextSize(Math.min(h * 0.42f, width * 0.26f));
            float ty = h * 0.55f;
            c.drawText(time, width / 2 - clockPaint.measureText(time) / 2, ty, clockPaint);
            small.setColor(fg);
            small.setTextSize(Math.max(12 * dp, Math.min(17 * dp, width * 0.06f)));
            String d2 = ellipsize(date.substring(0, 1).toUpperCase() + date.substring(1), small, width - 20 * dp);
            c.drawText(d2, width / 2 - small.measureText(d2) / 2, ty + small.getTextSize() * 1.5f, small);
            if (!alarm.isEmpty()) {
                small.setColor(accent);
                small.setTextSize(Math.max(11 * dp, Math.min(15 * dp, width * 0.05f)));
                c.drawText(alarm, width / 2 - small.measureText(alarm) / 2, h - 12 * dp, small);
            }
            c.drawText(tag, 10 * dp, 18 * dp, label);
            clockTick = true;
        } else if (isWidget) {
            small.setColor(fg);
            small.setTextSize(13 * dp);
            c.drawText(ellipsize(it.title, small, width - 92 * dp), 10 * dp, 24 * dp, small);
            float bar0 = widgetBar();
            Bitmap mirror = host != null ? host.widgetMirror(it) : null;
            if (mirror != null) c.drawBitmap(mirror, new Rect(0, 0, mirror.getWidth(), mirror.getHeight()), new RectF(0, bar0, width, h), bmpPaint);
            boolean bound = host != null && host.widgetBound(it);
            if (!bound && mirror == null) {
                small.setColor(muted);
                small.setTextSize(13 * dp);
                String t = it.wid < 0 ? "Toca para activar el widget (en el principal)" : "Cargando widget del principal…";
                float bar = widgetBar();
                c.drawText(ellipsize(t, small, width - 16 * dp), width / 2 - Math.min(small.measureText(t), width - 16 * dp) / 2,
                        bar + (h - bar) / 2, small);
            }
        } else if (isImg) c.drawText(tag, 10 * dp, h - 12 * dp, label);
        else c.drawText(tag, 10 * dp, 18 * dp, label);

        if (isNote) {
            hand.setColor(it.text.isEmpty() ? 0x772B2A22 : 0xFF2B2A22);
            c.save(); c.translate(pad, 32 * dp); it.layout.draw(c); c.restore();
        } else if (isImg) {
            Bitmap b = bitmapOf(it);
            RectF dst = new RectF(8 * dp, 8 * dp, width - 8 * dp, h - 36 * dp);
            if (b != null) c.drawBitmap(b, new Rect(0, 0, b.getWidth(), b.getHeight()), dst, bmpPaint);
            else {
                btn.setColor(0x14000000);
                c.drawRect(dst, btn);
                small.setColor(0xFF5B6B7A);
                small.setTextSize(13 * dp);
                String t = it.img.isEmpty() ? "Recibiendo imagen…" : "Cargando…";
                c.drawText(t, dst.centerX() - small.measureText(t) / 2, dst.centerY(), small);
            }
        } else if (isApp) {
            Bitmap b = bitmapOf(it);
            float is = Math.min(width, h) * 0.46f;
            RectF dst = new RectF(width / 2 - is / 2, h * 0.42f - is / 2, width / 2 + is / 2, h * 0.42f + is / 2);
            if (b != null) c.drawBitmap(b, new Rect(0, 0, b.getWidth(), b.getHeight()), dst, bmpPaint);
            small.setColor(fg);
            small.setTextSize(Math.max(12 * dp, Math.min(16 * dp, width * 0.07f)));
            String t = ellipsize(it.title, small, width - 16 * dp);
            c.drawText(t, width / 2 - small.measureText(t) / 2, h - 16 * dp, small);
        } else if (!isWidget && !isClock && !isBatt) {
            small.setColor(muted);
            small.setTextSize(12 * dp);
            String dom = host(it.url);
            c.drawText(ellipsize(dom.toUpperCase(), small, width - 2 * pad), pad, 46 * dp, small);
            small.setColor(fg);
            small.setTextSize(16 * dp);
            String t = it.title.isEmpty() ? dom : it.title;
            c.drawText(ellipsize(t, small, width - 2 * pad), pad, 72 * dp, small);
            it.openBtn.set(pad, 88 * dp, pad + 92 * dp, 120 * dp);
            btn.setColor((accent & 0x00FFFFFF) | 0x38000000);
            c.drawRoundRect(it.openBtn, 16 * dp, 16 * dp, btn);
            small.setColor(accent);
            small.setTextSize(14 * dp);
            c.drawText("Abrir  ↗", it.openBtn.left + 14 * dp, it.openBtn.centerY() + 5 * dp, small);
        }

        // sin flecha: para pasar al otro teléfono basta lanzarlo con el dedo.
        // la ✕ para quitar solo aparece al mantener presionado (modo edición), junto a girar y tamaño.
        it.sendBtn.setEmpty();
        if (locked(it) || it != editing) { it.delBtn.setEmpty(); if (locked(it)) { c.restore(); return; } }
        else {
            float bs = 32 * dp;
            it.delBtn.set(width - 6 * dp - bs, 6 * dp, width - 6 * dp, 6 * dp + bs);
            btn.setColor(0xFFD9534F);
            c.drawCircle(it.delBtn.centerX(), it.delBtn.centerY(), bs / 2, btn);
            icon.setColor(0xFFFFFFFF);
            float cx = it.delBtn.centerX(), cy = it.delBtn.centerY(), s5 = 5.5f * dp;
            c.drawLine(cx - s5, cy - s5, cx + s5, cy + s5, icon);
            c.drawLine(cx + s5, cy - s5, cx - s5, cy + s5, icon);
        }

        // controles de giro y tamaño
        if (it == editing) {
            RectF o = new RectF(-5 * dp, -5 * dp, width + 5 * dp, h + 5 * dp);
            c.drawRoundRect(o, 10 * dp, 10 * dp, outline);
            float hr = 15 * dp;
            // girar: círculo sobre el elemento
            c.drawLine(width / 2, -5 * dp, width / 2, -30 * dp + hr, outline);
            c.drawCircle(width / 2, -30 * dp, hr, handle);
            icon.setColor(0xFFFFFFFF);
            RectF arc = new RectF(width / 2 - 7 * dp, -37 * dp, width / 2 + 7 * dp, -23 * dp);
            c.drawArc(arc, -60, 270, false, icon);
            // tamaño: esquina inferior derecha
            c.drawCircle(width + 5 * dp, h + 5 * dp, hr, handle);
            float hx = width + 5 * dp, hy = h + 5 * dp, q = 5 * dp;
            c.drawLine(hx - q, hy - q, hx + q, hy + q, icon);
            c.drawLine(hx + q, hy + q, hx + q - 4 * dp, hy + q, icon);
            c.drawLine(hx + q, hy + q, hx + q, hy + q - 4 * dp, icon);
        }
        c.restore();
    }

    /** Una fila del widget de batería: nombre, pila con su nivel y porcentaje (⚡ si está cargando). */
    private void drawBattery(Canvas c, String who, int lvl, boolean charging, float width, float cy) {
        small.setColor(fg);
        small.setTextSize(Math.max(12 * dp, Math.min(15 * dp, width * 0.055f)));
        c.drawText(who, 14 * dp, cy + small.getTextSize() * 0.35f, small);
        float x0 = 14 * dp + Math.max(small.measureText("Extendido"), 70 * dp) + 10 * dp;
        float x1 = width - 64 * dp, bh = 20 * dp;
        if (x1 - x0 < 30 * dp) x1 = x0 + 30 * dp;
        RectF body = new RectF(x0, cy - bh / 2, x1, cy + bh / 2);
        battBody.setStyle(Paint.Style.STROKE);
        battBody.setStrokeWidth(2 * dp);
        battBody.setColor(muted);
        c.drawRoundRect(body, 5 * dp, 5 * dp, battBody);
        battBody.setStyle(Paint.Style.FILL);
        c.drawRoundRect(new RectF(x1 + 1.5f * dp, cy - 4 * dp, x1 + 5 * dp, cy + 4 * dp), 1.5f * dp, 1.5f * dp, battBody);
        String txt;
        if (lvl >= 0) {
            battFill.setColor(charging ? accent : lvl <= 15 ? 0xFFD9534F : lvl <= 35 ? 0xFFE8A33D : 0xFF4CAF6A);
            float w = (body.width() - 6 * dp) * Math.min(100, lvl) / 100f;
            c.drawRoundRect(new RectF(body.left + 3 * dp, body.top + 3 * dp, body.left + 3 * dp + w, body.bottom - 3 * dp), 3 * dp, 3 * dp, battFill);
            txt = lvl + "%" + (charging ? " ⚡" : "");
        } else txt = "—";
        small.setColor(fg);
        c.drawText(txt, x1 + 10 * dp, cy + small.getTextSize() * 0.35f, small);
    }

    private static String ellipsize(String t, Paint p, float max) {
        if (p.measureText(t) <= max) return t;
        while (t.length() > 1 && p.measureText(t + "…") > max) t = t.substring(0, t.length() - 1);
        return t + "…";
    }

    static String host(String url) {
        try {
            String h = android.net.Uri.parse(url).getHost();
            if (h == null) return url;
            return h.startsWith("www.") ? h.substring(4) : h;
        } catch (Exception e) { return url; }
    }

    // ---------------- geometría (respeta el giro de cada elemento) ----------------

    /** Punto de pantalla → coordenadas propias del elemento (sin girar, origen arriba a la izquierda). */
    private float[] toLocal(Item it, float px, float py) {
        int W = getWidth(), H = getHeight();
        float left = leftOf(it, W), top = topOf(it, H), width = (float) (it.w * W), h = it.hPx;
        float cx = left + width / 2, cy = top + h / 2;
        double a = Math.toRadians(-it.rot);
        float dx = px - cx, dy = py - cy;
        float rx = (float) (dx * Math.cos(a) - dy * Math.sin(a)), ry = (float) (dx * Math.sin(a) + dy * Math.cos(a));
        return new float[]{rx + width / 2, ry + h / 2, width, h};
    }

    private float[] centerOf(Item it) {
        int W = getWidth(), H = getHeight();
        return new float[]{leftOf(it, W) + (float) (it.w * W) / 2, topOf(it, H) + it.hPx / 2};
    }

    private Item hit(float px, float py) {
        List<Item> sorted = new ArrayList<>(items);
        Collections.sort(sorted, (p, q) -> Long.compare(q.z, p.z));
        for (Item it : sorted) {
            if (!onPage(it)) continue;
            float[] l = toLocal(it, px, py);
            if (l[0] >= 0 && l[0] <= l[2] && l[1] >= 0 && l[1] <= l[3]) return it;
        }
        return null;
    }

    private String handleAt(Item it, float px, float py) {
        float[] l = toLocal(it, px, py);
        float hr = 24 * dp;
        if (Math.hypot(l[0] - l[2] / 2, l[1] + 30 * dp) < hr) return "rotate";
        if (Math.hypot(l[0] - l[2] - 5 * dp, l[1] - l[3] - 5 * dp) < hr) return "resize";
        return null;
    }

    // ---------------- toque ----------------

    private void finishEditing() {
        if (editing == null) return;
        editing = null;
        invalidate();
    }

    private void commit(Item it) {
        it.z = System.currentTimeMillis();
        it.touch();
        invalidate();
        if (host != null) host.onItemChanged(it);
    }

    private void onLongPress() {
        if (pressed == null || moved || pressedBtn != null) return;
        longPressed = true;
        editing = pressed;
        editing.z = System.currentTimeMillis();
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        invalidate();
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        int W = getWidth(), H = getHeight();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                getParent().requestDisallowInterceptTouchEvent(true);
                mode = NONE; moved = false; longPressed = false; pressedBtn = null;
                downX = e.getX(); downY = e.getY();
                if (editing != null) {
                    String hnd = handleAt(editing, e.getX(), e.getY());
                    if (hnd != null) {
                        pressed = editing;
                        mode = hnd.equals("rotate") ? ROTATE : RESIZE;
                        float[] c = centerOf(editing);
                        startW = editing.w; startRot = editing.rot;
                        startDist = Math.hypot(e.getX() - c[0], e.getY() - c[1]);
                        return true;
                    }
                }
                pressed = hit(e.getX(), e.getY());
                if (pressed != editing) finishEditing();
                if (vt != null) vt.recycle();
                vt = VelocityTracker.obtain();
                vt.addMovement(e);
                if (slideAnim != null && slideAnim.isRunning()) slideAnim.cancel();
                slide = 0;
                if (pressed == null) { mode = SWIPE; return true; }   // espacio vacío: deslizar cambia de escritorio
                float[] l = toLocal(pressed, e.getX(), e.getY());
                downLocal = l;
                if (Item.RECENTS.equals(pressed.kind) && editing == null && recentsAccess && strip.size() > 1
                        && l[1] > recTop() - 4 * dp) {
                    mode = SCRUB; scrubbing = true; scrubStart = recScroll; scrubItem = pressed;
                    return true;
                }
                pressedBtn = locked(pressed) ? null : pressed == editing && pressed.delBtn.contains(l[0], l[1]) ? "del"
                        : Item.LINK.equals(pressed.kind) && pressed.openBtn.contains(l[0], l[1]) ? "open" : null;
                ox = pressed.x; oy = pressed.y;
                if (pressedBtn == null && editing == null && !locked(pressed)) postDelayed(longPress, 450);
                return true;
            }
            case MotionEvent.ACTION_POINTER_DOWN: {
                // segundo dedo sobre un elemento: girar y escalar con dos dedos
                if (mode == SCRUB) return true;
                Item target = pressed != null ? pressed : editing;
                if (target == null || e.getPointerCount() != 2 || mode == SWIPE || locked(target)) return true;
                if (target != editing) return true;   // girar o escalar: solo después de mantenerlo presionado
                removeCallbacks(longPress);
                if (mode == DRAG) { target.x = dragX; target.y = dragY; dragging = null; }
                mode = PINCH;
                pressed = target;
                startW = target.w; startRot = target.rot;
                startDist = Math.hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1));
                startAngle = Math.toDegrees(Math.atan2(e.getY(1) - e.getY(0), e.getX(1) - e.getX(0)));
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (mode == SCRUB && scrubItem != null) {
                    if (vt != null) vt.addMovement(e);
                    float dx = toLocal(scrubItem, e.getX(), e.getY())[0] - downLocal[0];
                    if (!moved && Math.abs(dx) < touchSlop) return true;
                    moved = true;
                    recScroll = Math.max(-0.45f, Math.min(strip.size() - 0.55f, scrubStart - dx / recSlot));
                    invalidate();
                    return true;
                }
                if (mode == SWIPE) {
                    if (vt != null) vt.addMovement(e);
                    float sx = e.getX() - downX;
                    if (!moved && Math.abs(sx) < touchSlop) return true;
                    moved = true;
                    boolean can = sx < 0 ? page < pages - 1 : page > 0;
                    slide = can ? sx : sx * 0.25f;   // en el último escritorio se resiste
                    invalidate();
                    return true;
                }
                if (pressed == null) return true;
                if (mode == PINCH && e.getPointerCount() >= 2) {
                    double d = Math.hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1));
                    double ang = Math.toDegrees(Math.atan2(e.getY(1) - e.getY(0), e.getX(1) - e.getX(0)));
                    pressed.w = clampW(startW * d / Math.max(1, startDist));
                    pressed.rot = snap((float) (startRot + ang - startAngle));
                    invalidate();
                    return true;
                }
                if (mode == ROTATE) {
                    float[] c = centerOf(pressed);
                    double ang = Math.toDegrees(Math.atan2(e.getY() - c[1], e.getX() - c[0])) + 90;
                    pressed.rot = snap((float) ang);
                    invalidate();
                    return true;
                }
                if (mode == RESIZE && Item.WIDGET.equals(pressed.kind)) {
                    // widget: ancho y alto libres, desde la esquina
                    float[] l = toLocal(pressed, e.getX(), e.getY());
                    float nw = Math.max(110 * dp, l[0] - 5 * dp), ch = Math.max(50 * dp, l[1] - 5 * dp - widgetBar());
                    pressed.w = clampW(nw / W);
                    pressed.ar = Math.max(0.15, Math.min(4, ch / (pressed.w * W)));
                    invalidate();
                    return true;
                }
                if (mode == RESIZE) {
                    float[] c = centerOf(pressed);
                    double d = Math.hypot(e.getX() - c[0], e.getY() - c[1]);
                    pressed.w = clampW(startW * d / Math.max(1, startDist));
                    invalidate();
                    return true;
                }
                if (vt != null) vt.addMovement(e);
                float ddx = e.getX() - downX, ddy = e.getY() - downY;
                if (!moved && Math.hypot(ddx, ddy) < touchSlop) return true;
                removeCallbacks(longPress);
                // nada se mueve sin mantenerlo presionado antes (o si está fijo): el gesto desliza el escritorio
                if (!moved && (locked(pressed) || (!longPressed && pressed != editing))) {
                    pressed = null; mode = SWIPE; downX = e.getX(); moved = false;
                    return true;
                }
                if (!moved) { moved = true; mode = DRAG; dragging = pressed; dragging.z = System.currentTimeMillis(); }
                dragX = ox + ddx / W;
                dragY = oy + ddy / H;
                if (vertical) dragX = clamp(dragX, -0.5, 0.95); else dragY = clamp(dragY, -0.4, 0.95);
                // contra el borde izquierdo o derecho (por fuera, no en la unión) → al escritorio de ese lado
                float ex = e.getX(), edgeW = 26 * dp;
                int ed = ex < edgeW && (vertical || side == 0) ? -1 : ex > W - edgeW && (vertical || side == 1) ? 1 : 0;
                if (ed != edgeDir) { edgeDir = ed; removeCallbacks(edgeFlip); if (ed != 0) postDelayed(edgeFlip, 650); }
                if (host != null) host.onDrag(dragging, dragX, dragY);
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_POINTER_UP: {
                if (mode == PINCH && pressed != null) { commit(pressed); mode = NONE; pressed = null; }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                removeCallbacks(longPress);
                removeCallbacks(edgeFlip);
                edgeDir = 0;
                Item it = pressed; pressed = null;
                int m = mode; mode = NONE;
                if (m == SCRUB) {
                    float vx = 0;
                    if (vt != null) { vt.addMovement(e); vt.computeCurrentVelocity(1000); vx = vt.getXVelocity(); vt.recycle(); vt = null; }
                    if (e.getActionMasked() == MotionEvent.ACTION_CANCEL) { scrubbing = false; invalidate(); return true; }
                    float[] l = scrubItem != null ? toLocal(scrubItem, e.getX(), e.getY()) : downLocal;
                    scrubEnd(moved, l[0], l[2], vx);
                    scrubItem = null;
                    return true;
                }
                if (m == SWIPE) {
                    float vx = 0;
                    if (vt != null) { vt.addMovement(e); vt.computeCurrentVelocity(1000); vx = vt.getXVelocity(); vt.recycle(); vt = null; }
                    if (!moved) return true;
                    int dir = 0;
                    if ((slide < -W * 0.22f || vx < -900 * dp / 2.6f) && page < pages - 1) dir = 1;
                    else if ((slide > W * 0.22f || vx > 900 * dp / 2.6f) && page > 0) dir = -1;
                    if (dir != 0) goTo(page + dir, slide + dir * W);
                    else animateSlide(slide);
                    return true;
                }
                if (it == null) return true;
                if (m == ROTATE || m == RESIZE || m == PINCH) { commit(it); return true; }
                double vel = 0;   // velocidad hacia el otro teléfono, en pantallas por segundo
                if (vt != null) {
                    vt.addMovement(e); vt.computeCurrentVelocity(1000);
                    vel = vertical ? vt.getYVelocity() / H : vt.getXVelocity() / W;
                    vt.recycle(); vt = null;
                }
                if (!moved) {
                    if (e.getActionMasked() == MotionEvent.ACTION_UP && !longPressed) tap(it);
                    return true;
                }
                dragging = null;
                double a = vertical ? dragY : dragX, b = vertical ? dragX : dragY, sz = alongSize(it);
                double szB = vertical ? it.w : it.hPx / H;
                double over = side == 0 ? (a + sz) - 1 : 1 - a;   // cuánto pasó al otro teléfono
                boolean flung = side == 0 ? vel > 1.6 : vel < -1.6;
                if (over > sz * 0.5 || (flung && over > -sz * 0.6)) {
                    // pasó más de la mitad (o se lanzó): queda entero en el otro teléfono
                    a = side == 0 ? 1.03 + Math.random() * 0.05 : 1 - 0.03 - sz - Math.random() * 0.05;
                    if (it == editing) finishEditing();
                } else {
                    // queda donde se soltó: se puede usar todo el espacio, incluso montado sobre la unión
                    a = clamp(a, -0.6 * sz, 2 - 0.4 * sz);
                }
                b = clamp(b, -0.6 * szB, 1 - 0.4 * szB);
                it.x = vertical ? b : a;
                it.y = vertical ? a : b;
                if (snapGrid) snapItem(it);
                commit(it);
                if (host != null) host.onDragEnd(it);
                if (autoApps && Item.APP.equals(it.kind)) arrangeApps();
                return true;
            }
        }
        return true;
    }

    private static double clampW(double w) { return Math.max(0.15, Math.min(1.0, w)); }

    /** Facilita dejar el elemento derecho: se "pega" a 0°, 90°, 180° y 270°. */
    private static float snap(float deg) {
        float d = ((deg % 360) + 540) % 360 - 180;   // a -180..180
        for (int t = -180; t <= 180; t += 90) if (Math.abs(d - t) < 6) return t == -180 ? 180 : t;
        return d;
    }

    private void tap(Item it) {
        if ("send".equals(pressedBtn)) sendAcross(it);
        else if ("del".equals(pressedBtn)) {
            if (it == editing) editing = null;
            items.remove(it); invalidate();
            if (host != null) host.onItemDeleted(it);
        }
        else if (it == editing) finishEditing();
        else if (Item.NOTE.equals(it.kind)) { if (host != null) host.onEditNote(it); }
        else if (Item.LINK.equals(it.kind) && "open".equals(pressedBtn)) { if (host != null) host.onOpenLink(it); }
        else if (Item.APP.equals(it.kind)) { if (host != null) host.onOpenApp(it); }
        else if (Item.WIDGET.equals(it.kind)) {
            if (host == null) return;
            float bar = widgetBar();
            if (it.wid >= 0 && downLocal != null && downLocal[1] > bar && downLocal[3] > bar)
                host.onWidgetTouch(it, downLocal[0] / downLocal[2], (downLocal[1] - bar) / (downLocal[3] - bar));
            else host.onWidgetTap(it);
        }
        else if (Item.CLOCK.equals(it.kind)) { if (host != null) host.onOpenAlarms(); }
        else if (Item.RECENTS.equals(it.kind)) {
            if (downLocal == null || host == null) return;
            if (onPin(downLocal[0], downLocal[1], downLocal[2])) { it.pin = !it.pin; if (it.pin) it.page = page; commit(it); }
            else if (!recentsAccess) host.onRecent(null, null);
        }
        else { it.z = System.currentTimeMillis(); invalidate(); }
    }
}
