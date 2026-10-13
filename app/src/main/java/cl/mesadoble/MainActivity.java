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
    private SquareButton statusBtn, menuBtn;
    private final List<SquareButton> squares = new ArrayList<>();
    private final List<SquareButton> tools = new ArrayList<>();   // los botones que se pliegan dentro del menú
    private boolean barOpen = true;
    private String otherVersion = "";
    private SquareButton settingsBtn;
    private LinearLayout dock, navPill;
    // ---- ChronOS
    private FrameLayout rootView, startOverlay;
    private ChronoBar cbar;
    private FilePane pane;
    private NotifCard barNotif, floatNotif;
    private BarPlayer barPlayer;
    private LinearLayout navRow, startPanel;
    private boolean fileMode;
    // widgets de Android
    private static final int REQ_BIND = 31, REQ_CONF = 32;
    private FrameLayout widgetLayer;
    private Item pendingWidget;
    private int pendingWidgetId = -1;
    private boolean pendingWidgetNew;
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
        CardTheme.load(this);

        FrameLayout root = new FrameLayout(this);
        desk = new DeskView(this, night);
        desk.setDeskColor(getSharedPreferences("mesa", MODE_PRIVATE).getInt("deskColor", 0), night);
        desk.setHost(this);
        root.addView(desk, new FrameLayout.LayoutParams(-1, -1));
        widgetLayer = new FrameLayout(this);
        widgetLayer.setClipChildren(false);
        root.addView(widgetLayer, new FrameLayout.LayoutParams(-1, -1));

        bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(dp(6), dp(6), dp(6), dp(6));
        bar.setBackground(barBg());
        bar.setElevation(dp(CardTheme.isIntense() ? 16 : 6));
        bar.setLayoutTransition(new android.animation.LayoutTransition());
        barOpen = prefs.getBoolean("barOpen", true);
        menuBtn = new SquareButton(this, SquareButton.MENU, "Menú", false, night);
        menuBtn.setOnClickListener(v -> toggleBar());
        squares.add(menuBtn);
        addSquare(SquareButton.TOOLS, "Herramientas", true, v -> toolsMenu());
        addSquare(SquareButton.APPS, "Apps", false, v -> showApps());
        addSquare(SquareButton.DESKS, "Mesas", false, v -> desksMenu());
        addSquare(SquareButton.KEYBOARD, "Teclado", false, v -> keyboardMenu());
        settingsBtn = addSquare(SquareButton.SETTINGS, "Ajustes", false, v -> settingsMenu());
        statusBtn = addSquare(SquareButton.STATUS, "Red", false, v -> showConnection());
        // Atrás, Inicio y Recientes del PRINCIPAL, al lado de la barra (solo en el extendido)
        navPill = new LinearLayout(this);
        navPill.setPadding(dp(6), dp(6), dp(6), dp(6));
        navPill.setBackground(barBg());
        navPill.setElevation(dp(CardTheme.isIntense() ? 16 : 6));
        int[] nk = {SquareButton.BACK, SquareButton.HOME, SquareButton.RECENTS};
        String[] nl = {"Atrás", "Inicio", "Recientes"}, nkeys = {"back", "home", "recents"};
        int navCount = Updater.isLiviana(this) ? 2 : 3;   // Recientes solo funciona con la versión completa
        for (int i = 0; i < navCount; i++) {
            final String k = nkeys[i];
            SquareButton nb = new SquareButton(this, nk[i], nl[i], false, night);
            nb.setCompact(true);   // pequeños y centrados en el borde de la pantalla
            nb.setOnClickListener(v -> {
                if (!hub.connected()) { Toast.makeText(this, "El principal no está conectado", Toast.LENGTH_SHORT).show(); return; }
                hub.send("nav", "k", k);
            });
            squares.add(nb);
            navPill.addView(nb);
        }
        dock = new LinearLayout(this);
        dock.addView(bar);
        // ChronOS: gestor de archivos (detrás de las barras), action bars y menú inicio
        rootView = root;
        pane = new FilePane(this, hub(), night);
        pane.setVisibility(View.GONE);
        root.addView(pane, new FrameLayout.LayoutParams(-1, -1));
        floatNotif = new NotifCard(this, night, true);
        floatNotif.setTap(this::onNotifTap);
        FrameLayout.LayoutParams fl = new FrameLayout.LayoutParams(dp(360), dp(64), Gravity.TOP | Gravity.START);
        fl.setMargins(dp(12), dp(36), dp(12), dp(12));
        root.addView(floatNotif, fl);
        startOverlay = new FrameLayout(this);
        startOverlay.setVisibility(View.GONE);
        startOverlay.setOnClickListener(v -> showStart(false));
        root.addView(startOverlay, new FrameLayout.LayoutParams(-1, -1));
        rebuildBar();



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
        desk.setPages(Math.max(prefs.getInt("pages", 1), maxPage() + 1), prefs.getInt("page", 0), 0);
        desk.setAppsMode(prefs.getBoolean("appsLock", false), prefs.getBoolean("appsAuto", false));
        desk.setSnap(prefs.getBoolean("snapGrid", false));
        cleanWidgets();

        side = hub.side();
        if (side >= 0) applySide();
        if (side < 0) chooseSide();   // solo la primera vez: después se recuerda (se cambia en Ajustes)
        hub.add(this);
        LinkService.start(this);
        if (android.os.Build.VERSION.SDK_INT >= 33 && !prefs.getBoolean("askedNotif", false)) {
            prefs.edit().putBoolean("askedNotif", true).apply();
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 3);
        }
        handleShare(getIntent());
        String bw = getIntent().getStringExtra("bindWidget");
        if (bw != null) bindRequested(bw, 0);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String bw = intent.getStringExtra("bindWidget");
        if (bw != null) { intent.removeExtra("bindWidget"); bindRequested(bw, 0); }
        handleShare(intent);
    }

    @Override protected void onResume() {
        super.onResume();
        hub.foreground = true;
        resumed = true;
        Updater.checkQuiet(this, false, this::showUpdateBadge);
        main.removeCallbacks(updatePoll);
        main.postDelayed(updatePoll, 10 * 60_000L);
        main.postDelayed(pollRecents, 300);
        main.postDelayed(camKeepAlive, 500);
        if (!hub.isPrincipal()) desk.setBgImage(GameBg.current);
        if (orient.canDetectOrientation()) orient.enable();
        if (hub.isPrincipal() && hub.connected() && MesaAccess.instance != null) MesaAccess.instance.switchToMesa();
    }

    @Override protected void onPause() {
        hub.foreground = false;
        resumed = false;
        main.removeCallbacks(pollRecents);
        main.removeCallbacks(camKeepAlive);
        if (!hub.isPrincipal() && hub.connected() && desk.hasCameraWidget()) hub.send("camCmd", "a", "stream", "v", false);
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
        hub.send("cardTheme", "c", CardTheme.color, "g", CardTheme.glass);
        hub.send("pages", "n", desk.pages(), "cur", desk.page());
        hub.send("apps", "lock", prefs.getBoolean("appsLock", false), "auto", prefs.getBoolean("appsAuto", false));
        hub.send("snap", "on", prefs.getBoolean("snapGrid", false));
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
        if (startOverlay != null && startOverlay.getVisibility() == View.VISIBLE) { showStart(false); return; }
        if (fileMode) { setFileMode(false, false); return; }
        if (isLauncher()) return;
        super.onBackPressed();
    }

    /** Activa o desactiva ChronOS como pantalla de inicio (launcher). No necesita permisos especiales. */
    private void toggleLauncher() {
        boolean on = !isLauncher();
        getPackageManager().setComponentEnabledSetting(new android.content.ComponentName(this, getPackageName() + ".Escritorio"),
                on ? android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED : android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                android.content.pm.PackageManager.DONT_KILL_APP);
        if (on) {
            new Ui.Sheet(this)
                    .title("Elige ChronOS como inicio")
                    .message("Se abrirá la lista de apps de inicio: elige ChronOS. Desde ahí el botón Inicio te trae a la mesa "
                            + "y las apps se abren desde el botón Apps.\n\nEn Xiaomi, si la navegación por gestos deja de funcionar con "
                            + "otro inicio, usa los botones de navegación.")
                    .button("Abrir ajustes", true, () -> openSettings(android.provider.Settings.ACTION_HOME_SETTINGS, null, "Elige ChronOS"))
                    .show();
        } else {
            Toast.makeText(this, "ChronOS ya no es la pantalla de inicio", Toast.LENGTH_SHORT).show();
            try { startActivity(new Intent(android.provider.Settings.ACTION_HOME_SETTINGS)); } catch (Exception ignored) { }
        }
    }

    // ---------------- color de fondo ----------------

    // 0 = según el tema. Oscuros, intensos, pasteles y claros.
    static final int[] COLORS = {
            0, 0xFF18212B, 0xFF0F2A44, 0xFF173A2E, 0xFF3B1622, 0xFF2A2A2E,
            0xFF000000, 0xFF1B1035, 0xFF0B3B3B, 0xFF3A2410, 0xFF2B0F0F, 0xFF102A1A,
            0xFF1F7F8C, 0xFF2E5EAA, 0xFF6A3FA0, 0xFFB03A6B, 0xFFC0532B, 0xFF3F8F4A,
            0xFF5CC3CF, 0xFF7FA7E6, 0xFFB79BE0, 0xFFF29BB8, 0xFFF4B183, 0xFF9DD6A3,
            0xFFDDE3E8, 0xFFE9DFC9, 0xFFE4E0F2, 0xFFDDEBDD, 0xFFF3D9CF, 0xFFFFFFFF,
            0xFFF6E6A2, 0xFFFFF4D6, 0xFFE0F2F1, 0xFFFCE4EC, 0xFFE3F2FD, 0xFFECEFF1};

    private void chooseColor() {
        int cur = prefs.getInt("deskColor", 0);
        final android.app.Dialog[] dlg = new android.app.Dialog[1];
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        for (int r = 0; r < COLORS.length / 6; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER);
            for (int i = r * 6; i < r * 6 + 6; i++) {
                final int col = COLORS[i];
                row.addView(swatch(col, col == cur, () -> { setDeskColor(col, false); if (dlg[0] != null) dlg[0].dismiss(); }), swatchLp());
            }
            grid.addView(row);
        }
        android.widget.ScrollView sc = new android.widget.ScrollView(this);
        sc.addView(grid);
        sc.setLayoutParams(new LinearLayout.LayoutParams(-1, Math.min(dp(300), getResources().getDisplayMetrics().heightPixels / 2)));
        dlg[0] = new Ui.Sheet(this)
                .title("Color de fondo")
                .message("Se aplica en ambos teléfonos. El primero sigue el modo claro u oscuro del sistema.")
                .view(sc)
                .option(Ui.PALETTE, "Color personalizado", "Elegir tono, intensidad y brillo", () -> customColor(cur, col -> setDeskColor(col, false)))
                .button("Cerrar", false, null)
                .show();
    }

    private View swatch(int col, boolean selected, Runnable r) {
        View sw = new View(this);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(col == 0 ? (night ? 0xFF18212B : 0xFFDDE3E8) : col);
        g.setStroke(dp(selected ? 4 : 1), selected ? (night ? 0xFF5CC3CF : 0xFF1F7F8C) : 0x55888888);
        sw.setBackground(g);
        sw.setContentDescription(col == 0 ? "Según el tema" : "Color");
        sw.setOnClickListener(v -> r.run());
        return sw;
    }

    private LinearLayout.LayoutParams swatchLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(40), dp(40));
        lp.setMargins(dp(5), dp(5), dp(5), dp(5));
        return lp;
    }

    /** Cualquier color: tono, intensidad y brillo, con vista previa en vivo. */
    private void customColor(int start, java.util.function.IntConsumer use) {
        float[] hsv = new float[3];
        android.graphics.Color.colorToHSV(start == 0 ? (night ? 0xFF18212B : 0xFFDDE3E8) : start, hsv);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        View preview = new View(this);
        GradientDrawable pg = new GradientDrawable();
        pg.setCornerRadius(dp(14));
        preview.setBackground(pg);
        box.addView(preview, new LinearLayout.LayoutParams(-1, dp(56)));
        String[] names = {"Tono", "Intensidad", "Brillo"};
        int[] max = {360, 100, 100};
        Runnable upd = () -> { int c = android.graphics.Color.HSVToColor(hsv); pg.setColor(c); preview.invalidate(); };
        for (int i = 0; i < 3; i++) {
            final int k = i;
            TextView t = new TextView(this);
            t.setText(names[i]);
            t.setTextColor(night ? 0xFFE6EDF3 : 0xFF1D2733);
            t.setPadding(0, dp(10), 0, 0);
            box.addView(t);
            android.widget.SeekBar sb = new android.widget.SeekBar(this);
            sb.setMax(max[i]);
            sb.setProgress(Math.round(k == 0 ? hsv[0] : hsv[k] * 100));
            sb.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(android.widget.SeekBar b, int p, boolean u) { hsv[k] = k == 0 ? p : p / 100f; upd.run(); }
                @Override public void onStartTrackingTouch(android.widget.SeekBar b) { }
                @Override public void onStopTrackingTouch(android.widget.SeekBar b) { }
            });
            box.addView(sb);
        }
        upd.run();
        new Ui.Sheet(this)
                .title("Color personalizado")
                .view(box)
                .button("Cancelar", false, null)
                .button("Usar", true, () -> use.accept(android.graphics.Color.HSVToColor(hsv)))
                .show();
    }

    // ---------------- color de tarjetas y menús ----------------

    static final int[] CARD_COLORS = {0xFF223040, 0xFF2A2A2E, 0xFF000000, 0xFFFFFFFF, 0xFFF1EDE4, 0xFF1F7F8C,
            0xFF2E5EAA, 0xFF6A3FA0, 0xFFB03A6B, 0xFFC0532B, 0xFF3F8F4A, 0xFFE8A33D};

    private android.graphics.drawable.Drawable barBg() {
        if (CardTheme.isAero()) return new Aero.Bg(CardTheme.aeroTint(), dp(16), getResources().getDisplayMetrics().density);
        if (CardTheme.isIntense()) return new Glass.Bg(dp(16), getResources().getDisplayMetrics().density);
        GradientDrawable g = pill(CardTheme.barFill(night));
        if (CardTheme.isGlass()) g.setStroke(dp(1), CardTheme.stroke());
        return g;
    }

    /** Tarjetas de apps y elementos, barra de opciones y ventanas de menú: automático, cristal o un color. */
    private void chooseCardColor() {
        final android.app.Dialog[] dlg = new android.app.Dialog[1];
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        for (int r = 0; r < CARD_COLORS.length / 6; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER);
            for (int i = r * 6; i < r * 6 + 6; i++) {
                final int col = CARD_COLORS[i];
                row.addView(swatch(col, !CardTheme.isGlass() && CardTheme.color == col,
                        () -> { setCardTheme(col, CardTheme.NONE, false); if (dlg[0] != null) dlg[0].dismiss(); }), swatchLp());
            }
            grid.addView(row);
        }
        dlg[0] = new Ui.Sheet(this)
                .title("Tarjetas y menús")
                .message("Color de las tarjetas de apps y elementos, de la barra de opciones y de estos menús. Se aplica en ambos teléfonos.")
                .option(Ui.PALETTE, -1, "Cristal intenso", "Vidrio esmerilado realista: fondo borroso, sombras profundas y brillo",
                        CardTheme.isIntense(), () -> setCardTheme(0, CardTheme.GLASS_INTENSE, false))
                .option(Ui.PALETTE, -1, "Cristal Aero (Windows 7)", "Vidrio de color con brillo y vetas de luz",
                        CardTheme.isAero(), this::chooseAeroTint)
                .option(Ui.PALETTE, -1, "Cristal claro", "Translúcido, como vidrio esmerilado", CardTheme.glass == CardTheme.GLASS_LIGHT,
                        () -> setCardTheme(0, CardTheme.GLASS_LIGHT, false))
                .option(Ui.PALETTE, -1, "Cristal oscuro", "Vidrio ahumado, para fondos claros u oscuros", CardTheme.glass == CardTheme.GLASS_DARK,
                        () -> setCardTheme(0, CardTheme.GLASS_DARK, false))
                .option(Ui.AUTO, -1, "Automático", "Según el modo claro u oscuro del teléfono", !CardTheme.isGlass() && CardTheme.color == 0,
                        () -> setCardTheme(0, CardTheme.NONE, false))
                .view(grid)
                .option(Ui.PALETTE, "Color personalizado", "Elegir tono, intensidad y brillo",
                        () -> customColor(CardTheme.color == 0 ? 0xFF223040 : CardTheme.color, col -> setCardTheme(col, CardTheme.NONE, false)))
                .button("Cerrar", false, null)
                .show();
    }

    /** Los colores de ventana de Windows 7. */
    private void chooseAeroTint() {
        final android.app.Dialog[] dlg = new android.app.Dialog[1];
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        for (int r = 0; r < Aero.TINTS.length / 4; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER);
            for (int i = r * 4; i < r * 4 + 4; i++) {
                final int col = Aero.TINTS[i];
                LinearLayout cell = new LinearLayout(this);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER_HORIZONTAL);
                cell.addView(swatch(col, CardTheme.isAero() && CardTheme.aeroTint() == col,
                        () -> { setCardTheme(col, CardTheme.GLASS_AERO, false); if (dlg[0] != null) dlg[0].dismiss(); }), swatchLp());
                TextView t = new TextView(this);
                t.setText(Aero.TINT_NAMES[i]);
                t.setTextSize(11);
                t.setTextColor(CardTheme.sheetNight(night) ? 0xFFE6EDF3 : 0xFF1D2733);
                cell.addView(t);
                row.addView(cell, new LinearLayout.LayoutParams(dp(70), -2));
            }
            grid.addView(row);
        }
        dlg[0] = new Ui.Sheet(this)
                .title("Cristal Aero")
                .message("Elige el color del vidrio, como en Windows 7.")
                .view(grid)
                .option(Ui.PALETTE, "Otro color", "Elegir tono, intensidad y brillo",
                        () -> customColor(CardTheme.aeroTint(), col -> setCardTheme(col, CardTheme.GLASS_AERO, false)))
                .button("Cerrar", false, null)
                .show();
    }

    private void setCardTheme(int color, int glass, boolean fromRemote) {
        CardTheme.save(this, color, glass);
        desk.setCardTheme();
        for (SquareButton b : squares) b.applyTheme();
        if (cbar != null) cbar.invalidate();
        rebuildBar();
        if (!fromRemote) hub.send("cardTheme", "c", color, "g", glass);
    }

    private void setDeskColor(int c, boolean fromRemote) {
        prefs.edit().putInt("deskColor", c).apply();
        desk.setDeskColor(c, night);
        if (!fromRemote) hub.send("bg", "c", c);
    }

    private SquareButton addSquare(int kind, String label, boolean primary, View.OnClickListener l) {
        SquareButton b = new SquareButton(this, kind, label, primary, night);
        b.setOnClickListener(l);
        tools.add(b);
        squares.add(b);
        return b;
    }

    /** Menú inicio (el logo de la barra): una cuadrícula de 4 × 3; los espacios sin nada quedan para lo que venga. */
    private void rebuildBar() {
        for (SquareButton b : tools) { b.setVisibility(View.VISIBLE); if (b.getParent() instanceof android.view.ViewGroup) ((android.view.ViewGroup) b.getParent()).removeView(b); }
        if (filesBtn == null) filesBtn = addSquare(SquareButton.FILES, "Archivos", false, v -> { showStart(false); setFileMode(!fileMode, false); });
        startPanel = new LinearLayout(this);
        startPanel.setOrientation(LinearLayout.VERTICAL);
        startPanel.setPadding(dp(14), dp(12), dp(14), dp(14));
        GradientDrawable g = new GradientDrawable();
        g.setColor(CardTheme.sheetFill(night));
        g.setCornerRadius(dp(22));
        if (CardTheme.isGlass()) g.setStroke(dp(1), CardTheme.stroke());
        startPanel.setBackground(CardTheme.isAero() ? new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{g,
                new Aero.Bg(CardTheme.aeroTint(), dp(22), getResources().getDisplayMetrics().density)}) : g);
        startPanel.setElevation(dp(16));
        startPanel.setClickable(true);
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        android.widget.ImageView logo = new android.widget.ImageView(this);
        logo.setImageResource(R.drawable.chronos);
        head.addView(logo, new LinearLayout.LayoutParams(dp(30), dp(30)));
        TextView name = new TextView(this);
        name.setText("ChronOS");
        name.setTextSize(17);
        name.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        name.setTextColor(Ui.fg(CardTheme.sheetNight(night)));
        name.setPadding(dp(8), 0, 0, 0);
        head.addView(name);
        startPanel.addView(head);
        // orden: lo más usado arriba
        SquareButton[] order = {byKind(SquareButton.APPS), settingsBtn, byKind(SquareButton.DESKS), statusBtn,
                byKind(SquareButton.TOOLS), byKind(SquareButton.KEYBOARD), filesBtn, null, null, null, null, null};
        LinearLayout row = null;
        for (int i = 0; i < order.length; i++) {
            if (i % 4 == 0) {
                row = new LinearLayout(this);
                LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-2, -2);
                rl.topMargin = dp(8);
                startPanel.addView(row, rl);
            }
            View v = order[i];
            if (v == null) {   // espacio disponible
                View e = new View(this);
                GradientDrawable eg = new GradientDrawable();
                eg.setCornerRadius(dp(12));
                eg.setStroke(dp(1), (Ui.fg(CardTheme.sheetNight(night)) & 0x00FFFFFF) | 0x33000000, dp(5), dp(4));
                e.setBackground(eg);
                v = e;
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(52), dp(52));
            if (i % 4 > 0) lp.setMarginStart(dp(10));
            row.addView(v, lp);
        }
        startOverlay.removeAllViews();
        startOverlay.addView(startPanel, new FrameLayout.LayoutParams(-2, -2));
        placeStart();
    }

    private SquareButton byKind(int k) {
        for (SquareButton b : tools) if (b.kind() == k) return b;
        return null;
    }

    private SquareButton filesBtn;

    /** El menú inicio aparece junto al logo (o junto al reloj en el principal). */
    private void placeStart() {
        if (startPanel == null || cbar == null) return;
        boolean top = cbar.edgeTop();
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2,
                (top ? Gravity.TOP : Gravity.BOTTOM) | (cbar.kind == ChronoBar.NAV ? Gravity.END : Gravity.START));
        int m = cbar.visualHeight() + dp(6);
        lp.setMargins(dp(12), top ? m : 0, dp(12), top ? 0 : m);
        startPanel.setLayoutParams(lp);
        startPanel.setPivotX(cbar.kind == ChronoBar.NAV ? dp(260) : 0);
        startPanel.setPivotY(top ? 0 : dp(240));
    }

    private void showStart(boolean on) {
        if (on == (startOverlay.getVisibility() == View.VISIBLE)) return;
        if (on) {
            placeStart();
            startOverlay.setVisibility(View.VISIBLE);
            startPanel.setScaleX(0.85f); startPanel.setScaleY(0.85f); startPanel.setAlpha(0f);
            startPanel.animate().scaleX(1).scaleY(1).alpha(1).setDuration(180).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
        } else {
            startPanel.animate().scaleX(0.9f).scaleY(0.9f).alpha(0).setDuration(130)
                    .withEndAction(() -> startOverlay.setVisibility(View.GONE)).start();
        }
    }

    // ---------------- ChronOS: action bars ----------------

    private Hub hub() { return hub != null ? hub : Hub.get(this); }

    /** Una barra por teléfono: reloj en el principal, navegación y logo en el extendido. */
    private void buildChronoBar() {
        int kind = side == 1 ? ChronoBar.CLOCK : ChronoBar.NAV;
        if (cbar != null && cbar.kind == kind) return;
        if (cbar != null) rootView.removeView(cbar);
        cbar = new ChronoBar(this, kind, night);
        cbar.setListener(new ChronoBar.Listener() {
            @Override public void onBarSwipe(ChronoBar b, boolean down) {
                boolean toggle = !stacked() || (b.edgeTop() == down);   // se lleva la barra al otro extremo
                if (toggle) setFileMode(!fileMode, false);
            }
            @Override public void onClockTap() { hub.openAlarms(); }
            @Override public void onLogoTap() { showStart(startOverlay.getVisibility() != View.VISIBLE); }
            @Override public void onLogoLong() { showStart(true); }
        });
        if (kind == ChronoBar.NAV) {
            navRow = new LinearLayout(this);
            navRow.setGravity(Gravity.CENTER);
            while (navPill.getChildCount() > 0) {
                View v = navPill.getChildAt(0);
                navPill.removeViewAt(0);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(26), dp(26));
                lp.setMargins(dp(10), 0, dp(10), 0);
                navRow.addView(v, lp);
            }
            cbar.setNavRow(navRow);
            barPlayer = new BarPlayer(this, hub, ChronoBar.ink(night));
            cbar.setPlayer(barPlayer);
        } else {
            if (navRow != null) {   // vuelve a ser principal: los botones vuelven a su lugar
                while (navRow.getChildCount() > 0) { View v = navRow.getChildAt(0); navRow.removeViewAt(0); navPill.addView(v); }
            }
            barNotif = new NotifCard(this, night, false);
            barNotif.setTap(this::onNotifTap);
            cbar.setNotif(barNotif);
        }
        int at = rootView.indexOfChild(startOverlay);
        rootView.addView(cbar, at, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
    }

    /** Dónde va la barra: reloj arriba y navegación abajo; con el gestor abierto (en horizontal), las dos junto a la unión. */
    private boolean barAtTop() {
        boolean principal = side == 1;
        boolean atTop = principal;
        if (fileMode && stacked()) atTop = !atTop;
        return atTop;
    }

    private void layoutChrono(boolean animate) {
        if (cbar == null) return;
        int H = rootView.getHeight();
        if (H == 0) { rootView.post(() -> layoutChrono(false)); return; }
        boolean top = barAtTop();
        int bh = cbar.barHeight();
        float y = top ? 0 : H - bh;
        // el gestor ocupa lo que la barra deja libre
        int pTop, pBottom;
        int vh = cbar.visualHeight();
        if (top) { pTop = vh; pBottom = 0; } else { pTop = 0; pBottom = vh; }
        pane.setPadding(0, pTop, 0, pBottom);
        // la tarjeta de notificación del extendido va arriba a la izquierda, sin taparse con la barra
        FrameLayout.LayoutParams fl = (FrameLayout.LayoutParams) floatNotif.getLayoutParams();
        fl.topMargin = top ? vh + dp(8) : dp(14);
        floatNotif.setLayoutParams(fl);
        // el gestor entra junto con la barra: en el principal desde arriba, en el extendido desde abajo
        float out = stacked() ? (side == 1 ? -H : H) : H;
        if (!animate) {
            cbar.setEdgeTop(top);
            cbar.setTranslationY(y);
            pane.setVisibility(fileMode ? View.VISIBLE : View.GONE);
            pane.setTranslationY(0);
            placeStart();
            return;
        }
        android.view.animation.Interpolator in = new android.view.animation.DecelerateInterpolator(1.4f);
        cbar.setEdgeTop(top);
        cbar.animate().translationY(y).setDuration(380).setInterpolator(in).start();
        if (fileMode) {
            pane.setVisibility(View.VISIBLE);
            pane.setTranslationY(out);
            pane.animate().translationY(0).setDuration(380).setInterpolator(in).start();
        } else {
            pane.animate().translationY(out).setDuration(320).setInterpolator(in)
                    .withEndAction(() -> { if (!fileMode) pane.setVisibility(View.GONE); }).start();
        }
        placeStart();
    }

    /** Abre o cierra el gestor de archivos en los dos teléfonos. */
    private void setFileMode(boolean on, boolean fromRemote) {
        if (fileMode == on) return;
        fileMode = on;
        if (on) pane.start(); else pane.stop();
        layoutChrono(true);
        if (!fromRemote && hub.connected()) hub.send("fileMode", "on", on);
    }

    private void onNotifTap(String pkg) {
        if (pkg == null || pkg.isEmpty()) return;
        openOnPrincipal(pkg, "");
    }

    private void avoidBar() { }

    private void toggleBar() {
        barOpen = !barOpen;
        prefs.edit().putBoolean("barOpen", barOpen).apply();
        for (SquareButton b : tools) b.setVisibility(barOpen ? View.VISIBLE : View.GONE);
        menuBtn.setOpen(barOpen);
        showUpdateBadge();
    }

    // ---------------- escritorios (a los lados) y apps ----------------

    private int maxPage() {
        int m = 0;
        for (Item it : desk.items) m = Math.max(m, it.page);
        return m;
    }

    private void savePages() { prefs.edit().putInt("pages", desk.pages()).putInt("page", desk.page()).apply(); }

    @Override public void onPageChange(int page, int pages) {
        savePages();
        hub.send("pages", "n", pages, "cur", page);
    }

    private void desksMenu() {
        int n = desk.pages(), cur = desk.page();
        boolean lock = prefs.getBoolean("appsLock", false), auto = prefs.getBoolean("appsAuto", false);
        Ui.Sheet sh = new Ui.Sheet(this)
                .title("Escritorios")
                .message("Estás en el " + (cur + 1) + " de " + n + ". Desliza a los lados sobre un espacio vacío para cambiar. "
                        + "Para llevar un elemento a otro escritorio, arrástralo y déjalo un momento contra el borde izquierdo o derecho.")
                .option(Ui.PLUS, "Nuevo escritorio", "Se agrega a la derecha", this::newPage);
        if (n > 1) sh.option(Ui.TRASH, "Quitar este escritorio", "Sus elementos pasan al de al lado", this::removePage);
        sh.option(Ui.ALIGN, "Ordenar y alinear", "Apps y elementos de este escritorio", this::arrangeMenu)
          .button("Cerrar", false, null)
          .show();
    }

    /** Ordenar y alinear apps y elementos del escritorio actual (se ve igual en los dos teléfonos). */
    private void arrangeMenu() {
        boolean lock = prefs.getBoolean("appsLock", false), auto = prefs.getBoolean("appsAuto", false), snap = prefs.getBoolean("snapGrid", false);
        new Ui.Sheet(this)
                .title("Ordenar y alinear")
                .option(Ui.GRID, "Ordenar todo", "En filas: apps por nombre, después reloj, batería, widgets, notas e imágenes", () -> {
                    desk.arrangeAll(); Toast.makeText(this, "Escritorio ordenado", Toast.LENGTH_SHORT).show(); })
                .option(Ui.GRID, "Ordenar solo las apps", "En cuadrícula, por nombre y del mismo tamaño", () -> {
                    desk.arrangeApps(); Toast.makeText(this, "Apps ordenadas", Toast.LENGTH_SHORT).show(); })
                .option(Ui.ALIGN, "Alinear a los puntos", "Cada elemento se ajusta a la cuadrícula, sin cambiar el orden", () -> {
                    desk.alignAll(); Toast.makeText(this, "Elementos alineados", Toast.LENGTH_SHORT).show(); })
                .option(Ui.CHECK, "Enderezar todo", "Quita el giro de todos los elementos", () -> {
                    desk.straightenAll(); Toast.makeText(this, "Todo derecho", Toast.LENGTH_SHORT).show(); })
                .option(Ui.PIN, -1, "Imán: " + (snap ? "sí" : "no"), "Al soltar un elemento, se ajusta solo a los puntos", snap, () -> {
                    prefs.edit().putBoolean("snapGrid", !snap).apply();
                    desk.setSnap(!snap);
                    hub.send("snap", "on", !snap);
                })
                .option(Ui.AUTO, -1, "Orden automático de apps: " + (auto ? "sí" : "no"), "Las apps se acomodan solas al agregarlas o soltarlas", auto,
                        () -> setAppsMode(lock, !auto, false))
                .option(lock ? Ui.LOCK : Ui.UNLOCK, -1, "Apps bloqueadas: " + (lock ? "sí" : "no"),
                        lock ? "No se mueven ni se borran; al tocarlas se abren" : "Fijar las apps en su lugar", lock,
                        () -> setAppsMode(!lock, auto, false))
                .button("Cerrar", false, null)
                .show();
    }

    private void newPage() {
        int n = desk.pages();
        if (n >= 50) return;
        desk.setPages(n + 1, n, 1);
        onPageChange(desk.page(), desk.pages());
        Toast.makeText(this, "Escritorio " + (n + 1) + " · desliza a los lados para cambiar", Toast.LENGTH_SHORT).show();
    }

    private void removePage() {
        final int cur = desk.page(), n = desk.pages();
        int count = 0;
        for (Item it : desk.items) if (it.page == cur) count++;
        Runnable go = () -> {
            int target = cur == 0 ? 0 : cur - 1;   // índices ya corridos
            for (Item it : new ArrayList<>(desk.items)) {
                if (it.page == cur) it.page = target;
                else if (it.page > cur) it.page--;
                else continue;
                it.touch();
                onItemChanged(it);
            }
            desk.setPages(n - 1, target, cur == 0 ? 1 : -1);
            onPageChange(desk.page(), desk.pages());
        };
        if (count == 0) { go.run(); return; }
        new Ui.Sheet(this)
                .title("Quitar este escritorio")
                .message("Sus " + count + (count == 1 ? " elemento pasa" : " elementos pasan") + " al escritorio "
                        + (cur == 0 ? "de la derecha" : "de la izquierda") + ".")
                .button("Cancelar", false, null)
                .button("Quitar", true, go)
                .show();
    }

    private void setAppsMode(boolean lock, boolean auto, boolean fromRemote) {
        prefs.edit().putBoolean("appsLock", lock).putBoolean("appsAuto", auto).apply();
        desk.setAppsMode(lock, auto);
        if (fromRemote) return;
        hub.send("apps", "lock", lock, "auto", auto);
        if (auto) desk.arrangeApps();
        Toast.makeText(this, lock ? "Apps bloqueadas en su lugar" : auto ? "Las apps se ordenan solas" : "Apps libres", Toast.LENGTH_SHORT).show();
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
                .option(principal ? Ui.LOCK : Ui.UNLOCK, -1, "Pantalla unida: " + (ScreenSync.enabled(this) ? "sí" : "no"),
                        "El extendido se enciende, desbloquea y bloquea junto con el principal", ScreenSync.enabled(this), () -> {
                            boolean on = !ScreenSync.enabled(this);
                            prefs.edit().putBoolean("syncScreen", on).apply();
                            hub.send("scrSync", "on", on);
                            Toast.makeText(this, on ? "Pantalla unida activada" : "Pantalla unida desactivada", Toast.LENGTH_SHORT).show();
                            if (on) main.postDelayed(() -> checkSetup(false), 300);
                        })
                .option(Ui.PALETTE, "Color de fondo", "Elegir el color de la mesa", this::chooseColor)
                .option(Ui.NOTE, -1, "Notificaciones en la barra: " + (principal ? (NotifService.enabled(this) ? "sí" : "no") : "del principal"),
                        principal ? "Muestra las notificaciones animadas en la barra del reloj (o en el extendido)"
                                  : "Se activa en el principal", principal && NotifService.enabled(this),
                        () -> {
                            if (!principal) { Toast.makeText(this, "Actívalo en el principal: Ajustes → Notificaciones", Toast.LENGTH_LONG).show(); return; }
                            openSettings("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS", null,
                                    "Activa ChronOS en \"Acceso a notificaciones\" y vuelve.");
                        })
                .option(Ui.RECEIVE, -1, "Llamadas: " + (principal ? (CallHub.ready(this) ? "listo" : "falta permiso") : "contestar aquí"),
                        principal ? "Permitir que el extendido conteste o cuelgue las llamadas de este teléfono"
                                  : "Las llamadas del principal se contestan o cuelgan desde este teléfono", principal && CallHub.ready(this),
                        () -> {
                            if (principal) requestPermissions(CallHub.PERMS, 61);
                            else Toast.makeText(this, "Actívalo en el principal: Ajustes → Llamadas", Toast.LENGTH_LONG).show();
                        })
                .option(Ui.IMAGE, -1, "Fondo de juegos: " + (GameBg.enabled(this) ? "sí" : "no"),
                        "Al abrir un juego en el principal, el extendido muestra una imagen del juego", GameBg.enabled(this), () -> {
                            boolean on = !GameBg.enabled(this);
                            prefs.edit().putBoolean("gameBg", on).apply();
                            if (!on) { GameBg.current = null; GameBg.currentPkg = ""; desk.setBgImage(null); }
                        })
                .option(Ui.PALETTE, "Color de tarjetas y menús", CardTheme.glass == CardTheme.GLASS_LIGHT ? "Cristal claro"
                        : CardTheme.glass == CardTheme.GLASS_DARK ? "Cristal oscuro" : CardTheme.isIntense() ? "Cristal intenso"
                        : CardTheme.isAero() ? "Cristal Aero" : CardTheme.color == 0 ? "Automático" : "Color propio", this::chooseCardColor)
                .option(Ui.HOME, isLauncher() ? "Pantalla de inicio: sí" : "Pantalla de inicio: no",
                        isLauncher() ? "ChronOS es tu launcher (tocar para dejar de usarla)" : "Usar ChronOS como launcher de este teléfono", this::toggleLauncher)
                .option(Ui.GEAR, "Revisar configuración", principal ? "Permisos del principal" : "Permisos del extendido", () -> checkSetup(true))
                .option(Ui.WIFI, "Conexión", statusText, this::showConnection)
                .option(Ui.NOTE, "Acerca de la app", Updater.updateAvailable(this)
                        ? "Hay una versión nueva: " + Updater.latestKnown(this) : "Versión " + Updater.clean(Updater.installedVersion(this)), this::about)
                .badgeIf(Updater.updateAvailable(this), "1")
                .button("Cerrar", false, null)
                .show();
    }

    private final Runnable updatePoll = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            Updater.checkQuiet(MainActivity.this, false, MainActivity.this::showUpdateBadge);
            main.postDelayed(this, 10 * 60_000L);
        }
    };

    /** Círculo rojo en Ajustes cuando hay una versión nueva publicada. */
    private void showUpdateBadge() {
        if (settingsBtn != null) settingsBtn.setBadge(Updater.updateAvailable(this));
        if (cbar != null) cbar.setBadge(Updater.updateAvailable(this));
    }

    /** Acerca de: versión de este teléfono y del otro, para saber si los dos están al día. */
    private void about() {
        String v = Updater.installedVersion(this);
        String kind = Updater.isLiviana(this) ? "liviana" : "completa";
        String when = "";
        try {
            long t = getPackageManager().getPackageInfo(getPackageName(), 0).lastUpdateTime;
            when = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(new java.util.Date(t));
        } catch (Exception ignored) { }
        String repo = Updater.repo(this);
        StringBuilder sb = new StringBuilder();
        sb.append("Versión: ").append(Updater.clean(v)).append(" (").append(kind).append(")\n");
        if (!when.isEmpty()) sb.append("Instalada el ").append(when).append("\n");
        sb.append("Este teléfono es el ").append(side == 1 ? "principal" : "extendido").append("\n");
        if (hub.connected()) {
            String ov = Updater.clean(otherVersion);
            sb.append("Otro teléfono: ").append(ov.isEmpty() ? "versión antigua (no informa su versión)" : ov);
            if (!ov.isEmpty() && !ov.equals(Updater.clean(v))) sb.append("  ⚠ distinta");
            sb.append("\n");
        } else sb.append("Otro teléfono: sin conexión\n");
        sb.append("Repositorio: ").append(repo.isEmpty() ? "(sin definir)" : repo).append("\n\n");
        // lo que trajo la versión instalada
        org.json.JSONArray notes = Updater.notes(this);
        for (int k = 0; k < notes.length(); k++) {
            org.json.JSONObject o = notes.optJSONObject(k);
            if (o == null || !Updater.clean(o.optString("tag")).equals(Updater.clean(v))) continue;
            sb.append("Lo nuevo en esta versión:\n");
            for (String ch : Updater.changes(o.optString("body"))) sb.append("  – ").append(ch).append("\n");
            sb.append("\n");
        }
        sb.append("ChronOS une dos teléfonos como si fueran uno: mesa compartida, teclado, touchpad, gamepad y archivos.\n");
        sb.append("Diccionario: FrequencyWords (MIT).");
        new Ui.Sheet(this)
                .title("Acerca de ChronOS")
                .message(sb.toString())
                .option(Ui.RECEIVE, Updater.updateAvailable(this) ? "Actualizar a la versión " + Updater.latestKnown(this) : "Buscar actualización",
                        null, () -> Updater.check(this, hub))
                .badgeIf(Updater.updateAvailable(this), "1")
                .option(Ui.NOTE, "Novedades", "Qué trae cada versión", this::showNotes)
                .button("Cerrar", false, null)
                .show();
    }

    /** Historial de cambios (de las versiones publicadas). */
    private void showNotes() {
        Runnable show = () -> {
            String t = Updater.notesText(this, 25);
            new Ui.Sheet(this)
                    .title("Novedades")
                    .message(t.isEmpty() ? "No pude leer las novedades (¿sin internet?)." : t)
                    .button("Cerrar", true, null)
                    .show();
        };
        Updater.checkQuiet(this, true, () -> { if (!isFinishing()) { show.run(); showUpdateBadge(); } });
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
     * Revisa lo que cada teléfono necesita. Principal: teclado ChronOS activado, cambio automático
     * de teclado (accesibilidad) y permiso para abrir apps pedidas desde el extendido.
     * Extendido: permiso para abrir el teclado solo aunque la mesa esté de fondo.
     */
    private void checkSetup(boolean always) {
        List<String> lines = new ArrayList<>();
        final List<Runnable> fixes = new ArrayList<>();
        if (hub.isPrincipal()) {
            if (!Hub.imeEnabled(this)) {
                lines.add("✗ Activar el teclado ChronOS");
                fixes.add(() -> openSettings(android.provider.Settings.ACTION_INPUT_METHOD_SETTINGS, null,
                        "Activa \"ChronOS\" en la lista de teclados y vuelve."));
            } else lines.add("✓ Teclado ChronOS activado");
            boolean full = MesaAccess.available(this);
            if (!full) {
                lines.add("• Versión liviana: al conectar, toca el aviso \"quiere escribir aquí\" para elegir el teclado ChronOS");
            } else if (!MesaAccess.isEnabled(this)) {
                lines.add("✗ Cambio automático de teclado (accesibilidad)");
                fixes.add(this::guideAccessibility);
            } else lines.add("✓ Cambio automático de teclado");
            if (!full) {
                lines.add("• Versión liviana: las apps pedidas desde el extendido se abren tocando un aviso");
            } else if (!canOverlay()) {
                lines.add("✗ Abrir apps pedidas desde el extendido");
                fixes.add(() -> openSettings(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()),
                        "Activa \"Mostrar sobre otras apps\" para ChronOS y vuelve."));
            } else lines.add("✓ Abrir apps pedidas desde el extendido");
        } else {
            if (ScreenSync.enabled(this)) {
                if (MesaAccess.instance == null && !ScreenSync.adminActive(this)) {
                    lines.add("✗ Bloquearse junto al principal");
                    fixes.add(this::requestLockAdmin);
                } else lines.add("✓ Bloquearse junto al principal");
                if (!ScreenSync.canWake(this)) {
                    lines.add("✗ Encenderse junto al principal");
                    fixes.add(() -> openSettings("android.settings.MANAGE_APP_USE_FULL_SCREEN_INTENT", Uri.parse("package:" + getPackageName()),
                            "Permite las notificaciones de pantalla completa y vuelve."));
                } else lines.add("✓ Encenderse junto al principal");
            }
            if (!MesaAccess.available(this)) {
                lines.add("• Versión liviana: si la mesa está de fondo, el teclado se abre tocando un aviso");
            } else if (!canOverlay()) {
                lines.add("✗ Abrir el teclado solo, aunque la mesa esté de fondo");
                fixes.add(() -> openSettings(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()),
                        "Activa \"Mostrar sobre otras apps\" para ChronOS y vuelve."));
            } else lines.add("✓ Abrir el teclado solo");
        }
        if (ScreenSync.enabled(this)) {
            if (!ScreenSync.ignoringBattery(this)) {
                lines.add("✗ Seguir conectado con la pantalla apagada");
                fixes.add(() -> openSettings(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()),
                        "Ahorro de batería → Sin restricciones"));
            } else lines.add("✓ Sigue conectado con la pantalla apagada");
        }
        if (fixes.isEmpty() && !always) return;
        if (!always) {
            // solo se avisa solo si falta algo distinto a lo que ya se avisó (se recuerda la respuesta)
            StringBuilder miss = new StringBuilder(hub.isPrincipal() ? "P" : "E");
            for (String l : lines) if (l.startsWith("✗")) miss.append('|').append(l);
            if (miss.toString().equals(prefs.getString("setupSeen", ""))) return;
            prefs.edit().putString("setupSeen", miss.toString()).apply();
        }
        Ui.Sheet sh = new Ui.Sheet(this)
                .title(hub.isPrincipal() ? "Configurar el principal" : "Configurar el extendido")
                .message(fixes.isEmpty() ? "Todo listo." : "Toca lo que falta para resolverlo. En teléfonos Xiaomi revisa también: "
                        + "Ajustes → Apps → ChronOS → Otros permisos → \"Mostrar ventanas emergentes en segundo plano\".");
        int fi = 0;
        for (String l : lines) {
            boolean ok = l.startsWith("✓"), bad = l.startsWith("✗");
            String text = l.substring(1).trim();
            Runnable r = bad ? fixes.get(fi++) : null;
            sh.option(ok ? Ui.CHECK : bad ? Ui.CROSS : Ui.GEAR, text, bad ? "Tocar para resolver" : null, r);
        }
        sh.button(fixes.isEmpty() ? "Listo" : "Después", !fixes.isEmpty() ? false : true, null).show();
    }

    /** Pide el permiso de "bloquear la pantalla" (administrador del dispositivo, solo esa función). */
    private void requestLockAdmin() {
        new Ui.Sheet(this)
                .title("Bloquearse junto al principal")
                .message("Android pide activar a ChronOS como \"administrador del dispositivo\" solo para poder bloquear la pantalla. "
                        + "No puede borrar ni ver nada.\n\nPara desinstalar la app más adelante, primero desactívalo en "
                        + "Ajustes → Contraseñas y seguridad → Privacidad → Apps de administración del dispositivo.")
                .button("Después", false, null)
                .button("Activar", true, () -> {
                    Intent i = new Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
                    i.putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN, ScreenSync.admin(this));
                    i.putExtra(android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                            "Para que este teléfono se bloquee cuando bloqueas el principal.");
                    try { startActivity(i); } catch (Exception e) { Toast.makeText(this, "No pude abrir ese permiso", Toast.LENGTH_SHORT).show(); }
                })
                .show();
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
                .message("ChronOS usa un servicio de accesibilidad solo para cambiar al teclado ChronOS "
                        + "(y devolver el tuyo al desconectarse) y para saber cuándo abres una app de chat. No lee tu pantalla.")
                .option(Ui.LOCK, "1. Permitir ajustes restringidos", "Si aparece bloqueado: Info de la app → menú ⋮", () ->
                        openSettings(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()),
                                "Menú ⋮ → Permitir ajustes restringidos"))
                .option(Ui.GEAR, "2. Activar en Accesibilidad", "Apps descargadas → ChronOS", () ->
                        openSettings(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS, null, "Busca ChronOS en Apps descargadas y actívalo."))
                .button("Después", false, null)
                .show();
    }

    /** Herramientas: nota, imagen, reloj, widgets de Android y archivos. */
    private void toolsMenu() {
        new Ui.Sheet(this)
                .title("Herramientas")
                .option(Ui.NOTE, "Nota", "Una nota para escribir", this::addNote)
                .option(Ui.IMAGE, "Imagen", "Una foto de la galería", this::pickImage)
                .option(Ui.BATTERY, "Batería", "Nivel de batería del principal y del extendido", this::addBattery)
                .option(Ui.CLOCK, "Reloj", "Hora, fecha y próxima alarma; al tocarlo abre las alarmas", this::addClock)
                .option(Ui.GRID, "Apps recientes", "Barra deslizante: lo que queda en la mira es la app al frente en el principal", this::addRecents)
                .option(Ui.WIDGETS, "Widget", "Widgets de tus apps (clima, calendario, música...)", this::addWidget)
                .option(Ui.FOLDER, "Archivos", "Pasar archivos entre los dos teléfonos", this::openFiles)
                .option(Ui.IMAGE, "Cámara", "Widget con el visor y los botones de la cámara del principal", this::addCamera)
                .option(Ui.RECEIVE, "Actualizar app", Updater.updateAvailable(this) ? "Hay una versión nueva: " + Updater.latestKnown(this)
                        : "Versión instalada: " + Updater.clean(Updater.installedVersion(this)), () -> Updater.check(this, hub))
                .badgeIf(Updater.updateAvailable(this), "1")
                .button("Cerrar", false, null)
                .show();
    }

    private void addBattery() {
        Item it = new Item();
        it.kind = Item.BATTERY;
        it.w = sized(0.62);
        double[] p = desk.spot(it.w);
        it.x = p[0]; it.y = p[1];
        addItem(it);
    }

    @Override public int[] battery() {
        boolean pr = hub.isPrincipal();
        int pl = pr ? hub.myBatt : hub.otherBatt, el = pr ? hub.otherBatt : hub.myBatt;
        boolean pc = pr ? hub.myChg : hub.otherChg, ec = pr ? hub.otherChg : hub.myChg;
        if (!hub.connected()) { if (pr) el = -1; else pl = -1; }
        return new int[]{pl, pc ? 1 : 0, el, ec ? 1 : 0};
    }

    private void addClock() {
        Item it = new Item();
        it.kind = Item.CLOCK;
        it.w = sized(0.62);
        double[] p = desk.spot(it.w);
        it.x = p[0]; it.y = p[1];
        addItem(it);
    }

    /** Widget de cámara: la cámara corre en el principal; el visor y los botones, en la mesa. */
    private void addCamera() {
        Item it = new Item();
        it.kind = Item.CAMERA;
        it.w = sized(0.5);
        double[] p = desk.spot(it.w);
        it.x = p[0]; it.y = p[1];
        addItem(it);
        camKeepAlive.run();
    }

    @Override public void onCamera(String a) {
        org.json.JSONObject m = new org.json.JSONObject();
        try { m.put("t", "camCmd"); m.put("a", a); } catch (Exception ignored) { }
        if (hub.isPrincipal()) CameraActivity.command(this, m);
        else if (hub.connected()) { hub.send(m); if ("open".equals(a)) hub.send("camCmd", "a", "stream", "v", true); }
        else Toast.makeText(this, "El principal no está conectado", Toast.LENGTH_SHORT).show();
    }

    /** Mientras se ve el widget de cámara en el extendido, se pide la imagen en vivo (cada 3 s se renueva). */
    private final Runnable camKeepAlive = new Runnable() {
        @Override public void run() {
            main.removeCallbacks(this);
            if (!resumed || hub.isPrincipal()) return;
            if (desk.hasCameraWidget() && hub.connected()) hub.send("camCmd", "a", "stream", "v", true);
            main.postDelayed(this, 3000);
        }
    };

    private void addRecents() {
        Item it = new Item();
        it.kind = Item.RECENTS;
        it.w = sized(0.7);
        double[] p = desk.spot(it.w);
        it.x = p[0]; it.y = p[1];
        addItem(it);
        pollRecents.run();
        if (hub.isPrincipal() && !RecentApps.hasAccess(this)) askRecentsAccess();
    }

    /** Las apps recientes se leen con el "Acceso a datos de uso" del principal. */
    private void askRecentsAccess() {
        new Ui.Sheet(this)
                .title("Apps recientes")
                .message("Para saber qué apps están abiertas, Android pide activar ChronOS en \"Acceso a datos de uso\" "
                        + "(en el PRINCIPAL). Se abre esa pantalla: busca ChronOS, actívalo y vuelve.")
                .button("Ahora no", false, null)
                .button("Abrir ajustes", true, () -> {
                    try { startActivity(RecentApps.accessIntent()); }
                    catch (Exception e) { Toast.makeText(this, "No pude abrir los ajustes", Toast.LENGTH_SHORT).show(); }
                })
                .show();
    }

    @Override public void onRecent(String pkg, String label) {
        if (pkg == null) {
            if (hub.isPrincipal()) askRecentsAccess();
            else if (hub.connected()) {
                hub.send("recAccess");
                Toast.makeText(this, "Activa el acceso en el principal", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        if (pkg.isEmpty()) { pkg = getPackageName(); label = "ChronOS"; }   // "Desktop": volver a la mesa del principal
        openOnPrincipal(pkg, label);
        main.postDelayed(pollRecents, 900);
    }

    /** Mientras se ve el widget, la lista se refresca cada 2 segundos (solo viaja si cambió). */
    private final Runnable pollRecents = new Runnable() {
        @Override public void run() {
            main.removeCallbacks(this);
            if (!resumed) return;
            if (!desk.hasRecentsWidget()) { /* nada que mostrar */ }
            else if (hub.isPrincipal()) {
                RecentApps.worker.execute(() -> {
                    org.json.JSONObject m = RecentApps.message(getApplicationContext());
                    main.post(() -> desk.setRecents(m));
                });
            } else if (hub.connected()) hub.send("recReq", "sig", desk.recentsSig());
            main.postDelayed(this, 2000);
        }
    };
    private boolean resumed;

    /** El gestor de archivos se abre en los dos teléfonos: cada uno maneja su ventana. */
    private void openFiles() { setFileMode(true, false); }

    /** El reloj muestra la próxima alarma de ESTE teléfono: al tocarlo se abren las alarmas de este teléfono. */
    @Override public void onOpenAlarms() { hub.openAlarms(); }

    private void showApps() {
        AppsDialog.show(this, night, new AppsDialog.Host() {
            @Override public void openHere(String pkg) { openOnPrincipal(pkg, ""); }
            @Override public void openThere(String pkg, String label) { openOnPrincipal(pkg, label); }
            @Override public void uninstall(String pkg, String label) { uninstallOnPrincipal(pkg, label); }
            @Override public void putOnDesk(String pkg, String label, String iconPng) {
                Item it = new Item();
                it.kind = Item.APP; it.pkg = pkg; it.title = label; it.w = sized(0.24);
                it.setImage(iconPng);
                double[] p = desk.spot(it.w);
                it.x = p[0]; it.y = p[1];
                addItem(it);
                sendImage(it);
                if (prefs.getBoolean("appsAuto", false)) desk.post(desk::arrangeApps);
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

    /** Desinstalar: siempre en el principal (Android pide confirmación allá). */
    private void uninstallOnPrincipal(String pkg, String label) {
        if (hub.isPrincipal()) {
            if (hub.uninstallHere(pkg, label) == Hub.NOT_INSTALLED) Toast.makeText(this, label + " ya no está instalada", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!hub.connected()) { Toast.makeText(this, "El principal no está conectado", Toast.LENGTH_SHORT).show(); return; }
        hub.send("uninstall", "pkg", pkg, "label", label);
    }

    @Override public void onOpenApp(Item it) { openOnPrincipal(it.pkg, it.title); }

    @Override protected void onDestroy() {
        widgetLayer.removeAllViews();   // los widgets siguen vivos en WidgetHub
        hub.remove(this);   // la conexión sigue viva para el teclado
        super.onDestroy();
    }

    // ---------------- teclado ----------------

    private void keyboardMenu() {
        new Ui.Sheet(this)
                .title("Teclado")
                .option(Ui.KEYBOARD, "Usar este teléfono como teclado", "Teclado, touchpad, gamepad o dictado",
                        () -> startActivity(new Intent(this, KeyboardActivity.class).putExtra("manual", true)))
                .option(Ui.RECEIVE, "Recibir aquí lo que escribo", "Elegir el teclado ChronOS en este teléfono", this::setupReceiver)
                .button("Cerrar", false, null)
                .show();
    }

    private boolean imeEnabled() { return Hub.imeEnabled(this); }

    private void setupReceiver() {
        if (Hub.imeSelected(this)) {
            new Ui.Sheet(this)
                    .title("Listo para recibir")
                    .message("El teclado ChronOS ya está elegido. Abre cualquier app, toca donde se escribe y escribe desde el otro teléfono. "
                            + "Para volver a tu teclado normal toca \"Cambiar teclado\" en la barra.")
                    .button("Cambiar teclado", false, () -> startActivity(new Intent(this, ImePickerActivity.class)))
                    .button("Entendido", true, null)
                    .show();
            return;
        }
        if (!imeEnabled()) {
            new Ui.Sheet(this)
                    .title("Activa el teclado ChronOS")
                    .message("Se abrirá la lista de teclados. Activa \"ChronOS\" y vuelve aquí. Android muestra un aviso general "
                            + "sobre teclados: ChronOS solo escribe lo que mandas desde tu otro teléfono y no guarda nada.")
                    .button("Cancelar", false, null)
                    .button("Abrir ajustes", true, () -> openSettings(android.provider.Settings.ACTION_INPUT_METHOD_SETTINGS, null,
                            "Activa \"ChronOS\" y vuelve."))
                    .show();
            return;
        }
        new Ui.Sheet(this)
                .title("Elige \"ChronOS\"")
                .message("En la lista que aparece elige ChronOS. Al tocar donde se escribe verás una barra delgada en vez del teclado.")
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
        buildChronoBar();
        rebuildBar();
        layoutChrono(false);

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
        boolean changed = side != s;
        side = s;
        prefs.edit().putInt("side", s).apply();
        applyOrientation(this);
        relayout();
        if (s == 1) sendView();
        if (s == 1 && hub.connected() && MesaAccess.instance != null) MesaAccess.instance.switchToMesa();
        if (changed) main.postDelayed(() -> checkSetup(false), 400);
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
        menuBtn.setStatus(linkState == Link.CONNECTED ? (otherSide == side ? 1 : 2) : linkState == Link.WAITING ? 1 : 0);
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
        it.page = desk.page();   // se agrega en el escritorio que se está viendo
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
        if (Item.WIDGET.equals(it.kind)) releaseWidget(it);
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

    // ---------------- widgets de Android ----------------
    // Todos los widgets son del PRINCIPAL (ahí corren). El principal los muestra de verdad;
    // el extendido ve su imagen en vivo y sus toques se hacen en el principal.

    private final Map<Integer, android.graphics.Bitmap> mirrors = new HashMap<>();
    private final Map<Integer, int[]> wantNow = new HashMap<>();
    private String wantSent = "";

    @Override public boolean widgetBound(Item it) { return it.wid >= 0 && (hub.isPrincipal() || mirrors.containsKey(it.wid)); }

    @Override public android.graphics.Bitmap widgetMirror(Item it) { return hub.isPrincipal() ? null : mirrors.get(it.wid); }

    @Override public void onWidgetTap(Item it) { if (it.wid < 0) requestBind(it); }

    @Override public void onWidgetTouch(Item it, float fx, float fy) {
        if (getPackageName().equals(it.pkg)) { hub.openAlarms(); return; }   // el reloj de ChronOS: alarmas de este teléfono
        if (hub.isPrincipal() || !hub.connected()) return;
        hub.send("wTap", "wid", it.wid, "x", (double) fx, "y", (double) fy);
    }

    private void addWidget() {
        WidgetsDialog.show(this, night, (prov, label, mwDp, mhDp) -> {
            Item it = new Item();
            it.kind = Item.WIDGET;
            it.url = prov;
            int slash = prov.indexOf('/');
            it.pkg = slash > 0 ? prov.substring(0, slash) : prov;
            it.title = label;
            int W = Math.max(1, desk.getWidth());
            float minW = Math.max(dp(mwDp), dp(140)), minH = Math.max(dp(mhDp), dp(60));
            it.w = Math.max(0.25, Math.min(0.95, (minW + dp(40)) / W));
            it.ar = Math.max(0.2, Math.min(3, (minH + dp(20)) / (it.w * W)));
            double[] p = desk.spot(it.w);
            it.x = p[0]; it.y = p[1];
            addItem(it);
            requestBind(it);
        });
    }

    /** El widget se activa en el principal: ahí Android pide el permiso y su configuración. */
    private void requestBind(Item it) {
        if (hub.isPrincipal()) { bindWidget(it); return; }
        if (!hub.connected()) { Toast.makeText(this, "El principal no está conectado", Toast.LENGTH_SHORT).show(); return; }
        hub.send("wBind", "id", it.id);
        Toast.makeText(this, "Confirma el widget en el principal", Toast.LENGTH_SHORT).show();
    }

    /** Pedido del extendido: activar un widget que se agregó allá (puede que el elemento aún no llegue). */
    private void bindRequested(String id, int tries) {
        Item it = desk.find(id);
        if (it != null) { if (it.wid < 0) bindWidget(it); return; }
        if (tries < 10) main.postDelayed(() -> bindRequested(id, tries + 1), 400);
    }

    private void bindWidget(Item it) {
        WidgetHub wh = WidgetHub.get(this);
        android.appwidget.AppWidgetProviderInfo info = wh.provider(it.url);
        if (info == null) { Toast.makeText(this, "Ese widget no está instalado en el principal (" + it.title + ")", Toast.LENGTH_LONG).show(); return; }
        pendingWidget = it;
        pendingWidgetId = wh.allocate();
        boolean ok;
        try { ok = wh.awm.bindAppWidgetIdIfAllowed(pendingWidgetId, info.provider); } catch (Exception e) { ok = false; }
        if (ok) { configureWidget(); return; }
        Intent i = new Intent(android.appwidget.AppWidgetManager.ACTION_APPWIDGET_BIND);
        i.putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, pendingWidgetId);
        i.putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider);
        try { startActivityForResult(i, REQ_BIND); }
        catch (Exception e) { cancelPendingWidget("Este teléfono no permite agregar widgets aquí"); }
    }

    private void configureWidget() {
        WidgetHub wh = WidgetHub.get(this);
        android.appwidget.AppWidgetProviderInfo info = wh.awm.getAppWidgetInfo(pendingWidgetId);
        if (info != null && info.configure != null) {
            try { wh.host.startAppWidgetConfigureActivityForResult(this, pendingWidgetId, 0, REQ_CONF, null); return; }
            catch (Exception ignored) { }
        }
        finishWidget();
    }

    private void finishWidget() {
        Item it = pendingWidget;
        if (it == null) return;
        it.wid = pendingWidgetId;
        pendingWidget = null; pendingWidgetId = -1;
        it.touch();
        onItemChanged(it);
        desk.invalidate();
    }

    private void cancelPendingWidget(String msg) {
        if (pendingWidgetId >= 0) WidgetHub.get(this).delete(pendingWidgetId);
        pendingWidget = null; pendingWidgetId = -1;
        if (msg != null) Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private void releaseWidget(Item it) {
        mirrors.remove(it.wid);
        if (hub.isPrincipal()) WidgetHub.get(this).delete(it.wid);
    }

    /** Borra widgets activados que ya no están en la mesa (solo el principal los tiene). */
    private void cleanWidgets() {
        if (!hub.isPrincipal()) return;
        Set<Integer> used = new HashSet<>();
        for (Item it : desk.items) if (Item.WIDGET.equals(it.kind) && it.wid >= 0) used.add(it.wid);
        WidgetHub.get(this).clean(used);
    }

    private final Map<Integer, int[]> widgetSizes = new HashMap<>();

    /** La mesa avisa dónde dibujó la tarjeta del widget. */
    @Override public void onWidgetFrame(Item it, float left, float top, float width, float height, float rot, float px, float py, boolean shown) {
        if (!hub.isPrincipal()) {   // extendido: pedir al principal la imagen de lo que se ve
            if (it.wid < 0) return;
            if (shown) wantNow.put(it.wid, new int[]{Math.round(width), Math.round(height)}); else wantNow.remove(it.wid);
            main.removeCallbacks(sendWant);
            main.postDelayed(sendWant, 300);
            return;
        }
        android.appwidget.AppWidgetHostView v = it.wid >= 0 ? WidgetHub.get(this).view(it.wid) : null;
        if (v == null) return;
        if (!shown) { if (v.getVisibility() != View.GONE) v.setVisibility(View.GONE); return; }
        int iw = Math.max(1, Math.round(width)), ih = Math.max(1, Math.round(height));
        if (v.getParent() != widgetLayer) {
            if (v.getParent() instanceof android.view.ViewGroup) ((android.view.ViewGroup) v.getParent()).removeView(v);
            widgetLayer.addView(v, new FrameLayout.LayoutParams(iw, ih));
        }
        if (v.getVisibility() != View.VISIBLE) v.setVisibility(View.VISIBLE);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) v.getLayoutParams();
        if (lp.width != iw || lp.height != ih) {
            lp.width = iw; lp.height = ih;
            v.setLayoutParams(lp);
            widgetSizes.put(it.wid, new int[]{Math.round(iw / getResources().getDisplayMetrics().density), Math.round(ih / getResources().getDisplayMetrics().density)});
            main.removeCallbacks(widgetResize);
            main.postDelayed(widgetResize, 350);
        }
        v.setTranslationX(left); v.setTranslationY(top);
        v.setPivotX(px); v.setPivotY(py);
        v.setRotation(rot);
    }

    /** Extendido: avisa al principal qué widgets se están viendo y a qué tamaño. */
    private final Runnable sendWant = () -> {
        StringBuilder key = new StringBuilder();
        JSONArray ids = new JSONArray(), ws = new JSONArray(), hs = new JSONArray();
        for (Map.Entry<Integer, int[]> e : wantNow.entrySet()) {
            ids.put(e.getKey()); ws.put(e.getValue()[0]); hs.put(e.getValue()[1]);
            key.append(e.getKey()).append(':').append(e.getValue()[0] / 16).append('x').append(e.getValue()[1] / 16).append(';');
        }
        if (key.toString().equals(wantSent) || !hub.connected()) return;
        wantSent = key.toString();
        JSONObject m = new JSONObject();
        try { m.put("t", "wWant"); m.put("ids", ids); m.put("w", ws); m.put("h", hs); } catch (JSONException ignored) { }
        hub.send(m);
    };

    /** Informa el nuevo tamaño a los widgets cuando se dejó de cambiar (algunos cambian su diseño). */
    @SuppressWarnings("deprecation")
    private final Runnable widgetResize = () -> {
        for (Map.Entry<Integer, int[]> e : widgetSizes.entrySet()) {
            android.appwidget.AppWidgetHostView v = WidgetHub.get(this).view(e.getKey());
            if (v != null) try { v.updateAppWidgetSize(null, e.getValue()[0], e.getValue()[1], e.getValue()[0], e.getValue()[1]); } catch (Exception ignored) { }
        }
        widgetSizes.clear();
    };

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
        if (req == REQ_BIND) {
            if (res == RESULT_OK && pendingWidget != null) configureWidget();
            else cancelPendingWidget("No se permitió el widget");
        }
        if (req == REQ_CONF) {
            if (res == RESULT_OK) finishWidget();
            else cancelPendingWidget(null);
        }
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
                hello.put("t", "hello"); hello.put("side", side); hello.put("ver", Updater.installedVersion(this));
                JSONArray arr = new JSONArray();
                for (Item it : desk.items) arr.put(it.toJson(false));
                full.put("t", "full"); full.put("items", arr);
            } catch (JSONException ignored) { }
            if (side == 1) sendView();   // el principal define la forma; va antes que los elementos
            wantSent = ""; main.postDelayed(sendWant, 800);   // widgets: volver a pedir las imágenes
            hub.send(hello);
            hub.send(full);
        }
        if (state != Link.CONNECTED) { otherSide = -1; desk.clearAllRemoteDrags(); requested.clear(); }
        updateStatus();
    }

    @Override public void onMessage(JSONObject m) {
        String t = m.optString("t");
        switch (t) {
            case "fileMode":
                main.post(() -> setFileMode(m.optBoolean("on"), true));
                break;
            case "notif":
                main.post(() -> {
                    if (side == 1 && barNotif != null) barNotif.show(m);   // en la barra del reloj
                    else if (side == 0) floatNotif.show(m);                   // el principal está en otra app: aquí, arriba a la izquierda
                });
                break;
            case "mState":
                if (MediaHub.AUDIO.equals(m.optString("kind")) && barPlayer != null)
                    main.post(() -> { boolean was = cbar.playerShown(); cbar.showPlayer(true); barPlayer.update(m); if (!was) main.postDelayed(() -> { layoutChrono(false); }, 560); });
                break;
            case "mEnd":
                if (MediaHub.AUDIO.equals(m.optString("kind")) && cbar != null) main.post(() -> { cbar.showPlayer(false); main.postDelayed(() -> layoutChrono(false), 400); });
                break;
            case "notifs":
                main.post(() -> desk.setNotified(m.optJSONArray("p")));
                break;
            case "gameBg":
                main.post(() -> desk.setBgImage(GameBg.current));
                break;
            case "camState":
                main.post(() -> desk.setCamState(m.optBoolean("open"), m.optBoolean("front"), m.optInt("flash")));
                break;
            case "camFrame":
                try {
                    byte[] jb = android.util.Base64.decode(m.optString("j"), android.util.Base64.DEFAULT);
                    android.graphics.Bitmap fb = android.graphics.BitmapFactory.decodeByteArray(jb, 0, jb.length);
                    if (fb != null) main.post(() -> desk.setCamFrame(fb));
                } catch (Exception ignored) { }
                break;
            case "camShot":
                main.post(() -> desk.setCamMessage(m.optBoolean("ok") ? "📸 Foto guardada en el principal" : "No se pudo guardar la foto"));
                break;
            case "alarmsAck":
                main.post(() -> Toast.makeText(this, "El principal no encontró la app de alarmas", Toast.LENGTH_SHORT).show());
                break;
            case "recList":
                main.post(() -> desk.setRecents(m));
                break;
            case "hello":
                otherSide = m.optInt("side", -1);
                otherVersion = m.optString("ver", "");
                updateStatus();
                break;
            case "pages": {   // ambos teléfonos muestran el mismo escritorio
                int n = Math.max(m.optInt("n", 1), maxPage() + 1), cur = m.optInt("cur", 0);
                desk.setPages(n, cur, Integer.compare(cur, desk.page()));
                savePages();
                break;
            }
            case "apps":
                setAppsMode(m.optBoolean("lock", false), m.optBoolean("auto", false), true);
                break;
            case "cardTheme":
                if (m.optInt("c") != CardTheme.color || m.optInt("g") != CardTheme.glass) setCardTheme(m.optInt("c"), m.optInt("g"), true);
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
                if (it != null) { desk.items.remove(it); if (Item.WIDGET.equals(it.kind)) releaseWidget(it); desk.invalidate(); scheduleSave(); }
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
            case "uninstallAck": {
                int r = m.optInt("r");
                String label = m.optString("label");
                Toast.makeText(this, r == Hub.LAUNCHED ? "Confirma la desinstalación de " + label + " en el principal"
                        : r == Hub.NOTIFIED ? "Toca el aviso en el principal para desinstalar " + label
                        : label + " ya no está instalada en el principal", Toast.LENGTH_LONG).show();
                break;
            }
            case "batt":
                desk.invalidate();
                break;
            case "snap":
                prefs.edit().putBoolean("snapGrid", m.optBoolean("on")).apply();
                desk.setSnap(m.optBoolean("on"));
                break;
            case "wImg": {   // imagen en vivo de un widget del principal
                try {
                    byte[] bytes = Base64.decode(m.optString("img"), Base64.DEFAULT);
                    android.graphics.Bitmap bm = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                    if (bm != null) { mirrors.put(m.optInt("wid"), bm); desk.invalidate(); }
                } catch (Exception ignored) { }
                break;
            }
            case "navAck":
                if (!m.optBoolean("ok")) Toast.makeText(this, "Recientes necesita la versión completa en el principal", Toast.LENGTH_SHORT).show();
                break;
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
        if (in.page >= desk.pages()) { desk.setPages(in.page + 1, desk.page(), 0); savePages(); }
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
