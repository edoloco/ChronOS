package cl.mesadoble;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Dibuja la mitad del escritorio que corresponde a este teléfono y maneja el arrastre. */
public class DeskView extends View {

    public interface Host {
        void onItemChanged(Item it);
        void onItemDeleted(Item it);
        void onDrag(Item it, double x, double y);
        void onDragEnd(Item it);
        void onEditNote(Item it);
        void onOpenLink(Item it);
    }

    public final List<Item> items = new ArrayList<>();
    private final Map<String, double[]> remoteDrags = new HashMap<>();
    private Host host;
    private int side = 0;
    private boolean connected = false;
    private final boolean night;

    private final float dp;
    private final int touchSlop;
    private final Paint bg = new Paint(), dot = new Paint(Paint.ANTI_ALIAS_FLAG), glow = new Paint(),
            edge = new Paint(), card = new Paint(Paint.ANTI_ALIAS_FLAG), btn = new Paint(Paint.ANTI_ALIAS_FLAG),
            icon = new Paint(Paint.ANTI_ALIAS_FLAG), bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final TextPaint hand = new TextPaint(Paint.ANTI_ALIAS_FLAG), small = new TextPaint(Paint.ANTI_ALIAS_FLAG),
            label = new TextPaint(Paint.ANTI_ALIAS_FLAG), empty = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final int deskColor, deskColor2, accent, fg, muted, linkBg;

    // estado del toque
    private Item pressed, dragging;
    private String pressedBtn;
    private float downX, downY;
    private double ox, oy, dragX, dragY;
    private boolean moved;
    private VelocityTracker vt;

    public DeskView(Context c, boolean night) {
        super(c);
        this.night = night;
        dp = getResources().getDisplayMetrics().density;
        touchSlop = ViewConfiguration.get(c).getScaledTouchSlop();
        deskColor = night ? 0xFF18212B : 0xFFDDE3E8;
        deskColor2 = night ? 0xFF243346 : 0xFFC6D2DC;
        accent = night ? 0xFF5CC3CF : 0xFF1F7F8C;
        fg = night ? 0xFFE6EDF3 : 0xFF1D2733;
        muted = night ? 0xFF93A4B5 : 0xFF5B6B7A;
        linkBg = night ? 0xFF223040 : 0xFFFFFFFF;

        bg.setColor(deskColor);
        dot.setColor(night ? 0x1ABED7EB : 0x29283C50);
        card.setShadowLayer(10 * dp, 0, 5 * dp, 0x55000000);
        icon.setStyle(Paint.Style.STROKE);
        icon.setStrokeWidth(2.2f * dp);
        icon.setStrokeCap(Paint.Cap.ROUND);
        icon.setStrokeJoin(Paint.Join.ROUND);

        hand.setTypeface(Typeface.create("casual", Typeface.NORMAL));
        hand.setColor(0xFF2B2A22);
        small.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        label.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        label.setLetterSpacing(0.12f);
        label.setTextSize(10 * dp);
        empty.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        empty.setColor(muted);
        empty.setTextSize(16 * dp);
    }

    public void setHost(Host h) { host = h; }
    public boolean isDragging(Item it) { return dragging == it; }
    public void setSide(int s) { side = s; invalidate(); }
    public void setConnected(boolean c) { connected = c; invalidate(); }

    public Item find(String id) {
        for (Item it : items) if (it.id.equals(id)) return it;
        return null;
    }

    public void setRemoteDrag(String id, double x, double y) { remoteDrags.put(id, new double[]{x, y}); invalidate(); }
    public void clearRemoteDrag(String id) { remoteDrags.remove(id); invalidate(); }
    public void clearAllRemoteDrags() { remoteDrags.clear(); invalidate(); }

    /** Lugar libre aproximado dentro de la pantalla de este teléfono. */
    public double[] spot(double w) {
        double x = side + 0.08 + Math.random() * Math.max(0.01, 0.84 - w);
        double y = 0.14 + Math.random() * 0.4;
        return new double[]{x, y};
    }

    public void sendAcross(Item it) {
        boolean onLeft = it.x + it.w / 2 < 1;
        it.x = onLeft ? 1.04 + Math.random() * 0.1 : 1 - 0.04 - it.w - Math.random() * 0.1;
        it.z = System.currentTimeMillis();
        it.touch();
        invalidate();
        if (host != null) host.onItemChanged(it);
    }

