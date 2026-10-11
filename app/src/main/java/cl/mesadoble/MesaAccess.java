package cl.mesadoble;

import android.accessibilityservice.AccessibilityService;
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
 * Servicio de accesibilidad del teléfono PRINCIPAL. Solo hace dos cosas:
 * 1) cambia el método de entrada a Mesa Doble (y lo devuelve al anterior al desconectarse);
 * 2) avisa al extendido cuando se abre una app de chat, para mostrar el teclado allá.
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
