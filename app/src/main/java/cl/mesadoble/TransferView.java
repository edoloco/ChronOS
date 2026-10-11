package cl.mesadoble;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;

/**
 * Animación de copia "tipo Windows", repartida entre los dos teléfonos: en el que envía, las hojas salen
 * de la carpeta y se van por el borde que toca al otro teléfono; en el que recibe, entran por ese borde y
 * caen en su carpeta. Así parece que vuelan de una pantalla a la otra.
 */
public class TransferView extends View {

    public interface Listener { void onCancel(); }

    private final float dp;
    private final boolean night;
    private final Paint dim = new Paint(), card = new Paint(Paint.ANTI_ALIAS_FLAG), paper = new Paint(Paint.ANTI_ALIAS_FLAG),
            line = new Paint(Paint.ANTI_ALIAS_FLAG), folderBack = new Paint(Paint.ANTI_ALIAS_FLAG),
            folderFront = new Paint(Paint.ANTI_ALIAS_FLAG), track = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint title = new TextPaint(Paint.ANTI_ALIAS_FLAG), body = new TextPaint(Paint.ANTI_ALIAS_FLAG),
            muted = new TextPaint(Paint.ANTI_ALIAS_FLAG), btn = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final int accent;

    private Listener listener;
    private boolean sending, active, cancelable;
    private float dirX, dirY = 1;
    private String head = "", name = "", detail = "";
    private float progress = -1;
    private long t0;
    private float alpha = 0;
    private ValueAnimator fade;
    private final RectF cancelRect = new RectF();

    public TransferView(Context c, boolean night) {
        super(c);
        this.night = night;
        dp = c.getResources().getDisplayMetrics().density;
        accent = night ? 0xFF5CC3CF : 0xFF1F7F8C;
        dim.setColor(0x99000000);
        card.setColor(night ? 0xF21E2934 : 0xF7FFFFFF);
        card.setShadowLayer(16 * dp, 0, 6 * dp, 0x55000000);
        paper.setColor(0xFFFFFFFF);
        paper.setShadowLayer(4 * dp, 0, 2 * dp, 0x44000000);
        line.setColor(0xFFB8C4CE);
        line.setStrokeWidth(1.6f * dp);
        folderBack.setColor(0xFFD9A62E);
        folderFront.setColor(0xFFF2C14E);
        folderFront.setShadowLayer(3 * dp, 0, -1 * dp, 0x33000000);
        track.setColor(night ? 0xFF33465A : 0xFFDDE3E8);
        fill.setColor(accent);
        int fg = night ? 0xFFE6EDF3 : 0xFF1D2733;
        title.setColor(fg); title.setTextSize(16 * dp); title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        body.setColor(fg); body.setTextSize(14 * dp);
        muted.setColor(night ? 0xFF93A4B5 : 0xFF5B6B7A); muted.setTextSize(12.5f * dp);
        btn.setColor(accent); btn.setTextSize(14 * dp); btn.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        setLayerType(LAYER_TYPE_SOFTWARE, null);   // para las sombras
        setVisibility(GONE);
    }

    public void setListener(Listener l) { listener = l; }
    public boolean isActive() { return active; }

    /** dirX/dirY: hacia dónde está el otro teléfono (por ese borde salen o entran las hojas). */
    public void begin(boolean sending, String head, float dirX, float dirY, boolean cancelable) {
        this.sending = sending; this.head = head; this.dirX = dirX; this.dirY = dirY; this.cancelable = cancelable;
        name = ""; detail = ""; progress = -1;
        if (!active) t0 = System.currentTimeMillis();
        active = true;
        setVisibility(VISIBLE);
        animateAlpha(1);
    }

    public void setDirection(float dx, float dy) { dirX = dx; dirY = dy; invalidate(); }

    public void update(String name, float progress, String detail) {
        this.name = name; this.progress = progress; this.detail = detail;
        invalidate();
    }

    public void end() {
        if (!active) return;
        active = false;
        animateAlpha(0);
    }

    private void animateAlpha(float to) {
        if (fade != null) fade.cancel();
        fade = ValueAnimator.ofFloat(alpha, to);
        fade.setDuration(220);
        fade.addUpdateListener(a -> {
            alpha = (float) a.getAnimatedValue();
            if (alpha <= 0.01f && !active) setVisibility(GONE);
            invalidate();
        });
        fade.start();
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (getVisibility() != VISIBLE) return false;
        if (e.getActionMasked() == MotionEvent.ACTION_UP && cancelable && active && cancelRect.contains(e.getX(), e.getY())) {
            if (listener != null) listener.onCancel();
        }
        return true;   // mientras pasa algo, la lista de abajo no recibe toques
    }

    private static PointF2 bez(float t, float x0, float y0, float cx, float cy, float x1, float y1) {
        float u = 1 - t;
        return new PointF2(u * u * x0 + 2 * u * t * cx + t * t * x1, u * u * y0 + 2 * u * t * cy + t * t * y1);
    }

    private static class PointF2 { final float x, y; PointF2(float x, float y) { this.x = x; this.y = y; } }