    // ---------------- dibujo ----------------

    @Override protected void onDraw(Canvas c) {
        int W = getWidth(), H = getHeight();
        c.drawRect(0, 0, W, H, bg);
        // brillo centrado en la unión de ambas pantallas: se ve continuo entre los dos teléfonos
        float seam = (1 - side) * W;
        glow.setShader(new RadialGradient(seam, H * 0.45f, Math.max(W, H) * 0.95f, deskColor2, deskColor, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, H, glow);
        float step = 22 * dp;
        float offset = (side * W) % step;
        for (float x = step / 2 - offset; x < W; x += step)
            for (float y = step / 2; y < H; y += step) c.drawCircle(x, y, 1.2f * dp, dot);

        boolean hot = !remoteDrags.isEmpty();
        if (dragging != null) hot |= side == 0 ? dragX + dragging.w > 0.97 : dragX < 1.03;
        float ew = (hot ? 18 : 7) * dp;
        int a = hot ? 255 : connected ? 200 : 90;
        int ac = Color.argb(a, Color.red(accent), Color.green(accent), Color.blue(accent));
        if (side == 0) {
            edge.setShader(new LinearGradient(W, 0, W - ew, 0, ac, 0x00000000, Shader.TileMode.CLAMP));
            c.drawRect(W - ew, 0, W, H, edge);
        } else {
            edge.setShader(new LinearGradient(0, 0, ew, 0, ac, 0x00000000, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, ew, H, edge);
        }

        if (items.isEmpty()) {
            String t = "Escritorio vacío. Agrega una nota o una imagen.";
            StaticLayout l = StaticLayout.Builder.obtain(t, 0, t.length(), empty, (int) (W * 0.7f))
                    .setAlignment(Layout.Alignment.ALIGN_CENTER).build();
            c.save(); c.translate(W * 0.15f, H * 0.45f); l.draw(c); c.restore();
        }

        List<Item> sorted = new ArrayList<>(items);
        Collections.sort(sorted, (p, q) -> Long.compare(p.z, q.z));
        if (dragging != null) { sorted.remove(dragging); sorted.add(dragging); }
        boolean again = false;
        for (Item it : sorted) {
            double tx = it.x, ty = it.y;
            float k = 0.26f;
            double[] rd = remoteDrags.get(it.id);
            if (it == dragging) { tx = dragX; ty = dragY; k = 1f; }
            else if (rd != null) { tx = rd[0]; ty = rd[1]; k = 0.55f; }
            if (Float.isNaN(it.dx)) { it.dx = (float) tx; it.dy = (float) ty; }
            it.dx += (float) (tx - it.dx) * k;
            it.dy += (float) (ty - it.dy) * k;
            if (Math.abs(tx - it.dx) > 0.0004 || Math.abs(ty - it.dy) > 0.0004) again = true;
            else { it.dx = (float) tx; it.dy = (float) ty; }
            float left = (it.dx - side) * W, top = it.dy * H, width = (float) (it.w * W);
            if (left > W + 10 || left + width < -10) { measure(it, width); continue; }
            drawItem(c, it, left, top, width, it == dragging || rd != null);
        }
        if (again) postInvalidateOnAnimation();
    }

    private float measure(Item it, float width) {
        float pad = 14 * dp;
        if (Item.NOTE.equals(it.kind)) {
            hand.setTextSize(Math.min(22 * dp, Math.max(17 * dp, width * 0.075f)));
            String t = it.text.isEmpty() ? "Toca para escribir" : it.text;
            String key = t + "|" + (int) width + "|" + hand.getTextSize();
            if (!key.equals(it.layoutKey)) {
                it.layout = StaticLayout.Builder.obtain(t, 0, t.length(), hand, (int) Math.max(10, width - 2 * pad))
                        .setLineSpacing(0, 1.1f).build();
                it.layoutKey = key;
            }
            it.hPx = Math.max(96 * dp, 34 * dp + it.layout.getHeight() + pad);
        } else if (Item.IMG.equals(it.kind)) {
            Bitmap b = it.bitmap();
            float inner = width - 16 * dp;
            float ih = b != null ? inner * b.getHeight() / (float) b.getWidth() : inner * 0.7f;
            it.hPx = 8 * dp + ih + 36 * dp;
        } else {
            it.hPx = 132 * dp;
        }
        return it.hPx;
    }

    private void drawItem(Canvas c, Item it, float left, float top, float width, boolean lifted) {
        float h = measure(it, width);
        float pad = 14 * dp, r = 6 * dp;
        c.save();
        c.translate(left, top);
        c.rotate(it.rot, width / 2, h / 2);
        if (lifted) c.scale(1.03f, 1.03f, width / 2, h / 2);
        RectF box = new RectF(0, 0, width, h);

        boolean isNote = Item.NOTE.equals(it.kind), isImg = Item.IMG.equals(it.kind);
        card.setColor(isNote ? it.color : isImg ? 0xFFFFFFFF : linkBg);
        card.setShadowLayer((lifted ? 18 : 10) * dp, 0, (lifted ? 10 : 5) * dp, 0x55000000);
        c.drawRoundRect(box, r, r, card);

        int ink = isNote || isImg ? 0xFF2B2A22 : fg;
        label.setColor((ink & 0x00FFFFFF) | 0x77000000);
        String tag = isNote ? "NOTA" : isImg ? "IMAGEN" : "ENLACE";
        if (isImg) c.drawText(tag, 10 * dp, h - 12 * dp, label);
        else c.drawText(tag, 10 * dp, 18 * dp, label);

        if (isNote) {
            hand.setColor(it.text.isEmpty() ? 0x772B2A22 : 0xFF2B2A22);
            c.save(); c.translate(pad, 32 * dp); it.layout.draw(c); c.restore();
        } else if (isImg) {
            Bitmap b = it.bitmap();
            if (b != null) {
                RectF dst = new RectF(8 * dp, 8 * dp, width - 8 * dp, h - 36 * dp);
                c.drawBitmap(b, new Rect(0, 0, b.getWidth(), b.getHeight()), dst, bmpPaint);
            }
        } else {
            small.setColor(muted);
            small.setTextSize(12 * dp);
            String dom = host(it.url);
            c.drawText(ellipsize(dom.toUpperCase(), small, width - 2 * pad), pad, 46 * dp, small);
            small.setColor(fg);
            small.setTextSize(16 * dp);
            String t = it.title.isEmpty() ? dom : it.title;
            c.drawText(ellipsize(t, small, width - 2 * pad), pad, 72 * dp, small);
            it.openBtn.set(pad, 88 * dp, pad + 92 * dp, 120 * dp);
            btn.setColor((accent & 0x00FFFFFF) | 0x38000000);
            c.drawRoundRect(it.openBtn, 16 * dp, 16 * dp, btn);
            small.setColor(accent);
            small.setTextSize(14 * dp);
            c.drawText("Abrir  ↗", it.openBtn.left + 14 * dp, it.openBtn.centerY() + 5 * dp, small);
        }

        // botones: enviar al otro lado y quitar
        float bw = 34 * dp, bh = 28 * dp, gap = 3 * dp;
        float by = isImg ? h - bh - 4 * dp : 4 * dp;
        it.delBtn.set(width - 4 * dp - bw, by, width - 4 * dp, by + bh);
        it.sendBtn.set(it.delBtn.left - gap - bw, by, it.delBtn.left - gap, by + bh);
        btn.setColor(isNote || isImg ? 0x14000000 : (accent & 0x00FFFFFF) | 0x38000000);
        c.drawRoundRect(it.sendBtn, 6 * dp, 6 * dp, btn);
        c.drawRoundRect(it.delBtn, 6 * dp, 6 * dp, btn);
        icon.setColor(isNote || isImg ? 0xFF2B2A22 : fg);
        float cx = it.sendBtn.centerX(), cy = it.sendBtn.centerY(), s = 6 * dp;
        boolean toRight = it.x + it.w / 2 < 1;
        float dir = toRight ? 1 : -1;
        Path p = new Path();
        p.moveTo(cx - dir * s, cy); p.lineTo(cx + dir * s, cy);
        p.moveTo(cx + dir * (s - 5 * dp), cy - 5 * dp); p.lineTo(cx + dir * s, cy); p.lineTo(cx + dir * (s - 5 * dp), cy + 5 * dp);
        c.drawPath(p, icon);
        cx = it.delBtn.centerX(); cy = it.delBtn.centerY(); s = 5 * dp;
        c.drawLine(cx - s, cy - s, cx + s, cy + s, icon);
        c.drawLine(cx + s, cy - s, cx - s, cy + s, icon);
        c.restore();
    }

    private static String ellipsize(String t, Paint p, float max) {
        if (p.measureText(t) <= max) return t;
        while (t.length() > 1 && p.measureText(t + "…") > max) t = t.substring(0, t.length() - 1);
        return t + "…";
    }

    static String host(String url) {
        try {
            String h = android.net.Uri.parse(url).getHost();
            if (h == null) return url;
            return h.startsWith("www.") ? h.substring(4) : h;
        } catch (Exception e) { return url; }
    }

    // ---------------- toque ----------------

    private Item hit(float px, float py) {
        int W = getWidth(), H = getHeight();
        List<Item> sorted = new ArrayList<>(items);
        Collections.sort(sorted, (p, q) -> Long.compare(q.z, p.z));
        for (Item it : sorted) {
            float left = (it.dx - side) * W, top = it.dy * H, width = (float) (it.w * W);
            if (px >= left && px <= left + width && py >= top && py <= top + it.hPx) return it;
        }
        return null;
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        int W = getWidth(), H = getHeight();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                pressed = hit(e.getX(), e.getY());
                if (pressed == null) return true;
                float lx = e.getX() - (pressed.dx - side) * W, ly = e.getY() - pressed.dy * H;
                pressedBtn = pressed.sendBtn.contains(lx, ly) ? "send" : pressed.delBtn.contains(lx, ly) ? "del"
                        : Item.LINK.equals(pressed.kind) && pressed.openBtn.contains(lx, ly) ? "open" : null;
                downX = e.getX(); downY = e.getY();
                ox = pressed.x; oy = pressed.y; moved = false;
                if (vt != null) vt.recycle();
                vt = VelocityTracker.obtain();
                vt.addMovement(e);
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (pressed == null) return true;
                if (vt != null) vt.addMovement(e);
                float ddx = e.getX() - downX, ddy = e.getY() - downY;
                if (!moved && Math.hypot(ddx, ddy) < touchSlop) return true;
                if (!moved) { moved = true; dragging = pressed; dragging.z = System.currentTimeMillis(); }
                dragX = ox + ddx / W;
                dragY = Math.max(-0.05, Math.min(0.95, oy + ddy / H));
                if (host != null) host.onDrag(dragging, dragX, dragY);
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                Item it = pressed; pressed = null;
                if (it == null) return true;
                double vx = 0;
                if (vt != null) { vt.addMovement(e); vt.computeCurrentVelocity(1000); vx = vt.getXVelocity() / W; vt.recycle(); vt = null; }
                if (!moved) {
                    if (e.getActionMasked() == MotionEvent.ACTION_UP) tap(it);
                    return true;
                }
                dragging = null;
                double x = dragX, y = dragY;
                double over = side == 0 ? (x + it.w) - 1 : 1 - x;
                boolean flung = side == 0 ? vx > 1.6 : vx < -1.6;   // pantallas por segundo
                if (over > it.w * 0.35 || (flung && over > -it.w * 0.6)) {
                    x = side == 0 ? 1.04 + Math.random() * 0.08 : 1 - 0.04 - it.w - Math.random() * 0.08;
                } else {
                    x = Math.min(Math.max(x, side + 0.02), side + 0.98 - it.w);
                }
                double hf = it.hPx / H;
                y = Math.min(Math.max(y, 0.04), Math.max(0.04, 0.97 - hf));
                it.x = x; it.y = y; it.z = System.currentTimeMillis(); it.touch();
                invalidate();
                if (host != null) { host.onItemChanged(it); host.onDragEnd(it); }
                return true;
            }
        }
        return true;
    }

    private void tap(Item it) {
        if ("send".equals(pressedBtn)) sendAcross(it);
        else if ("del".equals(pressedBtn)) { items.remove(it); invalidate(); if (host != null) host.onItemDeleted(it); }
        else if (Item.NOTE.equals(it.kind)) { if (host != null) host.onEditNote(it); }
        else if (Item.LINK.equals(it.kind) && "open".equals(pressedBtn)) { if (host != null) host.onOpenLink(it); }
        else { it.z = System.currentTimeMillis(); invalidate(); }
    }
}
