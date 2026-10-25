package cl.mesadoble;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chat en vivo de YouTube junto al video flotante (versión completa).
 * MesaAccess avisa cuando cambian las ventanas: si YouTube quedó en pantalla flotante (PiP) sale, animada desde el
 * video, una burbuja de chat con "?". Al tocarla se abre una ventanita gris oscura (se arrastra por arriba y cambia de
 * tamaño por la esquina) con el chat del directo o estreno, con los nombres en colores: dueño, moderador, miembro y resto.
 * Leer el chat: la página pública del chat de YouTube (no necesita cuenta).
 * Escribir: API de YouTube con la cuenta de Google del teléfono ({@link YtLogin}).
 * Ajustes → Configuración y pruebas → "Chat en vivo de YouTube" apaga la burbuja.
 */
final class LiveChat {

    static final String YT = "com.google.android.youtube";
    static final String SCOPE = "oauth2:https://www.googleapis.com/auth/youtube.force-ssl";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    private static final int C_OWNER = 0xFFFFD54F, C_MOD = 0xFF6EA8FF, C_MEMBER = 0xFF3DDC84, C_USER = 0xFFB4B4BC,
            C_TEXT = 0xFFEDEDF0, C_SYS = 0xFF8C8C96, BG = 0xF2242428, FIELD = 0xFF35353C, RED = 0xFFFF3040;

    static boolean enabled(Context c) { return c.getSharedPreferences("mesa", Context.MODE_PRIVATE).getBoolean("ytChat", true); }

    private static LiveChat inst;

    static LiveChat get(MesaAccess s) {
        if (inst == null || inst.svc != s) { if (inst != null) inst.closeAll(); inst = new LiveChat(s); }
        return inst;
    }

    static void release() { if (inst != null) inst.closeAll(); inst = null; }

    /** Cambió el ajuste: la burbuja aparece o se va. */
    static void refresh() { if (inst != null) inst.h.post(inst::scan); }

    final MesaAccess svc;
    private final WindowManager wm;
    private final float dp;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final SharedPreferences prefs;

    private LiveChat(MesaAccess s) {
        svc = s;
        wm = (WindowManager) s.getSystemService(Context.WINDOW_SERVICE);
        dp = s.getResources().getDisplayMetrics().density;
        prefs = s.getSharedPreferences("mesa", Context.MODE_PRIVATE);
    }

    private int dp(float v) { return Math.round(v * dp); }

    private Point screen() { Point p = new Point(); wm.getDefaultDisplay().getRealSize(p); return p; }

    // ======================= ¿YouTube flotando? =======================

    private final Rect pip = new Rect();
    private String dismissedFor;   // video en el que se escondió la burbuja (mantener apretada)
    private final Runnable scan = this::scan;

    void windowsChanged() { h.removeCallbacks(scan); h.postDelayed(scan, 250); }

    private MediaController ytSession() {
        try {
            MediaSessionManager msm = (MediaSessionManager) svc.getSystemService(Context.MEDIA_SESSION_SERVICE);
            for (MediaController mc : msm.getActiveSessions(new android.content.ComponentName(svc, NotifService.class)))
                if (YT.equals(mc.getPackageName())) return mc;
        } catch (Exception ignored) { }
        return null;
    }

    private String meta(String key) {
        MediaController mc = ytSession();
        MediaMetadata md = mc == null ? null : mc.getMetadata();
        String v = md == null ? null : md.getString(key);
        return v == null ? "" : v;
    }

