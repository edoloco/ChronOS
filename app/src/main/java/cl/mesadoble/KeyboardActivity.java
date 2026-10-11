package cl.mesadoble;

import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Pantalla de teclado / touchpad Bluetooth para escribir en el otro teléfono. */
@SuppressWarnings("MissingPermission")
public class KeyboardActivity extends Activity implements Hid.Listener {

    private static final int REQ_PERMS = 11, REQ_ENABLE = 12, REQ_DISCOVERABLE = 13;
    private static final String P_CONNECT = "android.permission.BLUETOOTH_CONNECT";
    private static final String P_ADVERTISE = "android.permission.BLUETOOTH_ADVERTISE";
    private static final String[] MODES = {"Teclado", "Teclado + touchpad", "Touchpad"};

    private boolean night;
    private SharedPreferences prefs;
    private Hid hid;
    private KeyboardView keyboard;
    private TouchpadView touchpad;
    private LinearLayout content;
    private TextView status;
    private Button modeBtn;
    private int mode;

    @Override protected void onCreate(Bundle b) {
        night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        setTheme(night ? android.R.style.Theme_Material_NoActionBar : android.R.style.Theme_Material_Light_NoActionBar);
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("mesa", MODE_PRIVATE);
        mode = prefs.getInt("kbMode", 0);

        int bg = night ? 0xFF121920 : 0xFFCDD5DC;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bg);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(8), dp(4), dp(4), 0);
        status = new TextView(this);
        status.setTextSize(13);
        status.setSingleLine(true);
        status.setTextColor(night ? 0xFF93A4B5 : 0xFF5B6B7A);
        top.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
        Button phone = small("Teléfono");
        phone.setOnClickListener(v -> pickDevice());
        modeBtn = small(MODES[mode]);
        modeBtn.setOnClickListener(v -> pickMode());
        Button close = small("Cerrar");
        close.setOnClickListener(v -> finish());
        top.addView(phone); top.addView(modeBtn); top.addView(close);
        root.addView(top, new LinearLayout.LayoutParams(-1, dp(40)));

        content = new LinearLayout(this);
        keyboard = new KeyboardView(this, night);
        touchpad = new TouchpadView(this, night);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        arrange();

        hid = new Hid(this, this);
        keyboard.setHid(hid);
        touchpad.setHid(hid);
        onStatus("Preparando…", false);
        if (hasPerms()) begin(); else requestPermissions(perms(), REQ_PERMS);
    }

    @Override protected void onDestroy() {
        hid.stop();
        super.onDestroy();
    }

    @Override public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        arrange();
    }

    @Override public void onWindowFocusChanged(boolean f) {
        super.onWindowFocusChanged(f);
        if (f) immersive();
    }

    @SuppressWarnings("deprecation")
    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private Button small(String t) {
        Button b = new Button(this);
        b.setText(t);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setMinWidth(0); b.setMinimumWidth(0);
        b.setMinHeight(0); b.setMinimumHeight(0);
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

    /** Ubica teclado y touchpad según el modo y la orientación. */
    private void arrange() {
        content.removeAllViews();
        boolean land = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        if (mode == 0) {
            content.setOrientation(LinearLayout.VERTICAL);
            content.addView(keyboard, new LinearLayout.LayoutParams(-1, -1));
        } else if (mode == 2) {
            content.setOrientation(LinearLayout.VERTICAL);
            content.addView(touchpad, new LinearLayout.LayoutParams(-1, -1));
        } else if (land) {
            content.setOrientation(LinearLayout.HORIZONTAL);
            content.addView(keyboard, new LinearLayout.LayoutParams(0, -1, 3f));
            content.addView(touchpad, new LinearLayout.LayoutParams(0, -1, 1.1f));
        } else {
            content.setOrientation(LinearLayout.VERTICAL);
            content.addView(touchpad, new LinearLayout.LayoutParams(-1, 0, 1f));
            content.addView(keyboard, new LinearLayout.LayoutParams(-1, 0, 1.25f));
        }
    }

    private void pickMode() {
        new AlertDialog.Builder(this)
                .setTitle("¿Qué quieres ver?")
                .setSingleChoiceItems(MODES, mode, (d, w) -> {
                    mode = w;
                    prefs.edit().putInt("kbMode", w).apply();
                    modeBtn.setText(MODES[w]);
                    arrange();
                    d.dismiss();
                })
                .show();
    }

    // ---------------- permisos y Bluetooth ----------------

    private String[] perms() {
        return Build.VERSION.SDK_INT >= 31 ? new String[]{P_CONNECT, P_ADVERTISE} : new String[0];
    }

    private boolean hasPerms() {
        for (String p : perms()) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        return true;
    }

    @Override public void onRequestPermissionsResult(int req, String[] p, int[] r) {
        if (req != REQ_PERMS) return;
        if (hasPerms()) begin();
        else onStatus("Sin permiso de Bluetooth no puedo funcionar como teclado", false);
    }

    private void begin() {
        BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
        if (a == null) { onStatus("Este teléfono no tiene Bluetooth", false); return; }
        if (!a.isEnabled()) {
            try { startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ_ENABLE); }
            catch (Exception e) { onStatus("Activa el Bluetooth y vuelve a abrir el teclado", false); }
            return;
        }
        String last = prefs.getString("kbHost", null);
        if (hid.start(last) && last == null) pickDevice();
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_ENABLE) {
            if (res == RESULT_OK) begin(); else onStatus("Activa el Bluetooth para usar el teclado", false);
        } else if (req == REQ_DISCOVERABLE && res != RESULT_CANCELED) {
            String name = "este teléfono";
            try { BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter(); if (a != null && a.getName() != null) name = "\"" + a.getName() + "\""; }
            catch (Exception ignored) { }
            new AlertDialog.Builder(this)
                    .setTitle("Vincula desde el otro teléfono")
                    .setMessage("En el otro teléfono abre Ajustes → Bluetooth → Vincular nuevo dispositivo, y elige " + name
                            + ".\n\nCuando termine, vuelve aquí: el teclado se conecta solo.")
                    .setPositiveButton("Entendido", null)
                    .show();
        }
    }

    private void pickDevice() {
        if (!hasPerms()) { requestPermissions(perms(), REQ_PERMS); return; }
        BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
        if (a == null || !a.isEnabled()) { begin(); return; }
        final List<BluetoothDevice> list = new ArrayList<>();
        List<String> names = new ArrayList<>();
        try {
            Set<BluetoothDevice> bonded = a.getBondedDevices();
            if (bonded != null) for (BluetoothDevice d : bonded) {
                list.add(d);
                names.add(d.getName() != null ? d.getName() : d.getAddress());
            }
        } catch (Exception ignored) { }
        AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle("¿En qué teléfono vas a escribir?");
        if (list.isEmpty()) {
            b.setMessage("Todavía no hay dispositivos vinculados. Toca \"Vincular nuevo\" y sigue los pasos.");
        } else {
            b.setItems(names.toArray(new String[0]), (d, w) -> {
                String addr = list.get(w).getAddress();
                prefs.edit().putString("kbHost", addr).apply();
                hid.start(addr);
                hid.connect(addr);
            });
        }
        b.setPositiveButton("Vincular nuevo", (d, w) -> {
            Intent i = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
            i.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120);
            try { startActivityForResult(i, REQ_DISCOVERABLE); }
            catch (Exception e) { onStatus("No pude hacer visible este teléfono", false); }
        });
        b.setNegativeButton("Cancelar", null);
        b.show();
    }

    @Override public void onStatus(String text, boolean connected) {
        status.setText((connected ? "● " : "") + text);
        if (connected && hid != null && hid.hostAddress() != null) prefs.edit().putString("kbHost", hid.hostAddress()).apply();
        status.setTextColor(connected ? (night ? 0xFF5CC3CF : 0xFF1F7F8C) : (night ? 0xFF93A4B5 : 0xFF5B6B7A));
    }
}
