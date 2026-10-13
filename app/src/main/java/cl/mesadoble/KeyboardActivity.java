package cl.mesadoble;

import android.app.Activity;
import android.content.Intent;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.List;

/**
 * Pantalla del teléfono que controla al principal, por la misma conexión WiFi de la mesa.
 * Modos: teclado, teclado + touchpad, touchpad, gamepad, dictado y control de video.
 */
public class KeyboardActivity extends Activity implements Link.Listener, KeyboardView.Sink {

    static final int KB = 0, KB_PAD = 1, PAD = 2, GAME = 3, DICT = 4, MEDIA = 5, CAMERA = 6;
    private static final String[] NAMES = {"Teclado", "Teclado + touchpad", "Touchpad", "Gamepad", "Dictado", "Control de video", "Cámara"};
    /** ¿Está a la vista el control de video? (para no abrirlo de nuevo) */
    static boolean showingMedia, showingCamera;

    private boolean night;
    private KbTheme th;
    private String themeSig = "";
    private SharedPreferences prefs;
    private Hub hub;
    private KeyboardView keyboard;
    private TouchpadView touchpad;
    private GamepadView gamepad;
    private MediaControlView mediaView;
    private CameraControlView cameraView;
    private EditText field;
    private LinearLayout content, suggestBar;
    private TextView status, modeBtn, editBtn, resetBtn, sendBtn, outBtn, pairBtn;
    private BtPad bt;
    private String btStatus = "";
    private boolean btOk;
    private static final String P_CONNECT = "android.permission.BLUETOOTH_CONNECT", P_ADVERTISE = "android.permission.BLUETOOTH_ADVERTISE";
    private int mode;

    // sugerencias: palabra que se está escribiendo
    private final StringBuilder word = new StringBuilder();
    private Dict dict;

    // dictado: lo escrito se refleja en el otro teléfono
    private String last = "";
    private boolean resetting;

    @Override protected void onCreate(Bundle b) {
        night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        setTheme(night ? android.R.style.Theme_Material_NoActionBar : android.R.style.Theme_Material_Light_NoActionBar);
        super.onCreate(b);
        // El teclado es para el EXTENDIDO. En el principal solo se abre si se pidió a propósito (Teclado → Usar este teléfono
        // como teclado); si Android lo reabre solo (al volver a la app o tras cerrarla), se va a la mesa.
        if (Hub.get(this).isPrincipal() && !getIntent().getBooleanExtra("manual", false)) {
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
            finish();
            return;
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        MainActivity.applyOrientation(this);   // misma orientación que la mesa
        prefs = getSharedPreferences("mesa", MODE_PRIVATE);
        mode = Math.min(CAMERA, Math.max(KB, prefs.getInt("kbMode3", KB)));
        mode = startMode(getIntent(), mode);
        dict = Dict.get(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        th = KbTheme.of(this, night);
        themeSig = KbTheme.sig(this);
        root.setBackgroundColor(th.bg);

        // barra superior: estado a la izquierda, botones de navegación SIEMPRE al centro, opciones a la derecha
        LinearLayout left = new LinearLayout(this), navRow = new LinearLayout(this), top = new LinearLayout(this);
        left.setGravity(Gravity.CENTER_VERTICAL);
        navRow.setGravity(Gravity.CENTER);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        CenterBar bar3 = new CenterBar(this, left, navRow, top);
        bar3.setPadding(dp(12), dp(4), dp(6), dp(4));
        status = new TextView(this);
        status.setTextSize(13);
        status.setSingleLine(true);
        status.setEllipsize(TextUtils.TruncateAt.END);
        status.setOnClickListener(v -> help());
        left.addView(status, new LinearLayout.LayoutParams(-1, -2));
        // Atrás, Inicio y Recientes del PRINCIPAL, junto al indicador "Escribiendo en el principal"
        int[] kinds = {SquareButton.BACK, SquareButton.HOME, SquareButton.RECENTS};
        String[] labels = {"Atrás", "Inicio", "Recientes"}, keys = {"back", "home", "recents"};
        int navCount = Updater.isLiviana(this) ? 2 : 3;   // Recientes solo funciona con la versión completa
        for (int i = 0; i < navCount; i++) {
            final String k = keys[i];
            SquareButton sb = new SquareButton(this, kinds[i], labels[i], false, night);
            sb.setCompact(true);
            sb.setOnClickListener(v -> hub.send("nav", "k", k));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMargins(dp(4), 0, dp(4), 0);
            navRow.addView(sb, lp);
        }
        editBtn = chip("Editar", v -> toggleEdit());
        resetBtn = chip("Restablecer", v -> { gamepad.resetLayout(); prefs.edit().remove("padLayout").apply(); });
        modeBtn = chip(NAMES[mode], v -> pickMode());
        sendBtn = chip("Enviar ➤", v -> { hub.send("kb", "a", "act", "x", "send"); });
        outBtn = chip("", v -> toggleOutput());
        pairBtn = chip("Vincular", v -> pairBluetooth());
        top.addView(sendBtn); top.addView(pairBtn); top.addView(outBtn); top.addView(resetBtn); top.addView(editBtn); top.addView(modeBtn);
        top.addView(chip("Cerrar", v -> close()));
        root.addView(bar3, new LinearLayout.LayoutParams(-1, dp(44)));

        content = new LinearLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));

        setContentView(root);

        hub = Hub.get(this);
        hub.ensureStarted();
        LinkService.start(this);
        buildViews();
        arrange();
    }

