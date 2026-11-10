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
 * Pasar videos y páginas entre las pantallas: con YouTube o un navegador al frente, en ESE teléfono aparece una flecha
 * naranja chica sin letras (triángulo ancho que flota). En el principal va abajo a la izquierda apuntando al
 * extendido; en el extendido, arriba a la izquierda apuntando al principal (para devolverlo). Tocarla pasa el video
 * (en el mismo segundo) o la página al otro teléfono; lanzarla fuera de la pantalla la descarta para esa app.
 * Se activa o desactiva en Ajustes › Configuración y pruebas (pref "moveArrow", en los dos).
 *   moveOpen {url, pkg} → (el otro) abrirlo en su misma app; si no la tiene, en la de siempre
 * Video de YouTube: el enlace se saca con Compartir › Copiar vínculo (accesibilidad) y el segundo, de su sesión de
 * música/video. También vale compartir a mano cualquier enlace a "Al otro teléfono" (MoveTo.ShareIn).
 */
final class MoveTo {

    private MoveTo() { }

    static final String YT = "com.google.android.youtube";
    private static final Handler h = new Handler(Looper.getMainLooper());
    private static Set<String> browsers;
    private static String offered;   // (principal) app que tiene la flecha ofrecida

    // ======================= PRINCIPAL =======================

    /** Avisos de diagnóstico: las primeras 4 veces en cada teléfono (para saber en qué paso falla). */
    private static void diag(Context c, Hub hub, String msg) {
        android.content.SharedPreferences p = c.getSharedPreferences("mesa", Context.MODE_PRIVATE);
        int n = p.getInt("moveDiag", 0);
        if (n >= 4) return;
        p.edit().putInt("moveDiag", n + 1).apply();
        hub.toast(msg);
    }

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

    static boolean enabled(Context c) { return c.getSharedPreferences("mesa", Context.MODE_PRIVATE).getBoolean("moveArrow", true); }

    /**
     * Cambió la app del frente en ESTE teléfono (los dos lo miran). Con YouTube o un navegador, aquí mismo aparece la
     * flecha chica: en el principal abajo a la izquierda apuntando al extendido; en el extendido arriba a la
     * izquierda apuntando al principal (para devolverlo).
     */
    static void onFront(Context c, Hub hub, String pkg) {
        String kind = YT.equals(pkg) ? "yt" : isBrowser(c, pkg) ? "web" : null;
        if (kind != null) {
            offered = pkg;
            if (!enabled(c) || !hub.connected() || pkg.equals(dismissedFor)) return;
            h.post(() -> show(c, hub, pkg));
        } else if (offered != null) {
            // teclados, avisos y ventanas del sistema no cuentan como "otra app"
            if (pkg.startsWith("com.android.systemui") || pkg.contains("inputmethod")) return;
            offered = null;
            dismissedFor = null;
            h.post(MoveTo::hide);
        }
    }

    /** Se conectaron (o cambió el ajuste): la flecha según lo que esté al frente. */
    static void reoffer(Context c, Hub hub) {
        offered = null;
        h.post(MoveTo::hide);
        String f = MesaAccess.front;
        if (f != null) onFront(c, hub, f);
    }

    private static void fail(Hub hub, String msg) {
        hub.toast(msg);
        if (arrow != null) h.post(() -> { if (arrow != null) arrow.busy(false); });
    }

    /** Se tocó la flecha: se lee el video o la página de ESTE teléfono y se abre en el otro. */
    static void request(Context c, Hub hub, String asked) {
        String pkg = asked != null && !asked.isEmpty() ? asked : offered != null ? offered : MesaAccess.front;
        if (!hub.connected()) { fail(hub, "El otro teléfono no está conectado"); return; }
        if (YT.equals(pkg)) { ytLink(c, hub); return; }
        if (pkg != null && isBrowser(c, pkg)) {
            String url = browserUrl(pkg);
            if (url == null) { fail(hub, "No pude leer la dirección de la página"); return; }
            hub.send("moveOpen", "url", url, "pkg", pkg);
            done();
            return;
        }
        fail(hub, "No hay un video ni un navegador al frente");
    }

