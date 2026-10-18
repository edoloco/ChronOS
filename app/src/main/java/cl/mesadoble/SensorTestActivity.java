package cl.mesadoble;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Prueba de sensores: muestra en vivo la proximidad, la luz y el campo magnético de LOS DOS teléfonos, para ver
 * cuál sirve para detectar que se cerró la carcasa. Suena un pitido al "cerrar" (los dos tapados) y otro al abrir,
 * porque con la tapa cerrada no se ven las pantallas.
 *   senOn / senOff → el otro teléfono empieza / deja de mandar   sen {s}   senOpen → abrir esta prueba allá también
 */
public class SensorTestActivity extends Activity implements Link.Listener {

    static volatile boolean open;
    private static final int ORANGE = 0xFFF57C1F;

    private final Handler h = new Handler(Looper.getMainLooper());
    private Hub hub;
    private SensorProbe probe;
    private float dp;
    private final Side me = new Side(), other = new Side();
    private final List<String> log = new ArrayList<>();
    private LinearLayout colMe, colOther, logBox;
    private TextView verdict;
    private int lidState = -1;   // 0 abierta, 1 cerrada
    private ToneGenerator tone;

    /** Lo visto en un teléfono: último valor y extremos. */
    private static final class Side {
        JSONObject s;
        float lMin = Float.MAX_VALUE, lMax = -1, mBase = -1, mDelta;
        int covers;
        boolean wasNear;
        long at;
        void reset() { lMin = Float.MAX_VALUE; lMax = -1; mBase = -1; mDelta = 0; covers = 0; }
    }

