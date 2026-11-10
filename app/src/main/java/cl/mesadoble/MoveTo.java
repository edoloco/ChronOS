package cl.mesadoble;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "Mover a extendido": con YouTube o un navegador al frente en el PRINCIPAL, en el EXTENDIDO flota una flecha naranja
 * (un triángulo estirado que sube y baja). Tocarla pasa el video (en el mismo segundo) o la página al extendido.
 * Arrastrarla fuera de la pantalla la descarta.
 *   moveOffer {pkg, kind: yt|web} / moveOffer {off}  → (extendido) mostrar / quitar la flecha
 *   moveReq                                            → (principal) leer el video o la página
 *   moveOpen {url, pkg}                                → (extendido) abrirlo (en su app; si no está, la de siempre)
 *   moveFail {msg}                                     → (extendido) aviso
 * Video de YouTube: el enlace se saca con Compartir › Copiar vínculo (accesibilidad) y el segundo, de su sesión de
 * música/video. También vale compartir a mano cualquier enlace a "Al extendido" (MoveTo.ShareIn).
 */
final class MoveTo {

    private MoveTo() { }

    static final String YT = "com.google.android.youtube";
    private static final Handler h = new Handler(Looper.getMainLooper());
    private static Set<String> browsers;
    private static String offered;   // (principal) app que tiene la flecha ofrecida

    // ======================= PRINCIPAL =======================