    /** Ya se mandó: aquí se va al inicio y se quita la flecha. */
    private static void done() {
        offered = null;
        h.post(MoveTo::hide);
        MesaAccess s = MesaAccess.instance;
        if (s != null) h.postDelayed(() -> s.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME), 300);
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
        if (s == null) { fail(hub, "Falta la accesibilidad de ChronOS en el principal"); return; }
        ytStart = SystemClock.uptimeMillis();
        try {   // se vacía el portapapeles: así se sabe que lo que llegue es el enlace de este video
            ClipboardManager cm = (ClipboardManager) s.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("", ""));
        } catch (Exception ignored) { }
        if (!clickText(s, Pattern.compile("^(Compartir|Share)$", Pattern.CASE_INSENSITIVE), true)) {
            // los controles del video pueden estar escondidos: un toque en el video los muestra
            fail(hub, "Toca el video en el principal (para ver «Compartir») y vuelve a tocar la flecha");
            return;
        }
        h.postDelayed(() -> {
            if (!clickText(s, Pattern.compile("^(Copiar v[ií]nculo|Copiar enlace|Copy link)$", Pattern.CASE_INSENSITIVE), false)) {
                s.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
                fail(hub, "No encontré «Copiar vínculo» en YouTube: usa Compartir › Al extendido");
                return;
            }
            h.postDelayed(() -> {
                String id = ytId(clip(s));
                if (id == null) {
                    fail(hub, "No pude leer el enlace del video");
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
        done();
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
            android.widget.Toast.makeText(this, "Abriendo en el otro teléfono", android.widget.Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    // ======================= EXTENDIDO =======================

    private static Arrow arrow;
    private static WindowManager awm;
    private static String dismissedFor;

    /** Llegó algo del otro teléfono para abrir aquí. */
    static void onMessage(Context c, Hub hub, JSONObject m) {
        if (!"moveOpen".equals(m.optString("t"))) return;
        String url = m.optString("url"), pkg = m.optString("pkg");
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        // su misma app si está en este teléfono; si no, la de siempre
        if (!pkg.isEmpty() && c.getPackageManager().getLaunchIntentForPackage(pkg) != null) i.setPackage(pkg);
        if (hub.startOrNotify(i, 94, "Video o página del otro teléfono", "Toca para abrirlo aquí") != Hub.LAUNCHED && i.getPackage() != null) {
            i.setPackage(null);
            hub.startOrNotify(i, 94, "Video o página del otro teléfono", "Toca para abrirlo aquí");
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
        boolean down = hub.isPrincipal();   // principal: hacia el extendido (abajo); extendido: hacia el principal (arriba)
        // chica: 1,6 teclas del teclado Android en horizontal; triángulo ancho, con aire para la sombra
        float d = ctx.getResources().getDisplayMetrics().density;
        int tri = Math.round(Math.max(sc.x, sc.y) / 10f * 1.6f);
        int pad = Math.round(16 * d), wide = tri + 2 * pad, tall = Math.round(tri * 0.52f) + 2 * pad + Math.round(8 * d);
        arrow = new Arrow(ctx, hub, pkg, down);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(wide, tall, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        int margin = Math.round(10 * d);
        lp.x = margin;
        lp.y = down ? sc.y - tall - Math.round(56 * d) : margin + Math.round(24 * d);   // sobre la barra de abajo / bajo la de arriba
        arrow.lp = lp;
        arrow.screen = sc;
        try { awm.addView(arrow, lp); }
        catch (Exception e) { arrow = null; }
    }

    static void hide() {
        if (arrow == null) return;
        arrow.stop();
        try { awm.removeView(arrow); } catch (Exception ignored) { }
        arrow = null;
    }

    /**
     * La flecha: triángulo naranja ancho (punta hacia abajo) que flota. Línea de ChronOS: degradado, brillo arriba y
     * sombra suave por fuera, sin bordes; la sombra se achica cuando la flecha sube. Tocar = mover, arrastrar afuera =
     * descartar.
     */
    private static final class Arrow extends View {
        final Hub hub;
        String pkg;
        WindowManager.LayoutParams lp;
        Point screen;
        private final float dp;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), gloss = new Paint(Paint.ANTI_ALIAS_FLAG),
                shadow = new Paint(Paint.ANTI_ALIAS_FLAG), txt = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final ValueAnimator bob = ValueAnimator.ofFloat(0, 1);
        private float off;
        private boolean busy;
        private int gw = -1;

        final boolean down;

        Arrow(Context c, Hub hub, String pkg, boolean down) {
            super(c);
            this.hub = hub;
            this.pkg = pkg;
            this.down = down;
            dp = c.getResources().getDisplayMetrics().density;
            setLayerType(LAYER_TYPE_SOFTWARE, null);   // para la sombra difuminada
            android.graphics.CornerPathEffect round = new android.graphics.CornerPathEffect(16 * dp);
            fill.setPathEffect(round);
            gloss.setPathEffect(round);
            shadow.setPathEffect(round);
            shadow.setMaskFilter(new android.graphics.BlurMaskFilter(14 * dp, android.graphics.BlurMaskFilter.Blur.NORMAL));
            txt.setColor(Color.WHITE);
            txt.setTextAlign(Paint.Align.CENTER);
            txt.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
            txt.setShadowLayer(3 * dp, 0, 1.5f * dp, 0x66000000);
            bob.setDuration(1600);
            bob.setRepeatCount(ValueAnimator.INFINITE);
            bob.setRepeatMode(ValueAnimator.REVERSE);
            bob.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
            bob.addUpdateListener(a -> { off = (float) a.getAnimatedValue(); invalidate(); });
            bob.start();
        }

        void stop() { bob.cancel(); }
        void busy(boolean b) { busy = b; invalidate(); }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), pad = 16 * dp, lift = 8 * dp;
            float l = pad, r = w - pad, triH = (r - l) * 0.52f;
            float up = off * lift;   // flota
            float base = pad + lift - up;   // borde de arriba del triángulo (el ancho)
            if (gw != getWidth()) {
                gw = getWidth();
                fill.setShader(new android.graphics.LinearGradient(0, 0, 0, triH, new int[]{0xFFFFB25E, 0xFFF57C1F, 0xFFD5530A},
                        new float[]{0, 0.55f, 1}, android.graphics.Shader.TileMode.CLAMP));
                gloss.setShader(new android.graphics.LinearGradient(0, 0, 0, triH * 0.5f, 0x66FFFFFF, 0x00FFFFFF, android.graphics.Shader.TileMode.CLAMP));
            }
            Path p = new Path();
            if (down) { p.moveTo(l, 0); p.lineTo(r, 0); p.lineTo(w / 2f, triH); }   // punta abajo: al extendido
            else { p.moveTo(w / 2f, 0); p.lineTo(r, triH); p.lineTo(l, triH); }      // punta arriba: al principal
            p.close();
            float a = busy ? 0.5f : 1f;
            shadow.setColor(Color.argb(Math.round((110 - 45 * off) * a), 0, 0, 0));
            c.save();
            c.translate(0, base + 6 * dp + up * 0.6f);
            c.scale(1 - 0.06f * off, 1, w / 2f, 0);
            c.drawPath(p, shadow);
            c.restore();
            c.save();
            c.translate(0, base);
            fill.setAlpha(Math.round(255 * a));
            c.drawPath(p, fill);
            c.drawPath(p, gloss);   // brillo de arriba
            c.restore();
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
                        if (!hub.connected()) { hub.toast("El otro teléfono no está conectado"); return true; }
                        busy(true);
                        request(getContext(), hub, pkg);   // se lee aquí y se abre en el otro
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
