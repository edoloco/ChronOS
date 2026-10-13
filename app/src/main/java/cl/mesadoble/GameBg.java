package cl.mesadoble;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fondo de juego: cuando en el principal se abre un juego, el extendido pone de fondo una imagen del juego.
 * La imagen sale de las capturas que el juego tiene publicadas en Google Play (en horizontal, en alta
 * resolución); si no hay internet o no está en Play, se arma un fondo con el ícono del juego.
 *   game (principal → extendido) {pkg, name, icon}   pkg "" = se cerró el juego
 */
final class GameBg {

    /** Fondo actual (null = el color de la mesa). */
    static volatile Bitmap current;
    static volatile String currentPkg = "";

    private static String lastSent = "";
    private static long nonGameSince;

    private GameBg() { }

    static boolean enabled(Context c) { return c.getSharedPreferences("mesa", Context.MODE_PRIVATE).getBoolean("gameBg", true); }

    // emuladores que no se declaran como juegos
    private static final String[] EMULATORS = {"com.retroarch", "org.ppsspp.", "org.dolphinemu.", "com.explusalpha.",
            "it.dbtecno.pizzaboy", "com.nostalgiaemulators.", "xyz.aethersx2.", "com.github.stenzek.duckstation",
            "org.citra.", "skyline.", "com.antutu.ABenchMark.placeholder", "org.yuzu.", "com.fms.", "com.epsxe.",
            "com.seleuco.mame4droid", "org.mupen64plusae.", "com.dsemu.", "me.magnum.melonds", "com.swordfish.lemuroid"};

    static boolean isGame(Context c, String pkg) {
        if (pkg == null || pkg.isEmpty() || pkg.startsWith("@")) return false;
        for (String e : EMULATORS) if (pkg.startsWith(e)) return true;
        try {
            ApplicationInfo ai = c.getPackageManager().getApplicationInfo(pkg, 0);
            if (ai.category == ApplicationInfo.CATEGORY_GAME) return true;
            return (ai.flags & ApplicationInfo.FLAG_IS_GAME) != 0;
        } catch (Exception e) { return false; }
    }

    /** Principal (cada 1,5 s, en segundo plano): ¿cambió el juego al frente? */
    static void poll(Context c, Hub hub) {
        if (!RecentApps.hasAccess(c)) return;
        String act = RecentApps.active(c);
        boolean game = isGame(c, act);
        Boost.gameInFront = game;
        long now = System.currentTimeMillis();
        if (game) {
            nonGameSince = 0;
            if (act.equals(lastSent)) return;
            lastSent = act;
            JSONObject m = new JSONObject();
            try {
                PackageManager pm = c.getPackageManager();
                ApplicationInfo ai = pm.getApplicationInfo(act, 0);
                m.put("t", "game"); m.put("pkg", act); m.put("name", String.valueOf(pm.getApplicationLabel(ai)));
                m.put("icon", AppsDialog.iconPng(pm.getApplicationIcon(ai), 192));
            } catch (Exception ignored) { }
            hub.send(m);
        } else if (!lastSent.isEmpty()) {
            // se salió del juego (unos segundos, para no parpadear al pasar por otra pantalla)
            if (nonGameSince == 0) nonGameSince = now;
            else if (now - nonGameSince > 3000) { lastSent = ""; hub.send("game", "pkg", ""); }
        }
    }

    /** Extendido: llega el juego; se busca (o arma) la imagen y se avisa a la mesa. */
    static void handle(Context c, Hub hub, JSONObject m) {
        String pkg = m.optString("pkg");
        if (pkg.isEmpty() || !enabled(c)) { set(hub, null, ""); return; }
        if (pkg.equals(currentPkg) && current != null) return;
        String icon = m.optString("icon"), name = m.optString("name");
        RecentApps.worker.execute(() -> {
            Bitmap b = load(c, pkg);
            if (b == null) b = fetchPlay(c, pkg);
            if (b == null) b = fromIcon(icon);
            if (b != null) set(hub, b, pkg);
        });
    }

