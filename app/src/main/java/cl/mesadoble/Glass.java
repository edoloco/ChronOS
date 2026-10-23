package cl.mesadoble;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Random;

/**
 * "Cristal intenso": vidrio esmerilado realista. Lo que está detrás se ve borroso (una copia desenfocada del fondo
 * de la mesa), con sombra profunda, grano fino, brillo diagonal, sombreado interior y bordes de luz.
 */
final class Glass {

    /** Fondo de la mesa desenfocado (a 1/4 del tamaño), y la mesa que lo generó. */
    static Bitmap blur;
    static WeakReference<View> desk = new WeakReference<>(null);
    private static final ArrayList<WeakReference<Drawable>> users = new ArrayList<>();

    private static final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG), contact = new Paint(Paint.ANTI_ALIAS_FLAG),
            bmp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG), tint = new Paint(Paint.ANTI_ALIAS_FLAG),
            grain = new Paint(Paint.ANTI_ALIAS_FLAG), sheen = new Paint(Paint.ANTI_ALIAS_FLAG), deep = new Paint(Paint.ANTI_ALIAS_FLAG),
            rim = new Paint(Paint.ANTI_ALIAS_FLAG), hair = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static float shadowDp;
    static {
        rim.setStyle(Paint.Style.STROKE);
        hair.setStyle(Paint.Style.STROKE);
        Bitmap n = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
        Random r = new Random(7);
        int[] px = new int[96 * 96];
        for (int i = 0; i < px.length; i++) {
            int v = r.nextInt(256);
            px[i] = v > 127 ? Color.argb((v - 128) / 9, 255, 255, 255) : Color.argb((127 - v) / 9, 0, 0, 0);
        }
        n.setPixels(px, 0, 96, 0, 0, 96, 96);
        grain.setShader(new BitmapShader(n, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
    }

    private Glass() { }

    /** ¿Hace falta el fondo desenfocado? Cristal intenso y Aero (como en Windows 7, que desenfoca lo de atrás). */
    static boolean on() { return CardTheme.glass == CardTheme.GLASS_INTENSE || CardTheme.glass == CardTheme.GLASS_AERO; }

    /** ¿El fondo de la mesa es oscuro? (decide el color del texto y del vidrio) */
    static boolean darkDesk() { return CardTheme.dark(CardTheme.desk == 0 ? 0xFF18212B : CardTheme.desk); }

    static void setBlur(Bitmap b, View from) {
        blur = b;
        desk = new WeakReference<>(from);
        for (int i = users.size() - 1; i >= 0; i--) {
            Drawable d = users.get(i).get();
            if (d == null) users.remove(i); else d.invalidateSelf();
        }
    }

    /** Sombra profunda bajo el vidrio (se dibuja antes del cuerpo). */
    static void shadow(Canvas c, RectF r, float radius, float dp, boolean lifted) {
        if (shadowDp != dp) {
            shadowDp = dp;
            shadow.setMaskFilter(new BlurMaskFilter(18 * dp, BlurMaskFilter.Blur.NORMAL));
            contact.setMaskFilter(new BlurMaskFilter(3 * dp, BlurMaskFilter.Blur.NORMAL));
        }
        shadow.setColor(lifted ? 0x8C000000 : 0x6B000000);
        contact.setColor(0x47000000);
        float oy = (lifted ? 16 : 10) * dp;
        c.drawRoundRect(new RectF(r.left + 4 * dp, r.top + oy, r.right - 4 * dp, r.bottom + oy), radius, radius, shadow);
        c.drawRoundRect(new RectF(r.left, r.top + 1.5f * dp, r.right, r.bottom + 2 * dp), radius, radius, contact);
    }

    /**
     * Cuerpo del vidrio. 'backdrop' dibuja lo que hay detrás (ya recortado a la forma); si es null se usa un
     * relleno translúcido.
     */
    static void body(Canvas c, RectF r, float radius, float dp, Runnable backdrop) {
        boolean dark = darkDesk();
        Path clip = new Path();
        clip.addRoundRect(r, radius, radius, Path.Direction.CW);
        c.save();
        c.clipPath(clip);
        if (backdrop != null) backdrop.run();
        // esmerilado: velo blanco (más fuerte en fondos claros) y grano fino
        tint.setShader(null);
        tint.setColor(backdrop == null ? (dark ? 0x99202A36 : 0xB3F4F7FA) : dark ? 0x2EFFFFFF : 0x61FFFFFF);
        c.drawRect(r, tint);
        c.drawRect(r, grain);
        // brillo diagonal (luz que entra desde arriba a la izquierda)
        sheen.setShader(new LinearGradient(r.left, r.top, r.left + r.width() * 0.75f, r.top + r.height() * 1.1f,
                new int[]{0x73FFFFFF, 0x1FFFFFFF, 0x00FFFFFF, 0x00FFFFFF}, new float[]{0, 0.38f, 0.52f, 1}, Shader.TileMode.CLAMP));
        c.drawRect(r, sheen);
        // sombreado interior intenso: el vidrio se oscurece hacia abajo y en los bordes
        deep.setShader(new LinearGradient(0, r.top + r.height() * 0.45f, 0, r.bottom,
                0x00000000, dark ? 0x59000000 : 0x38000000, Shader.TileMode.CLAMP));
        c.drawRect(r, deep);
        c.restore();
        // bordes: luz arriba que se apaga hacia abajo, y una línea oscura exterior
        rim.setStrokeWidth(1.6f * dp);
        rim.setShader(new LinearGradient(0, r.top, 0, r.bottom, new int[]{0xF2FFFFFF, 0x59FFFFFF, 0x1AFFFFFF},
                new float[]{0, 0.4f, 1}, Shader.TileMode.CLAMP));
        float i = 1.1f * dp;
        c.drawRoundRect(new RectF(r.left + i, r.top + i, r.right - i, r.bottom - i), Math.max(0, radius - i), Math.max(0, radius - i), rim);
        hair.setStrokeWidth(1f * dp);
        hair.setColor(dark ? 0x80000000 : 0x40000000);
        c.drawRoundRect(new RectF(r.left + dp / 2, r.top + dp / 2, r.right - dp / 2, r.bottom - dp / 2), radius, radius, hair);
    }

    /** Dibuja el fondo desenfocado cubriendo la mesa completa (en coordenadas de la mesa). */
    static void drawBlur(Canvas c, float deskW, float deskH) {
        if (blur != null) c.drawBitmap(blur, null, new RectF(0, 0, deskW, deskH), bmp);
    }

    /** Texto legible sobre el vidrio. */
    static void text(Paint p, float dp) {
        if (darkDesk()) p.setShadowLayer(3 * dp, 0, 1 * dp, 0xB3000000);
        else p.setShadowLayer(4 * dp, 0, 0, 0xB3FFFFFF);
    }

    // ---------------- desenfoque ----------------

    /** Desenfoque de caja en 3 pasadas (se parece a un gaussiano). Modifica el bitmap. */
    static void boxBlur(Bitmap b, int radius) {
        int w = b.getWidth(), h = b.getHeight();
        int[] a = new int[w * h], t = new int[w * h];
        b.getPixels(a, 0, w, 0, 0, w, h);
        for (int pass = 0; pass < 3; pass++) {
            pass(a, t, w, h, radius, true);
            pass(t, a, w, h, radius, false);
        }
        b.setPixels(a, 0, w, 0, 0, w, h);
    }

    private static void pass(int[] in, int[] out, int w, int h, int r, boolean horizontal) {
        int len = horizontal ? w : h, lines = horizontal ? h : w, div = 2 * r + 1;
        for (int l = 0; l < lines; l++) {
            int base = horizontal ? l * w : l, stride = horizontal ? 1 : w;
            int sa = 0, sr = 0, sg = 0, sb = 0;
            for (int k = -r; k <= r; k++) {
                int p = in[base + Math.min(len - 1, Math.max(0, k)) * stride];
                sa += p >>> 24; sr += (p >> 16) & 255; sg += (p >> 8) & 255; sb += p & 255;
            }
            for (int i = 0; i < len; i++) {
                out[base + i * stride] = ((sa / div) << 24) | ((sr / div) << 16) | ((sg / div) << 8) | (sb / div);
                int pOut = in[base + Math.max(0, i - r) * stride], pIn = in[base + Math.min(len - 1, i + r + 1) * stride];
                sa += (pIn >>> 24) - (pOut >>> 24);
                sr += ((pIn >> 16) & 255) - ((pOut >> 16) & 255);
                sg += ((pIn >> 8) & 255) - ((pOut >> 8) & 255);
                sb += (pIn & 255) - (pOut & 255);
            }
        }
    }

    // ---------------- fondo para la barra y los botones de navegación ----------------

    /** Vidrio intenso para vistas que flotan sobre la mesa: muestra la parte desenfocada que queda detrás. */
    static class Bg extends Drawable {
        private final float radius, dp;
        private final int[] me = new int[2], other = new int[2];

        Bg(float radiusPx, float dp) {
            this.radius = radiusPx;
            this.dp = dp;
            users.add(new WeakReference<>(this));
        }

        @Override public void draw(Canvas c) {
            RectF r = new RectF(getBounds());
            View v = getCallback() instanceof View ? (View) getCallback() : null;
            View d = desk.get();
            Runnable back = null;
            if (v != null && d != null && blur != null) {
                v.getLocationInWindow(me);
                d.getLocationInWindow(other);
                final float ox = other[0] - me[0], oy = other[1] - me[1], dw = d.getWidth(), dh = d.getHeight();
                back = () -> { c.save(); c.translate(ox, oy); drawBlur(c, dw, dh); c.restore(); };
            }
            body(c, r, radius, dp, back);
        }

        @Override public void getOutline(Outline o) { o.setRoundRect(getBounds(), radius); o.setAlpha(0.9f); }
        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(ColorFilter cf) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
