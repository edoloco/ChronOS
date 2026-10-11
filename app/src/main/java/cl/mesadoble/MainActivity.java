package cl.mesadoble;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.drawable.GradientDrawable;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Base64;
import android.view.Gravity;
import android.view.OrientationEventListener;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity implements DeskView.Host, Link.Listener {

    private static final int PICK_IMAGE = 7;
    private static final int[] PAPER = {0xFFF6E6A2, 0xFFCFE5C6, 0xFFC8DCF0, 0xFFF2CFC6, 0xFFE2D4F0};
    private static final Pattern URL = Pattern.compile("https?://\\S+", Pattern.CASE_INSENSITIVE);

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;
    private DeskView desk;
    private Hub hub;
    private LinearLayout bar;
    private SquareButton statusBtn;
    private final List<SquareButton> squares = new ArrayList<>();
    private OrientationEventListener orient;
    private int snapped = -1;
    private String statusText = "";
    // imágenes: viajan una sola vez, aparte de los movimientos
    private final Set<String> requested = new HashSet<>();
    private final Map<String, String[]> pendingImg = new HashMap<>();
    private boolean night;
    private int side = -1, otherSide = -1, linkState = Link.IDLE, colorIdx = 0;
    private long lastDragSend = 0, waitingSince = 0;
    private String linkInfo;

    // ---------------- ciclo de vida ----------------

    @Override protected void onCreate(Bundle b) {
        night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        setTheme(night ? android.R.style.Theme_Material_NoActionBar : android.R.style.Theme_Material_Light_NoActionBar);
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("mesa", MODE_PRIVATE);

        FrameLayout root = new FrameLayout(this);
        desk = new DeskView(this, night);
        desk.setHost(this);
        root.addView(desk, new FrameLayout.LayoutParams(-1, -1));

        bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(dp(6), dp(6), dp(6), dp(6));
        bar.setBackground(pill(night ? 0xE6182129 : 0xEEFFFFFF));
        bar.setElevation(dp(6));
        addSquare(SquareButton.NOTE, "Nota", true, v -> addNote());
        addSquare(SquareButton.IMAGE, "Imagen", false, v -> pickImage());
        addSquare(SquareButton.APPS, "Apps", false, v -> showApps());
        addSquare(SquareButton.KEYBOARD, "Teclado", false, v -> keyboardMenu());
        addSquare(SquareButton.SETTINGS, "Ajustes", false, v -> settingsMenu());
        statusBtn = addSquare(SquareButton.STATUS, "Red", false, v -> showConnection());
        root.addView(bar, new FrameLayout.LayoutParams(-2, -2));

        // Forma fija: dos teléfonos parados, lado a lado, que juntos forman una pantalla apaisada.
        // Cada pantalla queda en vertical; solo los íconos giran según cómo se sostiene.
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        orient = new OrientationEventListener(this) {
            @Override public void onOrientationChanged(int o) { onPhysicalOrientation(o); }
        };
        setContentView(root);
        hub = Hub.get(this);
        desk.setVertical(stacked());
        loadItems();
        boolean savedStacked = prefs.getBoolean("savedStacked", false);   // versiones anteriores: lado a lado
        if (savedStacked != stacked()) { desk.convertAll(stacked()); prefs.edit().putBoolean("savedStacked", stacked()).apply(); scheduleSave(); }
        for (Item it : desk.items) desk.normalize(it);

        side = hub.side();
        if (side >= 0) applySide();
        if (b == null) chooseSide();   // al iniciar, elegir principal o extendido
        hub.add(this);
        LinkService.start(this);
        if (android.os.Build.VERSION.SDK_INT >= 33 && !prefs.getBoolean("askedNotif", false)) {
            prefs.edit().putBoolean("askedNotif", true).apply();
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 3);
        }
        handleShare(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleShare(intent);
    }

    @Override protected void onResume() {
        super.onResume();
        hub.foreground = true;
        if (orient.canDetectOrientation()) orient.enable();
        if (hub.isPrincipal() && hub.connected() && MesaAccess.instance != null) MesaAccess.instance.switchToMesa();
    }

    @Override protected void onPause() {
        hub.foreground = false;
        orient.disable();
        super.onPause();
    }

    /** Mesa horizontal = teléfonos uno sobre otro; vertical = lado a lado. Siempre unidos por el lado largo. */
    private boolean stacked() { return false; }
    /** Ancho inicial de un elemento: en horizontal la pantalla es más ancha, así que se usa la mitad. */
    private double sized(double base) { return stacked() ? base * 0.5 : base; }
    private String sideName(int s) { return s == 1 ? "PRINCIPAL (derecha)" : "EXTENDIDO (izquierda)"; }
    private String firstPhone() { return "el extendido (izquierda)"; }
    private String secondPhone() { return "el principal (derecha)"; }

    private SquareButton addSquare(int kind, String label, boolean primary, View.OnClickListener l) {
        SquareButton b = new SquareButton(this, kind, label, primary, night);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMarginEnd(dp(6));
        bar.addView(b, lp);
        squares.add(b);
        return b;
    }

    /** Gira los íconos para que se lean derechos, sin mover nada de lugar. */
    private void onPhysicalOrientation(int o) {
        if (o == OrientationEventListener.ORIENTATION_UNKNOWN) return;
        int q = ((o + 45) / 90 * 90) % 360;
        if (snapped >= 0 && q != snapped) {
            int diff = Math.abs(o - snapped); diff = Math.min(diff, 360 - diff);
            if (diff < 60) return;   // margen para que no titubee entre dos posiciones
        }
        if (q == snapped) return;
        snapped = q;
        int r = getWindowManager().getDefaultDisplay().getRotation() * 90;
        float glyph = -((q + r) % 360);
        if (glyph < -180) glyph += 360;
        for (SquareButton b : squares) b.setGlyphRotation(glyph);
        desk.setGlyphRotation(glyph);
    }

    // ---------------- ajustes y apps ----------------

    private void settingsMenu() {
        boolean auto = hub.autoKeyboard();
        String[] opts = {
                "Este teléfono es el " + sideName(side) + " (cambiar)",
                hub.isPrincipal() ? "Revisar configuración del principal" : "Revisar configuración del extendido",
                "Teclado automático: " + (auto ? "SÍ" : "NO") + " (cambiar)",
                "Conexión y dirección IP"};
        new AlertDialog.Builder(this)
                .setTitle("Ajustes")
                .setItems(opts, (d, w) -> {
                    if (w == 0) chooseSide();
                    else if (w == 1) checkSetup(true);
                    else if (w == 2) {
                        prefs.edit().putBoolean("autoKb", !auto).apply();
                        Toast.makeText(this, !auto ? "El teclado se abrirá solo en el extendido" : "Teclado automático desactivado", Toast.LENGTH_SHORT).show();
                    }
                    else showConnection();
                })
                .show();
    }

    // ---------------- configuración guiada ----------------

    private boolean canOverlay() { return android.provider.Settings.canDrawOverlays(this); }

    /**
     * Revisa lo que cada teléfono necesita. Principal: teclado Mesa Doble activado, cambio automático
     * de teclado (accesibilidad) y permiso para abrir apps pedidas desde el extendido.
     * Extendido: permiso para abrir el teclado solo aunque la mesa esté de fondo.
     */
    private void checkSetup(boolean always) {
        List<String> lines = new ArrayList<>();
        final List<Runnable> fixes = new ArrayList<>();
        if (hub.isPrincipal()) {
            if (!Hub.imeEnabled(this)) {
                lines.add("✗ Activar el teclado Mesa Doble");
                fixes.add(() -> openSettings(android.provider.Settings.ACTION_INPUT_METHOD_SETTINGS, null,
                        "Activa \"Mesa Doble\" en la lista de teclados y vuelve."));
            } else lines.add("✓ Teclado Mesa Doble activado");
            boolean full = MesaAccess.available(this);
            if (!full) {
                lines.add("• Versión liviana: al conectar, toca el aviso \"quiere escribir aquí\" para elegir el teclado Mesa Doble");
            } else if (!MesaAccess.isEnabled(this)) {
                lines.add("✗ Cambio automático de teclado (accesibilidad)");
                fixes.add(this::guideAccessibility);
            } else lines.add("✓ Cambio automático de teclado");
            if (!full) {
                lines.add("• Versión liviana: las apps pedidas desde el extendido se abren tocando un aviso");
            } else if (!canOverlay()) {
                lines.add("✗ Abrir apps pedidas desde el extendido");
                fixes.add(() -> openSettings(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()),
                        "Activa \"Mostrar sobre otras apps\" para Mesa Doble y vuelve."));
            } else lines.add("✓ Abrir apps pedidas desde el extendido");
        } else {
            if (!MesaAccess.available(this)) {
                lines.add("• Versión liviana: si la mesa está de fondo, el teclado se abre tocando un aviso");
            } else if (!canOverlay()) {
                lines.add("✗ Abrir el teclado solo, aunque la mesa esté de fondo");
                fixes.add(() -> openSettings(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()),
                        "Activa \"Mostrar sobre otras apps\" para Mesa Doble y vuelve."));
            } else lines.add("✓ Abrir el teclado solo");
        }
        if (fixes.isEmpty() && !always) return;
        StringBuilder msg = new StringBuilder();
        for (String l : lines) msg.append(l).append("\n");
        if (fixes.isEmpty()) msg.append("\nTodo listo.");
        else msg.append("\nEn teléfonos Xiaomi revisa también: Ajustes → Apps → Mesa Doble → Otros permisos → "
                + "\"Mostrar ventanas emergentes mientras se ejecuta en segundo plano\".");
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(hub.isPrincipal() ? "Configurar el principal" : "Configurar el extendido")
                .setMessage(msg.toString());
        if (!fixes.isEmpty()) b.setPositiveButton("Resolver el primero", (d, w) -> fixes.get(0).run()).setNegativeButton("Después", null);
        else b.setPositiveButton("Listo", null);
        b.show();
    }

    private void openSettings(String action, Uri data, String toast) {
        try {
            Intent i = new Intent(action);
            if (data != null) i.setData(data);
            startActivity(i);
            Toast.makeText(this, toast, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "No pude abrir esa pantalla de Ajustes", Toast.LENGTH_LONG).show();
        }
    }

    private void guideAccessibility() {
        new AlertDialog.Builder(this)
                .setTitle("Cambio automático de teclado")
                .setMessage("Mesa Doble usa un servicio de accesibilidad solo para cambiar al teclado Mesa Doble "
                        + "(y devolver el tuyo al desconectarse) y para saber cuándo abres una app de chat. "
                        + "No lee lo que hay en tu pantalla.\n\n"
                        + "1. Si la opción aparece bloqueada (\"ajuste restringido\"): abre Info de la app → menú ⋮ → "
                        + "\"Permitir ajustes restringidos\".\n"
                        + "2. En Accesibilidad → Apps descargadas, activa \"Mesa Doble\".")
                .setPositiveButton("Abrir Accesibilidad", (d, w) -> openSettings(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS, null,
                        "Busca Mesa Doble en Apps descargadas y actívalo."))
                .setNeutralButton("Info de la app", (d, w) -> openSettings(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName()), "Menú ⋮ → Permitir ajustes restringidos"))
                .setNegativeButton("Después", null)
                .show();
    }

    private void showApps() {
        AppsDialog.show(this, night, new AppsDialog.Host() {
            @Override public void openHere(String pkg) { openOnPrincipal(pkg, ""); }
            @Override public void openThere(String pkg, String label) { openOnPrincipal(pkg, label); }
            @Override public void putOnDesk(String pkg, String label, String iconPng) {
                Item it = new Item();
                it.kind = Item.APP; it.pkg = pkg; it.title = label; it.w = sized(0.24);
                it.setImage(iconPng);
                double[] p = desk.spot(it.w);
                it.x = p[0]; it.y = p[1];
                addItem(it);
                sendImage(it);
            }
        });
    }

    private void openApp(String pkg, String label) {
        int r = hub.launchHere(pkg, label);
        if (r == Hub.NOT_INSTALLED) {
            new AlertDialog.Builder(this)
                    .setTitle("No está instalada en este teléfono")
                    .setMessage("Las apps se abren en el principal. ¿Quieres buscarla en Play Store?")
                    .setPositiveButton("Buscar", (d, w) -> {
                        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + pkg))); }
                        catch (Exception e) { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=" + pkg))); }
                    })
                    .setNegativeButton("Cancelar", null)
                    .show();
        }
    }

    /** Las apps corren en el principal: desde el extendido se le pide que la abra. */
    private void openOnPrincipal(String pkg, String label) {
        if (hub.isPrincipal()) { openApp(pkg, label); return; }
        if (!hub.connected()) { Toast.makeText(this, "El principal no está conectado", Toast.LENGTH_SHORT).show(); return; }
        hub.send("launch", "pkg", pkg, "label", label);
    }

    @Override public void onOpenApp(Item it) { openOnPrincipal(it.pkg, it.title); }

    @Override protected void onDestroy() {
        hub.remove(this);   // la conexión sigue viva para el teclado
        super.onDestroy();
    }

    // ---------------- teclado ----------------

    private void keyboardMenu() {
        new AlertDialog.Builder(this)
                .setTitle("Teclado")
                .setItems(new String[]{"Este teléfono es el teclado", "Este teléfono recibe lo que escribo"}, (d, w) -> {
                    if (w == 0) startActivity(new Intent(this, KeyboardActivity.class));
                    else setupReceiver();
                })
                .show();
    }

    private boolean imeEnabled() { return Hub.imeEnabled(this); }

    private void setupReceiver() {
        if (Hub.imeSelected(this)) {
            new AlertDialog.Builder(this)
                    .setTitle("Listo para recibir")
                    .setMessage("El teclado Mesa Doble ya está elegido en este teléfono. Abre cualquier app, toca donde se escribe "
                            + "y escribe desde el otro teléfono.\n\nPara volver a tu teclado normal toca \"Cambiar teclado\" en la barra.")
                    .setPositiveButton("Entendido", null)
                    .setNeutralButton("Cambiar teclado", (d, w) -> startActivity(new Intent(this, ImePickerActivity.class)))
                    .show();
            return;
        }
        if (!imeEnabled()) {
            new AlertDialog.Builder(this)
                    .setTitle("Activa el teclado Mesa Doble")
                    .setMessage("Se abrirá la lista de teclados. Activa \"Mesa Doble\" y vuelve aquí.\n\n"
                            + "Android mostrará un aviso general sobre teclados: Mesa Doble solo escribe lo que mandas "
                            + "desde tu otro teléfono y no guarda nada.")
                    .setPositiveButton("Abrir ajustes", (d, w) -> {
                        try { startActivity(new Intent(android.provider.Settings.ACTION_INPUT_METHOD_SETTINGS)); }
                        catch (Exception e) { Toast.makeText(this, "Busca \"Gestionar teclados\" en Ajustes", Toast.LENGTH_LONG).show(); }
                    })
                    .setNegativeButton("Cancelar", null)
                    .show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Elige \"Mesa Doble\"")
                .setMessage("En la lista que aparece elige Mesa Doble. Luego abre WhatsApp, Chrome o la app que quieras "
                        + "y toca donde se escribe: verás una barra delgada y podrás escribir desde el otro teléfono.\n\n"
                        + "Para volver a tu teclado normal toca \"Cambiar teclado\" en esa barra.")
                .setPositiveButton("Elegir teclado", (d, w) -> startActivity(new Intent(this, ImePickerActivity.class)))
                .setNegativeButton("Cancelar", null)
                .show();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) immersive();
    }

    @SuppressWarnings("deprecation")
    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    // ---------------- interfaz ----------------

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private GradientDrawable pill(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(16));
        return g;
    }

    private Button barButton(String text, boolean primary) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setMinWidth(0); b.setMinimumWidth(0);
        b.setMinHeight(dp(44)); b.setMinimumHeight(dp(44));
        b.setPadding(dp(12), 0, dp(12), 0);
        int accent = night ? 0xFF5CC3CF : 0xFF1F7F8C;
        b.setTextColor(primary ? (night ? 0xFF18212B : 0xFFFFFFFF) : (night ? 0xFFE6EDF3 : 0xFF1D2733));
        GradientDrawable g = new GradientDrawable();
        g.setColor(primary ? accent : 0x00000000);
        g.setCornerRadius(dp(11));
        b.setBackground(g);
        b.setStateListAnimator(null);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(44));
        lp.setMarginEnd(dp(4));
        b.setLayoutParams(lp);
        return b;
    }

    private void applySide() {
        desk.setSide(side);
        // la barra va en el borde exterior, lejos de la unión entre teléfonos
        desk.setVertical(stacked());
        FrameLayout.LayoutParams bp = (FrameLayout.LayoutParams) bar.getLayoutParams();
        if (stacked()) {
            bp.gravity = (side == 0 ? Gravity.TOP : Gravity.BOTTOM) | Gravity.START;
            bp.setMargins(dp(14), dp(12), dp(14), dp(12));
        } else {
            bp.gravity = Gravity.BOTTOM | (side == 0 ? Gravity.START : Gravity.END);
            bp.setMargins(dp(12), 0, dp(12), dp(14));
        }
        bar.setLayoutParams(bp);
        otherSide = -1;
        hub.setSide(side);
        waitingSince = System.currentTimeMillis();
        if (!prefs.getBoolean("welcomed", false)) {
            prefs.edit().putBoolean("welcomed", true).apply();
            Item it = new Item();
            it.w = sized(0.62);
            it.text = side == 0 ? "Arrástrame hacia el otro teléfono, o toca la flecha →"
                                : "Esta nota vive en la otra pantalla. Tócala para editarla.";
            it.color = PAPER[side == 0 ? 0 : 2];
            double[] sp = desk.spot(it.w);
            it.x = sp[0]; it.y = sp[1]; it.rot = side == 0 ? -1.5f : 1.2f;
            addItem(it);
        }
        updateStatus();
    }

    private void chooseSide() {
        String last = side == 1 ? "\n\nLa última vez este teléfono fue el principal." : side == 0 ? "\n\nLa última vez este teléfono fue el extendido." : "";
        new AlertDialog.Builder(this)
                .setTitle("¿Qué es este teléfono?")
                .setMessage("Pon los dos teléfonos parados, uno al lado del otro: juntos forman una pantalla apaisada.\n\n"
                        + "PRINCIPAL (derecha): aquí corren las apps.\n"
                        + "EXTENDIDO (izquierda): pantalla extra y teclado." + last)
                .setPositiveButton("Principal (derecha)", (d, w) -> setSide(1))
                .setNegativeButton("Extendido (izquierda)", (d, w) -> setSide(0))
                .setCancelable(side >= 0)
                .show();
    }

    private void setSide(int s) {
        side = s;
        prefs.edit().putInt("side", s).apply();
        applySide();
        if (s == 1 && hub.connected() && MesaAccess.instance != null) MesaAccess.instance.switchToMesa();
        main.postDelayed(() -> checkSetup(false), 400);
    }

    private void updateStatus() {
        String t;
        if (linkState == Link.CONNECTED) {
            t = otherSide == side ? "⚠ Ambos están en el mismo lado" : "● Unido al otro teléfono";
        } else if (linkInfo != null) {
            t = linkInfo;
        } else if (side == 0) {
            t = "Esperando al otro teléfono · IP " + Link.localIp();
        } else if (System.currentTimeMillis() - waitingSince > 12000) {
            t = "No encuentro " + firstPhone() + ". Puedes conectar escribiendo su IP.";
        } else {
            t = "Buscando " + firstPhone() + "…";
        }
        statusText = t;
        statusBtn.setStatus(linkState == Link.CONNECTED ? (otherSide == side ? 1 : 2) : linkState == Link.WAITING ? 1 : 0);
        desk.setConnected(linkState == Link.CONNECTED);
        main.removeCallbacks(statusTick);
        if (linkState != Link.CONNECTED) main.postDelayed(statusTick, 4000);
    }

    private final Runnable statusTick = this::updateStatus;

    private void showConnection() {
        final EditText ip = new EditText(this);
        ip.setHint("Ej: 192.168.1.20");
        ip.setInputType(InputType.TYPE_CLASS_PHONE);
        ip.setText(prefs.getString("lastIp", ""));
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(22), dp(4), dp(22), 0);
        box.addView(ip, new LinearLayout.LayoutParams(-1, -2));
        String state = statusText;
        AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle("Conexión");
        if (side == 0) {
            b.setMessage(state + "\n\nEste es " + firstPhone() + " y espera la conexión.\nSu IP es " + Link.localIp()
                    + "\n\nSi " + secondPhone() + " no lo encuentra solo, escribe esa IP en él.")
             .setPositiveButton("Listo", null);
        } else {
            b.setMessage(state + "\n\nEste es " + secondPhone() + " y busca a " + firstPhone().replace("el teléfono", "el") + " en el WiFi. "
                    + "Si no lo encuentra, escribe la IP que muestra " + firstPhone() + ".")
             .setView(box)
             .setPositiveButton("Conectar", (d, w) -> {
                 String v = ip.getText().toString().trim();
                 if (v.isEmpty()) return;
                 prefs.edit().putString("lastIp", v).apply();
                 linkInfo = "Conectando con " + v + "…";
                 updateStatus();
                 hub.link.connectTo(v);
             })
             .setNegativeButton("Cancelar", null);
        }
        b.show();
    }

    // ---------------- elementos ----------------

    private void addItem(Item it) {
        desk.items.add(it);
        desk.invalidate();
        onItemChanged(it);
    }

    private void addNote() {
        Item it = new Item();
        it.w = sized(0.62);
        it.color = PAPER[colorIdx++ % PAPER.length];
        double[] p = desk.spot(it.w);
        it.x = p[0]; it.y = p[1];
        it.rot = (float) (Math.random() * 4 - 2);
        addItem(it);
        onEditNote(it);
    }

    private void addText(String text) {
        Matcher m = URL.matcher(text);
        Item it = new Item();
        it.w = sized(0.62);
        if (m.find()) {
            it.kind = Item.LINK;
            it.url = m.group();
            String rest = text.replace(it.url, "").trim();
            it.title = rest.length() > 140 ? rest.substring(0, 140) : rest;
            it.w = sized(0.66);
        } else {
            it.text = text.length() > 2000 ? text.substring(0, 2000) : text;
            it.color = PAPER[colorIdx++ % PAPER.length];
            it.rot = (float) (Math.random() * 4 - 2);
        }
        double[] p = desk.spot(it.w);
        it.x = p[0]; it.y = p[1];
        addItem(it);
    }

    @Override public void onEditNote(final Item it) {
        final EditText e = new EditText(this);
        e.setText(it.text);
        e.setSelection(e.getText().length());
        e.setMinLines(3);
        e.setGravity(Gravity.TOP);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(22), dp(8), dp(22), 0);
        box.addView(e, new LinearLayout.LayoutParams(-1, -2));
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("Nota")
                .setView(box)
                .setPositiveButton("Guardar", (dlg, w) -> {
                    String t = e.getText().toString();
                    it.text = t.length() > 2000 ? t.substring(0, 2000) : t;
                    it.touch();
                    desk.invalidate();
                    onItemChanged(it);
                })
                .setNegativeButton("Cancelar", null)
                .create();
        d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        d.show();
    }

    @Override public void onOpenLink(Item it) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(it.url))); }
        catch (Exception ex) { Toast.makeText(this, "No hay una app para abrir ese enlace", Toast.LENGTH_SHORT).show(); }
    }

    @Override public void onItemChanged(Item it) {
        JSONObject m = new JSONObject();
        try { m.put("t", "up"); m.put("item", it.toJson(false)); } catch (JSONException ignored) { }
        hub.send(m);
        scheduleSave();
    }

    @Override public void onItemDeleted(Item it) {
        JSONObject m = new JSONObject();
        try { m.put("t", "del"); m.put("id", it.id); } catch (JSONException ignored) { }
        hub.send(m);
        scheduleSave();
    }

    @Override public void onDrag(Item it, double x, double y) {
        long now = System.currentTimeMillis();
        if (now - lastDragSend < 16) return;
        lastDragSend = now;
        JSONObject m = new JSONObject();
        try { m.put("t", "drag"); m.put("id", it.id); m.put("x", x); m.put("y", y); } catch (JSONException ignored) { }
        hub.send(m);
    }

    @Override public void onDragEnd(Item it) {
        JSONObject m = new JSONObject();
        try { m.put("t", "dragEnd"); m.put("id", it.id); } catch (JSONException ignored) { }
        hub.send(m);
    }

    // ---------------- imágenes ----------------

    private void pickImage() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("image/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        try { startActivityForResult(Intent.createChooser(i, "Elige una imagen"), PICK_IMAGE); }
        catch (Exception e) { Toast.makeText(this, "No encontré una galería", Toast.LENGTH_SHORT).show(); }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == PICK_IMAGE && res == RESULT_OK && data != null && data.getData() != null) addImage(data.getData());
    }

    private void addImage(final Uri uri) {
        io.execute(() -> {
            String b64 = encodeImage(uri);
            main.post(() -> {
                if (b64 == null) { Toast.makeText(this, "No pude abrir esa imagen", Toast.LENGTH_SHORT).show(); return; }
                Item it = new Item();
                it.kind = Item.IMG; it.setImage(b64); it.w = sized(0.5);
                double[] p = desk.spot(it.w);
                it.x = p[0]; it.y = p[1];
                it.rot = (float) (Math.random() * 3 - 1.5);
                addItem(it);
                sendImage(it);
            });
        });
    }

    private String encodeImage(Uri uri) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(uri)) { BitmapFactory.decodeStream(in, null, o); }
            int sample = 1;
            while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= 1000) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            Bitmap bmp;
            try (InputStream in = getContentResolver().openInputStream(uri)) { bmp = BitmapFactory.decodeStream(in, null, o2); }
            if (bmp == null) return null;
            int rot = 0;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                int ori = new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                rot = ori == ExifInterface.ORIENTATION_ROTATE_90 ? 90 : ori == ExifInterface.ORIENTATION_ROTATE_180 ? 180
                        : ori == ExifInterface.ORIENTATION_ROTATE_270 ? 270 : 0;
            } catch (Exception ignored) { }
            int max = 900, q = 80;
            for (int i = 0; i < 6; i++) {
                float s = Math.min(1f, max / (float) Math.max(bmp.getWidth(), bmp.getHeight()));
                Matrix m = new Matrix();
                m.postScale(s, s);
                m.postRotate(rot);
                Bitmap out = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                out.compress(Bitmap.CompressFormat.JPEG, q, bos);
                if (bos.size() < 220_000 || i == 5) return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
                max = (int) (max * 0.8); q -= 8;
            }
        } catch (Exception ignored) { }
        return null;
    }

    // ---------------- compartir desde otras apps ----------------

    @SuppressWarnings("deprecation")
    private void handleShare(Intent i) {
        if (i == null || !Intent.ACTION_SEND.equals(i.getAction())) return;
        String type = i.getType();
        if (type != null && type.startsWith("image/")) {
            Uri u = i.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) addImage(u);
        } else {
            CharSequence t = i.getCharSequenceExtra(Intent.EXTRA_TEXT);
            if (t != null && t.length() > 0) addText(t.toString());
        }
        i.setAction(Intent.ACTION_MAIN);
    }

    // ---------------- red ----------------

    @Override public void onState(int state, String info) {
        linkState = state;
        linkInfo = state == Link.CONNECTED ? null : info;
        if (state == Link.WAITING && info == null) waitingSince = System.currentTimeMillis();
        if (state == Link.CONNECTED) {
            JSONObject hello = new JSONObject();
            JSONObject full = new JSONObject();
            try {
                hello.put("t", "hello"); hello.put("side", side);
                JSONArray arr = new JSONArray();
                for (Item it : desk.items) arr.put(it.toJson(false));
                full.put("t", "full"); full.put("items", arr);
            } catch (JSONException ignored) { }
            hub.send(hello);
            hub.send(full);
        }
        if (state != Link.CONNECTED) { otherSide = -1; desk.clearAllRemoteDrags(); requested.clear(); }
        updateStatus();
    }

    @Override public void onMessage(JSONObject m) {
        String t = m.optString("t");
        switch (t) {
            case "hello":
                otherSide = m.optInt("side", -1);
                updateStatus();
                break;
            case "full": {
                JSONArray arr = m.optJSONArray("items");
                if (arr != null) for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o != null) merge(Item.fromJson(o), false);
                }
                desk.invalidate();
                scheduleSave();
                break;
            }
            case "up": {
                JSONObject o = m.optJSONObject("item");
                if (o != null) { Item in = Item.fromJson(o); desk.clearRemoteDrag(in.id); merge(in, true); desk.invalidate(); scheduleSave(); }
                break;
            }
            case "del": {
                Item it = desk.find(m.optString("id"));
                if (it != null) { desk.items.remove(it); desk.invalidate(); scheduleSave(); }
                break;
            }
            case "drag":
                desk.setRemoteDrag(m.optString("id"), m.optDouble("x", 0), m.optDouble("y", 0));
                break;
            case "dragEnd":
                desk.clearRemoteDrag(m.optString("id"));
                break;
            case "needImg": {
                Item it = desk.find(m.optString("id"));
                if (it != null) sendImage(it);
                break;
            }
            case "img": {
                String id = m.optString("id"), ik = m.optString("ik"), data = m.optString("img");
                Item it = desk.find(id);
                requested.remove(id);
                if (it == null) { pendingImg.put(id, new String[]{ik, data}); break; }
                if (ik.equals(it.ik) || it.ik.isEmpty()) { it.img = data; it.ik = ik; it.bmp = null; desk.invalidate(); scheduleSave(); }
                break;
            }
            case "launchAck": {
                int r = m.optInt("r");
                String label = m.optString("label");
                String msg = r == Hub.LAUNCHED ? "Abriendo " + label + " en el otro teléfono"
                        : r == Hub.NOTIFIED ? "Toca el aviso en el otro teléfono para abrir " + label
                        : label + " no está instalada en el otro teléfono";
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
                break;
            }
        }
    }

    /** Manda la imagen de un elemento una sola vez (los movimientos ya no la incluyen). */
    private void sendImage(Item it) {
        if (it.img.isEmpty()) return;
        JSONObject m = new JSONObject();
        try { m.put("t", "img"); m.put("id", it.id); m.put("ik", it.ik); m.put("img", it.img); } catch (JSONException ignored) { }
        hub.send(m);
    }

    private void requestImageIfMissing(Item it) {
        String[] p = pendingImg.remove(it.id);
        if (p != null && p[0].equals(it.ik)) { it.img = p[1]; it.bmp = null; return; }
        if (it.missingImage() && requested.add(it.id)) hub.send("needImg", "id", it.id);
    }

    /**
     * live = cambio recibido en este momento desde el otro teléfono: siempre gana
     * (antes se comparaban relojes de ambos teléfonos y, si no estaban sincronizados,
     * el cambio se descartaba y el elemento quedaba "perdido" fuera de pantalla).
     * Al sincronizar todo tras reconectar, gana la versión con más cambios.
     */
    private void merge(Item in, boolean live) {
        desk.normalize(in);
        Item cur = desk.find(in.id);
        if (cur == null) { desk.items.add(in); requestImageIfMissing(in); return; }
        if (live && dragging(cur)) return;
        if (live || in.rev > cur.rev || (in.rev == cur.rev && in.u > cur.u)) cur.copyFrom(in);
        requestImageIfMissing(cur);
    }

    private boolean dragging(Item it) { return desk.isDragging(it); }

    // ---------------- guardado local ----------------

    private final Runnable saveTask = () -> {
        JSONArray arr = new JSONArray();
        for (Item it : new ArrayList<>(desk.items)) arr.put(it.toJson());
        final String data = arr.toString();
        io.execute(() -> {
            File f = new File(getFilesDir(), "desk.json");
            File tmp = new File(getFilesDir(), "desk.json.tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(data.getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) { return; }
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(f);
        });
    };

    private void scheduleSave() {
        main.removeCallbacks(saveTask);
        main.postDelayed(saveTask, 600);
    }

    private void loadItems() {
        File f = new File(getFilesDir(), "desk.json");
        if (!f.exists()) return;
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            JSONArray arr = new JSONArray(new String(bos.toByteArray(), StandardCharsets.UTF_8));
            for (int i = 0; i < arr.length(); i++) desk.items.add(Item.fromJson(arr.getJSONObject(i)));
        } catch (Exception ignored) { }
    }
}
