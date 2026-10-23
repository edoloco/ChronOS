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
        boolean down, emoji;
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
    private float[] rowWeights = {0.72f, 1, 1, 1, 1, 1.18f};
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
            sub = new Paint(Paint.ANTI_ALIAS_FLAG), keyStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int bg, keyColor, keyDark, fg, muted, accent;

    public KeyboardView(Context ctx, boolean night) {
        super(ctx);
        dp = ctx.getResources().getDisplayMetrics().density;
        KbTheme th = KbTheme.of(ctx, night);   // a tono con el tema elegido
        bg = th.bg;
        keyColor = th.key;
        keyDark = th.keyDark;
        accent = th.accent;
        fg = th.fg;
        muted = th.muted;
        keyStroke.setStyle(Paint.Style.STROKE);
        keyStroke.setStrokeWidth(dp);
        keyStroke.setColor(th.stroke);
        main.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        main.setTextAlign(Paint.Align.CENTER);
        sub.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));

        android = ctx.getSharedPreferences("mesa", Context.MODE_PRIVATE).getBoolean("kbAndroid", false);
        build();
    }

    // ---------------- distribuciones: PC o Android (como el de Google) ----------------

    private boolean android;
    private int page;   // Android: 0 letras, 1 símbolos (?123), 2 más símbolos (=\<)

    /** ¿Teclado tipo Android (letras grandes, como el de Google) en vez del de PC? */
    public boolean isAndroid() { return android; }

    public void setAndroid(boolean a) {
        android = a;
        page = 0;
        getContext().getSharedPreferences("mesa", Context.MODE_PRIVATE).edit().putBoolean("kbAndroid", a).apply();
        heldMods = 0; latchedMods = 0; caps = false; deadKey = null;
        build();
    }

    /** Botón de emojis de la barra de arriba (junto a "Teclado"): abre o cierra los emojis (pasa al teclado Android). */
    public void toggleEmoji() {
        if (!android) setAndroid(true);
        page = page == 3 ? 0 : 3;
        build();
    }

    public boolean emojiShown() { return android && page == 3; }

    // ---------------- fila de números y teclas cuadradas (teclado vertical) ----------------
    private boolean numberRow;
    /** Fila de números arriba de las letras (como el teclado de Google con "fila de números"). */
    public void setNumberRow(boolean on) { if (numberRow != on) { numberRow = on; build(); } }

    /** Alto para que las teclas de letras queden cuadradas con este ancho. */
    public int squareHeight(int w) {
        float gap = 4 * dp, pad = 6 * dp;
        float unit = (w - 2 * pad - 9 * gap) / 10f;
        int rows = numberRow ? 5 : 4;
        return Math.round(2 * pad + unit * (rows + 0.05f) + gap * (rows - 1));
    }

    // ---------------- emojis: todos, por categoría y por páginas (como el teclado de Google) ----------------
    private int emoCat, emoPage;
    private static String[][] emoCache;

    /** Emojis de cada categoría que este teléfono sabe dibujar. */
    private static synchronized String[][] emojis() {
        if (emoCache != null) return emoCache;
        Paint p = new Paint();
        String[][] out = new String[EmojiData.LISTS.length][];
        for (int i = 0; i < out.length; i++) {
            List<String> ok = new ArrayList<>();
            for (String e : EmojiData.LISTS[i].split(" ")) if (!e.isEmpty() && p.hasGlyph(e)) ok.add(e);
            out[i] = ok.toArray(new String[0]);
        }
        return emoCache = out;
    }

    private int emoCols() {
        int w = getWidth() > 0 ? getWidth() : getResources().getDisplayMetrics().widthPixels;
        return Math.max(7, Math.min(12, Math.round(w / (46 * dp))));
    }

    private static Key nop() { return new Key(ACT, "", null, null, 0, "nop", 1); }

    private void buildEmoji(Key del) {
        String[] list = emojis()[emoCat];
        int cols = emoCols(), grid = numberRow ? 4 : 3, per = cols * grid;
        int pages = Math.max(1, (list.length + per - 1) / per);
        emoPage = Math.max(0, Math.min(pages - 1, emoPage));
        for (int r = 0; r < grid; r++) {
            Key[] row = new Key[cols];
            for (int c = 0; c < cols; c++) {
                int i = emoPage * per + r * cols + c;
                if (i < list.length) { Key k = l(list[i]); k.emoji = true; row[c] = k; } else row[c] = nop();
            }
            rows.add(row);
        }
        // abajo: ABC, página anterior, categorías, página siguiente y borrar
        Key[] bottom = new Key[EmojiData.ICONS.length + 4];
        bottom[0] = page("ABC", "page:0", 1.3f);
        bottom[1] = page(emoPage > 0 ? "◀" : "", emoPage > 0 ? "emo:prev" : "nop", 0.9f);
        for (int i = 0; i < EmojiData.ICONS.length; i++) {
            Key k = page(EmojiData.ICONS[i], "emo:cat:" + i, 1f);
            k.emoji = true;
            bottom[i + 2] = k;
        }
        bottom[EmojiData.ICONS.length + 2] = page(emoPage < pages - 1 ? "▶" : "", emoPage < pages - 1 ? "emo:next" : "nop", 0.9f);
        bottom[EmojiData.ICONS.length + 3] = del;
        rows.add(bottom);
    }

    private static Key page(String l, String a, float u) { return new Key(ACT, l, null, null, 0, a, u); }

    private void build() {
        rows.clear();
        if (!android) buildPc(); else buildAndroid();
        if (getWidth() > 0) layoutKeys(getWidth(), getHeight());
        invalidate();
    }

    private void buildPc() {
        rowWeights = new float[]{0.72f, 1, 1, 1, 1, 1.18f};   // la fila del espacio, un poco más alta
        rows.add(new Key[]{
                key("Esc", KeyEvent.KEYCODE_ESCAPE, 1.5f), act("Sel. todo", "all"), act("Copiar", "copy"),
                act("Cortar", "cut"), act("Pegar", "paste"), act("Deshacer", "undo"),
                media("⏯", "play"), media("Vol −", "vdown"), media("Vol +", "vup"),
                key("Supr", KeyEvent.KEYCODE_FORWARD_DEL, 1.5f), page("Android", "layout:android", 1.5f)});
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
                mod("Ctrl", CTRL, 1.25f), mod("AltGr", ALTGR, 1.25f), c(" ", null, null, 10.5f),   // espacio ancho y alto (sin flechas)
                sendKey()});   // Enviar: esquina inferior derecha
    }

    private void buildAndroid() {
        rowWeights = numberRow ? new float[]{1, 1, 1, 1, 1.05f} : new float[]{1, 1, 1, 1.05f};
        Key del = key("⌫", KeyEvent.KEYCODE_DEL, 1.5f);
        if (page == 3) { buildEmoji(del); return; }
        if (numberRow) {   // fila de números (en símbolos: más símbolos, para que todas las páginas tengan las mismas filas)
            if (page == 1) rows.add(new Key[]{l("%"), l("^"), l("~"), l("|"), l("["), l("]"), l("<"), l(">"), l("{"), l("}")});
            else rows.add(new Key[]{l("1"), l("2"), l("3"), l("4"), l("5"), l("6"), l("7"), l("8"), l("9"), l("0")});
        }
        if (page == 0) {
            rows.add(new Key[]{l("q"), l("w"), l("e"), l("r"), l("t"), l("y"), l("u"), l("i"), l("o"), l("p")});
            rows.add(new Key[]{l("a"), l("s"), l("d"), l("f"), l("g"), l("h"), l("j"), l("k"), l("l"), l("ñ")});
            rows.add(new Key[]{mod("⇧", SHIFT, 1.5f), l("z"), l("x"), l("c"), l("v"), l("b"), l("n"), l("m"), del});
        } else if (page == 1) {
            rows.add(new Key[]{l("1"), l("2"), l("3"), l("4"), l("5"), l("6"), l("7"), l("8"), l("9"), l("0")});
            rows.add(new Key[]{l("@"), l("#"), l("$"), l("_"), l("&"), l("-"), l("+"), l("("), l(")"), l("/")});
            rows.add(new Key[]{page("=\\<", "page:2", 1.5f), l("*"), l("\""), l("'"), l(":"), l(";"), l("!"), l("?"), del});
        } else {
            rows.add(new Key[]{l("~"), l("`"), l("|"), l("•"), l("√"), l("π"), l("÷"), l("×"), l("¶"), l("∆")});
            rows.add(new Key[]{l("£"), l("€"), l("¥"), l("^"), l("°"), l("="), l("{"), l("}"), l("\\"), l("%")});
            rows.add(new Key[]{page("?123", "page:1", 1.5f), l("¿"), l("¡"), l("["), l("]"), l("<"), l(">"), l("´"), del});
        }
        rows.add(new Key[]{page(page == 0 ? "?123" : "ABC", page == 0 ? "page:1" : "page:0", 1.3f), page("PC", "layout:pc", 1f),
                l(","), c(" ", null, null, 5.5f), l("."), sendKey()});   // sin "saltar línea": el punto en su lugar y el espacio más ancho
    }

    public void setSink(Sink s) { sink = s; }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        if (android && page == 3 && w != ow) { build(); return; }   // emojis: las columnas dependen del ancho
        layoutKeys(w, h);
    }

    private void layoutKeys(int w, int h) {
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
            if (k.type == ACT && "nop".equals(k.act)) continue;   // hueco
            boolean active = k.down || (k.type == MOD && k.code != 0 && (mods & k.code) != 0) || (android && k.type == MOD && k.code == SHIFT && caps)
                    || (k.type == MOD && k.code == 0 && caps) || (k.type == CHAR && k.label.equals(deadKey));
            if (k.type == ACT && k.act != null && k.act.equals("emo:cat:" + emoCat) && page == 3) active = true;
            boolean special = !(k.type == CHAR);
            boolean send = k.type == ACT && "send".equals(k.act);
            boolean layout = k.type == ACT && k.act != null && k.act.startsWith("layout:");   // PC / Android: verde agua
            keyBg.setColor(layout ? 0xFF2EC4B6 : active || send ? accent : special ? keyDark : keyColor);
            float r = 7 * dp;
            cv.drawRoundRect(k.r, r, r, keyBg);
            if (keyStroke.getColor() != 0 && !(active || send)) cv.drawRoundRect(k.r, r, r, keyStroke);   // cristal: borde de luz
            String label = k.label;
            if (isLetter(k) && (shift ^ caps)) label = label.toUpperCase();
            boolean space = k.type == CHAR && " ".equals(k.label);
            if (space) label = deadKey != null ? deadKey + " …" : "espacio";
            float size = Math.min(k.r.height() * (label.length() == 1 ? 0.42f : 0.28f), 26 * dp);
            if (label.length() > 1) size = Math.min(size, k.r.width() / (label.length() * 0.6f));
            if (k.emoji) size = Math.min(k.r.height(), k.r.width()) * 0.55f;   // un emoji es un solo dibujo
            main.setTextSize(size);
            main.setColor(layout ? 0xFFFFFFFF : active || send ? bg : space ? muted : fg);
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

    private long lastShiftAt;
    private boolean skipLatch;
    private Key longKey;
    private boolean longFired;
    private final Runnable longPress = () -> {
        Key k = longKey;
        if (k == null || !k.down || sink == null) return;
        String acc = accent(k.label);
        if (acc == null) return;
        longFired = true;
        boolean up = ((heldMods | latchedMods) & SHIFT) != 0 ^ caps;
        sink.text(up ? acc.toUpperCase() : acc);
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
    };

    /** Android: dejar apretada una vocal escribe la vocal con tilde. */
    private static String accent(String l) {
        switch (l) {
            case "a": return "á"; case "e": return "é"; case "i": return "í"; case "o": return "ó"; case "u": return "ú";
            case "n": return "ñ"; case ".": return "…"; case ",": return ";"; case "?": return "¿"; case "!": return "¡";
            default: return null;
        }
    }

    private void press(Key k) {
        k.down = true;
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        if (k.type == ACT && k.act != null && (k.act.startsWith("page:") || k.act.startsWith("layout:") || k.act.startsWith("emo:") || k.act.equals("nop"))) return;   // al soltar
        if (k.type == MOD) {
            if (android && k.code == SHIFT) {
                // Android: un toque = la siguiente letra en mayúscula; dos toques seguidos = bloq mayús; otro toque lo quita
                long now = System.nanoTime() / 1_000_000L;
                skipLatch = false;
                if (caps) { caps = false; skipLatch = true; }
                else if (now - lastShiftAt < 350) { caps = true; latchedMods &= ~SHIFT; skipLatch = true; }
                lastShiftAt = now;
                if (!skipLatch) { heldMods |= k.code; usedWhileHeld &= ~k.code; }
            } else if (k.code == 0) caps = !caps;
            else { heldMods |= k.code; usedWhileHeld &= ~k.code; }
        } else if (android && k.type == CHAR) {
            // Android: la letra se escribe al soltar (dejarla apretada da la variante con tilde)
            usedWhileHeld |= heldMods;
            longKey = k; longFired = false;
            repeat.removeCallbacks(longPress);
            if (accent(k.label) != null) repeat.postDelayed(longPress, 420);
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
        if (k.type == ACT && "nop".equals(k.act)) { invalidate(); return; }
        if (k.type == ACT && k.act != null && k.act.startsWith("emo:")) {
            if (k.act.startsWith("emo:cat:")) { emoCat = Integer.parseInt(k.act.substring(8)); emoPage = 0; }
            else if (k.act.equals("emo:next")) emoPage++;
            else if (k.act.equals("emo:prev")) emoPage--;
            build();
            return;
        }
        if (k.type == ACT && k.act != null && k.act.startsWith("page:")) { page = Integer.parseInt(k.act.substring(5)); build(); return; }
        if (k.type == ACT && k.act != null && k.act.startsWith("layout:")) { setAndroid("layout:android".equals(k.act)); return; }
        if (android && k.type == CHAR) {
            repeat.removeCallbacks(longPress);
            if (!(k == longKey && longFired)) fire(k);
            longKey = null;
        }
        if (android && k.type == MOD && k.code == SHIFT && skipLatch) { skipLatch = false; invalidate(); return; }
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
