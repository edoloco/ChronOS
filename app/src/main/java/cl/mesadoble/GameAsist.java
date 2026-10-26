package cl.mesadoble;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Game-Asist (modo de Teclados, en el extendido): mientras RetroArch corre un juego en el principal muestra
 *  - Ficha: carátula, pantalla de título, nombre, año, desarrolladora, género y tiempo jugado (bases públicas de
 *    libretro: libretro-database y libretro-thumbnails; se bajan una vez y quedan guardadas en el teléfono);
 *  - Partidas guardadas con su miniatura (tocar una la carga);
 *  - Botones: guardar, cargar, pausa, rápido, rebobinar, reiniciar, captura, menú y ranura.
 * Sin RetroArch, espera a que se abra un juego. El principal responde con {@link RetroLink}.
 */
final class GameAsist extends LinearLayout {

    interface Change { void retro(boolean on); }

    private static final int BG = 0xFF16161C, CARD = 0xFF24242C, TXT = 0xFFEDEDF2, MUTED = 0xFF9A9AA6, RED = 0xFFE5383B;

    private final Activity a;
    private final Hub hub;
    private final Change change;
    private final float dp;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final SharedPreferences prefs;

    private boolean on, polling;
    private long lastReply, lastTick;
    private String sys = "", name = "", crc = "", st = "", shown = "";
    private int slot;

    private ImageView box, titleImg;
    private TextView nameV, infoV, timeV, statusV, slotV, statesNote, aboutV;
    private LinearLayout states;

    GameAsist(Activity a, Hub hub, Change change) {
        super(a);
        this.a = a;
        this.hub = hub;
        this.change = change;
        dp = a.getResources().getDisplayMetrics().density;
        prefs = a.getSharedPreferences("mesa", Activity.MODE_PRIVATE);
        build();
    }

    private int dp(float v) { return Math.round(v * dp); }

    boolean isOn() { return on; }

