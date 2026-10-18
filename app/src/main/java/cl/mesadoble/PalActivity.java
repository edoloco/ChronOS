package cl.mesadoble;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Un PAL en la pantalla principal: fondo de naranja, panel flotante de cristal naranjo a la izquierda con las
 * opciones del servicio, y la ventana del servicio (con su barra de texto) en un cristal naranjo grande a la derecha.
 * Se escribe con el teclado ChronOS del extendido. La sesión queda guardada dentro del PAL.
 */
public class PalActivity extends Activity {

    private Pals.Pal pal;
    private WebView web;
    private SharedPreferences prefs;
    private float dp;
    private ValueCallback<Uri[]> fileCb;
    private TextView zoomLabel;

    private int dp(float v) { return Math.round(v * dp); }

    @SuppressLint("SetJavaScriptEnabled")
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        dp = getResources().getDisplayMetrics().density;
        prefs = getSharedPreferences("mesa", MODE_PRIVATE);
        pal = Pals.get(getIntent().getStringExtra("pal"));
        if (pal == null) pal = Pals.ALL.get(0);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        FrameLayout root = new FrameLayout(this) {
            final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            { setWillNotDraw(false); }
            @Override protected void onDraw(Canvas c) {
                // fondo "naranja": degradado tibio con dos brillos suaves
                float w = getWidth(), h = getHeight();
                p.setShader(new LinearGradient(0, 0, w, h, new int[]{0xFF3A1A08, 0xFF7A3510, 0xFF2A1408}, null, Shader.TileMode.CLAMP));
                c.drawRect(0, 0, w, h, p);
                p.setShader(new RadialGradient(w * 0.2f, h * 0.15f, w * 0.5f, 0x66FF9A3C, 0x00FF9A3C, Shader.TileMode.CLAMP));
                c.drawRect(0, 0, w, h, p);
                p.setShader(new RadialGradient(w * 0.85f, h * 0.95f, w * 0.45f, 0x4D2FA866, 0x002FA866, Shader.TileMode.CLAMP));
                c.drawRect(0, 0, w, h, p);
                p.setShader(null);
            }
        };
        root.setClipChildren(false);

        LinearLayout row = new LinearLayout(this);
        row.setClipChildren(false);
        row.setPadding(dp(14), dp(12), dp(14), dp(16));

        // ---- panel flotante de opciones (izquierda)
        Glass menu = new Glass(0x99F57C1F);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setPadding(dp(10), dp(12), dp(10), dp(12));
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView ic = new ImageView(this);
        ic.setImageBitmap(Pals.icon(pal, dp(40)));
        head.addView(ic, new LinearLayout.LayoutParams(dp(34), dp(34)));
        TextView name = text(pal.name, 15, true);
        name.setPadding(dp(8), 0, 0, 0);
        head.addView(name);
        menu.addView(head);
        space(menu, 10);
        for (Pals.Item it : pal.menu) menu.addView(option(it.glyph, it.label, v -> web.loadUrl(it.url)));
        space(menu, 8);
        menu.addView(option("←", "Atrás", v -> { if (web.canGoBack()) web.goBack(); }));
        menu.addView(option("↻", "Recargar", v -> web.reload()));
        LinearLayout zoom = new LinearLayout(this);
        zoom.setGravity(Gravity.CENTER_VERTICAL);
        zoom.addView(option("A−", "", v -> setZoom(-10)), new LinearLayout.LayoutParams(0, dp(38), 1));
        zoomLabel = text("", 12, false);
        zoomLabel.setGravity(Gravity.CENTER);
        zoom.addView(zoomLabel, new LinearLayout.LayoutParams(dp(42), dp(38)));
        zoom.addView(option("A+", "", v -> setZoom(10)), new LinearLayout.LayoutParams(0, dp(38), 1));
        menu.addView(zoom);
        View fill = new View(this);
        menu.addView(fill, new LinearLayout.LayoutParams(1, 0, 1));
        menu.addView(option("✕", "Cerrar", v -> finish()));
        row.addView(menu, new LinearLayout.LayoutParams(dp(176), -1));

