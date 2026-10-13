package cl.mesadoble;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Size;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Locale;

/**
 * Cámara de ChronOS, en el PRINCIPAL. Se controla desde el extendido (modo "Cámara" del teclado):
 * cambiar entre trasera y delantera, sacar foto, flash y zoom; el extendido ve lo que ve la cámara.
 *   camCmd   (extendido → principal) {a: open|switch|shot|flash|zoom(v)|close|stream(v)}
 *   camState (principal → extendido) {open, front, flash, zoom, max}
 *   camFrame (principal → extendido) {j: JPEG pequeño en base64}
 *   camShot  (principal → extendido) {ok, name}
 *   camOpen  (principal → extendido) se abrió la cámara: abrir los controles
 */
public class CameraActivity extends Activity implements TextureView.SurfaceTextureListener {

    static CameraActivity current;
    static boolean streaming;      // el extendido está mirando: se mandan cuadros de la vista previa

    private final Handler main = new Handler(Looper.getMainLooper());
    private HandlerThread bgThread;
    private Handler bg;
    private TextureView tv;
    private FrameLayout root;
    private View flashFx;
    private TextView flashBtn, zoomText, info;
    private Hub hub;

    private CameraManager cm;
    private CameraDevice dev;
    private CameraCaptureSession ses;
    private ImageReader reader;
    private CaptureRequest.Builder previewReq;
    private Size previewSize;
    private Rect activeArray;
    private int sensorOrientation;
    private boolean front, opening, hasFlash;
    private int flash;             // 0 = apagado, 1 = automático, 2 = encendido
    private float zoom = 1f, maxZoom = 1f;

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    // ---------------- órdenes (desde el extendido o desde aquí) ----------------

    /** En el principal: aplica una orden de la cámara. */
    static void command(Context c, JSONObject m) {
        String a = m.optString("a");
        CameraActivity cam = current;
        if ("stream".equals(a)) { streaming = m.optBoolean("v"); if (cam != null) cam.publish(); else sendClosed(c); return; }
        if ("open".equals(a)) {
            if (cam != null) { cam.publish(); return; }
            Hub.get(c).startOwn(new Intent(c, CameraActivity.class).putExtra("front", m.optBoolean("front")), () -> current != null,
                    47, "Cámara", "Toca para abrir la cámara");
            return;
        }
        if (cam == null) { sendClosed(c); return; }
        cam.main.post(() -> cam.apply(a, m.optDouble("v", 0)));
    }

    private static void sendClosed(Context c) {
        JSONObject s = new JSONObject();
        try { s.put("t", "camState"); s.put("open", false); } catch (JSONException ignored) { }
        Hub hub = Hub.get(c);
        if (hub.connected()) hub.send(s);
        hub.local(s);
    }

    private void apply(String a, double v) {
        switch (a) {
            case "switch": front = !front; getPreferences(MODE_PRIVATE).edit().putBoolean("front", front).apply(); reopen(); break;
            case "shot": shoot(); break;
            case "flash": flash = (flash + 1) % 3; updatePreview(); break;
            case "zoom": zoom = (float) Math.max(1, Math.min(maxZoom, v)); updatePreview(); break;
            case "close": finish(); return;
        }
        publish();
    }

    private void publish() {
        JSONObject s = new JSONObject();
        try {
            s.put("t", "camState"); s.put("open", true); s.put("front", front); s.put("flash", flash);
            s.put("hasFlash", hasFlash); s.put("zoom", (double) zoom); s.put("max", (double) maxZoom);
        } catch (JSONException ignored) { }
        if (hub.connected()) hub.send(s);
        hub.local(s);
        updateUi();
    }

    // ---------------- pantalla ----------------

