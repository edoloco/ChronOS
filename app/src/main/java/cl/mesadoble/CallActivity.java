package cl.mesadoble;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

/** Extendido: controles de la llamada del principal (contestar, rechazar, colgar) y quién llama. */
public class CallActivity extends Activity implements Link.Listener {

    static boolean showing;
    private final Handler h = new Handler(Looper.getMainLooper());
    private Hub hub;
    private TextView state, name, number, msg, answer, end;
    private long activeSince;

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    @SuppressWarnings("deprecation")
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        hub = Hub.get(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        MainActivity.applyOrientation(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(20), dp(16), dp(20), dp(16));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xFF0E3B2E, 0xFF07161F});
        root.setBackground(bg);

        state = text(13, 0xFF8FE3B8, true);
        state.setLetterSpacing(0.15f);
        root.addView(state);
        name = text(30, Color.WHITE, true);
        root.addView(name);
        number = text(16, 0xFFB8C7D0, false);
        root.addView(number);
        msg = text(13, 0xFFF0A26B, false);
        root.addView(msg);

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        row.setPadding(0, dp(18), 0, 0);
        answer = round("📞\nContestar", 0xFF2EBD5F);
        answer.setOnClickListener(v -> cmd("answer"));
        end = round("📵\nRechazar", 0xFFE53935);
        end.setOnClickListener(v -> cmd("end"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(110), dp(110));
        lp.setMargins(dp(22), 0, dp(22), 0);
        row.addView(end, lp);
        row.addView(answer, new LinearLayout.LayoutParams(lp));
        root.addView(row);

        TextView close = text(14, 0xFF8FA3B0, false);
        close.setText("Ocultar");
        close.setPadding(dp(16), dp(14), dp(16), dp(6));
        close.setOnClickListener(v -> finish());
        root.addView(close);
        setContentView(root);
        apply(CallHub.last);
    }

    private TextView text(float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    private TextView round(String s, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(14);
        t.setTextColor(Color.WHITE);
        t.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        t.setBackground(g);
        t.setElevation(dp(8));
        return t;
    }

    private void cmd(String a) {
        msg.setText("");
        if (hub.isPrincipal()) {
            JSONObject m = new JSONObject();
            try { m.put("a", a); } catch (Exception ignored) { }
            CallHub.command(this, hub, m);
        } else if (hub.connected()) hub.send("callCmd", "a", a);
        else msg.setText("Sin conexión con el principal");
    }

    private void apply(JSONObject m) {
        if (m == null) { state.setText("LLAMADA EN EL PRINCIPAL"); return; }
        String st = m.optString("state"), nm = m.optString("name"), num = m.optString("number");
        name.setText(!nm.isEmpty() ? nm : !num.isEmpty() ? num : "Número desconocido");
        number.setText(!nm.isEmpty() ? num : num.isEmpty() ? "Para ver el número: en el principal, Ajustes → Llamadas" : "");
        switch (st) {
            case "ring":
                state.setText("LLAMADA ENTRANTE");
                answer.setVisibility(android.view.View.VISIBLE);
                end.setText("📵\nRechazar");
                break;
            case "active":
                if (activeSince == 0) activeSince = SystemClock.elapsedRealtime();
                answer.setVisibility(android.view.View.GONE);
                end.setText("📵\nColgar");
                tick.run();
                break;
            default:
                state.setText("LLAMADA TERMINADA");
                answer.setVisibility(android.view.View.GONE);
                h.removeCallbacks(tick);
                h.postDelayed(this::finish, 1500);
        }
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            long s = (SystemClock.elapsedRealtime() - activeSince) / 1000;
            state.setText(String.format(java.util.Locale.ROOT, "EN LLAMADA · %d:%02d", s / 60, s % 60));
            h.postDelayed(this, 1000);
        }
    };

    @Override protected void onResume() { super.onResume(); showing = true; hub.add(this); apply(CallHub.last); }
    @Override protected void onPause() { showing = false; hub.remove(this); super.onPause(); }
    @Override protected void onDestroy() { h.removeCallbacksAndMessages(null); super.onDestroy(); }

    @Override public void onState(int s, String info) { }

    @Override public void onMessage(JSONObject m) {
        String t = m.optString("t");
        if ("call".equals(t)) h.post(() -> apply(m));
        else if ("callAck".equals(t) && !m.optBoolean("ok")) h.post(() -> msg.setText(m.optString("msg")));
    }
}
