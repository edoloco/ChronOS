package cl.mesadoble;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Game Pal en el PRINCIPAL: habla con RetroArch por sus "comandos de red" (UDP 127.0.0.1:55355; en RetroArch:
 * Ajustes → Red → Comandos de red). Responde al extendido:
 *  - raPoll   → raSt {run, st, sys, name, crc}: qué juego corre (GET_STATUS).
 *  - ra {c}   → manda el comando tal cual (SAVE_STATE, LOAD_STATE_SLOT 3, PAUSE_TOGGLE…).
 *  - raStates {name} → raStList {items:[{slot, t, img}]}: las partidas guardadas del juego, con su miniatura.
 */
final class RetroLink {

    private static final ExecutorService ex = Executors.newSingleThreadExecutor();
    private static String statesDir;

    static void handle(Hub hub, JSONObject m) { ex.execute(() -> { try { run(hub, m); } catch (Exception ignored) { } }); }

    static String udp(String cmd, boolean reply) {
        try (DatagramSocket s = new DatagramSocket()) {
            s.setSoTimeout(450);
            byte[] b = cmd.getBytes(StandardCharsets.UTF_8);
            s.send(new DatagramPacket(b, b.length, InetAddress.getByName("127.0.0.1"), 55355));
            if (!reply) return null;
            byte[] buf = new byte[8192];
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            s.receive(p);
            return new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8).trim();
        } catch (Exception e) { return null; }
    }

    private static void run(Hub hub, JSONObject m) throws Exception {
        String t = m.optString("t");
        if ("ra".equals(t)) { udp(m.optString("c"), false); return; }
        if ("raPoll".equals(t)) {
            JSONObject o = new JSONObject().put("t", "raSt");
            String r = udp("GET_STATUS", true);
            o.put("run", r != null);
            // "GET_STATUS PLAYING nes,Super Mario Bros. (World),crc32=3337ec46"  (el nombre puede tener comas)
            if (r != null && r.startsWith("GET_STATUS ")) {
                String rest = r.substring(11).trim();
                int sp = rest.indexOf(' ');
                String st = sp < 0 ? rest : rest.substring(0, sp);
                o.put("st", st);
                if (sp > 0) {
                    String info = rest.substring(sp + 1);
                    int c1 = info.indexOf(','), cc = info.lastIndexOf(",crc32=");
                    o.put("sys", c1 < 0 ? info : info.substring(0, c1));
                    if (c1 >= 0) o.put("name", cc > c1 ? info.substring(c1 + 1, cc) : info.substring(c1 + 1));
                    if (cc >= 0) o.put("crc", info.substring(cc + 7).trim().toUpperCase(java.util.Locale.ROOT));
                }
            }
            hub.send(o);
            return;
        }
        if ("raStates".equals(t)) {
            JSONObject o = new JSONObject().put("t", "raStList");
            String name = m.optString("name");
            File dir = dir();
            List<File> found = new ArrayList<>();
            if (dir != null) find(dir, name, found, 0);
            if (found.isEmpty()) for (String c : new String[]{"/storage/emulated/0/RetroArch", "/storage/emulated/0/retroarch", "/storage/emulated/0/ROMs", "/storage/emulated/0/Roms"}) {
                File f = new File(c);
                if (f.isDirectory()) find(f, name, found, 0);
                if (!found.isEmpty()) break;
            }
            if (dir != null && dir.listFiles() == null) o.put("err", "ChronOS no puede leer la carpeta de partidas: dale permiso de archivos en el principal.");
            found.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            JSONArray items = new JSONArray();
            for (int i = 0; i < found.size() && i < 12; i++) {
                File f = found.get(i);
                String suf = f.getName().substring(f.getName().lastIndexOf(".state") + 6);
                int slot = suf.isEmpty() ? 0 : "auto".equals(suf) ? -1 : parse(suf);
                if (slot < -1) continue;
                JSONObject it = new JSONObject().put("slot", slot).put("t", f.lastModified());
                File png = new File(f.getPath() + ".png");
                if (png.exists()) { String img = thumb(png); if (img != null) it.put("img", img); }
                items.put(it);
            }
            o.put("items", items);
            hub.send(o);
        }
    }

    private static int parse(String s) { try { return Integer.parseInt(s); } catch (Exception e) { return -9; } }

    /** Carpeta de partidas guardadas que usa RetroArch (se pregunta una vez). */
    private static File dir() {
        if (statesDir == null) {
            String r = udp("GET_CONFIG_PARAM savestate_directory", true);
            String k = "GET_CONFIG_PARAM savestate_directory";
            statesDir = r != null && r.startsWith(k) ? r.substring(k.length()).trim() : "";
        }
        if (statesDir.isEmpty()) return null;
        File f = new File(statesDir);
        return f.isDirectory() ? f : null;
    }

    private static String key(String s) { return s.toLowerCase(java.util.Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", ""); }

    /** Archivos "<juego>.stateN" (también dentro de subcarpetas por núcleo). */
    private static void find(File d, String name, List<File> out, int depth) {
        File[] fs = d.listFiles();
        if (fs == null || depth > 2) return;
        String kn = key(name);
        for (File f : fs) {
            if (f.isDirectory()) { if (!f.getName().startsWith(".") && !"Android".equals(f.getName())) find(f, name, out, depth + 1); continue; }
            String n = f.getName();
            int i = n.lastIndexOf(".state");
            if (i <= 0 || n.endsWith(".png")) continue;
            String base = n.substring(0, i);
            if (base.equals(name) || (!kn.isEmpty() && key(base).equals(kn))) out.add(f);
        }
    }

    private static String thumb(File png) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 2;
            Bitmap b = BitmapFactory.decodeFile(png.getPath(), o);
            if (b == null) return null;
            int w = 260, hh = Math.max(1, b.getHeight() * w / b.getWidth());
            Bitmap s = Bitmap.createScaledBitmap(b, w, hh, true);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            s.compress(Bitmap.CompressFormat.JPEG, 75, bo);
            return Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) { return null; }
    }
}
