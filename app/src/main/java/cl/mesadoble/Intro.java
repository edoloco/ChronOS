package cl.mesadoble;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Intro de ChronOS (nueva etapa): fondo naranja.
 *  1) Si todavía no se eligió, pregunta en los dos teléfonos "¿principal o extendido?"; donde se responda queda
 *     configurado y el otro toma el papel contrario (no se pueden dos principales ni dos extendidos).
 *  2) Letras grandes al centro: "Este es el principal" / "Este es el extendido"; se desvanecen.
 *  3) Principal: el logo aparece lento con el sonido, se corre a la izquierda y descubre "ChronOS" en blanco.
 *     Extendido: solo el fondo naranja. Al final todo se desvanece y queda el escritorio.
 * Se puede desactivar en Ajustes (la pregunta del papel igual aparece si falta).
 */
final class Intro extends FrameLayout {

    interface Host {
        /** Elegir el papel de este teléfono (1 principal, 0 extendido). false si no se puede (ya existe ese papel). */
        boolean chooseRole(int side);
        int side();
        boolean solo();
        boolean connected();
    }

    static final int ORANGE = 0xFFF57C1F;
    static boolean shownThisProcess;

    private final Activity a;
    private final Host host;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final float dp;
    private final Typeface font;
    private LinearLayout question;
    private TextView label, name, hint;
    private ImageView logo;
    private boolean asking, done;
    private Runnable onDone;

    private int dp(float v) { return Math.round(v * dp); }

    static Typeface font(Activity a) {
        try { return Typeface.createFromAsset(a.getAssets(), "fonts/didact.ttf"); }   // Didact Gothic (OFL), al estilo Century Gothic
        catch (Exception e) { return Typeface.create("sans-serif-light", Typeface.NORMAL); }
    }

    Intro(Activity a, Host host) {
        super(a);
        this.a = a;
        this.host = host;
        dp = a.getResources().getDisplayMetrics().density;
        font = font(a);
        setBackgroundColor(ORANGE);
        setClickable(true);
        setElevation(dp(40));

        label = new TextView(a);
        label.setTypeface(font);
        label.setTextColor(Color.WHITE);
        label.setTextSize(40);
        label.setGravity(Gravity.CENTER);
        label.setShadowLayer(dp(6), 0, dp(2), 0x55000000);
        label.setAlpha(0f);
        addView(label, new LayoutParams(-1, -2, Gravity.CENTER));

        logo = new ImageView(a);
        int ic = a.getResources().getIdentifier("ic_launcher", "mipmap", a.getPackageName());
        if (ic != 0) logo.setImageResource(ic);
        logo.setAlpha(0f);
        addView(logo, new LayoutParams(dp(132), dp(132), Gravity.CENTER));

        name = new TextView(a);
        name.setText("ChronOS");
        name.setTypeface(font);
        name.setTextColor(Color.WHITE);
        name.setTextSize(52);
        name.setShadowLayer(dp(8), dp(1), dp(3), 0x66000000);
        name.setAlpha(0f);
        addView(name, new LayoutParams(-2, -2, Gravity.CENTER));
    }

    void setOnDone(Runnable r) { onDone = r; }

    /** ask = falta elegir el papel de este teléfono. */
    void start(boolean ask) {
        shownThisProcess = true;
        if (ask) showQuestion(); else showLabel();
    }

    // ---------------- pregunta ----------------

