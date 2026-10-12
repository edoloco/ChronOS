package cl.mesadoble;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/**
 * Touchpad que controla el principal por WiFi.
 * Un dedo mueve, un toque hace clic, dos dedos desplazan. Abajo: Clic, Atrás e Inicio.
 */
public class TouchpadView extends View {

    public interface Sink {
        void move(int dx, int dy);
        void click();
        void scroll(int steps);
        void nav(String what);   // back, home, recents
    }

    private Sink sink;
    private String hint = "Touchpad";
    private final float dp;
    private final Paint pad = new Paint(Paint.ANTI_ALIAS_FLAG), btn = new Paint(Paint.ANTI_ALIAS_FLAG),
            text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int bg, surface, btnColor, accent, muted, fg;
    private final RectF area = new RectF();
    private final RectF[] buttons = {new RectF(), new RectF(), new RectF()};
    private final String[] labels = {"Clic", "Atrás", "Inicio"};
    private int pressedBtn = -1;

    private float lastX, lastY, startX, startY, scrollAcc, accX, accY;
    private long downTime;
    private int maxPointers, padPointer = -1;
    private boolean moved;

    public TouchpadView(Context c, boolean night) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        KbTheme th = KbTheme.of(c, night);
        bg = th.bg;
        surface = th.surface;
        btnColor = th.key;
        accent = th.accent;
        muted = th.muted;
        fg = th.fg;
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    }

    public void setSink(Sink s) { sink = s; }
    public void setHint(String h) { hint = h; invalidate(); }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        float p = 6 * dp, bh = Math.min(52 * dp, h * 0.2f), gap = 5 * dp;
        area.set(p, p, w - p, h - p - bh - gap);
        float bw = (w - 2 * p - 2 * gap) / 3f;
        for (int i = 0; i < 3; i++) buttons[i].set(p + i * (bw + gap), h - p - bh, p + i * (bw + gap) + bw, h - p);
    }

    @Override protected void onDraw(Canvas c) {
        c.drawColor(bg);
        pad.setColor(surface);
        c.drawRoundRect(area, 18 * dp, 18 * dp, pad);
        text.setColor(muted);
        text.setTextSize(13 * dp);
        c.drawText(hint, area.centerX(), area.centerY() - 6 * dp, text);
        text.setTextSize(11 * dp);
        c.drawText("1 dedo: mover · toque: clic · 2 dedos: desplazar", area.centerX(), area.centerY() + 14 * dp, text);
        for (int i = 0; i < 3; i++) {
            btn.setColor(pressedBtn == i ? accent : btnColor);
            c.drawRoundRect(buttons[i], 16 * dp, 16 * dp, btn);
            text.setTextSize(14 * dp);
            text.setColor(pressedBtn == i ? bg : fg);
            c.drawText(labels[i], buttons[i].centerX(), buttons[i].centerY() + 5 * dp, text);
        }
    }

    private int buttonAt(float x, float y) {
        for (int i = 0; i < 3; i++) if (buttons[i].contains(x, y)) return i;
        return -1;
    }

    private int padPointers(MotionEvent e) {
        int n = 0;
        for (int k = 0; k < e.getPointerCount(); k++) if (area.contains(e.getX(k), e.getY(k))) n++;
        return n;
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        int a = e.getActionMasked(), i = e.getActionIndex();
        float x = e.getX(i), y = e.getY(i);
        switch (a) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int b = buttonAt(x, y);
                if (b >= 0) {
                    pressedBtn = b;
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    invalidate();
                    return true;
                }
                if (padPointer < 0) {
                    padPointer = e.getPointerId(i);
                    lastX = startX = x; lastY = startY = y;
                    downTime = SystemClock.uptimeMillis();
                    moved = false; maxPointers = 1; scrollAcc = 0; accX = 0; accY = 0;
                } else maxPointers = Math.max(maxPointers, padPointers(e));
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (padPointer < 0) return true;
                int pi = e.findPointerIndex(padPointer);
                if (pi < 0) return true;
                float px = e.getX(pi), py = e.getY(pi), ddx = px - lastX, ddy = py - lastY;
                lastX = px; lastY = py;
                if (Math.hypot(px - startX, py - startY) > 8 * dp) moved = true;
                int n = padPointers(e);
                maxPointers = Math.max(maxPointers, n);
                if (sink == null) return true;
                if (n >= 2) {
                    scrollAcc += ddy;
                    int steps = (int) (scrollAcc / (22 * dp));
                    if (steps != 0) { sink.scroll(steps); scrollAcc -= steps * 22 * dp; }
                } else {
                    float speed = (float) Math.hypot(ddx, ddy) / dp;
                    float gain = 1.2f + Math.min(2.4f, speed / 10f);   // aceleración suave
                    accX += ddx / dp * gain; accY += ddy / dp * gain;
                    int mx = (int) accX, my = (int) accY;
                    if (mx != 0 || my != 0) { sink.move(mx, my); accX -= mx; accY -= my; }
                }
                return true;
            }
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_UP: {
                if (pressedBtn >= 0 && buttonAt(x, y) == pressedBtn) {
                    if (sink != null) {
                        if (pressedBtn == 0) sink.click();
                        else sink.nav(pressedBtn == 1 ? "back" : "home");
                    }
                    pressedBtn = -1;
                    invalidate();
                }
                if (e.getPointerId(i) == padPointer || a == MotionEvent.ACTION_UP) {
                    boolean tap = !moved && SystemClock.uptimeMillis() - downTime < 250;
                    if (tap && sink != null) {
                        if (maxPointers >= 2) sink.nav("back"); else sink.click();
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    }
                    padPointer = -1;
                }
                if (a == MotionEvent.ACTION_UP && pressedBtn >= 0) { pressedBtn = -1; invalidate(); }
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                padPointer = -1; pressedBtn = -1; invalidate();
                return true;
        }
        return true;
    }
}