    static Intent intent(Context c) {
        return new Intent(c, SensorTestActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }

    private int dp(float v) { return Math.round(v * dp); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        hub = Hub.get(this);
        dp = getResources().getDisplayMetrics().density;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        try { tone = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90); } catch (Exception ignored) { }
        ScrollView sc = new ScrollView(this);
        sc.setBackgroundColor(0xFF0B0F14);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(14), dp(16), dp(24));
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = new TextView(this);
        t.setText("Prueba de sensores");
        t.setTypeface(Intro.font(this));
        t.setTextSize(19);
        t.setTextColor(ORANGE);
        head.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        box.addView(head);
        LinearLayout btns = new LinearLayout(this);
        btns.addView(pill("Reiniciar", v -> { me.reset(); other.reset(); log.clear(); draw(); }));
        btns.addView(pill("Abrir en el otro", v -> {
            if (hub.connected()) { hub.send("senOpen"); toast("Se abrió la prueba en el otro teléfono"); } else toast("Sin conexión con el otro teléfono");
        }));
        btns.addView(pill("Cerrar", v -> finish()));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-2, -2);
        bl.topMargin = dp(8);
        box.addView(btns, bl);
        TextView how = new TextView(this);
        how.setTextSize(13);
        how.setTextColor(0xDDFFFFFF);
        how.setLineSpacing(dp(2), 1);
        how.setPadding(dp(14), dp(12), dp(14), dp(12));
        how.setText("Cómo probar:\n"
                + "1. Con la carcasa abierta, mira que \"Proximidad\" diga LIBRE en los dos.\n"
                + "2. Cierra la carcasa (o junta los teléfonos cara a cara) y espera 3 segundos. Sonará un pitido doble si los DOS"
                + " detectan la tapa.\n"
                + "3. Abre: un pitido simple. Revisa los contadores \"veces tapado\" y el registro de abajo.\n"
                + "4. Si la proximidad no responde, mira \"Imán\": con un imán en la carcasa el número salta mucho al cerrar.\n"
                + "Mándame una captura de esta pantalla después de cerrar y abrir 3 veces.");
        how.setBackground(card(0x22F57C1F));
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(-1, -2);
        hl.setMargins(0, dp(10), 0, dp(10));
        box.addView(how, hl);
        verdict = new TextView(this);
        verdict.setTextSize(20);
        verdict.setTypeface(Typeface.DEFAULT_BOLD);
        verdict.setGravity(Gravity.CENTER);
        verdict.setPadding(dp(12), dp(12), dp(12), dp(12));
        box.addView(verdict, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout cols = new LinearLayout(this);
        colMe = new LinearLayout(this);
        colMe.setOrientation(LinearLayout.VERTICAL);
        colOther = new LinearLayout(this);
        colOther.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, -2, 1);
        cl.setMargins(0, dp(8), dp(6), 0);
        LinearLayout.LayoutParams cr = new LinearLayout.LayoutParams(0, -2, 1);
        cr.setMargins(dp(6), dp(8), 0, 0);
        // misma disposición física: el principal a la izquierda/arriba
        cols.addView(hub.isPrincipal() ? colMe : colOther, cl);
        cols.addView(hub.isPrincipal() ? colOther : colMe, cr);
        box.addView(cols);
        logBox = new LinearLayout(this);
        logBox.setOrientation(LinearLayout.VERTICAL);
        logBox.setPadding(dp(4), dp(12), 0, 0);
        box.addView(logBox);
        sc.addView(box);
        setContentView(sc);
        draw();
        startProbe();
    }

    // con la tapa cerrada la pantalla puede apagarse: se sigue midiendo (aunque la prueba quede en pausa)
    // hasta que se cierre
    private void startProbe() {
        open = true;
        hub.add(this);
        probe = new SensorProbe(this, s -> h.post(() -> got(me, s, "este")));
        if (hub.connected()) hub.send("senOn");
    }

    @Override protected void onDestroy() {
        open = false;
        if (probe != null) probe.stop();
        probe = null;
        hub.remove(this);
        if (hub.connected()) hub.send("senOff");
        if (tone != null) tone.release();
        super.onDestroy();
    }

    @Override public void onMessage(JSONObject m) {
        if ("sen".equals(m.optString("t"))) {
            JSONObject s = m.optJSONObject("s");
            if (s != null) h.post(() -> got(other, s, "el otro"));
        }
    }

    @Override public void onState(int state, String info) {
        h.post(() -> {
            if (hub.connected()) hub.send("senOn"); else other.s = null;
            draw();
        });
    }

    private void got(Side sd, JSONObject s, String who) {
        if (probe == null && sd == me) return;
        sd.s = s;
        sd.at = System.currentTimeMillis();
        boolean near = s.optBoolean("near");
        if (near != sd.wasNear) {
            if (near) sd.covers++;
            addLog(who + ": proximidad " + (near ? "TAPADO" : "libre") + " (" + fmt((float) s.optDouble("px")) + " cm)");
            sd.wasNear = near;
        }
        float lx = (float) s.optDouble("lx", -1);
        if (lx >= 0) { sd.lMin = Math.min(sd.lMin, lx); sd.lMax = Math.max(sd.lMax, lx); }
        float mt = (float) s.optDouble("mt", -1);
        if (mt >= 0) {
            if (sd.mBase < 0) sd.mBase = mt;
            float dl = Math.abs(mt - sd.mBase);
            if (dl > sd.mDelta + 40 && sd.mDelta < 40) addLog(who + ": imán, salto de " + Math.round(dl) + " µT");
            sd.mDelta = Math.max(sd.mDelta, dl);
        }
        checkLid();
        draw();
    }

    private void checkLid() {
        boolean a = me.s != null && me.s.optBoolean("near"), b = other.s != null && other.s.optBoolean("near");
        int st = a && b ? 1 : (!a && !b ? 0 : -2);
        if (st == -2 || st == lidState) return;
        if (lidState == -1) { lidState = st; return; }
        lidState = st;
        addLog(st == 1 ? "TAPA CERRADA (los dos tapados)" : "tapa abierta");
        if (tone != null) {
            if (st == 1) { tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 300); }
            else tone.startTone(ToneGenerator.TONE_PROP_BEEP, 150);
        }
    }

    private void addLog(String s) {
        log.add(0, new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date()) + "  " + s);
        while (log.size() > 30) log.remove(log.size() - 1);
    }

    private void draw() {
        boolean a = me.s != null && me.s.optBoolean("near"), b = other.s != null && other.s.optBoolean("near");
        if (other.s == null) {
            verdict.setText(hub.connected() ? "Esperando al otro teléfono…" : "Sin conexión con el otro teléfono: solo se ve este");
            verdict.setTextColor(0xFFFFD54F);
            verdict.setBackground(card(0x26FFC107));
        } else if (a && b) {
            verdict.setText("Tapa: CERRADA");
            verdict.setTextColor(0xFF7CFC9A);
            verdict.setBackground(card(0x264CAF50));
        } else if (a || b) {
            verdict.setText("Solo " + (a ? "este" : "el otro") + " está tapado");
            verdict.setTextColor(0xFFFFD54F);
            verdict.setBackground(card(0x26FFC107));
        } else {
            verdict.setText("Tapa: abierta");
            verdict.setTextColor(Color.WHITE);
            verdict.setBackground(card(0x18FFFFFF));
        }
        fill(colMe, me, (hub.isPrincipal() ? "Principal" : "Extendido") + " · este");
        fill(colOther, other, hub.isPrincipal() ? "Extendido" : "Principal");
        logBox.removeAllViews();
        TextView lt = new TextView(this);
        lt.setText(log.isEmpty() ? "Registro: todavía sin cambios" : "Registro");
        lt.setTextColor(0xAAFFFFFF);
        lt.setTextSize(13);
        lt.setTypeface(Typeface.DEFAULT_BOLD);
        logBox.addView(lt);
        for (String s : log) {
            TextView v = new TextView(this);
            v.setText(s);
            v.setTextSize(12);
            v.setTypeface(Typeface.MONOSPACE);
            v.setTextColor(s.contains("CERRADA") ? 0xFF7CFC9A : 0xCCFFFFFF);
            logBox.addView(v);
        }
    }

    private void fill(LinearLayout col, Side sd, String title) {
        col.removeAllViews();
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(15);
        t.setTextColor(Color.WHITE);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(dp(4), 0, 0, dp(4));
        col.addView(t);
        JSONObject s = sd.s;
        if (s == null) { col.addView(card("Sin datos", "Abre ChronOS en ese teléfono y deja los dos conectados.", 2)); return; }
        // proximidad
        if (!s.optBoolean("hasP")) col.addView(card("Proximidad: no tiene", "Este teléfono no informa sensor de proximidad. Hay que usar el imán.", 0));
        else {
            boolean near = s.optBoolean("near");
            String d = fmt((float) s.optDouble("px")) + " cm (máximo " + fmt((float) s.optDouble("pmax")) + ")"
                    + " · veces tapado: " + sd.covers
                    + "\n" + s.optString("pn") + " · " + s.optString("pv") + (s.optBoolean("pw") ? " · despierta al teléfono" : "");
            if (s.optBoolean("virt")) d += "\n⚠ Parece un sensor VIRTUAL: puede que solo funcione durante las llamadas.";
            col.addView(card("Proximidad: " + (near ? "TAPADO" : "LIBRE"), d, near ? 1 : 3));
        }
        if (s.optBoolean("hasL")) {
            float lx = (float) s.optDouble("lx", -1);
            col.addView(card("Luz: " + (lx < 0 ? "—" : fmt(lx) + " lux"),
                    "Visto: " + (sd.lMax < 0 ? "—" : fmt(sd.lMin) + " a " + fmt(sd.lMax) + " lux") + "\nAl cerrar debería bajar casi a 0.", 3));
        } else col.addView(card("Luz: no tiene", "", 2));
        if (s.optBoolean("hasM")) {
            float mt = (float) s.optDouble("mt", -1);
            col.addView(card("Imán: " + (mt < 0 ? "—" : Math.round(mt) + " µT"),
                    "Mayor cambio visto: " + Math.round(sd.mDelta) + " µT (sin imán, girar el teléfono cambia ~30;"
                            + " un imán cerca, más de 100).", sd.mDelta > 100 ? 1 : 3));
        } else col.addView(card("Imán: no tiene", "", 2));
    }

    private static String fmt(float v) {
        if (v < 0) return "—";
        return v == Math.round(v) ? String.valueOf(Math.round(v)) : String.format(Locale.ROOT, "%.1f", v);
    }

    /** st: 1 verde, 0 rojo, 2 amarillo, 3 neutro. */
    private View card(String title, String desc, int st) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(dp(12), dp(10), dp(12), dp(10));
        r.setBackground(card(st == 1 ? 0x264CAF50 : st == 0 ? 0x33E53935 : st == 2 ? 0x26FFC107 : 0x18FFFFFF));
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(15);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(st == 1 ? 0xFF7CFC9A : st == 0 ? 0xFFFF8A80 : st == 2 ? 0xFFFFD54F : Color.WHITE);
        r.addView(t);
        if (!desc.isEmpty()) {
            TextView d = new TextView(this);
            d.setText(desc);
            d.setTextSize(12);
            d.setTextColor(0xCCFFFFFF);
            d.setPadding(0, dp(3), 0, 0);
            r.addView(d);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(4), 0, dp(4));
        r.setLayoutParams(lp);
        return r;
    }

    private GradientDrawable card(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setStroke(dp(1), 0x33FFFFFF);
        g.setCornerRadius(dp(14));
        return g;
    }

    private TextView pill(String s, View.OnClickListener l) {
        TextView b = new TextView(this);
        b.setText(s);
        b.setTextSize(13);
        b.setTextColor(Color.WHITE);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(14), 0, dp(14), 0);
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x40FFFFFF);
        g.setStroke(dp(1), 0x66FFFFFF);
        g.setCornerRadius(dp(16));
        b.setBackground(g);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(34));
        lp.setMargins(0, 0, dp(8), 0);
        b.setLayoutParams(lp);
        return b;
    }

    private void toast(String s) { android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show(); }
}
