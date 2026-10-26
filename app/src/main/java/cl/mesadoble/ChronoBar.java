package cl.mesadoble;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Region;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Action bar de ChronOS. Una franja delgada pegada a un borde de la pantalla y una "pestaña" más alta con un
 * corte en diagonal:
 *   CLOCK (principal): la pestaña a la izquierda tiene el reloj con la fecha; a su derecha salen las notificaciones.
 *   NAV   (extendido): franja recta con un "tope" en diagonal que sube a la pestaña del logo (menú inicio), a la
 *                      derecha; en la franja, la barra de tareas (apps abiertas del principal, como en Windows).
 * La barra es un FICHERO: la banda de notificaciones y el reproductor son hojas aparte, más claras, que asoman por
 * detrás del fichero (se dibujan antes que él). Con música, la hoja del reproductor sale de detrás y empuja la de
 * las notificaciones un renglón más afuera.
 * edgeTop: la franja va pegada al borde de arriba (la pestaña cuelga hacia abajo) o al de abajo (sube).
 * Deslizar la barra hacia el centro de la pantalla abre el gestor de archivos; al revés, lo cierra.
 */
public class ChronoBar extends FrameLayout {

    static final int CLOCK = 0, NAV = 1;

    public interface Listener {
        void onBarSwipe(ChronoBar bar, boolean down);
        void onClockTap();
        void onLogoTap();
        void onLogoLong();
    }

