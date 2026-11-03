package cl.mesadoble;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Fondo de pantalla de ChronOS (Ajustes → Apariencia → Fondo de pantalla). Una sola imagen se reparte entre las dos
 * pantallas (son UNA): la mitad de arriba (o izquierda) va al principal y la otra al extendido. Cada teléfono guarda
 * su mitad en wall.jpg. El teclado del extendido puede usarla de fondo ("kbWall").
 */
final class Wall {

    private Wall() { }

    private static Bitmap cached;
    private static long cachedAt = -1;

    static File file(Context c) { return new File(c.getFilesDir(), "wall.jpg"); }

    /** La mitad de este teléfono (o null si no hay fondo). */
    static synchronized Bitmap get(Context c) {
        File f = file(c);
        if (!f.exists()) { cached = null; cachedAt = -1; return null; }
        if (cached != null && cachedAt == f.lastModified()) return cached;
        cached = BitmapFactory.decodeFile(f.getPath());
        cachedAt = f.lastModified();
        return cached;
    }

    static synchronized void save(Context c, Bitmap b) {
        try (FileOutputStream o = new FileOutputStream(file(c))) { b.compress(Bitmap.CompressFormat.JPEG, 88, o); } catch (Exception ignored) { }
        cached = null;
    }

    static synchronized void clear(Context c) {
        //noinspection ResultOfMethodCallIgnored
        file(c).delete();
        cached = null;
    }

    static boolean onKeyboard(Context c) { return c.getSharedPreferences("mesa", Context.MODE_PRIVATE).getBoolean("kbWall", true); }

    /**
     * Recorta la imagen al tamaño de las dos pantallas juntas y la parte en dos.
     * stacked: una sobre la otra (horizontal); si no, una al lado de la otra. [0] = principal, [1] = extendido.
     */
    static Bitmap[] split(Bitmap src, int screenW, int screenH, boolean stacked) {
        int tw = stacked ? screenW : screenW * 2, th = stacked ? screenH * 2 : screenH;
        float k = Math.min(1f, 2400f / Math.max(tw, th));   // tope de tamaño
        tw = Math.max(2, Math.round(tw * k)); th = Math.max(2, Math.round(th * k));
        float sc = Math.max(tw / (float) src.getWidth(), th / (float) src.getHeight());
        float sw = tw / sc, sh = th / sc;
        Rect from = new Rect(Math.round((src.getWidth() - sw) / 2), Math.round((src.getHeight() - sh) / 2),
                Math.round((src.getWidth() + sw) / 2), Math.round((src.getHeight() + sh) / 2));
        Bitmap whole = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888);
        new Canvas(whole).drawBitmap(src, from, new Rect(0, 0, tw, th), new Paint(Paint.FILTER_BITMAP_FLAG));
        Bitmap a = stacked ? Bitmap.createBitmap(whole, 0, 0, tw, th / 2) : Bitmap.createBitmap(whole, 0, 0, tw / 2, th);
        Bitmap b = stacked ? Bitmap.createBitmap(whole, 0, th / 2, tw, th - th / 2) : Bitmap.createBitmap(whole, tw / 2, 0, tw - tw / 2, th);
        return new Bitmap[]{a, b};
    }

    /** Dibuja la imagen cubriendo todo (recortando lo que sobra), con un velo encima para que se lea lo de adelante. */
    static final class CoverDrawable extends Drawable {
        private final Bitmap b;
        private final int veil;
        private final Paint p = new Paint(Paint.FILTER_BITMAP_FLAG), v = new Paint();
        CoverDrawable(Bitmap b, int veil) { this.b = b; this.veil = veil; v.setColor(veil); }
        @Override public void draw(Canvas c) {
            Rect r = getBounds();
            float sc = Math.max(r.width() / (float) b.getWidth(), r.height() / (float) b.getHeight());
            float w = b.getWidth() * sc, h = b.getHeight() * sc;
            c.drawBitmap(b, null, new RectF(r.centerX() - w / 2, r.centerY() - h / 2, r.centerX() + w / 2, r.centerY() + h / 2), p);
            if (veil != 0) c.drawRect(r, v);
        }
        @Override public void setAlpha(int a) { p.setAlpha(a); }
        @Override public void setColorFilter(ColorFilter cf) { p.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.OPAQUE; }
    }
}
