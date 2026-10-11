package cl.mesadoble;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

/** Pantalla del teléfono que ESCRIBE en el otro, usando la misma conexión WiFi de la mesa. */
public class KeyboardActivity extends Activity implements Link.Listener, KeyboardView.Sink {

    private static final String[] MODES = {"Teclado grande", "Teclado del teléfono / dictado"};

    private boolean night;
    private SharedPreferences prefs;
    private Hub hub;
    private KeyboardView keyboard;
    private EditText field;
    private LinearLayout content;
    private TextView status;
    private Button modeBtn;
    private int mode;

    // modo "teclado del teléfono": lo escrito se refleja en el otro teléfono
    private String last = "";
    private boolean resetting;

    @Override protected void onCreate(Bundle b) {
        night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        setTheme(night ? android.R.style.Theme_Material_NoActionBar : android.R.style.Theme_Material_Light_NoActionBar);
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("mesa", MODE_PRIVATE);
        mode = Math.min(1, Math.max(0, prefs.getInt("kbMode2", 0)));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(night ? 0xFF121920 : 0xFFCDD5DC);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(10), dp(4), dp(4), 0);
        status = new TextView(this);
        status.setTextSize(13);
        status.setSingleLine(true);
        status.setOnClickListener(v -> help());
        top.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
        Button helpBtn = small("Ayuda");
        helpBtn.setOnClickListener(v -> help());
        modeBtn = small(mode == 0 ? "Teclado" : "Dictado");
        modeBtn.setOnClickListener(v -> pickMode());
        Button close = small("Cerrar");
        close.setOnClickListener(v -> finish());
        top.addView(helpBtn); top.addView(modeBtn); top.addView(close);
        root.addView(top, new LinearLayout.LayoutParams(-1, dp(40)));

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);

        keyboard = new KeyboardView(this, night);
        keyboard.setSink(this);
        field = new EditText(this);
        field.setHint("Escribe o dicta aquí: aparece en el otro teléfono");
        field.setTextSize(20);
        field.setGravity(Gravity.TOP);
        field.setPadding(dp(16), dp(14), dp(16), dp(14));
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_AUTO_CORRECT);
        field.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN);
        GradientDrawable fb = new GradientDrawable();
        fb.setColor(night ? 0xFF1E2934 : 0xFFFFFFFF);
        fb.setCornerRadius(dp(12));
        field.setBackground(fb);
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b2, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b2, int c) { }
            @Override public void afterTextChanged(Editable e) { mirror(e); }
        });
        arrange();

        hub = Hub.get(this);
        hub.ensureStarted();
        LinkService.start(this);
    }

    private boolean auto;
    private final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable autoClose = () -> { if (auto && !hub.remoteImeReady()) finish(); };

    @Override protected void onNewIntent(android.content.Intent i) {
        super.onNewIntent(i);
        auto = i.getBooleanExtra("auto", false);
    }

    @Override protected void onResume() {
        super.onResume();
        auto = auto || getIntent().getBooleanExtra("auto", false);
        hub.keyboardVisible = true;
        hub.add(this);
        hub.send("kbAsk");
    }

    @Override protected void onPause() {
        hub.keyboardVisible = false;
        h.removeCallbacks(autoClose);
        hub.remove(this);
        super.onPause();
    }

    @Override public void onWindowFocusChanged(boolean f) {
        super.onWindowFocusChanged(f);
        if (f && mode == 0) immersive();
    }

    @SuppressWarnings("deprecation")
    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    @SuppressWarnings("deprecation")
    private void arrange() {
        content.removeAllViews();
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (mode == 0) {
            if (imm != null) imm.hideSoftInputFromWindow(field.getWindowToken(), 0);
            getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
            content.addView(keyboard, new LinearLayout.LayoutParams(-1, -1));
            immersive();
        } else {
            getWindow().getDecorView().setSystemUiVisibility(0);
            getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -1);
            lp.setMargins(dp(10), dp(8), dp(10), dp(10));
            content.addView(field, lp);
            field.requestFocus();
            field.post(() -> { if (imm != null) imm.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT); });
        }
    }

    private void pickMode() {
        new AlertDialog.Builder(this)
                .setTitle("¿Cómo quieres escribir?")
                .setSingleChoiceItems(MODES, mode, (d, w) -> {
                    mode = w;
                    prefs.edit().putInt("kbMode2", w).apply();
                    modeBtn.setText(w == 0 ? "Teclado" : "Dictado");
                    arrange();
                    d.dismiss();
                })
                .show();
    }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private Button small(String t) {
        Button b = new Button(this);
        b.setText(t);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setMinWidth(0); b.setMinimumWidth(0); b.setMinHeight(0); b.setMinimumHeight(0);
        b.setPadding(dp(12), 0, dp(12), 0);
        b.setTextColor(night ? 0xFFE6EDF3 : 0xFF1D2733);
        GradientDrawable g = new GradientDrawable();
        g.setColor(night ? 0xFF2A3848 : 0xFFFFFFFF);
        g.setCornerRadius(dp(10));
        b.setBackground(g);
        b.setStateListAnimator(null);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(32));
        lp.setMarginStart(dp(4));
        b.setLayoutParams(lp);
        return b;
    }

    private void help() {
        new AlertDialog.Builder(this)
                .setTitle("Escribir en el otro teléfono")
                .setMessage("En el OTRO teléfono, una sola vez:\n"
                        + "1. Abre Mesa Doble, toca \"Teclado\" y luego \"Este teléfono recibe\".\n"
                        + "2. Activa el teclado \"Mesa Doble\" y elígelo.\n\n"
                        + "Desde ahí, al tocar un campo de texto (WhatsApp, Chrome, etc.) aparece una barra delgada "
                        + "en vez del teclado, y todo lo que escribas aquí se escribe allá.\n\n"
                        + "Para volver al teclado normal en ese teléfono, toca \"Cambiar teclado\" en la barra.")
                .setPositiveButton("Entendido", null)
                .show();
    }

    // ---------------- estado ----------------

    private void updateStatus() {
        String t;
        boolean ok = false;
        if (!hub.connected()) {
            t = "Sin conexión con el otro teléfono. Abre Mesa Doble en ambos";
        } else if (!hub.remoteImeSelected()) {
            t = "En el otro teléfono toca el aviso \"quiere escribir aquí\" para elegir el teclado Mesa Doble";
        } else if (!hub.remoteImeReady()) {
            t = "Toca un campo de texto en el otro teléfono";
        } else {
            t = "● Escribiendo en el otro teléfono" + (hub.acksOk > 0 ? "  ✓" + hub.acksOk : "");
            ok = true;
        }
        status.setText(t);
        status.setTextColor(ok ? (night ? 0xFF5CC3CF : 0xFF1F7F8C) : (night ? 0xFFF0A26B : 0xFFB4541A));
    }

    @Override public void onState(int state, String info) {
        if (state == Link.CONNECTED) hub.send("kbAsk");
        updateStatus();
    }

    @Override public void onMessage(JSONObject m) {
        String t = m.optString("t");
        if ("kbState".equals(t) || "kbAck".equals(t)) updateStatus();
        // abierto solo (al tocar un campo en el principal): se cierra solo cuando ya no hay dónde escribir
        if ("kbState".equals(t) && auto) {
            h.removeCallbacks(autoClose);
            if (!hub.remoteImeReady()) h.postDelayed(autoClose, 3000);
        }
    }

    // ---------------- envío ----------------

    @Override public void text(String s) { hub.send("kb", "a", "text", "s", s); }
    @Override public void key(int code, int meta) { hub.send("kb", "a", "key", "k", code, "m", meta); }
    @Override public void action(String what) { hub.send("kb", "a", "act", "x", what); }
    @Override public void media(String what) { hub.send("media", "k", what); }

    /** Refleja en el otro teléfono los cambios del campo de texto (incluye autocorrección y dictado). */
    private void mirror(Editable e) {
        if (resetting) return;
        String now = e.toString();
        int p = 0;
        while (p < last.length() && p < now.length() && last.charAt(p) == now.charAt(p)) p++;
        int del = last.codePointCount(p, last.length());
        String add = now.substring(p);
        if (del > 0) hub.send("kb", "a", "key", "k", KeyEvent.KEYCODE_DEL, "m", 0, "n", del);
        boolean enter = add.indexOf('\n') >= 0;
        String[] parts = add.split("\n", -1);
        for (int i = 0; i < parts.length; i++) {
            if (!parts[i].isEmpty()) text(parts[i]);
            if (i < parts.length - 1) key(KeyEvent.KEYCODE_ENTER, 0);
        }
        last = now;
        if (enter || now.length() > 400) {
            // vacía el campo sin borrar nada en el otro teléfono
            resetting = true;
            e.clear();
            resetting = false;
            last = "";
        }
    }
}