    final int kind;
    private final float dp;
    private boolean edgeTop, night, playerOn;
    private Listener listener;
    private final Path shape = new Path();
    private final Region region = new Region();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG),
            shadow = new Paint(Paint.ANTI_ALIAS_FLAG), gloss = new Paint(Paint.ANTI_ALIAS_FLAG),
            clockBox = new Paint(Paint.ANTI_ALIAS_FLAG), timeP = new Paint(Paint.ANTI_ALIAS_FLAG), dateP = new Paint(Paint.ANTI_ALIAS_FLAG),
            logoBox = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int[] me = new int[2], other = new int[2];
    private View navRow, logo, player, notif, ticker;
    private final android.graphics.drawable.Drawable logoDrawable;

    public ChronoBar(Context c, int kind, boolean night) {
        super(c);
        this.kind = kind;
        this.night = night;
        dp = c.getResources().getDisplayMetrics().density;
        setWillNotDraw(false);
        setClipChildren(false);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(1.2f * dp);
        timeP.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        dateP.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        logoDrawable = c.getDrawable(R.drawable.chronos);
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    public void setListener(Listener l) { listener = l; }
    private boolean badge;
    /** Círculo rojo sobre el logo (por ejemplo: hay una actualización). */
    public void setBadge(boolean b) { if (badge != b) { badge = b; invalidate(); } }
    public boolean edgeTop() { return edgeTop; }
    public void setEdgeTop(boolean t) { if (edgeTop != t) { edgeTop = t; requestLayout(); invalidate(); placePlayer(); } }
    public void setNight(boolean n) { night = n; invalidate(); }
    /** Carpeta abierta: la franja sigue en el gestor de archivos (mismo color), así que su borde exterior no se dibuja. */
    private boolean open;
    public void setOpen(boolean o) { if (open != o) { open = o; invalidate(); } }
    /** Borde exterior de la franja: con la carpeta abierta queda fuera de la vista (sin línea ni brillo). */
    private float y0() { return open ? (edgeTop ? -3 * dp : getHeight() + 3 * dp) : y(0); }

    // ---------------- medidas ----------------

    // extendido: renglón de la barra de tareas (abajo), renglón de banda/reproductor (arriba) y espacio de arriba
    // extendido: renglón de la barra de tareas; hojas: lo que asoma del reproductor (PV) y de la banda (TV), y lo
    // que queda escondido detrás del fichero (OVL)
    static final float ROW_T = 38, PV = 46, TV = 34, OVL = 12;
    float strip() { return (kind == NAV ? 4 + ROW_T + 4 : 36) * dp; }
    private boolean tickerOn() { return ticker != null && ticker.getVisibility() == VISIBLE; }
    /** Avance del reproductor al abrirse (0 = cerrado, 1 = abierto): sale desde el logo hacia la izquierda. */
    private float pp;
    private android.animation.ValueAnimator ppAnim;
    float tab() { return kind == NAV ? strip() + (PV + 6) * dp : 74 * dp; }
    float diag() { return 46 * dp; }
    float tabW() { return kind == CLOCK ? Math.min(getWidth() * 0.6f, Math.max(200 * dp, clockNeeded())) : logoW() + timeW() * tp; }
    /** Ancho de la zona del logo (extendido). */
    float logoW() { return 112 * dp; }

    // ---- hora junto al logo (extendido): sale hacia la izquierda cuando el principal muestra otra app
    private float tp;   // 0 = escondida, 1 = visible
    private android.animation.ValueAnimator tpAnim;
    private boolean timeOn;
    private final Paint navTimeP = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float timeSize() { return Math.min(tab() - 18 * dp, 52 * dp) * 0.5f; }

    /** Ancho que ocupa la hora (siempre el de "00:00", para que la pestaña no cambie de tamaño cada minuto). */
    private float tsCache = -1, twCache;
    private float timeW() {
        if (kind != NAV) return 0;
        float ts = timeSize();
        if (ts != tsCache) {   // se mide una sola vez: tabW() se pide muchas veces por cuadro
            navTimeP.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            navTimeP.setTextSize(ts);
            twCache = navTimeP.measureText("00:00") + 22 * dp;
            tsCache = ts;
        }
        return twCache;
    }

    /** Muestra u oculta la hora: aparece de derecha a izquierda junto al logo y se va de izquierda a derecha. */
    public void showTime(boolean on) {
        if (kind != NAV || timeOn == on) return;
        timeOn = on;
        if (tpAnim != null) tpAnim.cancel();
        tpAnim = android.animation.ValueAnimator.ofFloat(tp, on ? 1f : 0f);
        tpAnim.setDuration(on ? 420 : 340);
        tpAnim.setInterpolator(on ? new android.view.animation.DecelerateInterpolator(1.5f) : new android.view.animation.AccelerateDecelerateInterpolator());
        tpAnim.addUpdateListener(a -> { tp = (float) a.getAnimatedValue(); requestLayout(); invalidate(); });
        tpAnim.start();
    }
    public boolean timeShown() { return timeOn; }

    /** Ancho que necesita la caja del reloj para que la hora y la fecha queden adentro (también junto al corte diagonal). */
    private float clockNeeded() {
        float pad = 8 * dp, h = tab() - 2 * pad, cut = h * 0.75f;
        Date now = new Date();
        boolean is24 = android.text.format.DateFormat.is24HourFormat(getContext());
        String time = new SimpleDateFormat(is24 ? "HH:mm" : "h:mm", Locale.getDefault()).format(now);
        String day = new SimpleDateFormat("EEEE", new Locale("es")).format(now);
        String date = new SimpleDateFormat("d MMM yyyy", new Locale("es")).format(now).replace(".", "");
        timeP.setTextSize(h * 0.62f);
        dateP.setTextSize(Math.min(12.5f * dp, h * 0.3f));
        float dateW = Math.max(dateP.measureText(day), dateP.measureText(date));
        return pad + 12 * dp + timeP.measureText(time) + 10 * dp + dateW + cut + 18 * dp;
    }
    /** Alto de la barra (lo que ocupa en la pantalla). */
    int barHeight() { return Math.round(kind == NAV ? strip() + (PV + TV + 8) * dp : 74 * dp); }
    /** Lo que se ve de la barra con sus hojas (para dejar espacio al gestor de archivos y a la burbuja). */
    int visualHeight() {
        if (kind != NAV) return Math.round(Math.max(tab(), strip() + (tickerOn() ? TV * dp : 0)));
        return Math.round(Math.max(tab(), strip() + (tickerOn() ? TV * dp : 0) + pp * PV * dp));
    }

    private float y(float depth) { return edgeTop ? depth : getHeight() - depth; }

    public void setNavRow(View v) { navRow = v; addView(v, new LayoutParams(-2, -2)); }
    public void setPlayer(View v) { player = v; addView(v, new LayoutParams(-1, -2)); v.setVisibility(GONE); }
    public void setNotif(View v) { notif = v; addView(v, new LayoutParams(-2, -2)); }
    /** Banda de notificaciones tipo noticiero: corre en la franja, a la derecha del reloj. */
    public void setTicker(View v) { ticker = v; addView(v, new LayoutParams(-2, -2)); }
    /** Barra de tareas del extendido: las apps abiertas del principal. */
    private View taskbar, quick;
    /** Accesos rápidos (linterna, bluetooth…): cuadros en la franja de la barra del reloj. */
    public void setQuick(View v) { quick = v; addView(v, new LayoutParams(-2, -2)); }
    public void setTaskbar(View v) { taskbar = v; addView(v, new LayoutParams(-2, -2)); }

    /** Renglón [cerca, lejos] del borde (en dp) → rectángulo en la vista, según si la barra está arriba o abajo. */
    private void placeRow(View v, float x0, float x1, float near, float far) {
        int top = Math.round(edgeTop ? near * dp : getHeight() - far * dp), h = Math.round((far - near) * dp);
        v.layout(Math.round(x0), top, Math.round(Math.max(x0 + 1, x1)), top + h);
    }

    // ---- apps recientes: pestaña a la izquierda de la barra del extendido (se esconde con el gestor de archivos)
    private View recents;
    private float rp = 1f;   // 0 = escondida, 1 = visible
    private android.animation.ValueAnimator rpAnim;
    public void setRecents(View v) { recents = v; addView(v, new LayoutParams(-2, -2)); }
    /** Ancho de la pestaña de apps recientes. */
    float recW() { return recWFor(getWidth()); }
    /** Con música la pestaña de recientes se achica a solo la mira: el reproductor usa casi todo el ancho. */
    float recWFor(float W) {
        if (recents == null) return 0;
        float full = Math.min(W * 0.27f, 250 * dp), mini = Math.min(full, 74 * dp);
        return rp * (full - (full - mini) * pp);
    }

    public void showRecents(boolean on) {
        if (recents == null) return;
        if (rpAnim != null) rpAnim.cancel();
        if (on) recents.setVisibility(VISIBLE);
        rpAnim = android.animation.ValueAnimator.ofFloat(rp, on ? 1f : 0f);
        rpAnim.setDuration(300);
        rpAnim.addUpdateListener(a -> { rp = (float) a.getAnimatedValue(); recents.setAlpha(rp); requestLayout(); invalidate(); placePlayer(); });
        rpAnim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) { if (rp < 0.01f) recents.setVisibility(GONE); }
        });
        rpAnim.start();
    }

    public void showPlayer(boolean on) {
        if (playerOn == on) return;
        playerOn = on;
        if (player != null && on) player.setVisibility(VISIBLE);
        if (ppAnim != null) ppAnim.cancel();
        ppAnim = android.animation.ValueAnimator.ofFloat(pp, on ? 1f : 0f);
        ppAnim.setDuration(on ? 520 : 360);
        ppAnim.setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f));
        ppAnim.addUpdateListener(a -> { pp = (float) a.getAnimatedValue(); placePlayer(); invalidate(); });
        ppAnim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) { if (!playerOn && player != null) player.setVisibility(GONE); }
        });
        ppAnim.start();
    }

    /** Borde izquierdo de la parte alta (donde está el reproductor). */
    private float tallLeft() {
        float lmax = getWidth() - tabW(), lmin = recW() + diag() * 1.6f + 6 * dp;
        return lmax - pp * (lmax - lmin);
    }

    /** El reproductor se descubre de derecha a izquierda, junto con la parte alta de la barra. */
    private void placePlayer() {
        if (kind == NAV) {   // la hoja del reproductor sale de detrás del fichero y empuja la banda un renglón más afuera
            float out = edgeTop ? 1 : -1;
            if (player != null) player.setTranslationY(-out * (1 - pp) * (PV + 4) * dp);
            if (ticker != null) ticker.setTranslationY(out * pp * PV * dp);
            if (onTickerMove != null) onTickerMove.run();
            invalidate();
            return;
        }
        if (player == null || getWidth() == 0) return;
        float lmin = player.getLeft(), L = tallLeft();
        player.setTranslationX(L - lmin);
        int vis = Math.max(0, Math.round(getWidth() - tabW() - L - 6 * dp));
        player.setClipBounds(new android.graphics.Rect(0, 0, vis, player.getHeight()));
        player.setAlpha(Math.min(1f, pp * 1.4f));
    }
    public boolean playerShown() { return playerOn; }
    /** Avisa cuando la banda cambia de renglón (para cambiar su fondo). */
    Runnable onTickerMove;
    float playerProgress() { return pp; }

    @Override protected void onMeasure(int ws, int hs) {
        int W = MeasureSpec.getSize(ws), H = barHeight();
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            if (v == quick) {   // accesos rápidos: en la franja, a la derecha del corte del reloj
                float qw = W - tabW() - diag() - 14 * dp;
                v.measure(MeasureSpec.makeMeasureSpec(Math.max(1, Math.round(qw)), MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(Math.round(strip()), MeasureSpec.EXACTLY));
                continue;
            }
            if (kind == NAV && (v == player || v == ticker || v == taskbar)) {
                // la hoja del reproductor llega hasta debajo de la pestaña; la banda termina donde empieza la pestaña
                float rowW = v == taskbar ? W - Math.max(tabW(), logoW() + timeW()) - 14 * dp : v == player ? W - tabW() + 10 * dp : W - tabW() - 14 * dp;
                float rh = v == taskbar ? ROW_T : v == player ? OVL + PV : OVL + TV;
                v.measure(MeasureSpec.makeMeasureSpec(Math.max(1, Math.round(rowW)), MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(Math.round(rh * dp), MeasureSpec.EXACTLY));
                continue;
            }
            if (kind == CLOCK && v == ticker) {   // hoja de la banda: asoma bajo la franja, desde detrás del reloj
                v.measure(MeasureSpec.makeMeasureSpec(Math.max(1, Math.round(W - tabW() + 12 * dp - 10 * dp)), MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(Math.round((OVL + TV) * dp), MeasureSpec.EXACTLY));
                continue;
            }
            if (v == player) v.measure(MeasureSpec.makeMeasureSpec(Math.max(0, Math.round(W - tabW() - recWFor(W) - diag() * 1.6f - 12 * dp)), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(Math.round(98 * dp - strip() - 6 * dp), MeasureSpec.EXACTLY));
            else if (v == recents) v.measure(MeasureSpec.makeMeasureSpec(Math.max(1, Math.round(recWFor(W) / Math.max(0.01f, rp) - 10 * dp)), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(Math.round(74 * dp - 8 * dp), MeasureSpec.EXACTLY));
            else if (v == ticker) v.measure(MeasureSpec.makeMeasureSpec(Math.max(1, Math.round(W - tabW() - diag() * 0.5f - 14 * dp)), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(Math.round(strip() - 4 * dp), MeasureSpec.EXACTLY));
            else if (v == notif) v.measure(MeasureSpec.makeMeasureSpec(Math.round(Math.min(W - tabW() - diag() * 0.4f - 10 * dp, 520 * dp)), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(Math.round(tab() - 10 * dp), MeasureSpec.EXACTLY));
            else v.measure(MeasureSpec.makeMeasureSpec(W, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(Math.round(strip()), MeasureSpec.AT_MOST));
        }
        setMeasuredDimension(W, H);
    }

    @Override protected void onLayout(boolean ch, int l, int t, int r, int b) {
        int W = r - l, H = b - t;
        if (kind == NAV) {
            float x0 = 6 * dp;
            float S = strip() / dp;
            if (taskbar != null) placeRow(taskbar, x0, x0 + taskbar.getMeasuredWidth(), 4, 4 + ROW_T);
            if (player != null) placeRow(player, 8 * dp, 8 * dp + player.getMeasuredWidth(), S - OVL, S + PV);
            if (ticker != null) placeRow(ticker, 14 * dp, 14 * dp + ticker.getMeasuredWidth(), S - OVL, S + TV);
            paperInsets();
            if (navRow != null) navRow.layout(0, 0, 0, 0);
            placePlayer();
            return;
        }
        if (navRow != null) {
            int x = (W - navRow.getMeasuredWidth()) / 2, cy = Math.round(edgeTop ? strip() / 2 : H - strip() / 2);
            navRow.layout(x, cy - navRow.getMeasuredHeight() / 2, x + navRow.getMeasuredWidth(), cy + navRow.getMeasuredHeight() / 2);
        }
        if (player != null) {
            int x = Math.round(W - tabW() - 6 * dp - player.getMeasuredWidth()), h = player.getMeasuredHeight();
            int top = Math.round(edgeTop ? strip() + 2 * dp : H - strip() - 2 * dp - h);
            player.layout(x, top, x + player.getMeasuredWidth(), top + h);
            placePlayer();
        }
        if (recents != null) {
            int x = Math.round(4 * dp), h = recents.getMeasuredHeight();
            int top = Math.round(edgeTop ? 4 * dp : H - 4 * dp - h);
            recents.layout(x, top, x + recents.getMeasuredWidth(), top + h);
        }
        if (ticker != null) {
            float x = tabW() - 12 * dp, S = strip() / dp;
            placeRow(ticker, x, x + ticker.getMeasuredWidth(), S - OVL, S + TV);
            paperInsets();
        }
        if (quick != null) {
            int x = Math.round(W - 6 * dp - quick.getMeasuredWidth()), top = Math.round(edgeTop ? 0 : H - strip());
            quick.layout(x, top, x + quick.getMeasuredWidth(), top + quick.getMeasuredHeight());
        }
        if (notif != null) {
            int x = Math.round(tabW() + diag() * 0.4f), h = notif.getMeasuredHeight();
            int top = Math.round(edgeTop ? 4 * dp : H - 4 * dp - h);
            notif.layout(x, top, x + notif.getMeasuredWidth(), top + h);
        }
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) { super.onSizeChanged(w, h, ow, oh); buildShape(); }

    private void buildShape() {
        float W = getWidth(), S = strip(), T = tab(), D = diag(), TW = tabW(), rr = 14 * dp;
        shape.reset();
        if (kind == CLOCK) {
            // pestaña a la izquierda (reloj), diagonal, franja hacia la derecha
            shape.moveTo(0, y0());
            shape.lineTo(W, y0());
            shape.lineTo(W, y(S));
            shape.lineTo(TW + D, y(S));
            shape.lineTo(TW, y(T));
            shape.lineTo(0, y(T));
        } else if (pp > 0.001f && false) {
            // con música: la parte alta crece desde el logo hacia la izquierda, con su diagonal
            float L = tallLeft();
            shape.moveTo(0, y0());
            shape.lineTo(W, y0());
            shape.lineTo(W, y(T - rr));
            shape.quadTo(W, y(T), W - rr, y(T));
            shape.lineTo(L, y(T));
            shape.lineTo(L - D * 0.6f, y(S));
            recentsEdge(S, D);
        } else {
            // franja; diagonal y pestaña a la derecha (logo)
            shape.moveTo(0, y0());
            shape.lineTo(W, y0());
            shape.lineTo(W, y(T - rr));
            shape.quadTo(W, y(T), W - rr, y(T));
            shape.lineTo(W - TW, y(T));
            shape.lineTo(W - TW - D * 0.7f, y(S));
            shape.lineTo(0, y(S));
        }
        shape.close();
        region.setPath(shape, new Region(0, 0, Math.max(1, (int) W), Math.max(1, getHeight())));
    }

    /** Pestaña de apps recientes a la izquierda (de la franja sube en diagonal). */
    private void recentsEdge(float S, float D) {
        float RW = recW(), T0 = 74 * dp;
        if (RW < 1) { shape.lineTo(0, y(S)); return; }
        shape.lineTo(RW + D * 0.8f * rp, y(S));
        shape.lineTo(RW, y(T0));
        shape.lineTo(0, y(T0));
    }

    // ---------------- dibujo ----------------

    /** Lo escondido de cada hoja (detrás del fichero) no lleva contenido: se corre al lado que asoma. */
    private void paperInsets() {
        int o = Math.round(OVL * dp);
        if (ticker instanceof TickerView) ((TickerView) ticker).setHidden(o, !edgeTop);
        // a la derecha, la hoja del reproductor queda debajo de la pestaña del logo: sus botones (la X) no van ahí
        int right = kind == NAV ? Math.round(diag() * 0.7f + 22 * dp) : player == null ? 0 : player.getPaddingRight();
        if (player != null) player.setPadding(player.getPaddingLeft(), edgeTop ? o : 0, right, edgeTop ? 0 : o);
    }

    private final Paint paperShadow = new Paint(Paint.ANTI_ALIAS_FLAG);

    @Override protected void dispatchDraw(Canvas c) {
        long t = getDrawingTime();
        // 1) las hojas (reproductor y banda), con su sombra; 2) el fichero encima; 3) lo que va sobre el fichero
        for (View v : new View[]{ticker, player}) {
            if (v == null || v.getVisibility() != VISIBLE || v.getAlpha() <= 0.01f) continue;
            paperShadow.setColor(0x01000000);
            paperShadow.setShadowLayer(8 * dp, 0, (edgeTop ? 2 : -2) * dp, night ? 0x80000000 : 0x40000000);
            float ty = v.getTranslationY();
            c.drawRoundRect(v.getLeft(), v.getTop() + ty, v.getRight(), v.getBottom() + ty, 12 * dp, 12 * dp, paperShadow);
            drawChild(c, v, t);
        }
        drawFolder(c);
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            if (v != ticker && v != player && v.getVisibility() == VISIBLE) drawChild(c, v, t);
        }
    }

    @Override protected void onDraw(Canvas c) { }

    private void drawFolder(Canvas c) {
        buildShape();
        float W = getWidth(), H = getHeight();
        // sombra suave hacia el centro de la pantalla
        c.save();
        c.clipOutPath(shape);
        shadow.setColor(0x01000000);
        shadow.setShadowLayer(12 * dp, 0, (edgeTop ? 4 : -4) * dp, night ? 0x88000000 : 0x55000000);
        c.drawPath(shape, shadow);
        c.restore();
        c.save();
        c.clipPath(shape);
        paintBody(c, this, W, H, night, fill);
        // brillo desde el borde de la pantalla
        if (!open) {
            gloss.setShader(new LinearGradient(0, y(0), 0, y(tab()), 0x40FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, W, H, gloss);
        }
        c.restore();
        int st = CardTheme.stroke();
        stroke.setColor(st != 0 ? st : (CardTheme.dark(CardTheme.barFill(night) | 0xFF000000) ? 0x33FFFFFF : 0x22000000));
        c.drawPath(shape, stroke);

        if (kind == CLOCK) drawClock(c);
        else drawLogo(c);
    }

    /** Relleno de la barra (color, cristal o Aero). También lo usa el gestor de archivos: es el cuerpo de la carpeta. */
    static void paintBody(Canvas c, View self, float W, float H, boolean night, Paint fill) {
        if (CardTheme.isIntense() && Glass.blur != null && Glass.desk.get() != null) {
            View d = Glass.desk.get();
            int[] me = new int[2], other = new int[2];
            self.getLocationInWindow(me);
            d.getLocationInWindow(other);
            c.save();
            c.translate(other[0] - me[0], other[1] - me[1]);
            Glass.drawBlur(c, d.getWidth(), d.getHeight());
            c.restore();
            fill.setShader(null);
            fill.setColor(Glass.darkDesk() ? 0x33FFFFFF : 0x66FFFFFF);
            c.drawRect(0, 0, W, H, fill);
        } else if (CardTheme.isAero()) {
            int tint = CardTheme.aeroTint();
            boolean blurred = Glass.blur != null && Glass.desk.get() != null;
            if (blurred) {   // Aero: la mesa borrosa detrás del vidrio de color
                View d = Glass.desk.get();
                int[] me = new int[2], other = new int[2];
                self.getLocationInWindow(me);
                d.getLocationInWindow(other);
                c.save();
                c.translate(other[0] - me[0], other[1] - me[1]);
                Glass.drawBlur(c, d.getWidth(), d.getHeight());
                c.restore();
            }
            fill.setShader(new LinearGradient(0, 0, 0, H, (tint & 0x00FFFFFF) | (blurred ? 0x78000000 : 0x90000000),
                    (CardTheme.shade(tint, -0.25f) & 0x00FFFFFF) | (blurred ? 0x90000000 : 0xA8000000), Shader.TileMode.CLAMP));
            c.drawRect(0, 0, W, H, fill);
        } else {
            fill.setShader(null);
            int f = CardTheme.barFill(night);
            fill.setColor(CardTheme.isGlass() ? f : (f | 0xFF000000));
            c.drawRect(0, 0, W, H, fill);
        }
    }

    private boolean lightText() {
        if (CardTheme.isAero()) return false;
        if (CardTheme.isGlass()) return Glass.darkDesk() || CardTheme.glass == CardTheme.GLASS_DARK;
        return CardTheme.dark(CardTheme.barFill(night) | 0xFF000000);
    }

    private void drawClock(Canvas c) {
        float T = tab(), TW = tabW(), pad = 8 * dp;
        float top = edgeTop ? pad : getHeight() - T + pad, bottom = edgeTop ? T - pad : getHeight() - pad;
        float right = TW - 6 * dp;
        boolean lt = lightText();
        // caja del reloj (más clara/oscura que la barra)
        clockBox.setColor(lt ? 0x26FFFFFF : 0xCCFFFFFF);
        Path box = new Path();
        float cut = (bottom - top) * 0.75f;
        if (edgeTop) {
            box.moveTo(pad + 4 * dp, top); box.lineTo(right, top); box.lineTo(right - cut, bottom); box.lineTo(pad + 4 * dp, bottom);
        } else {
            box.moveTo(pad + 4 * dp, top); box.lineTo(right - cut, top); box.lineTo(right, bottom); box.lineTo(pad + 4 * dp, bottom);
        }
        box.close();
        c.drawPath(box, clockBox);
        int ink = lt ? 0xFFF5F8FA : 0xFF1D2733;
        Date now = new Date();
        boolean is24 = android.text.format.DateFormat.is24HourFormat(getContext());
        String time = new SimpleDateFormat(is24 ? "HH:mm" : "h:mm", Locale.getDefault()).format(now);
        String day = new SimpleDateFormat("EEEE", new Locale("es")).format(now);
        String date = new SimpleDateFormat("d MMM yyyy", new Locale("es")).format(now).replace(".", "");
        float h = bottom - top;
        timeP.setColor(ink);
        timeP.setTextSize(h * 0.62f);
        if (CardTheme.isAero()) Aero.glowText(timeP, dp); else timeP.clearShadowLayer();
        float tx = pad + 12 * dp, ty = top + h * 0.5f + timeP.getTextSize() * 0.36f;
        c.drawText(time, tx, ty, timeP);
        float dx = tx + timeP.measureText(time) + 10 * dp;
        dateP.setColor((ink & 0x00FFFFFF) | 0xCC000000);
        dateP.setTextSize(Math.min(12.5f * dp, h * 0.3f));
        float maxW = right - cut - dx - 4 * dp;
        if (maxW > 30 * dp) {
            c.drawText(Character.toUpperCase(day.charAt(0)) + day.substring(1), dx, top + h * 0.44f, dateP);
            c.drawText(date, dx, top + h * 0.44f + dateP.getTextSize() * 1.25f, dateP);
        }
        // el minuto siguiente
        postInvalidateDelayed(60_000 - System.currentTimeMillis() % 60_000 + 50);
    }

    private void drawLogo(Canvas c) {
        float T = tab(), TW = logoW(), W = getWidth();
        float s = Math.min(T - 18 * dp, 52 * dp);
        float cx = W - TW / 2 + 4 * dp, cy = edgeTop ? T / 2 + 2 * dp : getHeight() - T / 2 - 2 * dp;
        if (tp > 0.01f) drawTime(c, W, cy);
        // baterías: fijas, chiquitas, justo debajo de donde aparece la hora
        drawBatteries(c, W - logoW() + 6 * dp - timeW() / 2f, lightText() ? 0xFFF5F8FA : 0xFF1D2733);
        logoBox.setColor(lightText() ? 0x26FFFFFF : 0x99FFFFFF);
        c.drawRoundRect(cx - s * 0.9f, cy - s / 2 - 3 * dp, cx + s * 0.9f, cy + s / 2 + 3 * dp, 14 * dp, 14 * dp, logoBox);
        if (logoDrawable != null) {
            logoDrawable.setBounds(Math.round(cx - s / 2), Math.round(cy - s / 2), Math.round(cx + s / 2), Math.round(cy + s / 2));
            logoDrawable.draw(c);
        }
        if (badge) {
            float bx = cx + s * 0.85f, by = cy - s / 2;
            logoBox.setColor(0xFFFFFFFF);
            c.drawCircle(bx, by, 7.5f * dp, logoBox);
            logoBox.setColor(0xFFE53935);
            c.drawCircle(bx, by, 6 * dp, logoBox);
        }
    }

    /** La hora, a la izquierda del logo. Se recorta al borde de la pestaña (que va creciendo) y se desliza desde el logo. */
    private void drawTime(Canvas c, float W, float cy) {
        boolean is24 = android.text.format.DateFormat.is24HourFormat(getContext());
        String time = new SimpleDateFormat(is24 ? "HH:mm" : "h:mm", Locale.getDefault()).format(new Date());
        float tw = timeW(), zoneRight = W - logoW() + 6 * dp;
        navTimeP.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        navTimeP.setTextSize(timeSize());
        navTimeP.setTextAlign(Paint.Align.CENTER);
        int ink = lightText() ? 0xFFF5F8FA : 0xFF1D2733;
        navTimeP.setColor((ink & 0x00FFFFFF) | (Math.round(Math.min(1f, tp * 1.3f) * 255) << 24));
        if (CardTheme.isAero()) Aero.glowText(navTimeP, dp); else navTimeP.clearShadowLayer();
        float x = zoneRight - tw / 2f + (1 - tp) * tw;   // llega desde la derecha (desde el logo)
        c.save();
        c.clipRect(W - tabW(), 0, W, getHeight());
        c.drawText(time, x, cy + navTimeP.getTextSize() * 0.36f, navTimeP);
        c.restore();
        // el minuto siguiente
        postInvalidateDelayed(60_000 - System.currentTimeMillis() % 60_000 + 50);
    }

    /**
     * Baterías del principal (P) y del extendido (E), siempre a la vista: letra y barrita de pie, como la energía
     * de Mega Man (igual que el volumen del reproductor). Verde > 50 %, amarilla > 20 %, roja; letra amarilla = cargando.
     */
    private final Paint battP = new Paint(Paint.ANTI_ALIAS_FLAG);

    private void drawBatteries(Canvas c, float cx, int ink) {
        android.content.SharedPreferences pf = getContext().getSharedPreferences("mesa", android.content.Context.MODE_PRIVATE);
        int skin = pf.getInt("battSkin", 2);   // 0 no, 1 retro (Mega Man), 2 moderna (celular)
        if (skin == 0) return;
        boolean up = pf.getBoolean("battUp", false);
        Hub hub = Hub.get(getContext());
        boolean pr = hub.isPrincipal();
        int pl = pr ? hub.myBatt : hub.otherBatt, el = pr ? hub.otherBatt : hub.myBatt;
        boolean pc = pr ? hub.myChg : hub.otherChg, ec = pr ? hub.otherChg : hub.myChg;
        if (!hub.connected()) { if (pr) el = -1; else pl = -1; }
        battP.setTypeface(Typeface.DEFAULT_BOLD);
        battP.setTextSize(8 * dp);
        battP.setTextAlign(Paint.Align.LEFT);
        battP.clearShadowLayer();
        // medida de cada batería: de pie (angosta y alta) o acostada (ancha y baja)
        float bw = up ? (skin == 1 ? 3.5f : 6.5f) * dp : 15 * dp, bh = up ? 13 * dp : (skin == 1 ? 3.5f : 7) * dp;
        float lw = battP.measureText("P"), one = lw + 2.5f * dp + bw + (skin == 2 && !up ? 1.5f * dp : 0), sep = 7 * dp, total = 2 * one + sep;
        float mid = y(strip() / 2f);
        float x = cx - total / 2;
        battery(c, "P", pl, pc, x, mid, bw, bh, skin, up, ink);
        battery(c, "E", el, ec, x + one + sep, mid, bw, bh, skin, up, ink);
    }

    private static int battColor(int pct) { return pct < 0 ? 0xFF8A8A8A : pct <= 20 ? 0xFFFF3B30 : pct <= 50 ? 0xFFFFC400 : 0xFF3DDC84; }

    private void battery(Canvas c, String l, int pct, boolean chg, float x, float mid, float bw, float bh, int skin, boolean up, int ink) {
        battP.setStyle(Paint.Style.FILL);
        battP.setColor(chg ? 0xFFFFC400 : (ink & 0x00FFFFFF) | 0xCC000000);
        c.drawText(l, x, mid + 3 * dp, battP);
        x += battP.measureText(l) + 2.5f * dp;
        float top = mid - bh / 2, bot = mid + bh / 2, right = x + bw;
        int col = battColor(pct);
        float f = pct < 0 ? 0 : Math.max(0.04f, pct / 100f);
        if (skin == 1) {   // retro: rayitas de energía
            int segs = 5;
            float gap = 1 * dp, len = up ? bh : bw, seg = (len - gap * (segs - 1)) / segs;
            int lit = pct < 0 ? 0 : Math.max(pct > 0 ? 1 : 0, Math.round(pct / 100f * segs));
            battP.setColor(0x55000000);
            c.drawRoundRect(x - dp, top - dp, right + dp, bot + dp, 1.5f * dp, 1.5f * dp, battP);
            for (int i = 0; i < segs; i++) {
                battP.setColor(i < lit ? col : 0x2EFFFFFF);
                if (up) { float sb = bot - i * (seg + gap); c.drawRect(x, sb - seg, right, sb, battP); }
                else { float sl = x + i * (seg + gap); c.drawRect(sl, top, sl + seg, bot, battP); }
            }
            return;
        }
        // moderna: el ícono de batería de los celulares (contorno, puntita y relleno según la carga)
        int line = (ink & 0x00FFFFFF) | 0xCC000000;
        float sw = 1.1f * dp, in = 1.6f * dp, r = 1.8f * dp;
        battP.setStyle(Paint.Style.STROKE);
        battP.setStrokeWidth(sw);
        battP.setColor(line);
        c.drawRoundRect(x, top, right, bot, r, r, battP);
        battP.setStyle(Paint.Style.FILL);
        if (up) c.drawRoundRect(x + bw * 0.3f, top - 1.6f * dp, right - bw * 0.3f, top + 0.3f * dp, 0.6f * dp, 0.6f * dp, battP);
        else c.drawRoundRect(right - 0.3f * dp, mid - bh * 0.22f, right + 1.6f * dp, mid + bh * 0.22f, 0.6f * dp, 0.6f * dp, battP);
        battP.setColor(col);
        if (up) c.drawRoundRect(x + in, bot - in - (bh - 2 * in) * f, right - in, bot - in, 0.8f * dp, 0.8f * dp, battP);
        else c.drawRoundRect(x + in, top + in, x + in + (bw - 2 * in) * f, bot - in, 0.8f * dp, 0.8f * dp, battP);
        if (chg) {   // rayito de carga
            android.graphics.Path z = new android.graphics.Path();
            float cx = (x + right) / 2, s = Math.min(bw, bh) * 0.42f;
            z.moveTo(cx + s * 0.25f, mid - s * 1.1f); z.lineTo(cx - s * 0.55f, mid + s * 0.15f); z.lineTo(cx - s * 0.05f, mid + s * 0.15f);
            z.lineTo(cx - s * 0.25f, mid + s * 1.1f); z.lineTo(cx + s * 0.55f, mid - s * 0.15f); z.lineTo(cx + s * 0.05f, mid - s * 0.15f); z.close();
            battP.setColor(0xFFFFFFFF);
            c.drawPath(z, battP);
        }
    }

    boolean onLogo(float x, float y) {
        float T = tab(), TW = logoW();
        return kind == NAV && x > getWidth() - TW && (edgeTop ? y < T : y > getHeight() - T);
    }

    boolean onClock(float x, float y) {
        return kind == CLOCK && x < tabW() && (edgeTop ? y < tab() : y > getHeight() - tab());
    }

    // ---------------- toques: lo transparente deja pasar; deslizar abre/cierra el gestor ----------------

    private float downX, downY;
    private boolean swiping, longFired;
    private final Runnable longPress = () -> { longFired = true; if (listener != null) { performHapticFeedback(0); listener.onLogoLong(); } };

    @Override public boolean dispatchTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN && !region.contains((int) e.getX(), (int) e.getY())) {
            boolean onChild = false;
            for (View v : new View[]{notif, player, recents, ticker, taskbar})
                if (v != null && v.getVisibility() == VISIBLE && v.getAlpha() > 0.1f && e.getX() >= v.getLeft() && e.getX() <= v.getRight()
                        && e.getY() >= v.getTop() + v.getTranslationY() && e.getY() <= v.getBottom() + v.getTranslationY()) onChild = true;
            if (!onChild) return false;
        }
        return super.dispatchTouchEvent(e);
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: downX = e.getX(); downY = e.getY(); swiping = false; return false;
            case MotionEvent.ACTION_MOVE:
                float dy = e.getY() - downY, dx = e.getX() - downX;
                if (Math.abs(dy) > ViewConfiguration.get(getContext()).getScaledTouchSlop() * 1.5f && Math.abs(dy) > Math.abs(dx)) {
                    swiping = true;
                    return true;
                }
        }
        return false;
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX(); downY = e.getY(); swiping = false; longFired = false;
                if (onLogo(downX, downY) || onClock(downX, downY)) postDelayed(longPress, 500);
                return true;
            case MotionEvent.ACTION_MOVE:
                float dy = e.getY() - downY;
                if (Math.abs(dy) > ViewConfiguration.get(getContext()).getScaledTouchSlop() * 1.5f) { swiping = true; removeCallbacks(longPress); }
                return true;
            case MotionEvent.ACTION_UP: {
                removeCallbacks(longPress);
                float dy2 = e.getY() - downY;
                if (swiping && Math.abs(dy2) > 24 * dp) { if (listener != null) listener.onBarSwipe(this, dy2 > 0); }
                else if (!swiping && !longFired && listener != null) {
                    if (onLogo(e.getX(), e.getY())) listener.onLogoTap();
                    else if (onClock(e.getX(), e.getY())) listener.onClockTap();
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL: removeCallbacks(longPress); return true;
        }
        return true;
    }

    static int ink(boolean night) {
        if (CardTheme.isAero()) return 0xFF0B1A2A;
        if (CardTheme.isGlass()) return Glass.darkDesk() || CardTheme.glass == CardTheme.GLASS_DARK ? 0xFFF5F8FA : 0xFF1D2733;
        return CardTheme.dark(CardTheme.barFill(night) | 0xFF000000) ? 0xFFF5F8FA : 0xFF1D2733;
    }
}
