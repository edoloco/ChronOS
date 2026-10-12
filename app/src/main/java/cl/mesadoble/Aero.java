package cl.mesadoble;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * Vidrio al estilo Aero de Windows 7: color translúcido, brillo marcado en la mitad superior,
 * vetas diagonales de luz, borde interior blanco y borde exterior oscuro.
 */
final class Aero {

    /** Colores de la ventana de Windows 7 (Cielo, Crepúsculo, Mar, Hoja, Lima, Sol, Calabaza, Rubí, Fucsia, Rubor, Violeta, Escarcha). */
    static final int[] TINTS = {0xFF5DA0E8, 0xFF2D6AB8, 0xFF3AAEA8, 0xFF5BAE4A, 0xFFA8CE3A, 0xFFF2C230,
            0xFFF08A2A, 0xFFD8323C, 0xFFD04AA0, 0xFFF29AB4, 0xFF8A5AD0, 0xFFB8C8D8};
    static final String[] TINT_NAMES = {"Cielo", "Crepúsculo", "Mar", "Hoja", "Lima", "Sol",
            "Calabaza", "Rubí", "Fucsia", "Rubor", "Violeta", "Escarcha"};

    private static final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), gloss = new Paint(Paint.ANTI_ALIAS_FLAG),
            streak = new Paint(Paint.ANTI_ALIAS_FLAG), inner = new Paint(Paint.ANTI_ALIAS_FLAG), outer = new Paint(Paint.ANTI_ALIAS_FLAG),
            glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    static {
        inner.setStyle(Paint.Style.STROKE);
        outer.setStyle(Paint.Style.STROKE);
        streak.setColor(0x1CFFFFFF);
    }

    private Aero() { }

    /** Texto oscuro con halo blanco, como los títulos de ventana de Windows 7. */
    static void glowText(Paint p, float dp) { p.setShadowLayer(5 * dp, 0, 0, 0xCCFFFFFF); }

    static void draw(Canvas c, RectF r, float radius, int tint, float dp) {
        int a = 0x70;   // translucidez del color
        fill.setShader(new LinearGradient(0, r.top, 0, r.bottom,
                (tint & 0x00FFFFFF) | (a << 24), (CardTheme.shade(tint, -0.25f) & 0x00FFFFFF) | ((a + 0x18) << 24), Shader.TileMode.CLAMP));
        c.drawRoundRect(r, radius, radius, fill);

        c.save();
        Path clip = new Path();
        clip.addRoundRect(r, radius, radius, Path.Direction.CW);
        c.clipPath(clip);
        // vetas diagonales de luz
        float w = r.width(), h = r.height(), step = Math.max(28 * dp, w / 5f);
        for (float x = r.left - h; x < r.right + h; x += step) {
            Path p = new Path();
            float bw = step * 0.28f;
            p.moveTo(x, r.bottom); p.lineTo(x + h * 0.9f, r.top); p.lineTo(x + h * 0.9f + bw, r.top); p.lineTo(x + bw, r.bottom); p.close();
            c.drawPath(p, streak);
        }
        // brillo: fuerte arriba y corte nítido cerca de la mitad
        float mid = r.top + h * 0.48f;
        gloss.setShader(new LinearGradient(0, r.top, 0, mid, new int[]{0x8CFFFFFF, 0x40FFFFFF, 0x26FFFFFF}, new float[]{0, 0.55f, 1}, Shader.TileMode.CLAMP));
        c.drawRect(r.left, r.top, r.right, mid, gloss);
        // resplandor suave abajo
        glow.setShader(new LinearGradient(0, r.bottom - h * 0.3f, 0, r.bottom, 0x00FFFFFF, 0x26FFFFFF, Shader.TileMode.CLAMP));
        c.drawRect(r.left, r.bottom - h * 0.3f, r.right, r.bottom, glow);
        c.restore();

        // bordes: exterior oscuro y luz interior
        outer.setStrokeWidth(1.2f * dp);
        outer.setColor(0x99102030);
        c.drawRoundRect(r, radius, radius, outer);
        inner.setStrokeWidth(1f * dp);
        inner.setColor(0xB3FFFFFF);
        RectF in = new RectF(r.left + 1.4f * dp, r.top + 1.4f * dp, r.right - 1.4f * dp, r.bottom - 1.4f * dp);
        c.drawRoundRect(in, Math.max(0, radius - 1.4f * dp), Math.max(0, radius - 1.4f * dp), inner);
    }

    /** Para usar de fondo en vistas (barra, menús). */
    static class Bg extends Drawable {
        private final float radius, dp;
        private final int tint;
        Bg(int tint, float radiusPx, float dp) { this.tint = tint; this.radius = radiusPx; this.dp = dp; }
        @Override public void draw(Canvas c) { draw2(c); }
        private void draw2(Canvas c) { Aero.draw(c, new RectF(getBounds()), radius, tint, dp); }
        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(ColorFilter cf) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    static boolean isLight(int c) { return (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) > 150; }
}
