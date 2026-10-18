package cl.mesadoble;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

import java.util.ArrayList;
import java.util.List;

/**
 * PALS: "compañeritos" para las apps que no se ven bien en ChronOS (hechas para un teléfono en vertical).
 * Cada PAL abre la versión web del servicio y la acomoda a ChronOS (horizontal, cristal naranjo). En la lista de
 * apps y en la mesa se ven como una app más, con paquete "pal:<id>" y un ícono propio: una esfera de cristal
 * naranjo (como una naranja, con su hojita) con un personajito pixelado adentro.
 */
final class Pals {

    private Pals() { }

    /** Una opción del panel lateral: símbolo, nombre y la dirección que abre. */
    static final class Item {
        final String glyph, label, url, list;   // list: qué se despliega como árbol ("chats", "projects") o null
        Item(String glyph, String label, String url, String list) { this.glyph = glyph; this.label = label; this.url = url; this.list = list; }
    }

    /** La receta de un PAL. */
    static final class Pal {
        final String id, name, home;
        String login, group = "ia";
        boolean desktop, custom;   // desktop: la página se pide como computador (vista ancha, mejor en horizontal)
        int vw;                    // ancho virtual de la página en modo computador (0 = el real)
        final int accent;
        final String[] hosts;   // dominios que se abren dentro del PAL (el resto, en el navegador)
        final List<Item> menu = new ArrayList<>();
        Pal(String id, String name, String home, int accent, String... hosts) {
            this.id = id; this.name = name; this.home = home; this.accent = accent; this.hosts = hosts;
            this.login = home;
        }
        Pal item(String g, String l, String u) { menu.add(new Item(g, l, u, null)); return this; }
        Pal tree(String g, String l, String u, String list) { menu.add(new Item(g, l, u, list)); return this; }
        Pal login(String u) { login = u; return this; }
        Pal desk(int width) { desktop = true; vw = width; return this; }
        Pal group(String g) { group = g; return this; }
    }

