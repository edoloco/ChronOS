package cl.mesadoble;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Teclado en pantalla del teléfono que ESCRIBE. Envía caracteres listos (ñ, tildes, símbolos),
 * así que no importa cómo esté configurado el otro teléfono.
 */
public class KeyboardView extends View {

    public interface Sink {
        void text(String s);
        void key(int keycode, int meta);
        void action(String what);   // copy, cut, paste, all, undo
        void media(String what);    // play, vup, vdown
    }

    static final int CHAR = 0, KEY = 1, MOD = 2, ACT = 3, MEDIA = 4;
    static final int SHIFT = 1, CTRL = 2, ALTGR = 4;

    static class Key {
        final String label, shift, altgr, act;
        final int type, code;
        final float units;
        final RectF r = new RectF();
        boolean down;
        Key(int type, String label, String shift, String altgr, int code, String act, float units) {
            this.type = type; this.label = label; this.shift = shift; this.altgr = altgr;
            this.code = code; this.act = act; this.units = units;
        }
    }

    private static Key c(String l, String s, String a) { return new Key(CHAR, l, s, a, 0, null, 1); }
    private static Key c(String l, String s, String a, float u) { return new Key(CHAR, l, s, a, 0, null, u); }
    private static Key l(String l) { return new Key(CHAR, l, null, null, 0, null, 1); }
    private static Key key(String l, int code, float u) { return new Key(KEY, l, null, null, code, null, u); }
    private static Key mod(String l, int bit, float u) { return new Key(MOD, l, null, null, bit, null, u); }
    private static Key act(String l, String a) { return new Key(ACT, l, null, null, 0, a, 1.5f); }
    private static Key media(String l, String a) { return new Key(MEDIA, l, null, null, 0, a, 1.5f); }
    /** Envía lo escrito (como el botón de enviar del chat). */
    private static Key sendKey() { return new Key(ACT, "Enviar ➤", null, null, 0, "send", 2f); }

    private final List<Key[]> rows = new ArrayList<>();
    private final float[] rowWeights = {0.72f, 1, 1, 1, 1, 1};
    private final Map<Integer, Key> pointers = new HashMap<>();
    private int heldMods = 0, latchedMods = 0, usedWhileHeld = 0;
    private boolean caps = false;
    private String deadKey = null;
    private Sink sink;

    private final Handler repeat = new Handler(Looper.getMainLooper());
    private Key repeating;
    private final Runnable repeater = new Runnable() {
        @Override public void run() {
            if (repeating == null || !repeating.down) return;
            fire(repeating);
            repeat.postDelayed(this, 45);
        }
    };

    private final float dp;
    private final Paint keyBg = new Paint(Paint.ANTI_ALIAS_FLAG), main = new Paint(Paint.ANTI_ALIAS_FLAG),
            sub = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int bg, keyColor, keyDark, fg, muted, accent;