    static boolean isBrowser(Context c, String pkg) {
        if (browsers == null) {
            browsers = new HashSet<>();
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("http://example.com")).addCategory(Intent.CATEGORY_BROWSABLE);
                for (ResolveInfo r : c.getPackageManager().queryIntentActivities(i, 0x00020000 /* MATCH_ALL */))
                    if (r.activityInfo != null) browsers.add(r.activityInfo.packageName);
            } catch (Exception ignored) { }
            browsers.remove(c.getPackageName());
            browsers.remove(YT);
        }
        return browsers.contains(pkg);
    }

    /** Cambió la app del frente en el principal. */
    static void onFront(Context c, Hub hub, String pkg) {
        String kind = YT.equals(pkg) ? "yt" : isBrowser(c, pkg) ? "web" : null;
        if (kind != null) {
            if (pkg.equals(offered)) return;
            offered = pkg;
            hub.send("moveOffer", "pkg", pkg, "kind", kind);
        } else if (offered != null) {
            // teclados, avisos y ventanas del sistema no cuentan como "otra app"
            if (pkg.startsWith("com.android.systemui") || pkg.contains("inputmethod")) return;
            offered = null;
            hub.send("moveOffer", "off", true);
        }
    }

    /** El extendido tocó la flecha: se lee el video o la página y se manda. */
    static void request(Context c, Hub hub) {
        String pkg = MesaAccess.front;
        if (YT.equals(pkg)) { ytLink(c, hub); return; }
        if (pkg != null && isBrowser(c, pkg)) {
            String url = browserUrl(pkg);
            if (url == null) { hub.send("moveFail", "msg", "No pude leer la dirección de la página"); return; }
            hub.send("moveOpen", "url", url, "pkg", pkg);
            MesaAccess s = MesaAccess.instance;
            if (s != null) s.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME);
            offered = null;
            return;
        }
        hub.send("moveFail", "msg", "En el principal no hay un video ni un navegador al frente");
    }

    // ---- navegadores: la barra de direcciones

    private static final String[] URL_IDS = {
            ":id/url_bar", ":id/location_bar_edit_text", ":id/mozac_browser_toolbar_url_view", ":id/url_field",
            ":id/omnibarTextInput", ":id/url", ":id/search_box_text", ":id/address_bar_edit_text", ":id/toolbar_url"};
    private static final Pattern URLISH = Pattern.compile("^(https?://)?([\\w-]+\\.)+[a-z]{2,}(:\\d+)?(/\\S*)?$", Pattern.CASE_INSENSITIVE);

    private static String browserUrl(String pkg) {
        MesaAccess s = MesaAccess.instance;
        if (s == null) return null;
        AccessibilityNodeInfo root = s.getRootInActiveWindow();
        if (root == null) return null;
        for (String id : URL_IDS) {
            try {
                List<AccessibilityNodeInfo> l = root.findAccessibilityNodeInfosByViewId(pkg + id);
                for (AccessibilityNodeInfo n : l) {
                    CharSequence t = n.getText();
                    if (t != null && URLISH.matcher(t.toString().trim()).matches()) return full(t.toString().trim());
                }
            } catch (Exception ignored) { }
        }
        // cualquier texto con forma de dirección en la parte de arriba de la pantalla
        Point sc = new Point();
        ((WindowManager) s.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRealSize(sc);
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(root);
        Rect b = new Rect();
        int seen = 0;
        while (!q.isEmpty() && seen++ < 500) {
            AccessibilityNodeInfo n = q.poll();
            if (n == null) continue;
            CharSequence t = n.getText();
            if (t != null && t.length() < 2000) {
                n.getBoundsInScreen(b);
                if (b.top < sc.y * 0.3f && URLISH.matcher(t.toString().trim()).matches()) return full(t.toString().trim());
            }
            for (int i = 0; i < n.getChildCount(); i++) q.add(n.getChild(i));
        }
        return null;
    }

    private static String full(String u) { return u.matches("(?i)^https?://.*") ? u : "https://" + u; }

    // ---- YouTube: Compartir › Copiar vínculo, y el segundo de su sesión

    private static long ytStart;

    private static void ytLink(Context c, Hub hub) {
        MesaAccess s = MesaAccess.instance;
        if (s == null) { hub.send("moveFail", "msg", "Falta la accesibilidad de ChronOS en el principal"); return; }
        ytStart = SystemClock.uptimeMillis();
        try {   // se vacía el portapapeles: así se sabe que lo que llegue es el enlace de este video
            ClipboardManager cm = (ClipboardManager) s.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("", ""));
        } catch (Exception ignored) { }
        if (!clickText(s, Pattern.compile("^(Compartir|Share)$", Pattern.CASE_INSENSITIVE), true)) {
            // los controles del video pueden estar escondidos: un toque en el video los muestra
            hub.send("moveFail", "msg", "Toca el video en el principal (para ver «Compartir») y vuelve a tocar la flecha");
            return;
        }
        h.postDelayed(() -> {
            if (!clickText(s, Pattern.compile("^(Copiar v[ií]nculo|Copiar enlace|Copy link)$", Pattern.CASE_INSENSITIVE), false)) {
                s.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
                hub.send("moveFail", "msg", "No encontré «Copiar vínculo» en YouTube: usa Compartir › Al extendido");
                return;
            }
            h.postDelayed(() -> {
                String id = ytId(clip(s));
                if (id == null) {
                    hub.send("moveFail", "msg", "No pude leer el enlace del video");
                    return;
                }
                openYt(c, hub, id);
            }, 500);
        }, 900);
    }

    /** Manda el video con el segundo actual y lo pausa aquí. */
    static void openYt(Context c, Hub hub, String id) {
        long sec = 0;
        MediaController mc = ytSession(c);
        if (mc != null && mc.getPlaybackState() != null) {
            PlaybackState st = mc.getPlaybackState();
            long pos = st.getPosition();
            if (st.getState() == PlaybackState.STATE_PLAYING)
                pos += (long) ((SystemClock.elapsedRealtime() - st.getLastPositionUpdateTime()) * Math.max(0.1f, st.getPlaybackSpeed()));
            sec = Math.max(0, pos / 1000);
            try { mc.getTransportControls().pause(); } catch (Exception ignored) { }
        }
        hub.send("moveOpen", "url", "https://www.youtube.com/watch?v=" + id + (sec > 0 ? "&t=" + sec + "s" : ""), "pkg", YT);
        offered = null;
        MesaAccess s = MesaAccess.instance;
        if (s != null) h.postDelayed(() -> s.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME), 300);
    }

    private static MediaController ytSession(Context c) {
        try {
            MediaSessionManager msm = (MediaSessionManager) c.getSystemService(Context.MEDIA_SESSION_SERVICE);
            for (MediaController mc : msm.getActiveSessions(new ComponentName(c, NotifService.class)))
                if (YT.equals(mc.getPackageName())) return mc;
        } catch (Exception ignored) { }
        return null;
    }

    private static final Pattern YT_ID = Pattern.compile("(?:youtu\\.be/|[?&]v=|/shorts/|/live/|/embed/)([\\w-]{11})");

    static String ytId(String s) {
        if (s == null) return null;
        Matcher m = YT_ID.matcher(s);
        return m.find() ? m.group(1) : null;
    }

    private static String clip(Context c) {
        try {
            ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData d = cm == null ? null : cm.getPrimaryClip();
            if (d == null || d.getItemCount() == 0) return "";
            CharSequence t = d.getItemAt(0).coerceToText(c);
            return t == null ? "" : t.toString();
        } catch (Exception e) { return ""; }
    }

    /** Toca el primer elemento visible cuyo texto (o descripción) calza. */
    private static boolean clickText(MesaAccess s, Pattern p, boolean desc) {
        AccessibilityNodeInfo root = s.getRootInActiveWindow();
        if (root == null) return false;
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(root);
        int seen = 0;
        while (!q.isEmpty() && seen++ < 900) {
            AccessibilityNodeInfo n = q.poll();
            if (n == null) continue;
            CharSequence t = n.getText();
            CharSequence d = n.getContentDescription();
            if (n.isVisibleToUser() && ((t != null && p.matcher(t.toString().trim()).find()) || (d != null && p.matcher(d.toString().trim()).find()))) {
                for (AccessibilityNodeInfo x = n; x != null; x = x.getParent())
                    if (x.isClickable()) { x.performAction(AccessibilityNodeInfo.ACTION_CLICK); return true; }
            }
            for (int i = 0; i < n.getChildCount(); i++) q.add(n.getChild(i));
        }
        return false;
    }

    /** Compartir › "Al extendido": cualquier enlace que se comparta se abre en el extendido (YouTube, en su segundo). */
    public static class ShareIn extends Activity {
        @Override protected void onCreate(Bundle b) {
            super.onCreate(b);
            String txt = getIntent() != null ? getIntent().getStringExtra(Intent.EXTRA_TEXT) : null;
            Hub hub = Hub.get(this);
            if (txt == null || !hub.connected()) {
                android.widget.Toast.makeText(this, hub.connected() ? "No llegó ningún enlace" : "El extendido no está conectado", android.widget.Toast.LENGTH_SHORT).show();
                finish();
                return;
            }
            String id = ytId(txt);
            if (id != null) openYt(this, hub, id);
            else {
                Matcher m = Pattern.compile("https?://\\S+").matcher(txt);
                if (m.find()) hub.send("moveOpen", "url", m.group(), "pkg", MesaAccess.front == null ? "" : MesaAccess.front);
                else android.widget.Toast.makeText(this, "No es un enlace", android.widget.Toast.LENGTH_SHORT).show();
            }
            android.widget.Toast.makeText(this, "Abriendo en el extendido", android.widget.Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    // ======================= EXTENDIDO =======================

    private static Arrow arrow;
    private static WindowManager awm;
    private static String dismissedFor;

    static void onMessage(Context c, Hub hub, JSONObject m) {
        switch (m.optString("t")) {
            case "moveOffer":
                if (m.optBoolean("off")) { dismissedFor = null; hide(); return; }
                if (m.optString("pkg").equals(dismissedFor)) return;   // ya la descartó para esta app
                show(c, hub, m.optString("pkg"));
                break;
            case "moveOpen": {
                hide();
                String url = m.optString("url"), pkg = m.optString("pkg");
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                // su misma app si está en el extendido; si no, la de siempre
                if (!pkg.isEmpty() && c.getPackageManager().getLaunchIntentForPackage(pkg) != null) i.setPackage(pkg);
                if (hub.startOrNotify(i, 94, "Mover a extendido", "Toca para abrirlo aquí") != Hub.LAUNCHED && i.getPackage() != null) {
                    i.setPackage(null);
                    hub.startOrNotify(i, 94, "Mover a extendido", "Toca para abrirlo aquí");
                }
                break;
            }
            case "moveFail":
                if (arrow != null) arrow.busy(false);
                hub.toast(m.optString("msg"));
                break;
        }
    }

    private static void show(Context c, Hub hub, String pkg) {
        if (arrow != null) { arrow.pkg = pkg; arrow.busy(false); return; }
        Context ctx = MesaAccess.instance != null ? MesaAccess.instance : c.getApplicationContext();
        int type = MesaAccess.instance != null ? WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY : WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        if (MesaAccess.instance == null && !Settings.canDrawOverlays(c)) return;
        Point sc = new Point();
        awm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        awm.getDefaultDisplay().getRealSize(sc);
        // largo = 4 letras del teclado Android de ChronOS (cada tecla ≈ un décimo del ancho)
        int letter = Math.min(sc.x, sc.y) / 10, len = letter * 4, wide = Math.round(letter * 2.6f);
        arrow = new Arrow(ctx, hub, pkg);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(wide, len + letter, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = (sc.x - wide) / 2;
        lp.y = Math.round(sc.y * 0.08f);
        arrow.lp = lp;
        arrow.screen = sc;
        try { awm.addView(arrow, lp); } catch (Exception e) { arrow = null; }
    }

    static void hide() {
        if (arrow == null) return;
        arrow.stop();
        try { awm.removeView(arrow); } catch (Exception ignored) { }
        arrow = null;
    }

    /** La flecha: triángulo naranja estirado (punta hacia abajo) que flota; tocar = mover, arrastrar afuera = descartar. */
    private static final class Arrow extends View {
        final Hub hub;
        String pkg;
        WindowManager.LayoutParams lp;
        Point screen;
        private final float dp;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG), txt = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final ValueAnimator bob = ValueAnimator.ofFloat(0, 1);
        private float off;
        private boolean busy;

        Arrow(Context c, Hub hub, String pkg) {
            super(c);
            this.hub = hub;
            this.pkg = pkg;
            dp = c.getResources().getDisplayMetrics().density;
            fill.setColor(0xFFF57C1F);
            fill.setShadowLayer(6 * dp, 0, 2 * dp, 0x80000000);
            setLayerType(LAYER_TYPE_SOFTWARE, null);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(2 * dp);
            edge.setColor(0xCCFFFFFF);
            edge.setStrokeJoin(Paint.Join.ROUND);
            txt.setColor(Color.WHITE);
            txt.setTextAlign(Paint.Align.CENTER);
            txt.setFakeBoldText(true);
            bob.setDuration(1400);
            bob.setRepeatCount(ValueAnimator.INFINITE);
            bob.setRepeatMode(ValueAnimator.REVERSE);
            bob.addUpdateListener(a -> { off = (float) a.getAnimatedValue(); invalidate(); });
            bob.start();
        }

        void stop() { bob.cancel(); }
        void busy(boolean b) { busy = b; invalidate(); }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), hh = getHeight(), m = 8 * dp;
            float dy = off * (hh * 0.16f);   // sube y baja
            float top = m + dy, bottom = hh * 0.84f - m + dy;
            Path p = new Path();
            p.moveTo(m, top);
            p.lineTo(w - m, top);
            p.lineTo(w / 2f, bottom);
            p.close();
            c.drawPath(p, fill);
            c.drawPath(p, edge);
            txt.setTextSize(Math.max(10 * dp, w * 0.13f));
            float ty = top + (bottom - top) * 0.22f;
            if (busy) c.drawText("Moviendo…", w / 2f, ty + txt.getTextSize(), txt);
            else {
                c.drawText("Mover a", w / 2f, ty + txt.getTextSize(), txt);
                c.drawText("extendido", w / 2f, ty + txt.getTextSize() * 2.1f, txt);
            }
        }

        private float downX, downY;
        private int lx0, ly0;
        private boolean dragged;

        @Override public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX(); downY = e.getRawY(); lx0 = lp.x; ly0 = lp.y; dragged = false;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                    if (Math.hypot(dx, dy) > 10 * dp) dragged = true;
                    if (dragged) { lp.x = lx0 + Math.round(dx); lp.y = ly0 + Math.round(dy); try { awm.updateViewLayout(this, lp); } catch (Exception ignored) { } }
                    return true;
                }
                case MotionEvent.ACTION_UP: {
                    if (!dragged) {   // toque: mover
                        if (busy) return true;
                        if (!hub.connected()) { hub.toast("El principal no está conectado"); return true; }
                        busy(true);
                        hub.send("moveReq");
                        h.postDelayed(() -> busy(false), 8000);
                        return true;
                    }
                    // arrastrada (casi) fuera de la pantalla: se descarta para esta app
                    float cx = lp.x + getWidth() / 2f, cy = lp.y + getHeight() / 2f;
                    if (cx < 0 || cx > screen.x || cy < 0 || cy > screen.y
                            || lp.x < -getWidth() / 3 || lp.x > screen.x - getWidth() * 2 / 3 || lp.y > screen.y - getHeight() * 2 / 3 || lp.y < -getHeight() / 3) {
                        dismissedFor = pkg;
                        hide();
                    }
                    return true;
                }
            }
            return true;
        }
    }
}
