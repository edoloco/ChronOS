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
        desk.setDeskColor(getSharedPreferences("mesa", MODE_PRIVATE).getInt("deskColor", 0), night);
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



        // REGLA DE DISPOSICIÓN (fija para todas las versiones):
        //   horizontal → principal ARRIBA, extendido ABAJO; los elementos cruzan de arriba hacia abajo.
        //   vertical   → principal a la IZQUIERDA, extendido a la DERECHA; cruzan de izquierda a derecha.
        // El uso principal es en horizontal. El principal define la orientación y el extendido la copia.
        applyOrientation(this);
        orient = new OrientationEventListener(this) {
            @Override public void onOrientationChanged(int o) { onPhysicalOrientation(o); }
        };
        setContentView(root);
        hub = Hub.get(this);
        loadItems();
        boolean savedStacked = prefs.getBoolean("savedStacked", false);
        if (!prefs.getBoolean("layoutV2", false)) {
            // versiones anteriores tenían el principal a la derecha: se intercambian las mitades una vez
            if (!savedStacked) desk.swapHalves();
            prefs.edit().putBoolean("layoutV2", true).apply();
            scheduleSave();
        }
        desk.setVertical(savedStacked);
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

    /*
     * REGLA DE DISPOSICIÓN (fija): los teléfonos se unen por el lado largo.
     *   Horizontal: principal ARRIBA, extendido ABAJO (y va de 0 a 2; pantalla 0 = principal).
     *   Vertical:   principal a la IZQUIERDA, extendido a la DERECHA (x va de 0 a 2; pantalla 0 = principal).
     * Es el mismo armado físico girado: la orientación de la pantalla decide la disposición.
     */
    private boolean locked() { return prefs.getBoolean("viewLock", true); }
    private boolean landscapeNow() { return getResources().getConfiguration().orientation != Configuration.ORIENTATION_PORTRAIT; }
    private boolean stacked() { return landscapeNow(); }
    /** El principal siempre ocupa la primera pantalla (arriba o izquierda). */
    private int deskSide() { return side == 1 ? 0 : 1; }
    private int layoutIcon() { return stacked() ? Ui.LAYOUT_STACK : Ui.LAYOUT_SIDE_V; }
    private String layoutName() {
        return stacked() ? "Horizontal: principal arriba, extendido abajo" : "Vertical: principal a la izquierda, extendido a la derecha";
    }

    /**
     * Orientación de pantalla. Principal: la elegida (horizontal por defecto) o libre si la vista no está bloqueada.
     * Extendido: siempre la misma que informó el principal. La usan también el teclado y otras pantallas.
     */
    static void applyOrientation(Activity a) {
        SharedPreferences p = a.getSharedPreferences("mesa", MODE_PRIVATE);
        boolean h = p.getBoolean("orientH", true), lock = p.getBoolean("viewLock", true), principal = p.getInt("side", -1) == 1;
        if (principal && !lock) a.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR);
        else a.setRequestedOrientation(h ? ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
    }

    /** Elige orientación y bloqueo (en el principal) y los comparte con el extendido. */
    private void setView(boolean horizontal, boolean lock, boolean fromRemote) {
        prefs.edit().putBoolean("orientH", horizontal).putBoolean("viewLock", lock).apply();
        snapped = -1;
        applyOrientation(this);
        relayout();
        if (!fromRemote) sendView();
    }

    /** Ajusta la mesa a la orientación actual (convierte posiciones si cambió entre horizontal y vertical). */
    private void relayout() {
        if (desk.isVertical() != stacked()) {
            desk.convertAll(stacked());
            prefs.edit().putBoolean("savedStacked", stacked()).apply();
            scheduleSave();
        }
        applySide();
    }

    private void sendView() {
        if (side != 1) return;
        boolean h = locked() ? prefs.getBoolean("orientH", true) : landscapeNow();
        hub.send("view", "h", h, "lock", locked());
        hub.send("bg", "c", prefs.getInt("deskColor", 0));
    }

    @Override public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        relayout();
        if (side == 1 && !locked()) sendView();   // vista libre: el extendido sigue al principal al girar
    }

    /** Ancho inicial de un elemento: en horizontal la pantalla es más ancha, así que se usa la mitad. */
    private double sized(double base) { return stacked() ? base * 0.5 : base; }
    private String sideName(int s) { return s == 1 ? "PRINCIPAL" : "EXTENDIDO"; }
    private String firstPhone() { return "el extendido"; }
    private String secondPhone() { return "el principal"; }

    // ---------------- pantalla de inicio ----------------

    private boolean isLauncher() {
        return getPackageManager().getComponentEnabledSetting(new android.content.ComponentName(this, getPackageName() + ".Escritorio"))
                == android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
    }

    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() {
        if (isLauncher()) return;
        super.onBackPressed();
    }

    /** Activa o desactiva Mesa Doble como pantalla de inicio (launcher). No necesita permisos especiales. */
    private void toggleLauncher() {
        boolean on = !isLauncher();
        getPackageManager().setComponentEnabledSetting(new android.content.ComponentName(this, getPackageName() + ".Escritorio"),
                on ? android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED : android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                android.content.pm.PackageManager.DONT_KILL_APP);
        if (on) {
            new Ui.Sheet(this)
                    .title("Elige Mesa Doble como inicio")
                    .message("Se abrirá la lista de apps de inicio: elige Mesa Doble. Desde ahí el botón Inicio te trae a la mesa "
                            + "y las apps se abren desde el botón Apps.\n\nEn Xiaomi, si la navegación por gestos deja de funcionar con "
                            + "otro inicio, usa los botones de navegación.")
                    .button("Abrir ajustes", true, () -> openSettings(android.provider.Settings.ACTION_HOME_SETTINGS, null, "Elige Mesa Doble"))
                    .show();
        } else {
            Toast.makeText(this, "Mesa Doble ya no es la pantalla de inicio", Toast.LENGTH_SHORT).show();
            try { startActivity(new Intent(android.provider.Settings.ACTION_HOME_SETTINGS)); } catch (Exception ignored) { }
        }
    }

    // ---------------- color de fondo ----------------

    static final int[] COLORS = {0, 0xFF18212B, 0xFF0F2A44, 0xFF173A2E, 0xFF3B1622, 0xFF2A2A2E,
            0xFFDDE3E8, 0xFFE9DFC9, 0xFFE4E0F2, 0xFFDDEBDD, 0xFFF3D9CF, 0xFFFFFFFF};

    private void chooseColor() {
        int cur = prefs.getInt("deskColor", 0);
        final android.app.Dialog[] dlg = new android.app.Dialog[1];
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        for (int r = 0; r < 2; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER);
            for (int i = r * 6; i < r * 6 + 6; i++) {
                final int col = COLORS[i];
                View sw = new View(this);
                GradientDrawable g = new GradientDrawable();
                g.setShape(GradientDrawable.OVAL);
                g.setColor(col == 0 ? (night ? 0xFF18212B : 0xFFDDE3E8) : col);
                g.setStroke(dp(col == cur ? 4 : 1), col == cur ? (night ? 0xFF5CC3CF : 0xFF1F7F8C) : 0x55888888);
                sw.setBackground(g);
                sw.setContentDescription(col == 0 ? "Según el tema" : "Color");
                sw.setOnClickListener(v -> { setDeskColor(col, false); if (dlg[0] != null) dlg[0].dismiss(); });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(44), dp(44));
                lp.setMargins(dp(6), dp(6), dp(6), dp(6));
                row.addView(sw, lp);
            }
            grid.addView(row);
        }
        dlg[0] = new Ui.Sheet(this)
                .title("Color de fondo")
                .message("Se aplica en ambos teléfonos. El primero sigue el modo claro u oscuro del sistema.")
                .view(grid)
                .button("Cerrar", false, null)
                .show();
    }

    private void setDeskColor(int c, boolean fromRemote) {
        prefs.edit().putInt("deskColor", c).apply();
        desk.setDeskColor(c, night);
        if (!fromRemote) hub.send("bg", "c", c);
    }

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
        boolean auto = hub.autoKeyboard(), principal = side == 1;
        boolean h = prefs.getBoolean("orientH", true);
        Ui.Sheet sh = new Ui.Sheet(this).title("Ajustes");
        if (principal) {
            sh.option(h ? Ui.LAYOUT_STACK : Ui.LAYOUT_SIDE_V, deskSide(), h ? "Orientación: horizontal" : "Orientación: vertical",
                    h ? "Principal arriba, extendido abajo" : "Principal a la izquierda, extendido a la derecha", false, this::chooseOrientation);
            sh.option(locked() ? Ui.LOCK : Ui.UNLOCK, -1, locked() ? "Vista bloqueada" : "Vista libre",
                    locked() ? "No gira aunque muevas los teléfonos" : "Cambia sola entre horizontal y vertical al girar", false,
                    () -> setView(h, !locked(), false));
        } else {
            sh.option(layoutIcon(), deskSide(), "Orientación: la del principal", layoutName(), false, null);
        }
        sh.option(layoutIcon(), deskSide(), "Este teléfono es el " + (principal ? "principal" : "extendido"),
                        principal ? "Aquí corren las apps" : "Pantalla extra y teclado", false, this::chooseSide)
                .option(Ui.AUTO, -1, "Teclado automático: " + (auto ? "sí" : "no"),
                        "El teclado aparece solo en el extendido al escribir", false, () -> {
                            prefs.edit().putBoolean("autoKb", !auto).apply();
                            Toast.makeText(this, !auto ? "El teclado se abrirá solo en el extendido" : "Teclado automático desactivado", Toast.LENGTH_SHORT).show();
                        })
                .option(Ui.PALETTE, "Color de fondo", "Elegir el color de la mesa", this::chooseColor)
                .option(Ui.HOME, isLauncher() ? "Pantalla de inicio: sí" : "Pantalla de inicio: no",
                        isLauncher() ? "Mesa Doble es tu launcher (tocar para dejar de usarla)" : "Usar Mesa Doble como launcher de este teléfono", this::toggleLauncher)
                .option(Ui.GEAR, "Revisar configuración", principal ? "Permisos del principal" : "Permisos del extendido", () -> checkSetup(true))
                .option(Ui.WIFI, "Conexión", statusText, this::showConnection)
                .button("Cerrar", false, null)
                .show();
    }

    /** Horizontal o vertical, con dibujos de cómo van los teléfonos. */
    private void chooseOrientation() {
        boolean h = prefs.getBoolean("orientH", true);
        new Ui.Sheet(this)
                .title("Orientación")
                .message("P = principal (corren las apps) · E = extendido (pantalla extra y teclado). "
                        + "Los elementos cruzan por el borde donde se tocan los teléfonos.")
                .option(Ui.LAYOUT_STACK, 0, "Horizontal", "Principal arriba, extendido abajo. La forma de uso principal.", h,
                        () -> setView(true, true, false))
                .option(Ui.LAYOUT_SIDE_V, 0, "Vertical", "Principal a la izquierda, extendido a la derecha.", !h,
                        () -> setView(false, true, false))
                .button("Cerrar", false, null)
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
        Ui.Sheet sh = new Ui.Sheet(this)
                .title(hub.isPrincipal() ? "Configurar el principal" : "Configurar el extendido")
                .message(fixes.isEmpty() ? "Todo listo." : "Toca lo que falta para resolverlo. En teléfonos Xiaomi revisa también: "
                        + "Ajustes → Apps → Mesa Doble → Otros permisos → \"Mostrar ventanas emergentes en segundo plano\".");
        int fi = 0;
        for (String l : lines) {
            boolean ok = l.startsWith("✓"), bad = l.startsWith("✗");
            String text = l.substring(1).trim();
            Runnable r = bad ? fixes.get(fi++) : null;
            sh.option(ok ? Ui.CHECK : bad ? Ui.CROSS : Ui.GEAR, text, bad ? "Tocar para resolver" : null, r);
        }
        sh.button(fixes.isEmpty() ? "Listo" : "Después", !fixes.isEmpty() ? false : true, null).show();
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
        new Ui.Sheet(this)
                .title("Cambio automático de teclado")
                .message("Mesa Doble usa un servicio de accesibilidad solo para cambiar al teclado Mesa Doble "
                        + "(y devolver el tuyo al desconectarse) y para saber cuándo abres una app de chat. No lee tu pantalla.")
                .option(Ui.LOCK, "1. Permitir ajustes restringidos", "Si aparece bloqueado: Info de la app → menú ⋮", () ->
                        openSettings(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()),
                                "Menú ⋮ → Permitir ajustes restringidos"))
                .option(Ui.GEAR, "2. Activar en Accesibilidad", "Apps descargadas → Mesa Doble", () ->
                        openSettings(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS, null, "Busca Mesa Doble en Apps descargadas y actívalo."))
                .button("Después", false, null)
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
            new Ui.Sheet(this)
                    .title("No está instalada en el principal")
                    .message("Las apps se abren en el principal.")
                    .option(Ui.STORE, "Buscar en Play Store", null, () -> {
                        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + pkg))); }
                        catch (Exception e) { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=" + pkg))); }
                    })
                    .button("Cerrar", false, null)
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
        new Ui.Sheet(this)
                .title("Teclado")
                .option(Ui.KEYBOARD, "Usar este teléfono como teclado", "Teclado, touchpad, gamepad o dictado",
                        () -> startActivity(new Intent(this, KeyboardActivity.class)))
                .option(Ui.RECEIVE, "Recibir aquí lo que escribo", "Elegir el teclado Mesa Doble en este teléfono", this::setupReceiver)
                .button("Cerrar", false, null)
                .show();
    }

    private boolean imeEnabled() { return Hub.imeEnabled(this); }

    private void setupReceiver() {
        if (Hub.imeSelected(this)) {
            new Ui.Sheet(this)
                    .title("Listo para recibir")
                    .message("El teclado Mesa Doble ya está elegido. Abre cualquier app, toca donde se escribe y escribe desde el otro teléfono. "
                            + "Para volver a tu teclado normal toca \"Cambiar teclado\" en la barra.")
                    .button("Cambiar teclado", false, () -> startActivity(new Intent(this, ImePickerActivity.class)))
                    .button("Entendido", true, null)
                    .show();
            return;
        }
        if (!imeEnabled()) {
            new Ui.Sheet(this)
                    .title("Activa el teclado Mesa Doble")
                    .message("Se abrirá la lista de teclados. Activa \"Mesa Doble\" y vuelve aquí. Android muestra un aviso general "
                            + "sobre teclados: Mesa Doble solo escribe lo que mandas desde tu otro teléfono y no guarda nada.")
                    .button("Cancelar", false, null)
                    .button("Abrir ajustes", true, () -> openSettings(android.provider.Settings.ACTION_INPUT_METHOD_SETTINGS, null,
                            "Activa \"Mesa Doble\" y vuelve."))
                    .show();
            return;
        }
        new Ui.Sheet(this)
                .title("Elige \"Mesa Doble\"")
                .message("En la lista que aparece elige Mesa Doble. Al tocar donde se escribe verás una barra delgada en vez del teclado.")
                .button("Cancelar", false, null)
                .button("Elegir teclado", true, () -> startActivity(new Intent(this, ImePickerActivity.class)))
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
        int ds = deskSide();
        desk.setVertical(stacked());
        desk.setSide(ds);
        // la barra va en el borde exterior, lejos de la unión entre teléfonos
        FrameLayout.LayoutParams bp = (FrameLayout.LayoutParams) bar.getLayoutParams();
        if (stacked()) {
            bp.gravity = (ds == 0 ? Gravity.TOP : Gravity.BOTTOM) | Gravity.START;
            bp.setMargins(dp(14), dp(12), dp(14), dp(12));
        } else {
            bp.gravity = Gravity.BOTTOM | (ds == 0 ? Gravity.START : Gravity.END);
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
        int ic = layoutIcon();
        new Ui.Sheet(this)
                .title("¿Qué es este teléfono?")
                .message("En horizontal el principal va arriba; en vertical, a la izquierda.")
                .option(ic, 0, "Principal", "Arriba en horizontal · a la izquierda en vertical · aquí corren las apps", side == 1, () -> setSide(1))
                .option(ic, 1, "Extendido", "Abajo en horizontal · a la derecha en vertical · pantalla extra y teclado", side == 0, () -> setSide(0))
                .cancelable(side >= 0)
                .show();
    }

    private void setSide(int s) {
        side = s;
        prefs.edit().putInt("side", s).apply();
        applyOrientation(this);
        relayout();
        if (s == 1) sendView();
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
        final EditText ip = field("Ej: 192.168.1.20");
        ip.setInputType(InputType.TYPE_CLASS_PHONE);
        ip.setText(prefs.getString("lastIp", ""));
        Ui.Sheet sh = new Ui.Sheet(this).title("Conexión");
        if (side == 0) {
            sh.message(statusText + "\n\nEste es el extendido y espera la conexión. Su IP es " + Link.localIp()
                    + ". Si el principal no lo encuentra solo, escribe esa IP en el principal.")
              .button("Listo", true, null);
        } else {
            sh.message(statusText + "\n\nEl principal busca al extendido en el WiFi. Si no lo encuentra, escribe la IP que muestra el extendido.")
              .view(ip)
              .button("Cancelar", false, null)
              .button("Conectar", true, () -> {
                  String v = ip.getText().toString().trim();
                  if (v.isEmpty()) return;
                  prefs.edit().putString("lastIp", v).apply();
                  linkInfo = "Conectando con " + v + "…";
                  updateStatus();
                  hub.link.connectTo(v);
              });
        }
        sh.show();
    }

    /** Campo de texto con el estilo de la app. */
    private EditText field(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setTextSize(17);
        e.setTextColor(night ? 0xFFE6EDF3 : 0xFF1D2733);
        e.setHintTextColor(night ? 0xFF6F8294 : 0xFF8A99A8);
        e.setPadding(dp(16), dp(12), dp(16), dp(12));
        GradientDrawable g = new GradientDrawable();
        g.setColor(night ? 0xFF253445 : 0xFFFFFFFF);
        g.setCornerRadius(dp(16));
        g.setStroke(dp(1), night ? 0xFF33465A : 0xFFD8E0E7);
        e.setBackground(g);
        return e;
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
        final EditText e = field("Escribe tu nota");
        e.setText(it.text);
        e.setSelection(e.getText().length());
        e.setMinLines(3);
        e.setMaxLines(8);
        e.setGravity(Gravity.TOP);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        android.app.Dialog d = new Ui.Sheet(this)
                .title("Nota")
                .view(e)
                .button("Cancelar", false, null)
                .button("Guardar", true, () -> {
                    String t = e.getText().toString();
                    it.text = t.length() > 2000 ? t.substring(0, 2000) : t;
                    it.touch();
                    desk.invalidate();
                    onItemChanged(it);
                })
                .show();
        if (d.getWindow() != null) d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        e.requestFocus();
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
            if (side == 1) sendView();   // el principal define la forma; va antes que los elementos
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
            case "bg":
                if (m.optInt("c") != prefs.getInt("deskColor", 0)) setDeskColor(m.optInt("c"), true);
                break;
            case "view":   // el extendido sigue la orientación del principal
                if (side != 1 && m.has("h")) {
                    boolean hh = m.optBoolean("h", true), lk = m.optBoolean("lock", true);
                    if (hh != prefs.getBoolean("orientH", true) || lk != locked()) setView(hh, lk, true);
                }
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
