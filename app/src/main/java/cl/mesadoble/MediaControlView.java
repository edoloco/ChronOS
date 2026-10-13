package cl.mesadoble;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import org.json.JSONObject;

/**
 * Controlador de video (y música) del principal, como modo del teclado en el extendido.
 * Con el reproductor de ChronOS muestra título y barra de avance; con otras apps (YouTube, Netflix…)
 * envía las teclas de reproducción.
 */
public class MediaControlView extends LinearLayout {

    private static final int RED = 0xFFFF0033, CARD = 0xFF212121;
    private final Hub hub;
    private final TextView label, title, sub, posText, durText, note;
    private final LinearLayout seekRow;
    private final SeekBar seek;
    private final MediaIcon playBtn;
    private boolean dragging;
    private long dur, lastOwn;   // última vez que llegó el estado del reproductor propio
    private String ownKind = "";

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    public MediaControlView(Context c, Hub hub) {
        super(c);
        this.hub = hub;
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER);
        setBackgroundColor(0xFF0F0F0F);
        setPadding(dp(18), dp(10), dp(18), dp(10));

        label = new TextView(c);
        label.setTextColor(RED);
        label.setTextSize(11);
        label.setLetterSpacing(0.15f);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setGravity(Gravity.CENTER);
        addView(label);
        title = new TextView(c);
        title.setTextColor(Color.WHITE);
        title.setTextSize(18);
        title.setGravity(Gravity.CENTER);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        addView(title);
        sub = new TextView(c);
        sub.setTextColor(0xFFAAAAAA);
        sub.setTextSize(13);
        sub.setGravity(Gravity.CENTER);
        addView(sub);

        seekRow = new LinearLayout(c);
        seekRow.setGravity(Gravity.CENTER_VERTICAL);
        posText = new TextView(c); posText.setTextColor(0xFFDDDDDD); posText.setTextSize(13);
        durText = new TextView(c); durText.setTextColor(0xFFDDDDDD); durText.setTextSize(13);
        seek = new SeekBar(c);
        seek.setMax(1000);
        seek.getProgressDrawable().setColorFilter(RED, PorterDuff.Mode.SRC_IN);
        seek.getThumb().setColorFilter(RED, PorterDuff.Mode.SRC_IN);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) { if (u) posText.setText(MediaHub.fmt(p * dur / 1000)); }
            @Override public void onStartTrackingTouch(SeekBar s) { dragging = true; }
            @Override public void onStopTrackingTouch(SeekBar s) { dragging = false; MediaHub.cmd(getContext(), "seek", (double) s.getProgress() * dur / 1000); }
        });
        seekRow.addView(posText);
        seekRow.addView(seek, new LayoutParams(0, -2, 1));
        seekRow.addView(durText);
        LayoutParams sl = new LayoutParams(-1, -2);
        sl.setMargins(0, dp(10), 0, 0);
        addView(seekRow, sl);

        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER);
        row.setPadding(0, dp(10), 0, dp(6));
        MediaIcon prev = new MediaIcon(c, MediaIcon.PREV, 50, CARD, Color.WHITE);
        MediaIcon back = new MediaIcon(c, MediaIcon.BACK10, 54, CARD, Color.WHITE);
        playBtn = new MediaIcon(c, MediaIcon.PAUSE, 74, RED, Color.WHITE);
        MediaIcon fwd = new MediaIcon(c, MediaIcon.FWD10, 54, CARD, Color.WHITE);
        MediaIcon next = new MediaIcon(c, MediaIcon.NEXT, 50, CARD, Color.WHITE);
        MediaIcon vd = new MediaIcon(c, MediaIcon.VOL_DOWN, 46, CARD, Color.WHITE);
        MediaIcon vu = new MediaIcon(c, MediaIcon.VOL_UP, 46, CARD, Color.WHITE);
        prev.setOnClickListener(v -> act("prev", "prev", 0));
        back.setOnClickListener(v -> act("rel", "rw", -10000));
        playBtn.setOnClickListener(v -> act("toggle", "play", 0));
        fwd.setOnClickListener(v -> act("rel", "ff", 10000));
        next.setOnClickListener(v -> act("next", "next", 0));
        vd.setOnClickListener(v -> act("vol", "vdown", -1));
        vu.setOnClickListener(v -> act("vol", "vup", 1));
        for (View v : new View[]{vd, prev, back, playBtn, fwd, next, vu}) {
            LayoutParams lp = new LayoutParams(-2, -2);
            lp.setMargins(dp(5), 0, dp(5), 0);
            row.addView(v, lp);
        }
        addView(row);

        note = new TextView(c);
        note.setTextColor(0xFF777777);
        note.setTextSize(11.5f);
        note.setGravity(Gravity.CENTER);
        note.setText("La barra de avance aparece con los videos que abres desde Archivos (reproductor de ChronOS).");
        addView(note);
        showExternal(false);
    }

    /** ¿El reproductor propio está activo? (si no, es otra app y se usan teclas multimedia) */
    private boolean own() { return SystemClock.uptimeMillis() - lastOwn < 3000; }

    private void act(String cmd, String key, double v) {
        if (own()) MediaHub.cmd(getContext(), cmd, v);
        else if (hub.isPrincipal()) hub.media(key);   // abierto en el mismo principal
        else if (hub.connected()) hub.send("media", "k", key);
    }

    private void showExternal(boolean playing) {
        label.setText("▶  VIDEO EN EL PRINCIPAL");
        title.setText(playing ? "Reproduciendo" : "Controles del principal");
        sub.setText("Play/pausa, adelantar y volumen de lo que se ve en el principal");
        seekRow.setVisibility(GONE);
        note.setVisibility(VISIBLE);
        playBtn.setKind(playing ? MediaIcon.PAUSE : MediaIcon.PLAY);
    }

    /** Mensajes del principal: estado del reproductor propio o de otras apps. */
    public void onMessage(JSONObject m) {
        String t = m.optString("t");
        if ("mState".equals(t)) {
            String k = m.optString("kind");
            if (!MediaHub.VIDEO.equals(k) && !MediaHub.AUDIO.equals(k)) return;
            post(() -> {
                lastOwn = SystemClock.uptimeMillis();
                ownKind = k;
                label.setText(MediaHub.VIDEO.equals(k) ? "▶  VIDEO EN EL PRINCIPAL" : "♪  MÚSICA EN EL PRINCIPAL");
                title.setText(m.optString("title"));
                sub.setText(m.optString("sub"));
                dur = m.optLong("dur");
                long pos = m.optLong("pos");
                seekRow.setVisibility(VISIBLE);
                note.setVisibility(GONE);
                durText.setText(MediaHub.fmt(dur));
                if (!dragging) { posText.setText(MediaHub.fmt(pos)); seek.setProgress(dur > 0 ? (int) (pos * 1000 / dur) : 0); }
                playBtn.setKind(m.optBoolean("play") ? MediaIcon.PAUSE : MediaIcon.PLAY);
            });
        } else if ("mEnd".equals(t)) {
            post(() -> { lastOwn = 0; showExternal(false); });
        } else if ("mActive".equals(t) && !own()) {
            post(() -> showExternal(m.optBoolean("on")));
        }
    }
}