    static final List<Pal> ALL = new ArrayList<>();
    static {
        ALL.add(new Pal("claude", "Claude PAL", "https://claude.ai/new", 0xFFD97757,
                "claude.ai", "claude.com", "anthropic.com")
                .login("https://claude.ai/login")
                .item("✚", "Nuevo chat", "https://claude.ai/new")
                .tree("☰", "Chats", "https://claude.ai/recents", "chats")
                .tree("▣", "Proyectos", "https://claude.ai/projects", "projects")
                .item("◆", "Artefactos", "https://claude.ai/artifacts")
                .item("⚙", "Ajustes", "https://claude.ai/settings"));

        // ---- catálogo de redes sociales (hechas para el teléfono en vertical): se activan en Herramientas → PALS
        ALL.add(new Pal("instagram", "Instagram PAL", "https://www.instagram.com/", 0xFFE1306C, "instagram.com", "facebook.com")
                .group("social").desk(1100)
                .item("⌂", "Inicio", "https://www.instagram.com/")
                .item("⌕", "Explorar", "https://www.instagram.com/explore/")
                .item("▶", "Reels", "https://www.instagram.com/reels/")
                .item("✉", "Mensajes", "https://www.instagram.com/direct/inbox/")
                .item("♡", "Notificaciones", "https://www.instagram.com/accounts/activity/"));
        ALL.add(new Pal("facebook", "Facebook PAL", "https://www.facebook.com/", 0xFF1877F2, "facebook.com", "fb.com", "messenger.com")
                .group("social").desk(1150)
                .item("⌂", "Inicio", "https://www.facebook.com/")
                .item("▶", "Video", "https://www.facebook.com/watch/")
                .item("◫", "Marketplace", "https://www.facebook.com/marketplace/")
                .item("◎", "Grupos", "https://www.facebook.com/groups/feed/")
                .item("✉", "Mensajes", "https://www.facebook.com/messages/")
                .item("♡", "Notificaciones", "https://www.facebook.com/notifications/"));
        ALL.add(new Pal("x", "X PAL", "https://x.com/home", 0xFF8A8F98, "x.com", "twitter.com")
                .group("social").desk(1100)
                .item("⌂", "Inicio", "https://x.com/home")
                .item("⌕", "Explorar", "https://x.com/explore")
                .item("♡", "Notificaciones", "https://x.com/notifications")
                .item("✉", "Mensajes", "https://x.com/messages")
                .item("★", "Guardados", "https://x.com/i/bookmarks"));
        ALL.add(new Pal("threads", "Threads PAL", "https://www.threads.com/", 0xFF444444, "threads.com", "threads.net", "instagram.com")
                .group("social").desk(1050)
                .item("⌂", "Inicio", "https://www.threads.com/")
                .item("⌕", "Buscar", "https://www.threads.com/search")
                .item("♡", "Actividad", "https://www.threads.com/activity"));
        ALL.add(new Pal("tiktok", "TikTok PAL", "https://www.tiktok.com/foryou", 0xFF25F4EE, "tiktok.com")
                .group("social").desk(1150)
                .item("⌂", "Para ti", "https://www.tiktok.com/foryou")
                .item("◎", "Siguiendo", "https://www.tiktok.com/following")
                .item("⌕", "Explorar", "https://www.tiktok.com/explore")
                .item("✉", "Mensajes", "https://www.tiktok.com/messages"));
        ALL.add(new Pal("pinterest", "Pinterest PAL", "https://www.pinterest.com/", 0xFFE60023, "pinterest.com", "pinterest.cl")
                .group("social").desk(1150)
                .item("⌂", "Inicio", "https://www.pinterest.com/")
                .item("⌕", "Explorar", "https://www.pinterest.com/ideas/"));
        ALL.add(new Pal("reddit", "Reddit PAL", "https://www.reddit.com/", 0xFFFF4500, "reddit.com", "redd.it")
                .group("social").desk(1150)
                .item("⌂", "Inicio", "https://www.reddit.com/")
                .item("▲", "Popular", "https://www.reddit.com/r/popular/")
                .item("✉", "Mensajes", "https://www.reddit.com/message/inbox/"));
        ALL.add(new Pal("linkedin", "LinkedIn PAL", "https://www.linkedin.com/feed/", 0xFF0A66C2, "linkedin.com")
                .group("social").desk(1150)
                .item("⌂", "Inicio", "https://www.linkedin.com/feed/")
                .item("◎", "Mi red", "https://www.linkedin.com/mynetwork/")
                .item("▣", "Empleos", "https://www.linkedin.com/jobs/")
                .item("✉", "Mensajes", "https://www.linkedin.com/messaging/")
                .item("♡", "Notificaciones", "https://www.linkedin.com/notifications/"));
        ALL.add(new Pal("bluesky", "Bluesky PAL", "https://bsky.app/", 0xFF1185FE, "bsky.app")
                .group("social").desk(1050)
                .item("⌂", "Inicio", "https://bsky.app/")
                .item("⌕", "Buscar", "https://bsky.app/search")
                .item("♡", "Notificaciones", "https://bsky.app/notifications")
                .item("✉", "Mensajes", "https://bsky.app/messages"));
        ALL.add(new Pal("whatsapp", "WhatsApp Web PAL", "https://web.whatsapp.com/", 0xFF25D366, "whatsapp.com")
                .group("social").desk(1200)
                .item("✉", "Chats", "https://web.whatsapp.com/"));
        ALL.add(new Pal("telegram", "Telegram Web PAL", "https://web.telegram.org/a/", 0xFF2AABEE, "telegram.org", "t.me")
                .group("social").desk(1150)
                .item("✉", "Chats", "https://web.telegram.org/a/"));
    }

    // ---------------- cuáles están activos y los PALS propios (de cualquier página) ----------------

    static android.content.SharedPreferences prefs(Context c) { return c.getSharedPreferences("mesa", Context.MODE_PRIVATE); }