    private void buildViews() {
        keyboard = new KeyboardView(this, night);
        keyboard.setSink(this);

        suggestBar = new LinearLayout(this);
        suggestBar.setOrientation(LinearLayout.HORIZONTAL);
        suggestBar.setPadding(dp(6), dp(4), dp(6), 0);
        for (int i = 0; i < 3; i++) {
            TextView t = new TextView(this);
            t.setGravity(Gravity.CENTER);
            t.setTextSize(16);
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            t.setTextColor(th.fg);
            t.setTypeface(Typeface.create("sans-serif", i == 0 ? Typeface.BOLD : Typeface.NORMAL));
            GradientDrawable g = new GradientDrawable();
            g.setColor(th.keyDark);
            g.setCornerRadius(dp(12));
            t.setBackground(g);
            t.setOnClickListener(v -> pickSuggestion(((TextView) v).getText().toString()));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1);
            lp.setMargins(dp(3), 0, dp(3), 0);
            suggestBar.addView(t, lp);
        }

        touchpad = new TouchpadView(this, night);
        touchpad.setSink(new TouchpadView.Sink() {
            // Mouse Bluetooth (puntero real en el principal) o por WiFi (necesita la versión completa)
            @Override public void move(int dx, int dy) { if (useBt()) bt.mouse(dx, dy, 0); else hub.send("mouse", "dx", dx, "dy", dy); }
            @Override public void click() { if (useBt()) bt.click(1); else hub.send("click"); }
            @Override public void scroll(int steps) { if (useBt()) bt.mouse(0, 0, steps); else hub.send("scroll", "d", steps); }
            @Override public void nav(String what) { hub.send("nav", "k", what); }
        });

        gamepad = new GamepadView(this, night);
        gamepad.fromJson(prefs.getString("padLayout", ""));
        gamepad.setSink(new GamepadView.Sink() {
            @Override public void button(String id, boolean down, float x, float y) {
                if (useBt()) bt.button(id, down);   // control de juego real: lo reconocen los emuladores
                else hub.send("pad", "a", "btn", "id", id, "down", down, "x", (double) x, "y", (double) y);
            }
            @Override public void joy(String id, int phase, float cx, float cy, float r, float nx, float ny) {
                if (useBt()) bt.joy(id, nx, ny, phase == 2);
                else hub.send("pad", "a", "joy", "id", id, "ph", phase, "x", (double) cx, "y", (double) cy,
                        "r", (double) r, "nx", (double) nx, "ny", (double) ny);
            }
            @Override public void layoutChanged(String json) { prefs.edit().putString("padLayout", json).apply(); }
        });

        mediaView = new MediaControlView(this, Hub.get(this));
        cameraView = new CameraControlView(this, Hub.get(this));

