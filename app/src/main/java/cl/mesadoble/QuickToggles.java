package cl.mesadoble;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Toast;

/**
 * Accesos rápidos de la action bar del principal (arriba): cuadros con íconos blancos, como los botones de
 * navegación. Los que Android deja cambiar directo se cambian al tocar (linterna, bluetooth, rotación, ahorro de
 * batería si ChronOS está instalado en el sistema); los demás abren el ajuste o el panel justo (Android no deja que
 * una app los cambie sola). El último cuadro (lápiz) elige cuáles se ven.
 *   preferencia "qtSet": lista de claves separadas por coma, en el orden en que se muestran
 */
public class QuickToggles extends View {

    static final String[] KEYS = {"hotspot", "bt", "torch", "saver", "plane", "shot", "qr", "remote", "cast",
            "wifi", "data", "rotate", "location", "panel"};
    static final String[] NAMES = {"Compartir red", "Bluetooth", "Linterna", "Ahorro de batería", "Modo avión",
            "Captura de pantalla", "Lector QR", "Control remoto", "Transmitir", "WiFi", "Datos móviles",
            "Rotación automática", "Ubicación", "Panel del sistema"};
    private static final String DEFAULT = "hotspot,bt,torch,saver,plane,shot,qr,remote,cast";

    private final float dp;
    private final Hub hub;
    private String[] shown = new String[0];
    private final Paint box = new Paint(Paint.ANTI_ALIAS_FLAG), ic = new Paint(Paint.ANTI_ALIAS_FLAG), icf = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path pa = new Path();
    private int pressed = -1;

    public QuickToggles(Context c, Hub hub) {
        super(c);
        this.hub = hub;
        dp = c.getResources().getDisplayMetrics().density;
        ic.setStyle(Paint.Style.STROKE);
        ic.setStrokeCap(Paint.Cap.ROUND);
        ic.setStrokeJoin(Paint.Join.ROUND);
        ic.setColor(0xFFFFFFFF);
        icf.setColor(0xFFFFFFFF);
        load();
    }

    private SharedPreferences prefs() { return getContext().getSharedPreferences("mesa", Context.MODE_PRIVATE); }

    private void load() {
        String s = prefs().getString("qtSet", DEFAULT);
        shown = s.isEmpty() ? new String[0] : s.split(",");
        requestLayout();
        invalidate();
    }

    // ---------------- medidas ----------------

    private float cell() { return Math.min(28 * dp, getHeight() > 0 ? getHeight() - 6 * dp : 28 * dp); }
    private float gap() { return 6 * dp; }

    @Override protected void onMeasure(int ws, int hs) {
        int n = shown.length + 1;   // + lápiz
        int w = Math.round(n * cell() + (n - 1) * gap() + 4 * dp), h = Math.round(cell() + 4 * dp);
        setMeasuredDimension(resolveSize(w, ws), resolveSize(h, hs));
    }

    /** Cuántos caben: si la barra es angosta, se ven los primeros (el lápiz siempre). */
    private int fit() {
        int max = (int) ((getWidth() - 4 * dp + gap()) / (cell() + gap())) - 1;
        return Math.max(0, Math.min(shown.length, max));
    }

    /** Se alinean a la derecha: el lápiz queda en la punta. */
    private RectF cellRect(int i, int count) {
        float s = cell(), x0 = getWidth() - 2 * dp - (count + 1) * s - count * gap();
        float x = x0 + i * (s + gap()), y = (getHeight() - s) / 2;
        return new RectF(x, y, x + s, y + s);
    }

    // ---------------- estados ----------------

