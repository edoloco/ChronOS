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
 * Burbuja de notificación. Sale de la action bar, se detiene un par de segundos para destacar la notificación
 * recién llegada y vuelve hacia la barra desvaneciéndose:
 *   - principal (barra arriba): baja desde arriba y se va hacia arriba;
 *   - extendido (barra abajo, cuando el principal está en otra app): sube desde abajo y se va hacia abajo.
 * Si llegan varias, salen una tras otra. Al tocarla se abre lo que está notificando (en el principal).
 */
public class NotifCard extends View {

    public interface Tap { void onNotifTap(String pkg, String key); }

    private static final long HOLD = 4000;   // ms que queda a la vista antes de desvanecerse

    private final float dp;
    private final ArrayDeque<JSONObject> queue = new ArrayDeque<>();
    private JSONObject cur;
    private Bitmap icon;
    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG), bmp = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final TextPaint title = new TextPaint(Paint.ANTI_ALIAS_FLAG), body = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private boolean night, fromTop = true;
    private Tap tap;

    public NotifCard(Context c, boolean night, boolean unused) {
        super(c);
        this.night = night;
        dp = c.getResources().getDisplayMetrics().density;
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(1.2f * dp);
        setVisibility(INVISIBLE);
        setElevation(10 * dp);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), 20 * dp); }
        });
        setOnClickListener(v -> {
            if (cur != null && tap != null) tap.onNotifTap(cur.optString("pkg"), cur.optString("key"));
            leave(160);
        });
    }

    public void setTap(Tap t) { tap = t; }
    /** true: la barra está arriba (la burbuja baja); false: abajo (sube). */
    public void setFromTop(boolean t) { fromTop = t; }

    public void show(JSONObject n) {
        queue.add(n);
        if (cur == null) next();
    }

    private float away() { return (fromTop ? -1 : 1) * (getHeight() > 0 ? getHeight() + 12 * dp : 80 * dp); }

    private void next() {
        removeCallbacks(hold);
        cur = queue.poll();
        if (cur == null) { setVisibility(INVISIBLE); return; }
        icon = null;
        try {
            byte[] b = Base64.decode(cur.optString("icon"), Base64.DEFAULT);
            icon = BitmapFactory.decodeByteArray(b, 0, b.length);
        } catch (Exception ignored) { }
        invalidate();
        // sale desde la barra: se desliza y aparece, con un pequeño rebote al detenerse
        setVisibility(VISIBLE);
        animate().cancel();
        setTranslationY(away());
        setAlpha(0f);
        setScaleX(0.96f);
        setScaleY(0.96f);
        animate().translationY(0).alpha(1f).scaleX(1f).scaleY(1f).setDuration(460)
                .setInterpolator(new android.view.animation.OvershootInterpolator(1.1f))
                .withEndAction(() -> postDelayed(hold, HOLD)).start();
    }

    private final Runnable hold = () -> leave(520);

    /** Vuelve hacia la barra desvaneciéndose, y sale la siguiente. */
    private void leave(long ms) {
        removeCallbacks(hold);
        animate().cancel();
        animate().translationY(away() * 0.6f).alpha(0f).setDuration(ms)
                .setInterpolator(new android.view.animation.AccelerateInterpolator(1.3f))
                .withEndAction(() -> { cur = null; next(); }).start();
    }

    @Override protected void onDraw(Canvas c) {
        if (cur == null) return;
        float W = getWidth(), H = getHeight(), r = 20 * dp;
        bg.setColor(0xFFFFFFFF);   // siempre blanca, para que no pase desapercibida
        c.drawRoundRect(new RectF(0, 0, W, H), r, r, bg);
        edge.setColor(0x33000000);
        c.drawRoundRect(new RectF(dp, dp, W - dp, H - dp), r, r, edge);
        float pad = 12 * dp, is = Math.min(H - 2 * pad, 38 * dp);
        if (icon != null) c.drawBitmap(icon, null, new RectF(pad + 4 * dp, (H - is) / 2, pad + 4 * dp + is, (H + is) / 2), bmp);
        float tx = pad + 4 * dp + is + 12 * dp, tw = W - tx - pad;
        int ink = 0xFF1D2733;
        String t = cur.optString("title"), b = cur.optString("text");
        title.setColor(ink);
        body.setColor((ink & 0x00FFFFFF) | 0xBB000000);
        if (H > 52 * dp && !t.isEmpty() && !b.isEmpty()) {
            title.setTextSize(14.5f * dp);
            body.setTextSize(13 * dp);
            c.drawText(TextUtils.ellipsize(t, title, tw, TextUtils.TruncateAt.END).toString(), tx, H / 2 - 3 * dp, title);
            c.drawText(TextUtils.ellipsize(b, body, tw, TextUtils.TruncateAt.END).toString(), tx, H / 2 + 15 * dp, body);
        } else {
            title.setTextSize(14.5f * dp);
            String line = t.isEmpty() ? b : b.isEmpty() ? t : t + " " + b;
            c.drawText(TextUtils.ellipsize(line, title, tw, TextUtils.TruncateAt.END).toString(), tx, H / 2 + 5 * dp, title);
        }
    }

    public void setNight(boolean n) { night = n; invalidate(); }
}
