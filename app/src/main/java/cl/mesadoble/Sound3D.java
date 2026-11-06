package cl.mesadoble;

import android.content.Context;

/**
 * Boost 3D (Ajustes → Configuración → Nivelar volumen → "Sonido 3D"): como los parlantes de la Nintendo DSi, hace que
 * el sonido salga "por fuera" de los teléfonos y que se escuchen detalles (ambiente, ecos, platillos, pasos).
 *  1) ensancha el estéreo: sube lo que es distinto en cada lado (S) frente a lo que es igual (M: voz, bajo);
 *  2) cancela el cruce: a cada lado se le resta un poco del otro, atrasado ~0,2 ms y sin agudos (lo que el oído
 *     izquierdo oye del parlante derecho, y al revés);
 *  3) realce: los agudos se marcan un poco (sin subir el volumen).
 * Se procesa en el PRINCIPAL. Pref "boost3d" 0..100 (0 = Boost normal).
 */
final class Sound3D {

    static volatile int live;   // nivel actual (se cambia en vivo desde la perilla)
    static int level(Context c) { live = c.getSharedPreferences("mesa", Context.MODE_PRIVATE).getInt("boost3d", 0); return live; }
    static void set(Context c, int v) { live = v; c.getSharedPreferences("mesa", Context.MODE_PRIVATE).edit().putInt("boost3d", v).apply(); }

    private final int sr;
    final int level, rate;
    private final float w;
    private final boolean cross;
    private final float[] dl = new float[64], dr = new float[64];
    private int pos;
    private final int delay;
    private float lpL, lpR, hpL, hpR, inL, inR;
    private final float aLp, aHp;

    /** cross = false cuando solo se controla uno de los dos parlantes (apps: el principal suena tal cual). */
    Sound3D(int sampleRate, int level, boolean cross) {
        sr = Math.max(8000, sampleRate);
        this.level = level;
        rate = sampleRate;
        w = Math.max(0, Math.min(100, level)) / 100f;
        this.cross = cross;
        delay = Math.max(1, Math.min(63, Math.round(sr * 0.00022f)));
        aLp = (float) (1 - Math.exp(-2 * Math.PI * 2200 / sr));   // el cruce, sin agudos (la cabeza los tapa)
        aHp = (float) Math.exp(-2 * Math.PI * 3500 / sr);         // realce desde ~3,5 kHz
    }

    boolean on() { return w > 0; }

    private float lastL, lastR;
    float l() { return lastL; }
    float r() { return lastR; }

    /** Procesa un cuadro (muestras de 16 bits); el resultado queda en l() y r(). */
    void process(int L, int R) {
        float m = (L + R) * 0.5f, s = (L - R) * 0.5f;
        s *= 1 + 1.6f * w;
        m *= 1 - 0.18f * w;
        float l1 = m + s, r1 = m - s;
        if (cross) {
            int k = (pos - delay) & 63;
            lpL += aLp * (dl[k] - lpL);
            lpR += aLp * (dr[k] - lpR);
            float c = 0.42f * w;
            l1 -= c * lpR;
            r1 -= c * lpL;
            dl[pos] = l1; dr[pos] = r1;
            pos = (pos + 1) & 63;
        }
        // realce: los agudos pasan por una saturación suave (agrega brillo a los detalles)
        hpL = aHp * (hpL + l1 - inL); inL = l1;
        hpR = aHp * (hpR + r1 - inR); inR = r1;
        float e = 0.35f * w;
        l1 += e * soft(hpL * 2f);
        r1 += e * soft(hpR * 2f);
        lastL = limit(l1);
        lastR = limit(r1);
    }

    private static float soft(float x) { float n = x / 32768f; return 32768f * n / (1 + Math.abs(n)); }

    /** Sin cortes feos: sobre el 70 % se curva suave hasta el máximo. */
    private static float limit(float x) {
        float a = Math.abs(x), t = 0.7f * 32767;
        if (a <= t) return x;
        float over = (a - t) / (32767 - t);
        float y = t + (32767 - t) * over / (1 + over);
        return Math.signum(x) * Math.min(32767, y);
    }

    static short clip(float x) { return (short) Math.max(-32768, Math.min(32767, Math.round(x))); }
}
