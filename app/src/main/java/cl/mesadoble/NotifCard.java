package cl.mesadoble;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.Base64;
import android.view.View;
import android.view.ViewOutlineProvider;

import org.json.JSONObject;

import java.util.ArrayDeque;

/**
 * Tarjeta de notificación: sale de izquierda a derecha (como desenrollándose desde el reloj), se queda unos
 * segundos y vuelve. Si llegan varias, se muestran una tras otra. Al tocarla se abre la app (en el principal).
 */
public class NotifCard extends View {

    public interface Tap { void onNotifTap(String pkg); }

    private final float dp;
    private final ArrayDeque<JSONObject> queue = new ArrayDeque<>();
    private JSONObject cur;
    private Bitmap icon;
    private float reveal;            // 0..1: cuánto se ve (de izquierda a derecha)
    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG), bmp = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final TextPaint title = new TextPaint(Paint.ANTI_ALIAS_FLAG), body = new TextPaint(Paint.ANTI_ALIAS_FLAG), app = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private boolean night, floating;
    private Tap tap;
    private android.animation.ValueAnimator anim;

    public NotifCard(Context c, boolean night, boolean floating) {
        super(c);
        this.night = night;
        this.floating = floating;   // tarjeta suelta (en el extendido) en vez de dentro de la barra
        dp = c.getResources().getDisplayMetrics().density;
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        app.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        app.setLetterSpacing(0.06f);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(dp);
        setVisibility(INVISIBLE);
        if (floating) {
            setElevation(10 * dp);
            setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View v, Outline o) {
                    o.setRoundRect(0, 0, Math.max(1, Math.round(v.getWidth() * reveal)), v.getHeight(), 18 * dp);
                }
            });
        }
        setOnClickListener(v -> { if (cur != null && tap != null) tap.onNotifTap(cur.optString("pkg")); hideNow(); });
    }

    public void setTap(Tap t) { tap = t; }

    public void show(JSONObject n) {
        queue.add(n);
        if (cur == null) next();
    }

    private void next() {
        cur = queue.poll();
        if (cur == null) { setVisibility(INVISIBLE); return; }
        icon = null;
        try {
            byte[] b = Base64.decode(cur.optString("icon"), Base64.DEFAULT);
            icon = BitmapFactory.decodeByteArray(b, 0, b.length);
        } catch (Exception ignored) { }
        setVisibility(VISIBLE);
        animateTo(1f, 420, () -> postDelayed(hide, 5000));
    }

    private final Runnable hide = () -> animateTo(0f, 320, this::next);

    private void hideNow() { removeCallbacks(hide); animateTo(0f, 200, this::next); }

    private void animateTo(float to, long ms, Runnable end) {
        if (anim != null) anim.cancel();
        anim = android.animation.ValueAnimator.ofFloat(reveal, to);
        anim.setDuration(ms);
        anim.setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f));
        anim.addUpdateListener(a -> { reveal = (float) a.getAnimatedValue(); invalidate(); invalidateOutline(); });
        anim.addListener(new android.animation.AnimatorListenerAdapter() {
            boolean cancelled;
            @Override public void onAnimationCancel(android.animation.Animator a) { cancelled = true; }
            @Override public void onAnimationEnd(android.animation.Animator a) { if (!cancelled && end != null) end.run(); }
        });
        anim.start();
    }

    @Override protected void onDraw(Canvas c) {
        if (cur == null || reveal <= 0) return;
        float W = getWidth(), H = getHeight(), r = (floating ? 18 : H / 2.6f) * dp / dp;
        c.save();
        c.clipRect(0, 0, W * reveal, H);
        boolean lt = floating ? night : ChronoBar.ink(night) == 0xFFF5F8FA;
        bg.setColor(floating ? (night ? 0xF21E2934 : 0xF7FFFFFF) : (lt ? 0x33FFFFFF : 0xE6FFFFFF));
        RectF box = new RectF(0, 0, W, H);
        c.drawRoundRect(box, r, r, bg);
        edge.setColor(lt ? 0x40FFFFFF : 0x22000000);
        c.drawRoundRect(new RectF(dp / 2, dp / 2, W - dp / 2, H - dp / 2), r, r, edge);
        float pad = 10 * dp, is = Math.min(H - 2 * pad, 40 * dp);
        if (icon != null) c.drawBitmap(icon, null, new RectF(pad, (H - is) / 2, pad + is, (H + is) / 2), bmp);
        float tx = pad + is + 10 * dp, tw = W - tx - pad;
        int ink = lt ? 0xFFF5F8FA : 0xFF1D2733;
        String a = cur.optString("app"), t = cur.optString("title"), b = cur.optString("text");
        boolean two = H > 46 * dp;
        app.setColor((ink & 0x00FFFFFF) | 0x99000000);
        title.setColor(ink);
        body.setColor((ink & 0x00FFFFFF) | 0xCC000000);
        if (two) {
            app.setTextSize(10.5f * dp);
            title.setTextSize(14 * dp);
            body.setTextSize(13 * dp);
            float y0 = H / 2 - 13 * dp;
            c.drawText(TextUtils.ellipsize(a.toUpperCase(), app, tw, TextUtils.TruncateAt.END).toString(), tx, y0, app);
            c.drawText(TextUtils.ellipsize(t, title, tw, TextUtils.TruncateAt.END).toString(), tx, y0 + 17 * dp, title);
            c.drawText(TextUtils.ellipsize(b, body, tw, TextUtils.TruncateAt.END).toString(), tx, y0 + 34 * dp, body);
        } else {
            title.setTextSize(13.5f * dp);
            String line = t.isEmpty() ? b : b.isEmpty() ? t : t + ": " + b;
            c.drawText(TextUtils.ellipsize(line, title, tw, TextUtils.TruncateAt.END).toString(), tx, H / 2 + 5 * dp, title);
        }
        c.restore();
    }

    public void setNight(boolean n) { night = n; invalidate(); }
}
