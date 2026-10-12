package cl.mesadoble;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Diálogos de Mesa Doble: tarjetas redondeadas, opciones con íconos de línea y botones tipo píldora.
 * Siguen la orientación de la pantalla de la mesa.
 */
public class Ui {

    // ---- íconos de línea
    public static final int LAYOUT_SIDE_H = 1, LAYOUT_SIDE_V = 2, LAYOUT_STACK = 3, LOCK = 4, UNLOCK = 5,
            KEYBOARD = 6, KB_TOUCHPAD = 7, TOUCHPAD = 8, GAMEPAD = 9, MIC = 10, WIFI = 11, GEAR = 12,
            AUTO = 13, CHECK = 14, CROSS = 15, PIN = 16, OPEN = 17, STORE = 18, RECEIVE = 19, NOTE = 20,
            PALETTE = 21, HOME = 22, SEND = 23, PAGES = 24, PLUS = 25, GRID = 26, TRASH = 27, IMAGE = 28, CLOCK = 29, FOLDER = 30, WIDGETS = 31, BATTERY = 32, ALIGN = 33;

    public static boolean night(Context c) {
        return (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    static int surface(boolean n) { return n ? 0xFF1E2934 : 0xFFF7F9FB; }
    static int row(boolean n) {
        if (CardTheme.isGlass()) return n ? 0x26FFFFFF : 0x80FFFFFF;   // filas translúcidas sobre el cristal
        return n ? 0xFF253445 : 0xFFFFFFFF;
    }
    static int fg(boolean n) { return n ? 0xFFE6EDF3 : 0xFF1D2733; }
    static int muted(boolean n) { return n ? 0xFF93A4B5 : 0xFF5B6B7A; }
    static int accent(boolean n) { return n ? 0xFF5CC3CF : 0xFF1F7F8C; }

    /** Constructor de diálogos. */
    public static class Sheet {
        private final Activity a;
        private final boolean n;
        private final float dp;
        private final Dialog d;
        private final LinearLayout card, options, buttons;
        private final TextView title, message;
        private boolean hasOptions, hasButtons;

        public Sheet(Activity a) {
            this.a = a;
            n = CardTheme.sheetNight(night(a));   // el texto se adapta al color elegido para los menús
            dp = a.getResources().getDisplayMetrics().density;
            d = new Dialog(a, android.R.style.Theme_Material_Dialog_NoActionBar);
            d.requestWindowFeature(Window.FEATURE_NO_TITLE);

            card = new LinearLayout(a);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(px(22), px(20), px(22), px(16));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(CardTheme.sheetFill(night(a)));
            if (CardTheme.isGlass()) bg.setStroke(px(1), CardTheme.stroke());
            bg.setCornerRadius(px(26));
            if (CardTheme.isIntense()) {
                // cristal intenso: velo esmerilado con brillo arriba y sombra abajo (lo de atrás se ve borroso)
                GradientDrawable sh = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x59FFFFFF, 0x0AFFFFFF, 0x2E000000});
                sh.setCornerRadius(px(26));
                GradientDrawable rim = new GradientDrawable();
                rim.setColor(0x00000000);
                rim.setCornerRadius(px(26));
                rim.setStroke(px(1.5f), 0xD9FFFFFF);
                card.setBackground(new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{bg, sh, rim}));
            } else if (CardTheme.isAero()) {
                // Aero: el vidrio de color con brillo, sobre un fondo claro para que se lea
                android.graphics.drawable.LayerDrawable ld = new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{
                        bg, new Aero.Bg(CardTheme.aeroTint(), px(26), dp)});
                card.setBackground(ld);
            } else card.setBackground(bg);
            card.setElevation(px(CardTheme.isIntense() ? 28 : 12));

            title = new TextView(a);
            title.setTextSize(20);
            title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            title.setTextColor(fg(n));
            title.setVisibility(View.GONE);
            if (CardTheme.isAero()) title.setShadowLayer(px(5), 0, 0, 0xCCFFFFFF);
            card.addView(title);

