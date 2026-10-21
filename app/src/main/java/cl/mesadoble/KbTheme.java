package cl.mesadoble;

import android.content.Context;
import android.graphics.Color;

/**
 * Colores del teclado, touchpad y gamepad, a tono con el tema elegido: el fondo es el color de la mesa y las
 * teclas usan el color de las tarjetas (en cristal, el vidrio sobre el fondo).
 */
final class KbTheme {

    final int bg, key, keyDark, fg, muted, accent, stroke, surface, chip, warn;
    final boolean dark;   // ¿fondo oscuro?

    private KbTheme(int bg, int key, int stroke) { this(bg, key, stroke, false); }

    /** accentFromKey: el acento se elige según la hoja (key) y no según el fondo. */
    private KbTheme(int bg, int key, int stroke, boolean accentFromKey) {
        this.bg = bg;
        this.key = key;
        this.stroke = stroke;
        dark = CardTheme.dark(bg);
        boolean darkKey = CardTheme.dark(key);
        fg = darkKey ? 0xFFF2F5F8 : 0xFF1D2733;
        keyDark = darkKey ? CardTheme.shade(key, 0.10f) : CardTheme.shade(key, -0.13f);
        muted = blend(fg, key, 0.45f);
        accent = (accentFromKey ? darkKey : dark) ? 0xFF5CC3CF : 0xFF1F7F8C;
        warn = dark ? 0xFFF0A26B : 0xFFB4541A;
        surface = blend(bg, key, 0.5f);
        chip = key;
    }

    /**
     * Fichero abierto (gestor de archivos de la action bar): el cuerpo es la barra misma (lo pinta FilePane igual
     * que ChronoBar: color, cristal o Aero) y lo de adentro va en una hoja gris oscuro.
     */
    static KbTheme folder(Context c, boolean night) {
        int bar = CardTheme.barFill(night) | 0xFF000000;
        return new KbTheme(bar, 0xFF1C1F24, 0x33FFFFFF, true);
    }

    static KbTheme of(Context c, boolean night) {
        int desk = c.getSharedPreferences("mesa", Context.MODE_PRIVATE).getInt("deskColor", 0);
        int bg = desk == 0 ? (night ? 0xFF121920 : 0xFFCDD5DC) : CardTheme.shade(desk, CardTheme.dark(desk) ? -0.15f : -0.06f);
        int key;
        if (CardTheme.isAero()) key = blend(bg, CardTheme.shade(CardTheme.aeroTint(), 0.25f), 0.62f);
        else if (CardTheme.isGlass()) {
            int f = CardTheme.fill(night);
            key = blend(bg, f | 0xFF000000, Color.alpha(f) / 255f + 0.08f);
        } else if (CardTheme.color != 0) key = CardTheme.color;
        else key = night ? 0xFF2A3848 : 0xFFFFFFFF;
        return new KbTheme(bg, key, CardTheme.stroke());
    }

    /** Mezcla a → b (k = cuánto de b). */
    static int blend(int a, int b, float k) {
        k = Math.max(0, Math.min(1, k));
        return Color.rgb(Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * k),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * k),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * k));
    }

    /** Huella para saber si cambió el tema mientras el teclado estaba abierto. */
    static String sig(Context c) {
        return CardTheme.color + "/" + CardTheme.glass + "/" + c.getSharedPreferences("mesa", Context.MODE_PRIVATE).getInt("deskColor", 0);
    }
}
