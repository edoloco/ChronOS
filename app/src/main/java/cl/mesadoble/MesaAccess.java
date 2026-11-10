package cl.mesadoble;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import org.json.JSONObject;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;
import android.view.accessibility.AccessibilityEvent;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Servicio de accesibilidad del teléfono PRINCIPAL (versión completa):
 * 1) cambia el método de entrada a ChronOS (y lo devuelve al anterior al desconectarse);
 * 2) avisa al extendido cuando se abre una app de chat, para mostrar el teclado allá;
 * 3) ejecuta el touchpad (puntero, clic, desplazar, atrás/inicio) y el gamepad (toques en pantalla).
 * 4) mesa de dibujo para Sketchbook: dibuja los trazos que llegan del extendido y captura la zona para mostrarla allá.
 * 5) chat en vivo de YouTube: con el video en pantalla flotante, burbuja y ventanita de chat ({@link LiveChat}).
 * 6) al escribir sin un cuadro de texto elegido, busca el cuadro de texto (o el "Di algo…") de la app y lo toca.
 * Fuera de la captura de la mesa de dibujo (solo con Sketchbook abierto), no lee el contenido de las pantallas.
 */
public class MesaAccess extends AccessibilityService {

    static MesaAccess instance;

    static final Set<String> CHAT_APPS = new HashSet<>(Arrays.asList(
            "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "org.telegram.messenger.web",
            "org.thunderdog.challegram", "com.facebook.orca", "com.facebook.mlite",
            "com.google.android.apps.messaging", "com.android.mms", "com.samsung.android.messaging",
            "org.thoughtcrime.securesms", "com.instagram.android", "com.discord", "com.Slack",
            "com.microsoft.teams", "jp.naver.line.android", "com.viber.voip", "com.snapchat.android",
            "com.google.android.apps.dynamite", "com.skype.raider"));

