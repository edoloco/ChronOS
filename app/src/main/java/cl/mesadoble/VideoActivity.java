package cl.mesadoble;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.VideoView;

import org.json.JSONObject;

import java.util.ArrayList;

/**
 * Reproductor de video en el principal, con aspecto tipo YouTube (oscuro, barra roja).
 * Toques: uno muestra/oculta los controles; dos a la izquierda o derecha retroceden o adelantan 10 s.
 * Los controles grandes están en el extendido (RemoteActivity).
 */
public class VideoActivity extends Activity implements MediaHub.Controller {

    private static final int RED = 0xFFFF0033;
    private final Handler h = new Handler(Looper.getMainLooper());
    private VideoView video;
    private View overlay;
    private TextView title, time;
    private SeekBar seek;
    private MediaIcon playBtn;
    private ArrayList<String> list = new ArrayList<>();
    private int idx;
    private boolean prepared, dragging;

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    @SuppressWarnings("deprecation")
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        MainActivity.applyOrientation(this);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        video = new VideoView(this);
        root.addView(video, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));

        FrameLayout ov = new FrameLayout(this);
        overlay = ov;
        ov.setBackgroundColor(0x66000000);
        // arriba: título
        title = new TextView(this);
        title.setTextColor(Color.WHITE);
        title.setTextSize(17);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setSingleLine(true);
        title.setPadding(dp(18), dp(14), dp(18), dp(14));
        ov.addView(title, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        // centro: anterior, retroceder, play/pausa, adelantar, siguiente
        LinearLayout mid = new LinearLayout(this);
        mid.setGravity(Gravity.CENTER);
        MediaIcon prev = new MediaIcon(this, MediaIcon.PREV, 48, 0x55000000, Color.WHITE);
        MediaIcon back = new MediaIcon(this, MediaIcon.BACK10, 52, 0x55000000, Color.WHITE);
        playBtn = new MediaIcon(this, MediaIcon.PAUSE, 72, 0x77000000, Color.WHITE);
        MediaIcon fwd = new MediaIcon(this, MediaIcon.FWD10, 52, 0x55000000, Color.WHITE);
        MediaIcon next = new MediaIcon(this, MediaIcon.NEXT, 48, 0x55000000, Color.WHITE);
        prev.setOnClickListener(v -> cmd("prev", 0));
        back.setOnClickListener(v -> cmd("rel", -10000));
        playBtn.setOnClickListener(v -> cmd("toggle", 0));
        fwd.setOnClickListener(v -> cmd("rel", 10000));
        next.setOnClickListener(v -> cmd("next", 0));
        for (View v : new View[]{prev, back, playBtn, fwd, next}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMargins(dp(14), 0, dp(14), 0);
            mid.addView(v, lp);
        }
        ov.addView(mid, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
        // abajo: tiempo y barra roja
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(dp(14), 0, dp(14), dp(10));
        time = new TextView(this);
        time.setTextColor(0xFFEEEEEE);
        time.setTextSize(13);
        bottom.addView(time);
        seek = new SeekBar(this);
        seek.setMax(1000);
        seek.getProgressDrawable().setColorFilter(RED, PorterDuff.Mode.SRC_IN);
        seek.getThumb().setColorFilter(RED, PorterDuff.Mode.SRC_IN);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) { if (user && prepared) time.setText(MediaHub.fmt((long) p * video.getDuration() / 1000) + " / " + MediaHub.fmt(video.getDuration())); }
            @Override public void onStartTrackingTouch(SeekBar s) { dragging = true; h.removeCallbacks(hide); }
            @Override public void onStopTrackingTouch(SeekBar s) { dragging = false; if (prepared) video.seekTo((int) ((long) s.getProgress() * video.getDuration() / 1000)); showControls(); }
        });
        bottom.addView(seek);
        ov.addView(bottom, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        MediaIcon close = new MediaIcon(this, MediaIcon.CLOSE, 40, 0x55000000, Color.WHITE);
        close.setOnClickListener(v -> finish());
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        cl.setMargins(dp(8), dp(8), dp(8), dp(8));
        ov.addView(close, cl);
        root.addView(ov, new FrameLayout.LayoutParams(-1, -1));

        GestureDetector gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onSingleTapConfirmed(MotionEvent e) { if (overlay.getVisibility() == View.VISIBLE) hideNow(); else showControls(); return true; }
            @Override public boolean onDoubleTap(MotionEvent e) { cmd("rel", e.getX() < root.getWidth() / 2f ? -10000 : 10000); return true; }
        });
        root.setOnTouchListener((v, e) -> { gd.onTouchEvent(e); return true; });
        ov.setOnTouchListener((v, e) -> { gd.onTouchEvent(e); return true; });

        setContentView(root);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);

        video.setOnPreparedListener(mp -> { prepared = true; video.start(); showControls(); tick.run(); });
        video.setOnCompletionListener(mp -> { if (idx + 1 < list.size()) load(idx + 1); else { playBtn.setKind(MediaIcon.PLAY); showControls(); publish(); } });
        video.setOnErrorListener((mp, w, e) -> { title.setText("No se puede reproducir este video"); return true; });
        handle(getIntent());
    }

    @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); handle(i); }

    private void handle(Intent i) {
        ArrayList<String> p = i.getStringArrayListExtra("paths");
        if (p == null || p.isEmpty()) { finish(); return; }
        list = p;
        load(Math.max(0, Math.min(p.size() - 1, i.getIntExtra("idx", 0))));
    }

    private void load(int i) {
        idx = i;
        prepared = false;
        title.setText(MediaHub.title(list.get(idx)) + (list.size() > 1 ? "   ·   " + (idx + 1) + " de " + list.size() : ""));
        video.setVideoPath(list.get(idx));
        playBtn.setKind(MediaIcon.PAUSE);
    }

    @Override protected void onResume() {
        super.onResume();
        MediaHub.current = this;
        MediaHub.currentKind = MediaHub.VIDEO;
    }

    @Override protected void onPause() {
        if (isFinishing()) closeAll();
        super.onPause();
    }

    @Override protected void onDestroy() { closeAll(); super.onDestroy(); }

    private boolean closed;
    private void closeAll() {
        if (closed) return;
        closed = true;
        h.removeCallbacksAndMessages(null);
        if (MediaHub.current == this) MediaHub.current = null;
        MediaHub.end(this, MediaHub.VIDEO);
    }

    private final Runnable hide = this::hideNow;
    private void hideNow() { if (!dragging) overlay.setVisibility(View.GONE); }
    private void showControls() {
        overlay.setVisibility(View.VISIBLE);
        h.removeCallbacks(hide);
        if (video.isPlaying()) h.postDelayed(hide, 3500);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (prepared && !dragging) {
                int d = Math.max(1, video.getDuration());
                seek.setProgress((int) ((long) video.getCurrentPosition() * 1000 / d));
                time.setText(MediaHub.fmt(video.getCurrentPosition()) + " / " + MediaHub.fmt(video.getDuration()));
            }
            publish();
            h.postDelayed(this, 500);
        }
    };

    private void publish() {
        JSONObject s = new JSONObject();
        try {
            s.put("kind", MediaHub.VIDEO); s.put("title", MediaHub.title(list.get(idx))); s.put("sub", list.size() > 1 ? (idx + 1) + " de " + list.size() : "");
            s.put("pos", prepared ? video.getCurrentPosition() : 0); s.put("dur", prepared ? video.getDuration() : 0);
            s.put("play", video.isPlaying()); s.put("idx", idx); s.put("n", list.size());
        } catch (Exception ignored) { }
        MediaHub.state(this, s);
    }

    private void cmd(String a, double v) {
        JSONObject m = new JSONObject();
        try { m.put("a", a); m.put("v", v); } catch (Exception ignored) { }
        command(m);
        showControls();
    }

    @Override public void command(JSONObject m) {
        String a = m.optString("a");
        double v = m.optDouble("v", 0);
        switch (a) {
            case "toggle": if (video.isPlaying()) video.pause(); else video.start(); break;
            case "play": video.start(); break;
            case "pause": video.pause(); break;
            case "seek": if (prepared) video.seekTo((int) v); break;
            case "rel": if (prepared) video.seekTo(Math.max(0, Math.min(video.getDuration(), video.getCurrentPosition() + (int) v))); break;
            case "next": if (idx + 1 < list.size()) load(idx + 1); break;
            case "prev": if (prepared && video.getCurrentPosition() > 3000) video.seekTo(0); else if (idx > 0) load(idx - 1); break;
            case "stop": finish(); return;
        }
        playBtn.setKind(video.isPlaying() || !prepared ? MediaIcon.PAUSE : MediaIcon.PLAY);
        publish();
    }
}
