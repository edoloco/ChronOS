package cl.mesadoble;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import org.json.JSONObject;

/**
 * Controles en el EXTENDIDO para lo que se ve en el principal:
 *   video → como YouTube (oscuro, barra roja, retroceder/adelantar 10 s, volumen)
 *   fotos → anterior / siguiente, presentación, girar, zoom.
 */
public class RemoteActivity extends Activity implements Link.Listener {

    /** Hay un panel de controles para apps externas (YouTube, Netflix...) abierto. */
    static boolean extOpen;

    private static final int RED = 0xFFFF0033, BG = 0xFF0F0F0F, CARD = 0xFF212121;
    private final Handler h = new Handler(Looper.getMainLooper());
    private Hub hub;
    private String kind;
    private TextView title, sub, posText, durText;
    private SeekBar seek;
    private MediaIcon playBtn;
    private TextView slideBtn;
    private boolean dragging;
    private long dur;

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private TextView pill(String text, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(15);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(18), 0, dp(18), 0);
        GradientDrawable g = new GradientDrawable();
        g.setColor(CARD);
        g.setCornerRadius(dp(22));
        t.setBackground(g);
        t.setOnClickListener(l);
        return t;
    }

    private void c(String a, double v) { MediaHub.cmd(this, a, v); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        MainActivity.applyOrientation(this);
        hub = Hub.get(this);
        kind = getIntent().getStringExtra("kind");
        if (kind == null) kind = MediaHub.VIDEO;
        if ("ext".equals(kind)) { buildExternal(); return; }
        boolean video = MediaHub.VIDEO.equals(kind);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(20), dp(16), dp(20), dp(16));

        TextView label = new TextView(this);
        label.setText(video ? "▶  REPRODUCIENDO EN EL PRINCIPAL" : "🖼  VIENDO EN EL PRINCIPAL");
        label.setTextColor(video ? RED : 0xFF5CC3CF);
        label.setTextSize(11);
        label.setLetterSpacing(0.15f);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setGravity(Gravity.CENTER);
        root.addView(label);

        title = new TextView(this);
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setGravity(Gravity.CENTER);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setPadding(0, dp(8), 0, 0);
        root.addView(title);
        sub = new TextView(this);
        sub.setTextColor(0xFFAAAAAA);
        sub.setTextSize(13);
        sub.setGravity(Gravity.CENTER);
        root.addView(sub);

        if (video) {
            seek = new SeekBar(this);
            seek.setMax(1000);
            seek.getProgressDrawable().setColorFilter(RED, PorterDuff.Mode.SRC_IN);
            seek.getThumb().setColorFilter(RED, PorterDuff.Mode.SRC_IN);
            seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar s, int p, boolean u) { if (u) posText.setText(MediaHub.fmt(p * dur / 1000)); }
                @Override public void onStartTrackingTouch(SeekBar s) { dragging = true; }
                @Override public void onStopTrackingTouch(SeekBar s) { dragging = false; c("seek", (double) s.getProgress() * dur / 1000); }
            });
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2);
            sl.setMargins(0, dp(18), 0, 0);
            root.addView(seek, sl);
            LinearLayout times = new LinearLayout(this);
            posText = new TextView(this); posText.setTextColor(0xFFDDDDDD); posText.setTextSize(13);
            durText = new TextView(this); durText.setTextColor(0xFFDDDDDD); durText.setTextSize(13); durText.setGravity(Gravity.END);
            times.addView(posText, new LinearLayout.LayoutParams(0, -2, 1));
            times.addView(durText, new LinearLayout.LayoutParams(0, -2, 1));
            times.setPadding(dp(14), 0, dp(14), 0);
            root.addView(times, new LinearLayout.LayoutParams(-1, -2));

            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER);
            row.setPadding(0, dp(14), 0, dp(10));
            MediaIcon prev = new MediaIcon(this, MediaIcon.PREV, 52, CARD, Color.WHITE);
            MediaIcon back = new MediaIcon(this, MediaIcon.BACK10, 56, CARD, Color.WHITE);
            playBtn = new MediaIcon(this, MediaIcon.PAUSE, 80, RED, Color.WHITE);
            MediaIcon fwd = new MediaIcon(this, MediaIcon.FWD10, 56, CARD, Color.WHITE);
            MediaIcon next = new MediaIcon(this, MediaIcon.NEXT, 52, CARD, Color.WHITE);
            prev.setOnClickListener(v -> c("prev", 0));
            back.setOnClickListener(v -> c("rel", -10000));
            playBtn.setOnClickListener(v -> c("toggle", 0));
            fwd.setOnClickListener(v -> c("rel", 10000));
            next.setOnClickListener(v -> c("next", 0));
            for (View v : new View[]{prev, back, playBtn, fwd, next}) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                lp.setMargins(dp(8), 0, dp(8), 0);
                row.addView(v, lp);
            }
            root.addView(row);
            LinearLayout row2 = new LinearLayout(this);
            row2.setGravity(Gravity.CENTER);
            MediaIcon vd = new MediaIcon(this, MediaIcon.VOL_DOWN, 48, CARD, Color.WHITE);
            MediaIcon vu = new MediaIcon(this, MediaIcon.VOL_UP, 48, CARD, Color.WHITE);
            vd.setOnClickListener(v -> c("vol", -1));
            vu.setOnClickListener(v -> c("vol", 1));
            row2.addView(vd);
            View sp = new View(this);
            row2.addView(sp, new LinearLayout.LayoutParams(dp(16), 1));
            row2.addView(vu);
            View sp2 = new View(this);
            row2.addView(sp2, new LinearLayout.LayoutParams(dp(28), 1));
            row2.addView(pill("Cerrar video", v -> { c("stop", 0); finish(); }), new LinearLayout.LayoutParams(-2, dp(46)));
            root.addView(row2);
        } else {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER);
            row.setPadding(0, dp(22), 0, dp(14));
            MediaIcon prev = new MediaIcon(this, MediaIcon.PREV, 72, CARD, Color.WHITE);
            MediaIcon next = new MediaIcon(this, MediaIcon.NEXT, 72, CARD, Color.WHITE);
            prev.setOnClickListener(v -> c("prev", 0));
            next.setOnClickListener(v -> c("next", 0));
            row.addView(prev);
            View sp = new View(this);
            row.addView(sp, new LinearLayout.LayoutParams(dp(40), 1));
            row.addView(next);
            root.addView(row);
            LinearLayout row2 = new LinearLayout(this);
            row2.setGravity(Gravity.CENTER);
            slideBtn = pill("Presentación", v -> c("slide", 0));
            View[] vs = {pill("−", v -> c("zout", 0)), pill("+", v -> c("zin", 0)), pill("Girar", v -> c("rotate", 0)), slideBtn,
                    pill("Cerrar", v -> { c("stop", 0); finish(); })};
            for (View v : vs) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(46));
                lp.setMargins(dp(5), dp(4), dp(5), dp(4));
                row2.addView(v, lp);
            }
            android.widget.HorizontalScrollView sc = new android.widget.HorizontalScrollView(this);
            sc.setHorizontalScrollBarEnabled(false);
            sc.addView(row2);
            root.addView(sc, new LinearLayout.LayoutParams(-2, -2));
        }
        setContentView(root);
        hub.add(this);
    }

    @Override protected void onDestroy() { if ("ext".equals(kind)) extOpen = false; hub.remove(this); super.onDestroy(); }

    private void key(String k) { if (hub.connected()) hub.send("media", "k", k); }

    /**
     * Controles para un video o música en OTRA app del principal (YouTube, Netflix, Spotify...).
     * Van como teclas multimedia: play/pausa, retroceder/adelantar, anterior/siguiente y volumen.
     */
    private void buildExternal() {
        extOpen = true;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(20), dp(16), dp(20), dp(16));
        TextView label = new TextView(this);
        label.setText("▶  VIDEO EN EL PRINCIPAL");
        label.setTextColor(RED);
        label.setTextSize(11);
        label.setLetterSpacing(0.15f);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setGravity(Gravity.CENTER);
        root.addView(label);
        title = new TextView(this);
        title.setText("Reproduciendo");
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(6), 0, 0);
        root.addView(title);

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        row.setPadding(0, dp(20), 0, dp(12));
        MediaIcon prev = new MediaIcon(this, MediaIcon.PREV, 52, CARD, Color.WHITE);
        MediaIcon back = new MediaIcon(this, MediaIcon.BACK10, 56, CARD, Color.WHITE);
        playBtn = new MediaIcon(this, MediaIcon.PAUSE, 80, RED, Color.WHITE);
        MediaIcon fwd = new MediaIcon(this, MediaIcon.FWD10, 56, CARD, Color.WHITE);
        MediaIcon next = new MediaIcon(this, MediaIcon.NEXT, 52, CARD, Color.WHITE);
        prev.setOnClickListener(v -> key("prev"));
        back.setOnClickListener(v -> key("rw"));
        playBtn.setOnClickListener(v -> key("play"));
        fwd.setOnClickListener(v -> key("ff"));
        next.setOnClickListener(v -> key("next"));
        for (View v : new View[]{prev, back, playBtn, fwd, next}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMargins(dp(8), 0, dp(8), 0);
            row.addView(v, lp);
        }
        root.addView(row);
        LinearLayout row2 = new LinearLayout(this);
        row2.setGravity(Gravity.CENTER);
        MediaIcon vd = new MediaIcon(this, MediaIcon.VOL_DOWN, 48, CARD, Color.WHITE);
        MediaIcon vu = new MediaIcon(this, MediaIcon.VOL_UP, 48, CARD, Color.WHITE);
        vd.setOnClickListener(v -> key("vdown"));
        vu.setOnClickListener(v -> key("vup"));
        row2.addView(vd);
        View sp = new View(this);
        row2.addView(sp, new LinearLayout.LayoutParams(dp(16), 1));
        row2.addView(vu);
        View sp2 = new View(this);
        row2.addView(sp2, new LinearLayout.LayoutParams(dp(28), 1));
        row2.addView(pill("Cerrar", v -> finish()), new LinearLayout.LayoutParams(-2, dp(46)));
        root.addView(row2);
        TextView note = new TextView(this);
        note.setText("La barra de avance aparece con los videos que abres desde Archivos (reproductor de Mesa Doble). "
                + "En otras apps, Android solo deja enviar las teclas de reproducción.");
        note.setTextColor(0xFF888888);
        note.setTextSize(12);
        note.setGravity(Gravity.CENTER);
        note.setPadding(dp(10), dp(18), dp(10), 0);
        root.addView(note);
        setContentView(root);
        hub.add(this);
    }

    @Override public void onState(int state, String info) { }

    @Override public void onMessage(JSONObject m) {
        String t = m.optString("t");
        if ("ext".equals(kind)) {
            if ("mActive".equals(t)) h.post(() -> {
                boolean on = m.optBoolean("on");
                title.setText(on ? "Reproduciendo" : "En pausa");
                playBtn.setKind(on ? MediaIcon.PAUSE : MediaIcon.PLAY);
            });
            return;
        }
        if ("mEnd".equals(t) && kind.equals(m.optString("kind"))) { h.post(this::finish); return; }
        if (!"mState".equals(t) || !kind.equals(m.optString("kind"))) return;
        h.post(() -> {
            title.setText(m.optString("title"));
            sub.setText(m.optString("sub"));
            boolean playing = m.optBoolean("play");
            if (MediaHub.VIDEO.equals(kind)) {
                dur = m.optLong("dur");
                long pos = m.optLong("pos");
                durText.setText(MediaHub.fmt(dur));
                if (!dragging) { posText.setText(MediaHub.fmt(pos)); seek.setProgress(dur > 0 ? (int) (pos * 1000 / dur) : 0); }
                playBtn.setKind(playing ? MediaIcon.PAUSE : MediaIcon.PLAY);
            } else if (slideBtn != null) {
                slideBtn.setText(playing ? "Detener" : "Presentación");
            }
        });
    }
}
