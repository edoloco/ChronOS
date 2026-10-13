package cl.mesadoble;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Columna del Modo APP en el extendido: muestra derecha la app del principal que quedó de lado (Instagram…).
 * Los toques se mandan al principal como gestos (con la versión completa; la liviana solo muestra).
 */
public class AppCastView extends FrameLayout implements SurfaceHolder.Callback {

    private final Hub hub;
    private final SurfaceView sv;
    private float downX, downY;
    private long downAt;
    private final JSONArray pts = new JSONArray();
    private static boolean warned;

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    public AppCastView(Context c, Hub hub) {
        super(c);
        this.hub = hub;
        setBackgroundColor(Color.BLACK);
        sv = new SurfaceView(c);
        sv.getHolder().addCallback(this);
        addView(sv, new LayoutParams(-1, -1));
        setClipToOutline(true);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(14)); }
        });
        setElevation(dp(10));
        // botón para cerrar el Modo APP
        TextView close = new TextView(c);
        close.setText("✕");
        close.setTextSize(14);
        close.setTextColor(Color.WHITE);
        close.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(0x88000000);
        close.setBackground(g);
        close.setOnClickListener(v -> hub.send("appMode", "on", false));
        LayoutParams cl = new LayoutParams(dp(30), dp(30), Gravity.TOP | Gravity.START);
        cl.setMargins(dp(6), dp(6), 0, 0);
        addView(close, cl);
        setVisibility(GONE);
    }

    /** Tamaño de la columna dentro de un área (alto disponible manda; si la app va acostada, manda el ancho). */
    public void place(int areaW, int areaH, int top, int bottom, int end) {
        int w = ScreenCast.showW, h = ScreenCast.showH;
        if (!ScreenCast.shown || w <= 0 || h <= 0 || areaW <= 0 || areaH <= 0) { setVisibility(GONE); return; }
        int avail = areaH - top - bottom;
        int cw = Math.round(avail * (float) w / h), chh = avail;
        if (cw > areaW * 0.72f) { cw = Math.round(areaW * 0.72f); chh = Math.round(cw * (float) h / w); }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(cw, chh, Gravity.TOP | Gravity.END);
        lp.setMargins(0, top, end, 0);
        setLayoutParams(lp);
        if (getVisibility() != VISIBLE) {
            setVisibility(VISIBLE);
            setAlpha(0f);
            setTranslationX(dp(40));
            animate().alpha(1f).translationX(0).setDuration(260).start();
        }
    }

    @Override public void surfaceCreated(SurfaceHolder h) { ScreenCast.setSurface(h.getSurface(), hub); }
    @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) { }
    @Override public void surfaceDestroyed(SurfaceHolder h) { ScreenCast.clearSurface(h.getSurface()); }

    // ---------------- toques → gestos en el principal ----------------

    @Override public boolean onInterceptTouchEvent(MotionEvent e) {
        // el botón de cerrar recibe su toque; el resto es para la app
        return e.getX() > dp(42) || e.getY() > dp(42);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        float w = Math.max(1, getWidth()), h = Math.max(1, getHeight());
        float x = Math.max(0, Math.min(1, e.getX() / w)), y = Math.max(0, Math.min(1, e.getY() / h));
        try {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    while (pts.length() > 0) pts.remove(0);
                    downAt = e.getEventTime();
                    downX = x; downY = y;
                    pts.put((double) x).put((double) y);
                    break;
                case MotionEvent.ACTION_MOVE:
                    // un punto cada tanto (no hace falta mandar todos)
                    int n = pts.length();
                    double lx = pts.getDouble(n - 2), ly = pts.getDouble(n - 1);
                    if (Math.hypot(x - lx, y - ly) > 0.015 && n < 60) pts.put((double) x).put((double) y);
                    break;
                case MotionEvent.ACTION_UP: {
                    pts.put((double) x).put((double) y);
                    long ms = Math.max(30, Math.min(4000, e.getEventTime() - downAt));
                    JSONObject m = new JSONObject();
                    m.put("t", "appTouch");
                    m.put("p", new JSONArray(pts.toString()));
                    m.put("ms", ms);
                    hub.send(m);
                    break;
                }
                default: break;
            }
        } catch (Exception ignored) { }
        return true;
    }

    /** El principal no puede recibir toques (versión liviana): se avisa una vez. */
    public static void noTouch(Context c) {
        if (warned) return;
        warned = true;
        Toast.makeText(c, "Para tocar la app desde aquí, el principal necesita la versión completa. Puedes tocarla en el principal.", Toast.LENGTH_LONG).show();
    }
}