    private Rect findPip() {
        try {
            for (AccessibilityWindowInfo w : svc.getWindows()) {
                if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION || !w.isInPictureInPictureMode()) continue;
                AccessibilityNodeInfo root = w.getRoot();
                boolean yt = root != null && root.getPackageName() != null ? YT.equals(root.getPackageName().toString()) : ytSession() != null;
                if (!yt) continue;
                Rect b = new Rect();
                w.getBoundsInScreen(b);
                return b;
            }
        } catch (Exception ignored) { }
        return null;
    }

    private void scan() {
        h.removeCallbacks(scan);
        Rect r = findPip();
        if (r != null) pip.set(r);
        boolean want = r != null && chat == null && enabled(svc) && !meta(MediaMetadata.METADATA_KEY_TITLE).equals(dismissedFor);
        if (!want) hideBubble();
        else if (bubble == null) showBubble();
        else placeBubble();
        // mientras la burbuja esté a la vista, se sigue mirando (el video flotante se puede arrastrar)
        if (bubble != null) h.postDelayed(scan, 1200);
    }

    // ======================= la burbuja =======================

    private View bubble;
    private WindowManager.LayoutParams bLp;
    private boolean bRight;

    private final class Bubble extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Bubble() { super(svc); }
        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), cx = w / 2, cy = getHeight() / 2, r = w / 2 - dp(3);
            p.setStyle(Paint.Style.FILL);
            p.setShadowLayer(dp(3), 0, dp(1.5f), 0x66000000);
            p.setColor(0xFF2B2B30);
            c.drawCircle(cx, cy, r, p);
            p.clearShadowLayer();
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(2));
            p.setColor(RED);
            c.drawCircle(cx, cy, r - dp(1), p);
            // el globito de chat, con su colita
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.WHITE);
            RectF g = new RectF(cx - r * 0.52f, cy - r * 0.42f, cx + r * 0.52f, cy + r * 0.30f);
            c.drawRoundRect(g, r * 0.2f, r * 0.2f, p);
            android.graphics.Path t = new android.graphics.Path();
            t.moveTo(cx - r * 0.30f, g.bottom - 1);
            t.lineTo(cx - r * 0.38f, cy + r * 0.56f);
            t.lineTo(cx - r * 0.06f, g.bottom - 1);
            t.close();
            c.drawPath(t, p);
            p.setColor(0xFF2B2B30);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTypeface(Typeface.DEFAULT_BOLD);
            p.setTextSize(r * 0.62f);
            c.drawText("?", cx, g.centerY() + r * 0.22f, p);
        }
    }

    private void showBubble() {
        Bubble b = new Bubble();
        int B = dp(50);
        bLp = new WindowManager.LayoutParams(B, B, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
        bLp.gravity = Gravity.TOP | Gravity.LEFT;
        bubble = b;
        place(bLp);
        b.setOnClickListener(v -> openChat());
        b.setOnLongClickListener(v -> {
            dismissedFor = meta(MediaMetadata.METADATA_KEY_TITLE);
            hideBubble();
            Toast.makeText(svc, "Burbuja del chat oculta para este video", Toast.LENGTH_SHORT).show();
            return true;
        });
        try { wm.addView(b, bLp); } catch (Exception e) { bubble = null; return; }
        // sale del borde del video: crece desde el lado que mira al video, con un rebote
        b.setPivotX(bRight ? 0 : B);
        b.setPivotY(B / 2f);
        b.setScaleX(0.1f); b.setScaleY(0.1f); b.setAlpha(0f); b.setRotation(bRight ? -40 : 40);
        b.setTranslationX(bRight ? -dp(14) : dp(14));
        b.animate().scaleX(1).scaleY(1).alpha(1).rotation(0).translationX(0).setDuration(480)
                .setInterpolator(new android.view.animation.OvershootInterpolator(2.2f)).start();
        h.postDelayed(pulse, 3500);
    }

    private final Runnable pulse = new Runnable() {
        @Override public void run() {
            if (bubble == null) return;
            bubble.animate().scaleX(1.14f).scaleY(1.14f).setDuration(160).withEndAction(() -> {
                if (bubble != null) bubble.animate().scaleX(1).scaleY(1).setDuration(260)
                        .setInterpolator(new android.view.animation.OvershootInterpolator(3f)).start();
            }).start();
            h.postDelayed(this, 4000);
        }
    };

    /** Junto al video flotante: a la derecha si cabe, si no a la izquierda, si no arriba/abajo. */
    private void place(WindowManager.LayoutParams lp) {
        Point sc = screen();
        int B = lp.width, gap = dp(8);
        if (pip.right + gap + B <= sc.x) { bRight = true; lp.x = pip.right + gap; lp.y = pip.top + dp(6); }
        else if (pip.left - gap - B >= 0) { bRight = false; lp.x = pip.left - gap - B; lp.y = pip.top + dp(6); }
        else { bRight = true; lp.x = pip.centerX() - B / 2; lp.y = pip.top - gap - B >= 0 ? pip.top - gap - B : pip.bottom + gap; }
        lp.y = Math.max(0, Math.min(sc.y - B, lp.y));
    }

    private void placeBubble() {
        if (bubble == null) return;
        int x = bLp.x, y = bLp.y;
        place(bLp);
        if (x != bLp.x || y != bLp.y) try { wm.updateViewLayout(bubble, bLp); } catch (Exception ignored) { }
    }

    private void hideBubble() {
        h.removeCallbacks(pulse);
        if (bubble == null) return;
        View b = bubble;
        bubble = null;
        b.animate().scaleX(0.1f).scaleY(0.1f).alpha(0).setDuration(180).withEndAction(() -> {
            try { wm.removeView(b); } catch (Exception ignored) { }
        }).start();
    }

    // ======================= la ventana del chat =======================

    private FrameLayout chat;
    private WindowManager.LayoutParams cLp;
    private LinearLayout list;
    private ScrollView scroll;
    private TextView titleV, statusV, dot;
    private EditText input;
    private boolean linkMode;

    private void openChat() {
        hideBubble();
        if (chat != null) return;
        Point sc = screen();
        int w = Math.min(prefs.getInt("ycW", dp(290)), sc.x - dp(16)), hh = Math.min(prefs.getInt("ycH", dp(340)), sc.y - dp(16));
        cLp = new WindowManager.LayoutParams(w, hh, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        cLp.gravity = Gravity.TOP | Gravity.LEFT;
        cLp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
        // al lado del video flotante, donde quepa
        if (pip.right + dp(8) + w <= sc.x) cLp.x = pip.right + dp(8);
        else if (pip.left - dp(8) - w >= 0) cLp.x = pip.left - dp(8) - w;
        else cLp.x = sc.x - w - dp(8);
        cLp.y = Math.max(dp(8), Math.min(sc.y - hh - dp(8), pip.top));

        chat = new FrameLayout(svc) {
            @Override public boolean dispatchTouchEvent(MotionEvent e) {
                if (e.getActionMasked() == MotionEvent.ACTION_OUTSIDE) { focus(false); return false; }
                return super.dispatchTouchEvent(e);
            }
        };
        LinearLayout box = new LinearLayout(svc);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(BG);
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(1), 0x26FFFFFF);
        box.setBackground(bg);
        box.setClipToOutline(true);
        box.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);

        // cabecera: se arrastra para mover la ventana
        LinearLayout head = new LinearLayout(svc);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(12), dp(8), dp(6), dp(6));
        dot = new TextView(svc);
        dot.setText("●");
        dot.setTextSize(10);
        dot.setTextColor(C_SYS);
        head.addView(dot);
        LinearLayout tt = new LinearLayout(svc);
        tt.setOrientation(LinearLayout.VERTICAL);
        tt.setPadding(dp(6), 0, dp(4), 0);
        titleV = text("Chat en vivo", 13, Color.WHITE);
        titleV.setTypeface(Typeface.DEFAULT_BOLD);
        titleV.setSingleLine(true);
        titleV.setEllipsize(TextUtils.TruncateAt.END);
        statusV = text("", 11, C_SYS);
        statusV.setSingleLine(true);
        statusV.setEllipsize(TextUtils.TruncateAt.END);
        tt.addView(titleV);
        tt.addView(statusV);
        head.addView(tt, new LinearLayout.LayoutParams(0, -2, 1));
        TextView link = headBtn("🔗");
        link.setOnClickListener(v -> {
            linkMode = !linkMode;
            input.setHint(linkMode ? "Pega el enlace del directo y toca ➤" : "Escribe en el chat…");
            if (linkMode) { focus(true); sys("Pega el enlace del directo (youtube.com/watch?v=… o youtu.be/…)."); }
        });
        head.addView(link);
        TextView x = headBtn("✕");
        x.setOnClickListener(v -> closeChat());
        head.addView(x);
        head.setOnTouchListener(new View.OnTouchListener() {
            float rx, ry; int lx, ly;
            @Override public boolean onTouch(View v, MotionEvent e) {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) { rx = e.getRawX(); ry = e.getRawY(); lx = cLp.x; ly = cLp.y; return true; }
                if (e.getActionMasked() == MotionEvent.ACTION_MOVE) {
                    Point s = screen();
                    cLp.x = Math.max(-cLp.width / 2, Math.min(s.x - cLp.width / 2, lx + Math.round(e.getRawX() - rx)));
                    cLp.y = Math.max(0, Math.min(s.y - dp(40), ly + Math.round(e.getRawY() - ry)));
                    update();
                }
                return true;
            }
        });
        box.addView(head);
        View sep = new View(svc);
        sep.setBackgroundColor(0x1AFFFFFF);
        box.addView(sep, new LinearLayout.LayoutParams(-1, 1));

        scroll = new ScrollView(svc);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFadingEdgeLength(dp(16));
        scroll.setVerticalFadingEdgeEnabled(true);
        list = new LinearLayout(svc);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(12), dp(6), dp(12), dp(6));
        scroll.addView(list);
        box.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout foot = new LinearLayout(svc);
        foot.setGravity(Gravity.CENTER_VERTICAL);
        foot.setPadding(dp(8), dp(4), dp(22), dp(8));
        input = new EditText(svc);
        input.setTextSize(13);
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(0xFF7C7C86);
        input.setHint("Escribe en el chat…");
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setPadding(dp(12), dp(7), dp(12), dp(7));
        GradientDrawable fb = new GradientDrawable();
        fb.setColor(FIELD);
        fb.setCornerRadius(dp(16));
        input.setBackground(fb);
        input.setOnTouchListener((v, e) -> { if (e.getActionMasked() == MotionEvent.ACTION_DOWN) focus(true); return false; });
        input.setOnEditorActionListener((v, a, e) -> { submit(); return true; });
        foot.addView(input, new LinearLayout.LayoutParams(0, -2, 1));
        TextView sendB = new TextView(svc);
        sendB.setText("➤");
        sendB.setTextSize(15);
        sendB.setTextColor(Color.WHITE);
        sendB.setGravity(Gravity.CENTER);
        GradientDrawable sb = new GradientDrawable();
        sb.setShape(GradientDrawable.OVAL);
        sb.setColor(RED);
        sendB.setBackground(sb);
        sendB.setOnClickListener(v -> submit());
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(dp(32), dp(32));
        sl.setMarginStart(dp(6));
        foot.addView(sendB, sl);
        box.addView(foot);
        chat.addView(box, new FrameLayout.LayoutParams(-1, -1));

        // esquina para cambiar el tamaño
        View grip = new View(svc) {
            final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(Canvas c) {
                p.setColor(0x80FFFFFF);
                p.setStrokeWidth(dp(1.5f));
                p.setStrokeCap(Paint.Cap.ROUND);
                float w = getWidth(), g = getHeight();
                for (int i = 1; i <= 3; i++) c.drawLine(w - dp(5), g - dp(5) - i * dp(4), w - dp(5) - i * dp(4), g - dp(5), p);
            }
        };
        grip.setOnTouchListener(new View.OnTouchListener() {
            float rx, ry; int w0, h0;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN: rx = e.getRawX(); ry = e.getRawY(); w0 = cLp.width; h0 = cLp.height; return true;
                    case MotionEvent.ACTION_MOVE: {
                        Point s = screen();
                        cLp.width = Math.max(dp(200), Math.min(s.x, w0 + Math.round(e.getRawX() - rx)));
                        cLp.height = Math.max(dp(170), Math.min(s.y, h0 + Math.round(e.getRawY() - ry)));
                        update();
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                        prefs.edit().putInt("ycW", cLp.width).putInt("ycH", cLp.height).apply();
                        return true;
                }
                return true;
            }
        });
        chat.addView(grip, new FrameLayout.LayoutParams(dp(26), dp(26), Gravity.BOTTOM | Gravity.END));

        try { wm.addView(chat, cLp); } catch (Exception e) { chat = null; return; }
        chat.setAlpha(0f);
        chat.setScaleX(0.85f); chat.setScaleY(0.85f);
        chat.animate().alpha(1).scaleX(1).scaleY(1).setDuration(220).start();

        // leyenda de colores
        SpannableStringBuilder lg = new SpannableStringBuilder();
        span(lg, "★ dueño  ", C_OWNER, true);
        span(lg, "🛡 moderador  ", C_MOD, true);
        span(lg, "◆ miembro  ", C_MEMBER, true);
        span(lg, "usuario", C_USER, true);
        TextView l = text("", 11, C_SYS);
        l.setText(lg);
        l.setPadding(0, dp(2), 0, dp(6));
        list.addView(l);
        String t = meta(MediaMetadata.METADATA_KEY_TITLE);
        titleV.setText(t.isEmpty() ? "Chat en vivo" : t);
        startChat(null);
    }

    private TextView text(String t, float sp, int color) {
        TextView v = new TextView(svc);
        v.setText(t);
        v.setTextSize(sp);
        v.setTextColor(color);
        return v;
    }

    private TextView headBtn(String t) {
        TextView b = text(t, 14, 0xFFDADAE0);
        b.setGravity(Gravity.CENTER);
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(34), dp(34)));
        return b;
    }

    private void update() { if (chat != null) try { wm.updateViewLayout(chat, cLp); } catch (Exception ignored) { } }

    /** Para escribir la ventana toma el foco (y se abre el teclado del extendido); tocar afuera lo devuelve. */
    private void focus(boolean on) {
        if (chat == null) return;
        boolean was = (cLp.flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) == 0;
        if (on == was) return;
        if (on) cLp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        else cLp.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        update();
        InputMethodManager imm = (InputMethodManager) svc.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (on) {
            input.requestFocus();
            h.postDelayed(() -> { if (input != null && imm != null) imm.showSoftInput(input, 0); }, 120);
            Hub hub = Hub.get(svc);
            if (hub.connected()) hub.send("openKb", "pkg", svc.getPackageName());
        } else if (imm != null) imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
    }

    private void closeChat() {
        session++;
        if (chat != null) {
            focus(false);
            View c = chat;
            chat = null;
            c.animate().alpha(0).scaleX(0.85f).scaleY(0.85f).setDuration(160).withEndAction(() -> {
                try { wm.removeView(c); } catch (Exception ignored) { }
            }).start();
        }
        videoId = null; liveChatId = null; seen.clear();
        windowsChanged();
    }

    private void closeAll() {
        h.removeCallbacksAndMessages(null);
        session++;
        if (bubble != null) try { wm.removeView(bubble); } catch (Exception ignored) { }
        if (chat != null) try { wm.removeView(chat); } catch (Exception ignored) { }
        bubble = null; chat = null;
    }

    // ---- mensajes en la lista

    private static final int R_USER = 0, R_MEMBER = 1, R_MOD = 2, R_OWNER = 3;

    private static void span(SpannableStringBuilder b, String t, int color, boolean bold) {
        int s = b.length();
        b.append(t);
        b.setSpan(new ForegroundColorSpan(color), s, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (bold) b.setSpan(new StyleSpan(Typeface.BOLD), s, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private void add(CharSequence cs) {
        if (list == null || chat == null) return;
        boolean atEnd = scroll.getScrollY() + scroll.getHeight() >= list.getHeight() - dp(48);
        TextView v = text("", 13, C_TEXT);
        v.setText(cs);
        v.setPadding(0, dp(2), 0, dp(2));
        v.setLineSpacing(0, 1.08f);
        list.addView(v);
        while (list.getChildCount() > 250) list.removeViewAt(1);   // la leyenda se queda
        if (atEnd) scroll.post(() -> scroll.scrollTo(0, list.getHeight()));
    }

    private void msg(String name, int role, String text, String paid) {
        SpannableStringBuilder b = new SpannableStringBuilder();
        int c = role == R_OWNER ? C_OWNER : role == R_MOD ? C_MOD : role == R_MEMBER ? C_MEMBER : C_USER;
        String mark = role == R_OWNER ? "★ " : role == R_MOD ? "🛡 " : role == R_MEMBER ? "◆ " : "";
        span(b, mark + name, c, true);
        if (paid != null && !paid.isEmpty()) span(b, "  " + paid, 0xFFFFA726, true);
        if (!text.isEmpty()) span(b, "  " + text, C_TEXT, false);
        add(b);
    }

    private void sys(String t) {
        SpannableStringBuilder b = new SpannableStringBuilder();
        span(b, t, C_SYS, false);
        b.setSpan(new StyleSpan(Typeface.ITALIC), 0, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        add(b);
    }

    private void status(int my, String s, boolean live) {
        h.post(() -> {
            if (my != session || statusV == null) return;
            statusV.setText(s);
            dot.setTextColor(live ? RED : C_SYS);
        });
    }

    // ======================= leer el chat (página pública de YouTube) =======================

    private volatile int session;
    private volatile String videoId, liveChatId;
    private final LinkedHashSet<String> seen = new LinkedHashSet<>();

    private void startChat(String vid) {
        final int my = ++session;
        final String title = meta(MediaMetadata.METADATA_KEY_TITLE), ch = meta(MediaMetadata.METADATA_KEY_ARTIST),
                mid = meta(MediaMetadata.METADATA_KEY_MEDIA_ID);
        new Thread(() -> run(my, vid, title, ch, mid), "ytchat").start();
    }

    private boolean alive(int my) { return my == session && chat != null; }

    private void run(int my, String vid, String title, String ch, String mid) {
        try {
            if (vid == null && mid.matches("[A-Za-z0-9_-]{11}")) vid = mid;
            if (vid == null) {
                status(my, "Buscando el directo…", false);
                if (!title.isEmpty()) vid = findVideo(title, ch);
            }
            if (!alive(my)) return;
            if (vid == null) {
                status(my, "No encontré el directo", false);
                post(my, () -> sys("No encontré el directo de este video. Toca 🔗 y pega su enlace."));
                return;
            }
            videoId = vid;
            liveChatId = null;
            String html = get("https://www.youtube.com/live_chat?is_popout=1&v=" + vid);
            String key = re(html, "\"INNERTUBE_API_KEY\":\"([^\"]+)\""), ver = re(html, "\"INNERTUBE_CONTEXT_CLIENT_VERSION\":\"([^\"]+)\"");
            JSONObject data = initialData(html);
            JSONObject lcr = data == null ? null : path(data, "contents", "liveChatRenderer");
            if (lcr == null) {
                status(my, "Sin chat en vivo", false);
                post(my, () -> sys("Este video no tiene chat en vivo (no es un directo o estreno, o ya terminó)."));
                return;
            }
            // "Chat en vivo" (todos los mensajes) en vez de "Chat destacado"
            String cont = null;
            JSONObject menu = path(lcr, "header", "liveChatHeaderRenderer", "viewSelector", "sortFilterSubMenuRenderer");
            JSONArray items = menu == null ? null : menu.optJSONArray("subMenuItems");
            if (items != null && items.length() > 0) {
                JSONObject last = items.optJSONObject(items.length() - 1);
                JSONObject rc = last == null ? null : path(last, "continuation", "reloadContinuationData");
                if (rc != null) cont = rc.optString("continuation", null);
            }
            if (cont == null) { actions(my, lcr.optJSONArray("actions"), 0); cont = nextCont(lcr.optJSONArray("continuations"), null); }
            status(my, "En vivo", true);
            int fails = 0;
            while (alive(my) && cont != null) {
                long wait;
                try {
                    JSONObject body = new JSONObject().put("context", ctx(ver)).put("continuation", cont);
                    JSONObject r = new JSONObject(post("https://www.youtube.com/youtubei/v1/live_chat/get_live_chat?prettyPrint=false"
                            + (key != null ? "&key=" + key : ""), body.toString(), null));
                    JSONObject lc = path(r, "continuationContents", "liveChatContinuation");
                    if (lc == null) { status(my, "El chat terminó", false); break; }
                    long[] to = {2500};
                    cont = nextCont(lc.optJSONArray("continuations"), to);
                    wait = Math.max(1000, Math.min(6000, to[0]));
                    actions(my, lc.optJSONArray("actions"), wait);
                    if (fails > 0) status(my, "En vivo", true);
                    fails = 0;
                } catch (Exception e) {
                    if (++fails > 6) throw e;
                    status(my, "Reconectando…", false);
                    wait = 3000;
                }
                Thread.sleep(wait);
            }
        } catch (Exception e) {
            status(my, "Sin conexión con el chat", false);
            post(my, () -> sys("No se pudo leer el chat (" + e.getClass().getSimpleName() + "). Ciérralo y ábrelo de nuevo."));
        }
    }

    private void post(int my, Runnable r) { h.post(() -> { if (alive(my)) r.run(); }); }

    private static JSONObject ctx(String ver) throws Exception {
        return new JSONObject().put("client", new JSONObject().put("clientName", "WEB")
                .put("clientVersion", ver != null ? ver : "2.20240401.00.00").put("hl", "es").put("gl", "CL"));
    }

    private static String nextCont(JSONArray cs, long[] timeout) {
        if (cs == null || cs.length() == 0) return null;
        JSONObject c0 = cs.optJSONObject(0);
        if (c0 == null) return null;
        for (Iterator<String> it = c0.keys(); it.hasNext(); ) {
            JSONObject d = c0.optJSONObject(it.next());
            if (d == null || !d.has("continuation")) continue;
            if (timeout != null) timeout[0] = d.optLong("timeoutMs", 2500);
            return d.optString("continuation");
        }
        return null;
    }

    /** Agrega los mensajes nuevos, repartidos en el tiempo de espera (para que corran parejo, como en YouTube). */
    private void actions(int my, JSONArray acts, long spread) {
        if (acts == null) return;
        List<Runnable> out = new ArrayList<>();
        for (int i = 0; i < acts.length(); i++) {
            JSONObject a = acts.optJSONObject(i);
            JSONObject item = a == null ? null : path(a, "addChatItemAction", "item");
            if (item == null) continue;
            JSONObject r = item.optJSONObject("liveChatTextMessageRenderer");
            String paid = null, extra = null;
            if (r == null && (r = item.optJSONObject("liveChatPaidMessageRenderer")) != null) paid = simple(r.optJSONObject("purchaseAmountText"));
            if (r == null && (r = item.optJSONObject("liveChatMembershipItemRenderer")) != null) extra = runs(r.optJSONObject("headerSubtext"));
            if (r == null) continue;
            String id = r.optString("id");
            synchronized (seen) {
                if (!id.isEmpty() && !seen.add(id)) continue;
                while (seen.size() > 2000) { Iterator<String> it = seen.iterator(); it.next(); it.remove(); }
            }
            String name = simple(r.optJSONObject("authorName"));
            int role = R_USER;
            JSONArray badges = r.optJSONArray("authorBadges");
            if (badges != null) for (int k = 0; k < badges.length(); k++) {
                JSONObject br = badges.optJSONObject(k) == null ? null : badges.optJSONObject(k).optJSONObject("liveChatAuthorBadgeRenderer");
                if (br == null) continue;
                String icon = br.optJSONObject("icon") == null ? "" : br.optJSONObject("icon").optString("iconType");
                if ("OWNER".equals(icon)) role = Math.max(role, R_OWNER);
                else if ("MODERATOR".equals(icon)) role = Math.max(role, R_MOD);
                else if (br.has("customThumbnail")) role = Math.max(role, R_MEMBER);
            }
            if (extra != null && role < R_MEMBER) role = R_MEMBER;
            String text = runs(r.optJSONObject("message"));
            if (extra != null && text.isEmpty()) text = extra;
            final String n = name, t = text, p = paid;
            final int ro = role;
            out.add(() -> msg(n, ro, t, p));
        }
        for (int i = 0; i < out.size(); i++) {
            Runnable rr = out.get(i);
            long d = out.size() <= 1 ? 0 : spread * i / out.size();
            h.postDelayed(() -> { if (alive(my)) rr.run(); }, d);
        }
    }

    private static String simple(JSONObject o) {
        if (o == null) return "";
        if (o.has("simpleText")) return o.optString("simpleText");
        return runs(o);
    }

    private static String runs(JSONObject o) {
        JSONArray rs = o == null ? null : o.optJSONArray("runs");
        if (rs == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < rs.length(); i++) {
            JSONObject r = rs.optJSONObject(i);
            if (r == null) continue;
            if (r.has("text")) b.append(r.optString("text"));
            else if (r.has("emoji")) {
                JSONObject e = r.optJSONObject("emoji");
                if (!e.optBoolean("isCustomEmoji")) b.append(e.optString("emojiId"));
                else {
                    JSONArray sc = e.optJSONArray("shortcuts");
                    b.append(sc != null && sc.length() > 0 ? sc.optString(0) : "");
                }
            }
        }
        return b.toString();
    }

    private static JSONObject path(JSONObject o, String... ks) {
        for (String k : ks) { if (o == null) return null; o = o.optJSONObject(k); }
        return o;
    }

    private static JSONObject initialData(String html) {
        int i = html.indexOf("ytInitialData");
        if (i < 0) return null;
        i = html.indexOf('{', i);
        if (i < 0) return null;
        try { Object v = new JSONTokener(html.substring(i)).nextValue(); return v instanceof JSONObject ? (JSONObject) v : null; }
        catch (Exception e) { return null; }
    }

    private static String re(String s, String p) {
        Matcher m = Pattern.compile(p).matcher(s);
        return m.find() ? m.group(1) : null;
    }

    /** Busca en YouTube el video que está sonando (primero entre los directos), por título y canal. */
    private String findVideo(String title, String ch) throws Exception {
        String nt = norm(title), nc = norm(ch);
        String best = null;
        int bestScore = 0;
        for (String params : new String[]{"EgJAAQ==", null}) {
            JSONObject body = new JSONObject().put("context", ctx(null)).put("query", ch.isEmpty() ? title : title + " " + ch);
            if (params != null) body.put("params", params);
            JSONObject r = new JSONObject(post("https://www.youtube.com/youtubei/v1/search?prettyPrint=false", body.toString(), null));
            List<JSONObject> vids = new ArrayList<>();
            collect(r, "videoRenderer", vids, 0);
            for (JSONObject v : vids) {
                String t = norm(runs(v.optJSONObject("title"))), o = norm(runs(v.optJSONObject("ownerText")));
                int sc = t.equals(nt) ? 6 : (!nt.isEmpty() && (t.contains(nt) || nt.contains(t)) && t.length() > 3) ? 3 : 0;
                if (sc == 0) continue;
                if (!nc.isEmpty() && o.equals(nc)) sc += 3;
                if (v.toString().contains("LIVE")) sc += 1;
                if (sc > bestScore) { bestScore = sc; best = v.optString("videoId", null); }
            }
            if (bestScore >= 6) break;
        }
        return best;
    }

    private static void collect(Object o, String key, List<JSONObject> out, int depth) {
        if (depth > 40 || out.size() > 40) return;
        if (o instanceof JSONObject) {
            JSONObject j = (JSONObject) o;
            for (Iterator<String> it = j.keys(); it.hasNext(); ) {
                String k = it.next();
                Object v = j.opt(k);
                if (k.equals(key) && v instanceof JSONObject) out.add((JSONObject) v);
                else collect(v, key, out, depth + 1);
            }
        } else if (o instanceof JSONArray) {
            JSONArray a = (JSONArray) o;
            for (int i = 0; i < a.length(); i++) collect(a.opt(i), key, out, depth + 1);
        }
    }

    private static String norm(String s) {
        return s == null ? "" : s.toLowerCase(java.util.Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    private static HttpURLConnection open(String url, String auth) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept-Language", "es-CL,es;q=0.9");
        if (auth != null) c.setRequestProperty("Authorization", "Bearer " + auth);
        else c.setRequestProperty("Cookie", "SOCS=CAI; CONSENT=YES+1");
        return c;
    }

    private static String read(HttpURLConnection c) throws Exception {
        InputStream in = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream();
        if (in == null) return "";
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return new String(bo.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String get(String url) throws Exception { return read(open(url, null)); }

    private static String post(String url, String json, String auth) throws Exception {
        HttpURLConnection c = open(url, auth);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        try (OutputStream o = c.getOutputStream()) { o.write(json.getBytes(StandardCharsets.UTF_8)); }
        return read(c);
    }

    // ======================= escribir (API de YouTube con la cuenta del teléfono) =======================

    private String pending;

    private void submit() {
        String t = input.getText().toString().trim();
        if (t.isEmpty()) return;
        if (linkMode || t.matches("(?i).*(youtube\\.com/|youtu\\.be/).*")) {
            String id = re(t, "(?:v=|youtu\\.be/|/live/|/shorts/)([A-Za-z0-9_-]{11})");
            if (id == null) { sys("Ese enlace no parece de un video de YouTube."); return; }
            input.setText("");
            linkMode = false;
            input.setHint("Escribe en el chat…");
            sys("Conectando con ese directo…");
            synchronized (seen) { seen.clear(); }
            startChat(id);
            return;
        }
        if (videoId == null) { sys("Todavía no hay un chat conectado."); return; }
        if (t.length() > 200) { sys("El mensaje es muy largo (máximo 200 letras)."); return; }
        input.setText("");
        String acc = prefs.getString("ytAcc", "");
        if (acc.isEmpty()) {
            pending = t;
            sys("Elige tu cuenta de Google para escribir en el chat…");
            YtLogin.start(svc);
            return;
        }
        sendAsync(acc, t, true);
    }

    /** YtLogin terminó: sale lo que quedó esperando. */
    static void loginDone(String account, String error) {
        LiveChat l = inst;
        if (l == null) return;
        l.h.post(() -> {
            if (error != null) { l.sys(error); return; }
            if (l.pending != null) { String t = l.pending; l.pending = null; l.sendAsync(account, t, false); }
            else l.sys("Listo: ya puedes escribir en el chat.");
        });
    }

    private void sendAsync(String acc, String text, boolean canLogin) {
        final int my = session;
        final String vid = videoId;
        new Thread(() -> {
            try {
                AccountManager am = AccountManager.get(svc);
                Account a = new Account(acc, "com.google");
                for (int attempt = 0; attempt < 2; attempt++) {
                    String tok = am.blockingGetAuthToken(a, SCOPE, false);
                    if (tok == null) {   // hace falta que la persona dé permiso: se abre la pantalla de la cuenta
                        if (canLogin) { pending = text; YtLogin.start(svc); }
                        else post(my, () -> sys("Falta dar permiso a la cuenta para escribir."));
                        return;
                    }
                    if (liveChatId == null) {
                        HttpURLConnection c = open("https://www.googleapis.com/youtube/v3/videos?part=liveStreamingDetails&id=" + URLEncoder.encode(vid, "UTF-8"), tok);
                        int code = c.getResponseCode();
                        String body = read(c);
                        if (code == 401) { am.invalidateAuthToken("com.google", tok); continue; }
                        if (code >= 300) { post(my, () -> sys(apiError(body))); return; }
                        JSONArray it = new JSONObject(body).optJSONArray("items");
                        JSONObject d = it == null || it.length() == 0 ? null : path(it.optJSONObject(0), "liveStreamingDetails");
                        liveChatId = d == null ? null : d.optString("activeLiveChatId", null);
                        if (liveChatId == null) { post(my, () -> sys("Este directo no acepta mensajes ahora.")); return; }
                    }
                    JSONObject m = new JSONObject().put("snippet", new JSONObject().put("liveChatId", liveChatId)
                            .put("type", "textMessageEvent").put("textMessageDetails", new JSONObject().put("messageText", text)));
                    HttpURLConnection c = open("https://www.googleapis.com/youtube/v3/liveChatMessages?part=snippet", tok);
                    c.setRequestMethod("POST");
                    c.setDoOutput(true);
                    c.setRequestProperty("Content-Type", "application/json");
                    try (OutputStream o = c.getOutputStream()) { o.write(m.toString().getBytes(StandardCharsets.UTF_8)); }
                    int code = c.getResponseCode();
                    String body = read(c);
                    if (code == 401) { am.invalidateAuthToken("com.google", tok); continue; }
                    if (code >= 300) post(my, () -> sys(apiError(body)));
                    return;   // el mensaje aparece solo cuando YouTube lo publica en el chat
                }
            } catch (Exception e) {
                String why = String.valueOf(e.getMessage());
                post(my, () -> sys(friendly(why)));
            }
        }, "ytsend").start();
    }

    private static String apiError(String body) {
        try {
            JSONObject e = new JSONObject(body).optJSONObject("error");
            String msg = e == null ? body : e.optString("message");
            return friendly(msg);
        } catch (Exception x) { return "YouTube no aceptó el mensaje."; }
    }

    static String friendly(String why) {
        String w = why == null ? "" : why;
        if (w.contains("UNREGISTERED") || w.contains("not configured") || w.contains("accessNotConfigured") || w.contains("has not been used"))
            return "Falta registrar ChronOS en Google Cloud para poder escribir (ver instrucciones de la versión).";
        if (w.contains("quota")) return "Se acabó la cuota diaria de mensajes de la API de YouTube; mañana vuelve.";
        if (w.toLowerCase().contains("banned") || w.contains("forbidden")) return "YouTube no te deja escribir en este chat (solo miembros, modo lento o bloqueo).";
        return "No se pudo enviar: " + w;
    }
}
