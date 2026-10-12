package cl.mesadoble;

import android.content.Context;
import android.graphics.Color;

/**
 * Color de las tarjetas (apps, enlaces, widgets, reloj, batería) y de los menús (barra y ventanas).
 * Automático, cristal (claro u oscuro, translúcido) o un color sólido. Es igual en los dos teléfonos.
 */
final class CardTheme {

    static final int NONE = 0, GLASS_LIGHT = 1, GLASS_DARK = 2, GLASS_AERO = 3;   // Aero: 'color' es el tono del vidrio
    static int color = 0;      // 0 = automático (según el tema)
    static int glass = NONE;
    static int desk = 0;       // color de fondo de la mesa (para que el texto sobre cristal se lea)

    private CardTheme() { }

    static void load(Context c) {
        android.content.SharedPreferences p = c.getSharedPreferences("mesa", Context.MODE_PRIVATE);
        color = p.getInt("cardColor", 0);
        glass = p.getInt("cardGlass", NONE);
        desk = p.getInt("deskColor", 0);
    }

    static void save(Context c, int col, int gl) {
        color = col; glass = gl;
        c.getSharedPreferences("mesa", Context.MODE_PRIVATE).edit().putInt("cardColor", col).putInt("cardGlass", gl).apply();
    }

    static boolean isGlass() { return glass != NONE; }
    static boolean isAero() { return glass == GLASS_AERO; }
    static int aeroTint() { return color != 0 ? color : Aero.TINTS[0]; }

    static boolean dark(int c) { return (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) < 140; }

    private static int deskColor(boolean night) { return desk == 0 ? (night ? 0xFF18212B : 0xFFDDE3E8) : desk; }

    /** ¿Se lee mejor texto claro sobre las tarjetas? */
    static boolean lightText(boolean night) {
        if (glass == GLASS_DARK) return true;
        if (glass == GLASS_AERO) return false;   // texto oscuro con halo blanco, como en Windows 7
        if (glass == GLASS_LIGHT) return dark(deskColor(night));
        if (color != 0) return dark(color);
        return night;
    }

    static int fill(boolean night) {
        if (glass == GLASS_LIGHT) return 0x3DFFFFFF;
        if (glass == GLASS_DARK) return 0x66000000;
        if (glass == GLASS_AERO) return (aeroTint() & 0x00FFFFFF) | 0x70000000;
        if (color != 0) return color;
        return night ? 0xFF223040 : 0xFFFFFFFF;
    }

    /** Borde fino (solo cristal: es lo que lo hace verse como vidrio). */
    static int stroke() {
        if (glass == GLASS_LIGHT) return 0xA6FFFFFF;
        if (glass == GLASS_DARK) return 0x4DFFFFFF;
        if (glass == GLASS_AERO) return 0xB3FFFFFF;
        return 0;
    }

    static int fg(boolean night) { return isAero() ? 0xFF0B1A2A : lightText(night) ? 0xFFF2F5F8 : 0xFF1D2733; }
    static int muted(boolean night) { return isAero() ? 0xFF2E4256 : lightText(night) ? 0xFFB7C4D0 : 0xFF4B5966; }

    /** Fondo de la barra de opciones y de los botones de navegación. */
    static int barFill(boolean night) {
        if (glass == GLASS_LIGHT) return 0x40FFFFFF;
        if (glass == GLASS_DARK) return 0x80000000;
        if (glass == GLASS_AERO) return (aeroTint() & 0x00FFFFFF) | 0x70000000;
        if (color != 0) return (color & 0x00FFFFFF) | 0xEE000000;
        return night ? 0xE6182129 : 0xEEFFFFFF;
    }

    /** Fondo de cada botón cuadrado de la barra. */
    static int buttonFill(boolean night) {
        if (glass == GLASS_LIGHT) return 0x26FFFFFF;
        if (glass == GLASS_DARK || glass == GLASS_AERO) return 0x1FFFFFFF;
        if (color != 0) return shade(color, dark(color) ? 0.12f : -0.08f);
        return night ? 0xFF223040 : 0xFFFFFFFF;
    }

    static int buttonPressed(boolean night) {
        if (isGlass()) return 0x4DFFFFFF;
        if (color != 0) return shade(color, dark(color) ? 0.25f : -0.18f);
        return night ? 0xFF2F4258 : 0xFFE2E8ED;
    }

    /** Ventanas de menú (las hojas de opciones). */
    static boolean sheetNight(boolean night) {
        if (glass == GLASS_LIGHT || glass == GLASS_AERO) return false;
        if (glass == GLASS_DARK) return true;
        if (color != 0) return dark(color);
        return night;
    }

    static int sheetFill(boolean night) {
        if (glass == GLASS_LIGHT) return 0xB8F4F7FA;
        if (glass == GLASS_DARK) return 0xB8141C24;
        if (glass == GLASS_AERO) return (shade(aeroTint(), 0.6f) & 0x00FFFFFF) | 0xC8000000;
        if (color != 0) return color;
        return night ? 0xFF1E2934 : 0xFFF7F9FB;
    }

    /** Aclara (k > 0) u oscurece (k < 0) un color. */
    static int shade(int c, float k) {
        int r = Color.red(c), g = Color.green(c), b = Color.blue(c);
        if (k > 0) { r += (255 - r) * k; g += (255 - g) * k; b += (255 - b) * k; }
        else { r *= 1 + k; g *= 1 + k; b *= 1 + k; }
        return Color.rgb(r, g, b);
    }
}
