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
 * Touchpad: un dedo mueve el puntero, un toque hace clic,
 * dos dedos desplazan (scroll) y un toque con dos dedos es clic derecho (en Android: atrás).
 * Abajo tiene botones de clic izquierdo y derecho; mantener el izquierdo permite arrastrar.
 */
public class TouchpadView extends View {

    private Hid hid;
    private final float dp;
    private final Paint pad = new Paint(Paint.ANTI_ALIAS_FLAG), btn = new Paint(Paint.ANTI_ALIAS_FLAG),
            text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int bg, surface, btnColor, btnDown, muted;
    private final RectF area = new RectF(), left = new RectF(), right = new RectF();

    private float lastX, lastY, startX, startY, scrollAcc;
    private long downTime;
    private int maxPointers, buttons;
    private boolean moved, leftDown, rightDown;
    private int padPointer = -1;

    public TouchpadView(Context c, boolean night) {
        super(c);
        dp = getResources().getDisplayMetrics().density;
        bg = night ? 0xFF121920 : 0xFFCDD5DC;
        surface = night ? 0xFF1E2934 : 0xFFE6EBEF;
        btnColor = night ? 0xFF2A3848 : 0xFFFFFFFF;
        btnDown = night ? 0xFF5CC3CF : 0xFF1F7F8C;
        muted = night ? 0xFF8798A9 : 0xFF5B6B7A;
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    }

    public void setHid(Hid h) { hid = h; }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        float p = 6 * dp, bh = Math.min(56 * dp, h * 0.22f), gap = 4 * dp;
        area.set(p, p, w - p, h - p - bh - gap);
        left.set(p, h - p - bh, w / 2f - gap / 2, h - p);
        right.set(w / 2f + gap / 2, h - p - bh, w - p, h - p);
    }

    @Override protected void onDraw(Canvas c) {
        c.drawColor(bg);
        float r = 12 * dp;
        pad.setColor(surface);
        c.drawRoundRect(area, r, r, pad);
        text.setColor(muted);
        text.setTextSize(13 * dp);
        c.drawText("Touchpad", area.centerX(), area.centerY() - 8 * dp, text);
        text.setTextSize(11 * dp);
        c.drawText("1 dedo: mover · toque: clic · 2 dedos: desplazar", area.centerX(), area.centerY() + 12 * dp, text);
        btn.setColor(leftDown ? btnDown : btnColor);
        c.drawRoundRect(left, 10 * dp, 10 * dp, btn);
        btn.setColor(rightDown ? btnDown : btnColor);
        c.drawRoundRect(right, 10 * dp, 10 * dp, btn);
        text.setTextSize(13 * dp);
        text.setColor(leftDown ? bg : muted);
        c.drawText("Clic", left.centerX(), left.centerY() + 5 * dp, text);
        text.setColor(rightDown ? bg : muted);
        c.drawText("Clic derecho", right.centerX(), right.centerY() + 5 * dp, text);
    }

    private void sendButtons() {
        buttons = (leftDown ? 1 : 0) | (rightDown ? 2 : 0);
        if (hid != null) hid.mouse(buttons, 0, 0, 0);
        invalidate();
    }

    private void click(int b) {
        if (hid == null) return;
        hid.mouse(buttons | b, 0, 0, 0);
        hid.mouse(buttons, 0, 0, 0);
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        int a = e.getActionMasked(), i = e.getActionIndex();
        float x = e.getX(i), y = e.getY(i);
        switch (a) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                if (left.contains(x, y)) { leftDown = true; sendButtons(); performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); return true; }
                if (right.contains(x, y)) { rightDown = true; sendButtons(); performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); return true; }
                if (a == MotionEvent.ACTION_DOWN || padPointer < 0) {
                    padPointer = e.getPointerId(i);
                    lastX = startX = x; lastY = startY = y;
                    downTime = SystemClock.uptimeMillis();
                    moved = false; maxPointers = 1; scrollAcc = 0;
                } else {
                    maxPointers = Math.max(maxPointers, padPointers(e));
                }
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (padPointer < 0) return true;
                int pi = e.findPointerIndex(padPointer);
                if (pi < 0) return true;
                float px = e.getX(pi), py = e.getY(pi);
                float ddx = px - lastX, ddy = py - lastY;
                lastX = px; lastY = py;
                if (Math.hypot(px - startX, py - startY) > 8 * dp) moved = true;
                int n = padPointers(e);
                maxPointers = Math.max(maxPointers, n);
                if (hid == null) return true;
                if (n >= 2) {
                    scrollAcc += ddy;
                    int steps = (int) (scrollAcc / (18 * dp));
                    if (steps != 0) { hid.mouse(buttons, 0, 0, steps); scrollAcc -= steps * 18 * dp; }
                } else {
                    float speed = (float) Math.hypot(ddx, ddy) / dp;
                    float gain = 1.1f + Math.min(2.2f, speed / 12f);   // aceleración suave
                    int mx = Math.round(ddx / dp * gain), my = Math.round(ddy / dp * gain);
                    if (mx != 0 || my != 0) hid.mouse(buttons, mx, my, 0);
                }
                return true;
            }
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_UP: {
                if (leftDown && left.contains(x, y)) { leftDown = false; sendButtons(); }
                else if (rightDown && right.contains(x, y)) { rightDown = false; sendButtons(); }
                if (e.getPointerId(i) == padPointer || a == MotionEvent.ACTION_UP) {
                    boolean tap = !moved && SystemClock.uptimeMillis() - downTime < 250;
                    if (tap) click(maxPointers >= 2 ? 2 : 1);
                    padPointer = -1;
                }
                if (a == MotionEvent.ACTION_UP && (leftDown || rightDown)) { leftDown = rightDown = false; sendButtons(); }
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                padPointer = -1;
                if (leftDown || rightDown) { leftDown = rightDown = false; sendButtons(); }
                return true;
        }
        return true;
    }

    private int padPointers(MotionEvent e) {
        int n = 0;
        for (int k = 0; k < e.getPointerCount(); k++) if (area.contains(e.getX(k), e.getY(k))) n++;
        return n;
    }
}
