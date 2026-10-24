package cl.mesadoble;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import org.json.JSONObject;

/**
 * Reproductor de la action bar: la música suena en el principal y aquí están los controles, la barra de avance
 * y "Boost experience" (canal derecho en el principal, izquierdo en el extendido).
 */
public class BarPlayer extends LinearLayout {

    private final Hub hub;
    private final MediaIcon play;
    private final SeekBar seek;
    private final TextView pos, dur, title, boost;
    private long duration;
    private boolean dragging;

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    public BarPlayer(Context c, Hub hub, int ink) {
        super(c);
        this.hub = hub;
        this.inkC = ink;
        setGravity(Gravity.CENTER_VERTICAL);
        setPadding(dp(8), 0, dp(8), 0);
        int bg = 0x00000000;
        MediaIcon prev = new MediaIcon(c, MediaIcon.PREV, 32, bg, ink);
        MediaIcon back = new MediaIcon(c, MediaIcon.BACK10, 32, bg, ink);
        play = new MediaIcon(c, MediaIcon.PAUSE, 40, (ink & 0x00FFFFFF) | 0x26000000, ink);
        MediaIcon fwd = new MediaIcon(c, MediaIcon.FWD10, 32, bg, ink);
        MediaIcon next = new MediaIcon(c, MediaIcon.NEXT, 32, bg, ink);
        prev.setOnClickListener(v -> act("prev", 0, "prev"));
        back.setOnClickListener(v -> act("rel", -10000, "rw"));
        play.setOnClickListener(v -> act("toggle", 0, "play"));
        fwd.setOnClickListener(v -> act("rel", 10000, "ff"));
        next.setOnClickListener(v -> act("next", 0, "next"));
        for (View v : new View[]{prev, back, play, fwd, next}) addView(v);

        LinearLayout mid = new LinearLayout(c);
        mid.setOrientation(VERTICAL);
        mid.setPadding(dp(10), 0, dp(6), 0);
        title = new TextView(c);
        title.setTextSize(12);
        title.setTextColor(ink);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        mid.addView(title);
        LinearLayout sr = new LinearLayout(c);
        sr.setGravity(Gravity.CENTER_VERTICAL);
        pos = new TextView(c); pos.setTextSize(10.5f); pos.setTextColor(ink);
        dur = new TextView(c); dur.setTextSize(10.5f); dur.setTextColor(ink);
        seek = new SeekBar(c);
        seek.setMax(1000);
        seek.getProgressDrawable().setColorFilter(ink, PorterDuff.Mode.SRC_IN);
        seek.getThumb().setColorFilter(ink, PorterDuff.Mode.SRC_IN);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) { if (u) pos.setText(MediaHub.fmt(p * duration / 1000)); }
            @Override public void onStartTrackingTouch(SeekBar s) { dragging = true; }
            @Override public void onStopTrackingTouch(SeekBar s) {
                dragging = false;
                double to = (double) s.getProgress() * duration / 1000;
                if (external) xCmd("seek", to); else MediaHub.cmd(getContext(), "seek", to);   // YouTube: por su sesión
            }
        });
        sr.addView(pos);
        sr.addView(seek, new LayoutParams(0, -2, 1));
        sr.addView(dur);
        mid.addView(sr);
        addView(mid, new LayoutParams(0, -2, 1));

        MediaIcon vd = new MediaIcon(c, MediaIcon.VOL_DOWN, 30, bg, ink);
        MediaIcon vu = new MediaIcon(c, MediaIcon.VOL_UP, 30, bg, ink);
        vd.setOnClickListener(v -> act("vol", -1, "vdown"));
        vu.setOnClickListener(v -> act("vol", 1, "vup"));
        addView(vd);
        // entre bajar y subir: el nivel del volumen en barritas (como la energía de Mega Man)
        meter = new VolMeter(c, 8, ink, (ink & 0x00FFFFFF) | 0x2E000000);
        LayoutParams ml = new LayoutParams(dp(9), dp(30));
        ml.setMargins(dp(2), 0, dp(2), 0);
        addView(meter, ml);
        addView(vu);

        boost = new TextView(c);
        boost.setText("BOOST");
        boost.setTextSize(11);
        boost.setTypeface(Typeface.DEFAULT_BOLD);
        boost.setLetterSpacing(0.08f);
        boost.setGravity(Gravity.CENTER);
        boost.setPadding(dp(10), 0, dp(10), 0);
        boost.setOnClickListener(v -> {
            if (external) {   // video de otra app: se captura su sonido en el principal y suena también aquí
                appBoost = !appBoost;
                if (hub.isPrincipal()) hub.boostApp(appBoost); else hub.send("boostApp", "on", appBoost);
                paintBoost(appBoost, ink);
                return;
            }
            boolean on = !Boost.enabled(getContext());
            Boost.setEnabled(getContext(), hub, on);
            paintBoost(on, ink);
        });
        addView(boost, new LayoutParams(-2, dp(30)));
        MediaIcon close = new MediaIcon(c, MediaIcon.CLOSE, 30, bg, ink);
        close.setOnClickListener(v -> { if (external) onClose.run(); else MediaHub.cmd(c, "stop", 0); });
        LayoutParams cl = new LayoutParams(-2, -2);
        cl.setMarginStart(dp(4));
        addView(close, cl);
        paintBoost(Boost.enabled(c), ink);
    }

    private final VolMeter meter;
    /** Volumen del principal (0..1). */
    public void setVolume(float f) { meter.setLevel(f); }

    private void xCmd(String a, double v) {
        JSONObject m = new JSONObject();
        try { m.put("t", "xCmd"); m.put("a", a); m.put("v", v); } catch (Exception ignored) { }
        if (hub.isPrincipal()) ExtMedia.command(getContext(), m); else if (hub.connected()) hub.send(m);
    }

    /** YouTube u otra app del principal: título, canal y avance (la barra se puede mover). */
    public void updateExternal(JSONObject m) {
        if (!external) setExternal(true);
        String t = m.optString("title"), sb = m.optString("sub");
        title.setText("▶  " + (t.isEmpty() ? "Video en el principal" : t) + (sb.isEmpty() ? "" : "  ·  " + sb));
        duration = m.optLong("dur");
        boolean bar = duration > 0;
        seek.setVisibility(bar ? VISIBLE : INVISIBLE);
        seek.setEnabled(m.optBoolean("seek", true));
        dur.setText(bar ? MediaHub.fmt(duration) : "");
        if (bar && !dragging) {
            long p = m.optLong("pos");
            pos.setText(MediaHub.fmt(p));
            seek.setProgress((int) (p * 1000 / duration));
        } else if (!bar) pos.setText("");
        play.setKind(m.optBoolean("play") ? MediaIcon.PAUSE : MediaIcon.PLAY);
    }

    private void paintBoost(boolean on, int ink) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(15));
        g.setColor(on ? 0xFFFF6D00 : 0x00000000);
        g.setStroke(dp(1.5f), on ? 0xFFFF6D00 : (ink & 0x00FFFFFF) | 0x88000000);
        boost.setBackground(g);
        boost.setTextColor(on ? 0xFFFFFFFF : ink);
    }

    // ---- video de otra app (YouTube, Netflix…): sin barra de avance; los botones mandan teclas multimedia
    private boolean external, appBoost;
    private int inkC;
    /** El principal avisó si el Boost está sonando (capturado o de archivo). */
    public void setBoostOn(boolean on) { if (external) { appBoost = on; paintBoost(on, inkC); } }
    private Runnable onClose = () -> { };
    public void setOnClose(Runnable r) { onClose = r; }
    public boolean isExternal() { return external; }

    public void setExternal(boolean on) {
        external = on;
        if (on) {
            title.setText("▶  Video en el principal");
            seek.setVisibility(INVISIBLE);
            paintBoost(appBoost, inkC);
            pos.setText(""); dur.setText("");
            play.setKind(MediaIcon.PAUSE);
        } else seek.setVisibility(VISIBLE);
    }

    private void act(String cmd, double v, String key) {
        if (external && !"vol".equals(cmd)) { xCmd(cmd, v); return; }   // ±10 s y la barra de verdad (sesión multimedia)
        if (external) { if (hub.isPrincipal()) hub.media(key); else hub.send("media", "k", key); }
        else MediaHub.cmd(getContext(), cmd, v);
    }

    /** Estado del reproductor (mState de música o video). */
    public void update(JSONObject m) {
        if (external) setExternal(false);
        title.setText((MediaHub.VIDEO.equals(m.optString("kind")) ? "▶  " : "♪  ") + m.optString("title")
                + (m.optString("sub").isEmpty() ? "" : "  ·  " + m.optString("sub")));
        duration = m.optLong("dur");
        dur.setText(MediaHub.fmt(duration));
        if (!dragging) {
            long p = m.optLong("pos");
            pos.setText(MediaHub.fmt(p));
            seek.setProgress(duration > 0 ? (int) (p * 1000 / duration) : 0);
        }
        play.setKind(m.optBoolean("play") ? MediaIcon.PAUSE : MediaIcon.PLAY);
    }
}