    private TextView bigButton(String t, View.OnClickListener l) {
        TextView b = new TextView(a);
        b.setText(t);
        b.setTypeface(font);
        b.setTextSize(24);
        b.setTextColor(ORANGE);
        b.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.WHITE);
        g.setCornerRadius(dp(34));
        b.setBackground(g);
        b.setElevation(dp(6));
        b.setOnClickListener(l);
        return b;
    }

    private void showQuestion() {
        asking = true;
        question = new LinearLayout(a);
        question.setOrientation(LinearLayout.VERTICAL);
        question.setGravity(Gravity.CENTER);
        TextView q = new TextView(a);
        q.setText("¿Qué teléfono es este?");
        q.setTypeface(font);
        q.setTextSize(32);
        q.setTextColor(Color.WHITE);
        q.setGravity(Gravity.CENTER);
        q.setShadowLayer(dp(5), 0, dp(2), 0x44000000);
        question.addView(q);
        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(dp(190), dp(68));
        bl.setMargins(dp(12), dp(26), dp(12), 0);
        row.addView(bigButton("Principal", v -> answer(1)), bl);
        row.addView(bigButton("Extendido", v -> answer(0)), new LinearLayout.LayoutParams(bl));
        question.addView(row);
        hint = new TextView(a);
        hint.setTypeface(font);
        hint.setTextSize(15);
        hint.setTextColor(0xEEFFFFFF);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(dp(24), dp(22), dp(24), 0);
        hint.setText(host.connected() ? "Responde en cualquiera de los dos: el otro toma el papel contrario."
                : "Principal: aquí corren las apps. Extendido: pantalla extra y teclado.");
        question.addView(hint);
        question.setAlpha(0f);
        addView(question, new LayoutParams(-1, -2, Gravity.CENTER));
        question.animate().alpha(1f).setDuration(500).start();
    }

    private void answer(int side) {
        if (!asking) return;
        if (!host.chooseRole(side)) {
            hint.setText(side == 1 ? "Ya existe un teléfono principal" : "Ya existe un teléfono extendido");
            hint.setTextColor(Color.WHITE);
            hint.animate().scaleX(1.08f).scaleY(1.08f).setDuration(120).withEndAction(() -> hint.animate().scaleX(1f).scaleY(1f).setDuration(120).start()).start();
            return;
        }
        endQuestion();
    }

    /** El otro teléfono respondió primero: este toma el papel contrario y sigue la intro. */
    void remoteAnswered() { if (asking) endQuestion(); }

    boolean asking() { return asking; }

    private void endQuestion() {
        asking = false;
        final View qv = question;
        if (qv != null) qv.animate().alpha(0f).setDuration(350).withEndAction(() -> { removeView(qv); showLabel(); }).start();
        else showLabel();
    }

    // ---------------- letras, logo y nombre ----------------

    private void showLabel() {
        boolean p = host.side() == 1 || host.solo();
        label.setText(host.solo() ? "ChronOS en una pantalla" : p ? "Este es el principal" : "Este es el extendido");
        label.animate().alpha(1f).setDuration(600).start();
        h.postDelayed(() -> label.animate().alpha(0f).setDuration(700).withEndAction(this::showLogo).start(), 2400);
    }

    private void showLogo() {
        boolean p = host.side() == 1 || host.solo();
        if (!p) { h.postDelayed(this::finish, 4200); return; }   // extendido: solo el naranja
        try {
            int snd = a.getResources().getIdentifier("intro", "raw", a.getPackageName());
            if (snd != 0) {
                MediaPlayer mp = MediaPlayer.create(a, snd);
                if (mp != null) { mp.setOnCompletionListener(MediaPlayer::release); mp.start(); }
            }
        } catch (Exception ignored) { }
        logo.animate().alpha(1f).setDuration(1400).start();
        h.postDelayed(() -> {
            // el logo se corre a la izquierda y descubre el nombre
            float nw = name.getWidth(), gap = dp(18), L = dp(132);
            float logoTo = -(gap + nw) / 2f, nameTo = (L + gap) / 2f;
            logo.animate().translationX(logoTo).setDuration(900).start();
            name.setTranslationX(nameTo - dp(40));
            name.animate().alpha(1f).translationX(nameTo).setDuration(900).setStartDelay(150).start();
        }, 1700);
        h.postDelayed(() -> {
            logo.animate().alpha(0f).setDuration(600).start();
            name.animate().alpha(0f).setDuration(600).setStartDelay(0).start();
        }, 4300);
        h.postDelayed(this::finish, 5000);
    }

    /** El naranja se desvanece y queda el escritorio. */
    void finish() {
        if (done) return;
        done = true;
        animate().alpha(0f).setDuration(700).withEndAction(() -> {
            if (getParent() instanceof android.view.ViewGroup) ((android.view.ViewGroup) getParent()).removeView(this);
            if (onDone != null) onDone.run();
        }).start();
    }
}
