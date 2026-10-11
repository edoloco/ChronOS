package cl.mesadoble;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Teclado en pantalla que envía teclas físicas (códigos HID).
 * Las etiquetas siguen la distribución "Español (Latinoamérica)": el otro teléfono
 * debe tener ese idioma elegido para el teclado físico, así ñ, tildes y símbolos coinciden.
 */
public class KeyboardView extends View {

    static final int NORMAL = 0, MOD = 1, MEDIA = 2;

    static class Key {
        final String label, shift, altgr;
        final int code, type;
        final float units;
        final RectF r = new RectF();
        boolean down;
        Key(String label, String shift, String altgr, int code, float units, int type) {
            this.label = label; this.shift = shift; this.altgr = altgr;
            this.code = code; this.units = units; this.type = type;
        }
    }

    private final List<Key[]> rows = new ArrayList<>();
    private final float[] rowWeights;
    private final Map<Integer, Key> pointers = new HashMap<>();
    private final List<Integer> held = new ArrayList<>();   // códigos de teclas presionadas
    private int heldMods = 0, latchedMods = 0, usedWhileHeld = 0;
    private boolean caps = false;
    private Hid hid;

    private final float dp;
    private final Paint keyBg = new Paint(Paint.ANTI_ALIAS_FLAG), main = new Paint(Paint.ANTI_ALIAS_FLAG),
            sub = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int bg, keyColor, keyDark, keyDown, fg, muted, accent;

    private static Key k(String l, String s, String a, int code) { return new Key(l, s, a, code, 1, NORMAL); }
    private static Key k(String l, String s, String a, int code, float u) { return new Key(l, s, a, code, u, NORMAL); }
    private static Key letter(char c, int code) { return new Key(String.valueOf(c), null, null, code, 1, NORMAL); }
    private static Key mod(String l, int bit, float u) { return new Key(l, null, null, bit, u, MOD); }
    private static Key media(String l, int usage) { return new Key(l, null, null, usage, 1.5f, MEDIA); }