        // ---- la ventana del servicio (cristal naranjo grande)
        Glass chat = new Glass(0x66F57C1F);
        chat.setPadding(dp(9), dp(9), dp(9), dp(9));
        web = new WebView(this);
        web.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(14)); }
        });
        web.setClipToOutline(true);
        chat.addView(web, new LinearLayout.LayoutParams(-1, -1));
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, -1, 1);
        cl.leftMargin = dp(14);
        row.addView(chat, cl);
        root.addView(row, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        immersive();
        setupWeb();
        String last = prefs.getString("pal_" + pal.id + "_url", null);
        web.loadUrl(b == null && last != null && allowed(Uri.parse(last)) ? last : pal.home);
        if (!prefs.getBoolean("pal_" + pal.id + "_tip", false)) {
            prefs.edit().putBoolean("pal_" + pal.id + "_tip", true).apply();
            Toast.makeText(this, "Inicia sesión con tu correo y el código que te llega (el botón de Google no funciona dentro de apps). Escribe con el teclado del extendido.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override public void onWindowFocusChanged(boolean f) { super.onWindowFocusChanged(f); if (f) immersive(); }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWeb() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setTextZoom(prefs.getInt("pal_" + pal.id + "_zoom", 90));
        // como el navegador normal (sin la marca de "app incrustada", que algunos sitios rechazan)
        s.setUserAgentString(s.getUserAgentString().replace("; wv", "").replaceAll(" Version/\\d+(\\.\\d+)*", ""));
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);
        web.setBackgroundColor(0xFF1F1E1D);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (allowed(u)) return false;
                // enlaces de afuera: en el navegador
                try { startActivity(new Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception ignored) { }
                return true;
            }
            @Override public void onPageFinished(WebView v, String url) {
                if (allowed(Uri.parse(url))) prefs.edit().putString("pal_" + pal.id + "_url", url).apply();
                CookieManager.getInstance().flush();
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                // adjuntar archivos (fotos, PDF…) desde el principal
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                try { startActivityForResult(p.createIntent(), 61); }
                catch (Exception e) { fileCb = null; return false; }
                return true;
            }
        });
        setZoom(0);
    }

    private boolean allowed(Uri u) {
        String h = u.getHost();
        if (h == null) return true;
        for (String d : pal.hosts) if (h.equals(d) || h.endsWith("." + d)) return true;
        return false;
    }

    private void setZoom(int d) {
        int z = Math.max(60, Math.min(140, web.getSettings().getTextZoom() + d));
        web.getSettings().setTextZoom(z);
        prefs.edit().putInt("pal_" + pal.id + "_zoom", z).apply();
        if (zoomLabel != null) zoomLabel.setText(z + "%");
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != 61 || fileCb == null) return;
        fileCb.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data));
        fileCb = null;
    }

    @Override public void onBackPressed() { if (web.canGoBack()) web.goBack(); else super.onBackPressed(); }

    @Override protected void onPause() { CookieManager.getInstance().flush(); web.onPause(); super.onPause(); }
    @Override protected void onResume() { super.onResume(); web.onResume(); }
    @Override protected void onDestroy() { web.destroy(); super.onDestroy(); }

    // ---------------- piezas ----------------

    private TextView text(String t, float sp, boolean bold) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(sp);
        v.setTextColor(Color.WHITE);
        v.setShadowLayer(3 * dp, 0, dp, 0x80000000);
        v.setTypeface(bold ? Intro.font(this) : null);
        return v;
    }

    private void space(LinearLayout l, int h) { l.addView(new View(this), new LinearLayout.LayoutParams(1, dp(h))); }

    /** Opción del panel: símbolo y nombre, sobre un vidrio un poco más claro al tocar. */
    private View option(String glyph, String label, View.OnClickListener l) {
        TextView v = text(label.isEmpty() ? glyph : glyph + "   " + label, 14, false);
        v.setGravity(label.isEmpty() ? Gravity.CENTER : Gravity.CENTER_VERTICAL);
        v.setPadding(dp(12), 0, dp(10), 0);
        android.graphics.drawable.StateListDrawable st = new android.graphics.drawable.StateListDrawable();
        android.graphics.drawable.GradientDrawable on = new android.graphics.drawable.GradientDrawable(), off = new android.graphics.drawable.GradientDrawable();
        on.setColor(0x59FFFFFF); on.setCornerRadius(dp(12));
        off.setColor(0x1AFFFFFF); off.setCornerRadius(dp(12));
        st.addState(new int[]{android.R.attr.state_pressed}, on);
        st.addState(new int[]{}, off);
        v.setBackground(st);
        v.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(40));
        lp.setMargins(0, dp(3), 0, dp(3));
        v.setLayoutParams(lp);
        return v;
    }

    /** Cristal naranjo: vidrio esmerilado de ChronOS teñido de naranjo, con sombra difuminada. */
    private final class Glass extends LinearLayout {
        final Paint tint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Glass(int color) {
            super(PalActivity.this);
            setWillNotDraw(false);
            tint.setColor(color);
        }
        @Override protected void onDraw(Canvas c) {
            RectF r = new RectF(0, 0, getWidth(), getHeight());
            float rad = 22 * dp;
            Path out = new Path();
            out.addRoundRect(r, rad, rad, Path.Direction.CW);
            c.save();
            c.clipOutPath(out);
            Glass.this.shadow(c, r, rad);
            c.restore();
            Glass_body(c, r, rad);
        }
        private void shadow(Canvas c, RectF r, float rad) { cl.mesadoble.Glass.shadow(c, r, rad, dp, true); }
        private void Glass_body(Canvas c, RectF r, float rad) {
            cl.mesadoble.Glass.body(c, r, rad, dp, null);
            c.drawRoundRect(r, rad, rad, tint);
        }
    }
}
