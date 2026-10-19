package cl.mesadoble;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Banda de notificaciones tipo noticiero: las notificaciones pendientes del principal corren de derecha a izquierda
 * en bucle hasta que se revisan (cuando la app las borra). El color dice la antigüedad: verde = recién llegada,
 * gris = vieja. Al tocar una, se abre su app en el principal.
 */
public class TickerView extends View {

    public interface Tap { void onNotifTap(String pkg); }

    private static final class Item {
        String pkg, text, app;
        long time;
        Bitmap icon;
        float w;
    }

    private final float dp;
    private final List<Item> items = new ArrayList<>();
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG), app = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pill = new Paint(Paint.ANTI_ALIAS_FLAG), dot = new Paint(Paint.ANTI_ALIAS_FLAG), bmp = new Paint(Paint.FILTER_BITMAP_FLAG),
            band = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float offset, total;
    private long last;
    private Tap tap;
    private final boolean floating;
    private int ink = 0xFFFFFFFF;

    public TickerView(Context c, boolean floating) {
        super(c);
        this.floating = floating;   // banda suelta arriba del extendido (con fondo propio)
        dp = c.getResources().getDisplayMetrics().density;
        text.setTextSize(13 * dp);
        app.setTextSize(11 * dp);
        app.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        app.setLetterSpacing(0.06f);
        band.setColor(0xCC0E1A22);
        setVisibility(GONE);
        if (floating) setElevation(8 * dp);
    }

    public void setTap(Tap t) { tap = t; }
    public void setInk(int c) { ink = c; invalidate(); }
    private int bandColor, hidden;
    private boolean hiddenBottom = true;
    /** Parte de la hoja escondida detrás de la barra (sin contenido): abajo (bottom) o arriba. */
    public void setHidden(int px, boolean bottom) { if (hidden != px || hiddenBottom != bottom) { hidden = px; hiddenBottom = bottom; invalidate(); } }
    /** Fondo propio de la banda (0 = sin fondo, va sobre la barra). */
    public void setBand(int color) { if (bandColor != color) { bandColor = color; invalidate(); } }

    /** Lista de notificaciones pendientes: [{pkg, app, title, text, time, icon}] (las más nuevas primero). */
    public void set(JSONArray a) {
        items.clear();
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            Item it = new Item();
            it.pkg = o.optString("pkg");
            it.app = o.optString("app").toUpperCase();
            String t = o.optString("title"), b = o.optString("text");
            it.text = t.isEmpty() ? b : b.isEmpty() ? t : t + ": " + b;
            it.text = TextUtils.ellipsize(it.text.replace('\n', ' '), text, 380 * dp, TextUtils.TruncateAt.END).toString();
            it.time = o.optLong("time");
            it.icon = decode(o.optString("icon"));
            it.w = 10 * dp + 18 * dp + 8 * dp + app.measureText(it.app) + 8 * dp + text.measureText(it.text) + 14 * dp;
            items.add(it);
        }
        total = 0;
        for (Item it : items) total += it.w + 12 * dp;
        setVisibility(items.isEmpty() ? GONE : VISIBLE);
        last = 0;
        invalidate();
    }

    private static final java.util.Map<String, Bitmap> cache = new java.util.HashMap<>();

    private static Bitmap decode(String b64) {
        if (b64 == null || b64.isEmpty()) return null;
        Bitmap b = cache.get(b64);
        if (b != null) return b;
        try {
            byte[] d = android.util.Base64.decode(b64, android.util.Base64.DEFAULT);
            b = android.graphics.BitmapFactory.decodeByteArray(d, 0, d.length);
            if (b != null && cache.size() < 60) cache.put(b64, b);
            return b;
        } catch (Exception e) { return null; }
    }

    public boolean hasItems() { return !items.isEmpty(); }

    /** Verde si es nueva, va pasando a gris en una hora. */
    private static int ageColor(long time) {
        float k = time <= 0 ? 1f : Math.max(0f, Math.min(1f, (System.currentTimeMillis() - time) / 3_600_000f));
        k = (float) Math.sqrt(k);   // cambia rápido al principio: lo de hace 10 minutos ya no es "nuevo"
        int g = 0xFF2ECC71, gray = 0xFF7D8B99;
        int r = Math.round(((g >> 16) & 255) * (1 - k) + ((gray >> 16) & 255) * k);
        int gg = Math.round(((g >> 8) & 255) * (1 - k) + ((gray >> 8) & 255) * k);
        int b = Math.round((g & 255) * (1 - k) + (gray & 255) * k);
        return 0xFF000000 | (r << 16) | (gg << 8) | b;
    }

    @Override protected void onDraw(Canvas c) {
        if (items.isEmpty()) return;
        float W = getWidth(), H = getHeight();
        if (floating) c.drawRoundRect(new RectF(0, 0, W, H), H / 2, H / 2, band);
        else if (bandColor != 0) {
            band.setColor(bandColor);
            float r = 12 * dp;   // hoja: esquinas apenas redondeadas
            c.drawRoundRect(new RectF(0, 0, W, H), r, r, band);
            band.setColor(0x26000000);
            band.setStyle(Paint.Style.STROKE);
            band.setStrokeWidth(dp);
            c.drawRoundRect(new RectF(dp / 2, dp / 2, W - dp / 2, H - dp / 2), r, r, band);
            band.setStyle(Paint.Style.FILL);
            band.setColor(0xCC0E1A22);
        }
        c.save();
        if (hidden > 0) { if (!hiddenBottom) c.translate(0, hidden); H -= hidden; }
        c.clipRect(floating ? H / 2 : bandColor != 0 ? 10 * dp : 0, 0, floating ? W - H / 2 : bandColor != 0 ? W - 10 * dp : W, H);
        long now = android.os.SystemClock.uptimeMillis();
        if (last != 0) offset += (now - last) * 0.055f * dp;   // ~55 dp por segundo
        last = now;
        if (total > 0) offset %= total;
        float x = W - offset;
        // se dibuja la cinta dos veces seguidas para que el bucle no tenga cortes
        float start = x - total * (float) Math.ceil(x / Math.max(1, total));
        for (float base = start; base < W; base += total) {
            float cx = base;
            for (Item it : items) {
                if (cx + it.w > 0 && cx < W) drawItem(c, it, cx, H);
                cx += it.w + 12 * dp;
            }
        }
        c.restore();
        postInvalidateOnAnimation();
    }

    private void drawItem(Canvas c, Item it, float x, float H) {
        int col = ageColor(it.time);
        float ph = Math.min(H - 6 * dp, 28 * dp), top = (H - ph) / 2;
        pill.setColor((col & 0x00FFFFFF) | 0x40000000);
        c.drawRoundRect(new RectF(x, top, x + it.w, top + ph), ph / 2, ph / 2, pill);
        dot.setColor(col);
        float ix = x + 10 * dp, cy = H / 2;
        if (it.icon != null) c.drawBitmap(it.icon, null, new RectF(ix, cy - 9 * dp, ix + 18 * dp, cy + 9 * dp), bmp);
        else c.drawCircle(ix + 9 * dp, cy, 5 * dp, dot);
        float tx = ix + 18 * dp + 8 * dp;
        app.setColor(col);
        c.drawText(it.app, tx, cy + app.getTextSize() * 0.36f, app);
        tx += app.measureText(it.app) + 8 * dp;
        text.setColor(ink);
        c.drawText(it.text, tx, cy + text.getTextSize() * 0.36f, text);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_DOWN) return true;
        if (e.getAction() != MotionEvent.ACTION_UP || items.isEmpty() || tap == null) return true;
        float W = getWidth(), x = W - offset, start = x - total * (float) Math.ceil(x / Math.max(1, total));
        for (float base = start; base < W; base += total) {
            float cx = base;
            for (Item it : items) {
                if (e.getX() >= cx && e.getX() <= cx + it.w) { tap.onNotifTap(it.pkg); return true; }
                cx += it.w + 12 * dp;
            }
        }
        return true;
    }
}