    @SuppressWarnings("deprecation")
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        hub = Hub.get(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        MainActivity.applyOrientation(this);
        front = getIntent().hasExtra("front") ? getIntent().getBooleanExtra("front", false) : getPreferences(MODE_PRIVATE).getBoolean("front", false);
        cm = (CameraManager) getSystemService(CAMERA_SERVICE);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        tv = new TextureView(this);
        tv.setSurfaceTextureListener(this);
        root.addView(tv, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        flashFx = new View(this);
        flashFx.setBackgroundColor(Color.WHITE);
        flashFx.setAlpha(0);
        root.addView(flashFx, new FrameLayout.LayoutParams(-1, -1));

        info = new TextView(this);
        info.setTextColor(Color.WHITE);
        info.setTextSize(14);
        info.setGravity(Gravity.CENTER);
        info.setShadowLayer(dp(3), 0, 0, Color.BLACK);
        root.addView(info, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));

        // controles en el principal (los mismos que en el extendido)
        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setGravity(Gravity.CENTER);
        side.setPadding(dp(10), dp(10), dp(14), dp(10));
        TextView sw = round("🔄", 54, 0x66000000);
        sw.setOnClickListener(v -> apply("switch", 0));
        View shot = new View(this);
        GradientDrawable sd = new GradientDrawable();
        sd.setShape(GradientDrawable.OVAL);
        sd.setColor(Color.WHITE);
        sd.setStroke(dp(5), 0x88FFFFFF);
        shot.setBackground(sd);
        shot.setOnClickListener(v -> apply("shot", 0));
        flashBtn = round("⚡", 54, 0x66000000);
        flashBtn.setOnClickListener(v -> apply("flash", 0));
        zoomText = round("1×", 54, 0x66000000);
        zoomText.setOnClickListener(v -> apply("zoom", zoom >= maxZoom - 0.01f ? 1 : Math.min(maxZoom, zoom < 2 ? 2 : zoom * 2)));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(54), dp(54));
        sp.setMargins(0, dp(10), 0, dp(10));
        side.addView(flashBtn, sp);
        side.addView(sw, new LinearLayout.LayoutParams(sp));
        LinearLayout.LayoutParams shp = new LinearLayout.LayoutParams(dp(76), dp(76));
        shp.setMargins(0, dp(10), 0, dp(10));
        side.addView(shot, shp);
        side.addView(zoomText, new LinearLayout.LayoutParams(sp));
        root.addView(side, new FrameLayout.LayoutParams(-2, -1, Gravity.END | Gravity.CENTER_VERTICAL));
        TextView close = round("✕", 44, 0x66000000);
        close.setOnClickListener(v -> finish());
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP | Gravity.START);
        cl.setMargins(dp(12), dp(12), dp(12), dp(12));
        root.addView(close, cl);

        setContentView(root);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    private TextView round(String t, int sizeDp, int bgColor) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextColor(Color.WHITE);
        v.setTextSize(sizeDp * 0.36f);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(bgColor);
        g.setStroke(dp(1.5f), 0x66FFFFFF);
        v.setBackground(g);
        return v;
    }

    private void updateUi() {
        flashBtn.setText(flash == 0 ? "⚡✕" : flash == 1 ? "⚡A" : "⚡");
        flashBtn.setAlpha(hasFlash ? 1f : 0.35f);
        zoomText.setText(String.format(Locale.ROOT, zoom < 10 ? "%.1f×" : "%.0f×", zoom).replace(".0×", "×"));
    }

    @Override protected void onResume() {
        super.onResume();
        current = this;
        bgThread = new HandlerThread("camara");
        bgThread.start();
        bg = new Handler(bgThread.getLooper());
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, 51);
        } else if (tv.isAvailable()) open();
        if (hub.connected()) hub.send("camOpen");
        main.post(frames);
    }

    @Override public void onRequestPermissionsResult(int req, String[] p, int[] r) {
        if (req != 51) return;
        if (r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED) { if (tv.isAvailable()) open(); }
        else info.setText("Sin permiso de cámara");
    }

    @Override protected void onPause() {
        main.removeCallbacks(frames);
        closeCamera();
        if (bgThread != null) { bgThread.quitSafely(); bgThread = null; bg = null; }
        if (current == this) current = null;
        if (isFinishing()) sendClosed(this);
        super.onPause();
    }

    @Override protected void onDestroy() { if (current == this) current = null; super.onDestroy(); }

    @Override public void onSurfaceTextureAvailable(SurfaceTexture s, int w, int h) { if (current == this) open(); }
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture s, int w, int h) { transform(); }
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture s) { return true; }
    @Override public void onSurfaceTextureUpdated(SurfaceTexture s) { }

    // ---------------- cámara ----------------

    private String cameraId() throws CameraAccessException {
        String fallback = null;
        for (String id : cm.getCameraIdList()) {
            Integer f = cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
            if (f == null) continue;
            if (fallback == null) fallback = id;
            if (f == (front ? CameraCharacteristics.LENS_FACING_FRONT : CameraCharacteristics.LENS_FACING_BACK)) return id;
        }
        return fallback;
    }

    private static Size pick(Size[] sizes, int maxW) {
        Size best = null;
        for (Size s : sizes) {
            if (Math.abs(s.getWidth() * 3 - s.getHeight() * 4) > s.getHeight() / 10) continue;   // 4:3
            if (s.getWidth() > maxW) continue;
            if (best == null || s.getWidth() > best.getWidth()) best = s;
        }
        if (best == null) best = sizes[0];
        return best;
    }

    private void open() {
        if (dev != null || opening || bg == null) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        try {
            String id = cameraId();
            if (id == null) { info.setText("Este teléfono no tiene cámara disponible"); return; }
            CameraCharacteristics ch = cm.getCameraCharacteristics(id);
            Integer lf = ch.get(CameraCharacteristics.LENS_FACING);
            front = lf != null && lf == CameraCharacteristics.LENS_FACING_FRONT;
            StreamConfigurationMap map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Integer so = ch.get(CameraCharacteristics.SENSOR_ORIENTATION);
            sensorOrientation = so == null ? 90 : so;
            Float mz = ch.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
            maxZoom = mz == null ? 1f : Math.min(8f, mz);
            zoom = Math.min(zoom, maxZoom);
            activeArray = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            Boolean fl = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            hasFlash = fl != null && fl;
            previewSize = pick(map.getOutputSizes(SurfaceTexture.class), 1440);
            Size jpeg = pick(map.getOutputSizes(ImageFormat.JPEG), 4200);
            reader = ImageReader.newInstance(jpeg.getWidth(), jpeg.getHeight(), ImageFormat.JPEG, 2);
            reader.setOnImageAvailableListener(this::saved, bg);
            fitView();
            opening = true;
            info.setText("");
            cm.openCamera(id, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice d) { opening = false; dev = d; startSession(); }
                @Override public void onDisconnected(CameraDevice d) { opening = false; d.close(); dev = null; }
                @Override public void onError(CameraDevice d, int e) {
                    opening = false; d.close(); dev = null;
                    main.post(() -> info.setText("La cámara está ocupada por otra app"));
                }
            }, bg);
        } catch (Exception e) {
            opening = false;
            info.setText("No pude abrir la cámara");
        }
    }

    private void startSession() {
        try {
            SurfaceTexture st = tv.getSurfaceTexture();
            if (st == null || dev == null) return;
            st.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            Surface surface = new Surface(st);
            previewReq = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewReq.addTarget(surface);
            dev.createCaptureSession(Arrays.asList(surface, reader.getSurface()), new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession s) {
                    if (dev == null) return;
                    ses = s;
                    updatePreview();
                    main.post(() -> { transform(); publish(); });
                }
                @Override public void onConfigureFailed(CameraCaptureSession s) { main.post(() -> info.setText("No pude iniciar la cámara")); }
            }, bg);
        } catch (Exception e) { main.post(() -> info.setText("No pude iniciar la cámara")); }
    }

    private void setCommon(CaptureRequest.Builder r, boolean still) {
        r.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
        r.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        if (hasFlash && flash == 2 && still) r.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH);
        else if (hasFlash && flash == 1) r.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
        else {
            r.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            r.set(CaptureRequest.FLASH_MODE, hasFlash && flash == 2 && !still ? CaptureRequest.FLASH_MODE_OFF : CaptureRequest.FLASH_MODE_OFF);
        }
        if (activeArray != null && zoom > 1.01f) {
            int cw = Math.round(activeArray.width() / zoom), chh = Math.round(activeArray.height() / zoom);
            int x = (activeArray.width() - cw) / 2, y = (activeArray.height() - chh) / 2;
            r.set(CaptureRequest.SCALER_CROP_REGION, new Rect(x, y, x + cw, y + chh));
        } else if (activeArray != null) r.set(CaptureRequest.SCALER_CROP_REGION, new Rect(0, 0, activeArray.width(), activeArray.height()));
    }

    private void updatePreview() {
        if (ses == null || previewReq == null || bg == null) return;
        try {
            setCommon(previewReq, false);
            ses.setRepeatingRequest(previewReq.build(), null, bg);
        } catch (Exception ignored) { }
    }

    private int displayDegrees() {
        int r = getWindowManager().getDefaultDisplay().getRotation();
        return r == Surface.ROTATION_90 ? 90 : r == Surface.ROTATION_180 ? 180 : r == Surface.ROTATION_270 ? 270 : 0;
    }

    private void shoot() {
        if (ses == null || dev == null) { Toast.makeText(this, "La cámara aún no está lista", Toast.LENGTH_SHORT).show(); return; }
        try {
            CaptureRequest.Builder r = dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            r.addTarget(reader.getSurface());
            setCommon(r, true);
            int deg = displayDegrees();
            int jo = front ? (sensorOrientation + deg) % 360 : (sensorOrientation - deg + 360) % 360;
            r.set(CaptureRequest.JPEG_ORIENTATION, jo);
            r.set(CaptureRequest.JPEG_QUALITY, (byte) 92);
            ses.capture(r.build(), null, bg);
            flashFx.setAlpha(0.85f);
            flashFx.animate().alpha(0).setDuration(260).start();
        } catch (Exception e) { Toast.makeText(this, "No pude sacar la foto", Toast.LENGTH_SHORT).show(); }
    }

    /** Guarda la foto en Imágenes/ChronOS y avisa al extendido. */
    private void saved(ImageReader r) {
        Image img = null;
        String name = "MesaDoble_" + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(new java.util.Date()) + ".jpg";
        boolean ok = false;
        try {
            img = r.acquireNextImage();
            if (img == null) return;
            ByteBuffer buf = img.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[buf.remaining()];
            buf.get(bytes);
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            cv.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
            if (android.os.Build.VERSION.SDK_INT >= 29) cv.put("relative_path", "Pictures/ChronOS");
            Uri u = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
            if (u != null) try (OutputStream o = getContentResolver().openOutputStream(u)) { if (o != null) { o.write(bytes); ok = true; } }
        } catch (Exception ignored) {
        } finally { if (img != null) img.close(); }
        final boolean fok = ok;
        main.post(() -> {
            Toast.makeText(this, fok ? "Foto guardada en Imágenes › ChronOS" : "No pude guardar la foto", Toast.LENGTH_SHORT).show();
            JSONObject m = new JSONObject();
            try { m.put("t", "camShot"); m.put("ok", fok); m.put("name", name); } catch (JSONException ignored) { }
            if (hub.connected()) hub.send(m);
            hub.local(m);
        });
    }

    private void closeCamera() {
        try { if (ses != null) ses.close(); } catch (Exception ignored) { }
        ses = null;
        try { if (dev != null) dev.close(); } catch (Exception ignored) { }
        dev = null;
        try { if (reader != null) reader.close(); } catch (Exception ignored) { }
        reader = null;
        opening = false;
    }

    private void reopen() {
        closeCamera();
        main.postDelayed(this::open, 120);
    }

    // ---------------- vista previa: tamaño y giro ----------------

    /** La vista previa ocupa lo más posible sin deformarse. */
    private void fitView() {
        main.post(() -> {
            int W = root.getWidth(), H = root.getHeight();
            if (W == 0 || H == 0 || previewSize == null) return;
            boolean landscape = W > H;
            float aspect = landscape ? previewSize.getWidth() / (float) previewSize.getHeight()
                                     : previewSize.getHeight() / (float) previewSize.getWidth();
            int w = W, h = Math.round(W / aspect);
            if (h > H) { h = H; w = Math.round(H * aspect); }
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h, Gravity.CENTER);
            tv.setLayoutParams(lp);
            tv.post(this::transform);
        });
    }

    private void transform() {
        if (previewSize == null) return;
        int vw = tv.getWidth(), vh = tv.getHeight();
        if (vw == 0 || vh == 0) return;
        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        Matrix m = new Matrix();
        RectF view = new RectF(0, 0, vw, vh);
        RectF buffer = new RectF(0, 0, previewSize.getHeight(), previewSize.getWidth());
        float cx = view.centerX(), cy = view.centerY();
        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            buffer.offset(cx - buffer.centerX(), cy - buffer.centerY());
            m.setRectToRect(view, buffer, Matrix.ScaleToFit.FILL);
            float scale = Math.max((float) vh / previewSize.getHeight(), (float) vw / previewSize.getWidth());
            m.postScale(scale, scale, cx, cy);
            m.postRotate(90 * (rotation - 2), cx, cy);
        } else if (rotation == Surface.ROTATION_180) m.postRotate(180, cx, cy);
        tv.setTransform(m);
    }

    // ---------------- lo que ve la cámara, al extendido ----------------

    private final Runnable frames = new Runnable() {
        @Override public void run() {
            if (current != CameraActivity.this) return;
            if (streaming && hub.connected() && ses != null && tv.isAvailable()) {
                int w = 360, h = Math.max(1, Math.round(360f * tv.getHeight() / Math.max(1, tv.getWidth())));
                Bitmap b = null;
                try { b = tv.getBitmap(w, h); } catch (Exception ignored) { }
                final Bitmap fb = b;
                if (fb != null && bg != null) bg.post(() -> {
                    ByteArrayOutputStream o = new ByteArrayOutputStream();
                    fb.compress(Bitmap.CompressFormat.JPEG, 55, o);
                    fb.recycle();
                    hub.send("camFrame", "j", Base64.encodeToString(o.toByteArray(), Base64.NO_WRAP));
                });
            }
            main.postDelayed(this, 220);
        }
    };
}