    @Override protected void onDraw(Canvas c) {
        int W = getWidth(), H = getHeight();
        int a = Math.round(alpha * 255);
        c.saveLayerAlpha(0, 0, W, H, a);
        c.drawRect(0, 0, W, H, dim);

        boolean vert = dirY != 0;   // el otro teléfono está arriba o abajo
        float cx = W / 2f, cy = H / 2f, ext = (vert ? H : W) / 2f;
        // carpeta, lejos de la unión
        float fx = vert ? W * 0.36f : cx - dirX * ext * 0.35f;
        float fy = vert ? cy - dirY * ext * 0.30f : cy - 46 * dp;
        // punto fuera de la pantalla, del lado del otro teléfono
        float sx = vert ? fx + 30 * dp : cx + dirX * (ext + 50 * dp);
        float sy = vert ? cy + dirY * (ext + 50 * dp) : fy;
        // curva hacia el costado, como en Windows
        float bx = vert ? -110 * dp : 0, by = vert ? 0 : -110 * dp;
        float ccx = (fx + sx) / 2 + bx, ccy = (fy + sy) / 2 + by;

        // tarjeta con el texto
        float cw = Math.min(W * 0.86f, 420 * dp), ch = 132 * dp;
        float tx, ty;
        if (vert) { tx = Math.min(W - cw - 16 * dp, fx + 60 * dp); ty = fy - ch / 2; }
        else { tx = cx - cw / 2; ty = cy + 44 * dp; }
        RectF cr = new RectF(tx, ty, tx + cw, ty + ch);
        c.drawRoundRect(cr, 20 * dp, 20 * dp, card);
        float pad = 18 * dp;
        c.drawText(TextUtils.ellipsize(head, title, cw - 2 * pad, TextUtils.TruncateAt.END).toString(), tx + pad, ty + 30 * dp, title);
        c.drawText(TextUtils.ellipsize(name, body, cw - 2 * pad, TextUtils.TruncateAt.MIDDLE).toString(), tx + pad, ty + 54 * dp, body);
        // barra de progreso
        RectF tr = new RectF(tx + pad, ty + 68 * dp, tx + cw - pad, ty + 76 * dp);
        c.drawRoundRect(tr, 4 * dp, 4 * dp, track);
        long now = System.currentTimeMillis();
        if (progress >= 0) {
            c.drawRoundRect(new RectF(tr.left, tr.top, tr.left + tr.width() * Math.min(1, progress), tr.bottom), 4 * dp, 4 * dp, fill);
        } else {
            float ph = ((now - t0) % 1400) / 1400f, seg = tr.width() * 0.3f;
            float l = tr.left + (tr.width() + seg) * ph - seg;
            c.save(); c.clipRect(tr);
            c.drawRoundRect(new RectF(l, tr.top, l + seg, tr.bottom), 4 * dp, 4 * dp, fill);
            c.restore();
        }
        c.drawText(TextUtils.ellipsize(detail, muted, cw - 2 * pad - 90 * dp, TextUtils.TruncateAt.END).toString(), tx + pad, ty + 98 * dp, muted);
        if (cancelable) {
            float bw = btn.measureText("Cancelar");
            cancelRect.set(tx + cw - pad - bw - 16 * dp, ty + 84 * dp, tx + cw - pad + 8 * dp, ty + 112 * dp);
            c.drawText("Cancelar", tx + cw - pad - bw - 4 * dp, ty + 103 * dp, btn);
        } else cancelRect.setEmpty();

        // carpeta (parte de atrás)
        float fw = 64 * dp, fh = 48 * dp;
        RectF back = new RectF(fx - fw / 2, fy - fh / 2, fx + fw / 2, fy + fh / 2);
        Path tab = new Path();
        tab.moveTo(back.left, back.top + 6 * dp);
        tab.lineTo(back.left, back.top - 6 * dp); tab.lineTo(back.left + fw * 0.38f, back.top - 6 * dp);
        tab.lineTo(back.left + fw * 0.46f, back.top); tab.lineTo(back.right, back.top); tab.lineTo(back.right, back.top + 6 * dp); tab.close();
        c.drawPath(tab, folderBack);
        c.drawRoundRect(back, 5 * dp, 5 * dp, folderBack);

        // hojas volando
        if (active || alpha > 0) {
            int n = 4;
            float period = 1300f;
            for (int i = 0; i < n; i++) {
                float t = (((now - t0) + i * period / n) % period) / period;
                PointF2 p = sending ? bez(t, fx, fy - 6 * dp, ccx, ccy, sx, sy) : bez(t, sx, sy, ccx, ccy, fx, fy - 6 * dp);
                float s = 0.7f + 0.45f * (float) Math.sin(Math.PI * t);
                float rot = (sending ? 1 : -1) * (t * 220 - 30);
                drawPaper(c, p.x, p.y, s, rot);
            }
        }

        // carpeta (frente), encima de las hojas que entran o salen
        Path front = new Path();
        front.moveTo(back.left - 3 * dp, back.top + 12 * dp);
        front.lineTo(back.right + 3 * dp, back.top + 12 * dp);
        front.lineTo(back.right, back.bottom);
        front.lineTo(back.left, back.bottom);
        front.close();
        c.drawPath(front, folderFront);

        c.restore();
        if (active || alpha > 0.01f) postInvalidateOnAnimation();
    }

    private void drawPaper(Canvas c, float x, float y, float s, float rot) {
        float w = 22 * dp * s, h = 28 * dp * s, f = 7 * dp * s;
        c.save();
        c.translate(x, y);
        c.rotate(rot);
        Path p = new Path();
        p.moveTo(-w / 2, -h / 2); p.lineTo(w / 2 - f, -h / 2); p.lineTo(w / 2, -h / 2 + f); p.lineTo(w / 2, h / 2); p.lineTo(-w / 2, h / 2); p.close();
        c.drawPath(p, paper);
        for (int i = 0; i < 4; i++) {
            float ly = -h / 2 + h * (0.32f + i * 0.17f);
            c.drawLine(-w / 2 + 4 * dp * s, ly, w / 2 - 4 * dp * s, ly, line);
        }
        c.restore();
    }
}
