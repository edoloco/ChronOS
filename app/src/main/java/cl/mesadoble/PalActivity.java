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

    // ---------------- árbol de opciones ----------------

    /** Un nudo del árbol: un enlace, y si tiene "list", una lista que se trae de la cuenta al desplegarlo. */
    private final class Node {
        final String glyph, label, url, list;
        final java.util.List<Node> kids = new java.util.ArrayList<>();
        boolean open, loading, loaded;
        String note;
        Node(String glyph, String label, String url, String list) { this.glyph = glyph; this.label = label; this.url = url; this.list = list; }
    }

    private final java.util.List<Node> roots = new java.util.ArrayList<>();
    private LinearLayout tree;
    private final String nonce = Long.toHexString(new java.security.SecureRandom().nextLong());
    private final java.util.Map<String, Node> waiting = new java.util.HashMap<>();
    private int reqN;

    private void drawTree() {
        tree.removeAllViews();
        for (Node n : roots) drawNode(n, 0);
    }

    private void drawNode(Node n, int depth) {
        boolean branch = n.list != null;
        String arrow = branch ? (n.open ? "▾ " : "▸ ") : "   ";
        TextView v = text(arrow + (n.glyph == null ? "" : n.glyph + "  ") + n.label, depth == 0 ? 14 : 12.5f, false);
        v.setSingleLine(true);
        v.setEllipsize(android.text.TextUtils.TruncateAt.END);
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setPadding(dp(8 + depth * 14), 0, dp(6), 0);
        v.setBackground(rowBg(depth == 0 ? 0x1AFFFFFF : 0x0DFFFFFF));
        v.setOnClickListener(x -> {
            if (branch) {
                n.open = !n.open;
                if (n.open && !n.loaded && !n.loading) load(n);
                drawTree();
            } else if (n.url != null) web.loadUrl(n.url);
        });
        // mantener presionado una rama: abre su página (por ejemplo, el proyecto entero)
        if (branch && n.url != null) v.setOnLongClickListener(x -> { web.loadUrl(n.url); return true; });
        if (!branch && pinned(n)) v.setOnLongClickListener(x -> {
            new Ui.Sheet(this).title("¿Quitar de Fijadas?").message(n.label)
                    .button("Cancelar", false, null).button("Quitar", true, () -> unpin(n.url)).show();
            return true;
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(depth == 0 ? 40 : 34));
        lp.setMargins(0, dp(2), 0, dp(2));
        tree.addView(v, lp);
        if (!n.open) return;
        if (n.loading || n.note != null) {
            TextView w = text(n.loading ? "cargando…" : n.note, 11.5f, false);
            w.setAlpha(0.8f);
            w.setPadding(dp(22 + depth * 14), dp(4), dp(6), dp(4));
            tree.addView(w);
        }
        for (Node k : n.kids) drawNode(k, depth + 1);
    }

    private boolean pinned(Node n) {
        for (Node r : roots) if ("pins".equals(r.list) && r.kids.contains(n)) return true;
        return false;
    }

    private void reloadLists() {
        for (Node n : roots) if (n.list != null) { n.loaded = false; n.kids.clear(); if (n.open) load(n); }
        drawTree();
    }

    /**
     * Trae la lista desde la cuenta abierta en el PAL: se pide desde la misma página (con su sesión) y vuelve por
     * ChronosPal.got(). Son las mismas consultas que hace la página de Claude para su menú.
     */
    private void load(Node n) {
        if ("pins".equals(n.list)) {
            n.kids.clear();
            org.json.JSONArray a = pins();
            for (int i = 0; i < a.length(); i++) {
                org.json.JSONObject o = a.optJSONObject(i);
                n.kids.add(new Node(null, o.optString("l"), o.optString("u"), null));
            }
            n.note = a.length() == 0 ? "Toca ★ abajo para fijar la página que estás viendo" : null;
            n.loaded = true;
            return;
        }
        Uri cur = Uri.parse(web.getUrl() == null ? "" : web.getUrl());
        if (cur.getHost() == null || !allowed(cur)) { n.note = "Abre primero " + pal.name.replace(" PAL", ""); return; }
        n.loading = true;
        n.note = null;
        String id = "r" + (++reqN);
        waiting.put(id, n);
        String what = n.list;
        String js = "(async()=>{const N='" + nonce + "',I='" + id + "',W='" + what + "';"
                + "const g=async u=>{const r=await fetch(u,{credentials:'include'});if(!r.ok)throw new Error('http '+r.status);return r.json();};"
                + "try{let o=window.__chronosOrg;if(!o){const os=await g('/api/organizations');"
                + "const c=os.find(x=>(x.capabilities||[]).includes('chat'))||os[0];o=window.__chronosOrg=c.uuid;}"
                + "let out=[];"
                + "if(W==='projects'){const ps=await g('/api/organizations/'+o+'/projects');"
                + "out=ps.filter(p=>!p.archived_at).map(p=>({id:p.uuid,name:p.name}));}"
                + "else if(W==='chats'){const cs=await g('/api/organizations/'+o+'/chat_conversations?limit=40');"
                + "out=cs.map(c=>({id:c.uuid,name:c.name||'(sin título)'}));}"
                + "else if(W.startsWith('pchats:')){const p=W.slice(7);let cs;"
                + "try{cs=await g('/api/organizations/'+o+'/projects/'+p+'/conversations');}"
                + "catch(e){cs=(await g('/api/organizations/'+o+'/chat_conversations?limit=100')).filter(c=>c.project_uuid===p);}"
                + "out=cs.map(c=>({id:c.uuid,name:c.name||'(sin título)'}));}"
                + "ChronosPal.got(N,I,JSON.stringify(out));}catch(e){ChronosPal.got(N,I,'!'+String(e&&e.message||e));}})();";
        web.evaluateJavascript(js, null);
    }

    /** Respuesta de la página: solo se acepta con la clave de esta sesión del PAL. */
    private final class Bridge {
        @android.webkit.JavascriptInterface
        public void got(String key, String id, String data) {
            if (!nonce.equals(key)) return;
            runOnUiThread(() -> fill(id, data));
        }
    }

    private void fill(String id, String data) {
        Node n = waiting.remove(id);
        if (n == null) return;
        n.loading = false;
        n.kids.clear();
        if (data == null || data.startsWith("!")) {
            String e = data == null ? "" : data;
            n.note = e.contains("401") || e.contains("403") ? "Inicia sesión primero" : "No se pudo cargar (toca ↻)";
            drawTree();
            return;
        }
        try {
            org.json.JSONArray a = new org.json.JSONArray(data);
            for (int i = 0; i < a.length(); i++) {
                org.json.JSONObject o = a.getJSONObject(i);
                String uid = o.optString("id"), nm = o.optString("name");
                if ("projects".equals(n.list)) n.kids.add(new Node("▣", nm, "https://claude.ai/project/" + uid, "pchats:" + uid));
                else n.kids.add(new Node(null, nm, "https://claude.ai/chat/" + uid, null));
            }
            if (a.length() == 0) n.note = "projects".equals(n.list) ? "Sin proyectos" : "Sin chats";
            // atajos dentro de cada rama: abrir el proyecto entero / ver la lista completa
            if (n.list.startsWith("pchats:")) n.kids.add(0, new Node("↗", "Abrir proyecto", n.url, null));
            else n.kids.add(new Node("…", "Ver todos", n.url, null));
            n.loaded = true;
        } catch (Exception e) { n.note = "No se pudo leer la lista"; }
        drawTree();
    }

    private android.graphics.drawable.Drawable rowBg(int color) {
        android.graphics.drawable.StateListDrawable st = new android.graphics.drawable.StateListDrawable();
        android.graphics.drawable.GradientDrawable on = new android.graphics.drawable.GradientDrawable(), off = new android.graphics.drawable.GradientDrawable();
        on.setColor(0x59FFFFFF); on.setCornerRadius(dp(10));
        off.setColor(color); off.setCornerRadius(dp(10));
        st.addState(new int[]{android.R.attr.state_pressed}, on);
        st.addState(new int[]{}, off);
        return st;
    }

    private View small(String glyph, View.OnClickListener l) {
        TextView v = text(glyph, 15, false);
        v.setGravity(Gravity.CENTER);
        v.setBackground(rowBg(0x1AFFFFFF));
        v.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(36), 1);
        lp.setMargins(dp(2), dp(2), dp(2), dp(2));
        v.setLayoutParams(lp);
        return v;
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        dp = getResources().getDisplayMetrics().density;
        prefs = getSharedPreferences("mesa", MODE_PRIVATE);
        pal = Pals.get(this, getIntent().getStringExtra("pal"));
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
        space(menu, 8);
        // árbol: las opciones se despliegan con su contenido (la lista de proyectos, los chats…)
        android.widget.ScrollView sc = new android.widget.ScrollView(this);
        sc.setVerticalScrollBarEnabled(false);
        tree = new LinearLayout(this);
        tree.setOrientation(LinearLayout.VERTICAL);
        sc.addView(tree);
        menu.addView(sc, new LinearLayout.LayoutParams(-1, 0, 1));
        for (Pals.Item it : pal.menu) roots.add(new Node(it.glyph, it.label, it.url, it.list));
        roots.add(new Node("★", "Fijadas", null, "pins"));   // páginas que tú fijas con ★
        drawTree();
        // barra de abajo, solo símbolos
        LinearLayout bar1 = new LinearLayout(this), bar2 = new LinearLayout(this);
        bar1.addView(small("←", v -> { if (web.canGoBack()) web.goBack(); }), new LinearLayout.LayoutParams(0, dp(36), 1));
        bar1.addView(small("↻", v -> { web.reload(); reloadLists(); }), new LinearLayout.LayoutParams(0, dp(36), 1));
        bar1.addView(small("⎘", v -> openCopiedLink()), new LinearLayout.LayoutParams(0, dp(36), 1));
        bar1.addView(small("★", v -> pinPage()), new LinearLayout.LayoutParams(0, dp(36), 1));
        bar1.addView(small("✕", v -> finish()), new LinearLayout.LayoutParams(0, dp(36), 1));
        bar2.addView(small("A−", v -> setZoom(-10)), new LinearLayout.LayoutParams(0, dp(34), 1));
        zoomLabel = text("", 12, false);
        zoomLabel.setGravity(Gravity.CENTER);
        bar2.addView(zoomLabel, new LinearLayout.LayoutParams(0, dp(34), 1));
        bar2.addView(small("A+", v -> setZoom(10)), new LinearLayout.LayoutParams(0, dp(34), 1));
        space(menu, 6);
        menu.addView(bar1);
        menu.addView(bar2);
        row.addView(menu, new LinearLayout.LayoutParams(dp(236), -1));

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
        if (pal.desktop) {
            // como computador: la página se arma ancha (foto a un lado y comentarios al otro, etc.), mejor en horizontal
            String ua = s.getUserAgentString();
            java.util.regex.Matcher cv = java.util.regex.Pattern.compile("Chrome/[\\d.]+").matcher(ua);
            s.setUserAgentString("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + (cv.find() ? cv.group() : "Chrome/124.0.0.0") + " Safari/537.36");
        }
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);
        web.setBackgroundColor(0xFF1F1E1D);
        web.addJavascriptInterface(new Bridge(), "ChronosPal");
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (allowed(u)) return false;
                // enlaces de afuera: en el navegador
                try { startActivity(new Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception ignored) { }
                return true;
            }
            @Override public void onPageStarted(WebView v, String url, android.graphics.Bitmap fav) {
                // Google no deja iniciar sesión dentro de apps: la página queda pegada en accounts.google.com.
                // Se vuelve a la entrada del servicio y se explica cómo entrar con el correo.
                Uri u = Uri.parse(url);
                if ("accounts.google.com".equals(u.getHost()) && !allowed(u)) {
                    v.stopLoading();
                    v.post(() -> { v.loadUrl(pal.login); googleHelp(); });
                }
            }
            @Override public void onPageCommitVisible(WebView v, String url) { wide(); }
            @Override public void onPageFinished(WebView v, String url) {
                wide();
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

    /** Modo computador: se fija un ancho virtual para que la página se arme como en un computador. */
    private void wide() {
        if (!pal.desktop || pal.vw <= 0) return;
        web.evaluateJavascript("(function(){var m=document.querySelector('meta[name=viewport]');"
                + "if(!m){m=document.createElement('meta');m.name='viewport';(document.head||document.documentElement).appendChild(m);}"
                + "var c='width=" + pal.vw + "';if(m.content!==c)m.content=c;})();", null);
    }

    // ---------------- páginas fijadas (★) ----------------

    private org.json.JSONArray pins() {
        try { return new org.json.JSONArray(prefs.getString("pal_" + pal.id + "_pins", "[]")); } catch (Exception e) { return new org.json.JSONArray(); }
    }

    /** ★: la página que se está viendo queda en la rama "Fijadas" del panel. */
    private void pinPage() {
        String url = web.getUrl(), title = web.getTitle();
        if (url == null || !allowed(Uri.parse(url))) { Toast.makeText(this, "No hay una página para fijar", Toast.LENGTH_SHORT).show(); return; }
        org.json.JSONArray a = pins();
        for (int i = 0; i < a.length(); i++) if (url.equals(a.optJSONObject(i).optString("u"))) { Toast.makeText(this, "Ya está fijada", Toast.LENGTH_SHORT).show(); return; }
        try { a.put(new org.json.JSONObject().put("l", title == null || title.isEmpty() ? url : title).put("u", url)); } catch (Exception ignored) { }
        prefs.edit().putString("pal_" + pal.id + "_pins", a.toString()).apply();
        Toast.makeText(this, "Fijada en ★ Fijadas (mantén presionada para quitarla)", Toast.LENGTH_SHORT).show();
        for (Node n : roots) if ("pins".equals(n.list)) { n.loaded = false; if (n.open) load(n); }
        drawTree();
    }

    private void unpin(String url) {
        org.json.JSONArray a = pins(), b = new org.json.JSONArray();
        for (int i = 0; i < a.length(); i++) if (!url.equals(a.optJSONObject(i).optString("u"))) b.put(a.optJSONObject(i));
        prefs.edit().putString("pal_" + pal.id + "_pins", b.toString()).apply();
        for (Node n : roots) if ("pins".equals(n.list)) { n.loaded = false; if (n.open) load(n); }
        drawTree();
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        String id = i.getStringExtra("pal");
        if (id != null && !id.equals(pal.id)) { setIntent(i); recreate(); }   // otro PAL: se arma de nuevo
    }

    private boolean helpShown;

    private void googleHelp() {
        if (helpShown || isFinishing()) return;
        helpShown = true;
        String svc = pal.name.replace(" PAL", "");
        String msg = "claude".equals(pal.id)
                ? "1. En la pantalla de Claude, escribe tu correo (el mismo de tu cuenta de Google) y toca \"Continuar con correo\".\n"
                  + "2. Te llega un correo: escribe aquí el código que trae.\n"
                  + "3. Si en vez de código trae un enlace: mantenlo presionado en tu correo, toca \"Copiar enlace\" y vuelve aquí a "
                  + "\"⎘ Abrir enlace copiado\".\n\nEs la misma cuenta: verás tus chats y proyectos de siempre."
                : "Entra a " + svc + " con tu usuario (o correo / teléfono) y contraseña, no con el botón de Google.\n"
                  + "Si te piden un código, llega por correo o SMS: escríbelo aquí con el teclado del extendido.\n"
                  + "Si te llega un enlace, cópialo y usa \"⎘ Abrir enlace copiado\".";
        new Ui.Sheet(this).title("Entra sin el botón de Google")
                .message("Google no permite iniciar sesión dentro de otras apps, por eso se quedaba pegado.\n\n" + msg)
                .button("Entendido", true, () -> helpShown = false)
                .show();
    }

    /** Abre en el PAL un enlace copiado (por ejemplo, el enlace de inicio de sesión que llega por correo). */
    private void openCopiedLink() {
        android.content.ClipboardManager cb = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        CharSequence t = null;
        if (cb != null && cb.hasPrimaryClip() && cb.getPrimaryClip() != null && cb.getPrimaryClip().getItemCount() > 0)
            t = cb.getPrimaryClip().getItemAt(0).coerceToText(this);
        String s = t == null ? "" : t.toString().trim();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("https?://\\S+").matcher(s);
        if (!m.find()) { Toast.makeText(this, "No hay un enlace copiado", Toast.LENGTH_SHORT).show(); return; }
        Uri u = Uri.parse(m.group());
        if (!allowed(u)) { Toast.makeText(this, "Ese enlace no es de " + pal.name.replace(" PAL", ""), Toast.LENGTH_SHORT).show(); return; }
        web.loadUrl(u.toString());
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
