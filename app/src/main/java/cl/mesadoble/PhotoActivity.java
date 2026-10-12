package cl.mesadoble;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.ExifInterface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Galería de fotos del principal, al estilo de la galería de Android 2.3 (Gingerbread): fondo negro,
 * barra superior translúcida con el nombre, botones de zoom − / + redondos y "Presentación" abajo.
 * Deslizar cambia de foto; pellizcar o tocar dos veces acerca. Se controla también desde el extendido.
 */
public class PhotoActivity extends Activity implements MediaHub.Controller {

    private final Handler h = new Handler(Looper.getMainLooper());
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private PhotoView photo;
    private View topBar, bottomBar;
    private TextView name, counter;
    private TextView slideBtn;
    private ArrayList<String> list = new ArrayList<>();
    private int idx;
    private boolean slideshow;

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    static int exifRotation(String path) {
        try {
            int o = new ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            return o == ExifInterface.ORIENTATION_ROTATE_90 ? 90 : o == ExifInterface.ORIENTATION_ROTATE_180 ? 180 : o == ExifInterface.ORIENTATION_ROTATE_270 ? 270 : 0;
        } catch (Exception e) { return 0; }
    }

    /** Botón gris con degradado, como los de Gingerbread. */
    private TextView gbButton(String text, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(15);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(18), 0, dp(18), 0);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xCC5A5A5A, 0xCC2A2A2A});
        g.setCornerRadius(dp(6));
        g.setStroke(dp(1), 0x88999999);
        t.setBackground(g);
        t.setOnClickListener(l);
        return t;
    }

    /** Botón de zoom redondo gris, como el ZoomButtonsController de Android 2.x. */
    private TextView zoomButton(String text, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(26);
        t.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xDD6E6E6E, 0xDD303030});
        g.setShape(GradientDrawable.OVAL);
        g.setStroke(dp(2), 0xAAB0B0B0);
        t.setBackground(g);
        t.setOnClickListener(l);
        return t;
    }

    @SuppressWarnings("deprecation")
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        MainActivity.applyOrientation(this);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        photo = new PhotoView(this);
        root.addView(photo, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout top = new LinearLayout(this);
        topBar = top;
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(12), dp(10), dp(12), dp(10));
        top.setBackgroundColor(0xB0000000);
        name = new TextView(this);
        name.setTextColor(Color.WHITE);
        name.setTextSize(16);
        name.setSingleLine(true);
        top.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        counter = new TextView(this);
        counter.setTextColor(0xFFBBBBBB);
        counter.setTextSize(14);
        counter.setPadding(dp(12), 0, dp(12), 0);
        top.addView(counter);
        top.addView(gbButton("Cerrar", v -> finish()), new LinearLayout.LayoutParams(-2, dp(36)));
        root.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        FrameLayout bottom = new FrameLayout(this);
        bottomBar = bottom;
        bottom.setPadding(dp(14), dp(10), dp(14), dp(14));
        slideBtn = gbButton("Presentación", v -> cmd("slide"));
        bottom.addView(slideBtn, new FrameLayout.LayoutParams(-2, dp(42), Gravity.START | Gravity.BOTTOM));
        LinearLayout zoom = new LinearLayout(this);
        zoom.addView(zoomButton("−", v -> cmd("zout")), new LinearLayout.LayoutParams(dp(52), dp(52)));
        View gap = new View(this);
        zoom.addView(gap, new LinearLayout.LayoutParams(dp(14), 1));
        zoom.addView(zoomButton("+", v -> cmd("zin")), new LinearLayout.LayoutParams(dp(52), dp(52)));
        bottom.addView(zoom, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM));
        bottom.addView(gbButton("Girar", v -> cmd("rotate")), new FrameLayout.LayoutParams(-2, dp(42), Gravity.END | Gravity.BOTTOM));
        root.addView(bottom, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        setContentView(root);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        handle(getIntent());
    }

    @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); handle(i); }

    private void handle(Intent i) {
        ArrayList<String> p = i.getStringArrayListExtra("paths");
        if (p == null || p.isEmpty()) { finish(); return; }
        list = p;
        show(Math.max(0, Math.min(p.size() - 1, i.getIntExtra("idx", 0))), 0);
        showBars();
    }

    /** Carga una foto (ajustada a la pantalla) y la hace entrar deslizando desde dir (-1 izquierda, 1 derecha, 0 directo). */
    private void show(int i, int dir) {
        idx = i;
        String path = list.get(idx);
        name.setText(MediaHub.title(path));
        counter.setText((idx + 1) + " / " + list.size());
        int max = Math.max(getResources().getDisplayMetrics().widthPixels, getResources().getDisplayMetrics().heightPixels);
        decoder.execute(() -> {
            Bitmap bmp = null;
            try {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(path, o);
                int sample = 1;
                while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= max) sample *= 2;
                o = new BitmapFactory.Options();
                o.inSampleSize = sample;
                bmp = BitmapFactory.decodeFile(path, o);
                int rot = exifRotation(path);
                if (bmp != null && rot != 0) {
                    Matrix m = new Matrix();
                    m.postRotate(rot);
                    bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
                }
            } catch (Throwable ignored) { }
            final Bitmap fb = bmp;
            h.post(() -> { if (!isFinishing()) { photo.set(fb, dir); publish(); } });
        });
        publish();
    }

    @Override protected void onResume() {
        super.onResume();
        MediaHub.current = this;
        MediaHub.currentKind = MediaHub.IMAGE;
    }

    @Override protected void onPause() { if (isFinishing()) closeAll(); super.onPause(); }
    @Override protected void onDestroy() { closeAll(); decoder.shutdownNow(); super.onDestroy(); }

    private boolean closed;
    private void closeAll() {
        if (closed) return;
        closed = true;
        h.removeCallbacksAndMessages(null);
        if (MediaHub.current == this) MediaHub.current = null;
        MediaHub.end(this, MediaHub.IMAGE);
    }

    private final Runnable hideBars = () -> { topBar.animate().alpha(0).setDuration(250).start(); bottomBar.animate().alpha(0).setDuration(250).start(); };
    private void showBars() {
        topBar.animate().alpha(1).setDuration(150).start();
        bottomBar.animate().alpha(1).setDuration(150).start();
        h.removeCallbacks(hideBars);
        h.postDelayed(hideBars, 3500);
    }
    private void toggleBars() { if (topBar.getAlpha() > 0.5f) { h.removeCallbacks(hideBars); hideBars.run(); } else showBars(); }

    private final Runnable slideStep = new Runnable() {
        @Override public void run() {
            if (!slideshow) return;
            show(idx + 1 < list.size() ? idx + 1 : 0, 1);
            h.postDelayed(this, 3500);
        }
    };

    private void publish() {
        JSONObject s = new JSONObject();
        try {
            s.put("kind", MediaHub.IMAGE); s.put("title", MediaHub.title(list.get(idx))); s.put("sub", (idx + 1) + " de " + list.size());
            s.put("play", slideshow); s.put("idx", idx); s.put("n", list.size());
        } catch (Exception ignored) { }
        MediaHub.state(this, s);
    }

    private void cmd(String a) {
        JSONObject m = new JSONObject();
        try { m.put("a", a); } catch (Exception ignored) { }
        command(m);
        showBars();
    }

    @Override public void command(JSONObject m) {
        switch (m.optString("a")) {
            case "next": if (idx + 1 < list.size()) show(idx + 1, 1); break;
            case "prev": if (idx > 0) show(idx - 1, -1); break;
            case "toggle": case "slide":
                slideshow = !slideshow;
                slideBtn.setText(slideshow ? "Detener" : "Presentación");
                h.removeCallbacks(slideStep);
                if (slideshow) h.postDelayed(slideStep, 2500);
                break;
            case "rotate": photo.rotate(); break;
            case "zin": photo.zoom(1.5f); break;
            case "zout": photo.zoom(1 / 1.5f); break;
            case "stop": finish(); return;
        }
        publish();
    }

    // ---------------- la foto: zoom, desplazamiento y deslizar para cambiar ----------------

    private class PhotoView extends View {
        private Bitmap bmp, old;
        private final Matrix m = new Matrix();
        private float scale = 1, tx, ty, rot;   // zoom y desplazamiento sobre el ajuste a la pantalla
        private float slide, oldSlide;           // animación al cambiar de foto
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG), shade = new Paint();
        private final ScaleGestureDetector sg;
        private final GestureDetector gd;

        PhotoView(Context c) {
            super(c);
            sg = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override public boolean onScale(ScaleGestureDetector d) { scale = Math.max(1, Math.min(6, scale * d.getScaleFactor())); clampPan(); invalidate(); return true; }
            });
            gd = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
                @Override public boolean onSingleTapConfirmed(MotionEvent e) { toggleBars(); return true; }
                @Override public boolean onDoubleTap(MotionEvent e) { animateZoom(scale > 1.05f ? 1 : 2.5f); return true; }
                @Override public boolean onScroll(MotionEvent a, MotionEvent b, float dx, float dy) {
                    if (scale > 1.01f) { tx -= dx; ty -= dy; clampPan(); invalidate(); }
                    return true;
                }
                @Override public boolean onFling(MotionEvent a, MotionEvent b, float vx, float vy) {
                    if (scale > 1.01f || Math.abs(vx) < Math.abs(vy) * 1.2f || Math.abs(vx) < 600) return false;
                    if (vx < 0 && idx + 1 < list.size()) show(idx + 1, 1);
                    else if (vx > 0 && idx > 0) show(idx - 1, -1);
                    return true;
                }
            });
        }

        void set(Bitmap b, int dir) {
            old = dir != 0 ? bmp : null;
            bmp = b;
            scale = 1; tx = 0; ty = 0; rot = 0;
            if (dir != 0) {
                ValueAnimator a = ValueAnimator.ofFloat(dir * getWidth(), 0);
                a.setDuration(280);
                a.setInterpolator(new DecelerateInterpolator());
                a.addUpdateListener(v -> { slide = (float) v.getAnimatedValue(); oldSlide = slide - dir * getWidth(); invalidate(); });
                a.start();
            } else { slide = 0; invalidate(); }
        }

        void rotate() { rot = (rot + 90) % 360; scale = 1; tx = ty = 0; invalidate(); }
        void zoom(float k) { animateZoom(Math.max(1, Math.min(6, scale * k))); }

        private void animateZoom(float to) {
            ValueAnimator a = ValueAnimator.ofFloat(scale, to);
            a.setDuration(200);
            a.addUpdateListener(v -> { scale = (float) v.getAnimatedValue(); if (scale <= 1.001f) { tx = ty = 0; } clampPan(); invalidate(); });
            a.start();
        }

        private void clampPan() {
            if (bmp == null) return;
            float maxX = getWidth() * (scale - 1) / 2 + 40, maxY = getHeight() * (scale - 1) / 2 + 40;
            tx = Math.max(-maxX, Math.min(maxX, tx));
            ty = Math.max(-maxY, Math.min(maxY, ty));
        }

        private void drawBmp(Canvas c, Bitmap b, float offX, float sc, float r, float px, float py) {
            if (b == null) return;
            int W = getWidth(), H = getHeight();
            boolean side = ((int) r / 90) % 2 == 1;
            float bw = side ? b.getHeight() : b.getWidth(), bh = side ? b.getWidth() : b.getHeight();
            float fit = Math.min(W / bw, H / bh);
            m.reset();
            m.postTranslate(-b.getWidth() / 2f, -b.getHeight() / 2f);
            m.postRotate(r);
            m.postScale(fit * sc, fit * sc);
            m.postTranslate(W / 2f + px + offX, H / 2f + py);
            c.drawBitmap(b, m, paint);
        }

        @Override protected void onDraw(Canvas c) {
            if (old != null && slide != 0) drawBmp(c, old, oldSlide, 1, 0, 0, 0);
            if (bmp == null) {
                Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
                t.setColor(0xFF888888); t.setTextSize(dp(15)); t.setTextAlign(Paint.Align.CENTER);
                c.drawText("Cargando…", getWidth() / 2f, getHeight() / 2f, t);
                return;
            }
            drawBmp(c, bmp, slide, scale, rot, tx, ty);
            // brillo suave abajo, como el reflejo de la galería de 2.3
            shade.setShader(new LinearGradient(0, getHeight() * 0.8f, 0, getHeight(), 0x00000000, 0x66000000, Shader.TileMode.CLAMP));
            c.drawRect(new RectF(0, getHeight() * 0.8f, getWidth(), getHeight()), shade);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            sg.onTouchEvent(e);
            if (!sg.isInProgress()) gd.onTouchEvent(e);
            return true;
        }
    }
}
