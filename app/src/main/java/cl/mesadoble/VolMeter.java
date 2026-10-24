package cl.mesadoble;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/**
 * Nivel de volumen como la barra de energía de Mega Man: una columna de rayitas que se llenan de abajo hacia
 * arriba (o una fila, si es más ancha que alta). Va entre los botones de bajar y subir volumen.
 */
public class VolMeter extends View {

    private float level = -1;   // 0..1; -1 = aún no se sabe
    private final int segs;
    private int on, off;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

    public VolMeter(Context c, int segments, int onColor, int offColor) {
        super(c);
        segs = segments;
        on = onColor;
        off = offColor;
    }

    public void setLevel(float f) {
        f = Math.max(0, Math.min(1, f));
        if (f != level) { level = f; invalidate(); }
    }

    public void setColors(int onColor, int offColor) { on = onColor; off = offColor; invalidate(); }

    @Override protected void onDraw(Canvas c) {
        float W = getWidth(), H = getHeight(), dp = getResources().getDisplayMetrics().density;
        boolean vertical = H >= W;
        int lit = level < 0 ? 0 : Math.round(level * segs);
        float gap = 1.5f * dp, len = vertical ? H : W, seg = (len - gap * (segs - 1)) / segs;
        // marco oscuro, como el de Mega Man
        p.setColor(0x66000000);
        c.drawRoundRect(-dp, -dp, W + dp, H + dp, 2 * dp, 2 * dp, p);
        for (int i = 0; i < segs; i++) {
            boolean lightOn = i < lit;
            // los últimos tramos (volumen alto) se ponen naranjos
            p.setColor(lightOn ? (i >= segs * 0.8f ? 0xFFFF6D00 : on) : off);
            float a = i * (seg + gap);
            if (vertical) c.drawRect(dp, H - a - seg, W - dp, H - a, p);
            else c.drawRect(a, dp, a + seg, H - dp, p);
        }
    }
}
