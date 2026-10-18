package cl.mesadoble;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

import java.util.ArrayList;
import java.util.List;

/**
 * PALS: "compañeritos" para las apps que no se ven bien en ChronOS (hechas para un teléfono en vertical).
 * Cada PAL abre la versión web del servicio y la acomoda a ChronOS (horizontal, cristal naranjo). En la lista de
 * apps y en la mesa se ven como una app más, con paquete "pal:<id>" y un ícono propio: una esfera de cristal
 * naranjo (como una naranja, con su hojita) con un personajito pixelado adentro.
 */
final class Pals {

    private Pals() { }

    /** Una opción del panel lateral: símbolo, nombre y la dirección que abre. */
    static final class Item {
        final String glyph, label, url;
        Item(String glyph, String label, String url) { this.glyph = glyph; this.label = label; this.url = url; }
    }

    /** La receta de un PAL. */
    static final class Pal {
        final String id, name, home;
        String login;
        final int accent;
        final String[] hosts;   // dominios que se abren dentro del PAL (el resto, en el navegador)
        final List<Item> menu = new ArrayList<>();
        Pal(String id, String name, String home, int accent, String... hosts) {
            this.id = id; this.name = name; this.home = home; this.accent = accent; this.hosts = hosts;
            this.login = home;
        }
        Pal item(String g, String l, String u) { menu.add(new Item(g, l, u)); return this; }
        Pal login(String u) { login = u; return this; }
    }

    static final List<Pal> ALL = new ArrayList<>();
    static {
        ALL.add(new Pal("claude", "Claude PAL", "https://claude.ai/new", 0xFFD97757,
                "claude.ai", "claude.com", "anthropic.com")
                .login("https://claude.ai/login")
                .item("✚", "Nuevo chat", "https://claude.ai/new")
                .item("☰", "Chats", "https://claude.ai/recents")
                .item("▣", "Proyectos", "https://claude.ai/projects")
                .item("◆", "Artefactos", "https://claude.ai/artifacts")
                .item("⚙", "Ajustes", "https://claude.ai/settings"));
    }

    static Pal get(String id) {
        for (Pal p : ALL) if (p.id.equals(id)) return p;
        return null;
    }

    static boolean isPal(String pkg) { return pkg != null && pkg.startsWith("pal:"); }

    static Intent intent(Context c, String id) {
        return new Intent(c, PalActivity.class).putExtra("pal", id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }

    /** Para la lista de apps: los PALS van primero. */
    static List<AppsDialog.App> asApps(Context c) {
        List<AppsDialog.App> l = new ArrayList<>();
        for (Pal p : ALL) l.add(new AppsDialog.App("pal:" + p.id, p.name, new BitmapDrawable(c.getResources(), icon(p, 192))));
        return l;
    }

    // ---------------- ícono ----------------

    /** Personajito (10 × 8): X cuerpo, O ojos, M boca, . vacío. Diseño propio de ChronOS. */
    private static final String[] SPRITE = {
            "..XXXXXX..",
            ".XXXXXXXX.",
            "XXOOXXOOXX",
            "XXOOXXOOXX",
            "XXXXXXXXXX",
            "XXXMMMMXXX",
            ".XXXXXXXX.",
            ".X.X..X.X."};

    static Bitmap icon(Pal p, int size) {
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        float s = size, r = s * 0.44f, cx = s / 2, cy = s * 0.54f;
        Paint pt = new Paint(Paint.ANTI_ALIAS_FLAG);
        // sombra suave
        pt.setColor(0x55000000);
        pt.setMaskFilter(new android.graphics.BlurMaskFilter(s * 0.04f, android.graphics.BlurMaskFilter.Blur.NORMAL));
        c.drawCircle(cx, cy + s * 0.03f, r, pt);
        pt.setMaskFilter(null);
        // esfera de cristal naranjo
        pt.setShader(new RadialGradient(cx - r * 0.35f, cy - r * 0.4f, r * 1.5f,
                new int[]{0xFFFFC27A, 0xFFF57C1F, 0xFFB8480C}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, r, pt);
        // un toque del color del servicio, abajo
        pt.setShader(new LinearGradient(0, cy, 0, cy + r, 0x00000000, (p.accent & 0x00FFFFFF) | 0x66000000, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, r, pt);
        // brillo de vidrio arriba a la izquierda
        pt.setShader(new LinearGradient(0, cy - r, 0, cy, 0xB3FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawOval(new RectF(cx - r * 0.7f, cy - r * 0.92f, cx + r * 0.45f, cy - r * 0.1f), pt);
        pt.setShader(null);
        // borde
        pt.setStyle(Paint.Style.STROKE);
        pt.setStrokeWidth(s * 0.015f);
        pt.setColor(0x99FFFFFF);
        c.drawCircle(cx, cy, r - s * 0.008f, pt);
        pt.setStyle(Paint.Style.FILL);
        // hojita verde (como una naranja)
        pt.setColor(0xFF2FA866);
        c.save();
        c.rotate(-30, cx + r * 0.15f, cy - r);
        c.drawOval(new RectF(cx + r * 0.05f, cy - r * 1.18f, cx + r * 0.55f, cy - r * 0.9f), pt);
        c.restore();
        // personajito pixelado
        float px = r * 0.12f, w = SPRITE[0].length() * px, h = SPRITE.length * px;
        float x0 = cx - w / 2, y0 = cy - h / 2 + r * 0.08f;
        for (int y = 0; y < SPRITE.length; y++) for (int x = 0; x < SPRITE[y].length(); x++) {
            char ch = SPRITE[y].charAt(x);
            if (ch == '.') continue;
            pt.setColor(ch == 'X' ? 0xFFFFF4E6 : ch == 'O' ? 0xFF2A1A12 : p.accent);
            c.drawRect(x0 + x * px, y0 + y * px, x0 + (x + 1) * px + 0.5f, y0 + (y + 1) * px + 0.5f, pt);
        }
        return b;
    }

    static Drawable drawable(Context c, Pal p) { return new BitmapDrawable(c.getResources(), icon(p, 192)); }
}