    public KeyboardView(Context ctx, boolean night) {
        super(ctx);
        dp = ctx.getResources().getDisplayMetrics().density;
        bg = night ? 0xFF121920 : 0xFFCDD5DC;
        keyColor = night ? 0xFF2A3848 : 0xFFFFFFFF;
        keyDark = night ? 0xFF1E2934 : 0xFFB7C2CC;
        accent = night ? 0xFF5CC3CF : 0xFF1F7F8C;
        fg = night ? 0xFFE6EDF3 : 0xFF1D2733;
        muted = night ? 0xFF8798A9 : 0xFF5B6B7A;
        main.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        main.setTextAlign(Paint.Align.CENTER);
        sub.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));

        rows.add(new Key[]{
                key("Esc", KeyEvent.KEYCODE_ESCAPE, 1.5f), act("Sel. todo", "all"), act("Copiar", "copy"),
                act("Cortar", "cut"), act("Pegar", "paste"), act("Deshacer", "undo"),
                media("⏯", "play"), media("Vol −", "vdown"), media("Vol +", "vup"),
                key("Supr", KeyEvent.KEYCODE_FORWARD_DEL, 1.5f)});
        rows.add(new Key[]{
                c("|", "°", "¬"), c("1", "!", null), c("2", "\"", null), c("3", "#", null), c("4", "$", null),
                c("5", "%", null), c("6", "&", null), c("7", "/", null), c("8", "(", null), c("9", ")", null),
                c("0", "=", null), c("'", "?", "\\"), c("¿", "¡", null), key("⌫", KeyEvent.KEYCODE_DEL, 2f)});
        rows.add(new Key[]{
                key("Tab", KeyEvent.KEYCODE_TAB, 1.5f), c("q", null, "@"), l("w"), l("e"), l("r"), l("t"),
                l("y"), l("u"), l("i"), l("o"), l("p"), c("´", "¨", null), c("+", "*", "~"), c("}", "]", "`", 1.5f)});
        rows.add(new Key[]{
                mod("Bloq", 0, 1.75f), l("a"), l("s"), l("d"), l("f"), l("g"), l("h"), l("j"), l("k"), l("l"),
                l("ñ"), c("{", "[", "^"), key("Enter", KeyEvent.KEYCODE_ENTER, 2.25f)});
        rows.add(new Key[]{
                mod("Mayús", SHIFT, 1.25f), c("<", ">", null), l("z"), l("x"), l("c"), l("v"), l("b"), l("n"),
                l("m"), c(",", ";", null), c(".", ":", null), c("-", "_", null), mod("Mayús", SHIFT, 2.75f)});
        rows.add(new Key[]{
                mod("Ctrl", CTRL, 1.5f), mod("AltGr", ALTGR, 1.5f), c(" ", null, null, 5f),
                key("←", KeyEvent.KEYCODE_DPAD_LEFT, 1.25f), key("↑", KeyEvent.KEYCODE_DPAD_UP, 1.25f),
                key("↓", KeyEvent.KEYCODE_DPAD_DOWN, 1.25f), key("→", KeyEvent.KEYCODE_DPAD_RIGHT, 1.25f),
                sendKey()});   // Enviar: esquina inferior derecha
    }

    public void setSink(Sink s) { sink = s; }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        float gap = 4 * dp, pad = 6 * dp, total = 0;
        for (float rw : rowWeights) total += rw;
        float avail = h - 2 * pad - gap * (rows.size() - 1);
        float y = pad;
        for (int i = 0; i < rows.size(); i++) {
            Key[] row = rows.get(i);
            float rh = avail * rowWeights[i] / total, units = 0;
            for (Key k : row) units += k.units;
            float uw = (w - 2 * pad - gap * (row.length - 1)) / units, x = pad;
            for (Key k : row) { float kw = uw * k.units; k.r.set(x, y, x + kw, y + rh); x += kw + gap; }
            y += rh + gap;
        }
    }

    // ---------------- dibujo ----------------

    private boolean isLetter(Key k) { return k.type == CHAR && k.label.length() == 1 && Character.isLetter(k.label.charAt(0)); }

    @Override protected void onDraw(Canvas cv) {
        cv.drawColor(bg);
        int mods = heldMods | latchedMods;
        boolean shift = (mods & SHIFT) != 0;
        for (Key[] row : rows) for (Key k : row) {
            boolean active = k.down || (k.type == MOD && k.code != 0 && (mods & k.code) != 0)
                    || (k.type == MOD && k.code == 0 && caps) || (k.type == CHAR && k.label.equals(deadKey));
            boolean special = !(k.type == CHAR);
            boolean send = k.type == ACT && "send".equals(k.act);
            keyBg.setColor(active || send ? accent : special ? keyDark : keyColor);
            float r = 7 * dp;
            cv.drawRoundRect(k.r, r, r, keyBg);
            String label = k.label;
            if (isLetter(k) && (shift ^ caps)) label = label.toUpperCase();
            boolean space = k.type == CHAR && " ".equals(k.label);
            if (space) label = deadKey != null ? deadKey + " …" : "espacio";
            float size = Math.min(k.r.height() * (label.length() == 1 ? 0.42f : 0.28f), 26 * dp);
            if (label.length() > 1) size = Math.min(size, k.r.width() / (label.length() * 0.6f));
            main.setTextSize(size);
            main.setColor(active || send ? bg : space ? muted : fg);
            cv.drawText(label, k.r.centerX(), k.r.centerY() + size * 0.36f, main);
            float ss = Math.min(11 * dp, k.r.height() * 0.24f);
            sub.setTextSize(ss);
            if (k.shift != null) {
                sub.setTextAlign(Paint.Align.LEFT);
                sub.setColor(active ? bg : muted);
                cv.drawText(k.shift, k.r.left + 5 * dp, k.r.top + ss + 3 * dp, sub);
            }
            if (k.altgr != null) {
                sub.setTextAlign(Paint.Align.RIGHT);
                sub.setColor(active ? bg : accent);
                cv.drawText(k.altgr, k.r.right - 5 * dp, k.r.bottom - 5 * dp, sub);
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
        int a = e.getActionMasked(), i = e.getActionIndex();
        if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_POINTER_DOWN) {
            Key k = keyAt(e.getX(i), e.getY(i));
            if (k != null) { pointers.put(e.getPointerId(i), k); press(k); }
        } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_POINTER_UP) {
            Key k = pointers.remove(e.getPointerId(i));
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
            if (k.code == 0) caps = !caps;
            else { heldMods |= k.code; usedWhileHeld &= ~k.code; }
        } else {
            usedWhileHeld |= heldMods;
            fire(k);
            if (k.type == KEY && (k.code == KeyEvent.KEYCODE_DEL || k.code == KeyEvent.KEYCODE_FORWARD_DEL
                    || k.code == KeyEvent.KEYCODE_DPAD_LEFT || k.code == KeyEvent.KEYCODE_DPAD_RIGHT
                    || k.code == KeyEvent.KEYCODE_DPAD_UP || k.code == KeyEvent.KEYCODE_DPAD_DOWN)) {
                repeating = k;
                repeat.removeCallbacks(repeater);
                repeat.postDelayed(repeater, 380);
            }
        }
        invalidate();
    }

    private void release(Key k) {
        k.down = false;
        if (k == repeating) { repeating = null; repeat.removeCallbacks(repeater); }
        if (k.type == MOD && k.code != 0) {
            heldMods &= ~k.code;
            // toque corto sin otra tecla: queda activo para la tecla siguiente
            if ((usedWhileHeld & k.code) == 0) latchedMods ^= k.code;
        } else if (k.type != MOD) {
            if (pointersOnlyMods()) latchedMods = 0;
        }
        invalidate();
    }

    private boolean pointersOnlyMods() {
        for (Key k : pointers.values()) if (k.type != MOD) return false;
        return true;
    }

    /** Ejecuta una tecla según los modificadores activos. */
    private void fire(Key k) {
        if (sink == null) return;
        int mods = heldMods | latchedMods;
        if (k.type == ACT) { sink.action(k.act); return; }
        if (k.type == MEDIA) { sink.media(k.act); return; }
        int meta = ((mods & SHIFT) != 0 ? KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_LEFT_ON : 0)
                | ((mods & CTRL) != 0 ? KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON : 0);
        if (k.type == KEY) {
            flushDead();
            sink.key(k.code, meta);
            return;
        }
        // tecla de carácter
        if ((mods & CTRL) != 0 && isLetter(k)) {
            String l = k.label;
            switch (l) {
                case "a": sink.action("all"); return;
                case "c": sink.action("copy"); return;
                case "x": sink.action("cut"); return;
                case "v": sink.action("paste"); return;
                case "z": sink.action("undo"); return;
            }
            char ch = l.charAt(0);
            if (ch >= 'a' && ch <= 'z') sink.key(KeyEvent.KEYCODE_A + (ch - 'a'), meta);
            return;
        }
        String out;
        if ((mods & ALTGR) != 0 && k.altgr != null) out = k.altgr;
        else if (isLetter(k)) out = ((mods & SHIFT) != 0) ^ caps ? k.label.toUpperCase() : k.label;
        else if ((mods & SHIFT) != 0 && k.shift != null) out = k.shift;
        else out = k.label;

        if (out.equals("´") || out.equals("¨")) {   // tilde o diéresis: se combina con la letra siguiente
            if (deadKey != null) { sink.text(deadKey); }
            deadKey = out;
            return;
        }
        if (deadKey != null) {
            String combined = compose(deadKey, out);
            deadKey = null;
            sink.text(combined);
            return;
        }
        sink.text(out);
    }

    private void flushDead() {
        if (deadKey != null && sink != null) { sink.text(deadKey); deadKey = null; invalidate(); }
    }

    private static String compose(String dead, String ch) {
        String table = dead.equals("´") ? "aáeéiíoóuúAÁEÉIÍOÓUÚ" : "uüUÜiïIÏeëEËaäAÄoöOÖ";
        int i = table.indexOf(ch);
        if (i >= 0 && i % 2 == 0) return String.valueOf(table.charAt(i + 1));
        if (ch.equals(" ")) return dead;
        return dead + ch;
    }
}
