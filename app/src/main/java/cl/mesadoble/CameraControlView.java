package cl.mesadoble;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.Locale;

/**
 * Controles de la cámara del PRINCIPAL, como modo del teclado en el extendido: se ve lo que ve la cámara,
 * se cambia entre trasera y delantera, se saca la foto, flash y zoom.
 */
public class CameraControlView extends LinearLayout {

    private final Hub hub;
    private final ImageView view;
    private final TextView status, switchBtn, flashBtn, openBtn, note;
    private final View shutter;
    private final SeekBar zoomBar;
    private final LinearLayout controls;
    private boolean open, front, hasFlash, userZoom;
    private int flash;
    private float maxZoom = 1;

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    public CameraControlView(Context c, Hub hub) {
        super(c);
        this.hub = hub;
        setOrientation(HORIZONTAL);
        setBackgroundColor(0xFF0B0B0D);
        setPadding(dp(10), dp(8), dp(10), dp(8));

        // izquierda: lo que ve la cámara
        FrameLayout left = new FrameLayout(c);
        view = new ImageView(c);
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        view.setBackgroundColor(0xFF000000);
        left.addView(view, new FrameLayout.LayoutParams(-1, -1));
        status = new TextView(c);
        status.setTextColor(Color.WHITE);
        status.setTextSize(13);
        status.setShadowLayer(dp(3), 0, 0, Color.BLACK);
        status.setPadding(dp(10), dp(6), dp(10), dp(6));
        left.addView(status, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START));
        openBtn = pill("📷  Abrir la cámara en el principal", 0xFF1F7F8C);
        openBtn.setOnClickListener(v -> cmd("open", 0));
        left.addView(openBtn, new FrameLayout.LayoutParams(-2, dp(52), Gravity.CENTER));
        addView(left, new LayoutParams(0, -1, 1.6f));