            message = new TextView(a);
            message.setTextSize(15);
            message.setLineSpacing(0, 1.15f);
            message.setTextColor(muted(n));
            message.setPadding(0, px(8), 0, 0);
            message.setVisibility(View.GONE);
            card.addView(message);

            options = new LinearLayout(a);
            options.setOrientation(LinearLayout.VERTICAL);
            card.addView(options);

            buttons = new LinearLayout(a);
            buttons.setOrientation(LinearLayout.HORIZONTAL);
            buttons.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            buttons.setPadding(0, px(14), 0, 0);
            card.addView(buttons);
        }

        int px(float v) { return Math.round(v * dp); }

        public Sheet title(String t) { title.setText(t); title.setVisibility(View.VISIBLE); return this; }
        public Sheet message(String m) { message.setText(m); message.setVisibility(View.VISIBLE); return this; }

        /** Contenido propio (por ejemplo, un campo de texto). */
        public Sheet view(View v) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = px(12);
            options.addView(v, lp);
            return this;
        }

        /** Opción con ícono, título y descripción opcional. */
        public Sheet option(int icon, int highlight, String label, String sub, boolean selected, Runnable r) {
            LinearLayout rowV = new LinearLayout(a);
            rowV.setOrientation(LinearLayout.HORIZONTAL);
            rowV.setGravity(Gravity.CENTER_VERTICAL);
            rowV.setPadding(px(12), px(10), px(14), px(10));
            GradientDrawable rb = new GradientDrawable();
            rb.setColor(row(n));
            rb.setCornerRadius(px(18));
            if (selected) rb.setStroke(px(2), accent(n));
            rowV.setBackground(rb);
            rowV.setClickable(true);
            rowV.setFocusable(true);
            rowV.setForeground(a.getDrawable(android.R.drawable.list_selector_background));
            if (icon > 0) {
                LineIcon li = new LineIcon(a, icon, highlight, selected);
                LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(px(52), px(52));
                ip.setMarginEnd(px(12));
                rowV.addView(li, ip);
            }
            LinearLayout texts = new LinearLayout(a);
            texts.setOrientation(LinearLayout.VERTICAL);
            TextView l = new TextView(a);
            l.setText(label);
            l.setTextSize(16);
            l.setTextColor(fg(n));
            l.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            texts.addView(l);
            if (sub != null && !sub.isEmpty()) {
                TextView s = new TextView(a);
                s.setText(sub);
                s.setTextSize(13);
                s.setTextColor(muted(n));
                texts.addView(s);
            }
            rowV.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
            rowV.setOnClickListener(v -> { d.dismiss(); if (r != null) r.run(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = px(hasOptions ? 8 : 14);
            options.addView(rowV, lp);
            hasOptions = true;
            return this;
        }

        public Sheet option(int icon, String label, String sub, Runnable r) { return option(icon, -1, label, sub, false, r); }

        public Sheet button(String label, boolean primary, Runnable r) {
            TextView b = new TextView(a);
            b.setText(label);
            b.setTextSize(15);
            b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            b.setGravity(Gravity.CENTER);
            b.setPadding(px(18), 0, px(18), 0);
            b.setMinHeight(px(44));
            b.setMaxLines(1);
            b.setEllipsize(TextUtils.TruncateAt.END);
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(px(22));
            if (primary) { g.setColor(accent(n)); b.setTextColor(n ? 0xFF102029 : 0xFFFFFFFF); }
            else { g.setColor(0x00000000); b.setTextColor(accent(n)); }
            b.setBackground(g);
            b.setClickable(true);
            b.setOnClickListener(v -> { d.dismiss(); if (r != null) r.run(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, px(44));
            lp.setMarginStart(px(8));
            buttons.addView(b, lp);
            hasButtons = true;
            return this;
        }

        public Sheet cancelable(boolean c) { d.setCancelable(c); d.setCanceledOnTouchOutside(c); return this; }

        public Dialog show() {
            if (!hasButtons) buttons.setVisibility(View.GONE);
            ScrollView sv = new ScrollView(a);
            sv.setFillViewport(false);
            sv.addView(card, new FrameLayout.LayoutParams(-1, -2));
            FrameLayout wrap = new FrameLayout(a);
            wrap.setPadding(px(16), px(16), px(16), px(16));
            int maxW = Math.min(a.getResources().getDisplayMetrics().widthPixels - px(32), px(520));
            wrap.addView(sv, new FrameLayout.LayoutParams(maxW, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
            d.setContentView(wrap);
            Window w = d.getWindow();
            if (w != null) {
                w.setBackgroundDrawable(new ColorDrawable(0x00000000));
                w.setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                w.setDimAmount(CardTheme.isGlass() ? 0.2f : 0.45f);
                if (CardTheme.isGlass() && android.os.Build.VERSION.SDK_INT >= 31) {
                    // cristal: lo de atrás se ve borroso (Android 12 o superior, si el teléfono lo permite)
                    try {
                        w.addFlags(0x00000004 /* FLAG_BLUR_BEHIND */);
                        WindowManager.LayoutParams lp = w.getAttributes();
                        WindowManager.LayoutParams.class.getMethod("setBlurBehindRadius", int.class).invoke(lp, px(CardTheme.isIntense() ? 70 : 28));
                        w.setAttributes(lp);
                    } catch (Exception ignored) { }
                }
            }
            d.show();
            return d;
        }

        public Dialog dialog() { return d; }
    }

    /** Íconos de línea dibujados en código (sin imágenes). */
    public static class LineIcon extends View {
        private final int kind, highlight;
        private final boolean selected, n;
        private final Paint st = new Paint(Paint.ANTI_ALIAS_FLAG), fl = new Paint(Paint.ANTI_ALIAS_FLAG),
                tx = new Paint(Paint.ANTI_ALIAS_FLAG), bgP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float dp;

        public LineIcon(Context c, int kind, int highlight, boolean selected) {
            super(c);
            this.kind = kind; this.highlight = highlight; this.selected = selected;
            n = night(c);
            dp = c.getResources().getDisplayMetrics().density;
            st.setStyle(Paint.Style.STROKE);
            st.setStrokeWidth(1.8f * dp);
            st.setStrokeCap(Paint.Cap.ROUND);
            st.setStrokeJoin(Paint.Join.ROUND);
            tx.setTextAlign(Paint.Align.CENTER);
            tx.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        }

        private void rect(Canvas c, float l, float t, float r, float b, boolean on, String letter) {
            RectF rf = new RectF(l, t, r, b);
            int ac = accent(n);
            if (on) { fl.setColor((ac & 0x00FFFFFF) | 0x40000000); c.drawRoundRect(rf, 3 * dp, 3 * dp, fl); }
            st.setColor(on ? ac : fg(n));
            c.drawRoundRect(rf, 3 * dp, 3 * dp, st);
            if (letter != null) {
                tx.setTextSize(Math.min(r - l, b - t) * 0.42f);
                tx.setColor(on ? ac : muted(n));
                c.drawText(letter, rf.centerX(), rf.centerY() + tx.getTextSize() * 0.36f, tx);
            }
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), cx = w / 2, cy = h / 2;
            bgP.setColor(selected ? ((accent(n) & 0x00FFFFFF) | 0x1F000000) : (n ? 0xFF1E2934 : 0xFFEEF2F5));
            c.drawRoundRect(new RectF(0, 0, w, h), 14 * dp, 14 * dp, bgP);
            int ink = fg(n);
            st.setColor(ink);
            fl.setColor(ink);
            float u = Math.min(w, h) / 2 * 0.62f;
            switch (kind) {
                case LAYOUT_SIDE_H: {   // dos teléfonos apaisados, lado a lado
                    float rw = u * 0.95f, rh = u * 0.62f, g = 1.5f * dp;
                    rect(c, cx - g - rw, cy - rh / 2, cx - g, cy + rh / 2, highlight == 0, "E");
                    rect(c, cx + g, cy - rh / 2, cx + g + rw, cy + rh / 2, highlight == 1, "P");
                    break;
                }
                case LAYOUT_SIDE_V: {   // vertical: principal a la izquierda, extendido a la derecha
                    float rw = u * 0.55f, rh = u * 1.5f, g = 1.5f * dp;
                    rect(c, cx - g - rw, cy - rh / 2, cx - g, cy + rh / 2, highlight == 0, "P");
                    rect(c, cx + g, cy - rh / 2, cx + g + rw, cy + rh / 2, highlight == 1, "E");
                    break;
                }
                case LAYOUT_STACK: {    // horizontal: principal arriba, extendido abajo
                    float rw = u * 1.5f, rh = u * 0.62f, g = 1.5f * dp;
                    rect(c, cx - rw / 2, cy - g - rh, cx + rw / 2, cy - g, highlight == 0, "P");
                    rect(c, cx - rw / 2, cy + g, cx + rw / 2, cy + g + rh, highlight == 1, "E");
                    break;
                }
                case LOCK: case UNLOCK: {
                    RectF body = new RectF(cx - u * 0.6f, cy - u * 0.1f, cx + u * 0.6f, cy + u * 0.8f);
                    c.drawRoundRect(body, 3 * dp, 3 * dp, st);
                    RectF arc = new RectF(cx - u * 0.4f, cy - u * 0.85f, cx + u * 0.4f, cy + u * 0.15f);
                    if (kind == LOCK) c.drawArc(arc, 180, 180, false, st);
                    else c.drawArc(new RectF(arc.left + u * 0.5f, arc.top, arc.right + u * 0.5f, arc.bottom), 180, 160, false, st);
                    c.drawLine(cx - u * 0.4f, cy - u * 0.35f, cx - u * 0.4f, cy - u * 0.1f, st);
                    if (kind == LOCK) c.drawLine(cx + u * 0.4f, cy - u * 0.35f, cx + u * 0.4f, cy - u * 0.1f, st);
                    break;
                }
                case KEYBOARD: case KB_TOUCHPAD: {
                    float kw = kind == KB_TOUCHPAD ? u * 1.05f : u * 1.4f;
                    float left = kind == KB_TOUCHPAD ? cx - u * 1.4f : cx - kw;
                    c.drawRoundRect(new RectF(left, cy - u * 0.65f, left + kw * 2 * (kind == KB_TOUCHPAD ? 0.95f : 1f) , cy + u * 0.65f), 3 * dp, 3 * dp, st);
                    float kx0 = left + u * 0.25f, kx1 = left + kw * 2 * (kind == KB_TOUCHPAD ? 0.95f : 1f) - u * 0.25f;
                    for (int i = 0; i < 4; i++) c.drawCircle(kx0 + (kx1 - kx0) * i / 3, cy - u * 0.2f, 1.3f * dp, fl);
                    c.drawLine(kx0 + u * 0.2f, cy + u * 0.3f, kx1 - u * 0.2f, cy + u * 0.3f, st);
                    if (kind == KB_TOUCHPAD) {
                        float tl = left + kw * 1.9f + u * 0.15f;
                        c.drawRoundRect(new RectF(tl, cy - u * 0.65f, cx + u * 1.45f, cy + u * 0.65f), 3 * dp, 3 * dp, st);
                    }
                    break;
                }
                case TOUCHPAD:
                    c.drawRoundRect(new RectF(cx - u, cy - u * 0.75f, cx + u, cy + u * 0.75f), 4 * dp, 4 * dp, st);
                    c.drawLine(cx - u, cy + u * 0.35f, cx + u, cy + u * 0.35f, st);
                    c.drawLine(cx, cy + u * 0.35f, cx, cy + u * 0.75f, st);
                    break;
                case GAMEPAD: {
                    RectF body = new RectF(cx - u * 1.2f, cy - u * 0.6f, cx + u * 1.2f, cy + u * 0.6f);
                    c.drawRoundRect(body, u * 0.6f, u * 0.6f, st);
                    c.drawLine(cx - u * 0.75f, cy, cx - u * 0.35f, cy, st);
                    c.drawLine(cx - u * 0.55f, cy - u * 0.2f, cx - u * 0.55f, cy + u * 0.2f, st);
                    c.drawCircle(cx + u * 0.45f, cy - u * 0.12f, 1.8f * dp, fl);
                    c.drawCircle(cx + u * 0.7f, cy + u * 0.12f, 1.8f * dp, fl);
                    break;
                }
                case MIC:
                    c.drawRoundRect(new RectF(cx - u * 0.3f, cy - u * 0.85f, cx + u * 0.3f, cy + u * 0.2f), u * 0.3f, u * 0.3f, st);
                    c.drawArc(new RectF(cx - u * 0.6f, cy - u * 0.4f, cx + u * 0.6f, cy + u * 0.5f), 0, 180, false, st);
                    c.drawLine(cx, cy + u * 0.5f, cx, cy + u * 0.85f, st);
                    break;
                case WIFI:
                    for (int i = 1; i <= 3; i++) {
                        float r = u * 0.38f * i;
                        c.drawArc(new RectF(cx - r, cy + u * 0.55f - r, cx + r, cy + u * 0.55f + r), 225, 90, false, st);
                    }
                    c.drawCircle(cx, cy + u * 0.55f, 2 * dp, fl);
                    break;
                case GEAR:
                    c.drawCircle(cx, cy, u * 0.35f, st);
                    c.drawCircle(cx, cy, u * 0.72f, st);
                    for (int i = 0; i < 8; i++) {
                        double ang = Math.PI / 4 * i;
                        c.drawLine(cx + (float) Math.cos(ang) * u * 0.72f, cy + (float) Math.sin(ang) * u * 0.72f,
                                cx + (float) Math.cos(ang) * u, cy + (float) Math.sin(ang) * u, st);
                    }
                    break;
                case AUTO:
                    c.drawRoundRect(new RectF(cx - u, cy - u * 0.45f, cx + u * 0.4f, cy + u * 0.45f), 3 * dp, 3 * dp, st);
                    c.drawLine(cx + u * 0.65f, cy - u * 0.75f, cx + u * 0.65f, cy - u * 0.25f, st);
                    c.drawLine(cx + u * 0.4f, cy - u * 0.5f, cx + u * 0.9f, cy - u * 0.5f, st);
                    break;
                case CHECK:
                    st.setColor(accent(n));
                    c.drawLine(cx - u * 0.55f, cy, cx - u * 0.15f, cy + u * 0.4f, st);
                    c.drawLine(cx - u * 0.15f, cy + u * 0.4f, cx + u * 0.6f, cy - u * 0.45f, st);
                    break;
                case CROSS:
                    st.setColor(n ? 0xFFF0A26B : 0xFFB4541A);
                    c.drawLine(cx - u * 0.45f, cy - u * 0.45f, cx + u * 0.45f, cy + u * 0.45f, st);
                    c.drawLine(cx + u * 0.45f, cy - u * 0.45f, cx - u * 0.45f, cy + u * 0.45f, st);
                    break;
                case PIN:   // acceso directo: cuadrito con flecha
                    c.drawRoundRect(new RectF(cx - u * 0.75f, cy - u * 0.75f, cx + u * 0.75f, cy + u * 0.75f), 5 * dp, 5 * dp, st);
                    c.drawLine(cx - u * 0.25f, cy + u * 0.25f, cx + u * 0.3f, cy - u * 0.3f, st);
                    c.drawLine(cx + u * 0.3f, cy - u * 0.3f, cx - u * 0.05f, cy - u * 0.3f, st);
                    c.drawLine(cx + u * 0.3f, cy - u * 0.3f, cx + u * 0.3f, cy + u * 0.05f, st);
                    break;
                case OPEN: case RECEIVE: {
                    float rw = u * 0.55f, rh = u * 1.2f;
                    rect(c, cx - u - rw * 0.2f, cy - rh / 2, cx - u + rw * 0.8f, cy + rh / 2, kind == RECEIVE, null);
                    rect(c, cx + u * 0.2f, cy - rh / 2, cx + u * 0.2f + rw, cy + rh / 2, kind == OPEN, null);
                    c.drawLine(cx - u * 0.15f, cy, cx + u * 0.1f, cy, st);
                    break;
                }
                case STORE:
                    c.drawRoundRect(new RectF(cx - u * 0.75f, cy - u * 0.35f, cx + u * 0.75f, cy + u * 0.8f), 3 * dp, 3 * dp, st);
                    c.drawArc(new RectF(cx - u * 0.35f, cy - u * 0.85f, cx + u * 0.35f, cy - u * 0.05f), 180, 180, false, st);
                    break;
                case PALETTE:
                    c.drawCircle(cx, cy, u * 0.85f, st);
                    for (int i = 0; i < 4; i++) {
                        double ang = Math.PI / 2 * i - Math.PI / 4;
                        c.drawCircle(cx + (float) Math.cos(ang) * u * 0.45f, cy + (float) Math.sin(ang) * u * 0.45f, 2.3f * dp, fl);
                    }
                    break;
                case HOME: {
                    Path hp = new Path();
                    hp.moveTo(cx - u * 0.85f, cy - u * 0.05f); hp.lineTo(cx, cy - u * 0.8f); hp.lineTo(cx + u * 0.85f, cy - u * 0.05f);
                    c.drawPath(hp, st);
                    c.drawRoundRect(new RectF(cx - u * 0.6f, cy - u * 0.2f, cx + u * 0.6f, cy + u * 0.8f), 2 * dp, 2 * dp, st);
                    break;
                }
                case SEND: {
                    Path sp = new Path();
                    sp.moveTo(cx - u * 0.8f, cy - u * 0.7f); sp.lineTo(cx + u * 0.85f, cy); sp.lineTo(cx - u * 0.8f, cy + u * 0.7f);
                    sp.lineTo(cx - u * 0.45f, cy); sp.close();
                    c.drawPath(sp, st);
                    break;
                }
                case PAGES: {   // tres escritorios lado a lado
                    float pw = u * 0.5f, ph = u * 1.1f;
                    rect(c, cx - pw / 2, cy - ph / 2, cx + pw / 2, cy + ph / 2, true, null);
                    rect(c, cx - pw / 2 - u * 0.75f, cy - ph * 0.35f, cx - pw / 2 - u * 0.2f, cy + ph * 0.35f, false, null);
                    rect(c, cx + pw / 2 + u * 0.2f, cy - ph * 0.35f, cx + pw / 2 + u * 0.75f, cy + ph * 0.35f, false, null);
                    break;
                }
                case PLUS:
                    c.drawRoundRect(new RectF(cx - u * 0.8f, cy - u * 0.8f, cx + u * 0.8f, cy + u * 0.8f), 5 * dp, 5 * dp, st);
                    c.drawLine(cx, cy - u * 0.4f, cx, cy + u * 0.4f, st);
                    c.drawLine(cx - u * 0.4f, cy, cx + u * 0.4f, cy, st);
                    break;
                case GRID:
                    for (int i = -1; i <= 1; i += 2) for (int j = -1; j <= 1; j += 2)
                        c.drawRoundRect(new RectF(cx + i * u * 0.45f - u * 0.35f, cy + j * u * 0.45f - u * 0.35f,
                                cx + i * u * 0.45f + u * 0.35f, cy + j * u * 0.45f + u * 0.35f), 3 * dp, 3 * dp, st);
                    break;
                case IMAGE: {
                    c.drawRoundRect(new RectF(cx - u * 0.9f, cy - u * 0.7f, cx + u * 0.9f, cy + u * 0.7f), 3 * dp, 3 * dp, st);
                    Path ip = new Path();
                    ip.moveTo(cx - u * 0.9f, cy + u * 0.45f); ip.lineTo(cx - u * 0.25f, cy - u * 0.1f);
                    ip.lineTo(cx + u * 0.25f, cy + u * 0.3f); ip.lineTo(cx + u * 0.9f, cy - u * 0.2f);
                    c.drawPath(ip, st);
                    c.drawCircle(cx + u * 0.4f, cy - u * 0.35f, 1.6f * dp, fl);
                    break;
                }
                case CLOCK:
                    c.drawCircle(cx, cy, u * 0.85f, st);
                    c.drawLine(cx, cy, cx, cy - u * 0.5f, st);
                    c.drawLine(cx, cy, cx + u * 0.35f, cy + u * 0.2f, st);
                    break;
                case FOLDER: {
                    Path fp = new Path();
                    fp.moveTo(cx - u * 0.9f, cy - u * 0.6f); fp.lineTo(cx - u * 0.3f, cy - u * 0.6f); fp.lineTo(cx - u * 0.1f, cy - u * 0.4f);
                    fp.lineTo(cx + u * 0.9f, cy - u * 0.4f); fp.lineTo(cx + u * 0.9f, cy + u * 0.65f); fp.lineTo(cx - u * 0.9f, cy + u * 0.65f); fp.close();
                    c.drawPath(fp, st);
                    break;
                }
                case WIDGETS:
                    c.drawRoundRect(new RectF(cx - u * 0.9f, cy - u * 0.8f, cx - u * 0.1f, cy - u * 0.05f), 3 * dp, 3 * dp, st);
                    c.drawRoundRect(new RectF(cx + u * 0.1f, cy - u * 0.8f, cx + u * 0.9f, cy + u * 0.8f), 3 * dp, 3 * dp, st);
                    c.drawRoundRect(new RectF(cx - u * 0.9f, cy + u * 0.1f, cx - u * 0.1f, cy + u * 0.8f), 3 * dp, 3 * dp, st);
                    break;
                case BATTERY:
                    c.drawRoundRect(new RectF(cx - u * 0.9f, cy - u * 0.45f, cx + u * 0.7f, cy + u * 0.45f), 3 * dp, 3 * dp, st);
                    c.drawLine(cx + u * 0.9f, cy - u * 0.18f, cx + u * 0.9f, cy + u * 0.18f, st);
                    c.drawRect(new RectF(cx - u * 0.7f, cy - u * 0.25f, cx + u * 0.15f, cy + u * 0.25f), fl);
                    break;
                case ALIGN:
                    c.drawLine(cx - u * 0.9f, cy - u * 0.9f, cx - u * 0.9f, cy + u * 0.9f, st);
                    c.drawRoundRect(new RectF(cx - u * 0.6f, cy - u * 0.65f, cx + u * 0.8f, cy - u * 0.15f), 2 * dp, 2 * dp, st);
                    c.drawRoundRect(new RectF(cx - u * 0.6f, cy + u * 0.15f, cx + u * 0.3f, cy + u * 0.65f), 2 * dp, 2 * dp, st);
                    break;
                case TRASH:
                    c.drawLine(cx - u * 0.8f, cy - u * 0.55f, cx + u * 0.8f, cy - u * 0.55f, st);
                    c.drawLine(cx - u * 0.25f, cy - u * 0.8f, cx + u * 0.25f, cy - u * 0.8f, st);
                    c.drawRoundRect(new RectF(cx - u * 0.55f, cy - u * 0.55f, cx + u * 0.55f, cy + u * 0.85f), 3 * dp, 3 * dp, st);
                    break;
                case NOTE:
                    c.drawRoundRect(new RectF(cx - u * 0.7f, cy - u * 0.8f, cx + u * 0.7f, cy + u * 0.8f), 3 * dp, 3 * dp, st);
                    for (int i = 0; i < 3; i++) c.drawLine(cx - u * 0.4f, cy - u * 0.35f + i * u * 0.35f, cx + u * 0.4f, cy - u * 0.35f + i * u * 0.35f, st);
                    break;
            }
        }
    }
}
