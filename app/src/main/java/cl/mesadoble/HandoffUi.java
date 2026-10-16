package cl.mesadoble;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Lo que se ve de "lanzar a la otra pantalla":
 *  - PESTAÑA (principal): lengüeta con una flecha en el borde que da al extendido (abajo en horizontal, a la derecha
 *    en vertical). Sale solo si la app del frente se puede lanzar. Deslizar hacia el borde o tocarla = lanzar.
 *    Flecha llena = se abre en el mismo punto; con contorno = se abre la app desde su inicio.
 *  - BOTONCITO (extendido): con una app lanzada al frente, ChronOS queda como un círculo semitransparente en la
 *    esquina inferior derecha: Devolver arriba · Volver a ChronOS · Cerrar la app.
 *  - Avisos de una sola vez.
 */
final class HandoffUi {

    private HandoffUi() { }

    private static final Handler main = new Handler(Looper.getMainLooper());

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("mesa", Context.MODE_PRIVATE); }

    /** Ventana flotante sobre todo: la de accesibilidad si está (no pide permiso) o la de "mostrar sobre otras apps". */
    private static WindowManager.LayoutParams params(Context c) {
        int type;
        if (MesaAccess.instance != null) type = 2032;   // TYPE_ACCESSIBILITY_OVERLAY
        else if (Settings.canDrawOverlays(c)) type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        else return null;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
        return lp;
    }

    private static WindowManager wm(Context c) {
        Context host = MesaAccess.instance != null ? MesaAccess.instance : c.getApplicationContext();
        return (WindowManager) host.getSystemService(Context.WINDOW_SERVICE);
    }

    private static Context host(Context c) { return MesaAccess.instance != null ? MesaAccess.instance : c.getApplicationContext(); }

    private static boolean landscape(Context c) {
        return c.getResources().getConfiguration().orientation != Configuration.ORIENTATION_PORTRAIT;
    }

    // ======================= PESTAÑA (principal) =======================

    private static final java.util.concurrent.ExecutorService bg = java.util.concurrent.Executors.newSingleThreadExecutor();
    private static TabView tab;

    /** Prueba desde Ajustes: muestra la flecha unos segundos. false si Android no deja dibujarla. */
    static String lastError;

    static boolean test(Context c, Hub hub) {
        hideTab();
        tabPkg = "test";
        lastError = null;
        showTab(c, hub, "test", true);
        if (tab == null) {
            tabPkg = null;
            android.widget.Toast.makeText(c, "No se pudo dibujar la flecha: " + (lastError != null ? lastError : "sin permiso"), android.widget.Toast.LENGTH_LONG).show();
            return true;
        }
        final TabView v = tab;
        main.postDelayed(() -> {
            String how = MesaAccess.instance != null ? "accesibilidad" : "superposición";
            String r = v.isAttachedToWindow() && v.getWidth() > 0
                    ? "Flecha puesta (" + how + "): búscala en el borde " + (v.land ? "de abajo" : "derecho") + ", parpadeando"
                    : "Android quitó la flecha (" + how + ")";
            android.widget.Toast.makeText(c, r, android.widget.Toast.LENGTH_LONG).show();
            v.blink = true; v.invalidate();
        }, 500);
        main.postDelayed(() -> { if ("test".equals(tabPkg)) hideTab(); }, 10000);
        return true;
    }
    private static String tabPkg, hiddenFor;

    /** Cambió la app del frente en el principal. "" o null = la mesa o el inicio: sin pestaña. */
    static void front(Context c, Hub hub, String pkg) {
        main.post(() -> {
            boolean ok = pkg != null && !pkg.isEmpty() && !pkg.startsWith("@") && !pkg.equals(c.getPackageName())
                    && hub.isPrincipal() && hub.connected() && !MainActivity.soloMode(c) && !pkg.equals(hiddenFor);
            if ("test".equals(tabPkg)) return;   // la prueba de Ajustes se deja ver
            if (!ok) { hideTab(); return; }
            if (pkg.equals(tabPkg) && tab != null) return;
            tabPkg = pkg;
            bg.execute(() -> {
                boolean exact = Handoff.exact(c, pkg);
                main.post(() -> showTab(c, hub, pkg, exact));
            });
        });
    }

    static void hideTab() {
        tabPkg = null;
        if (tab != null) { try { tab.wm.removeView(tab); } catch (Exception ignored) { } tab = null; }
        hideHint();
    }

    private static void showTab(Context c, Hub hub, String pkg, boolean exact) {
        if (!pkg.equals(tabPkg)) return;
        boolean land = landscape(c);
        if (tab != null && tab.land == land) { tab.exact = exact; tab.invalidate(); return; }
        if (tab != null) { try { tab.wm.removeView(tab); } catch (Exception ignored) { } tab = null; }
        WindowManager.LayoutParams lp = params(c);
        if (lp == null) { lastError = "falta la accesibilidad o \"mostrar sobre otras apps\""; return; }
        float dp = c.getResources().getDisplayMetrics().density;
        TabView v = new TabView(host(c), land, hub);
        v.exact = exact;
        if (land) { lp.width = (int) (76 * dp); lp.height = (int) (26 * dp); lp.gravity = Gravity.BOTTOM | Gravity.START; lp.x = (int) (c.getResources().getDisplayMetrics().widthPixels * 0.30f); }
        else { lp.width = (int) (26 * dp); lp.height = (int) (76 * dp); lp.gravity = Gravity.END | Gravity.TOP; lp.y = (int) (c.getResources().getDisplayMetrics().heightPixels * 0.38f); }
        v.wm = wm(c);
        try { v.wm.addView(v, lp); tab = v; } catch (Exception e) { lastError = e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : ""); return; }
        if (!prefs(c).getBoolean("tabHint", false)) {
            prefs(c).edit().putBoolean("tabHint", true).apply();
            hint(c, land ? "Desliza la flecha hacia abajo y esta app pasa al extendido" : "Desliza la flecha hacia la derecha y esta app pasa al extendido", land);
        }
    }

    private static final class TabView extends View {
        final boolean land;
        final Hub hub;
        boolean exact, blink;
        WindowManager wm;
        private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG), ar = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float dp;
        private float dx, dy;
        private long downAt;
        private boolean fired;

        TabView(Context c, boolean land, Hub hub) {
            super(c);
            this.land = land;
            this.hub = hub;
            dp = c.getResources().getDisplayMetrics().density;
            bg.setColor(0xB31E5BC6);
            ar.setColor(0xF2FFFFFF);
            ar.setStrokeWidth(1.8f * dp);
            ar.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), r = Math.min(w, h) / 2;
            if (blink) {   // prueba: parpadea en azul para que se note
                boolean on = (android.os.SystemClock.uptimeMillis() / 400) % 2 == 0;
                bg.setColor(on ? 0xFF2E7DFF : 0xFFFF8A00);
                postInvalidateDelayed(400);
            }
            c.drawRoundRect(new RectF(0, 0, w, h), r, r, bg);
            Path p = new Path();
            float cx = w / 2, cy = h / 2, s = Math.min(w, h) * 0.32f;
            if (land) { p.moveTo(cx - s * 1.3f, cy - s * 0.6f); p.lineTo(cx + s * 1.3f, cy - s * 0.6f); p.lineTo(cx, cy + s * 0.9f); }
            else { p.moveTo(cx - s * 0.6f, cy - s * 1.3f); p.lineTo(cx - s * 0.6f, cy + s * 1.3f); p.lineTo(cx + s * 0.9f, cy); }
            p.close();
            ar.setStyle(exact ? Paint.Style.FILL : Paint.Style.STROKE);
            c.drawPath(p, ar);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    dx = e.getRawX(); dy = e.getRawY(); downAt = e.getEventTime(); fired = false;
                    bg.setColor(0xD92E7DFF); invalidate();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float d = land ? e.getRawY() - dy : e.getRawX() - dx;
                    // solo se marca: la acción va al soltar (quitar la ventana con el dedo encima puede dejar trabado el toque)
                    if (!fired && d > 10 * dp) { fired = true; bg.setColor(0xFF2ECC71); invalidate(); }
                    return true;
                case MotionEvent.ACTION_UP:
                    bg.setColor(0xB31E5BC6); invalidate();
                    if (fired) { main.postDelayed(this::go, 60); return true; }
                    if (e.getEventTime() - downAt > 650) {   // dejar apretado: ocultar para esta app
                        hiddenFor = tabPkg;
                        main.postDelayed(HandoffUi::hideTab, 60);
                        android.widget.Toast.makeText(getContext(), "Flecha oculta para esta app", android.widget.Toast.LENGTH_SHORT).show();
                    } else main.postDelayed(this::go, 60);
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    bg.setColor(0xB31E5BC6); invalidate();
                    return true;
            }
            return true;
        }

        private void go() {
            String pkg = tabPkg;
            if (pkg == null) return;
            if ("test".equals(pkg)) { hideTab(); android.widget.Toast.makeText(getContext(), "¡La flecha funciona! Aparece sola en las apps que se pueden lanzar", android.widget.Toast.LENGTH_LONG).show(); return; }
            hiddenFor = null;
            hideTab();
            Handoff.launchOther(getContext(), hub, pkg, false);
        }
    }

    // ======================= aviso de una vez =======================

    private static TextView hintV;
    private static WindowManager hintWm;

    static void hint(Context c, String text, boolean bottom) {
        hideHint();
        WindowManager.LayoutParams lp = params(c);
        if (lp == null) { android.widget.Toast.makeText(c, text, android.widget.Toast.LENGTH_LONG).show(); return; }
        float dp = c.getResources().getDisplayMetrics().density;
        TextView t = new TextView(host(c));
        t.setText(text);
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(14);
        t.setMaxWidth((int) (260 * dp));
        t.setPadding((int) (14 * dp), (int) (10 * dp), (int) (14 * dp), (int) (10 * dp));
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xF02E7DFF);
        g.setCornerRadius(14 * dp);
        t.setBackground(g);
        t.setElevation(6 * dp);
        lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        if (bottom) { lp.gravity = Gravity.BOTTOM | Gravity.START; lp.x = (int) (c.getResources().getDisplayMetrics().widthPixels * 0.30f); lp.y = (int) (28 * dp); }
        else { lp.gravity = Gravity.END | Gravity.TOP; lp.x = (int) (28 * dp); lp.y = (int) (c.getResources().getDisplayMetrics().heightPixels * 0.38f); }
        hintWm = wm(c);
        try { hintWm.addView(t, lp); hintV = t; } catch (Exception ignored) { }
        main.postDelayed(HandoffUi::hideHint, 6500);
    }

    static void hideHint() {
        if (hintV != null) { try { hintWm.removeView(hintV); } catch (Exception ignored) { } hintV = null; }
    }

    /** La primera vez que aparece una ventana flotante en el principal. */
    static void pipHint(Context c) {
        if (prefs(c).getBoolean("pipHint", false)) return;
        prefs(c).edit().putBoolean("pipHint", true).apply();
        main.post(() -> hint(c, "Arrastra la ventana flotante hasta abajo (a la ✕) y pasa al extendido. Para cerrarla sin pasarla, usa la ✕ de la misma ventana.", true));
    }

    // ======================= BOTONCITO (extendido) =======================

    private static LinearLayout bubble;
    private static WindowManager bubbleWm;
    private static WindowManager.LayoutParams bubbleLp;
    private static boolean open;
    private static final Runnable collapse = () -> setOpen(false);

    /** Muestra u oculta el botoncito según haya una app lanzada aquí y ChronOS no esté al frente. */
    static void update(Context c, Hub hub) {
        main.post(() -> {
            boolean show = Handoff.active() && !hub.isPrincipal() && !hub.foreground;
            if (!show) { hideBubble(); return; }
            if (bubble == null) showBubble(c, hub);
        });
    }

    static void hideBubble() {
        main.post(() -> {
            if (bubble != null) { try { bubbleWm.removeView(bubble); } catch (Exception ignored) { } bubble = null; }
            open = false;
        });
    }

    private static TextView btn(Context c, String glyph, String label, float dp, Runnable r) {
        TextView t = new TextView(c);
        t.setText(label == null ? glyph : glyph + "  " + label);
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(label == null ? 18 : 14);
        t.setGravity(Gravity.CENTER_VERTICAL | (label == null ? Gravity.CENTER_HORIZONTAL : Gravity.START));
        t.setPadding((int) (12 * dp), 0, (int) (14 * dp), 0);
        t.setMinHeight((int) (44 * dp));
        if (r != null) t.setOnClickListener(v -> r.run());
        return t;
    }

    private static void showBubble(Context c, Hub hub) {
        WindowManager.LayoutParams lp = params(c);
        if (lp == null) return;
        Context h = host(c);
        float dp = c.getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(h);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xE610202A);
        g.setCornerRadius(22 * dp);
        box.setBackground(g);
        box.setElevation(6 * dp);
        // menú (oculto al principio)
        String label = Handoff.here != null ? Handoff.here.optString("label") : "la app";
        box.addView(btn(h, "⇧", "Devolver arriba", dp, () -> { setOpen(false); Handoff.giveBack(c, hub); }));
        box.addView(btn(h, "⌨", "Volver a ChronOS", dp, () -> { setOpen(false); hub.goDesk(); }));
        box.addView(btn(h, "✕", "Cerrar " + label, dp, () -> { setOpen(false); Handoff.end(c, hub, true); }));
        // el círculo (siempre)
        TextView dot = btn(h, "◆", null, dp, null);
        dot.setMinWidth((int) (44 * dp));
        dot.setTextSize(15);
        box.addView(dot, new LinearLayout.LayoutParams((int) (44 * dp), (int) (44 * dp)));
        for (int i = 0; i < 3; i++) box.getChildAt(i).setVisibility(View.GONE);
        box.setAlpha(0.45f);
        lp.gravity = Gravity.BOTTOM | Gravity.END;
        lp.x = (int) (10 * dp);
        lp.y = (int) (10 * dp);
        // arrastrar para moverlo; tocar para abrir el menú
        final float[] st = new float[4];
        final boolean[] moved = {false};
        dot.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    st[0] = e.getRawX(); st[1] = e.getRawY(); st[2] = bubbleLp.x; st[3] = bubbleLp.y; moved[0] = false;
                    box.setAlpha(1f);
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float ddx = e.getRawX() - st[0], ddy = e.getRawY() - st[1];
                    if (Math.abs(ddx) + Math.abs(ddy) > 12 * dp) moved[0] = true;
                    if (moved[0]) {
                        bubbleLp.x = (int) Math.max(0, st[2] - ddx);
                        bubbleLp.y = (int) Math.max(0, st[3] - ddy);
                        try { bubbleWm.updateViewLayout(box, bubbleLp); } catch (Exception ignored) { }
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    if (!moved[0]) setOpen(!open);
                    else if (!open) box.setAlpha(0.45f);
                    return true;
            }
            return true;
        });
        bubbleWm = wm(c);
        bubbleLp = lp;
        try { bubbleWm.addView(box, lp); bubble = box; } catch (Exception ignored) { }
    }

    private static void setOpen(boolean o) {
        main.removeCallbacks(collapse);
        open = o;
        if (bubble == null) return;
        for (int i = 0; i < 3; i++) bubble.getChildAt(i).setVisibility(o ? View.VISIBLE : View.GONE);
        bubble.setAlpha(o ? 1f : 0.45f);
        if (o) main.postDelayed(collapse, 6000);
    }
}