    static boolean enabled(Context c, Pal p) {
        if (p.custom) return true;
        return prefs(c).getStringSet("pals_on", java.util.Collections.singleton("claude")).contains(p.id);
    }

    static void setEnabled(Context c, String id, boolean on) {
        java.util.Set<String> s = new java.util.HashSet<>(prefs(c).getStringSet("pals_on", java.util.Collections.singleton("claude")));
        if (on) s.add(id); else s.remove(id);
        prefs(c).edit().putStringSet("pals_on", s).apply();
    }

    /** PALS propios: {id, name, url, desk}. */
    static List<Pal> customs(Context c) {
        List<Pal> l = new ArrayList<>();
        try {
            org.json.JSONArray a = new org.json.JSONArray(prefs(c).getString("pals_custom", "[]"));
            for (int i = 0; i < a.length(); i++) {
                org.json.JSONObject o = a.getJSONObject(i);
                String url = o.optString("url");
                android.net.Uri u = android.net.Uri.parse(url);
                String host = u.getHost() == null ? "" : u.getHost().replaceFirst("^www\\.", "").replaceFirst("^m\\.", "");
                Pal p = new Pal(o.optString("id"), o.optString("name"), url, accentFor(host), host);
                p.custom = true;
                p.group = "propio";
                if (o.optBoolean("desk", true)) p.desk(1150);
                p.item("⌂", "Inicio", url);
                l.add(p);
            }
        } catch (Exception ignored) { }
        return l;
    }

    static void addCustom(Context c, String name, String url, boolean desk) {
        try {
            org.json.JSONArray a = new org.json.JSONArray(prefs(c).getString("pals_custom", "[]"));
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("id", "c" + Long.toString(System.currentTimeMillis(), 36));
            o.put("name", name);
            o.put("url", url);
            o.put("desk", desk);
            a.put(o);
            prefs(c).edit().putString("pals_custom", a.toString()).apply();
        } catch (Exception ignored) { }
    }

    static void removeCustom(Context c, String id) {
        try {
            org.json.JSONArray a = new org.json.JSONArray(prefs(c).getString("pals_custom", "[]")), b = new org.json.JSONArray();
            for (int i = 0; i < a.length(); i++) if (!id.equals(a.getJSONObject(i).optString("id"))) b.put(a.getJSONObject(i));
            prefs(c).edit().putString("pals_custom", b.toString()).apply();
        } catch (Exception ignored) { }
    }

    /** Lo que se comparte con el otro teléfono (para que la lista de apps sea la misma en los dos). */
    static org.json.JSONObject syncState(Context c) {
        org.json.JSONObject o = new org.json.JSONObject();
        try {
            o.put("on", new org.json.JSONArray(prefs(c).getStringSet("pals_on", java.util.Collections.singleton("claude"))));
            o.put("custom", prefs(c).getString("pals_custom", "[]"));
        } catch (Exception ignored) { }
        return o;
    }

    static void applySync(Context c, org.json.JSONObject o) {
        if (o == null) return;
        java.util.Set<String> on = new java.util.HashSet<>();
        org.json.JSONArray a = o.optJSONArray("on");
        for (int i = 0; a != null && i < a.length(); i++) on.add(a.optString(i));
        prefs(c).edit().putStringSet("pals_on", on).putString("pals_custom", o.optString("custom", "[]")).apply();
    }

    private static int accentFor(String host) {
        int[] cols = {0xFF2FA866, 0xFF1E88E5, 0xFF8E24AA, 0xFFE53935, 0xFF00897B, 0xFFFDD835, 0xFF6D4C41};
        return cols[Math.abs(host.hashCode()) % cols.length];
    }

    /** Los PALS que se muestran: los activos del catálogo y los propios. */
    static List<Pal> active(Context c) {
        List<Pal> l = new ArrayList<>();
        for (Pal p : ALL) if (enabled(c, p)) l.add(p);
        l.addAll(customs(c));
        return l;
    }

    static Pal get(Context c, String id) {
        for (Pal p : ALL) if (p.id.equals(id)) return p;
        for (Pal p : customs(c)) if (p.id.equals(id)) return p;
        return null;
    }