        // derecha: controles
        controls = new LinearLayout(c);
        controls.setOrientation(VERTICAL);
        controls.setGravity(Gravity.CENTER);
        controls.setPadding(dp(12), 0, dp(4), 0);

        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER);
        switchBtn = circle("🔄", 64);
        switchBtn.setOnClickListener(v -> cmd("switch", 0));
        shutter = new View(c);
        GradientDrawable sd = new GradientDrawable();
        sd.setShape(GradientDrawable.OVAL);
        sd.setColor(Color.WHITE);
        sd.setStroke(dp(6), 0xFF5A5A60);
        shutter.setBackground(sd);
        shutter.setOnClickListener(v -> { cmd("shot", 0); shutter.animate().scaleX(0.85f).scaleY(0.85f).setDuration(90).withEndAction(() -> shutter.animate().scaleX(1).scaleY(1).setDuration(120).start()).start(); });
        flashBtn = circle("⚡", 56);
        flashBtn.setOnClickListener(v -> cmd("flash", 0));
        LayoutParams a = new LayoutParams(dp(56), dp(56)); a.setMargins(dp(8), 0, dp(8), 0);
        LayoutParams b = new LayoutParams(dp(84), dp(84)); b.setMargins(dp(8), 0, dp(8), 0);
        LayoutParams s = new LayoutParams(dp(64), dp(64)); s.setMargins(dp(8), 0, dp(8), 0);
        row.addView(flashBtn, a);
        row.addView(shutter, b);
        row.addView(switchBtn, s);
        controls.addView(row);

        TextView zl = new TextView(c);
        zl.setText("Zoom");
        zl.setTextColor(0xFFAAAAAA);
        zl.setTextSize(12);
        LayoutParams zlp = new LayoutParams(-2, -2); zlp.topMargin = dp(16);
        controls.addView(zl, zlp);
        zoomBar = new SeekBar(c);
        zoomBar.setMax(100);
        zoomBar.getProgressDrawable().setColorFilter(0xFFFFC107, PorterDuff.Mode.SRC_IN);
        zoomBar.getThumb().setColorFilter(0xFFFFC107, PorterDuff.Mode.SRC_IN);
        zoomBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean u) { if (u) cmd("zoom", 1 + (maxZoom - 1) * p / 100.0); }
            @Override public void onStartTrackingTouch(SeekBar sb) { userZoom = true; }
            @Override public void onStopTrackingTouch(SeekBar sb) { userZoom = false; }
        });
        controls.addView(zoomBar, new LayoutParams(-1, -2));

        TextView close = pill("Cerrar cámara", 0xFF3A3A40);
        close.setOnClickListener(v -> cmd("close", 0));
        LayoutParams clp = new LayoutParams(-2, dp(42)); clp.topMargin = dp(14);
        controls.addView(close, clp);

        note = new TextView(c);
        note.setTextColor(0xFF8A8A90);
        note.setTextSize(11.5f);
        note.setGravity(Gravity.CENTER);
        LayoutParams np = new LayoutParams(-1, -2); np.topMargin = dp(10);
        controls.addView(note, np);
        addView(controls, new LayoutParams(0, -1, 1f));
        show(false);
    }

    /** En horizontal: vista a la izquierda y controles a la derecha; en vertical, uno sobre otro. */
    @Override protected void onMeasure(int ws, int hs) {
        boolean wide = MeasureSpec.getSize(ws) >= MeasureSpec.getSize(hs);
        if ((getOrientation() == HORIZONTAL) != wide) {
            setOrientation(wide ? HORIZONTAL : VERTICAL);
            View l = getChildAt(0), r = getChildAt(1);
            l.setLayoutParams(wide ? new LayoutParams(0, -1, 1.6f) : new LayoutParams(-1, 0, 1.4f));
            r.setLayoutParams(wide ? new LayoutParams(0, -1, 1f) : new LayoutParams(-1, 0, 1f));
        }
        super.onMeasure(ws, hs);
    }

    private TextView circle(String t, int size) {
        TextView v = new TextView(getContext());
        v.setText(t);
        v.setTextSize(size * 0.33f);
        v.setTextColor(Color.WHITE);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(0xFF2A2A30);
        v.setBackground(g);
        return v;
    }

    private TextView pill(String t, int color) {
        TextView v = new TextView(getContext());
        v.setText(t);
        v.setTextSize(15);
        v.setTextColor(Color.WHITE);
        v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(20), 0, dp(20), 0);
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(26));
        v.setBackground(g);
        return v;
    }

    private void cmd(String a, double v) {
        JSONObject m = new JSONObject();
        try { m.put("t", "camCmd"); m.put("a", a); m.put("v", v); if ("open".equals(a)) m.put("front", front); } catch (Exception ignored) { }
        if (hub.isPrincipal()) CameraActivity.command(getContext(), m);
        else if (hub.connected()) hub.send(m);
        else status.setText("Sin conexión con el principal");
    }

    private void show(boolean o) {
        open = o;
        openBtn.setVisibility(o ? GONE : VISIBLE);
        for (int i = 0; i < controls.getChildCount(); i++) controls.getChildAt(i).setEnabled(o);
        controls.setAlpha(o ? 1f : 0.4f);
        if (!o) { view.setImageBitmap(null); status.setText("Cámara del principal: cerrada"); }
        note.setText("Las fotos quedan en el principal: Imágenes › Mesa Doble");
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        sendStream(true);   // pedir la vista previa (y el estado)
    }

    @Override protected void onDetachedFromWindow() {
        sendStream(false);
        super.onDetachedFromWindow();
    }

    private void sendStream(boolean on) {
        JSONObject m = new JSONObject();
        try { m.put("t", "camCmd"); m.put("a", "stream"); m.put("v", on); } catch (Exception ignored) { }
        if (hub.isPrincipal()) CameraActivity.command(getContext(), m);
        else if (hub.connected()) hub.send(m);
    }

    /** Mensajes del principal. */
    public void onMessage(JSONObject m) {
        String t = m.optString("t");
        switch (t) {
            case "camState":
                post(() -> {
                    show(m.optBoolean("open"));
                    if (!open) return;
                    front = m.optBoolean("front");
                    flash = m.optInt("flash");
                    hasFlash = m.optBoolean("hasFlash");
                    maxZoom = (float) m.optDouble("max", 1);
                    float z = (float) m.optDouble("zoom", 1);
                    status.setText((front ? "Cámara delantera" : "Cámara trasera") + String.format(Locale.ROOT, "  ·  %.1f×", z));
                    switchBtn.setText(front ? "🔄\nTras." : "🔄\nDel.");
                    switchBtn.setTextSize(13);
                    flashBtn.setText(flash == 0 ? "⚡✕" : flash == 1 ? "⚡A" : "⚡");
                    flashBtn.setAlpha(hasFlash ? 1f : 0.35f);
                    zoomBar.setEnabled(maxZoom > 1.01f);
                    if (!userZoom) zoomBar.setProgress(maxZoom > 1.01f ? Math.round((z - 1) * 100 / (maxZoom - 1)) : 0);
                });
                break;
            case "camOpen":
                post(() -> { show(true); sendStream(true); });
                break;
            case "camFrame":
                try {
                    byte[] b = Base64.decode(m.optString("j"), Base64.DEFAULT);
                    Bitmap bmp = BitmapFactory.decodeByteArray(b, 0, b.length);
                    if (bmp != null) post(() -> { if (!open) show(true); view.setImageBitmap(bmp); });
                } catch (Exception ignored) { }
                break;
            case "camShot":
                post(() -> status.setText(m.optBoolean("ok") ? "📸 Foto guardada en el principal" : "No se pudo guardar la foto"));
                break;
        }
    }
}
