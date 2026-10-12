package cl.mesadoble;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.Random;

/**
 * Reproductor de música flotante, al estilo Winamp pero moderno: pantalla "LCD" con el título que corre,
 * tiempo grande y barras de espectro. Se arrastra por su franja superior y lo de atrás se puede seguir usando.
 * La música suena en el principal; esta ventana la controla (normalmente desde el extendido).
 */
public class PlayerActivity extends Activity implements Link.Listener {

    private static final int LCD = 0xFF06110F, LCD_TEXT = 0xFF5CF2C8, ACCENT = 0xFF5CC3CF;
    private final Handler h = new Handler(Looper.getMainLooper());
    private Hub hub;
    private TextView titleView, timeView, infoView;
    private SeekBar seek;
    private MediaIcon playBtn;
    private Spectrum spectrum;
    private boolean dragging, playing;
    private long dur;
    private float downRawX, downRawY;
    private int startX, startY;

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        hub = Hub.get(this);
        WindowManager.LayoutParams wl = getWindow().getAttributes();
        wl.width = Math.min(dp(360), getResources().getDisplayMetrics().widthPixels - dp(16));
        wl.height = WindowManager.LayoutParams.WRAP_CONTENT;
        wl.gravity = Gravity.TOP | Gravity.START;
        wl.x = getSharedPreferences("mesa", MODE_PRIVATE).getInt("ampX", dp(16));
        wl.y = getSharedPreferences("mesa", MODE_PRIVATE).getInt("ampY", dp(60));
        getWindow().setAttributes(wl);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);   // lo de atrás sigue respondiendo
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(10), dp(6), dp(10), dp(10));
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xF2232B36, 0xF2141A21});
        g.setCornerRadius(dp(18));
        g.setStroke(dp(1), 0x553A4A5C);
        card.setBackground(g);
        card.setElevation(dp(12));

        // franja para arrastrar: nombre, minimizar y cerrar
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView brand = new TextView(this);
        brand.setText("MESA DOBLE · AMP");
        brand.setTextColor(0xFF8FA3B5);
        brand.setTextSize(10);
        brand.setLetterSpacing(0.2f);
        brand.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        MediaIcon min = new MediaIcon(this, MediaIcon.MINIMIZE, 30, 0x00000000, 0xFFB0BEC5);
        min.setOnClickListener(v -> finish());
        MediaIcon close = new MediaIcon(this, MediaIcon.CLOSE, 30, 0x00000000, 0xFFB0BEC5);
        close.setOnClickListener(v -> { MediaHub.cmd(this, "stop", 0); finish(); });
        head.addView(min);
        head.addView(close);
        head.setOnTouchListener(this::drag);
        card.addView(head, new LinearLayout.LayoutParams(-1, dp(32)));

        // pantalla LCD
        FrameLayout lcd = new FrameLayout(this);
        GradientDrawable lg = new GradientDrawable();
        lg.setColor(LCD);
        lg.setCornerRadius(dp(10));
        lg.setStroke(dp(1), 0x334CF2C8);
        lcd.setBackground(lg);
        lcd.setPadding(dp(10), dp(6), dp(10), dp(6));
        LinearLayout lcol = new LinearLayout(this);
        lcol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        timeView = new TextView(this);
        timeView.setTextColor(LCD_TEXT);
        timeView.setTextSize(30);
        timeView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        timeView.setText("0:00");
        timeView.setShadowLayer(dp(6), 0, 0, 0x885CF2C8);
        row.addView(timeView, new LinearLayout.LayoutParams(0, -2, 1));
        spectrum = new Spectrum(this);
        row.addView(spectrum, new LinearLayout.LayoutParams(dp(120), dp(40)));
        lcol.addView(row);
        titleView = new TextView(this);
        titleView.setTextColor(LCD_TEXT);
        titleView.setTextSize(14);
        titleView.setTypeface(Typeface.MONOSPACE);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        titleView.setMarqueeRepeatLimit(-1);
        titleView.setHorizontallyScrolling(true);
        titleView.setSelected(true);
        titleView.setText("Esperando música…");
        lcol.addView(titleView);
        infoView = new TextView(this);
        infoView.setTextColor(0x995CF2C8);
        infoView.setTextSize(11);
        infoView.setTypeface(Typeface.MONOSPACE);
        lcol.addView(infoView);
        lcd.addView(lcol);
        card.addView(lcd);

        seek = new SeekBar(this);
        seek.setMax(1000);
        seek.getProgressDrawable().setColorFilter(ACCENT, PorterDuff.Mode.SRC_IN);
        seek.getThumb().setColorFilter(ACCENT, PorterDuff.Mode.SRC_IN);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) { if (u) timeView.setText(MediaHub.fmt(p * dur / 1000)); }
            @Override public void onStartTrackingTouch(SeekBar s) { dragging = true; }
            @Override public void onStopTrackingTouch(SeekBar s) { dragging = false; MediaHub.cmd(PlayerActivity.this, "seek", (double) s.getProgress() * dur / 1000); }
        });
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2);
        sl.setMargins(0, dp(8), 0, dp(4));
        card.addView(seek, sl);

        // botones
        LinearLayout btns = new LinearLayout(this);
        btns.setGravity(Gravity.CENTER);
        int bb = 0xFF2A3644;
        MediaIcon prev = new MediaIcon(this, MediaIcon.PREV, 44, bb, Color.WHITE);
        playBtn = new MediaIcon(this, MediaIcon.PLAY, 56, ACCENT, 0xFF0D1B22);
        MediaIcon stop = new MediaIcon(this, MediaIcon.STOP, 44, bb, Color.WHITE);
        MediaIcon next = new MediaIcon(this, MediaIcon.NEXT, 44, bb, Color.WHITE);
        MediaIcon vd = new MediaIcon(this, MediaIcon.VOL_DOWN, 36, 0x00000000, 0xFFB0BEC5);
        MediaIcon vu = new MediaIcon(this, MediaIcon.VOL_UP, 36, 0x00000000, 0xFFB0BEC5);
        prev.setOnClickListener(v -> MediaHub.cmd(this, "prev", 0));
        playBtn.setOnClickListener(v -> MediaHub.cmd(this, "toggle", 0));
        stop.setOnClickListener(v -> { MediaHub.cmd(this, "pause", 0); MediaHub.cmd(this, "seek", 0); });
        next.setOnClickListener(v -> MediaHub.cmd(this, "next", 0));
        vd.setOnClickListener(v -> MediaHub.cmd(this, "vol", -1));
        vu.setOnClickListener(v -> MediaHub.cmd(this, "vol", 1));
        for (View v : new View[]{vd, prev, playBtn, stop, next, vu}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMargins(dp(4), 0, dp(4), 0);
            btns.addView(v, lp);
        }
        card.addView(btns);

        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(dp(6), dp(6), dp(6), dp(10));
        wrap.addView(card);
        setContentView(wrap);
        hub.add(this);
    }

    @Override protected void onDestroy() { hub.remove(this); h.removeCallbacksAndMessages(null); super.onDestroy(); }

    /** Arrastrar la ventana por su franja superior. */
    private boolean drag(View v, MotionEvent e) {
        WindowManager.LayoutParams wl = getWindow().getAttributes();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: downRawX = e.getRawX(); downRawY = e.getRawY(); startX = wl.x; startY = wl.y; return true;
            case MotionEvent.ACTION_MOVE:
                wl.x = Math.max(0, startX + Math.round(e.getRawX() - downRawX));
                wl.y = Math.max(0, startY + Math.round(e.getRawY() - downRawY));
                getWindow().setAttributes(wl);
                return true;
            case MotionEvent.ACTION_UP:
                getSharedPreferences("mesa", MODE_PRIVATE).edit().putInt("ampX", wl.x).putInt("ampY", wl.y).apply();
                return true;
        }
        return true;
    }

    @Override public void onState(int state, String info) { }

    @Override public void onMessage(JSONObject m) {
        String t = m.optString("t");
        if ("mEnd".equals(t) && MediaHub.AUDIO.equals(m.optString("kind"))) { h.post(this::finish); return; }
        if (!"mState".equals(t) || !MediaHub.AUDIO.equals(m.optString("kind"))) return;
        h.post(() -> {
            playing = m.optBoolean("play");
            dur = m.optLong("dur");
            long pos = m.optLong("pos");
            String ti = m.optString("title");
            if (!ti.equals(titleView.getText().toString())) { titleView.setText(ti); titleView.setSelected(true); }
            infoView.setText(m.optString("sub") + "   " + (m.optInt("idx") + 1) + "/" + m.optInt("n") + "   " + MediaHub.fmt(dur));
            if (!dragging) {
                timeView.setText(MediaHub.fmt(pos));
                seek.setProgress(dur > 0 ? (int) (pos * 1000 / dur) : 0);
            }
            playBtn.setKind(playing ? MediaIcon.PAUSE : MediaIcon.PLAY);
            spectrum.setPlaying(playing);
        });
    }

    /** Barras de espectro decorativas (se mueven mientras suena). */
    private static class Spectrum extends View {
        private final float[] v = new float[16], target = new float[16];
        private final Random r = new Random();
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean playing;

        Spectrum(Context c) { super(c); }

        void setPlaying(boolean b) { if (playing != b) { playing = b; invalidate(); } }

        @Override protected void onDraw(Canvas c) {
            int n = v.length;
            float w = getWidth(), hh = getHeight(), bw = w / n;
            p.setShader(new LinearGradient(0, hh, 0, 0, new int[]{0xFF2BD9A8, 0xFFE8F25C, 0xFFFF6B4A}, null, Shader.TileMode.CLAMP));
            boolean moving = false;
            for (int i = 0; i < n; i++) {
                if (playing && r.nextInt(3) == 0) target[i] = 0.15f + r.nextFloat() * (0.85f - Math.abs(i - n / 3f) / n);
                if (!playing) target[i] = 0.04f;
                v[i] += (target[i] - v[i]) * 0.35f;
                if (Math.abs(target[i] - v[i]) > 0.01f) moving = true;
                float bh = Math.max(2, v[i] * hh);
                c.drawRoundRect(new RectF(i * bw + 1.5f, hh - bh, (i + 1) * bw - 1.5f, hh), 2, 2, p);
            }
            if (playing || moving) postInvalidateDelayed(60);
        }
    }
}