    private String lastPkg = "";
    /** App que está al frente en este teléfono (para Game-Asist: ¿RetroArch abierto?). */
    static volatile String front = "";

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Hub h = Hub.get(this);
        h.ensureStarted();
        ForceView.apply(this);   // con la accesibilidad, la vista forzada usa su propia capa
        if (h.isPrincipal() && h.connected()) switchToMesa();
    }

    @Override public boolean onUnbind(android.content.Intent intent) {
        if (instance == this) instance = null;
        LiveChat.release();
        return super.onUnbind(intent);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        // Bat Op: solo lo que hace el usuario en otras apps cuenta como "en uso" (toques; no los cambios de pantalla
        // que hacen solas, ni las ventanas de ChronOS: sus toques se ven en dispatchTouchEvent)
        int et = e.getEventType();
        CharSequence ep = e.getPackageName();
        if ((et == AccessibilityEvent.TYPE_VIEW_CLICKED || et == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED)
                && ep != null && !getPackageName().contentEquals(ep)) BatOp.touch();
        // chat en vivo de YouTube: ¿quedó el video en pantalla flotante? (solo mira la lista de ventanas)
        if (e.getEventType() == AccessibilityEvent.TYPE_WINDOWS_CHANGED
                || (e.getPackageName() != null && LiveChat.YT.equals(e.getPackageName().toString()))) LiveChat.get(this).windowsChanged();
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || e.getPackageName() == null) return;
        String pkg = e.getPackageName().toString();
        // ventanas propias que no son la pantalla de ChronOS (marco de la mesa de dibujo, teclado…): no cambian "la app del frente"
        CharSequence cls = e.getClassName();
        if (pkg.equals(getPackageName()) && (cls == null || !cls.toString().endsWith("Activity"))) return;
        if (pkg.equals(lastPkg)) return;
        lastPkg = pkg;
        front = pkg;
        Hub h = Hub.get(this);
        // YouTube o un navegador al frente (en cualquiera de los dos): la flecha chica para pasarlo al otro
        if (h.connected()) MoveTo.onFront(this, h, pkg);
        if (!h.isPrincipal() || !h.connected()) return;
        // mesa de dibujo: Sketchbook al frente → el extendido se vuelve tableta (teclados y avisos del sistema no cuentan)
        if (pkg.equals(getPackageName()) || getPackageManager().getLaunchIntentForPackage(pkg) != null) Sketch.front(h, pkg);
        // app de dos pantallas (DUAL Motion u otra que lo declare): en el extendido se abre su otra mitad
        if (Hub.isDualApp(this, pkg)) h.send("dualOpen", "pkg", pkg);
        // RetroArch al frente: en el extendido se abre Game-Asist
        if (pkg.startsWith("com.retroarch")) h.send("openAsist");
        if (CHAT_APPS.contains(pkg)) {
            switchToMesa();
            h.send("openKb", "pkg", pkg);
        }
    }

    @Override public void onInterrupt() { }

    private static final java.util.regex.Pattern CHAT_HINT = java.util.regex.Pattern.compile(
            "(?i)(di algo|chatea|chat|say something|escribe|comenta|comentario|add a comment|mensaje|message|reply|responder)");

    /**
     * Teclado: se escribió sin un cuadro de texto elegido. Busca en la pantalla del principal el cuadro de texto (o el
     * botón "Di algo…/Chatea…" que lo abre) y lo toca. Solo mira la ventana activa, y solo en ese momento.
     */
    static boolean focusTextField() {
        MesaAccess s = instance;
        if (s == null) return false;
        android.view.accessibility.AccessibilityNodeInfo root;
        try { root = s.getRootInActiveWindow(); } catch (Exception e) { return false; }
        if (root == null) return false;
        java.util.ArrayDeque<android.view.accessibility.AccessibilityNodeInfo> q = new java.util.ArrayDeque<>();
        q.add(root);
        android.view.accessibility.AccessibilityNodeInfo hint = null;
        int seen = 0;
        while (!q.isEmpty() && seen++ < 600) {
            android.view.accessibility.AccessibilityNodeInfo n = q.poll();
            if (n == null) continue;
            if (n.isVisibleToUser()) {
                if (n.isEditable()) {
                    n.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_FOCUS);
                    n.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);
                    return true;
                }
                if (hint == null) {
                    CharSequence t = n.getText() != null ? n.getText() : n.getContentDescription();
                    if (t == null && android.os.Build.VERSION.SDK_INT >= 26) t = n.getHintText();
                    if (t != null && t.length() < 40 && CHAT_HINT.matcher(t).find()) hint = n;
                }
            }
            for (int i = 0; i < n.getChildCount(); i++) q.add(n.getChild(i));
        }
        // no hay cuadro a la vista: el botón que lo abre (o su contenedor que se puede tocar)
        for (android.view.accessibility.AccessibilityNodeInfo n = hint; n != null; n = n.getParent())
            if (n.isClickable()) { n.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK); return true; }
        return false;
    }

    /**
     * Escribir sin teclado: si la app no abrió el teclado en su cuadro (YouTube después de mandar un comentario), se
     * aplica lo escrito (letras, sugerencias, borrar, Enviar) directo al cuadro con accesibilidad. false = no hay cuadro.
     */
    static boolean typeIntoField(java.util.List<org.json.JSONObject> ops) {
        MesaAccess s = instance;
        if (s == null || ops.isEmpty()) return false;
        android.view.accessibility.AccessibilityNodeInfo f = null;
        try {
            android.view.accessibility.AccessibilityNodeInfo root = s.getRootInActiveWindow();
            if (root != null) f = root.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT);
            if (f == null || !f.isEditable()) f = firstEditable(root);
        } catch (Exception e) { return false; }
        if (f == null) return false;
        CharSequence cur = f.getText();
        boolean hint = android.os.Build.VERSION.SDK_INT >= 26 && f.isShowingHintText();
        StringBuilder b = new StringBuilder(cur == null || hint ? "" : cur);
        boolean enter = false;
        for (org.json.JSONObject m : ops) {
            String a = m.optString("a");
            if ("text".equals(a)) b.append(m.optString("s"));
            else if ("word".equals(a)) {
                String old = m.optString("old");
                if (!old.isEmpty() && b.toString().toLowerCase(java.util.Locale.ROOT).endsWith(old.toLowerCase(java.util.Locale.ROOT))) b.setLength(b.length() - old.length());
                b.append(m.optString("s"));
            } else if ("key".equals(a)) {
                int k = m.optInt("k"), n = Math.max(1, m.optInt("n", 1));
                if (k == android.view.KeyEvent.KEYCODE_DEL) b.setLength(Math.max(0, b.length() - n));
                else if (k == android.view.KeyEvent.KEYCODE_ENTER) enter = true;
                else if (k == android.view.KeyEvent.KEYCODE_SPACE) b.append(' ');
            }
        }
        android.os.Bundle args = new android.os.Bundle();
        args.putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, b.toString());
        boolean ok = f.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        if (enter && android.os.Build.VERSION.SDK_INT >= 30) f.performAction(0x01020036 /* ACTION_IME_ENTER: Enviar */);
        return ok;
    }

    private static android.view.accessibility.AccessibilityNodeInfo firstEditable(android.view.accessibility.AccessibilityNodeInfo root) {
        if (root == null) return null;
        java.util.ArrayDeque<android.view.accessibility.AccessibilityNodeInfo> q = new java.util.ArrayDeque<>();
        q.add(root);
        int seen = 0;
        while (!q.isEmpty() && seen++ < 600) {
            android.view.accessibility.AccessibilityNodeInfo n = q.poll();
            if (n == null) continue;
            if (n.isVisibleToUser() && n.isEditable()) return n;
            for (int i = 0; i < n.getChildCount(); i++) q.add(n.getChild(i));
        }
        return null;
    }

    /** ¿La app pkg quedó en pantalla partida (su ventana ocupa menos de lo que mide la pantalla)? */
    static boolean isSplit(String pkg) {
        MesaAccess s = instance;
        if (s == null) return false;
        try {
            Point sc = s.screen();
            for (android.view.accessibility.AccessibilityWindowInfo w : s.getWindows()) {
                if (w.getType() != android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                android.view.accessibility.AccessibilityNodeInfo r = w.getRoot();
                if (r == null || r.getPackageName() == null || !pkg.equals(r.getPackageName().toString())) continue;
                android.graphics.Rect b = new android.graphics.Rect();
                w.getBoundsInScreen(b);
                return b.height() < sc.y * 0.8f && b.width() >= sc.x * 0.8f;
            }
        } catch (Exception ignored) { }
        return false;
    }

    // ---------------- touchpad y gamepad ----------------

    private final Handler h = new Handler(Looper.getMainLooper());
    private View cursor;
    private WindowManager.LayoutParams cursorLp;
    private float px = -1, py = -1;
    private final Runnable hideCursor = () -> { if (cursor != null) cursor.setVisibility(View.GONE); };

    private Point screen() {
        Point p = new Point();
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (wm != null) wm.getDefaultDisplay().getRealSize(p);
        return p;
    }

    /** Mensajes del extendido: mouse, click, scroll, nav, pad. Devuelve false si no los maneja. */
    public boolean handle(JSONObject m) {
        String t = m.optString("t");
        switch (t) {
            case "mouse": movePointer(m.optInt("dx"), m.optInt("dy")); return true;
            case "click": showPointer(); tap(px, py, 40); return true;
            case "scroll": {
                showPointer();
                float d = m.optInt("d") * 90 * getResources().getDisplayMetrics().density / 2.5f;
                swipe(px, py, px, py + d, 160);
                return true;
            }
            case "nav": {
                String w = m.optString("k");
                performGlobalAction("home".equals(w) ? GLOBAL_ACTION_HOME : "recents".equals(w) ? GLOBAL_ACTION_RECENTS : GLOBAL_ACTION_BACK);
                return true;
            }
            case "pad": pad(m); return true;
            case "vswipe": {   // teclado vertical: el gesto del touchpad se repite en el centro de la pantalla
                Point sc = screen();
                float d = (float) m.optDouble("f") * sc.y * 1.3f;
                d = Math.max(-sc.y * 0.8f, Math.min(sc.y * 0.8f, d));
                float cx = sc.x / 2f, cy = sc.y / 2f;
                swipe(cx, cy - d / 2, cx, cy + d / 2, Math.max(60, Math.min(700, m.optLong("ms", 250))));
                return true;
            }
            case "vtap": { Point sc = screen(); tap(sc.x / 2f, sc.y / 2f, 40); return true; }
        }
        return false;
    }

    private void showPointer() {
        Point sc = screen();
        if (px < 0) { px = sc.x / 2f; py = sc.y / 2f; }
        if (cursor == null) {
            final float dp = getResources().getDisplayMetrics().density;
            cursor = new View(this) {
                final Paint f = new Paint(Paint.ANTI_ALIAS_FLAG), s = new Paint(Paint.ANTI_ALIAS_FLAG);
                @Override protected void onDraw(Canvas c) {
                    Path p = new Path();
                    p.moveTo(2 * dp, 2 * dp); p.lineTo(2 * dp, 20 * dp); p.lineTo(7 * dp, 15 * dp);
                    p.lineTo(11 * dp, 23 * dp); p.lineTo(14 * dp, 21.5f * dp); p.lineTo(10 * dp, 14 * dp); p.lineTo(16 * dp, 14 * dp); p.close();
                    f.setColor(0xFFFFFFFF); s.setColor(0xFF1D2733); s.setStyle(Paint.Style.STROKE); s.setStrokeWidth(1.5f * dp);
                    c.drawPath(p, f); c.drawPath(p, s);
                }
            };
            cursorLp = new WindowManager.LayoutParams(Math.round(20 * dp), Math.round(26 * dp),
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            cursorLp.gravity = Gravity.TOP | Gravity.START;
            try { ((WindowManager) getSystemService(WINDOW_SERVICE)).addView(cursor, cursorLp); } catch (Exception e) { cursor = null; return; }
        }
        cursor.setVisibility(View.VISIBLE);
        cursorLp.x = Math.round(px); cursorLp.y = Math.round(py);
        try { ((WindowManager) getSystemService(WINDOW_SERVICE)).updateViewLayout(cursor, cursorLp); } catch (Exception ignored) { }
        h.removeCallbacks(hideCursor);
        h.postDelayed(hideCursor, 6000);
    }

    private void movePointer(int dx, int dy) {
        Point sc = screen();
        if (px < 0) { px = sc.x / 2f; py = sc.y / 2f; }
        float k = getResources().getDisplayMetrics().density * 0.9f;
        px = Math.max(0, Math.min(sc.x - 1, px + dx * k));
        py = Math.max(0, Math.min(sc.y - 1, py + dy * k));
        showPointer();
    }

    private void tap(float x, float y, long ms) {
        Path p = new Path();
        p.moveTo(x, y);
        try { dispatchGesture(new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(p, 0, ms)).build(), null, null); }
        catch (Exception ignored) { }
    }

    private void swipe(float x1, float y1, float x2, float y2, long ms) {
        Point sc = screen();
        y2 = Math.max(1, Math.min(sc.y - 2, y2));
        Path p = new Path();
        p.moveTo(x1, y1); p.lineTo(x2, y2);
        try { dispatchGesture(new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(p, 0, ms)).build(), null, null); }
        catch (Exception ignored) { }
    }

    // ---- cámara del teléfono: se buscan sus botones por nombre (obturador, cambiar cámara, flash) y se tocan

    private static final String[] SHOT = {"shutter", "obturador", "disparador", "capturar", "tomar foto", "take photo", "capture", "camera_btn", "快门", "拍照"};
    private static final String[] SWITCH = {"switch", "cambiar cámara", "cambiar camara", "girar", "frontal", "flip", "lens", "切换"};
    private static final String[] FLASH = {"flash", "linterna", "闪光"};

    /** Toca un botón de la cámara que está al frente. Devuelve false si no lo encontró. */
    public boolean cameraClick(String what) {
        String[] keys = "shot".equals(what) ? SHOT : "switch".equals(what) ? SWITCH : FLASH;
        android.view.accessibility.AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        android.view.accessibility.AccessibilityNodeInfo n = find(root, keys, 0);
        if (n != null) {
            android.view.accessibility.AccessibilityNodeInfo c = n;
            while (c != null && !c.isClickable()) c = c.getParent();
            if (c != null && c.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) return true;
            android.graphics.Rect r = new android.graphics.Rect();
            n.getBoundsInScreen(r);
            tap(r.exactCenterX(), r.exactCenterY(), 40);
            return true;
        }
        if ("shot".equals(what)) {   // sin nombre: el obturador suele estar al centro del borde inferior (o derecho en horizontal)
            Point sc = screen();
            if (sc.x > sc.y) tap(sc.x * 0.92f, sc.y * 0.5f, 40); else tap(sc.x * 0.5f, sc.y * 0.88f, 40);
            return true;
        }
        return false;
    }

    private android.view.accessibility.AccessibilityNodeInfo find(android.view.accessibility.AccessibilityNodeInfo n, String[] keys, int depth) {
        if (n == null || depth > 30) return null;
        String d = ((n.getContentDescription() == null ? "" : n.getContentDescription()) + " "
                + (n.getViewIdResourceName() == null ? "" : n.getViewIdResourceName()) + " "
                + (n.getText() == null ? "" : n.getText())).toLowerCase(java.util.Locale.ROOT);
        for (String k : keys) if (d.contains(k)) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            android.view.accessibility.AccessibilityNodeInfo f = find(n.getChild(i), keys, depth + 1);
            if (f != null) return f;
        }
        return null;
    }

    /** Zoom de la cámara: pellizco con dos dedos al centro de la pantalla. */
    public void cameraZoom(boolean in) {
        Point sc = screen();
        float cx = sc.x / 2f, cy = sc.y / 2f, near = Math.min(sc.x, sc.y) * 0.08f, far = Math.min(sc.x, sc.y) * 0.3f;
        float a = in ? near : far, b = in ? far : near;
        Path p1 = new Path(), p2 = new Path();
        p1.moveTo(cx - a, cy); p1.lineTo(cx - b, cy);
        p2.moveTo(cx + a, cy); p2.lineTo(cx + b, cy);
        try {
            dispatchGesture(new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(p1, 0, 260))
                    .addStroke(new GestureDescription.StrokeDescription(p2, 0, 260)).build(), null, null);
        } catch (Exception ignored) { }
    }

    // ---- gamepad: cada control toca el principal en el mismo lugar relativo, con los dedos virtuales de Game Pal
    // (se puede mover y disparar al mismo tiempo, y el dedo queda apoyado mientras se aprieta el botón)
    private final java.util.Map<String, Integer> padFinger = new java.util.HashMap<>();
    private final java.util.Map<String, float[]> padLast = new java.util.HashMap<>();

    /** Un dedo virtual fijo por control (los números de Game Pal son más bajos: no se pisan). */
    private int padId(String id) {
        Integer f = padFinger.get(id);
        if (f == null) { f = 1000 + padFinger.size(); padFinger.put(id, f); }
        return f;
    }

    private void pad(JSONObject m) {
        Point sc = screen();
        float x = (float) m.optDouble("x", 0.5) * sc.x, y = (float) m.optDouble("y", 0.5) * sc.y;
        String id = m.optString("id");
        int f = padId(id);
        if ("btn".equals(m.optString("a"))) {
            GamePalTouch.touch(this, f, m.optBoolean("down") ? 0 : 2, x, y);
            return;
        }
        // joystick: empieza en el centro y se mueve hasta el borde; al soltar levanta donde estaba (así el ataque apunta)
        int phase = m.optInt("ph");
        float r = (float) m.optDouble("r", 0.2) * Math.min(sc.x, sc.y);
        float nx = (float) m.optDouble("nx", 0), ny = (float) m.optDouble("ny", 0);
        if (phase == 2) {
            float[] l = padLast.remove(id);
            if (l != null) { nx = l[0]; ny = l[1]; }
            GamePalTouch.touch(this, f, 2, x + nx * r, y + ny * r);
            return;
        }
        if (phase == 0) GamePalTouch.touch(this, f, 0, x, y);
        if (phase == 1 || nx != 0 || ny != 0) GamePalTouch.touch(this, f, 1, x + nx * r, y + ny * r);
        padLast.put(id, new float[]{nx, ny});
    }

    // ---------------- cambio de teclado ----------------

    static String mesaImeId(Context c) {
        InputMethodManager imm = (InputMethodManager) c.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm == null) return null;
        for (InputMethodInfo i : imm.getEnabledInputMethodList())
            if (c.getPackageName().equals(i.getPackageName())) return i.getId();
        return null;
    }

    /** Cambia al teclado ChronOS, recordando cuál estaba antes. */
    public boolean switchToMesa() {
        String id = mesaImeId(this);
        if (id == null) return false;
        String cur = Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        if (id.equals(cur)) return true;
        SharedPreferences p = getSharedPreferences("mesa", MODE_PRIVATE);
        if (cur != null) p.edit().putString("prevIme", cur).apply();
        return switchTo(id);
    }

    /** Devuelve el teclado que había antes (por ejemplo, al perder la conexión con el extendido). */
    public void restorePrevious() {
        String id = mesaImeId(this);
        String cur = Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        String prev = getSharedPreferences("mesa", MODE_PRIVATE).getString("prevIme", null);
        if (prev != null && id != null && id.equals(cur)) switchTo(prev);
    }

    private boolean switchTo(String imeId) {
        if (Build.VERSION.SDK_INT < 33) return false;
        try {
            Object ctl = getSoftKeyboardController();
            Method m = ctl.getClass().getMethod("switchToInputMethod", String.class);   // Android 13+
            Object r = m.invoke(ctl, imeId);
            return !(r instanceof Boolean) || (Boolean) r;
        } catch (Exception e) {
            return false;
        }
    }

    /** false en la versión liviana, que no incluye este servicio. */
    static boolean available(Context c) {
        try {
            c.getPackageManager().getServiceInfo(new android.content.ComponentName(c, MesaAccess.class), 0);
            return true;
        } catch (Exception e) { return false; }
    }

    static boolean isEnabled(Context c) {
        String list = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return list != null && list.contains(c.getPackageName() + "/");
    }
}