    // ---------------- ¿hay un juego en RetroArch? ----------------

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!polling) return;
            if (hub.connected()) hub.send("raPoll");
            // sin respuesta: RetroArch cerrado (o sin comandos de red)
            if (System.currentTimeMillis() - lastReply > 5000) {
                setOn(false);
                timeV.setText("Principal: " + (hub.connected() ? "conectado, sin respuesta" : "desconectado"));
                idle(hub.connected() ? "El principal no responde: actualiza ChronOS también en el principal (necesita esta misma versión)."
                        : "Sin conexión con el principal.");
            }
            h.postDelayed(this, 1500);
        }
    };

    void start() { if (!polling) { polling = true; lastReply = System.currentTimeMillis(); if (!on) nameV.setText("Esperando un juego en RetroArch…"); idle("Buscando RetroArch en el principal…"); h.post(poll); } }

    private void idle(String why) { if (!on) { infoV.setText(why); infoV.setTextColor(TXT); } }

    void stop() { polling = false; h.removeCallbacks(poll); }

    private void setOn(boolean v) {
        if (v == on) return;
        on = v;
        if (!v) {
            shown = "";
            nameV.setText("Esperando un juego en RetroArch…");
            statusV.setText("");
            timeV.setText("");
            aboutV.setText("");
            box.setImageDrawable(null);
            titleImg.setImageDrawable(null);
            states.removeAllViews();
        }
        if (change != null) change.retro(v);
    }

    void onMessage(JSONObject m) {
        String t = m.optString("t");
        if ("raSt".equals(t)) {
            boolean run = m.optBoolean("run");
            String s = m.optString("st");
            boolean game = run && !s.isEmpty() && !"CONTENTLESS".equals(s);
            lastReply = System.currentTimeMillis();
            if (!game) {
                setOn(false);
                String fg = m.optString("fg"), ver = m.optString("ver");
                // datos técnicos (para revisar a distancia con una foto)
                timeV.setText("Principal: responde · RetroArch: " + (run ? "responde (" + s + ")" : ver.isEmpty() ? "no responde" : "solo versión " + ver)
                        + " · Al frente: " + (fg.isEmpty() ? "?" : fg));
                if (run) idle("RetroArch está abierto, pero sin un juego cargado.");
                else if (!ver.isEmpty()) idle("Tu RetroArch (" + ver + ") es antiguo y no informa qué juego corre: actualízalo a la versión más nueva.");
                else if (fg.startsWith("com.retroarch")) { if (++mute >= 2) setupHelp(); idle("RetroArch está abierto pero no responde. En RetroArch: Ajustes → Red → Comandos de red: ACTIVADO "
                        + "(puerto 55355). Después ciérralo del todo (sácalo de Recientes) y ábrelo de nuevo."); }
                else idle("Abre un juego en RetroArch en el principal. Si ya está abierto: Ajustes → Red → Comandos de red: activado, "
                        + "y reinicia RetroArch.");
                return;
            }
            long now = System.currentTimeMillis();
            if ("PLAYING".equals(st) && lastTick > 0 && now - lastTick < 10000)
                prefs.edit().putLong("raTime_" + id(), prefs.getLong("raTime_" + id(), 0) + (now - lastTick)).apply();
            lastTick = now;
            st = s;
            sys = m.optString("sys");
            name = m.optString("name");
            crc = m.optString("crc");
            setOn(true);
            infoV.setTextColor(MUTED);
            if (!prefs.getBoolean("raOk", false)) prefs.edit().putBoolean("raOk", true).apply();   // ya quedó configurado: no se vuelve a explicar
            statusV.setText("RetroArch · " + ("PAUSED".equals(st) ? "en pausa" : "jugando"));
            timeV.setText("Jugado: " + played(prefs.getLong("raTime_" + id(), 0)));
            if (!id().equals(shown)) { shown = id(); loadInfo(); askStates(); }
        } else if ("raStList".equals(t)) showStates(m);
    }

    private int mute;
    private boolean helped;

    /** RetroArch abierto pero mudo: ventana con los pasos (una vez por sesión, y nunca más cuando ya funcionó). */
    private void setupHelp() {
        if (helped || prefs.getBoolean("raOk", false) || !isAttachedToWindow()) return;
        helped = true;
        new Ui.Sheet(a).title("Falta configurar RetroArch")
                .message("RetroArch está abierto, pero no le contesta a ChronOS. Hazlo una sola vez en el principal:\n\n"
                        + "1. En RetroArch toca el engranaje ⚙ (Ajustes) → Red → activa «Comandos de red» (puerto 55355).\n"
                        + "   Si no aparece: Ajustes → Interfaz de usuario → «Mostrar ajustes avanzados».\n\n"
                        + "2. Ajustes → Guardado → activa las miniaturas de las partidas guardadas.\n\n"
                        + "3. Menú principal → Archivo de configuración → «Guardar configuración actual».\n\n"
                        + "4. Cierra RetroArch del todo (Menú principal → Salir, o sácalo de Recientes) y ábrelo de nuevo.")
                .button("Entendido", true, null).show();
    }

    private String id() { return crc.isEmpty() ? name : crc; }

    private static String played(long ms) {
        long min = ms / 60000;
        return min < 60 ? min + " min" : (min / 60) + " h " + (min % 60) + " min";
    }

    private void askStates() { if (hub.connected()) hub.send("raStates", "name", name); }

    private void cmd(String c) { if (hub.connected()) hub.send("ra", "c", c); }

    // ---------------- interfaz ----------------

    private GradientDrawable round(int color, float r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(r));
        return g;
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView v = new TextView(a);
        v.setText(s);
        v.setTextSize(sp);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT_BOLD);
        return v;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(a);
        c.setOrientation(VERTICAL);
        c.setBackground(round(CARD, 16));
        c.setPadding(dp(12), dp(10), dp(12), dp(10));
        return c;
    }

    private LinearLayout.LayoutParams col(float w) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, w);
        lp.setMargins(dp(5), dp(6), dp(5), dp(6));
        return lp;
    }

    private void build() {
        setOrientation(HORIZONTAL);
        setBackgroundColor(BG);
        setPadding(dp(4), dp(2), dp(4), dp(2));

        // ficha
        LinearLayout info = card();
        LinearLayout imgs = new LinearLayout(a);
        box = new ImageView(a);
        box.setScaleType(ImageView.ScaleType.FIT_CENTER);
        titleImg = new ImageView(a);
        titleImg.setScaleType(ImageView.ScaleType.FIT_CENTER);
        imgs.addView(box, new LinearLayout.LayoutParams(0, -1, 1));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -1, 1.2f);
        tl.setMarginStart(dp(8));
        imgs.addView(titleImg, tl);
        info.addView(imgs, new LinearLayout.LayoutParams(-1, 0, 1));
        nameV = text("", 16, TXT, true);
        nameV.setMaxLines(2);
        nameV.setEllipsize(TextUtils.TruncateAt.END);
        nameV.setPadding(0, dp(8), 0, 0);
        info.addView(nameV);
        infoV = text("", 12, MUTED, false);
        infoV.setMaxLines(9);
        info.addView(infoV);
        timeV = text("", 12, TXT, false);
        info.addView(timeV);
        statusV = text("", 11, RED, true);
        info.addView(statusV);
        addView(info, col(1.05f));

        // acerca del juego e instrucciones (Wikipedia)
        LinearLayout ab = card();
        ab.addView(text("Acerca del juego", 14, TXT, true));
        android.widget.ScrollView sc = new android.widget.ScrollView(a);
        sc.setVerticalFadingEdgeEnabled(true);
        aboutV = text("", 12, 0xFFD0D0D8, false);
        aboutV.setLineSpacing(0, 1.12f);
        aboutV.setPadding(0, dp(4), 0, 0);
        sc.addView(aboutV);
        ab.addView(sc, new LinearLayout.LayoutParams(-1, 0, 1));
        addView(ab, col(1.25f));

        // partidas guardadas
        LinearLayout sv = card();
        LinearLayout sh = new LinearLayout(a);
        sh.setGravity(Gravity.CENTER_VERTICAL);
        sh.addView(text("Partidas guardadas", 14, TXT, true), new LinearLayout.LayoutParams(0, -2, 1));
        TextView rf = pill("↻", v -> askStates());
        sh.addView(rf);
        sv.addView(sh);
        statesNote = text("Toca una para cargarla", 11, MUTED, false);
        sv.addView(statesNote);
        HorizontalScrollView hs = new HorizontalScrollView(a);
        hs.setHorizontalScrollBarEnabled(false);
        states = new LinearLayout(a);
        states.setGravity(Gravity.CENTER_VERTICAL);
        hs.addView(states, new FrameLayout.LayoutParams(-2, -1));
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(-1, 0, 1);
        hl.topMargin = dp(6);
        sv.addView(hs, hl);
        addView(sv, col(1.3f));

        // botones
        LinearLayout bt = card();
        String[][] b = {{"💾 Guardar", "SAVE_STATE"}, {"📂 Cargar", "LOAD_STATE"}, {"⏯ Pausa", "PAUSE_TOGGLE"}, {"⏩ Rápido", "FAST_FORWARD"},
                {"⏪ Rebobinar", "REWIND"}, {"🐢 Lento", "SLOWMOTION"}, {"↺ Reiniciar", "RESET"}, {"📷 Captura", "SCREENSHOT"},
                {"🔇 Silencio", "MUTE"}, {"☰ Menú", "MENU_TOGGLE"}};
        for (int r = 0; r < 5; r++) {
            LinearLayout row = new LinearLayout(a);
            for (int c = 0; c < 2; c++) {
                final String[] it = b[r * 2 + c];
                TextView p = pill(it[0], v -> {
                    cmd(it[1]);
                    if ("SAVE_STATE".equals(it[1])) h.postDelayed(this::askStates, 1500);
                });
                if ("REWIND".equals(it[1])) {
                    // rebobinar en RetroArch es "mantener apretado": mientras el dedo está encima, el principal lo repite
                    p.setText("⏪ Mantén: rebobinar");
                    p.setOnClickListener(null);
                    p.setOnTouchListener((v, e) -> {
                        int ac = e.getActionMasked();
                        if (ac == android.view.MotionEvent.ACTION_DOWN) { v.setPressed(true); if (hub.connected()) hub.send("raHold", "c", "REWIND", "on", true); }
                        else if (ac == android.view.MotionEvent.ACTION_UP || ac == android.view.MotionEvent.ACTION_CANCEL) { v.setPressed(false); if (hub.connected()) hub.send("raHold", "c", "REWIND", "on", false); }
                        return true;
                    });
                }
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1);
                lp.setMargins(dp(3), dp(3), dp(3), dp(3));
                row.addView(p, lp);
            }
            bt.addView(row, new LinearLayout.LayoutParams(-1, 0, 1));
        }
        LinearLayout sr = new LinearLayout(a);
        sr.setGravity(Gravity.CENTER_VERTICAL);
        sr.addView(pill("−", v -> { slot = Math.max(0, slot - 1); cmd("STATE_SLOT_MINUS"); slotV.setText("Ranura " + slot); }));
        slotV = text("Ranura 0", 13, TXT, true);
        slotV.setGravity(Gravity.CENTER);
        sr.addView(slotV, new LinearLayout.LayoutParams(0, -2, 1));
        sr.addView(pill("+", v -> { slot = Math.min(999, slot + 1); cmd("STATE_SLOT_PLUS"); slotV.setText("Ranura " + slot); }));
        bt.addView(sr, new LinearLayout.LayoutParams(-1, dp(42)));
        addView(bt, col(1f));
    }

    private TextView pill(String s, View.OnClickListener l) {
        TextView p = text(s, 13, TXT, true);
        p.setGravity(Gravity.CENTER);
        p.setPadding(dp(12), dp(6), dp(12), dp(6));
        p.setBackground(round(0xFF34343E, 12));
        p.setForeground(a.getDrawable(android.R.drawable.list_selector_background));
        p.setOnClickListener(l);
        return p;
    }

    private void showStates(JSONObject m) {
        states.removeAllViews();
        if (m.has("err")) { statesNote.setText(m.optString("err")); return; }
        JSONArray items = m.optJSONArray("items");
        if (items == null || items.length() == 0) {
            statesNote.setText("Todavía no hay partidas guardadas de este juego (💾 Guardar).");
            return;
        }
        statesNote.setText("Toca una para cargarla");
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it == null) continue;
            final int sl = it.optInt("slot");
            LinearLayout c = new LinearLayout(a);
            c.setOrientation(VERTICAL);
            c.setGravity(Gravity.CENTER_HORIZONTAL);
            c.setPadding(dp(4), dp(4), dp(4), dp(4));
            c.setBackground(round(0xFF2E2E37, 12));
            ImageView iv = new ImageView(a);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setBackground(round(Color.BLACK, 8));
            iv.setClipToOutline(true);
            try {
                byte[] d = Base64.decode(it.optString("img"), Base64.DEFAULT);
                Bitmap bm = BitmapFactory.decodeByteArray(d, 0, d.length);
                if (bm != null) iv.setImageBitmap(bm);
            } catch (Exception ignored) { }
            c.addView(iv, new LinearLayout.LayoutParams(dp(150), 0, 1));
            String when = android.text.format.DateUtils.getRelativeTimeSpanString(it.optLong("t")).toString();
            c.addView(text((sl < 0 ? "Automática" : "Ranura " + sl) + " · " + when, 11, TXT, false));
            c.setOnClickListener(v -> {
                cmd(sl < 0 ? "LOAD_STATE" : "LOAD_STATE_SLOT " + sl);
                Toast.makeText(a, "Cargando " + (sl < 0 ? "la automática" : "ranura " + sl), Toast.LENGTH_SHORT).show();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -1);
            lp.setMarginEnd(dp(8));
            states.addView(c, lp);
        }
    }

    // ---------------- ficha (bases públicas de libretro) ----------------

    private static String db(String sys) {
        switch (sys.toLowerCase(java.util.Locale.ROOT)) {
            case "snes": case "super_nes": return "Nintendo - Super Nintendo Entertainment System";
            case "gb": case "game_boy": return "Nintendo - Game Boy";
            case "gbc": case "game_boy_color": return "Nintendo - Game Boy Color";
            case "gba": case "game_boy_advance": return "Nintendo - Game Boy Advance";
            case "n64": return "Nintendo - Nintendo 64";
            case "genesis": case "mega_drive": case "megadrive": case "md": return "Sega - Mega Drive - Genesis";
            case "master_system": case "sms": return "Sega - Master System - Mark III";
            case "game_gear": case "gg": return "Sega - Game Gear";
            case "psx": case "playstation": return "Sony - PlayStation";
            case "pce": case "pc_engine": return "NEC - PC Engine - TurboGrafx 16";
            default: return "Nintendo - Nintendo Entertainment System";
        }
    }

    private void loadInfo() {
        final String n = name, c = crc, base = db(sys);
        nameV.setText(n);
        infoV.setText("Buscando la ficha…");
        aboutV.setText("Buscando información…");
        box.setImageDrawable(null);
        titleImg.setImageDrawable(null);
        new Thread(() -> {
            String real = lookup("no-intro", base, c, n, "name");
            if (real == null || real.isEmpty()) real = n;
            String year = lookup("releaseyear", base, c, real, "releaseyear"), dev = lookup("developer", base, c, real, "developer"),
                    genre = lookup("genre", base, c, real, "genre"), users = lookup("maxusers", base, c, real, "users");
            StringBuilder sb = new StringBuilder();
            for (String s : new String[]{year, dev, genre, users == null ? null : users + (users.equals("1") ? " jugador" : " jugadores")})
                if (s != null && !s.isEmpty()) sb.append(sb.length() > 0 ? " · " : "").append(s);
            Bitmap bx = image(base, "Named_Boxarts", real), ti = image(base, "Named_Titles", real);
            if (bx == null && !real.equals(n)) bx = image(base, "Named_Boxarts", n);
            String about = wiki(real);
            final String ab = about == null ? "No encontré información de este juego en Wikipedia." : about;
            final String rn = real, inf = sb.length() == 0 ? "Sin datos en la base pública" : sb.toString();
            final Bitmap fb = bx, ft = ti;
            h.post(() -> {
                if (!id().equals(c.isEmpty() ? n : c)) return;
                nameV.setText(rn);
                infoV.setText(inf);
                aboutV.setText(ab);
                if (fb != null) box.setImageBitmap(fb);
                if (ft != null) titleImg.setImageBitmap(ft);
            });
        }, "gameasist").start();
    }

    private File cache(String sub) {
        File d = new File(a.getCacheDir(), "retro/" + sub);
        d.mkdirs();
        return d;
    }

    private static boolean download(String url, File out) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(30000);
            if (c.getResponseCode() != 200) return false;
            File tmp = new File(out.getPath() + ".tmp");
            try (InputStream in = c.getInputStream(); FileOutputStream o = new FileOutputStream(tmp)) {
                byte[] b = new byte[32768];
                int r;
                while ((r = in.read(b)) > 0) o.write(b, 0, r);
            }
            return tmp.renameTo(out);
        } catch (Exception e) { return false; }
    }

    private static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8").replace("+", "%20"); } catch (Exception e) { return s; }
    }

    /** Un dato del juego desde los .dat de libretro-database (por CRC; si no, por nombre). */
    private String lookup(String folder, String base, String crc, String name, String key) {
        try {
            File f = new File(cache("db"), folder + "_" + base.replaceAll("[^A-Za-z0-9]", "_") + ".dat");
            if (!f.exists() && !download("https://raw.githubusercontent.com/libretro/libretro-database/master/metadat/" + folder + "/" + enc(base) + ".dat", f)) return null;
            String s = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            int i = crc.isEmpty() ? -1 : s.indexOf("crc " + crc);
            if (i < 0) i = s.indexOf("name \"" + name + "\"");
            if (i < 0) return null;
            int start = s.lastIndexOf("game (", i), end = s.indexOf("\n)", i);
            if (start < 0) return null;
            String block = s.substring(start, end < 0 ? s.length() : end);
            java.util.regex.Matcher mm = java.util.regex.Pattern.compile("\\n\\s*" + key + " \"?([^\"\\n]*)\"?").matcher(block);
            return mm.find() ? mm.group(1).trim() : null;
        } catch (Exception e) { return null; }
    }

    private static String http(String url) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(20000);
            c.setRequestProperty("User-Agent", "ChronOS-GameAsist/1.0 (cl.mesadoble)");
            if (c.getResponseCode() != 200) return null;
            try (InputStream in = c.getInputStream()) {
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] b = new byte[16384];
                int r;
                while ((r = in.read(b)) > 0) bo.write(b, 0, r);
                return new String(bo.toByteArray(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) { return null; }
    }

    /**
     * Acerca del juego + cómo se juega, desde Wikipedia (primero en español, si no en inglés). Se guarda en caché.
     * Muestra la introducción y la sección de jugabilidad (Jugabilidad / Modo de juego / Gameplay).
     */
    private String wiki(String game) {
        String clean = game.replaceAll("\\s*[\\(\\[].*$", "").trim();
        File f = new File(cache("wiki"), clean.replaceAll("[^A-Za-z0-9]", "_") + ".txt");
        try { if (f.exists()) return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8); } catch (Exception ignored) { }
        for (String lang : new String[]{"es", "en"}) {
            try {
                String q = http("https://" + lang + ".wikipedia.org/w/api.php?action=query&list=search&format=json&srlimit=1&srsearch="
                        + enc(clean + (lang.equals("es") ? " videojuego" : " video game")));
                if (q == null) continue;
                JSONArray hits = new JSONObject(q).optJSONObject("query").optJSONArray("search");
                if (hits == null || hits.length() == 0) continue;
                String title = hits.optJSONObject(0).optString("title");
                String ex = http("https://" + lang + ".wikipedia.org/w/api.php?action=query&prop=extracts&explaintext=1&format=json&redirects=1&titles=" + enc(title));
                if (ex == null) continue;
                JSONObject pages = new JSONObject(ex).optJSONObject("query").optJSONObject("pages");
                String txt = null;
                for (java.util.Iterator<String> it = pages.keys(); it.hasNext(); ) txt = pages.optJSONObject(it.next()).optString("extract", null);
                if (txt == null || txt.length() < 80) continue;
                StringBuilder out = new StringBuilder();
                int firstH = txt.indexOf("\n== ");
                out.append((firstH > 0 ? txt.substring(0, firstH) : txt).trim());
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("\n==\\s*(Jugabilidad|Modo de juego|Sistema de juego|Mecánica de juego|Gameplay)\\s*==\n", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(txt);
                if (m.find()) {
                    int s = m.end(), e = txt.indexOf("\n== ", s);
                    String sec = txt.substring(s, e < 0 ? txt.length() : e).replaceAll("\n=== ?(.*?) ?===", "\n▸ $1").trim();
                    out.append("\n\n🎮 ").append(lang.equals("es") ? "Cómo se juega" : "Cómo se juega (en inglés)").append("\n").append(sec);
                }
                out.append("\n\nFuente: Wikipedia — ").append(title);
                String res = out.toString();
                if (res.length() > 9000) res = res.substring(0, 9000) + "…";
                try (FileOutputStream o = new FileOutputStream(f)) { o.write(res.getBytes(StandardCharsets.UTF_8)); }
                return res;
            } catch (Exception ignored) { }
        }
        return null;
    }

    /** Imagen de libretro-thumbnails (los caracteres &*\/:`<>?\| van como _). */
    private Bitmap image(String base, String kind, String name) {
        try {
            String fn = name.replaceAll("[&*/:`<>?\\\\|\"]", "_");
            File f = new File(cache("img"), (base + "_" + kind + "_" + fn).replaceAll("[^A-Za-z0-9._-]", "_") + ".png");
            if (!f.exists() && !download("https://raw.githubusercontent.com/libretro-thumbnails/" + enc(base.replace(" ", "_"))
                    + "/master/" + kind + "/" + enc(fn) + ".png", f)) return null;
            return BitmapFactory.decodeFile(f.getPath());
        } catch (Exception e) { return null; }
    }
}