    static boolean isPal(String pkg) { return pkg != null && pkg.startsWith("pal:"); }

    static Intent intent(Context c, String id) {
        return new Intent(c, PalActivity.class).putExtra("pal", id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }

    /** Para la lista de apps: los PALS van primero. */
    static List<AppsDialog.App> asApps(Context c) {
        List<AppsDialog.App> l = new ArrayList<>();
        for (Pal p : active(c)) l.add(new AppsDialog.App("pal:" + p.id, p.name, new BitmapDrawable(c.getResources(), icon(p, 192))));
        return l;
    }

    // ---------------- ícono ----------------

    /** Personajito (10 × 8): X cuerpo, O ojos, M boca, . vacío. Diseño propio de ChronOS. */
    private static final String[] SPRITE = {
            "..XXXXXX..",
            ".XXXXXXXX.",
            "XXOOXXOOXX",
            "XXOOXXOOXX",
            "XXXXXXXXXX",
            "XXXMMMMXXX",
            ".XXXXXXXX.",
            ".X.X..X.X."};

    static Bitmap icon(Pal p, int size) {
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        float s = size, r = s * 0.44f, cx = s / 2, cy = s * 0.54f;
        Paint pt = new Paint(Paint.ANTI_ALIAS_FLAG);
        // sombra suave
        pt.setColor(0x55000000);
        pt.setMaskFilter(new android.graphics.BlurMaskFilter(s * 0.04f, android.graphics.BlurMaskFilter.Blur.NORMAL));
        c.drawCircle(cx, cy + s * 0.03f, r, pt);
        pt.setMaskFilter(null);
        // esfera de cristal naranjo
        pt.setShader(new RadialGradient(cx - r * 0.35f, cy - r * 0.4f, r * 1.5f,
                new int[]{0xFFFFC27A, 0xFFF57C1F, 0xFFB8480C}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, r, pt);
        // un toque del color del servicio, abajo
        pt.setShader(new LinearGradient(0, cy, 0, cy + r, 0x00000000, (p.accent & 0x00FFFFFF) | 0x66000000, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, r, pt);
        // brillo de vidrio arriba a la izquierda
        pt.setShader(new LinearGradient(0, cy - r, 0, cy, 0xB3FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawOval(new RectF(cx - r * 0.7f, cy - r * 0.92f, cx + r * 0.45f, cy - r * 0.1f), pt);
        pt.setShader(null);
        // borde
        pt.setStyle(Paint.Style.STROKE);
        pt.setStrokeWidth(s * 0.015f);
        pt.setColor(0x99FFFFFF);
        c.drawCircle(cx, cy, r - s * 0.008f, pt);
        pt.setStyle(Paint.Style.FILL);
        // hojita verde (como una naranja)
        pt.setColor(0xFF2FA866);
        c.save();
        c.rotate(-30, cx + r * 0.15f, cy - r);
        c.drawOval(new RectF(cx + r * 0.05f, cy - r * 1.18f, cx + r * 0.55f, cy - r * 0.9f), pt);
        c.restore();
        // personajito pixelado
        float px = r * 0.12f, w = SPRITE[0].length() * px, h = SPRITE.length * px;
        float x0 = cx - w / 2, y0 = cy - h / 2 + r * 0.08f;
        for (int y = 0; y < SPRITE.length; y++) for (int x = 0; x < SPRITE[y].length(); x++) {
            char ch = SPRITE[y].charAt(x);
            if (ch == '.') continue;
            pt.setColor(ch == 'X' ? 0xFFFFF4E6 : ch == 'O' ? 0xFF2A1A12 : p.accent);
            c.drawRect(x0 + x * px, y0 + y * px, x0 + (x + 1) * px + 0.5f, y0 + (y + 1) * px + 0.5f, pt);
        }
        return b;
    }

    static Drawable drawable(Context c, Pal p) { return new BitmapDrawable(c.getResources(), icon(p, 192)); }
}
