package cl.mesadoble;

import android.content.Context;
import android.graphics.Color;

/**
 * Color de las tarjetas (apps, enlaces, widgets, reloj, batería) y de los menús (barra y ventanas).
 * Automático, cristal (claro u oscuro, translúcido) o un color sólido. Es igual en los dos teléfonos.
 */
final class CardTheme {

    static final int NONE = 0, GLASS_LIGHT = 1, GLASS_DARK = 2, GLASS_AERO = 3, GLASS_INTENSE = 4;   // Aero: 'color' es el tono del vidrio
    static int color = 0;      // 0 = automático (según el tema)
    static int glass = NONE;
    static int desk = 0;       // color de fondo de la mesa (para que el texto sobre cristal se lea)
    static int console = 0;    // tema de consola (0 = ninguno). Se desbloquea tocando 3 veces el primer color de fondo

    // ---- temas de consola: colores inspirados en sus menús (sin logos ni marcas) ----
    //                 nombre       mesa        tarjeta     menú        barra       botón       borde       texto       texto 2     menú oscuro
    static final String[] CONSOLE_NAMES = {"", "DS", "3DS", "Switch", "Wii", "PS3", "PSP", "NES", "Super NES"};
    static final String[] CONSOLE_DESC = {"",
            "Plateado y blanco, con celeste", "Blanco con gris suave y rojo", "Gris oscuro con azul y rojo neón",
            "Blanco con bordes celestes", "Negro azulado con brillo plateado", "Azul profundo y violeta",
            "Gris claro, gris oscuro y rojo", "Gris lila con morado"};
    private static final int[][] CONSOLES = {
            {},
            {0xFFE4E7EB, 0xFFFFFFFF, 0xFFF7F8FA, 0xF2EEF1F4, 0xFFF0F3F6, 0xFF9FB4C8, 0xFF2B3440, 0xFF5E6B78, 0},   // DS
            {0xFFEDEDED, 0xFFFFFFFF, 0xFFFAFAFA, 0xF2F2F2F2, 0xFFF4F4F4, 0xFFD5302F, 0xFF333333, 0xFF6E6E6E, 0},   // 3DS
            {0xFF2D2D2D, 0xFF3C3C3C, 0xFF333333, 0xF2282828, 0xFF474747, 0xFF00C3E3, 0xFFFFFFFF, 0xFFC4C4C4, 1},   // Switch
            {0xFFF2F5F8, 0xFFFFFFFF, 0xFFFFFFFF, 0xF2FFFFFF, 0xFFF3FAFE, 0xFF34BEED, 0xFF555A60, 0xFF8A9198, 0},   // Wii
            {0xFF07152E, 0x59000000, 0xD9091222, 0xB3000000, 0x26FFFFFF, 0x66C9D6E8, 0xFFFFFFFF, 0xFFB8C6D9, 1},   // PS3
            {0xFF1B2A5C, 0x4D0A1030, 0xD9161E44, 0xB30A1030, 0x26FFFFFF, 0x80A48CFF, 0xFFFFFFFF, 0xFFC7C2EE, 1},   // PSP
            {0xFF8C8C8C, 0xFFC9C9C9, 0xFFD6D6D6, 0xF2BDBDBD, 0xFF3A3A3A, 0xFFC4262E, 0xFF111111, 0xFF4A4A4A, 0},   // NES
            {0xFFCFCFDB, 0xFFE6E6EE, 0xFFEFEFF5, 0xF2DADAE6, 0xFFBDB9E6, 0xFF5B4FBF, 0xFF2B2550, 0xFF5F5A85, 0}};  // Super NES

    static int consoleDesk(int k) { return k > 0 && k < CONSOLES.length ? CONSOLES[k][0] : 0; }
    /** Tres colores para la muestra del tema: mesa, tarjeta y borde. */
    static int[] consolePreview(int k) { return new int[]{CONSOLES[k][0], CONSOLES[k][1] | 0xFF000000, CONSOLES[k][5] | 0xFF000000}; }
    private static int[] k() { return console > 0 && console < CONSOLES.length ? CONSOLES[console] : null; }

    private CardTheme() { }

    static void load(Context c) {
        android.content.SharedPreferences p = c.getSharedPreferences("mesa", Context.MODE_PRIVATE);
        color = p.getInt("cardColor", 0);
        glass = p.getInt("cardGlass", NONE);
        desk = p.getInt("deskColor", 0);
        console = p.getInt("cardConsole", 0);
    }

    static void save(Context c, int col, int gl) { save(c, col, gl, 0); }

    static void save(Context c, int col, int gl, int con) {
        color = col; glass = gl; console = con;
        c.getSharedPreferences("mesa", Context.MODE_PRIVATE).edit().putInt("cardColor", col).putInt("cardGlass", gl).putInt("cardConsole", con).apply();
    }

