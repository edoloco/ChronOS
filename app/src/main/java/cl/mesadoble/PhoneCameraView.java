package cl.mesadoble;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

/**
 * Controlador de la app de cámara DEL TELÉFONO principal, como modo del teclado en el extendido:
 * abrir en foto o video, cambiar trasera/delantera y disparar. Con la versión completa, también zoom y flash.
 */
public class PhoneCameraView extends LinearLayout {

    interface Host { void useMesaCamera(); }

    private final Hub hub;
    private final TextView status, faceBtn, note;
    private final LinearLayout extra;
    private boolean front;
    private Host host;

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    public PhoneCameraView(Context c, Hub hub) {
        super(c);
        this.hub = hub;
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER);
        setBackgroundColor(0xFF0B0B0D);
        setPadding(dp(16), dp(8), dp(16), dp(8));

        TextView label = new TextView(c);
        label.setText("📷  CÁMARA DEL PRINCIPAL");
        label.setTextColor(0xFFFFC107);
        label.setTextSize(11);
        label.setLetterSpacing(0.15f);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setGravity(Gravity.CENTER);
        addView(label);
        status = new TextView(c);
        status.setTextColor(Color.WHITE);
        status.setTextSize(16);
        status.setGravity(Gravity.CENTER);
        status.setText("Cámara del teléfono");
        addView(status);

        // fila principal: foto · cambiar cámara (grande) · disparar (grande) · video
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER);
        row.setPadding(0, dp(12), 0, dp(8));
        TextView photo = circle("📷\nFoto", 62, 0xFF2A2A30);
        photo.setOnClickListener(v -> cmd("photo"));
        faceBtn = circle("🔄\nDelantera", 86, 0xFF1F7F8C);
        faceBtn.setOnClickListener(v -> { front = !front; paint(); cmd("toggle"); });
        View shot = new View(c);
        GradientDrawable sd = new GradientDrawable();
        sd.setShape(GradientDrawable.OVAL);
        sd.setColor(Color.WHITE);
        sd.setStroke(dp(6), 0xFF5A5A60);
        shot.setBackground(sd);
        shot.setOnClickListener(v -> { cmd("shot"); shot.animate().scaleX(0.85f).scaleY(0.85f).setDuration(90)
                .withEndAction(() -> shot.animate().scaleX(1).scaleY(1).setDuration(120).start()).start(); });
        TextView video = circle("🎥\nVideo", 62, 0xFF2A2A30);
        video.setOnClickListener(v -> cmd("video"));
        add(row, photo, 62);
        add(row, faceBtn, 86);
        add(row, shot, 86);
        add(row, video, 62);
        addView(row);

        // solo con la versión completa (accesibilidad en el principal)
        extra = new LinearLayout(c);
        extra.setGravity(Gravity.CENTER);
        TextView zo = pill("Zoom −", 0xFF2A2A30), zi = pill("Zoom +", 0xFF2A2A30), fl = pill("⚡ Flash", 0xFF2A2A30);
        zo.setOnClickListener(v -> cmd("zoomout"));
        zi.setOnClickListener(v -> cmd("zoomin"));
        fl.setOnClickListener(v -> cmd("flash"));
        for (View v : new View[]{zo, zi, fl}) {
            LayoutParams lp = new LayoutParams(-2, dp(40));
            lp.setMargins(dp(5), 0, dp(5), 0);
            extra.addView(v, lp);
        }
        extra.setVisibility(GONE);
        addView(extra);

        note = new TextView(c);
        note.setTextColor(0xFF8A8A90);
        note.setTextSize(11.5f);
        note.setGravity(Gravity.CENTER);
        note.setPadding(0, dp(8), 0, dp(4));
        addView(note);

        TextView mesa = pill("Usar la Cámara Mesa Doble (con visor aquí, zoom y flash)", 0xFF3A3A40);
        mesa.setTextSize(13);
        mesa.setOnClickListener(v -> { if (host != null) host.useMesaCamera(); });
        addView(mesa, new LayoutParams(-2, dp(40)));
        paint();
        setAccess(false);
    }

    public void setHost(Host h) { host = h; }

    private void add(LinearLayout row, View v, int size) {
        LayoutParams lp = new LayoutParams(dp(size), dp(size));
        lp.setMargins(dp(8), 0, dp(8), 0);
        row.addView(v, lp);
    }

    private TextView circle(String t, int size, int color) {
        TextView v = new TextView(getContext());
        v.setText(t);
        v.setTextSize(size > 70 ? 13 : 12);
        v.setTextColor(Color.WHITE);
        v.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        v.setBackground(g);
        return v;
    }

    private TextView pill(String t, int color) {
        TextView v = new TextView(getContext());
        v.setText(t);
        v.setTextSize(14);
        v.setTextColor(Color.WHITE);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(18), 0, dp(18), 0);
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(22));
        v.setBackground(g);
        return v;
    }

    private void paint() {
        faceBtn.setText(front ? "🔄\nTrasera" : "🔄\nDelantera");   // a cuál cambia
    }

    private void setAccess(boolean on) {
        extra.setVisibility(on ? VISIBLE : GONE);
        note.setText(on
                ? "Con la versión completa los botones tocan la cámara del teléfono directamente."
                : "Cambiar cámara vuelve a abrir la cámara del teléfono con la otra cámara. Disparar la abre como lo hace el "
                + "asistente de voz (cuenta 3 s y saca la foto) si tu cámara lo acepta. El zoom y el flash de la cámara del "
                + "teléfono solo se pueden con la versión completa.");
    }

    private void cmd(String a) {
        JSONObject m = new JSONObject();
        try { m.put("t", "phoneCam"); m.put("a", a); } catch (Exception ignored) { }
        if (hub.isPrincipal()) PhoneCamera.command(getContext(), hub, m);
        else if (hub.connected()) hub.send(m);
        else status.setText("Sin conexión con el principal");
    }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); cmd("state"); }

    public void onMessage(JSONObject m) {
        String t = m.optString("t");
        if ("phoneCamState".equals(t)) post(() -> {
            front = m.optBoolean("front");
            status.setText(m.optString("app", "Cámara") + "  ·  " + (front ? "delantera" : "trasera"));
            paint();
            setAccess(m.optBoolean("access"));
        });
        else if ("camApp".equals(t)) post(() -> status.setText("El principal abrió la cámara"));
    }
}