    private static void set(Hub hub, Bitmap b, String pkg) {
        current = b;
        currentPkg = pkg;
        JSONObject n = new JSONObject();
        try { n.put("t", "gameBg"); n.put("pkg", pkg); } catch (Exception ignored) { }
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> hub.local(n));
    }

    private static File cache(Context c, String pkg) {
        File d = new File(c.getCacheDir(), "juegos");
        d.mkdirs();
        return new File(d, pkg + ".jpg");
    }

    private static Bitmap load(Context c, String pkg) {
        File f = cache(c, pkg);
        if (!f.exists() || System.currentTimeMillis() - f.lastModified() > 30L * 24 * 3600 * 1000) return null;
        return BitmapFactory.decodeFile(f.getAbsolutePath());
    }

    private static final Pattern SHOT = Pattern.compile("(https://play-lh\\.googleusercontent\\.com/[A-Za-z0-9_\\-]+)=w(\\d+)-h(\\d+)");

    /** Primera captura horizontal del juego en Google Play, a 1600×900. */
    private static Bitmap fetchPlay(Context c, String pkg) {
        try {
            String html = get("https://play.google.com/store/apps/details?id=" + pkg + "&hl=es", 2_000_000);
            if (html == null) return null;
            // la primera imagen horizontal grande de la página (las capturas del juego)
            Matcher mt = SHOT.matcher(html);
            String url = null;
            while (mt.find()) {
                int w = Integer.parseInt(mt.group(2)), h = Integer.parseInt(mt.group(3));
                if (w > h && w >= 400) { url = mt.group(1) + "=w1600-h900"; break; }
            }
            if (url == null) return null;
            HttpURLConnection h = open(url);
            if (h.getResponseCode() != 200) return null;
            byte[] data;
            try (InputStream in = h.getInputStream()) { data = read(in, 8_000_000); }
            Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length);
            if (b == null) return null;
            try (FileOutputStream o = new FileOutputStream(cache(c, pkg))) { o.write(data); }
            return b;
        } catch (Exception e) { return null; }
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36");
        h.setConnectTimeout(8000);
        h.setReadTimeout(12000);
        return h;
    }

    private static String get(String url, int max) throws Exception {
        HttpURLConnection h = open(url);
        if (h.getResponseCode() != 200) return null;
        try (InputStream in = h.getInputStream()) { return new String(read(in, max), StandardCharsets.UTF_8); }
    }

    private static byte[] read(InputStream in, int max) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0 && b.size() < max) b.write(buf, 0, n);
        return b.toByteArray();
    }

    /** Sin internet: el ícono del juego en grande y desenfocado, con su color, y el ícono nítido al centro. */
    static Bitmap fromIcon(String b64) {
        try {
            byte[] d = Base64.decode(b64, Base64.DEFAULT);
            Bitmap icon = BitmapFactory.decodeByteArray(d, 0, d.length);
            if (icon == null) return null;
            int W = 1280, H = 720;
            Bitmap out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(out);
            Bitmap small = Bitmap.createScaledBitmap(icon, 64, 36, true).copy(Bitmap.Config.ARGB_8888, true);
            Glass.boxBlur(small, 4);
            Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
            c.drawBitmap(small, null, new RectF(0, 0, W, H), p);
            Paint shade = new Paint();
            shade.setShader(new RadialGradient(W / 2f, H / 2f, W * 0.7f, 0x22000000, 0xAA000000, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, W, H, shade);
            float s = H * 0.42f;
            Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
            glow.setShader(new RadialGradient(W / 2f, H / 2f, s, 0x55FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            c.drawCircle(W / 2f, H / 2f, s, glow);
            c.drawBitmap(icon, null, new RectF(W / 2f - s / 2, H / 2f - s / 2, W / 2f + s / 2, H / 2f + s / 2), p);
            Paint fade = new Paint();
            fade.setShader(new LinearGradient(0, H * 0.7f, 0, H, 0x00000000, 0x66000000, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, W, H, fade);
            return out;
        } catch (Exception e) { return null; }
    }
}