        field = new EditText(this);
        field.setHint("Escribe o dicta aquí: aparece en el principal");
        field.setTextSize(20);
        field.setGravity(Gravity.TOP);
        field.setPadding(dp(16), dp(14), dp(16), dp(14));
        field.setTextColor(th.fg);
        field.setHintTextColor(th.muted);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_AUTO_CORRECT);
        field.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN);
        GradientDrawable fb = new GradientDrawable();
        fb.setColor(th.key);
        fb.setCornerRadius(dp(16));
        field.setBackground(fb);
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b2, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b2, int c) { }
            @Override public void afterTextChanged(Editable e) { mirror(e); }
        });
    }

    // ---------------- ciclo de vida ----------------

    private boolean auto, resumed;
    private final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable autoClose = () -> { if (auto && !hub.remoteImeReady()) close(); };

    /** Cerrar el teclado vuelve a la mesa (no saca de la app, aunque el teclado se haya abierto solo). */
    private void close() {
        if (isTaskRoot()) startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        finish();
    }

    @Override protected void onNewIntent(android.content.Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        auto = i.getBooleanExtra("auto", false);
        int m = startMode(i, mode);
        if (m != mode) { mode = m; arrange(); }
    }

    /** Modo pedido al abrir: el control de video (al empezar un video) o, si se abrió para escribir, un modo de teclado. */
    private static int startMode(Intent i, int current) {
        int m = i.getIntExtra("mode", -1);
        if (m >= KB && m <= CAMERA) return m;
        if (i.getBooleanExtra("auto", false) && current >= MEDIA) return KB;
        return current;
    }

    @Override protected void onResume() {
        super.onResume();
        auto = auto || getIntent().getBooleanExtra("auto", false);
        resumed = true;
        if (!themeSig.equals(KbTheme.sig(this))) { recreate(); return; }   // cambió el tema: se vuelve a pintar
        hub.keyboardVisible = mode < MEDIA;
        showingMedia = mode == MEDIA;
        showingCamera = mode == CAMERA;
        hub.add(this);
        hub.send("kbAsk");
    }

    // ---------------- Bluetooth (gamepad y mouse) ----------------

    /** ¿El modo actual usa Bluetooth? Gamepad y touchpad por defecto sí: es lo que reconocen emuladores y Android. */
    private boolean btMode() {
        if (mode == GAME) return prefs.getBoolean("padBt", true);
        if (mode == PAD || mode == KB_PAD) return prefs.getBoolean("mouseBt", true);
        return false;
    }

    private boolean useBt() { return btMode() && bt != null && bt.connected(); }

    private String[] btPerms() {
        return android.os.Build.VERSION.SDK_INT >= 31 ? new String[]{P_CONNECT, P_ADVERTISE} : new String[0];
    }

    private boolean hasBtPerms() {
        for (String p : btPerms()) if (checkSelfPermission(p) != android.content.pm.PackageManager.PERMISSION_GRANTED) return false;
        return true;
    }

    /** Enciende el Bluetooth solo en los modos que lo necesitan; lo apaga en los demás (no molesta al WiFi). */
    private void syncBluetooth() {
        if (btMode()) {
            if (!hasBtPerms()) { requestPermissions(btPerms(), 21); return; }
            if (bt == null) bt = BtPad.get(this);
            bt.setListener((t, c) -> { btStatus = t; btOk = c; updateStatus(); });
            android.bluetooth.BluetoothAdapter a = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
            if (a != null && !a.isEnabled()) {
                try { startActivityForResult(new android.content.Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE), 22); } catch (Exception ignored) { }
                return;
            }
            bt.start();
        } else if (bt != null) {
            bt.stop();
            bt.setListener(null);
        }
    }

    @Override public void onRequestPermissionsResult(int req, String[] p, int[] r) {
        if (req == 21 && hasBtPerms()) syncBluetooth();
        else if (req == 21) { btStatus = "Sin permiso de Bluetooth: usa la salida por WiFi"; updateStatus(); }
    }

    @Override protected void onActivityResult(int req, int res, android.content.Intent data) {
        super.onActivityResult(req, res, data);
        if (req == 22 && res == RESULT_OK) syncBluetooth();
        if (req == 23 && res != RESULT_CANCELED) {
            new Ui.Sheet(this)
                    .title("Vincula desde el principal")
                    .message("En el PRINCIPAL abre Ajustes → Bluetooth → Vincular nuevo dispositivo y elige este teléfono. "
                            + "Cuando termine vuelve aquí: el control se conecta solo.\n\n"
                            + "En el emulador (por ejemplo Nostalgia.NES → Ajustes → Controles) asigna los botones si no los toma solos.")
                    .button("Entendido", true, null)
                    .show();
        }
    }

    private void pairBluetooth() {
        android.content.Intent i = new android.content.Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
        i.putExtra(android.bluetooth.BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120);
        try { startActivityForResult(i, 23); } catch (Exception e) { android.widget.Toast.makeText(this, "No pude hacer visible este teléfono", android.widget.Toast.LENGTH_SHORT).show(); }
    }

    private void toggleOutput() {
        String key = mode == GAME ? "padBt" : "mouseBt";
        prefs.edit().putBoolean(key, !prefs.getBoolean(key, true)).apply();
        arrange();
    }

    @Override protected void onDestroy() {
        if (bt != null) { bt.stop(); bt.setListener(null); }
        super.onDestroy();
    }

    @Override protected void onPause() {
        resumed = false;
        hub.keyboardVisible = false;
        showingMedia = false;
        showingCamera = false;
        h.removeCallbacks(autoClose);
        hub.remove(this);
        super.onPause();
    }

    @Override public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        arrange();
    }

    @Override public void onWindowFocusChanged(boolean f) {
        super.onWindowFocusChanged(f);
        if (f && mode != DICT) immersive();
    }

    @SuppressWarnings("deprecation")
    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    // ---------------- distribución ----------------

    private static void detach(View v) {
        if (v != null && v.getParent() instanceof android.view.ViewGroup) ((android.view.ViewGroup) v.getParent()).removeView(v);
    }

    /** Arma la pantalla según el modo y la orientación. */
    @SuppressWarnings("deprecation")
    private void arrange() {
        for (View v : new View[]{keyboard, suggestBar, touchpad, gamepad, field, mediaView, cameraView}) detach(v);
        content.removeAllViews();
        boolean land = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        editBtn.setVisibility(mode == GAME ? View.VISIBLE : View.GONE);
        boolean btCapable = mode == GAME || mode == PAD || mode == KB_PAD;
        outBtn.setVisibility(btCapable ? View.VISIBLE : View.GONE);
        outBtn.setText(btMode() ? "Salida: Bluetooth" : "Salida: WiFi");
        pairBtn.setVisibility(btCapable && btMode() ? View.VISIBLE : View.GONE);
        sendBtn.setVisibility(mode == DICT ? View.VISIBLE : View.GONE);
        resetBtn.setVisibility(mode == GAME && gamepad.isEditing() ? View.VISIBLE : View.GONE);
        modeBtn.setText(NAMES[mode]);
        if (mode != GAME && gamepad.isEditing()) gamepad.setEditing(false);
        if (mode != DICT) {
            if (imm != null) imm.hideSoftInputFromWindow(field.getWindowToken(), 0);
            getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
            immersive();
        }
        switch (mode) {
            case KB:
                content.setOrientation(LinearLayout.VERTICAL);
                content.addView(suggestBar, new LinearLayout.LayoutParams(-1, dp(42)));
                content.addView(keyboard, new LinearLayout.LayoutParams(-1, 0, 1));
                break;
            case KB_PAD: {
                LinearLayout kbCol = new LinearLayout(this);
                kbCol.setOrientation(LinearLayout.VERTICAL);
                kbCol.addView(suggestBar, new LinearLayout.LayoutParams(-1, dp(42)));
                kbCol.addView(keyboard, new LinearLayout.LayoutParams(-1, 0, 1));
                if (land) {
                    content.setOrientation(LinearLayout.HORIZONTAL);
                    content.addView(kbCol, new LinearLayout.LayoutParams(0, -1, 3f));
                    content.addView(touchpad, new LinearLayout.LayoutParams(0, -1, 1.2f));
                } else {
                    content.setOrientation(LinearLayout.VERTICAL);
                    content.addView(touchpad, new LinearLayout.LayoutParams(-1, 0, 1f));
                    content.addView(kbCol, new LinearLayout.LayoutParams(-1, 0, 1.3f));
                }
                break;
            }
            case PAD:
                content.setOrientation(LinearLayout.VERTICAL);
                content.addView(touchpad, new LinearLayout.LayoutParams(-1, -1));
                break;
            case GAME:
                content.setOrientation(LinearLayout.VERTICAL);
                content.addView(gamepad, new LinearLayout.LayoutParams(-1, -1));
                break;
            case MEDIA:
                content.setOrientation(LinearLayout.VERTICAL);
                content.addView(mediaView, new LinearLayout.LayoutParams(-1, -1));
                break;
            case CAMERA:
                content.setOrientation(LinearLayout.VERTICAL);
                content.addView(cameraView, new LinearLayout.LayoutParams(-1, -1));
                break;
            default: {
                getWindow().getDecorView().setSystemUiVisibility(0);
                getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
                content.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -1);
                lp.setMargins(dp(10), dp(8), dp(10), dp(10));
                content.addView(field, lp);
                field.requestFocus();
                field.post(() -> { if (imm != null) imm.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT); });
            }
        }
        if (hub != null && resumed) { hub.keyboardVisible = mode < MEDIA; showingMedia = mode == MEDIA; showingCamera = mode == CAMERA; }
        updateTouchpadHint();
        refreshSuggestions();
        syncBluetooth();
    }

    private void pickMode() {
        int[] icons = {Ui.KEYBOARD, Ui.KB_TOUCHPAD, Ui.TOUCHPAD, Ui.GAMEPAD, Ui.MIC, Ui.SEND, Ui.IMAGE};
        String[] subs = {
                "Teclado completo con sugerencias",
                "Teclado y touchpad juntos",
                "Mover el puntero, clic, desplazar, atrás e inicio",
                "Joystick, cruceta y botones que se pueden mover",
                "Escribir con el teclado del teléfono o dictar por voz",
                "Play/pausa, adelantar 10 s y volumen del video del principal",
                "Cámara del principal: ver aquí lo que ve, trasera/delantera, foto, flash y zoom"};
        Ui.Sheet sh = new Ui.Sheet(this).title("¿Cómo quieres controlar el principal?");
        for (int i = 0; i < NAMES.length; i++) {
            final int m = i;
            sh.option(icons[i], -1, NAMES[i], subs[i], mode == i, () -> {
                mode = m;
                prefs.edit().putInt("kbMode3", m).apply();
                arrange();
            });
        }
        sh.button("Cerrar", false, null).show();
    }

    private void toggleEdit() {
        gamepad.setEditing(!gamepad.isEditing());
        editBtn.setText(gamepad.isEditing() ? "Listo" : "Editar");
        resetBtn.setVisibility(gamepad.isEditing() ? View.VISIBLE : View.GONE);
        if (!gamepad.isEditing()) prefs.edit().putString("padLayout", gamepad.toJson()).apply();
    }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    /**
     * Tres zonas: izquierda, centro y derecha. El centro queda en el medio exacto de la pantalla; solo si la
     * derecha no deja espacio se corre lo justo a la izquierda. La izquierda usa lo que sobra.
     */
    static class CenterBar extends android.view.ViewGroup {
        private final View l, c, r;
        CenterBar(Context ctx, View left, View center, View right) {
            super(ctx);
            l = left; c = center; r = right;
            addView(l); addView(c); addView(r);
        }
        @Override protected void onMeasure(int ws, int hs) {
            int W = MeasureSpec.getSize(ws), H = MeasureSpec.getSize(hs);
            int ih = Math.max(0, H - getPaddingTop() - getPaddingBottom()), iw = Math.max(0, W - getPaddingLeft() - getPaddingRight());
            int hSpec = MeasureSpec.makeMeasureSpec(ih, MeasureSpec.AT_MOST);
            c.measure(MeasureSpec.makeMeasureSpec(iw, MeasureSpec.AT_MOST), hSpec);
            r.measure(MeasureSpec.makeMeasureSpec(Math.max(0, iw - c.getMeasuredWidth()), MeasureSpec.AT_MOST), hSpec);
            int[] x = place(W);
            l.measure(MeasureSpec.makeMeasureSpec(Math.max(0, x[0] - getPaddingLeft() - (int) (8 * getResources().getDisplayMetrics().density)),
                    MeasureSpec.EXACTLY), hSpec);
            setMeasuredDimension(W, H);
        }
        /** {x del centro, x de la derecha} */
        private int[] place(int W) {
            int cw = c.getMeasuredWidth(), rx = W - getPaddingRight() - r.getMeasuredWidth();
            int cx = Math.min((W - cw) / 2, rx - cw);
            return new int[]{Math.max(getPaddingLeft(), cx), rx};
        }
        @Override protected void onLayout(boolean ch, int a, int b, int cc, int d) {
            int W = getWidth(), H = getHeight();
            int[] x = place(W);
            lay(l, getPaddingLeft(), H); lay(c, x[0], H); lay(r, x[1], H);
        }
        private static void lay(View v, int x, int H) {
            int y = (H - v.getMeasuredHeight()) / 2;
            v.layout(x, y, x + v.getMeasuredWidth(), y + v.getMeasuredHeight());
        }
    }

    private TextView chip(String t, View.OnClickListener l) {
        TextView b = new TextView(this);
        b.setText(t);
        b.setTextSize(13);
        b.setGravity(Gravity.CENTER);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setPadding(dp(14), 0, dp(14), 0);
        b.setTextColor(th.fg);
        GradientDrawable g = new GradientDrawable();
        g.setColor(th.chip);
        if (th.stroke != 0) g.setStroke(dp(1), th.stroke);
        g.setCornerRadius(dp(18));
        b.setBackground(g);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(36));
        lp.setMarginStart(dp(6));
        b.setLayoutParams(lp);
        return b;
    }

    private void help() {
        new Ui.Sheet(this)
                .title("Controlar el principal")
                .message("Teclado y dictado escriben donde tocas en el principal, si ahí está elegido el teclado ChronOS "
                        + "(barra delgada en vez del teclado). Si no, en el principal aparece un aviso para elegirlo con un toque.\n\n"
                        + "Touchpad y gamepad: con la versión completa (accesibilidad activa en el principal) mueven un puntero y "
                        + "tocan la pantalla. Con la versión liviana el touchpad mueve el cursor del texto.\n\n"
                        + "Gamepad: cada botón toca el principal en el mismo lugar donde lo pones aquí. Usa \"Editar\" para "
                        + "moverlos (arrastrar) y cambiar su tamaño (pellizcar con dos dedos).\n\n"
                        + "Enviar: usa el botón de enviar del campo. En WhatsApp actívalo en Ajustes → Chats → \"Enter para enviar\" "
                        + "para que funcione siempre.")
                .button("Entendido", true, null)
                .show();
    }

    // ---------------- estado ----------------

    private void updateStatus() {
        String t;
        boolean ok = false;
        boolean typing = mode == KB || mode == KB_PAD || mode == DICT;
        if (btMode() && !typing) {
            status.setText(btStatus.isEmpty() ? "Preparando control Bluetooth…" : btStatus);
            status.setTextColor(btOk ? th.accent : th.warn);
            updateTouchpadHint();
            return;
        }
        if (!hub.connected()) {
            t = "Sin conexión. Abre ChronOS en ambos teléfonos";
        } else if (!typing) {
            t = hub.remotePointer() ? "● Conectado al principal" : "● Conectado · versión liviana: sin puntero";
            ok = hub.remotePointer();
        } else if (!hub.remoteImeSelected()) {
            t = "En el principal toca el aviso para elegir el teclado ChronOS";
        } else if (!hub.remoteImeReady()) {
            t = "Toca donde se escribe en el principal";
        } else {
            t = "● Escribiendo en el principal";
            ok = true;
        }
        status.setText(t);
        status.setTextColor(ok ? th.accent : th.warn);
        updateTouchpadHint();
    }

    private void updateTouchpadHint() {
        if (touchpad == null) return;
        if (btMode()) touchpad.setHint(btOk ? "Mouse Bluetooth" : "Mouse Bluetooth · toca \"Vincular\" la primera vez");
        else touchpad.setHint(hub != null && hub.remotePointer() ? "Touchpad" : "Touchpad · mueve el cursor del texto");
    }

    @Override public void onState(int state, String info) {
        if (state == Link.CONNECTED) hub.send("kbAsk");
        updateStatus();
    }

    @Override public void onMessage(JSONObject m) {
        String t = m.optString("t");
        if ("navAck".equals(t) && !m.optBoolean("ok"))
            android.widget.Toast.makeText(this, "Recientes necesita la versión completa en el principal", android.widget.Toast.LENGTH_SHORT).show();
        if ("kbState".equals(t) || "kbAck".equals(t)) updateStatus();
        if (mediaView != null && t.startsWith("m")) mediaView.onMessage(m);
        if (cameraView != null && t.startsWith("cam")) cameraView.onMessage(m);
        // abierto solo (al tocar un campo en el principal): se cierra solo cuando ya no hay dónde escribir
        if ("kbState".equals(t) && auto && (mode == KB || mode == KB_PAD || mode == DICT)) {
            h.removeCallbacks(autoClose);
            if (!hub.remoteImeReady()) h.postDelayed(autoClose, 3000);
        }
    }

    // ---------------- envío y sugerencias ----------------

    private void sendText(String s) { hub.send("kb", "a", "text", "s", s); }
    private void sendKey(int code, int meta, int n) { hub.send("kb", "a", "key", "k", code, "m", meta, "n", n); }

    @Override public void text(String s) {
        sendText(s);
        boolean letters = !s.isEmpty();
        for (int i = 0; i < s.length(); i++) if (!Character.isLetter(s.charAt(i))) { letters = false; break; }
        if (letters) word.append(s); else word.setLength(0);
        refreshSuggestions();
    }

    @Override public void key(int code, int meta) {
        sendKey(code, meta, 1);
        if (code == KeyEvent.KEYCODE_DEL && meta == 0 && word.length() > 0) word.setLength(word.length() - 1);
        else word.setLength(0);
        refreshSuggestions();
    }

    @Override public void action(String what) { hub.send("kb", "a", "act", "x", what); word.setLength(0); refreshSuggestions(); }
    @Override public void media(String what) { hub.send("media", "k", what); }

    private void refreshSuggestions() {
        if (suggestBar == null) return;
        List<String> s = dict.suggest(word.toString(), 3);
        for (int i = 0; i < 3; i++) {
            TextView t = (TextView) suggestBar.getChildAt(i);
            String v = i < s.size() ? s.get(i) : "";
            t.setText(v);
            t.setVisibility(v.isEmpty() ? View.INVISIBLE : View.VISIBLE);
        }
    }

    /** Reemplaza la palabra a medio escribir por la sugerencia elegida. */
    private void pickSuggestion(String s) {
        if (s.isEmpty()) return;
        suggestBar.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        int n = word.codePointCount(0, word.length());
        if (n > 0) sendKey(KeyEvent.KEYCODE_DEL, 0, n);
        sendText(s + " ");
        word.setLength(0);
        refreshSuggestions();
    }

    /** Refleja en el principal los cambios del campo de dictado (incluye autocorrección). */
    private void mirror(Editable e) {
        if (resetting) return;
        String now = e.toString();
        int p = 0;
        while (p < last.length() && p < now.length() && last.charAt(p) == now.charAt(p)) p++;
        int del = last.codePointCount(p, last.length());
        String add = now.substring(p);
        if (del > 0) sendKey(KeyEvent.KEYCODE_DEL, 0, del);
        boolean enter = add.indexOf('\n') >= 0;
        String[] parts = add.split("\n", -1);
        for (int i = 0; i < parts.length; i++) {
            if (!parts[i].isEmpty()) sendText(parts[i]);
            if (i < parts.length - 1) sendKey(KeyEvent.KEYCODE_ENTER, 0, 1);
        }
        last = now;
        if (enter || now.length() > 400) {
            resetting = true;
            e.clear();
            resetting = false;
            last = "";
        }
    }
}
