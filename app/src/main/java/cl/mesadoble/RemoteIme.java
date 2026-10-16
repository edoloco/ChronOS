package cl.mesadoble;

import android.content.res.Configuration;
import android.graphics.drawable.GradientDrawable;
import android.inputmethodservice.InputMethodService;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

/**
 * Teclado "ChronOS" del teléfono que RECIBE: en vez de un teclado grande muestra
 * una barra delgada y escribe en la app abierta lo que llega desde el otro teléfono.
 */
public class RemoteIme extends InputMethodService implements Link.Listener {

    private Hub hub;
    private TextView label;
    private LinearLayout fixRow;
    private android.widget.HorizontalScrollView fixScroll;
    private long lastEditAt;
    private static boolean userSent;
    private boolean night;

    @Override public void onCreate() {
        super.onCreate();
        hub = Hub.get(this);
        hub.ensureStarted();
        hub.add(this);
    }

    @Override public void onDestroy() {
        hub.remove(this);
        hub.setImeReady(false);
        super.onDestroy();
    }

    @Override public boolean onEvaluateFullscreenMode() { return false; }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    @Override public View onCreateInputView() {
        night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12), dp(4), dp(6), dp(4));
        bar.setBackgroundColor(night ? 0xFF18212B : 0xFFDDE3E8);
        label = new TextView(this);
        label.setTextSize(13);
        label.setSingleLine(true);
        bar.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        // palabra tocada: las sugerencias del corrector aparecen aquí como botones
        fixScroll = new android.widget.HorizontalScrollView(this);
        fixScroll.setHorizontalScrollBarEnabled(false);
        fixRow = new LinearLayout(this);
        fixRow.setGravity(Gravity.CENTER_VERTICAL);
        fixScroll.addView(fixRow);
        fixScroll.setVisibility(View.GONE);
        bar.addView(fixScroll, new LinearLayout.LayoutParams(0, -1, 1));
        Button sw = new Button(this);
        sw.setText("Cambiar teclado");
        sw.setAllCaps(false);
        sw.setTextSize(13);
        sw.setMinWidth(0); sw.setMinimumWidth(0); sw.setMinHeight(0); sw.setMinimumHeight(0);
        sw.setPadding(dp(12), 0, dp(12), 0);
        sw.setTextColor(night ? 0xFFE6EDF3 : 0xFF1D2733);
        GradientDrawable g = new GradientDrawable();
        g.setColor(night ? 0xFF2A3848 : 0xFFFFFFFF);
        g.setCornerRadius(dp(10));
        sw.setBackground(g);
        sw.setStateListAnimator(null);
        sw.setOnClickListener(v -> {
            if (!switchToPreviousInputMethod()) {
                InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) imm.showInputMethodPicker();
            }
        });
        bar.addView(sw, new LinearLayout.LayoutParams(-2, dp(34)));
        LinearLayout wrap = new LinearLayout(this);
        wrap.addView(bar, new LinearLayout.LayoutParams(-1, dp(46)));
        updateLabel();
        return wrap;
    }

    @Override public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        hub.setImeReady(true);
        updateLabel();
        hideFix();
        // el diccionario personal del teléfono (solo el teclado activo puede leerlo) va al extendido una vez
        if (!userSent && hub.connected() && hub.isPrincipal()) {
            userSent = true;
            new Thread(() -> hub.send("userDict", "w", Speller.userWords(this))).start();
        }
    }

    // ---------------- palabra tocada: sugerencias de ortografía ----------------

    @Override public void onUpdateSelection(int os, int oe, int ns, int ne, int cs, int ce) {
        super.onUpdateSelection(os, oe, ns, ne, cs, ce);
        if (ns != ne || SystemClock.uptimeMillis() - lastEditAt < 600) return;   // escribiendo: no
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        CharSequence b = ic.getTextBeforeCursor(48, 0), a = ic.getTextAfterCursor(48, 0);
        if (b == null) b = "";
        if (a == null) a = "";
        int i = b.length();
        while (i > 0 && Character.isLetter(b.charAt(i - 1))) i--;
        int j = 0;
        while (j < a.length() && Character.isLetter(a.charAt(j))) j++;
        final String w = b.subSequence(i, b.length()).toString() + a.subSequence(0, j);
        final int nb = b.length() - i, na = j;
        if (w.length() < 2) { hideFix(); return; }
        Speller.check(this, w, list -> {
            java.util.List<String> all = new java.util.ArrayList<>();
            for (String x : list) if (!x.equals(w) && !all.contains(x)) all.add(x);
            for (String x : Dict.get(this).suggest(w, 3)) if (!x.equals(w) && !all.contains(x) && all.size() < 5) all.add(x);
            showFix(w, all, nb, na);
            if (hub.connected()) hub.send("wordFix", "w", w, "s", new org.json.JSONArray(all), "b", nb, "a", na);
        });
    }

    private void showFix(String w, java.util.List<String> s, int nb, int na) {
        if (fixRow == null) return;
        fixRow.removeAllViews();
        if (s.isEmpty()) { hideFix(); return; }
        for (String v : s) {
            TextView c = new TextView(this);
            c.setText(v);
            c.setTextSize(14);
            c.setTextColor(night ? 0xFFE6EDF3 : 0xFF1D2733);
            c.setPadding(dp(12), 0, dp(12), 0);
            c.setGravity(Gravity.CENTER);
            GradientDrawable g = new GradientDrawable();
            g.setColor(night ? 0xFF2A3848 : 0xFFFFFFFF);
            g.setCornerRadius(dp(10));
            g.setStroke(dp(1), night ? 0xFF5CC3CF : 0xFF1F7F8C);
            c.setBackground(g);
            c.setOnClickListener(v2 -> { replace(nb, na, v); hideFix(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(32));
            lp.setMarginEnd(dp(6));
            fixRow.addView(c, lp);
        }
        label.setVisibility(View.GONE);
        fixScroll.setVisibility(View.VISIBLE);
    }

    private void hideFix() {
        if (fixScroll == null) return;
        fixScroll.setVisibility(View.GONE);
        label.setVisibility(View.VISIBLE);
    }

    /** Cambia la palabra donde está el cursor (nb letras antes, na después) por otra. */
    private void replace(int nb, int na, String s) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        lastEditAt = SystemClock.uptimeMillis();
        ic.beginBatchEdit();
        ic.deleteSurroundingText(nb, na);
        ic.commitText(s, 1);
        ic.endBatchEdit();
    }

    @Override public void onFinishInputView(boolean finishing) {
        hub.setImeReady(false);
        super.onFinishInputView(finishing);
    }

    private void updateLabel() {
        if (label == null) return;
        boolean c = hub.connected();
        label.setText(c ? "● Escribiendo desde el otro teléfono" : "Esperando al otro teléfono…");
        label.setTextColor(c ? (night ? 0xFF5CC3CF : 0xFF1F7F8C) : (night ? 0xFF93A4B5 : 0xFF5B6B7A));
    }

    // ---------------- mensajes ----------------

    @Override public void onState(int state, String info) { updateLabel(); }

    /**
     * "Enviar": usa la acción que la app definió para su campo (enviar, buscar, ir...).
     * Si el campo no define ninguna (por ejemplo, WhatsApp sin "Enter para enviar"), prueba "enviar" y si no, Enter.
     */
    private void send(InputConnection ic) {
        EditorInfo ei = getCurrentInputEditorInfo();
        int act = ei != null ? ei.imeOptions & EditorInfo.IME_MASK_ACTION : EditorInfo.IME_ACTION_UNSPECIFIED;
        boolean noAction = ei != null && (ei.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0;
        if (act != EditorInfo.IME_ACTION_NONE && act != EditorInfo.IME_ACTION_UNSPECIFIED && !noAction) {
            ic.performEditorAction(act);
        } else if (!ic.performEditorAction(EditorInfo.IME_ACTION_SEND)) {
            sendKeyChar('\n');
        }
    }

    @Override public void onMessage(JSONObject m) {
        if (!"kb".equals(m.optString("t"))) return;
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        lastEditAt = SystemClock.uptimeMillis();
        hideFix();
        switch (m.optString("a")) {
            case "replace":   // sugerencia elegida en el extendido para la palabra tocada
                replace(m.optInt("b"), m.optInt("af"), m.optString("s"));
                break;
            case "text":
                ic.commitText(m.optString("s"), 1);
                break;
            case "key": {
                int code = m.optInt("k"), meta = m.optInt("m"), n = Math.max(1, Math.min(500, m.optInt("n", 1)));
                ic.beginBatchEdit();
                for (int i = 0; i < n; i++) {
                    if (code == KeyEvent.KEYCODE_ENTER && meta == 0) sendKeyChar('\n');
                    else if (meta == 0) sendDownUpKeyEvents(code);
                    else {
                        long t = SystemClock.uptimeMillis();
                        ic.sendKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0, meta));
                        ic.sendKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0, meta));
                    }
                }
                ic.endBatchEdit();
                break;
            }
            case "act": {
                if ("send".equals(m.optString("x"))) { send(ic); break; }
                int id = 0;
                switch (m.optString("x")) {
                    case "copy": id = android.R.id.copy; break;
                    case "cut": id = android.R.id.cut; break;
                    case "paste": id = android.R.id.paste; break;
                    case "all": id = android.R.id.selectAll; break;
                    case "undo": id = android.R.id.undo; break;
                }
                if (id != 0) ic.performContextMenuAction(id);
                break;
            }
        }
    }
}