    private boolean on(String k) {
        Context c = getContext();
        try {
            switch (k) {
                case "torch": return Torch.mine;
                case "bt": { BluetoothAdapter b = BluetoothAdapter.getDefaultAdapter(); return b != null && b.isEnabled(); }
                case "saver": { android.os.PowerManager pm = (android.os.PowerManager) c.getSystemService(Context.POWER_SERVICE); return pm != null && pm.isPowerSaveMode(); }
                case "plane": return Settings.Global.getInt(c.getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, 0) == 1;
                case "wifi": { android.net.wifi.WifiManager w = (android.net.wifi.WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE); return w != null && w.isWifiEnabled(); }
                case "hotspot": {
                    android.net.wifi.WifiManager w = (android.net.wifi.WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                    return w != null && (Boolean) w.getClass().getMethod("isWifiApEnabled").invoke(w);
                }
                case "rotate": return Settings.System.getInt(c.getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 0) == 1;
                case "location": { android.location.LocationManager lm = (android.location.LocationManager) c.getSystemService(Context.LOCATION_SERVICE); return lm != null && lm.isLocationEnabled(); }
            }
        } catch (Throwable ignored) { }
        return false;
    }

    private final Runnable poll = new Runnable() {
        @Override public void run() { invalidate(); postDelayed(this, 2500); }
    };

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); Torch.listen(getContext(), hub); post(poll); }
    @Override protected void onDetachedFromWindow() { removeCallbacks(poll); super.onDetachedFromWindow(); }

    // ---------------- dibujo ----------------

    @Override protected void onDraw(Canvas c) {
        int n = fit();
        for (int i = 0; i <= n; i++) {
            RectF r = cellRect(i, n);
            String k = i < n ? shown[i] : "edit";
            boolean act = i < n && on(k);
            box.setColor(act ? 0xFFF57C1F : (i == pressed ? 0xCC1D2733 : 0x8C1D2733));
            c.drawRoundRect(r, 7 * dp, 7 * dp, box);
            c.save();
            float u = r.width() / 24f;
            c.translate(r.left, r.top);
            c.scale(u, u);
            ic.setStrokeWidth(1.8f * dp / u);
            icon(c, k);
            c.restore();
        }
    }

    /** Íconos de línea en una grilla de 24 × 24. */
    private void icon(Canvas c, String k) {
        pa.reset();
        switch (k) {
            case "hotspot":
                c.drawCircle(12, 12, 1.8f, icf);
                c.drawArc(new RectF(7.5f, 7.5f, 16.5f, 16.5f), 135, 90, false, ic);
                c.drawArc(new RectF(7.5f, 7.5f, 16.5f, 16.5f), -45, 90, false, ic);
                c.drawArc(new RectF(4, 4, 20, 20), 135, 90, false, ic);
                c.drawArc(new RectF(4, 4, 20, 20), -45, 90, false, ic);
                break;
            case "bt":
                pa.moveTo(7.5f, 8); pa.lineTo(16, 15.5f); pa.lineTo(12, 19.5f); pa.lineTo(12, 4.5f); pa.lineTo(16, 8.5f); pa.lineTo(7.5f, 16);
                c.drawPath(pa, ic);
                break;
            case "torch":
                pa.moveTo(8, 4); pa.lineTo(16, 4); pa.lineTo(16, 8); pa.lineTo(14, 11); pa.lineTo(14, 20); pa.lineTo(10, 20); pa.lineTo(10, 11); pa.lineTo(8, 8); pa.close();
                c.drawPath(pa, ic);
                c.drawLine(8, 8, 16, 8, ic);
                c.drawLine(12, 13.5f, 12, 15.5f, ic);
                break;
            case "saver":
                c.drawRoundRect(new RectF(8, 6, 16, 20), 1.5f, 1.5f, ic);
                c.drawLine(10.5f, 4, 13.5f, 4, ic);
                c.drawLine(12, 10.5f, 12, 15.5f, ic);
                c.drawLine(9.5f, 13, 14.5f, 13, ic);
                break;
            case "plane":
                pa.moveTo(12, 3.5f); pa.lineTo(12, 20);
                c.drawPath(pa, ic);
                pa.reset();
                pa.moveTo(4, 14); pa.lineTo(12, 9.5f); pa.lineTo(20, 14);
                pa.moveTo(9, 20); pa.lineTo(12, 18); pa.lineTo(15, 20);
                c.drawPath(pa, ic);
                break;
            case "shot":
                pa.moveTo(4.5f, 9); pa.lineTo(4.5f, 4.5f); pa.lineTo(9, 4.5f);
                pa.moveTo(15, 4.5f); pa.lineTo(19.5f, 4.5f); pa.lineTo(19.5f, 9);
                pa.moveTo(19.5f, 15); pa.lineTo(19.5f, 19.5f); pa.lineTo(15, 19.5f);
                pa.moveTo(9, 19.5f); pa.lineTo(4.5f, 19.5f); pa.lineTo(4.5f, 15);
                c.drawPath(pa, ic);
                c.drawCircle(12, 12, 2.6f, ic);
                break;
            case "qr":
                c.drawRect(4.5f, 4.5f, 10, 10, ic);
                c.drawRect(14, 4.5f, 19.5f, 10, ic);
                c.drawRect(4.5f, 14, 10, 19.5f, ic);
                c.drawRect(14, 14, 16.2f, 16.2f, icf);
                c.drawRect(17.3f, 17.3f, 19.5f, 19.5f, icf);
                c.drawRect(6.6f, 6.6f, 7.9f, 7.9f, icf);
                c.drawRect(16.1f, 6.6f, 17.4f, 7.9f, icf);
                c.drawRect(6.6f, 16.1f, 7.9f, 17.4f, icf);
                break;
            case "remote":
                c.drawRoundRect(new RectF(8, 3.5f, 16, 20.5f), 3, 3, ic);
                c.drawCircle(12, 8.5f, 2, ic);
                c.drawCircle(10.3f, 13.5f, 0.9f, icf);
                c.drawCircle(13.7f, 13.5f, 0.9f, icf);
                c.drawCircle(10.3f, 16.8f, 0.9f, icf);
                c.drawCircle(13.7f, 16.8f, 0.9f, icf);
                break;
            case "cast":
                pa.moveTo(4, 8.5f); pa.lineTo(4, 6); pa.lineTo(20, 6); pa.lineTo(20, 18.5f); pa.lineTo(14.5f, 18.5f);
                c.drawPath(pa, ic);
                c.drawArc(new RectF(-1, 13, 9, 23), -90, 90, false, ic);
                c.drawArc(new RectF(-5, 9, 13, 27), -90, 90, false, ic);
                c.drawCircle(4.3f, 18.3f, 1.1f, icf);
                break;
            case "wifi":
                c.drawArc(new RectF(3, 6, 21, 24), 225, 90, false, ic);
                c.drawArc(new RectF(6.5f, 9.5f, 17.5f, 20.5f), 225, 90, false, ic);
                c.drawCircle(12, 17.5f, 1.4f, icf);
                break;
            case "data":
                pa.moveTo(8, 19); pa.lineTo(8, 5); pa.lineTo(5, 8.5f);
                pa.moveTo(16, 5); pa.lineTo(16, 19); pa.lineTo(19, 15.5f);
                c.drawPath(pa, ic);
                break;
            case "rotate":
                c.drawArc(new RectF(5, 5, 19, 19), 200, 290, false, ic);
                pa.moveTo(4.2f, 6.5f); pa.lineTo(5.6f, 10.4f); pa.lineTo(9.4f, 9);
                c.drawPath(pa, ic);
                break;
            case "location":
                pa.moveTo(12, 20.5f);
                pa.cubicTo(7, 15, 6, 12.5f, 6, 10);
                pa.cubicTo(6, 6.5f, 8.7f, 4, 12, 4);
                pa.cubicTo(15.3f, 4, 18, 6.5f, 18, 10);
                pa.cubicTo(18, 12.5f, 17, 15, 12, 20.5f);
                c.drawPath(pa, ic);
                c.drawCircle(12, 10, 2.2f, ic);
                break;
            case "panel":
                c.drawRoundRect(new RectF(4.5f, 4.5f, 10.5f, 10.5f), 1.5f, 1.5f, ic);
                c.drawRoundRect(new RectF(13.5f, 4.5f, 19.5f, 10.5f), 1.5f, 1.5f, ic);
                c.drawRoundRect(new RectF(4.5f, 13.5f, 10.5f, 19.5f), 1.5f, 1.5f, ic);
                c.drawRoundRect(new RectF(13.5f, 13.5f, 19.5f, 19.5f), 1.5f, 1.5f, ic);
                break;
            case "edit":
                pa.moveTo(5, 19); pa.lineTo(5.8f, 15.2f); pa.lineTo(15.5f, 5.5f); pa.lineTo(18.5f, 8.5f); pa.lineTo(8.8f, 18.2f); pa.close();
                c.drawPath(pa, ic);
                c.drawLine(13.5f, 7.5f, 16.5f, 10.5f, ic);
                break;
        }
    }

    // ---------------- toques ----------------

    private int hit(float x, float y) {
        int n = fit();
        for (int i = 0; i <= n; i++) { RectF r = cellRect(i, n); r.inset(-gap() / 2, -4 * dp); if (r.contains(x, y)) return i; }
        return -1;
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pressed = hit(e.getX(), e.getY());
                if (pressed < 0) return false;
                invalidate();
                return true;
            case MotionEvent.ACTION_UP: {
                int i = hit(e.getX(), e.getY()), was = pressed;
                pressed = -1;
                invalidate();
                if (i < 0 || i != was) return true;
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                int n = fit();
                if (i >= n) edit(); else act(shown[i]);
                postDelayed(this::invalidate, 400);
                return true;
            }
            case MotionEvent.ACTION_CANCEL: pressed = -1; invalidate(); return true;
        }
        return true;
    }

    // ---------------- acciones ----------------

    private void toast(String s) { Toast.makeText(getContext(), s, Toast.LENGTH_SHORT).show(); }

    private boolean open(Intent i) {
        try { getContext().startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return true; } catch (Exception e) { return false; }
    }

    private boolean openPkg(String... pkgs) {
        for (String p : pkgs) {
            Intent i = getContext().getPackageManager().getLaunchIntentForPackage(p);
            if (i != null && open(i)) return true;
        }
        return false;
    }

    private boolean global(int action) {
        MesaAccess a = MesaAccess.instance;
        return a != null && a.performGlobalAction(action);
    }

    private void act(String k) {
        Context c = getContext();
        switch (k) {
            case "torch":
                Torch.set(c, !Torch.mine);
                break;
            case "bt": {
                BluetoothAdapter b = BluetoothAdapter.getDefaultAdapter();
                if (b == null) { toast("Este teléfono no tiene bluetooth"); return; }
                boolean done = false;
                if (Build.VERSION.SDK_INT < 33) {
                    if (Build.VERSION.SDK_INT >= 31 && c.checkSelfPermission("android.permission.BLUETOOTH_CONNECT") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        if (c instanceof Activity) ((Activity) c).requestPermissions(new String[]{"android.permission.BLUETOOTH_CONNECT"}, 77);
                        return;
                    }
                    try { done = b.isEnabled() ? b.disable() : b.enable(); } catch (Exception ignored) { }
                }
                if (!done) {
                    if (!b.isEnabled()) open(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
                    else open(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
                }
                break;
            }
            case "saver": {
                boolean now = on("saver");
                boolean done = false;
                if (SystemSetup.canSecure(c)) {
                    try { done = Settings.Global.putInt(c.getContentResolver(), "low_power", now ? 0 : 1); } catch (Exception ignored) { }
                }
                if (!done && !open(new Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))) open(new Intent(Settings.ACTION_SETTINGS));
                break;
            }
            case "plane":
                // Android no deja que una app lo cambie: se abre el panel del sistema para tocarlo ahí
                if (global(5 /* GLOBAL_ACTION_QUICK_SETTINGS */)) toast("Toca \"Modo avión\" en el panel");
                else if (!open(new Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS))) open(new Intent(Settings.ACTION_WIRELESS_SETTINGS));
                break;
            case "shot":
                if (!global(9 /* GLOBAL_ACTION_TAKE_SCREENSHOT */))
                    toast(Updater.isLiviana(c) ? "La captura necesita la versión completa de ChronOS" : "Activa la accesibilidad de ChronOS para tomar capturas");
                break;
            case "qr":
                if (!openPkg("com.xiaomi.scanner", "com.google.ar.lens", "com.miui.scanner"))
                    if (!open(new Intent("android.media.action.STILL_IMAGE_CAMERA"))) toast("No se encontró un lector QR");
                break;
            case "remote":
                if (!openPkg("com.duokan.phone.remotecontroller", "com.xiaomi.mitv.phone.remotecontroller", "com.google.android.videos")) {
                    toast("No hay una app de control remoto: se abre la tienda");
                    open(new Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=control%20remoto%20infrarrojo")));
                }
                break;
            case "cast":
                if (!open(new Intent("android.settings.CAST_SETTINGS"))) open(new Intent(Settings.ACTION_WIRELESS_SETTINGS));
                break;
            case "hotspot": {
                Intent i = new Intent().setComponent(new ComponentName("com.android.settings", "com.android.settings.TetherSettings"));
                if (!open(i) && !open(new Intent("android.settings.TETHER_SETTINGS"))) open(new Intent(Settings.ACTION_WIRELESS_SETTINGS));
                break;
            }
            case "wifi":
                if (Build.VERSION.SDK_INT >= 29 && open(new Intent("android.settings.panel.action.WIFI"))) break;
                open(new Intent(Settings.ACTION_WIFI_SETTINGS));
                break;
            case "data":
                if (Build.VERSION.SDK_INT >= 29 && open(new Intent("android.settings.panel.action.INTERNET_CONNECTIVITY"))) break;
                if (!open(new Intent(Settings.ACTION_DATA_ROAMING_SETTINGS))) open(new Intent(Settings.ACTION_WIRELESS_SETTINGS));
                break;
            case "rotate":
                if (Settings.System.canWrite(c)) {
                    try { Settings.System.putInt(c.getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, on("rotate") ? 0 : 1); }
                    catch (Exception e) { toast("No se pudo cambiar la rotación"); }
                } else {
                    toast("Permite a ChronOS \"modificar ajustes del sistema\" y vuelve a tocar");
                    open(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + c.getPackageName())));
                }
                break;
            case "location":
                open(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
                break;
            case "panel":
                if (!global(5)) toast(Updater.isLiviana(c) ? "El panel necesita la versión completa de ChronOS" : "Activa la accesibilidad de ChronOS para abrir el panel");
                break;
        }
        invalidate();
    }

    /** Lápiz: elegir qué accesos se ven (se marcan y desmarcan; se mantienen en el orden de la lista). */
    private void edit() {
        if (!(getContext() instanceof Activity)) return;
        Activity a = (Activity) getContext();
        java.util.List<String> cur = java.util.Arrays.asList(shown);
        Ui.Sheet sh = new Ui.Sheet(a).title("Accesos rápidos").message("Elige los que quieres ver en la barra de arriba");
        for (int i = 0; i < KEYS.length; i++) {
            final String k = KEYS[i];
            boolean in = cur.contains(k);
            sh.option(in ? Ui.CHECK : Ui.PLUS, -1, NAMES[i], in ? "Se ve en la barra" : "Oculto", in, () -> {
                StringBuilder sb = new StringBuilder();
                for (String x : KEYS) {
                    boolean keep = x.equals(k) ? !in : cur.contains(x);
                    if (keep) sb.append(sb.length() > 0 ? "," : "").append(x);
                }
                prefs().edit().putString("qtSet", sb.toString()).apply();
                load();
                edit();
            });
        }
        sh.option(Ui.AUTO, "Volver a los de siempre", null, () -> { prefs().edit().putString("qtSet", DEFAULT).apply(); load(); });
        sh.button("Listo", true, null).show();
    }
}
