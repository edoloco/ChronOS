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
        setGravity(Gravity.CENTER_VERTICAL);
        setPadding(dp(8), 0, dp(8), 0);
        int bg = 0x00000000;
        MediaIcon prev = new MediaIcon(c, MediaIcon.PREV, 34, bg, ink);
        MediaIcon back = new MediaIcon(c, MediaIcon.BACK10, 34, bg, ink);
        play = new MediaIcon(c, MediaIcon.PAUSE, 42, (ink & 0x00FFFFFF) | 0x26000000, ink);
        MediaIcon fwd = new MediaIcon(c, MediaIcon.FWD10, 34, bg, ink);
        MediaIcon next = new MediaIcon(c, MediaIcon.NEXT, 34, bg, ink);
        prev.setOnClickListener(v -> MediaHub.cmd(c, "prev", 0));
        back.setOnClickListener(v -> MediaHub.cmd(c, "rel", -10000));
        play.setOnClickListener(v -> MediaHub.cmd(c, "toggle", 0));
        fwd.setOnClickListener(v -> MediaHub.cmd(c, "rel", 10000));
        next.setOnClickListener(v -> MediaHub.cmd(c, "next", 0));
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
            @Override public void onStopTrackingTouch(SeekBar s) { dragging = false; MediaHub.cmd(getContext(), "seek", (double) s.getProgress() * duration / 1000); }
        });
        sr.addView(pos);
        sr.addView(seek, new LayoutParams(0, -2, 1));
        sr.addView(dur);
        mid.addView(sr);
        addView(mid, new LayoutParams(0, -2, 1));

        boost = new TextView(c);
        boost.setText("BOOST");
        boost.setTextSize(11);
        boost.setTypeface(Typeface.DEFAULT_BOLD);
        boost.setLetterSpacing(0.08f);
        boost.setGravity(Gravity.CENTER);
        boost.setPadding(dp(10), 0, dp(10), 0);
        boost.setOnClickListener(v -> {
            boolean on = !Boost.enabled(getContext());
            Boost.setEnabled(getContext(), hub, on);
            paintBoost(on, ink);
        });
        addView(boost, new LayoutParams(-2, dp(30)));
        MediaIcon close = new MediaIcon(c, MediaIcon.CLOSE, 30, bg, ink);
        close.setOnClickListener(v -> MediaHub.cmd(c, "stop", 0));
        LayoutParams cl = new LayoutParams(-2, -2);
        cl.setMarginStart(dp(4));
        addView(close, cl);
        paintBoost(Boost.enabled(c), ink);
    }

    private void paintBoost(boolean on, int ink) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(15));
        g.setColor(on ? 0xFFFF6D00 : 0x00000000);
        g.setStroke(dp(1.5f), on ? 0xFFFF6D00 : (ink & 0x00FFFFFF) | 0x88000000);
        boost.setBackground(g);
        boost.setTextColor(on ? 0xFFFFFFFF : ink);
    }

    /** Estado de la música (mState de tipo audio). */
    public void update(JSONObject m) {
        title.setText(m.optString("title") + (m.optString("sub").isEmpty() ? "" : "  ·  " + m.optString("sub")));
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
