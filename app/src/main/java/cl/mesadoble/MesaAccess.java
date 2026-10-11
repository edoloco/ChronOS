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
 * 1) cambia el método de entrada a Mesa Doble (y lo devuelve al anterior al desconectarse);
 * 2) avisa al extendido cuando se abre una app de chat, para mostrar el teclado allá;
 * 3) ejecuta el touchpad (puntero, clic, desplazar, atrás/inicio) y el gamepad (toques en pantalla).
 * No lee el contenido de las pantallas.
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

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Hub h = Hub.get(this);
        h.ensureStarted();
        if (h.isPrincipal() && h.connected()) switchToMesa();
    }

    @Override public boolean onUnbind(android.content.Intent intent) {
        if (instance == this) instance = null;
        return super.onUnbind(intent);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || e.getPackageName() == null) return;
        String pkg = e.getPackageName().toString();
        if (pkg.equals(lastPkg)) return;
        lastPkg = pkg;
        Hub h = Hub.get(this);
        if (!h.isPrincipal() || !h.connected()) return;
        if (CHAT_APPS.contains(pkg)) {
            switchToMesa();
            h.send("openKb", "pkg", pkg);
        }
    }

    @Override public void onInterrupt() { }

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

    // ---- gamepad: cada control toca el principal en el mismo lugar relativo
    private GestureDescription.StrokeDescription joyStroke;
    private float joyX, joyY, joyTX, joyTY;
    private final Runnable joyHold = new Runnable() {
        @Override public void run() {
            if (joyStroke == null) return;
            continueJoy(joyTX, joyTY, true);
            h.postDelayed(this, 45);
        }
    };

    private void continueJoy(float tx, float ty, boolean willContinue) {
        if (joyStroke == null) return;
        Path p = new Path();
        p.moveTo(joyX, joyY);
        p.lineTo(tx, ty);
        try {
            joyStroke = joyStroke.continueStroke(p, 0, 60, willContinue);
            dispatchGesture(new GestureDescription.Builder().addStroke(joyStroke).build(), null, null);
            joyX = tx; joyY = ty;
        } catch (Exception e) { joyStroke = null; }
        if (!willContinue) joyStroke = null;
    }

    private void pad(JSONObject m) {
        Point sc = screen();
        float x = (float) m.optDouble("x", 0.5) * sc.x, y = (float) m.optDouble("y", 0.5) * sc.y;
        String a = m.optString("a");
        if ("btn".equals(a)) {
            if (m.optBoolean("down")) tap(x, y, 50);
            return;
        }
        // joystick
        int phase = m.optInt("ph");
        float r = (float) m.optDouble("r", 0.2) * Math.min(sc.x, sc.y);
        float tx = x + (float) m.optDouble("nx") * r, ty = y + (float) m.optDouble("ny") * r;
        if (phase == 0) {
            h.removeCallbacks(joyHold);
            Path p = new Path();
            p.moveTo(x, y);
            p.lineTo(tx, ty);
            joyStroke = new GestureDescription.StrokeDescription(p, 0, 60, true);
            try { dispatchGesture(new GestureDescription.Builder().addStroke(joyStroke).build(), null, null); }
            catch (Exception e) { joyStroke = null; return; }
            joyX = tx; joyY = ty; joyTX = tx; joyTY = ty;
            h.postDelayed(joyHold, 45);
        } else if (phase == 1) {
            joyTX = tx; joyTY = ty;
        } else {
            h.removeCallbacks(joyHold);
            continueJoy(x, y, false);
        }
    }

    // ---------------- cambio de teclado ----------------

    static String mesaImeId(Context c) {
        InputMethodManager imm = (InputMethodManager) c.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm == null) return null;
        for (InputMethodInfo i : imm.getEnabledInputMethodList())
            if (c.getPackageName().equals(i.getPackageName())) return i.getId();
        return null;
    }

    /** Cambia al teclado Mesa Doble, recordando cuál estaba antes. */
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