    public KeyboardView(Context c, boolean night) {
        super(c);
        dp = getResources().getDisplayMetrics().density;
        bg = night ? 0xFF121920 : 0xFFCDD5DC;
        keyColor = night ? 0xFF2A3848 : 0xFFFFFFFF;
        keyDark = night ? 0xFF1E2934 : 0xFFB7C2CC;
        accent = night ? 0xFF5CC3CF : 0xFF1F7F8C;
        keyDown = accent;
        fg = night ? 0xFFE6EDF3 : 0xFF1D2733;
        muted = night ? 0xFF8798A9 : 0xFF5B6B7A;
        main.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        main.setTextAlign(Paint.Align.CENTER);
        sub.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));

        rows.add(new Key[]{
                k("Esc", null, null, 0x29, 1.5f), media("Atrás", Hid.AC_BACK), media("Inicio", Hid.AC_HOME),
                media("⏮", Hid.MEDIA_PREV), media("⏯", Hid.MEDIA_PLAY), media("⏭", Hid.MEDIA_NEXT),
                media("Vol −", Hid.VOL_DOWN), media("Vol +", Hid.VOL_UP), media("Silencio", Hid.MUTE),
                k("Supr", null, null, 0x4C, 1.5f)});
        rows.add(new Key[]{
                k("|", "°", "¬", 0x35), k("1", "!", null, 0x1E), k("2", "\"", null, 0x1F), k("3", "#", null, 0x20),
                k("4", "$", null, 0x21), k("5", "%", null, 0x22), k("6", "&", null, 0x23), k("7", "/", null, 0x24),
                k("8", "(", null, 0x25), k("9", ")", null, 0x26), k("0", "=", null, 0x27),
                k("'", "?", "\\", 0x2D), k("¿", "¡", null, 0x2E), k("⌫", null, null, 0x2A, 2f)});
        rows.add(new Key[]{
                k("Tab", null, null, 0x2B, 1.5f), new Key("q", null, "@", 0x14, 1, NORMAL), letter('w', 0x1A),
                letter('e', 0x08), letter('r', 0x15), letter('t', 0x17), letter('y', 0x1C), letter('u', 0x18),
                letter('i', 0x0C), letter('o', 0x12), letter('p', 0x13),
                k("´", "¨", null, 0x2F), k("+", "*", "~", 0x30), k("}", "]", "`", 0x31, 1.5f)});
        rows.add(new Key[]{
                k("Bloq", null, null, 0x39, 1.75f), letter('a', 0x04), letter('s', 0x16), letter('d', 0x07),
                letter('f', 0x09), letter('g', 0x0A), letter('h', 0x0B), letter('j', 0x0D), letter('k', 0x0E),
                letter('l', 0x0F), letter('ñ', 0x33), k("{", "[", "^", 0x34), k("Enter", null, null, 0x28, 2.25f)});
        rows.add(new Key[]{
                mod("Mayús", Hid.SHIFT, 1.25f), k("<", ">", null, 0x64), letter('z', 0x1D), letter('x', 0x1B),
                letter('c', 0x06), letter('v', 0x19), letter('b', 0x05), letter('n', 0x11), letter('m', 0x10),
                k(",", ";", null, 0x36), k(".", ":", null, 0x37), k("-", "_", null, 0x38), mod("Mayús", Hid.SHIFT, 2.75f)});
        rows.add(new Key[]{
                mod("Ctrl", Hid.CTRL, 1.25f), mod("Alt", Hid.ALT, 1.25f), k("", null, null, 0x2C, 6f),
                mod("AltGr", Hid.ALTGR, 1.5f), k("←", null, null, 0x50, 1.25f), k("↑", null, null, 0x52, 1.25f),
                k("↓", null, null, 0x51, 1.25f), k("→", null, null, 0x4F, 1.25f)});
        rowWeights = new float[]{0.72f, 1, 1, 1, 1, 1};
    }

    public void setHid(Hid h) { hid = h; }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) { layoutKeys(w, h); }

    private void layoutKeys(int w, int h) {
        float gap = 4 * dp, pad = 6 * dp;
        float total = 0;
        for (float rw : rowWeights) total += rw;
        float avail = h - 2 * pad - gap * (rows.size() - 1);
        float y = pad;
        for (int i = 0; i < rows.size(); i++) {
            Key[] row = rows.get(i);
            float rh = avail * rowWeights[i] / total;
            float units = 0;
            for (Key k : row) units += k.units;
            float uw = (w - 2 * pad - gap * (row.length - 1)) / units;
            float x = pad;
            for (Key k : row) {
                float kw = uw * k.units;
                k.r.set(x, y, x + kw, y + rh);
                x += kw + gap;
            }
            y += rh + gap;
        }
        invalidate();
    }

    // ---------------- dibujo ----------------

    @Override protected void onDraw(Canvas c) {
        c.drawColor(bg);
        int mods = heldMods | latchedMods;
        boolean upper = ((mods & Hid.SHIFT) != 0) ^ caps;
        for (Key[] row : rows) for (Key k : row) {
            boolean active = k.down || (k.type == MOD && (mods & k.code) != 0) || (k.code == 0x39 && caps);
            boolean special = k.type != NORMAL || k.label.length() > 1 && !k.label.equals("ñ") || k.code == 0x2C;
            keyBg.setColor(active ? keyDown : special ? keyDark : keyColor);
            float r = 7 * dp;
            c.drawRoundRect(k.r, r, r, keyBg);
            int textColor = active ? bg : fg;
            String label = k.label;
            boolean isLetter = label.length() == 1 && Character.isLetter(label.charAt(0));
            if (isLetter && upper) label = label.toUpperCase();
            if (k.code == 0x2C && k.type == NORMAL) label = "espacio";
            float size = Math.min(k.r.height() * (isLetter || label.length() == 1 ? 0.42f : 0.28f), 26 * dp);
            if (label.length() > 1) size = Math.min(size, k.r.width() / (label.length() * 0.62f));
            main.setTextSize(size);
            main.setColor(k.code == 0x2C ? muted : textColor);
            c.drawText(label, k.r.centerX(), k.r.centerY() + size * 0.36f, main);
            float ss = Math.min(11 * dp, k.r.height() * 0.24f);
            sub.setTextSize(ss);
            sub.setColor(active ? bg : muted);
            if (k.shift != null) { sub.setTextAlign(Paint.Align.LEFT); c.drawText(k.shift, k.r.left + 5 * dp, k.r.top + ss + 3 * dp, sub); }
            if (k.altgr != null) {
                sub.setTextAlign(Paint.Align.RIGHT);
                sub.setColor(active ? bg : accent);
                c.drawText(k.altgr, k.r.right - 5 * dp, k.r.bottom - 5 * dp, sub);
            }
        }
    }

    // ---------------- toques (multitáctil) ----------------

    private Key keyAt(float x, float y) {
        for (Key[] row : rows) for (Key k : row) {
            RectF r = k.r;
            if (x >= r.left - 2 * dp && x <= r.right + 2 * dp && y >= r.top - 2 * dp && y <= r.bottom + 2 * dp) return k;
        }
        return null;
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        int a = e.getActionMasked();
        int idx = e.getActionIndex();
        if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_POINTER_DOWN) {
            Key k = keyAt(e.getX(idx), e.getY(idx));
            if (k != null) { pointers.put(e.getPointerId(idx), k); press(k); }
        } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_POINTER_UP) {
            Key k = pointers.remove(e.getPointerId(idx));
            if (k != null) release(k);
        } else if (a == MotionEvent.ACTION_CANCEL) {
            for (Key k : new ArrayList<>(pointers.values())) release(k);
            pointers.clear();
        }
        return true;
    }

    private void press(Key k) {
        k.down = true;
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        if (k.type == MOD) {
            heldMods |= k.code;
            usedWhileHeld &= ~k.code;
        } else if (k.type == MEDIA) {
            if (hid != null) hid.media(k.code);
        } else {
            if (k.code == 0x39) caps = !caps;
            if (!held.contains(k.code)) held.add(k.code);
            usedWhileHeld |= heldMods;
            sendKeys();
        }
        invalidate();
    }

    private void release(Key k) {
        k.down = false;
        if (k.type == MOD) {
            heldMods &= ~k.code;
            // un toque corto (sin otra tecla) deja el modificador "enganchado" para la siguiente tecla
            if ((usedWhileHeld & k.code) == 0) latchedMods ^= k.code;
            sendKeys();
        } else if (k.type == MEDIA) {
            if (hid != null) hid.media(0);
        } else {
            held.remove(Integer.valueOf(k.code));
            sendKeys();
            if (held.isEmpty() && latchedMods != 0) { latchedMods = 0; sendKeys(); }
        }
        invalidate();
    }

    private void sendKeys() {
        if (hid == null) return;
        int[] codes = new int[Math.min(6, held.size())];
        for (int i = 0; i < codes.length; i++) codes[i] = held.get(i);
        int mods = heldMods | (held.isEmpty() ? 0 : latchedMods);
        hid.keys(mods, codes);
    }
}
