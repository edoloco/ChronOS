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
    private TickerView barTicker, floatTicker;
    private org.json.JSONArray tickerItems;
    private boolean tickerHere;
    private BarPlayer barPlayer;
    private RecentsBar recentsBar;
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
        solo = prefs.getBoolean("soloMode", false);
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
        SquareButton kbNav = new SquareButton(this, SquareButton.KEYBOARD, "Teclado", false, night);
        kbNav.setCompact(true);
        kbNav.setOnClickListener(v -> startActivity(new Intent(this, KeyboardActivity.class).putExtra("manual", true)
                .putExtra("mode", KeyboardActivity.KB).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)));
        squares.add(kbNav);
        navPill.addView(kbNav);
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
        // banda tipo noticiero arriba del extendido (cuando el principal está en otra app)
        floatTicker = new TickerView(this, true);
        floatTicker.setTap(pkg -> showNotifList());
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(-1, dp(34), Gravity.TOP);
        tl.setMargins(dp(12), dp(26), dp(12), 0);
        root.addView(floatTicker, tl);
        root.addOnLayoutChangeListener((v, l, t, r2, b2, ol, ot, or, ob) -> {
            if (b2 - t != ob - ot || r2 - l != or - ol) main.post(() -> layoutChrono(false));   // al ocultarse las barras de Android
        });
        offlinePanel = buildOffline();
        root.addView(offlinePanel, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
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
        boolean savedStacked = prefs.getBoolean(sk("savedStacked"), solo);
        if (!solo && !prefs.getBoolean("layoutV2", false)) {
            // versiones anteriores tenían el principal a la derecha: se intercambian las mitades una vez
            if (!savedStacked) desk.swapHalves();
            prefs.edit().putBoolean("layoutV2", true).apply();
            scheduleSave();
        }
        desk.setVertical(savedStacked);
        if (savedStacked != stacked()) { desk.convertAll(stacked()); prefs.edit().putBoolean(sk("savedStacked"), stacked()).apply(); scheduleSave(); }
        for (Item it : desk.items) desk.normalize(it);
        desk.setPages(Math.max(prefs.getInt(sk("pages"), 1), maxPage() + 1), prefs.getInt(sk("page"), 0), 0);
        desk.setAppsMode(prefs.getBoolean("appsLock", false), prefs.getBoolean("appsAuto", false));
        desk.setSnap(prefs.getBoolean("snapGrid", false));
        cleanWidgets();

        side = hub.side();
        if (side >= 0) applySide();
        if (solo) setupSolo(root);
        // intro de la nueva etapa: fondo naranja, papel del teléfono (si falta), letras, logo y sonido
        boolean askRole = !solo && !prefs.getBoolean("roleV2", false);
        if (b == null && !Intro.shownThisProcess && (askRole || prefs.getBoolean("introOn", true))) showIntro(askRole);
        else if (side < 0) chooseSide();   // solo la primera vez: después se recuerda (se cambia en Ajustes)
        hub.add(this);
        LinkService.start(this);
        if (android.os.Build.VERSION.SDK_INT >= 33 && !prefs.getBoolean("askedNotif", false)) {
            prefs.edit().putBoolean("askedNotif", true).apply();
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 3);
        }
        if (getIntent().getBooleanExtra("overLock", false)) showOverLock(true);
        handleShare(getIntent());
        String bw = getIntent().getStringExtra("bindWidget");
        if (bw != null) bindRequested(bw, 0);
        if (getIntent().hasExtra("homeSet")) { boolean on = getIntent().getBooleanExtra("homeSet", true); getIntent().removeExtra("homeSet"); main.post(() -> { if (on) forceHome(); else releaseHome(); }); }
        if (getIntent().getBooleanExtra("callPerm", false)) { getIntent().removeExtra("callPerm"); requestPermissions(CallHub.PERMS, 61); }
        if (getIntent().getBooleanExtra("notifSetup", false)) { getIntent().removeExtra("notifSetup"); main.post(this::guideNotifs); }
        if (getIntent().hasExtra("phoneAsk")) { String pt = getIntent().getStringExtra("phoneAsk"); getIntent().removeExtra("phoneAsk"); main.post(() -> phoneOffer(pt)); }
        if (getIntent().getBooleanExtra("forceLink", false)) { getIntent().removeExtra("forceLink"); main.post(() -> { hub.forceLink(); Toast.makeText(this, "Buscando al extendido…", Toast.LENGTH_SHORT).show(); }); }
        if (getIntent().getBooleanExtra("phoneNow", false)) { getIntent().removeExtra("phoneNow"); main.post(this::goNormal); }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent.getBooleanExtra("overLock", false)) showOverLock(true);
        String bw = intent.getStringExtra("bindWidget");
        if (bw != null) { intent.removeExtra("bindWidget"); bindRequested(bw, 0); }
        if (intent.hasExtra("homeSet")) { boolean on = intent.getBooleanExtra("homeSet", true); intent.removeExtra("homeSet"); main.post(() -> { if (on) forceHome(); else releaseHome(); }); }
        if (intent.getBooleanExtra("callPerm", false)) { intent.removeExtra("callPerm"); requestPermissions(CallHub.PERMS, 61); }
        if (intent.getBooleanExtra("notifSetup", false)) { intent.removeExtra("notifSetup"); main.post(this::guideNotifs); }
        if (intent.hasExtra("phoneAsk")) { String pt = intent.getStringExtra("phoneAsk"); intent.removeExtra("phoneAsk"); main.post(() -> phoneOffer(pt)); }
        if (intent.getBooleanExtra("forceLink", false)) { intent.removeExtra("forceLink"); main.post(() -> { hub.forceLink(); Toast.makeText(this, "Buscando al extendido…", Toast.LENGTH_SHORT).show(); }); }
        if (intent.getBooleanExtra("phoneNow", false)) { intent.removeExtra("phoneNow"); main.post(this::goNormal); }
        handleShare(intent);
    }

    @Override protected void onResume() {
        super.onResume();
        LockArt.noteRotation(this, hub, getWindowManager().getDefaultDisplay().getRotation());
        immersive();
        hub.foreground = true;
        live = this;
        hub.notifsChanged();
        main.removeCallbacks(offlineTick);
        main.postDelayed(offlineTick, 600);
        resumed = true;
        main.postDelayed(this::reportHome, 1200);
        if (side == 1) PhoneMode.exit(this, hub);   // se volvió a ChronOS: deja el modo teléfono normal
        // Xiaomi a veces detiene el lector de notificaciones: se le pide a Android que lo vuelva a conectar
        if (side == 1 && NotifService.instance == null && NotifService.enabled(this))
            try { android.service.notification.NotificationListenerService.requestRebind(new android.content.ComponentName(this, NotifService.class)); } catch (Exception ignored) { }
        Updater.checkQuiet(this, false, this::showUpdateBadge);
        // el principal quedó esperando el permiso de instalar: si ya lo tiene, sigue la actualización
        if (side == 1 && prefs.getBoolean("pendingUpdate", false) && Updater.canInstall(this)) Updater.updateFromRemote(this, hub);
        main.removeCallbacks(updatePoll);
        main.postDelayed(updatePoll, 10 * 60_000L);
        main.postDelayed(pollRecents, 300);
        main.postDelayed(callsTick, 800);
        main.postDelayed(camKeepAlive, 500);
        if (!hub.isPrincipal()) desk.setBgImage(GameBg.current);
        if (orient.canDetectOrientation()) orient.enable();
        if (hub.isPrincipal() && hub.connected() && MesaAccess.instance != null) MesaAccess.instance.switchToMesa();
    }

    @Override protected void onPause() {
        hub.foreground = false;
        hub.notifsChanged();
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
    private boolean stacked() { return solo || landscapeNow(); }
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
        if (p.getBoolean("soloMode", false)) { a.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT); return; }   // una pantalla
        boolean h = p.getBoolean("orientH", true), lock = p.getBoolean("viewLock", true), principal = p.getInt("side", -1) == 1;
        boolean flip = p.getBoolean(principal ? "flipP" : "flipE", false);   // teléfono puesto al revés
        if (principal && !lock) a.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR);
        else if (flip) a.setRequestedOrientation(h ? ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE : ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT);
        else a.setRequestedOrientation(h ? ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
    }

    /** Cada teléfono puede ir al revés (girado 180°); se guarda en los dos. */
    private void setFlip(boolean p, boolean e, boolean fromRemote) {
        prefs.edit().putBoolean("flipP", p).putBoolean("flipE", e).apply();
        applyOrientation(this);
        if (!fromRemote) hub.send("flip", "p", p, "e", e);
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
        hub.send("cardTheme", "c", CardTheme.color, "g", CardTheme.glass, "k", CardTheme.console);
        hub.send("flip", "p", prefs.getBoolean("flipP", false), "e", prefs.getBoolean("flipE", false));
        if (!solo) hub.send("pages", "n", desk.pages(), "cur", desk.page());
        hub.send("apps", "lock", prefs.getBoolean("appsLock", false), "auto", prefs.getBoolean("appsAuto", false));
        hub.send("snap", "on", prefs.getBoolean("snapGrid", false));
    }

    @Override public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        LockArt.noteRotation(this, hub, getWindowManager().getDefaultDisplay().getRotation());
        relayout();
        if (side == 1 && !locked()) sendView();   // vista libre: el extendido sigue al principal al girar
    }

    /** Ancho inicial de un elemento: en horizontal la pantalla es más ancha, así que se usa la mitad. */
    private double sized(double base) { return stacked() ? base * 0.5 : base; }
    private String sideName(int s) { return s == 1 ? "PRINCIPAL" : "EXTENDIDO"; }
    private String firstPhone() { return "el extendido"; }
    private String secondPhone() { return "el principal"; }

    // ---------------- pantalla de inicio ----------------

    private boolean isLauncher() { return homeEnabled(this); }

    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() {
        if (startOverlay != null && startOverlay.getVisibility() == View.VISIBLE) { showStart(false); return; }
        if (fileMode) { setFileMode(false, false); return; }
        if (isLauncher()) return;
        super.onBackPressed();
    }

    // ChronOS como pantalla de inicio, en este teléfono o en el otro. "Forzar" usa la ventana de Android
    // "¿Usar ChronOS como app de inicio predeterminada?" (Android 10+); si el teléfono no la tiene, abre los ajustes de inicio.

    static boolean homeEnabled(android.content.Context c) {
        return c.getPackageManager().getComponentEnabledSetting(new android.content.ComponentName(c, c.getPackageName() + ".Escritorio"))
                == android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
    }

    /** ¿ChronOS es de verdad la pantalla de inicio de este teléfono? */
    static boolean homeDefault(android.content.Context c) {
        try {
            Object rm = c.getSystemService("role");
            if (rm != null) return (Boolean) rm.getClass().getMethod("isRoleHeld", String.class).invoke(rm, "android.app.role.HOME");
        } catch (Exception ignored) { }
        android.content.pm.ResolveInfo r = c.getPackageManager().resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
        return r != null && r.activityInfo != null && c.getPackageName().equals(r.activityInfo.packageName);
    }

    static void setHomeEnabled(android.content.Context c, boolean on) {
        c.getPackageManager().setComponentEnabledSetting(new android.content.ComponentName(c, c.getPackageName() + ".Escritorio"),
                on ? android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED : android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                android.content.pm.PackageManager.DONT_KILL_APP);
    }

    private static final int REQ_HOME = 91;

    /** Pone ChronOS como inicio de ESTE teléfono: primero la ventana de Android; si no existe, los ajustes de inicio. */
    void forceHome() {
        setHomeEnabled(this, true);
        if (homeDefault(this)) { Toast.makeText(this, "ChronOS ya es la pantalla de inicio de este teléfono", Toast.LENGTH_SHORT).show(); reportHome(); return; }
        main.postDelayed(() -> {   // Android tarda un momento en ver la pantalla de inicio recién activada
            try {
                Object rm = getSystemService("role");
                boolean avail = rm != null && (Boolean) rm.getClass().getMethod("isRoleAvailable", String.class).invoke(rm, "android.app.role.HOME");
                if (avail) {
                    Intent i = (Intent) rm.getClass().getMethod("createRequestRoleIntent", String.class).invoke(rm, "android.app.role.HOME");
                    startActivityForResult(i, REQ_HOME);
                    return;
                }
            } catch (Exception ignored) { }
            homeSettings();
        }, 400);
    }

    private void homeSettings() {
        for (String a : new String[]{android.provider.Settings.ACTION_HOME_SETTINGS, "android.settings.MANAGE_DEFAULT_APPS_SETTINGS"}) {
            try { startActivity(new Intent(a)); Toast.makeText(this, "Elige ChronOS como pantalla de inicio", Toast.LENGTH_LONG).show(); return; }
            catch (Exception ignored) { }
        }
        openSettings(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()),
                "Busca \"Pantalla de inicio\" / \"Abrir de forma predeterminada\" y elige ChronOS");
    }

    /** Deja de ser la pantalla de inicio de este teléfono: Android vuelve a la de siempre. */
    void releaseHome() {
        boolean was = homeDefault(this);
        setHomeEnabled(this, false);
        Toast.makeText(this, "ChronOS ya no es la pantalla de inicio de este teléfono", Toast.LENGTH_SHORT).show();
        if (was) main.postDelayed(this::homeSettings, 300);
        main.postDelayed(this::reportHome, 800);
    }

    /** Le cuenta al otro teléfono si ChronOS es el inicio aquí (para mostrarlo en Ajustes). */
    void reportHome() {
        if (hub.connected()) hub.send("homeState", "def", homeDefault(this), "en", homeEnabled(this));
    }

    private boolean otherHome, otherHomeKnown;

    private String homeLabel(boolean def) { return def ? "sí" : "no"; }

    /** Ajustes → Pantalla de inicio: en el principal y en el extendido, desde cualquiera de los dos. */
    private void launcherMenu() {
        if (hub.connected()) hub.send("homeAsk");
        boolean me = homeDefault(this), principal = hub.isPrincipal();
        String meName = principal ? "el principal" : "el extendido", otherName = principal ? "el extendido" : "el principal";
        Ui.Sheet sh = new Ui.Sheet(this)
                .title("ChronOS como pantalla de inicio")
                .message("Con ChronOS como inicio, el botón Inicio del teléfono te trae a la mesa. Android pide confirmarlo en cada teléfono: "
                        + "aparece su ventana \"¿Usar como app de inicio?\" (o sus ajustes) y se elige ChronOS.");
        sh.option(Ui.HOME, (me ? "Quitar en " : "Poner en ") + meName, "Ahora: " + homeLabel(me) + " · este teléfono",
                () -> { if (me) releaseHome(); else forceHome(); });
        if (hub.connected()) {
            sh.option(Ui.HOME, (otherHomeKnown && otherHome ? "Quitar en " : "Poner en ") + otherName,
                    "Ahora: " + (otherHomeKnown ? homeLabel(otherHome) : "…") + " · la confirmación aparece en " + otherName, () -> {
                        boolean on = !(otherHomeKnown && otherHome);
                        hub.send("homeSet", "on", on);
                        Toast.makeText(this, "Confirma en " + otherName, Toast.LENGTH_LONG).show();
                    });
        }
        sh.option(Ui.GEAR, "Abrir los ajustes de inicio de este teléfono", "Si la ventana no aparece, elígelo a mano", this::homeSettings);
        sh.button("Cerrar", false, null).show();
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
                final boolean first = i == 0;
                row.addView(swatch(col, col == cur, () -> {
                    setDeskColor(col, false);
                    if (dlg[0] != null) dlg[0].dismiss();
                    if (first && secretTap()) { main.postDelayed(this::chooseConsole, 300); return; }
                    main.postDelayed(this::chooseColor, 260);
                }), swatchLp());
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
                .option(Ui.PALETTE, "Color personalizado", "Elegir tono, intensidad y brillo", () -> customColor(cur, col -> { setDeskColor(col, false); main.postDelayed(this::chooseColor, 260); }))
                .button("Cerrar", false, null)
                .show();
    }

    private static int secretTaps;
    private static long secretAt;

    /** Tres toques seguidos al primer color de fondo desbloquean los temas de consola. */
    private boolean secretTap() {
        long now = System.currentTimeMillis();
        secretTaps = now - secretAt < 2500 ? secretTaps + 1 : 1;
        secretAt = now;
        if (secretTaps < 3) return false;
        secretTaps = 0;
        boolean was = prefs.getBoolean("consoleUnlocked", false);
        prefs.edit().putBoolean("consoleUnlocked", true).apply();
        Toast.makeText(this, was ? "Temas de consola" : "🎮 ¡Desbloqueaste los temas de consola!", Toast.LENGTH_LONG).show();
        return true;
    }

    /** Temas de consola: colores de menús inspirados en consolas de juego (también cambian el fondo de la mesa). */
    private void chooseConsole() {
        Ui.Sheet sh = new Ui.Sheet(this)
                .title("🎮 Temas de consola")
                .message("Menús, tarjetas y fondo con los colores de cada consola. Se aplica en ambos teléfonos.");
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        final android.app.Dialog[] dlg = new android.app.Dialog[1];
        boolean n = CardTheme.sheetNight(night);
        for (int k = 1; k < CardTheme.CONSOLE_NAMES.length; k++) {
            final int kk = k;
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(10), dp(8), dp(10), dp(8));
            // muestra: mesa con una tarjeta encima y su borde
            int[] pv = CardTheme.consolePreview(k);
            android.widget.FrameLayout prev = new android.widget.FrameLayout(this);
            GradientDrawable bgp = new GradientDrawable();
            bgp.setColor(pv[0]);
            bgp.setCornerRadius(dp(10));
            bgp.setStroke(dp(1), 0x33888888);
            prev.setBackground(bgp);
            View card = new View(this);
            GradientDrawable cg = new GradientDrawable();
            cg.setColor(pv[1]);
            cg.setCornerRadius(dp(5));
            cg.setStroke(dp(2), pv[2]);
            card.setBackground(cg);
            prev.addView(card, new android.widget.FrameLayout.LayoutParams(dp(30), dp(20), Gravity.CENTER));
            row.addView(prev, new LinearLayout.LayoutParams(dp(56), dp(38)));
            LinearLayout tx = new LinearLayout(this);
            tx.setOrientation(LinearLayout.VERTICAL);
            tx.setPadding(dp(12), 0, 0, 0);
            TextView t1 = new TextView(this);
            t1.setText((CardTheme.console == k ? "✓ " : "") + "Tema " + CardTheme.CONSOLE_NAMES[k]);
            t1.setTextSize(16);
            t1.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
            t1.setTextColor(Ui.fg(n));
            TextView t2 = new TextView(this);
            t2.setText(CardTheme.CONSOLE_DESC[k]);
            t2.setTextSize(12);
            t2.setTextColor(Ui.muted(n));
            tx.addView(t1);
            tx.addView(t2);
            row.addView(tx, new LinearLayout.LayoutParams(0, -2, 1));
            row.setOnClickListener(v -> {
                setCardTheme(0, CardTheme.NONE, kk, false);
                setDeskColor(CardTheme.consoleDesk(kk), false);
                if (dlg[0] != null) dlg[0].dismiss();
                main.postDelayed(this::chooseConsole, 300);
            });
            list.addView(row);
        }
        android.widget.ScrollView sc = new android.widget.ScrollView(this);
        sc.addView(list);
        sc.setLayoutParams(new LinearLayout.LayoutParams(-1, Math.min(dp(360), getResources().getDisplayMetrics().heightPixels * 3 / 5)));
        sh.view(sc);
        if (CardTheme.isConsole()) sh.option(Ui.AUTO, "Quitar tema de consola", "Volver a los colores automáticos",
                () -> { setCardTheme(0, CardTheme.NONE, 0, false); setDeskColor(0, false); });
        dlg[0] = sh.button("Cerrar", false, null).show();
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
                        () -> { setCardTheme(col, CardTheme.NONE, false); if (dlg[0] != null) dlg[0].dismiss(); main.postDelayed(this::chooseCardColor, 260); }), swatchLp());
            }
            grid.addView(row);
        }
        dlg[0] = new Ui.Sheet(this)
                .title("Tarjetas y menús")
                .message("Color de las tarjetas de apps y elementos, de la barra de opciones y de estos menús. Se aplica en ambos teléfonos.")
                .optionIf(prefs.getBoolean("consoleUnlocked", false), Ui.PALETTE, "🎮 Temas de consola",
                        CardTheme.isConsole() ? "Ahora: " + CardTheme.CONSOLE_NAMES[CardTheme.console] : "DS, 3DS, Switch, Wii, PS3, PSP, NES y Super NES", this::chooseConsole)
                .option(Ui.PALETTE, -1, "Cristal intenso", "Vidrio esmerilado realista: fondo borroso, sombras profundas y brillo",
                        CardTheme.isIntense(), () -> { setCardTheme(0, CardTheme.GLASS_INTENSE, false); main.postDelayed(this::chooseCardColor, 260); })
                .option(Ui.PALETTE, -1, "Cristal Aero (Windows 7)", "Vidrio de color con brillo y vetas de luz",
                        CardTheme.isAero(), this::chooseAeroTint)
                .option(Ui.PALETTE, -1, "Cristal claro", "Translúcido, como vidrio esmerilado", CardTheme.glass == CardTheme.GLASS_LIGHT,
                        () -> { setCardTheme(0, CardTheme.GLASS_LIGHT, false); main.postDelayed(this::chooseCardColor, 260); })
                .option(Ui.PALETTE, -1, "Cristal oscuro", "Vidrio ahumado, para fondos claros u oscuros", CardTheme.glass == CardTheme.GLASS_DARK,
                        () -> { setCardTheme(0, CardTheme.GLASS_DARK, false); main.postDelayed(this::chooseCardColor, 260); })
                .option(Ui.AUTO, -1, "Automático", "Según el modo claro u oscuro del teléfono", !CardTheme.isGlass() && !CardTheme.isConsole() && CardTheme.color == 0,
                        () -> { setCardTheme(0, CardTheme.NONE, false); main.postDelayed(this::chooseCardColor, 260); })
                .view(grid)
                .option(Ui.PALETTE, "Color personalizado", "Elegir tono, intensidad y brillo",
                        () -> customColor(CardTheme.color == 0 ? 0xFF223040 : CardTheme.color, col -> { setCardTheme(col, CardTheme.NONE, false); main.postDelayed(this::chooseCardColor, 260); }))
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
                        () -> { setCardTheme(col, CardTheme.GLASS_AERO, false); if (dlg[0] != null) dlg[0].dismiss(); main.postDelayed(this::chooseAeroTint, 260); }), swatchLp());
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
                        () -> customColor(CardTheme.aeroTint(), col -> { setCardTheme(col, CardTheme.GLASS_AERO, false); main.postDelayed(this::chooseAeroTint, 260); }))
                .button("Cerrar", false, null)
                .show();
    }

    private void setCardTheme(int color, int glass, boolean fromRemote) { setCardTheme(color, glass, 0, fromRemote); }

    private void setCardTheme(int color, int glass, int console, boolean fromRemote) {
        CardTheme.save(this, color, glass, console);
        desk.setCardTheme();
        for (SquareButton b : squares) b.applyTheme();
        if (cbar != null) cbar.invalidate();
        rebuildBar();
        if (!fromRemote) hub.send("cardTheme", "c", color, "g", glass, "k", console);
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
        if (CardTheme.isConsole()) g.setStroke(dp(2), CardTheme.stroke());
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
                byKind(SquareButton.TOOLS), phoneSettingsBtn(), phoneBtn(), soloBtn(), powerBtn(), null, null, aboutBtn()};   // Archivos está en Herramientas
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

    private SquareButton filesBtn, aboutSq;

    private SquareButton powerSq;

    /** Menú inicio: centro de apagado. */
    private SquareButton powerBtn() {
        if (powerSq == null) {
            powerSq = new SquareButton(this, SquareButton.POWER, "Apagar", false, night);
            powerSq.setOnClickListener(v -> { showStart(false); powerCenter(); });
            squares.add(powerSq);
        }
        if (powerSq.getParent() instanceof android.view.ViewGroup) ((android.view.ViewGroup) powerSq.getParent()).removeView(powerSq);
        return powerSq;
    }

    /** Centro de apagado: primero cuál teléfono, después apagar, reiniciar o bloquear. */
    private void powerCenter() {
        Ui.Sheet sh = new Ui.Sheet(this).title("Centro de apagado").message("¿Qué teléfono?");
        sh.option(Ui.LOCK, "Los dos", "Principal y extendido", () -> powerWhat(true, true));
        sh.option(Ui.LOCK, "Principal", hub.isPrincipal() ? "Este teléfono" : "El de las apps", () -> powerWhat(true, false));
        if (!solo) sh.option(Ui.LOCK, "Extendido", hub.isPrincipal() ? "El de los controles" : "Este teléfono", () -> powerWhat(false, true));
        sh.button("Cancelar", false, null).show();
    }

    private void powerWhat(boolean p, boolean e) {
        String who = p && e ? "los dos teléfonos" : p ? "el principal" : "el extendido";
        new Ui.Sheet(this)
                .title("¿Qué hacer con " + who + "?")
                .message("Android no deja que una app apague o reinicie sola: se abre el menú de apagado de Android en cada teléfono "
                        + "y ahí tocas Apagar o Reiniciar.")
                .option(Ui.LOCK, "Apagar", "Abre el menú de apagado: toca \"Apagar\"", () -> powerDo(p, e, "menu", "Apagar"))
                .option(Ui.AUTO, "Reiniciar", "Abre el menú de apagado: toca \"Reiniciar\"", () -> powerDo(p, e, "menu", "Reiniciar"))
                .option(Ui.LOCK, "Bloquear pantalla", "Apaga la pantalla (sin apagar el teléfono)", () -> powerDo(p, e, "lock", null))
                .button("Cancelar", false, null)
                .show();
    }

    /** Primero el otro teléfono (si va), después este. */
    private void powerDo(boolean p, boolean e, String a, String tap) {
        boolean meP = hub.isPrincipal() || solo;
        boolean other = (p && !meP) || (e && meP && !solo), me = (p && meP) || (e && !meP);
        if (other) {
            if (hub.connected()) hub.send("power", "a", a);
            else Toast.makeText(this, "El otro teléfono no está conectado", Toast.LENGTH_SHORT).show();
        }
        if (me) main.postDelayed(() -> {
            if (!Power.run(this, a)) powerFail(a);
            else if (tap != null) Toast.makeText(this, "Toca \"" + tap + "\" en el menú de Android", Toast.LENGTH_LONG).show();
        }, other ? 700 : 0);
    }

    private void powerFail(String a) {
        if (!"menu".equals(a)) {
            new Ui.Sheet(this).title("No se pudo en este teléfono")
                    .message("Para bloquear la pantalla, activa \"Bloquear pantalla\" en Ajustes → Revisar configuración.")
                    .button("Entendido", true, null).show();
            return;
        }
        String why = Power.why(this);
        Ui.Sheet sh = new Ui.Sheet(this).title("No se abrió el menú de apagado").message(Power.explain(why == null ? "dead" : why, true));
        if (!"lite".equals(why)) sh.option(Ui.OPEN, "Abrir Accesibilidad", "En este teléfono", () -> {
            try { startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)); } catch (Exception ignored) { }
        });
        sh.button("Entendido", true, null).show();
    }

    private SquareButton phoneSetSq;

    /** Menú inicio: los ajustes de Android del teléfono que se elija. */
    private SquareButton phoneSettingsBtn() {
        if (phoneSetSq == null) {
            phoneSetSq = new SquareButton(this, SquareButton.SETTINGS, "Ajustes del teléfono", false, night);
            phoneSetSq.setOnClickListener(v -> { showStart(false); phoneSettings(); });
            squares.add(phoneSetSq);
        }
        if (phoneSetSq.getParent() instanceof android.view.ViewGroup) ((android.view.ViewGroup) phoneSetSq.getParent()).removeView(phoneSetSq);
        return phoneSetSq;
    }

    private static final String[][] ANDROID_SETTINGS = {
            {"WiFi", android.provider.Settings.ACTION_WIFI_SETTINGS},
            {"Bluetooth", android.provider.Settings.ACTION_BLUETOOTH_SETTINGS},
            {"Pantalla y brillo", android.provider.Settings.ACTION_DISPLAY_SETTINGS},
            {"Sonido", android.provider.Settings.ACTION_SOUND_SETTINGS},
            {"Batería", "android.intent.action.POWER_USAGE_SUMMARY"},
            {"Aplicaciones", android.provider.Settings.ACTION_APPLICATION_SETTINGS},
            {"Notificaciones", "android.settings.ALL_APPS_NOTIFICATION_SETTINGS"},
            {"Almacenamiento", android.provider.Settings.ACTION_INTERNAL_STORAGE_SETTINGS},
            {"Ubicación", android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS},
            {"Seguridad y bloqueo", android.provider.Settings.ACTION_SECURITY_SETTINGS},
            {"Idioma y teclado", android.provider.Settings.ACTION_INPUT_METHOD_SETTINGS},
            {"Fecha y hora", android.provider.Settings.ACTION_DATE_SETTINGS},
            {"Accesibilidad", android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS},
            {"Datos móviles", android.provider.Settings.ACTION_DATA_ROAMING_SETTINGS},
            {"Acerca del teléfono", android.provider.Settings.ACTION_DEVICE_INFO_SETTINGS},
            {"Todos los ajustes", android.provider.Settings.ACTION_SETTINGS}};

    /** Ajustes del teléfono: primero de cuál, después qué parte. */
    private void phoneSettings() {
        boolean principal = hub.isPrincipal();
        Ui.Sheet sh = new Ui.Sheet(this).title("Ajustes del teléfono").message("¿De qué teléfono?");
        sh.option(Ui.GEAR, "Principal", principal ? "Este teléfono" : "Se abren en el principal", () -> settingsList(true));
        if (!solo) sh.option(Ui.GEAR, "Extendido", principal ? "Se abren en el extendido" : "Este teléfono", () -> settingsList(false));
        sh.button("Cerrar", false, null).show();
    }

    private void settingsList(boolean onPrincipal) {
        boolean here = onPrincipal == hub.isPrincipal() || solo;
        if (!here && !hub.connected()) { Toast.makeText(this, "El otro teléfono no está conectado", Toast.LENGTH_SHORT).show(); return; }
        Ui.Sheet sh = new Ui.Sheet(this).title("Ajustes del " + (onPrincipal ? "principal" : "extendido"));
        for (String[] it : ANDROID_SETTINGS) {
            final String action = it[1];
            sh.option(Ui.GEAR, it[0], "", () -> {
                if (here) openAndroidSettings(this, action);
                else { hub.send("openSettings", "a", action); Toast.makeText(this, "Abriendo en el " + (onPrincipal ? "principal" : "extendido"), Toast.LENGTH_SHORT).show(); }
            });
        }
        sh.button("Cerrar", false, null).show();
    }

    static void openAndroidSettings(android.content.Context c, String action) {
        try { c.startActivity(new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
        catch (Exception e) { try { c.startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception ignored) { } }
    }

    private SquareButton soloSq;

    /** Menú inicio: pasar a una pantalla (o volver a dos). */
    private SquareButton soloBtn() {
        if (soloSq == null) {
            soloSq = new SquareButton(this, SquareButton.DESKS, solo ? "Dos pantallas" : "Una pantalla", false, night);
            soloSq.setOnClickListener(v -> { showStart(false); setSolo(!solo); });
            squares.add(soloSq);
        }
        if (soloSq.getParent() instanceof android.view.ViewGroup) ((android.view.ViewGroup) soloSq.getParent()).removeView(soloSq);
        return soloSq;
    }

    private SquareButton phoneSq;

    /** "Teléfono normal" en el menú inicio: el principal vuelve a su pantalla de inicio y su teclado de siempre. */
    private SquareButton phoneBtn() {
        if (phoneSq == null) {
            phoneSq = new SquareButton(this, SquareButton.HOME, "Teléfono normal", false, night);
            phoneSq.setOnClickListener(v -> { showStart(false); phoneModeNow(); });
            squares.add(phoneSq);
        }
        if (phoneSq.getParent() instanceof android.view.ViewGroup) ((android.view.ViewGroup) phoneSq.getParent()).removeView(phoneSq);
        return phoneSq;
    }

    // ---------------- lista de notificaciones (al tocar la banda) ----------------

    private android.app.Dialog notifDlg;
    private LinearLayout notifBox;

    /** Ventana con las notificaciones pendientes: tocar abre, deslizar a un lado borra. */
    private void showNotifList() {
        if (tickerItems == null || tickerItems.length() == 0) return;
        notifBox = new LinearLayout(this);
        notifBox.setOrientation(LinearLayout.VERTICAL);
        fillNotifList();
        android.widget.ScrollView sc = new android.widget.ScrollView(this);
        sc.addView(notifBox);
        sc.setLayoutParams(new LinearLayout.LayoutParams(-1, Math.min(dp(420), getResources().getDisplayMetrics().heightPixels * 3 / 5)));
        notifDlg = new Ui.Sheet(this)
                .title("Notificaciones")
                .message("Toca para abrir · desliza a un lado para borrar")
                .view(sc)
                .button("Borrar todas", false, () -> {
                    for (int i = 0; tickerItems != null && i < tickerItems.length(); i++) notifAction("notifDel", tickerItems.optJSONObject(i));
                })
                .button("Cerrar", true, null)
                .show();
        notifDlg.setOnDismissListener(d -> { notifDlg = null; notifBox = null; });
    }

    private void fillNotifList() {
        if (notifBox == null) return;
        notifBox.removeAllViews();
        boolean n = CardTheme.sheetNight(night);
        if (tickerItems == null || tickerItems.length() == 0) {
            TextView e = new TextView(this);
            e.setText("No quedan notificaciones");
            e.setTextColor(Ui.muted(n));
            e.setPadding(dp(8), dp(16), dp(8), dp(16));
            notifBox.addView(e);
            return;
        }
        for (int i = 0; i < tickerItems.length(); i++) {
            org.json.JSONObject o = tickerItems.optJSONObject(i);
            if (o == null) continue;
            notifBox.addView(notifRow(o, n));
        }
    }

    private View notifRow(org.json.JSONObject o, boolean n) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(16));
        g.setColor((Ui.fg(n) & 0x00FFFFFF) | 0x12000000);
        row.setBackground(g);
        android.widget.ImageView ic = new android.widget.ImageView(this);
        try {
            byte[] d = android.util.Base64.decode(o.optString("icon"), android.util.Base64.DEFAULT);
            ic.setImageBitmap(android.graphics.BitmapFactory.decodeByteArray(d, 0, d.length));
        } catch (Exception ignored) { }
        row.addView(ic, new LinearLayout.LayoutParams(dp(34), dp(34)));
        LinearLayout tx = new LinearLayout(this);
        tx.setOrientation(LinearLayout.VERTICAL);
        tx.setPadding(dp(12), 0, 0, 0);
        TextView a = new TextView(this);
        long mins = Math.max(0, (System.currentTimeMillis() - o.optLong("time")) / 60000);
        a.setText(o.optString("app").toUpperCase() + "  ·  " + (mins < 1 ? "ahora" : mins < 60 ? "hace " + mins + " min" : "hace " + (mins / 60) + " h"));
        a.setTextSize(11);
        a.setLetterSpacing(0.05f);
        a.setTextColor(Ui.muted(n));
        TextView t = new TextView(this);
        t.setText(o.optString("title"));
        t.setTextSize(15);
        t.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        t.setTextColor(Ui.fg(n));
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        TextView b = new TextView(this);
        b.setText(o.optString("text"));
        b.setTextSize(13);
        b.setTextColor(Ui.muted(n));
        b.setMaxLines(2);
        b.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tx.addView(a);
        if (!o.optString("title").isEmpty()) tx.addView(t);
        if (!o.optString("text").isEmpty()) tx.addView(b);
        row.addView(tx, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(8);
        row.setLayoutParams(lp);
        // deslizar a un lado = borrar; tocar = abrir
        final float[] st = new float[2];
        final boolean[] dragging = {false};
        final int slop = android.view.ViewConfiguration.get(this).getScaledTouchSlop();
        row.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    st[0] = e.getRawX(); st[1] = e.getRawY(); dragging[0] = false;
                    v.setPressed(true);
                    return true;
                case android.view.MotionEvent.ACTION_MOVE: {
                    float dx = e.getRawX() - st[0], dy = e.getRawY() - st[1];
                    if (!dragging[0] && Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy)) {
                        dragging[0] = true;
                        v.getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    if (dragging[0]) {
                        v.setTranslationX(dx);
                        v.setAlpha(Math.max(0.2f, 1 - Math.abs(dx) / Math.max(1, v.getWidth())));
                    }
                    return true;
                }
                case android.view.MotionEvent.ACTION_UP: {
                    v.setPressed(false);
                    float dx = e.getRawX() - st[0];
                    if (dragging[0]) {
                        if (Math.abs(dx) > v.getWidth() * 0.35f) {
                            v.animate().translationX(Math.signum(dx) * v.getWidth()).alpha(0).setDuration(160)
                                    .withEndAction(() -> { notifAction("notifDel", o); removeFromList(o.optString("key")); }).start();
                        } else v.animate().translationX(0).alpha(1).setDuration(160).start();
                    } else {
                        notifAction("notifOpen", o);
                        if (notifDlg != null) notifDlg.dismiss();
                    }
                    return true;
                }
                case android.view.MotionEvent.ACTION_CANCEL:
                    v.setPressed(false);
                    v.animate().translationX(0).alpha(1).setDuration(160).start();
                    return true;
                default: return true;
            }
        });
        return row;
    }

    /** Borrar o abrir: lo hace el principal (aquí mismo si este es el principal). */
    private void notifAction(String what, org.json.JSONObject o) {
        String key = o.optString("key"), pkg = o.optString("pkg");
        if (hub.isPrincipal()) { if ("notifDel".equals(what)) hub.notifDel(key); else hub.notifOpen(key, pkg); }
        else if (hub.connected()) hub.send(what, "key", key, "pkg", pkg);
        else Toast.makeText(this, "El principal no está conectado", Toast.LENGTH_SHORT).show();
    }

    private void removeFromList(String key) {
        if (tickerItems == null) return;
        org.json.JSONArray left = new org.json.JSONArray();
        for (int i = 0; i < tickerItems.length(); i++) if (!key.equals(tickerItems.optJSONObject(i).optString("key"))) left.put(tickerItems.optJSONObject(i));
        tickerItems = left;
        applyTicker();
        fillNotifList();
    }

    /** Banda de notificaciones: en la barra del reloj del principal; en el extendido solo si el principal está en otra app. */
    private void applyTicker() {
        if (side == 1) {
            if (barTicker != null) barTicker.set(tickerItems);
            if (floatTicker != null) floatTicker.set(null);
        } else if (floatTicker != null) floatTicker.set(tickerHere ? null : tickerItems);
    }

    /** "Acerca de" en el menú inicio (con el punto rojo si hay una versión nueva). */
    private SquareButton aboutBtn() {
        if (aboutSq == null) {
            aboutSq = new SquareButton(this, SquareButton.STATUS, "Acerca de", false, night);
            aboutSq.setInfo(true);
            aboutSq.setOnClickListener(v -> { showStart(false); about(); });
            squares.add(aboutSq);
        }
        if (aboutSq.getParent() instanceof android.view.ViewGroup) ((android.view.ViewGroup) aboutSq.getParent()).removeView(aboutSq);
        aboutSq.setBadge(Updater.updateAvailable(this));
        return aboutSq;
    }

    /** El menú inicio aparece junto al logo (o junto al reloj en el principal). */
    private void placeStart() {
        if (startPanel == null || cbar == null) return;
        ChronoBar sb = solo && nbar != null ? nbar : cbar;
        boolean top = sb.edgeTop();
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2,
                (top ? Gravity.TOP : Gravity.BOTTOM) | (sb.kind == ChronoBar.NAV ? Gravity.END : Gravity.START));
        int m = sb.visualHeight() + dp(6);
        lp.setMargins(dp(12), top ? m : 0, dp(12), top ? 0 : m);
        startPanel.setLayoutParams(lp);
        startPanel.setPivotX(sb.kind == ChronoBar.NAV ? dp(260) : 0);
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
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(32), dp(32));
                lp.setMargins(dp(12), 0, dp(12), 0);
                navRow.addView(v, lp);
            }
            cbar.setNavRow(navRow);
            barPlayer = new BarPlayer(this, hub, ChronoBar.ink(night));
            barPlayer.setOnClose(() -> { cbar.showPlayer(false); main.postDelayed(() -> layoutChrono(false), 400); });
            cbar.setPlayer(barPlayer);
            recentsBar = new RecentsBar(this, ChronoBar.ink(night));
            recentsBar.setPick(this::onRecent);
            cbar.setRecents(recentsBar);
        } else {
            recentsBar = null;
            barPlayer = null;
            if (navRow != null) {   // vuelve a ser principal: los botones vuelven a su lugar
                while (navRow.getChildCount() > 0) { View v = navRow.getChildAt(0); navRow.removeViewAt(0); navPill.addView(v); }
            }
            barNotif = new NotifCard(this, night, false);
            barNotif.setTap(this::onNotifTap);
            cbar.setNotif(barNotif);
            barTicker = new TickerView(this, false);
            barTicker.setTap(pkg -> showNotifList());
            barTicker.setInk(Ui.fg(CardTheme.sheetNight(night)));
            cbar.setTicker(barTicker);
            if (tickerItems != null) applyTicker();
        }
        int at = rootView.indexOfChild(startOverlay);
        rootView.addView(cbar, at, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
    }

    /** Dónde va la barra: reloj arriba y navegación abajo; con el gestor abierto (en horizontal), las dos junto a la unión. */
    private boolean barAtTop() {
        boolean principal = side == 1;
        boolean atTop = principal;
        if (fileMode && stacked() && !solo) atTop = !atTop;
        return atTop;
    }

    private void layoutChrono(boolean animate) {
        if (cbar == null) return;
        int H = rootView.getHeight();
        if (H == 0) { rootView.post(() -> layoutChrono(false)); return; }
        if (solo && nbar != null && pane2 != null) { layoutSolo(animate, H); return; }
        boolean top = barAtTop();
        int bh = cbar.barHeight();
        float y = top ? 0 : H - bh;
        // el gestor ocupa lo que la barra deja libre
        int pTop, pBottom;
        int vh = cbar.visualHeight();
        if (top) { pTop = vh; pBottom = 0; } else { pTop = 0; pBottom = vh; }
        if (solo && nbar != null) {
            nbar.setEdgeTop(false);
            nbar.setTranslationY(H - nbar.barHeight());
            pBottom = nbar.visualHeight();
        }
        if (solo && pane2 != null) {
            // una pantalla: la mitad de arriba (bajo la barra del reloj) y la de abajo (sobre la barra del logo)
            FrameLayout.LayoutParams a1 = (FrameLayout.LayoutParams) pane.getLayoutParams(), a2 = (FrameLayout.LayoutParams) pane2.getLayoutParams();
            if (a1.height != H / 2 || a2.height != H - H / 2) {
                a1.height = H / 2; a1.gravity = Gravity.TOP;
                a2.height = H - H / 2; a2.gravity = Gravity.BOTTOM;
                pane.setLayoutParams(a1); pane2.setLayoutParams(a2);
            }
            pane.setPadding(0, pTop, 0, dp(3));
            pane2.setPadding(0, dp(3), 0, pBottom);
            if (!animate) { pane2.setVisibility(fileMode ? View.VISIBLE : View.GONE); pane2.setTranslationY(0); }
            else if (fileMode) {
                pane2.setVisibility(View.VISIBLE);
                pane2.setTranslationY(H);
                pane2.animate().translationY(0).setDuration(380).setInterpolator(new android.view.animation.DecelerateInterpolator(1.4f)).start();
            } else pane2.animate().translationY(H).setDuration(320).withEndAction(() -> { if (!fileMode) pane2.setVisibility(View.GONE); }).start();
        } else pane.setPadding(0, pTop, 0, pBottom);
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
        if (pane2 != null) { if (on) pane2.start(); else pane2.stop(); }
        if (cbar != null) cbar.showRecents(!on);   // las apps recientes se esconden con el gestor
        if (!on) main.postDelayed(pollRecents, 300);
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

    private void savePages() { prefs.edit().putInt(sk("pages"), desk.pages()).putInt(sk("page"), desk.page()).apply(); }

    /**
     * Una pantalla y dos pantallas tienen escritorios separados: cada modo guarda sus elementos y escritorios aparte
     * (desk.json / desk_solo.json) y en una pantalla nada se sincroniza con el otro teléfono.
     */
    private String sk(String k) { return solo ? "solo_" + k : k; }
    private String deskFile() { return solo ? "desk_solo.json" : "desk.json"; }

    @Override public void onPageChange(int page, int pages) {
        savePages();
        if (!solo) hub.send("pages", "n", pages, "cur", page);
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
        if (o == OrientationEventListener.ORIENTATION_UNKNOWN || solo) return;
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
        boolean introOn = prefs.getBoolean("introOn", true);
        sh.option(Ui.PALETTE, -1, "Intro al iniciar: " + (introOn ? "sí" : "no"), "Fondo naranja, logo y sonido al abrir ChronOS", introOn, () -> {
            prefs.edit().putBoolean("introOn", !introOn).apply();
            Toast.makeText(this, !introOn ? "La intro se verá al iniciar" : "Intro desactivada", Toast.LENGTH_SHORT).show();
        });
        sh.option(Ui.PALETTE, "Ver la intro ahora", "En los dos teléfonos, con la pregunta de cuál es el principal", () -> {
            if (hub.connected() && !solo) hub.send("introPlay");
            playIntroAgain();
        });
        boolean gpOn = GamePalActivity.enabled(this);
        sh.option(Ui.AUTO, -1, "Game Pal: " + (gpOn ? "sí" : "no"), "Control en pantalla en el extendido al jugar Brawl Stars", gpOn, () -> {
            prefs.edit().putBoolean("gamePal", !gpOn).apply();
            hub.send("gpPref", "on", !gpOn);
            Toast.makeText(this, !gpOn ? "Game Pal activado" : "Game Pal desactivado", Toast.LENGTH_SHORT).show();
        });
        if (SystemSetup.canSecure(this)) {
            boolean cl = prefs.getBoolean("cleanLock", true);
            sh.option(Ui.LOCK, -1, "Bloqueo sin notificaciones: " + (cl ? "sí" : "no"),
                    "Oculta notificaciones y controles de video en la pantalla de bloqueo de este teléfono", cl, () -> {
                        prefs.edit().putBoolean("cleanLock", !cl).apply();
                        SystemSetup.secure(this, prefs);
                        Toast.makeText(this, !cl ? "Pantalla de bloqueo limpia" : "Se vuelven a ver las notificaciones al bloquear", Toast.LENGTH_SHORT).show();
                    });
        }
        boolean laOn = LockArt.enabled(this);
        sh.option(Ui.PALETTE, -1, "Pantalla de bloqueo naranja: " + (laOn ? "sí" : "no"),
                "Fondo naranja en los dos, con el mensaje para desbloquear en el sentido de las pantallas", laOn, () -> {
                    setLockArt(!laOn, LockArt.method(this));
                    Toast.makeText(this, !laOn ? "Listo: apaga y enciende la pantalla para verla" : "Se volvió al fondo de bloqueo normal", Toast.LENGTH_LONG).show();
                });
        if (laOn && !solo) sh.option(Ui.PALETTE, "Voltear el mensaje de bloqueo", "Si el texto quedó de cabeza", () -> {
            prefs.edit().putBoolean("lockFlip", !prefs.getBoolean("lockFlip", false)).apply();
            LockArt.apply(this, hub);
            if (hub.connected()) hub.send("lockFlip", "on", prefs.getBoolean("lockFlip", false));
            Toast.makeText(this, "Volteado: apaga y enciende la pantalla para verlo", Toast.LENGTH_LONG).show();
        });
        if (laOn) sh.option(Ui.LOCK, "Mi bloqueo es con: " + LockArt.METHODS[LockArt.method(this)],
                "Toca para cambiar (PIN, patrón o contraseña). La huella se detecta sola", () -> {
                    int nm = (LockArt.method(this) + 1) % LockArt.METHODS.length;
                    setLockArt(true, nm);
                    Toast.makeText(this, "El mensaje dirá: " + LockArt.message(this), Toast.LENGTH_LONG).show();
                });
        boolean lidOn = LidWatch.enabled(this);
        sh.option(Ui.LOCK, -1, "Bloquear al cerrar la tapa: " + (lidOn ? "sí" : "no"),
                "Al cerrar la carcasa se bloquean los dos; al abrir se encienden", lidOn, () -> {
                    LidWatch.setEnabled(this, hub, !lidOn);
                    hub.send("lidPref", "on", !lidOn);
                    Toast.makeText(this, !lidOn ? "Se bloquearán al cerrar la tapa" : "Bloqueo con la tapa desactivado", Toast.LENGTH_SHORT).show();
                });
        sh.option(Ui.LOCK, "Prueba de sensores", "Proximidad, luz e imán de los dos teléfonos: para detectar la tapa cerrada",
                () -> startActivity(SensorTestActivity.intent(this)));
        if (solo) {
            boolean spl = prefs.getBoolean("soloSplit", true);
            sh.option(Ui.LAYOUT_STACK, -1, "Apps: " + (spl ? "arriba, con ChronOS abajo" : "pantalla completa"),
                    spl ? "Como en los dos teléfonos (necesita la accesibilidad)" : "La app ocupa toda la pantalla", spl, () -> {
                        prefs.edit().putBoolean("soloSplit", !spl).apply();
                        Toast.makeText(this, !spl ? "Las apps se abrirán arriba, con ChronOS abajo" : "Las apps se abrirán a pantalla completa", Toast.LENGTH_SHORT).show();
                    });
        }
        if (principal) {
            sh.option(h ? Ui.LAYOUT_STACK : Ui.LAYOUT_SIDE_V, deskSide(), h ? "Orientación: horizontal" : "Orientación: vertical",
                    h ? "Principal arriba, extendido abajo" : "Principal a la izquierda, extendido a la derecha", false, this::chooseOrientation);
            sh.option(locked() ? Ui.LOCK : Ui.UNLOCK, -1, locked() ? "Vista bloqueada" : "Vista libre",
                    locked() ? "No gira aunque muevas los teléfonos" : "Cambia sola entre horizontal y vertical al girar", false,
                    () -> setView(h, !locked(), false));
        } else {
            sh.option(layoutIcon(), deskSide(), "Orientación: la del principal", layoutName(), false, null);
        }
        boolean fp = prefs.getBoolean("flipP", false), fe = prefs.getBoolean("flipE", false);
        sh.option(Ui.LAYOUT_STACK, 0, "Principal: " + (fp ? "al revés" : "normal"), "Gira la pantalla del principal 180° (si lo pones al revés)", fp,
                () -> setFlip(!fp, fe, false));
        sh.option(Ui.LAYOUT_STACK, 1, "Extendido: " + (fe ? "al revés" : "normal"), "Gira la pantalla del extendido 180° (si lo pones al revés)", fe,
                () -> setFlip(fp, !fe, false));
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
                .option(Ui.PALETTE, "Personalizar", "Color de fondo, tarjetas y menús, fondo de juegos", this::personalize)
                .option(Ui.NOTE, -1, "Notificaciones en la barra: " + (principal ? (NotifService.enabled(this) ? "sí" : "no") : "del principal"),
                        "Probar, activar o reparar las notificaciones del principal", principal && NotifService.enabled(this), this::guideNotifs)
                .option(Ui.RECEIVE, -1, "Llamadas: " + (principal ? (CallHub.ready(this) ? "listo" : "falta permiso") : "contestar aquí"),
                        principal ? "Permitir que el extendido conteste o cuelgue las llamadas de este teléfono"
                                  : "Las llamadas del principal se contestan o cuelgan desde este teléfono", principal && CallHub.ready(this),
                        () -> {
                            if (principal) requestPermissions(CallHub.PERMS, 61);
                            else Toast.makeText(this, "Actívalo en el principal: Ajustes → Llamadas", Toast.LENGTH_LONG).show();
                        })
                .option(Ui.HOME, "Pantalla de inicio", (principal ? "Principal: " + homeLabel(homeDefault(this)) + " · Extendido: " + (otherHomeKnown ? homeLabel(otherHome) : "…")
                        : "Principal: " + (otherHomeKnown ? homeLabel(otherHome) : "…") + " · Extendido: " + homeLabel(homeDefault(this))), this::launcherMenu)
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
        if (aboutSq != null) aboutSq.setBadge(Updater.updateAvailable(this));
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

    /** Personalizar: los colores de la mesa y de las tarjetas y menús, juntos. */
    private void personalize() {
        new Ui.Sheet(this)
                .title("Personalizar")
                .option(Ui.PALETTE, "Color de fondo", "El color de la mesa", this::chooseColor)
                .option(Ui.PALETTE, "Tarjetas y menús", CardTheme.glass == CardTheme.GLASS_LIGHT ? "Cristal claro"
                        : CardTheme.glass == CardTheme.GLASS_DARK ? "Cristal oscuro" : CardTheme.isIntense() ? "Cristal intenso"
                        : CardTheme.isAero() ? "Cristal Aero" : CardTheme.isConsole() ? "Tema " + CardTheme.CONSOLE_NAMES[CardTheme.console]
                        : CardTheme.color == 0 ? "Automático" : "Color propio", this::chooseCardColor)
                .option(Ui.IMAGE, -1, "Fondo de juegos: " + (GameBg.enabled(this) ? "sí" : "no"),
                        "Al abrir un juego en el principal, el extendido muestra una imagen del juego", GameBg.enabled(this), () -> {
                            boolean on = !GameBg.enabled(this);
                            prefs.edit().putBoolean("gameBg", on).apply();
                            if (!on) { GameBg.current = null; GameBg.currentPkg = ""; desk.setBgImage(null); }
                            main.postDelayed(this::personalize, 200);
                        })
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

    /**
     * Notificaciones en la barra: Android 13+ bloquea el "Acceso a notificaciones" de las apps instaladas fuera de
     * Play Store hasta que se permiten los ajustes restringidos de ChronOS. Por eso son dos pasos.
     */
    void guideNotifs() {
        boolean principal = hub.isPrincipal();
        Ui.Sheet sh = new Ui.Sheet(this)
                .title("Notificaciones en la barra")
                .message(principal
                        ? (NotifService.enabled(this) ? "El permiso está activo. Prueba que lleguen; si no llegan, la prueba reinicia el lector de notificaciones."
                                : "Android pide dos pasos. Si en el paso 2 ChronOS aparece gris o dice \"ajuste restringido\", haz primero el paso 1.")
                        : "Las notificaciones son las del principal. La prueba se hace allá y el resultado aparece aquí.");
        sh.option(Ui.CHECK, "Probar notificaciones", "Revisa el permiso, reinicia el lector si hace falta y manda un aviso de prueba", this::testNotifs);
        if (principal) {
            sh.option(Ui.GEAR, "1. Permitir ajustes restringidos", "Info de la app → ⋮ (arriba a la derecha) → Permitir ajustes restringidos", () ->
                    openSettings(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()),
                            "Toca ⋮ arriba a la derecha → \"Permitir ajustes restringidos\" y vuelve."));
            sh.option(Ui.NOTE, "2. Acceso a notificaciones", NotifService.enabled(this) ? "ChronOS ya está activado" : "Activa ChronOS en la lista", () ->
                    openSettings("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS", null,
                            "Activa ChronOS en \"Acceso a notificaciones\" y vuelve."));
        } else {
            sh.option(Ui.GEAR, "Activar en el principal", "Abre en el principal los pasos para dar el permiso", () -> {
                hub.send("notifSetup");
                Toast.makeText(this, "Sigue los pasos en el principal", Toast.LENGTH_LONG).show();
            });
        }
        sh.button("Cerrar", false, null).show();
    }

    private void testNotifs() {
        if (hub.isPrincipal()) {
            if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 3);
            NotifTest.run(this, hub);
        } else if (hub.connected()) hub.send("notifTest");
        else { Toast.makeText(this, "El principal no está conectado", Toast.LENGTH_SHORT).show(); return; }
        Toast.makeText(this, "Probando… (unos segundos)", Toast.LENGTH_SHORT).show();
    }

    /** Resultado de la prueba, paso por paso, con qué hacer si algo falla. */
    private void notifResult(org.json.JSONObject m) {
        boolean perm = m.optBoolean("perm"), conn = m.optBoolean("conn"), post = m.optBoolean("post"), seen = m.optBoolean("seen");
        StringBuilder b = new StringBuilder();
        b.append(perm ? "✓" : "✗").append("  Permiso \"Acceso a notificaciones\" en el principal\n");
        if (perm) b.append(conn ? "✓" : "✗").append("  Lector de notificaciones funcionando").append(m.optBoolean("fixed") ? " (se reinició)" : "").append("\n");
        if (perm) b.append(post ? "✓" : "✗").append("  Aviso de prueba publicado\n");
        if (perm && post) b.append(seen ? "✓" : "✗").append("  ChronOS recibió el aviso\n");
        String what;
        if (!perm) what = "Falta el permiso. Toca \"Activar\" y sigue los dos pasos en el principal.";
        else if (!post) what = "El principal no deja que ChronOS muestre avisos: Ajustes → Apps → ChronOS → Notificaciones → permitir.";
        else if (!seen) what = "Android no le pasa los avisos a ChronOS. En el principal: Ajustes → Apps → ChronOS → Ahorro de batería → Sin restricciones, "
                + "y activa \"Inicio automático\". Después vuelve a probar.";
        else what = "Todo funciona. Deberías haber visto \"Prueba de ChronOS\" en la barra del reloj (si ChronOS estaba a la vista en el principal) o como tarjeta en el extendido.";
        Ui.Sheet sh = new Ui.Sheet(this).title(seen ? "Las notificaciones funcionan" : "Las notificaciones no llegan").message(b + "\n" + what);
        if (!perm || !seen) sh.button("Activar", true, () -> { if (hub.isPrincipal()) guideNotifs(); else hub.send("notifSetup"); });
        sh.button("Cerrar", false, null).show();
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
                .option(Ui.GRID, "PALS", "Compañeritos para las apps que no se ven bien en horizontal (Claude…)", this::palsMenu)
                .option(Ui.PALETTE, "DUAL-Draw", "Dibuja abajo con zoom y presión; arriba se ve la obra completa (con capas)", this::openDualDraw)
                .option(Ui.NOTE, "Nota", "Una nota para escribir", this::addNote)
                .option(Ui.IMAGE, "Imagen", "Una foto de la galería", this::pickImage)
                .option(Ui.BATTERY, "Batería", "Nivel de batería del principal y del extendido", this::addBattery)
                .option(Ui.CLOCK, "Reloj", "Hora, fecha y próxima alarma; al tocarlo abre las alarmas", this::addClock)
                .option(Ui.GRID, "Apps recientes", "Barra deslizante: lo que queda en la mira es la app al frente en el principal", this::addRecents)
                .option(Ui.WIDGETS, "Widget", "Widgets de tus apps (clima, calendario, música...)", this::addWidget)
                .option(Ui.FOLDER, "Archivos", "Pasar archivos entre los dos teléfonos", this::openFiles)
                .option(Ui.IMAGE, "Cámara", "Widget con el visor y los botones de la cámara del principal", this::addCamera)
                .option(Ui.RECEIVE, "Llamadas", "Últimas llamadas del principal, marcar un número o buscar un contacto", this::addCalls)
                .option(Ui.PALETTE, "Brillo", "Dos perillas, como una mesa de audio: el brillo de cada pantalla, con candado para moverlas juntas", this::addBright)
                .option(Ui.AUTO, "Linternas", "Un interruptor para la linterna del principal y otro para la del extendido", this::addTorch)
                .option(Ui.HOME, "Modo teléfono normal", "El principal vuelve a su pantalla de inicio y su teclado de siempre", this::phoneModeNow)
                .option(Ui.RECEIVE, "Actualizar app", Updater.updateAvailable(this) ? "Hay una versión nueva: " + Updater.latestKnown(this)
                        : "Versión instalada: " + Updater.clean(Updater.installedVersion(this)), () -> Updater.check(this, hub))
                .badgeIf(Updater.updateAvailable(this), "1")
                .button("Cerrar", false, null)
                .show();
    }

    /** PALS: tus compañeritos, el catálogo de redes sociales y PALS propios de cualquier página. */
    private void palsMenu() {
        Ui.Sheet sh = new Ui.Sheet(this).title("PALS");
        for (Pals.Pal p : Pals.active(this))
            sh.option(Ui.OPEN, p.name, p.custom ? "PAL propio" : "social".equals(p.group) ? "Red social" : "Inteligencia artificial", () -> palMenu(p));
        sh.option(Ui.GRID, "Catálogo de redes sociales", "Instagram, Facebook, X, TikTok, WhatsApp Web… en horizontal y con tu cuenta", this::palsCatalog);
        sh.option(Ui.PLUS, "Crear un PAL de cualquier página", "Una página que no se vea bien en ChronOS", this::palCreate);
        sh.button("Cerrar", false, null).show();
    }

    private void palsSync() { if (hub.connected()) hub.send("palsSync", "s", Pals.syncState(this)); }

    private void palMenu(Pals.Pal p) {
        Ui.Sheet sh = new Ui.Sheet(this).title(p.name)
                .option(Ui.OPEN, "Abrir", "Se abre en el principal; escribes con el teclado del extendido", () -> openOnPrincipal("pal:" + p.id, p.name))
                .option(Ui.PIN, "Poner en la mesa", "Como una app más, con su ícono de naranja", () -> palOnDesk(p));
        if (p.custom) sh.option(Ui.TRASH, "Eliminar este PAL", null, () -> { Pals.removeCustom(this, p.id); palsSync(); });
        else if (!"claude".equals(p.id)) sh.option(Ui.CROSS, "Quitar de mis PALS", "Vuelve al catálogo", () -> { Pals.setEnabled(this, p.id, false); palsSync(); });
        sh.button("Cerrar", false, null).show();
    }

    private void palOnDesk(Pals.Pal p) {
        Item it = new Item();
        it.kind = Item.APP; it.pkg = "pal:" + p.id; it.title = p.name; it.w = sized(0.24);
        android.graphics.Bitmap ic = Pals.icon(this, p, 192);
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        ic.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bos);
        it.setImage(android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP));
        double[] pos = desk.spotOn(it.w, deskSide());
        it.x = pos[0]; it.y = pos[1];
        it.page = desk.page();
        addItem(it);
        sendImage(it);
        main.postDelayed(() -> desk.startEditing(it.id), 200);
    }

    /** Catálogo: redes sociales hechas para el teléfono en vertical; al activarlas aparecen en tus apps. */
    private void palsCatalog() {
        Ui.Sheet sh = new Ui.Sheet(this).title("Catálogo de redes sociales")
                .message("Las que actives aparecen en tus apps. Al abrirlas inicias sesión una vez (con usuario y contraseña, no con el botón de Google) y queda guardada.");
        for (Pals.Pal p : Pals.ALL) {
            if (!"social".equals(p.group)) continue;
            boolean on = Pals.enabled(this, p);
            sh.option(on ? Ui.CHECK : Ui.PLUS, -1, p.name.replace(" PAL", ""), on ? "Activo · toca para quitarlo" : "Toca para agregarlo", on, () -> {
                Pals.setEnabled(this, p.id, !on);
                palsSync();
                Toast.makeText(this, !on ? p.name + " está en tus apps" : p.name + " quitado", Toast.LENGTH_SHORT).show();
                palsCatalog();
            });
        }
        sh.button("Listo", true, null).show();
    }

    /** PAL propio: nombre y dirección (se puede usar un enlace copiado). */
    private void palCreate() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        android.widget.EditText name = new android.widget.EditText(this), url = new android.widget.EditText(this);
        name.setHint("Nombre (por ejemplo: Diario)");
        url.setHint("Dirección (https://…)");
        url.setInputType(android.text.InputType.TYPE_TEXT_VARIATION_URI | android.text.InputType.TYPE_CLASS_TEXT);
        box.addView(name);
        box.addView(url);
        TextView paste = new TextView(this);
        paste.setText("⎘  Usar el enlace copiado");
        paste.setTextSize(15);
        paste.setPadding(0, dp(10), 0, dp(6));
        paste.setOnClickListener(v -> {
            android.content.ClipboardManager cb = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            CharSequence t = cb != null && cb.hasPrimaryClip() && cb.getPrimaryClip() != null && cb.getPrimaryClip().getItemCount() > 0
                    ? cb.getPrimaryClip().getItemAt(0).coerceToText(this) : null;
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("https?://\\S+").matcher(t == null ? "" : t);
            if (m.find()) url.setText(m.group()); else Toast.makeText(this, "No hay un enlace copiado", Toast.LENGTH_SHORT).show();
        });
        box.addView(paste);
        android.widget.CheckBox wide = new android.widget.CheckBox(this);
        wide.setText("Vista de computador (más ancha, mejor en horizontal)");
        wide.setChecked(true);
        box.addView(wide);
        new Ui.Sheet(this).title("Crear un PAL").view(box)
                .button("Cancelar", false, null)
                .button("Crear", true, () -> {
                    String u = url.getText().toString().trim();
                    if (!u.isEmpty() && !u.startsWith("http")) u = "https://" + u;
                    Uri uu = Uri.parse(u);
                    if (uu.getHost() == null || !uu.getHost().contains(".")) { Toast.makeText(this, "Falta una dirección válida", Toast.LENGTH_SHORT).show(); return; }
                    String n = name.getText().toString().trim();
                    if (n.isEmpty()) n = uu.getHost().replaceFirst("^www\\.", "");
                    Pals.addCustom(this, n + " PAL", u, wide.isChecked());
                    palsSync();
                    Toast.makeText(this, n + " PAL está en tus apps", Toast.LENGTH_SHORT).show();
                }).show();
    }

    /** DUAL-Draw: en el principal se ve la obra, en el extendido se dibuja (en una pantalla, las dos cosas). */
    private void openDualDraw() {
        if (solo || !hub.connected()) {
            if (!solo) Toast.makeText(this, "Sin el otro teléfono, DUAL-Draw se abre en una sola pantalla", Toast.LENGTH_LONG).show();
            startActivity(DualDrawActivity.intent(this, "solo"));
            return;
        }
        hub.send("dwOpen");
        startActivity(DualDrawActivity.intent(this, side == 1 ? "view" : "edit"));
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

    private void addTorch() {
        Item it = new Item();
        it.kind = Item.TORCH;
        it.w = sized(0.62);
        double[] p = desk.spot(it.w);
        it.x = p[0]; it.y = p[1];
        addItem(it);
        Torch.listen(this, hub);
    }

    // ---------------- llamadas ----------------

    private org.json.JSONArray callItems = new org.json.JSONArray();
    private boolean callsOk = true;

    private void addCalls() {
        Item it = new Item();
        it.kind = Item.CALLS;
        it.w = sized(0.62);
        double[] p = desk.spot(it.w);
        it.x = p[0]; it.y = p[1];
        addItem(it);
        refreshCalls();
    }

    /** Pide las últimas llamadas al principal (o las lee aquí si este es el principal). */
    private void refreshCalls() {
        if (hub.isPrincipal()) RecentApps.worker.execute(() -> { org.json.JSONObject m = Phone.callLog(getApplicationContext()); main.post(() -> onCallLog(m)); });
        else if (hub.connected()) hub.send("callLogReq");
    }

    private void onCallLog(org.json.JSONObject m) {
        callItems = m.optJSONArray("items") != null ? m.optJSONArray("items") : new org.json.JSONArray();
        callsOk = m.optBoolean("perm", true);
        desk.invalidate();
    }

    private final Runnable callsTick = new Runnable() {
        @Override public void run() {
            main.removeCallbacks(this);
            if (!resumed) return;
            boolean any = false;
            for (Item it : desk.items) if (Item.CALLS.equals(it.kind)) { any = true; break; }
            if (any) refreshCalls();
            main.postDelayed(this, 30_000);
        }
    };

    @Override public org.json.JSONArray calls() { return callItems; }
    @Override public boolean callsAllowed() { return callsOk; }

    @Override public void onCalls(String what, String num) {
        switch (what) {
            case "dial": dial(num); break;
            case "pad": dialPad(); break;
            case "contacts": contactsSheet(); break;
            case "perm":
                if (hub.isPrincipal()) requestPermissions(CallHub.PERMS, 61);
                else if (hub.connected()) { hub.send("callPerm"); Toast.makeText(this, "Acepta los permisos en el principal", Toast.LENGTH_LONG).show(); }
                break;
            default: break;
        }
    }

    private void dial(String num) {
        if (num == null || num.trim().isEmpty()) return;
        if (hub.isPrincipal()) Phone.dial(this, hub, num);
        else if (hub.connected()) hub.send("dial", "num", num);
        else { Toast.makeText(this, "El principal no está conectado", Toast.LENGTH_SHORT).show(); return; }
        Toast.makeText(this, "Llamando a " + num + "…", Toast.LENGTH_SHORT).show();
        main.postDelayed(this::refreshCalls, 4000);
    }

    /** Teclado numérico para marcar: en horizontal, el número y los botones a la izquierda y las teclas a la derecha. */
    private void dialPad() {
        boolean n = CardTheme.sheetNight(night);
        boolean land = getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        // tamaño de tecla según el espacio: que las 4 filas quepan en la altura de la pantalla
        int avail = dm.heightPixels - dp(land ? 120 : 330);
        int key = Math.max(dp(36), Math.min(dp(64), avail / 4 - dp(8)));
        final StringBuilder num = new StringBuilder();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(land ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        // número y botones
        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setGravity(Gravity.CENTER);
        TextView disp = new TextView(this);
        disp.setTextSize(land ? 26 : 28);
        disp.setGravity(Gravity.CENTER);
        disp.setTextColor(Ui.fg(n));
        disp.setSingleLine(true);
        disp.setEllipsize(android.text.TextUtils.TruncateAt.START);
        disp.setPadding(dp(8), dp(4), dp(8), dp(10));
        disp.setText(" ");
        side.addView(disp, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout acts = new LinearLayout(this);
        acts.setGravity(Gravity.CENTER);
        TextView del = padBtn("⌫", n, false);
        TextView call = padBtn("Llamar", n, true);
        acts.addView(del, new LinearLayout.LayoutParams(dp(64), dp(44)));
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(dp(120), dp(44));
        cl.setMarginStart(dp(10));
        acts.addView(call, cl);
        side.addView(acts);
        // teclas
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        String[] keys = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#"};
        for (int r = 0; r < 4; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER);
            for (int k = 0; k < 3; k++) {
                final String kk = keys[r * 3 + k];
                TextView b = new TextView(this);
                b.setText(kk);
                b.setTextSize(Math.max(16, key / dm.density * 0.36f));
                b.setGravity(Gravity.CENTER);
                b.setTextColor(Ui.fg(n));
                GradientDrawable g = new GradientDrawable();
                g.setShape(GradientDrawable.OVAL);
                g.setColor((Ui.fg(n) & 0x00FFFFFF) | 0x14000000);
                b.setBackground(g);
                b.setOnClickListener(v -> { v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY); num.append(kk); disp.setText(num); });
                if ("0".equals(kk)) b.setOnLongClickListener(v -> { num.append("+"); disp.setText(num); return true; });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(key, key);
                lp.setMargins(dp(6), dp(3), dp(6), dp(3));
                row.addView(b, lp);
            }
            grid.addView(row);
        }
        if (land) {
            box.addView(side, new LinearLayout.LayoutParams(dp(220), -2));
            LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(-2, -2);
            gl.setMarginStart(dp(16));
            box.addView(grid, gl);
        } else {
            box.addView(side, new LinearLayout.LayoutParams(-1, -2));
            box.addView(grid);
        }
        final android.app.Dialog dlg = new Ui.Sheet(this).title("Marcar").view(box).button("Cerrar", false, null).show();
        del.setOnClickListener(v -> { if (num.length() > 0) num.setLength(num.length() - 1); disp.setText(num.length() == 0 ? " " : num); });
        del.setOnLongClickListener(v -> { num.setLength(0); disp.setText(" "); return true; });
        call.setOnClickListener(v -> { if (num.length() == 0) return; dlg.dismiss(); dial(num.toString()); });
    }

    private TextView padBtn(String t, boolean n, boolean green) {
        TextView b = new TextView(this);
        b.setText(t);
        b.setTextSize(16);
        b.setGravity(Gravity.CENTER);
        b.setTextColor(green ? 0xFFFFFFFF : Ui.fg(n));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(22));
        g.setColor(green ? 0xFF2EA043 : (Ui.fg(n) & 0x00FFFFFF) | 0x14000000);
        b.setBackground(g);
        return b;
    }

    private View findButton(View v, String text) {
        if (v instanceof TextView && text.contentEquals(((TextView) v).getText())) return v;
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) { View r = findButton(g.getChildAt(i), text); if (r != null) return r; }
        }
        return null;
    }

    private LinearLayout contactList;
    private String contactQuery = "";

    /** Buscar un contacto del principal y llamarlo. */
    private void contactsSheet() {
        boolean n = CardTheme.sheetNight(night);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        EditText q = field("Buscar nombre o número");
        box.addView(q, new LinearLayout.LayoutParams(-1, -2));
        contactList = new LinearLayout(this);
        contactList.setOrientation(LinearLayout.VERTICAL);
        android.widget.ScrollView sc = new android.widget.ScrollView(this);
        sc.addView(contactList);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, Math.min(dp(340), getResources().getDisplayMetrics().heightPixels / 2));
        sl.topMargin = dp(8);
        box.addView(sc, sl);
        final Runnable search = () -> askContacts(q.getText().toString());
        q.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence a, int b, int c, int d) { }
            @Override public void onTextChanged(CharSequence a, int b, int c, int d) { }
            @Override public void afterTextChanged(android.text.Editable e) { main.removeCallbacks(search); main.postDelayed(search, 250); }
        });
        android.app.Dialog d = new Ui.Sheet(this).title("Contactos").view(box).button("Cerrar", false, null).show();
        d.setOnDismissListener(x -> contactList = null);
        askContacts("");
    }

    private void askContacts(String q) {
        contactQuery = q;
        if (hub.isPrincipal()) RecentApps.worker.execute(() -> { org.json.JSONObject m = Phone.contacts(getApplicationContext(), q); main.post(() -> fillContacts(m)); });
        else if (hub.connected()) hub.send("contactsReq", "q", q);
    }

    private void fillContacts(org.json.JSONObject m) {
        if (contactList == null || !contactQuery.equals(m.optString("q"))) return;
        contactList.removeAllViews();
        boolean n = CardTheme.sheetNight(night);
        org.json.JSONArray a = m.optJSONArray("items");
        if (a == null || a.length() == 0) {
            TextView e = new TextView(this);
            e.setText("Sin resultados");
            e.setTextColor(Ui.muted(n));
            e.setPadding(dp(8), dp(12), dp(8), dp(12));
            contactList.addView(e);
            return;
        }
        for (int i = 0; i < a.length(); i++) {
            org.json.JSONObject o = a.optJSONObject(i);
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(10), dp(8), dp(10), dp(8));
            LinearLayout tx = new LinearLayout(this);
            tx.setOrientation(LinearLayout.VERTICAL);
            TextView t1 = new TextView(this);
            t1.setText(o.optString("name"));
            t1.setTextSize(15);
            t1.setTextColor(Ui.fg(n));
            TextView t2 = new TextView(this);
            t2.setText(o.optString("num"));
            t2.setTextSize(12);
            t2.setTextColor(Ui.muted(n));
            tx.addView(t1);
            tx.addView(t2);
            row.addView(tx, new LinearLayout.LayoutParams(0, -2, 1));
            TextView call = new TextView(this);
            call.setText("Llamar");
            call.setTextColor(0xFFFFFFFF);
            call.setTextSize(13);
            call.setPadding(dp(14), dp(6), dp(14), dp(6));
            GradientDrawable g = new GradientDrawable();
            g.setColor(0xFF2EA043);
            g.setCornerRadius(dp(16));
            call.setBackground(g);
            call.setOnClickListener(v -> dial(o.optString("num")));
            row.addView(call);
            contactList.addView(row);
        }
    }

    private void addBright() {
        Item it = new Item();
        it.kind = Item.BRIGHT;
        it.w = sized(0.36);
        double[] p = desk.spot(it.w);
        it.x = p[0]; it.y = p[1];
        addItem(it);
        if (hub.connected()) hub.send("brAsk");
        if (!Bright.canWrite(this)) askBrightPerm();
    }

    private boolean brightAsked;

    /** Android pide "Modificar ajustes del sistema" para cambiar el brillo (una vez por teléfono). */
    private void askBrightPerm() {
        if (brightAsked) return;
        brightAsked = true;
        new Ui.Sheet(this)
                .title("Permiso para el brillo")
                .message("Para cambiar el brillo de este teléfono, Android pide activar \"Modificar ajustes del sistema\" para ChronOS.")
                .button("Ahora no", false, null)
                .button("Activar", true, () -> { try { startActivity(Bright.permIntent(this)); } catch (Exception ignored) { } })
                .show();
    }

    @Override public float bright(boolean principal) {
        if (principal == hub.isPrincipal()) return Bright.mine(this);
        return hub.connected() ? Bright.other : -1;
    }

    @Override public boolean brightLock() { return Bright.locked(this); }

    private long brSentAt;

    @Override public void onBright(boolean principal, float f, boolean done) {
        boolean lock = Bright.locked(this);
        boolean here = principal == hub.isPrincipal();
        // aquí: directo; con el candado, las dos pantallas quedan con la misma intensidad
        if (here || lock) {
            if (!Bright.set(this, f)) {
                android.view.WindowManager.LayoutParams lp = getWindow().getAttributes();   // sin permiso: al menos mientras se ve ChronOS
                lp.screenBrightness = Math.max(0.02f, f * f);
                getWindow().setAttributes(lp);
                if (done) askBrightPerm();
            }
        }
        if ((!here || lock) && hub.connected()) {
            long now = android.os.SystemClock.uptimeMillis();
            if (done || now - brSentAt > 70) { brSentAt = now; hub.send("brSet", "f", (double) f, "done", done); }
            Bright.other = f;
        }
        desk.invalidate();
    }

    @Override public void onBrightLock() {
        boolean on = !Bright.locked(this);
        Bright.setLocked(this, on);
        if (hub.connected()) hub.send("brLock", "on", on);
        if (on) {   // al cerrar el candado, el extendido toma el brillo del principal
            float base = hub.isPrincipal() ? Bright.mine(this) : (Bright.other >= 0 ? Bright.other : Bright.mine(this));
            onBright(true, base, true);
        }
        Toast.makeText(this, on ? "Brillo unido: las dos pantallas iguales" : "Brillo independiente", Toast.LENGTH_SHORT).show();
        desk.invalidate();
    }

    @Override public boolean torchOn(boolean principal) { return Torch.on(hub, principal); }
    @Override public void onTorch(boolean principal) { Torch.toggleOne(this, hub, principal); }

    /** Herramientas → Modo teléfono normal: en el principal se hace ya; desde el extendido se le pregunta al principal. */
    private void phoneModeNow() {
        if (hub.isPrincipal()) { PhoneMode.enter(this, hub); return; }
        if (!hub.connected()) { Toast.makeText(this, "El principal no está conectado", Toast.LENGTH_SHORT).show(); return; }
        hub.send("phoneOffer", "title", "¿Volver al modo teléfono normal?");
        Toast.makeText(this, "Confirma en el principal", Toast.LENGTH_SHORT).show();
    }

    /** El principal ofrece volver a la interfaz normal del teléfono (extendido sin batería o sin conexión). */
    private android.app.Dialog phoneSheet;

    /**
     * El principal no encuentra al extendido (o el extendido se queda sin batería): o se vuelve al teléfono normal,
     * o se busca al extendido a la fuerza. Antes de volver al normal se ofrece actualizar si hay versión nueva.
     */
    private void phoneOffer(String title) {
        if (!hub.isPrincipal() || PhoneMode.active(this)) return;
        if (phoneSheet != null && phoneSheet.isShowing()) phoneSheet.dismiss();
        boolean lost = !hub.connected();
        Ui.Sheet sh = new Ui.Sheet(this).title(lost ? PhoneMode.LOST : title)
                .message(lost ? "Puedes usar este teléfono como uno normal (su inicio y su teclado de siempre) o buscar al extendido a la fuerza."
                              : "Puedes seguir usando este teléfono con su pantalla de inicio y su teclado de siempre. Cuando quieras volver, abre ChronOS.");
        sh.option(Ui.HOME, "Usar el teléfono normal", "Cuando el extendido vuelva, te aviso para regresar a ChronOS", this::goNormal);
        if (lost) {
            sh.option(Ui.WIFI, "Buscar al extendido", "Prueba su última dirección y todo el WiFi (unos segundos)", () -> {
                linkInfo = "Buscando al extendido en todo el WiFi…";
                updateStatus();
                hub.forceLink();
                Toast.makeText(this, "Buscando al extendido…", Toast.LENGTH_SHORT).show();
            });
            sh.option(Ui.GEAR, "Escribir la IP del extendido", "Está en el extendido: menú inicio → Conexión", this::showConnection);
        }
        sh.button("Seguir en ChronOS", false, null);
        phoneSheet = sh.show();
    }

    // ---------------- pantalla unida: ChronOS sobre el bloqueo del extendido ----------------
    static volatile MainActivity live;

    /** El principal se desbloqueó: si el extendido tiene PIN o huella, ChronOS se muestra encima del bloqueo. */
    void showOverLock(boolean on) {
        try {
            setShowWhenLocked(on);
            setTurnScreenOn(on);
        } catch (Exception ignored) { }
    }

    /** El principal se bloqueó: ChronOS deja de verse sobre el bloqueo. */
    static void dropOverLock() {
        MainActivity a = live;
        if (a != null) a.runOnUiThread(() -> a.showOverLock(false));
    }

    // ---------------- modo una pantalla ----------------
    // Un solo teléfono, en vertical: la mitad de arriba es la mesa del principal (con la barra del reloj) y la de abajo
    // la del extendido (con la barra del logo, el menú inicio y las apps recientes). Las apps se abren en este teléfono.

    private boolean solo;
    private DeskView desk2;
    private ChronoBar nbar;

    /** Pantalla de bloqueo naranja: guarda el ajuste y lo aplica en los dos teléfonos. */
    private void setLockArt(boolean on, int method) {
        prefs.edit().putBoolean("lockArt", on).putInt("lockMethod", method).apply();
        LockArt.apply(this, hub);
        if (hub.connected() && !soloMode(this)) hub.send("lockArt", "on", on, "m", method);
    }

    static boolean soloMode(android.content.Context c) { return c.getSharedPreferences("mesa", MODE_PRIVATE).getBoolean("soloMode", false); }

    /** Entra o sale del modo una pantalla (se vuelve a armar la pantalla). */
    void setSolo(boolean on) {
        if (on == solo) return;
        SharedPreferences.Editor e = prefs.edit().putBoolean("soloMode", on);
        if (on) {
            e.putInt("soloPrevSide", side);
            e.apply();
            if (side != 1) hub.setSide(1);   // este teléfono hace de principal mientras tanto
        } else {
            int prev = prefs.getInt("soloPrevSide", side);
            e.apply();
            if (prev >= 0 && prev != hub.side()) hub.setSide(prev);
        }
        Toast.makeText(this, on ? "Modo una pantalla" : "Modo dos pantallas", Toast.LENGTH_SHORT).show();
        recreate();
    }

    private void setupSolo(FrameLayout root) {
        desk.setSide(0);
        desk.setVertical(true);
        desk2 = new DeskView(this, night);
        desk2.setDeskColor(prefs.getInt("deskColor", 0), night);
        desk2.setHost(this);
        desk.setTwin(desk2);
        desk2.setSide(1);
        root.addView(desk2, 1, new FrameLayout.LayoutParams(-1, 0, Gravity.BOTTOM));
        final boolean sp = isInMultiWindowMode();   // pantalla partida: arriba está la app, aquí solo la mesa de abajo
        if (sp) desk.setVisibility(View.GONE);
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int H = b - t;
            if (H <= 0) return;
            FrameLayout.LayoutParams a1 = (FrameLayout.LayoutParams) desk.getLayoutParams(), a2 = (FrameLayout.LayoutParams) desk2.getLayoutParams();
            int h1 = sp ? 0 : H / 2;
            if (a1.height == h1 && a2.height == H - h1) return;
            a1.height = h1; a1.gravity = Gravity.TOP;
            a2.height = H - h1; a2.gravity = Gravity.BOTTOM;
            main.post(() -> { desk.setLayoutParams(a1); desk2.setLayoutParams(a2); });
        });
        // barra de abajo (como la del extendido): accesos rápidos, logo con el menú inicio y apps recientes
        nbar = new ChronoBar(this, ChronoBar.NAV, night);
        nbar.setListener(new ChronoBar.Listener() {
            @Override public void onBarSwipe(ChronoBar b, boolean down) { if (b.edgeTop() == down || split()) setFileMode(!fileMode, false); }
            @Override public void onClockTap() { hub.openAlarms(); }
            @Override public void onLogoTap() { showStart(startOverlay.getVisibility() != View.VISIBLE); }
            @Override public void onLogoLong() { showStart(true); }
        });
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        int[] kinds = sp ? new int[]{SquareButton.APPS, SquareButton.HOME, SquareButton.FILES} : new int[]{SquareButton.APPS, SquareButton.TOOLS, SquareButton.FILES};
        String[] labels = sp ? new String[]{"Apps", "Pantalla completa", "Archivos"} : new String[]{"Apps", "Herramientas", "Archivos"};
        Runnable[] acts = sp ? new Runnable[]{this::showApps, this::soloFull, () -> setFileMode(!fileMode, false)}
                : new Runnable[]{this::showApps, this::toolsMenu, () -> setFileMode(!fileMode, false)};
        for (int i = 0; i < kinds.length; i++) {
            final Runnable r = acts[i];
            SquareButton sb = new SquareButton(this, kinds[i], labels[i], false, night);
            sb.setCompact(true);
            sb.setOnClickListener(v -> r.run());
            squares.add(sb);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(32), dp(32));
            lp.setMargins(dp(12), 0, dp(12), 0);
            row.addView(sb, lp);
        }
        nbar.setNavRow(row);
        recentsBar = new RecentsBar(this, ChronoBar.ink(night));
        recentsBar.setPick(this::onRecent);
        nbar.setRecents(recentsBar);
        root.addView(nbar, root.indexOfChild(startOverlay), new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        // gestor de archivos en dos mitades (arriba y abajo), como si fueran las dos pantallas
        pane2 = new FilePane(this, hub(), night);
        pane2.setVisibility(View.GONE);
        root.addView(pane2, root.indexOfChild(pane) + 1, new FrameLayout.LayoutParams(-1, 0, Gravity.BOTTOM));
        pane.setPeer(pane2);
        pane2.setPeer(pane);
        root.post(() -> layoutChrono(false));
    }

    private FilePane pane2;
    private android.animation.ValueAnimator soloAnim;

    /** Una pantalla partida por Android (la app arriba, ChronOS abajo). */
    private boolean split() { return solo && isInMultiWindowMode(); }

    /**
     * Una pantalla: el gestor se ve como en los dos teléfonos. Las barras se juntan en el medio (la del reloj baja,
     * la del logo sube) y van destapando el gestor detrás, como abrir una ventana; al devolverlas, se cierra.
     * Con la pantalla partida (ChronOS abajo) solo está la mitad de abajo: la barra del logo sube y destapa el gestor.
     */
    private void layoutSolo(boolean animate, int H) {
        final int W = rootView.getWidth();
        final boolean sp = split();
        final int bh = cbar.barHeight(), nbh = nbar.barHeight();
        FrameLayout.LayoutParams a1 = (FrameLayout.LayoutParams) pane.getLayoutParams(), a2 = (FrameLayout.LayoutParams) pane2.getLayoutParams();
        int h1 = sp ? 0 : H / 2, h2 = H - h1;
        if (a1.height != h1 || a2.height != h2 || a1.gravity != Gravity.TOP || a2.gravity != Gravity.BOTTOM) {
            a1.height = h1; a1.gravity = Gravity.TOP;
            a2.height = h2; a2.gravity = Gravity.BOTTOM;
            pane.setLayoutParams(a1); pane2.setLayoutParams(a2);
        }
        pane.setPadding(0, 0, 0, cbar.visualHeight());
        pane2.setPadding(0, nbar.visualHeight(), 0, 0);
        pane.setTranslationY(0); pane2.setTranslationY(0);
        cbar.setVisibility(sp ? View.GONE : View.VISIBLE);
        cbar.setEdgeTop(!fileMode);
        nbar.setEdgeTop(fileMode);
        final float cTo = fileMode ? h1 - bh : 0, nTo = fileMode ? h1 : H - nbh;
        final float cFrom = cbar.getTranslationY(), nFrom = nbar.getTranslationY();
        if (fileMode) { if (!sp) pane.setVisibility(View.VISIBLE); pane2.setVisibility(View.VISIBLE); }
        if (soloAnim != null) soloAnim.cancel();
        soloAnim = android.animation.ValueAnimator.ofFloat(0f, 1f);
        soloAnim.setDuration(animate ? 420 : 0);
        soloAnim.setInterpolator(new android.view.animation.DecelerateInterpolator(1.4f));
        soloAnim.addUpdateListener(va -> {
            float f = (float) va.getAnimatedValue();
            float cy = cFrom + (cTo - cFrom) * f, ny = nFrom + (nTo - nFrom) * f;
            cbar.setTranslationY(cy);
            nbar.setTranslationY(ny);
            // arriba se ve el gestor por encima de la barra que baja; abajo, por debajo de la barra que sube
            pane.setClipBounds(new android.graphics.Rect(0, 0, Math.max(1, W), Math.max(0, Math.round(cy + bh))));
            pane2.setClipBounds(new android.graphics.Rect(0, Math.max(0, Math.round(ny - h1)), Math.max(1, W), h2));
        });
        soloAnim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) {
                if (!fileMode) { pane.setVisibility(View.GONE); pane2.setVisibility(View.GONE); }
                if (sp) pane.setVisibility(View.GONE);
            }
        });
        soloAnim.start();
        placeStart();
    }

    // ---------------- una pantalla: la app arriba y ChronOS abajo, como en los dos teléfonos ----------------

    /**
     * Abre la app arriba y deja ChronOS abajo (pantalla partida de Android). Necesita la accesibilidad (versión
     * completa) para partir la pantalla; sin ella, la app se abre a pantalla completa.
     */
    private void soloLaunch(String pkg, String label) {
        Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) { Toast.makeText(this, label + " no está instalada", Toast.LENGTH_SHORT).show(); return; }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (!prefs.getBoolean("soloSplit", true) || MesaAccess.instance == null) {
            try { startActivity(i); } catch (Exception ignored) { }
            if (MesaAccess.instance == null && prefs.getBoolean("soloSplit", true) && !prefs.getBoolean("soloSplitHint", false)) {
                prefs.edit().putBoolean("soloSplitHint", true).apply();
                Toast.makeText(this, "Para ver la app arriba y ChronOS abajo, activa la accesibilidad de ChronOS (versión completa)", Toast.LENGTH_LONG).show();
            }
            return;
        }
        if (isInMultiWindowMode()) {   // ChronOS ya está abajo: la app va arriba
            i.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT);
            try { startActivity(i); } catch (Exception ignored) { }
            return;
        }
        try { startActivity(i); } catch (Exception e) { return; }
        final android.content.Context app = getApplicationContext();
        main.postDelayed(() -> {
            if (MesaAccess.instance == null) return;
            boolean asked = MesaAccess.instance.performGlobalAction(7 /* GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN: la app queda arriba */);
            main.postDelayed(() -> {
                // solo si Android de verdad partió la pantalla se trae ChronOS abajo; si no, la app se queda completa
                if (!asked || !MesaAccess.isSplit(pkg)) {
                    if (!prefs.getBoolean("soloSplitFail", false)) {
                        prefs.edit().putBoolean("soloSplitFail", true).apply();
                        Toast.makeText(app, "Este teléfono no dejó partir la pantalla: la app queda completa. Puedes cambiarlo en Ajustes → Apps", Toast.LENGTH_LONG).show();
                    }
                    return;
                }
                try { app.startActivity(new Intent(app, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)); }
                catch (Exception ignored) { }
            }, 1000);
        }, 900);
    }

    /** Con la pantalla partida: la app de arriba pasa a pantalla completa. */
    private void soloFull() {
        if (MesaAccess.instance != null && isInMultiWindowMode()) MesaAccess.instance.performGlobalAction(7);
    }

    @Override public void onMultiWindowModeChanged(boolean on) {
        super.onMultiWindowModeChanged(on);
        if (solo) recreate();   // ChronOS abajo (solo la mesa de abajo) o a pantalla completa (las dos mitades)
    }

    /** En una pantalla, el otro teléfono volvió: ¿volver a dos pantallas? */
    private void offerTwoScreens() {
        new Ui.Sheet(this)
                .title("Volvió el otro teléfono")
                .message("¿Vuelves a usar ChronOS en dos pantallas?")
                .button("Seguir en una", false, null)
                .button("Dos pantallas", true, () -> setSolo(false))
                .show();
    }

    // ---------------- principal sin extendido: botones flotantes ----------------
    // Sin el extendido el principal no tiene menú de apps ni controles: aparece una tarjeta flotante con lo esencial.

    private LinearLayout offlinePanel, offlineFull;
    private TextView offlineInfo, offlinePill, offlineUpd, offlineSolo, offlineTitle;
    private boolean offlineCollapsed;
    private final long startedAt = android.os.SystemClock.uptimeMillis();

    private LinearLayout buildOffline() {
        boolean n = CardTheme.sheetNight(night);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        offlineFull = new LinearLayout(this);
        offlineFull.setOrientation(LinearLayout.VERTICAL);
        offlineFull.setPadding(dp(20), dp(16), dp(20), dp(16));
        GradientDrawable g = new GradientDrawable();
        g.setColor(CardTheme.sheetFill(night));
        g.setCornerRadius(dp(24));
        g.setStroke(dp(1), (Ui.fg(n) & 0x00FFFFFF) | 0x33000000);
        offlineFull.setBackground(g);
        offlineFull.setElevation(dp(18));
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = new TextView(this);
        offlineTitle = t;
        t.setText("Sin teléfono extendido");
        t.setTextSize(18);
        t.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        t.setTextColor(Ui.fg(n));
        head.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        TextView min = new TextView(this);
        min.setText("—");
        min.setTextSize(18);
        min.setTextColor(Ui.fg(n));
        min.setPadding(dp(14), 0, dp(4), 0);
        min.setOnClickListener(v -> { offlineCollapsed = true; updateOffline(); });
        head.addView(min);
        offlineFull.addView(head);
        offlineInfo = new TextView(this);
        offlineInfo.setTextSize(13);
        offlineInfo.setTextColor(Ui.muted(n));
        offlineInfo.setPadding(0, dp(4), 0, dp(12));
        offlineFull.addView(offlineInfo);
        LinearLayout r1 = new LinearLayout(this), r2 = new LinearLayout(this);
        r1.addView(offBtn("Conectar", "Buscar al otro teléfono", true, () -> {
            if (side == 1) { hub.forceLink(); linkInfo = "Buscando al extendido en todo el WiFi…"; }
            else { hub.link.call(null); linkInfo = "Llamando al principal…"; }
            updateStatus();
            updateOffline();
        }), new LinearLayout.LayoutParams(dp(150), -2));
        r1.addView(offBtn("Apps", "Abrir una app aquí", false, this::showApps), offLp());
        offlineUpd = (TextView) offBtn("Actualizar", "Buscar versión nueva", false, this::offlineUpdate);
        r2.addView(offlineUpd, new LinearLayout.LayoutParams(dp(150), -2));
        r2.addView(offBtn("Teléfono normal", "Su inicio y su teclado", false, this::goNormal), offLp());
        offlineFull.addView(r1);
        offlineSolo = (TextView) offBtn("Una pantalla", "ChronOS completo en este teléfono", false, () -> setSolo(true));
        LinearLayout.LayoutParams r2l = new LinearLayout.LayoutParams(-2, -2);
        r2l.topMargin = dp(10);
        offlineFull.addView(r2, r2l);
        LinearLayout.LayoutParams r3l = new LinearLayout.LayoutParams(dp(310), -2);
        r3l.topMargin = dp(10);
        offlineFull.addView(offlineSolo, r3l);
        box.addView(offlineFull);
        // versión chica: una pastilla que se toca para volver a abrir la tarjeta
        offlinePill = new TextView(this);
        offlinePill.setText("⚠  Sin extendido  ▾");
        offlinePill.setTextSize(14);
        offlinePill.setTextColor(0xFFFFFFFF);
        offlinePill.setPadding(dp(16), dp(8), dp(16), dp(8));
        GradientDrawable pg = new GradientDrawable();
        pg.setColor(0xEEE07A1F);
        pg.setCornerRadius(dp(20));
        offlinePill.setBackground(pg);
        offlinePill.setElevation(dp(12));
        offlinePill.setOnClickListener(v -> { offlineCollapsed = false; updateOffline(); });
        box.addView(offlinePill);
        box.setVisibility(View.GONE);
        return box;
    }

    private LinearLayout.LayoutParams offLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(150), -2);
        lp.setMarginStart(dp(10));
        return lp;
    }

    private View offBtn(String title, String sub, boolean primary, Runnable r) {
        boolean n = CardTheme.sheetNight(night);
        TextView b = new TextView(this);
        b.setText(title + "\n" + sub);
        android.text.SpannableString ss = new android.text.SpannableString(title + "\n" + sub);
        ss.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, title.length(), 0);
        ss.setSpan(new android.text.style.RelativeSizeSpan(0.78f), title.length() + 1, ss.length(), 0);
        b.setText(ss);
        b.setTextSize(15);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(10), dp(12), dp(10), dp(12));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(16));
        if (primary) { g.setColor(0xFF00917A); b.setTextColor(0xFFFFFFFF); }
        else { g.setColor((Ui.fg(n) & 0x00FFFFFF) | 0x14000000); b.setTextColor(Ui.fg(n)); }
        b.setBackground(g);
        b.setOnClickListener(v -> { v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY); r.run(); });
        return b;
    }

    /** Se muestra en el principal mientras no esté el extendido (como el widget de baterías, que también lo sabe). */
    private void updateOffline() {
        if (offlinePanel == null) return;
        boolean show = side >= 0 && !solo && !hub.connected() && !PhoneMode.active(this)
                && android.os.SystemClock.uptimeMillis() - startedAt > 5000;   // al abrir, unos segundos para que se conecte solo
        if (!show) { offlinePanel.setVisibility(View.GONE); offlineCollapsed = false; return; }
        offlinePanel.setVisibility(View.VISIBLE);
        offlineFull.setVisibility(offlineCollapsed ? View.GONE : View.VISIBLE);
        offlinePill.setVisibility(offlineCollapsed ? View.VISIBLE : View.GONE);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) offlinePanel.getLayoutParams();
        lp.gravity = offlineCollapsed ? Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL : Gravity.CENTER;
        lp.bottomMargin = offlineCollapsed ? dp(24) : 0;
        offlinePanel.setLayoutParams(lp);
        offlineTitle.setText(side == 1 ? "Sin teléfono extendido" : "Sin teléfono principal");
        offlineInfo.setText(linkInfo != null ? linkInfo : side == 1 ? "El extendido no responde. Puedes buscarlo, abrir apps aquí, usar ChronOS en una pantalla o el teléfono normal."
                : "El principal no responde. Puedes llamarlo, usar ChronOS en una pantalla en este teléfono o el teléfono normal.");
        if (Updater.updateAvailable(this)) offlineUpd.setText("Actualizar\nHay versión " + Updater.latestKnown(this));
        offlinePanel.bringToFront();
    }

    private final Runnable offlineTick = new Runnable() {
        @Override public void run() { updateOffline(); if (side >= 0 && !hub.connected()) main.postDelayed(this, 3000); }
    };

    private void offlineUpdate() {
        Toast.makeText(this, "Buscando versión nueva…", Toast.LENGTH_SHORT).show();
        Updater.checkQuiet(this, true, () -> {
            if (isFinishing()) return;
            updateOffline();
            if (!Updater.updateAvailable(this)) { Toast.makeText(this, "Ya tienes la última versión (" + Updater.clean(Updater.installedVersion(this)) + ")", Toast.LENGTH_LONG).show(); return; }
            new Ui.Sheet(this)
                    .title("Versión nueva: " + Updater.latestKnown(this))
                    .message("Se actualiza este teléfono (el principal). El extendido se actualiza cuando vuelva a conectarse.")
                    .button("Ahora no", false, null)
                    .button("Actualizar", true, () -> { Toast.makeText(this, "Descargando…", Toast.LENGTH_LONG).show(); Updater.updateFromRemote(this, hub); })
                    .show();
        });
    }

    /** Al teléfono normal; si hay una versión nueva, antes se ofrece actualizar. */
    private void goNormal() {
        Toast.makeText(this, "Revisando si hay actualización…", Toast.LENGTH_SHORT).show();
        Updater.checkQuiet(this, true, () -> {
            if (isFinishing()) return;
            if (!Updater.updateAvailable(this)) { PhoneMode.enter(this, hub); return; }
            new Ui.Sheet(this)
                    .title("Hay una versión nueva: " + Updater.latestKnown(this))
                    .message("Este es un buen momento para actualizar el principal (el extendido no está). Instalada la versión nueva, "
                            + "ChronOS se vuelve a abrir y puedes elegir el teléfono normal.")
                    .button("Ahora no", false, () -> PhoneMode.enter(this, hub))
                    .button("Actualizar", true, () -> {
                        Toast.makeText(this, "Descargando la actualización…", Toast.LENGTH_LONG).show();
                        Updater.updateFromRemote(this, hub);
                    })
                    .show();
        });
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
            boolean barRec = recentsBar != null && !fileMode;
            if (!desk.hasRecentsWidget() && !barRec) { /* nada que mostrar */ }
            else if (hub.isPrincipal()) {
                RecentApps.worker.execute(() -> {
                    org.json.JSONObject m = RecentApps.message(getApplicationContext());
                    main.post(() -> { desk.setRecents(m); if (recentsBar != null) recentsBar.set(m); });
                });
            } else if (hub.connected()) {
                String have = barRec ? recentsBar.sig() : desk.recentsSig();
                if (barRec && desk.hasRecentsWidget() && !have.equals(desk.recentsSig())) have = "?";
                hub.send("recReq", "sig", have);
            }
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
            @Override public void putOnDesk(String pkg, String label, String iconPng, int screen) {
                Item it = new Item();
                it.kind = Item.APP; it.pkg = pkg; it.title = label; it.w = sized(0.24);
                it.setImage(iconPng);
                double[] p = desk.spotOn(it.w, screen);
                it.x = p[0]; it.y = p[1];
                it.page = desk.page();
                addItem(it);
                sendImage(it);
                // queda en modo edición en la pantalla donde se puso, para ubicarla a gusto
                if (screen == deskSide()) main.postDelayed(() -> desk.startEditing(it.id), 200);
                else if (hub.connected()) main.postDelayed(() -> hub.send("editItem", "id", it.id), 400);
            }
        });
    }

    private void openApp(String pkg, String label) {
        if (Pals.isPal(pkg)) { hub.launchHere(pkg, label); return; }
        if (solo && !pkg.equals(getPackageName())) { soloLaunch(pkg, label); return; }
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

    /** Se instaló una app nueva en el principal: ¿también en el extendido? */
    private void appAdded(org.json.JSONObject m) {
        String label = m.optString("label");
        new Ui.Sheet(this)
                .title("Se instaló " + label + " en el principal")
                .message(m.optBoolean("play") ? "Se abre su página de Play Store en el extendido: solo toca \"Instalar\"."
                        : "Se copia desde el principal y Android te pide confirmar.")
                .option(Ui.STORE, "Instalar en ambos", "También en el extendido", () -> AppShare.install(this, hub, m))
                .button("Solo en el principal", false, null)
                .show();
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

    // ---------------- intro ----------------

    private Intro intro;
    private boolean otherRole2;

    /** Repite la intro completa (también la pregunta del papel). */
    private void playIntroAgain() {
        if (intro != null) return;
        if (!solo) prefs.edit().putBoolean("roleV2", false).apply();
        showIntro(!solo);
    }

    private void showIntro(boolean ask) {
        intro = new Intro(this, new Intro.Host() {
            @Override public boolean chooseRole(int s) { return chooseRole2(s); }
            @Override public int side() { return side; }
            @Override public boolean solo() { return solo; }
            @Override public boolean connected() { return hub.connected(); }
        });
        intro.setOnDone(() -> intro = null);
        rootView.addView(intro, new FrameLayout.LayoutParams(-1, -1));
        intro.start(ask);
    }

    /** Papel elegido en la intro: el otro teléfono toma el contrario. No deja dos principales ni dos extendidos. */
    private boolean chooseRole2(int s) {
        if (hub.connected() && otherRole2 && otherSide == s) return false;
        prefs.edit().putBoolean("roleV2", true).apply();
        if (hub.connected()) hub.send("roleSet", "side", 1 - s);
        if (side != s) main.postDelayed(() -> setSide(s), 300);
        return true;
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
            ip.setHint("IP del principal (opcional)");
            ip.setText(prefs.getString("principalIp", ""));
            boolean on = hub.connected();
            sh.message(statusText + "\n\nEste es el extendido (IP " + Link.localIp() + ")."
                    + (on ? " Está conectado con el principal (" + hub.link.peerIp() + ")."
                          : " Si el principal no aparece solo, llámalo: el principal recibe el aviso y se conecta. "
                            + "Si tampoco funciona, escribe la IP del principal (está en el principal: Ajustes → Acerca del teléfono → Estado → Dirección IP)."))
              .option(Ui.WIFI, "Llamar al principal", "Le pide al principal que se conecte a este teléfono", () -> {
                  hub.link.call(null);
                  linkInfo = "Llamando al principal…";
                  updateStatus();
                  Toast.makeText(this, "Llamando al principal…", Toast.LENGTH_SHORT).show();
              })
              .option(Ui.AUTO, "Reiniciar la conexión", "Vuelve a anunciarse en el WiFi y espera al principal", () -> {
                  hub.link.start(true);
                  main.postDelayed(() -> hub.link.call(null), 1200);
                  Toast.makeText(this, "Conexión reiniciada", Toast.LENGTH_SHORT).show();
              })
              .view(ip)
              .button("Cerrar", false, null)
              .button("Llamar a esa IP", true, () -> {
                  String v = ip.getText().toString().trim();
                  if (v.isEmpty()) { hub.link.call(null); return; }
                  prefs.edit().putString("principalIp", v).apply();
                  linkInfo = "Llamando a " + v + "…";
                  updateStatus();
                  hub.link.call(v);
              });
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

    /** ⋯ de un elemento: en las apps, cuadrado estándar o grande; en todos, quitarlo. */
    @Override public void onItemMenu(Item it) {
        String name = Item.APP.equals(it.kind) ? it.title : "Elemento";
        Ui.Sheet sh = new Ui.Sheet(this).title(name);
        if (Item.APP.equals(it.kind)) {
            sh.option(Ui.GRID, -1, "Cuadrado estándar", "El tamaño de siempre", it.sz == 0, () -> setAppSize(it, 0));
            sh.option(Ui.GRID, -1, "Cuadrado grande", "Más grande, para las apps que más usas", it.sz == 1, () -> setAppSize(it, 1));
        }
        sh.option(Ui.TRASH, "Quitar del escritorio", null, () -> (desk.find(it.id) != null ? desk : desk2 != null ? desk2 : desk).removeItem(it));
        sh.button("Cerrar", false, null).show();
    }

    private void setAppSize(Item it, int sz) {
        if (it.sz == sz) return;
        it.sz = sz;
        it.touch();
        desk.invalidate();
        if (desk2 != null) desk2.invalidate();
        onItemChanged(it);
    }

    @Override public void onItemChanged(Item it) {
        if (solo) { scheduleSave(); return; }   // una pantalla: su escritorio es solo de este teléfono
        JSONObject m = new JSONObject();
        try { m.put("t", "up"); m.put("item", it.toJson(false)); } catch (JSONException ignored) { }
        hub.send(m);
        scheduleSave();
    }

    @Override public void onItemDeleted(Item it) {
        if (Item.WIDGET.equals(it.kind)) releaseWidget(it);
        if (solo) { scheduleSave(); return; }
        JSONObject m = new JSONObject();
        try { m.put("t", "del"); m.put("id", it.id); } catch (JSONException ignored) { }
        hub.send(m);
        scheduleSave();
    }

    @Override public void onDrag(Item it, double x, double y) {
        if (solo) return;
        long now = System.currentTimeMillis();
        if (now - lastDragSend < 16) return;
        lastDragSend = now;
        JSONObject m = new JSONObject();
        try { m.put("t", "drag"); m.put("id", it.id); m.put("x", x); m.put("y", y); } catch (JSONException ignored) { }
        hub.send(m);
    }

    @Override public void onDragEnd(Item it) {
        if (solo) return;
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
        if (req == REQ_HOME) {
            main.postDelayed(() -> {
                boolean ok = homeDefault(this);
                if (!ok) homeSettings();   // la ventana no lo dejó: a los ajustes de inicio
                else Toast.makeText(this, "Listo: ChronOS es la pantalla de inicio", Toast.LENGTH_SHORT).show();
                reportHome();
            }, 500);
        }
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
        main.removeCallbacks(offlineTick);
        main.post(offlineTick);
        if (state == Link.WAITING && info == null) waitingSince = System.currentTimeMillis();
        if (state == Link.CONNECTED && side == 0) main.postDelayed(() -> AppsDialog.prefetch(this, hub), 1500);   // menú de apps al instante
        if (state == Link.CONNECTED) main.postDelayed(() -> { reportHome(); hub.send("homeAsk"); }, 2000);
        if (state == Link.CONNECTED && solo) main.post(this::offerTwoScreens);
        if (state == Link.CONNECTED && phoneSheet != null && phoneSheet.isShowing()) {   // apareció el extendido: ya no hace falta la pregunta
            phoneSheet.dismiss();
            Toast.makeText(this, "Extendido conectado", Toast.LENGTH_SHORT).show();
        }   // ¿ChronOS es el inicio en cada teléfono?
        if (state == Link.CONNECTED) {
            JSONObject hello = new JSONObject();
            JSONObject full = new JSONObject();
            try {
                hello.put("t", "hello"); hello.put("side", side); hello.put("ver", Updater.installedVersion(this));
                hello.put("role2", prefs.getBoolean("roleV2", false));
                JSONArray arr = new JSONArray();
                for (Item it : desk.items) arr.put(it.toJson(false));
                full.put("t", "full"); full.put("items", arr);
            } catch (JSONException ignored) { }
            if (side == 1) sendView();   // el principal define la forma; va antes que los elementos
            wantSent = ""; main.postDelayed(sendWant, 800);   // widgets: volver a pedir las imágenes
            hub.send(hello);
            if (!solo) hub.send(full);
        }
        if (state != Link.CONNECTED) { otherSide = -1; desk.clearAllRemoteDrags(); requested.clear(); }
        updateStatus();
    }

    @Override public void onMessage(JSONObject m) {
        String t = m.optString("t");
        // una pantalla: el escritorio de dos pantallas (del otro teléfono) no se mezcla con este
        if (solo && ("full".equals(t) || "up".equals(t) || "del".equals(t) || "drag".equals(t) || "dragEnd".equals(t)
                || "pages".equals(t) || "editItem".equals(t) || "needImg".equals(t) || "img".equals(t))) return;
        switch (t) {
            case "editItem":
                main.post(() -> desk.startEditing(m.optString("id")));
                break;
            case "appsList":
                if (side == 0) AppsDialog.received(this, m);
                break;
            case "fileMode":
                main.post(() -> setFileMode(m.optBoolean("on"), true));
                break;
            case "notif":   // ahora las notificaciones corren en la banda (ticker); la tarjeta ya no se usa
                break;
            case "ticker":
                main.post(() -> { tickerItems = m.optJSONArray("items"); tickerHere = m.optBoolean("here"); applyTicker(); fillNotifList(); });
                break;
            case "notifTestR":
                main.post(() -> notifResult(m));
                break;
            case "notifSetup":
                if (side == 1) main.post(this::guideNotifs);
                break;
            case "homeState":
                otherHome = m.optBoolean("def");
                otherHomeKnown = true;
                break;
            case "homeAsk":
                main.post(this::reportHome);
                break;
            case "appAdded":
                if (side != 1 && !AppShare.installedHere(this, m.optString("pkg"))) main.post(() -> appAdded(m));
                break;
            case "phoneOffer":
                main.post(() -> phoneOffer(m.optString("title")));
                break;
            case "powerR":
                if (!m.optBoolean("ok")) main.post(() -> {
                    if ("lock".equals(m.optString("a"))) { Toast.makeText(this, "El otro teléfono no pudo bloquearse (falta el permiso de bloquear pantalla allá)", Toast.LENGTH_LONG).show(); return; }
                    String why = m.optString("why");
                    Ui.Sheet sh = new Ui.Sheet(this).title("No se abrió el menú de apagado").message(Power.explain(why.isEmpty() ? "dead" : why, false));
                    if (!"lite".equals(why)) sh.option(Ui.OPEN, "Abrir Accesibilidad allá", "Se abre en el otro teléfono",
                            () -> hub.send("openSettings", "a", android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS));
                    sh.button("Entendido", true, null).show();
                });
                else if ("menu".equals(m.optString("a"))) main.post(() -> Toast.makeText(this, "En el otro teléfono se abrió el menú de apagado", Toast.LENGTH_SHORT).show());
                break;
            case "callLog":
                main.post(() -> onCallLog(m));
                break;
            case "contacts":
                main.post(() -> fillContacts(m));
                break;
            case "brNow":
                Bright.other = (float) m.optDouble("f", -1);
                Bright.otherOk = m.optBoolean("ok", true);
                main.post(() -> desk.invalidate());
                break;
            case "brLock":
                Bright.setLocked(this, m.optBoolean("on"));
                main.post(() -> desk.invalidate());
                break;
            case "torchUi":
                main.post(() -> desk.invalidate());
                break;
            case "updMsg":
            case "boostMsg":
                main.post(() -> Toast.makeText(this, m.optString("msg"), Toast.LENGTH_LONG).show());
                break;
            case "boost":
                if (barPlayer != null) main.post(() -> barPlayer.setBoostOn(m.optBoolean("on")));
                break;
            case "mActive":   // video en otra app del principal: los controles salen en la barra
                if (side == 0 && barPlayer != null && m.optBoolean("video") && m.optBoolean("fresh"))
                    main.post(() -> { boolean was = cbar.playerShown(); barPlayer.setExternal(true); cbar.showPlayer(true); if (!was) main.postDelayed(() -> layoutChrono(false), 560); });
                else if (side == 0 && barPlayer != null && !m.optBoolean("on") && !m.optBoolean("app"))   // se cerró la app o terminó: se recoge
                    main.postDelayed(() -> { if (barPlayer != null && barPlayer.isExternal() && cbar.playerShown()) { cbar.showPlayer(false); main.postDelayed(() -> layoutChrono(false), 400); } }, 8000);
                break;
            case "mState":
                if ((MediaHub.AUDIO.equals(m.optString("kind")) || MediaHub.VIDEO.equals(m.optString("kind"))) && barPlayer != null)
                    main.post(() -> { boolean was = cbar.playerShown(); cbar.showPlayer(true); barPlayer.update(m); if (!was) main.postDelayed(() -> { layoutChrono(false); }, 560); });
                break;
            case "mEnd":
                if ((MediaHub.AUDIO.equals(m.optString("kind")) || MediaHub.VIDEO.equals(m.optString("kind"))) && cbar != null)
                    main.post(() -> { cbar.showPlayer(false); main.postDelayed(() -> layoutChrono(false), 400); });
                break;
            case "notifs":
                main.post(() -> { desk.setNotified(m.optJSONArray("p")); if (recentsBar != null) recentsBar.setNotified(m.optJSONArray("p")); });
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
                main.post(() -> { desk.setRecents(m); if (recentsBar != null) recentsBar.set(m); });
                break;
            case "introPlay":
                if (!solo) main.post(this::playIntroAgain);
                break;
            case "roleSet":   // el otro teléfono eligió su papel en la intro: este toma el contrario
                if (!solo) main.post(() -> {
                    int ns = m.optInt("side", side);
                    prefs.edit().putBoolean("roleV2", true).apply();
                    if (ns != side && ns >= 0) setSide(ns);
                    if (intro != null) intro.remoteAnswered();
                });
                break;
            case "hello":
                otherRole2 = m.optBoolean("role2", false);
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
                if (m.optInt("c") != CardTheme.color || m.optInt("g") != CardTheme.glass || m.optInt("k") != CardTheme.console)
                    setCardTheme(m.optInt("c"), m.optInt("g"), m.optInt("k"), true);
                break;
            case "bg":
                if (m.optInt("c") != prefs.getInt("deskColor", 0)) setDeskColor(m.optInt("c"), true);
                break;
            case "flip":
                main.post(() -> setFlip(m.optBoolean("p"), m.optBoolean("e"), true));
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
        if (it.img.isEmpty() || solo) return;
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
        final String data = arr.toString(), file = deskFile();
        io.execute(() -> {
            File f = new File(getFilesDir(), file);
            File tmp = new File(getFilesDir(), file + ".tmp");
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
        File f = new File(getFilesDir(), deskFile());
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