    static boolean isGlass() { return glass != NONE && console == 0; }
    static boolean isConsole() { return console != 0; }
    static boolean isAero() { return glass == GLASS_AERO && console == 0; }
    static boolean isIntense() { return glass == GLASS_INTENSE && console == 0; }
    static int aeroTint() { return color != 0 ? color : Aero.TINTS[0]; }

    static boolean dark(int c) { return (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) < 140; }

    private static int deskColor(boolean night) { return desk == 0 ? (night ? 0xFF18212B : 0xFFDDE3E8) : desk; }

    /** ¿Se lee mejor texto claro sobre las tarjetas? */
    static boolean lightText(boolean night) {
        if (k() != null) return k()[8] == 1;
        if (glass == GLASS_DARK) return true;
        if (glass == GLASS_AERO) return false;   // texto oscuro con halo blanco, como en Windows 7
        if (glass == GLASS_LIGHT || glass == GLASS_INTENSE) return dark(deskColor(night));
        if (color != 0) return dark(color);
        return night;
    }

    static int fill(boolean night) {
        if (k() != null) return k()[1];
        if (glass == GLASS_LIGHT) return 0x3DFFFFFF;
        if (glass == GLASS_INTENSE) return 0x40FFFFFF;
        if (glass == GLASS_DARK) return 0x66000000;
        if (glass == GLASS_AERO) return (aeroTint() & 0x00FFFFFF) | 0x70000000;
        if (color != 0) return color;
        return night ? 0xFF223040 : 0xFFFFFFFF;
    }

    /** Borde fino (solo cristal: es lo que lo hace verse como vidrio). */
    static int stroke() {
        if (k() != null) return k()[5];
        if (glass == GLASS_LIGHT) return 0xA6FFFFFF;
        if (glass == GLASS_DARK) return 0x4DFFFFFF;
        if (glass == GLASS_AERO) return 0xB3FFFFFF;
        if (glass == GLASS_INTENSE) return 0xCCFFFFFF;
        return 0;
    }

    static int fg(boolean night) { if (k() != null) return k()[6]; return isAero() ? 0xFF0B1A2A : lightText(night) ? 0xFFF2F5F8 : 0xFF1D2733; }
    static int muted(boolean night) { if (k() != null) return k()[7]; return isAero() ? 0xFF2E4256 : lightText(night) ? 0xFFB7C4D0 : 0xFF4B5966; }

    /** Fondo de la barra de opciones y de los botones de navegación. */
    static int barFill(boolean night) {
        if (k() != null) return k()[3];
        if (glass == GLASS_LIGHT || glass == GLASS_INTENSE) return 0x40FFFFFF;
        if (glass == GLASS_DARK) return 0x80000000;
        if (glass == GLASS_AERO) return (aeroTint() & 0x00FFFFFF) | 0x70000000;
        if (color != 0) return (color & 0x00FFFFFF) | 0xEE000000;
        return night ? 0xE6182129 : 0xEEFFFFFF;
    }

    /** Fondo de cada botón cuadrado de la barra. */
    static int buttonFill(boolean night) {
        if (k() != null) return k()[4];
        if (glass == GLASS_LIGHT) return 0x26FFFFFF;
        if (glass == GLASS_INTENSE) return 0x24FFFFFF;
        if (glass == GLASS_DARK || glass == GLASS_AERO) return 0x1FFFFFFF;
        if (color != 0) return shade(color, dark(color) ? 0.12f : -0.08f);
        return night ? 0xFF223040 : 0xFFFFFFFF;
    }

    static int buttonPressed(boolean night) {
        if (k() != null) return shade(k()[4] | 0xFF000000, k()[8] == 1 ? 0.25f : -0.15f);
        if (isGlass()) return 0x4DFFFFFF;
        if (color != 0) return shade(color, dark(color) ? 0.25f : -0.18f);
        return night ? 0xFF2F4258 : 0xFFE2E8ED;
    }

    /** Ventanas de menú (las hojas de opciones). */
    static boolean sheetNight(boolean night) {
        if (k() != null) return k()[8] == 1;
        if (glass == GLASS_LIGHT || glass == GLASS_AERO) return false;
        if (glass == GLASS_DARK) return true;
        if (glass == GLASS_INTENSE) return dark(deskColor(night));
        if (color != 0) return dark(color);
        return night;
    }

    static int sheetFill(boolean night) {
        if (k() != null) return k()[2];
        if (glass == GLASS_LIGHT) return 0xB8F4F7FA;
        if (glass == GLASS_DARK) return 0xB8141C24;
        if (glass == GLASS_INTENSE) return dark(deskColor(night)) ? 0xA6141C26 : 0xB3F2F5F8;   // esmerilado: deja ver lo de atrás
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
