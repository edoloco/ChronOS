package cl.mesadoble;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.RectF;
import android.text.StaticLayout;
import android.util.Base64;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;

/** Un elemento del escritorio. x va de 0 a 2 (anchos de pantalla), y de 0 a 1 (alto de pantalla). */
public class Item {
    public static final String NOTE = "note", IMG = "img", LINK = "link", APP = "app", WIDGET = "widget", CLOCK = "clock", BATTERY = "battery", RECENTS = "recents", CAMERA = "camera", TORCH = "torch", BRIGHT = "bright", CALLS = "calls";

    public String id = UUID.randomUUID().toString();
    public String kind = NOTE;
    public String text = "";
    public String url = "";
    public String title = "";
    public String img = "";      // JPEG (foto) o PNG (ícono de app) en base64
    public String ik = "";       // huella de la imagen: viaja con cada cambio; la imagen, solo una vez
    public String pkg = "";      // paquete de la app (elementos tipo APP)
    public int color = 0xFFF6E6A2;
    public double x, y, w = 0.62;
    public long z = System.currentTimeMillis();
    public long u = System.currentTimeMillis();   // última modificación (solo para desempatar)
    public int rev = 1;                           // versión: sube con cada cambio
    public float rot = 0f;
    public int wid = -1;                          // widget: su número en el PRINCIPAL (-1 = sin activar)
    public double ar = 0.6;                       // widget: alto / ancho del contenido
    public boolean pin;                           // fijo: no se mueve y se ve en todos los escritorios
    public int page = 0;                          // escritorio (0 = el primero; los demás van a los lados)
    public int sz = 0;                            // apps: 0 = cuadrado estándar, 1 = cuadrado grande

    // Estado solo local (no se envía)
    public float dx = Float.NaN, dy = Float.NaN;  // posición mostrada, animada
    public float hPx = 0;                         // alto medido en píxeles
    public final RectF sendBtn = new RectF(), delBtn = new RectF(), openBtn = new RectF(), moreBtn = new RectF();
    public Bitmap bmp;
    public volatile boolean decoding;
    public StaticLayout layout;
    public String layoutKey = "";

    /** Para guardar en el teléfono (con imagen). */
    public JSONObject toJson() { return toJson(true); }

    /** withImg=false para la red: la imagen pesa mucho y se manda aparte, una sola vez. */
    public JSONObject toJson(boolean withImg) {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id); o.put("kind", kind); o.put("text", text); o.put("url", url);
            o.put("title", title); o.put("color", color); o.put("pkg", pkg); o.put("ik", ik);
            if (withImg) o.put("img", img);
            o.put("x", x); o.put("y", y); o.put("w", w); o.put("z", z); o.put("u", u); o.put("rev", rev); o.put("rot", (double) rot); o.put("page", page); o.put("ar", ar); o.put("wid", wid); o.put("pin", pin); o.put("sz", sz);
        } catch (JSONException ignored) { }
        return o;
    }

    public static Item fromJson(JSONObject o) {
        Item it = new Item();
        it.id = o.optString("id", it.id);
        it.kind = o.optString("kind", NOTE);
        it.text = o.optString("text", "");
        it.url = o.optString("url", "");
        it.title = o.optString("title", "");
        it.img = o.optString("img", "");
        it.pkg = o.optString("pkg", "");
        it.ik = o.optString("ik", "");
        if (it.ik.isEmpty() && !it.img.isEmpty()) it.ik = keyOf(it.img);
        it.color = o.optInt("color", 0xFFF6E6A2);
        it.x = o.optDouble("x", 0.2);
        it.y = o.optDouble("y", 0.2);
        it.w = o.optDouble("w", 0.62);
        it.z = o.optLong("z", 0);
        it.u = o.optLong("u", 0);
        it.rev = o.optInt("rev", 1);
        it.rot = (float) o.optDouble("rot", 0);
        it.page = Math.max(0, Math.min(49, o.optInt("page", 0)));
        it.sz = o.optInt("sz", 0);
        it.wid = o.optInt("wid", -1);
        it.pin = o.optBoolean("pin", false);
        it.ar = Math.max(0.15, Math.min(4, o.optDouble("ar", 0.6)));
        // nunca dejar un elemento fuera de ambas pantallas
        if (Double.isNaN(it.w) || it.w <= 0.05 || it.w > 1) it.w = 0.62;
        if (Double.isNaN(it.x)) it.x = 0.2;
        if (Double.isNaN(it.y)) it.y = 0.2;
        // el encuadre dentro de la mesa lo hace DeskView.normalize (depende de cómo están unidos)
        return it;
    }

    /** Copia los datos compartidos de otro elemento, conservando la animación local. */
    public void copyFrom(Item o) {
        kind = o.kind; text = o.text; url = o.url; title = o.title; color = o.color; pkg = o.pkg;
        if (!o.img.isEmpty()) { if (!o.img.equals(img)) bmp = null; img = o.img; ik = o.ik; }
        else if (!o.ik.equals(ik)) { img = ""; ik = o.ik; bmp = null; }   // cambió la imagen: llegará aparte
        x = o.x; y = o.y; w = o.w; z = o.z; u = o.u; rev = o.rev; rot = o.rot; page = o.page; ar = o.ar; wid = o.wid; pin = o.pin; sz = o.sz;
    }

    public void setImage(String b64) {
        img = b64;
        ik = keyOf(b64);
        bmp = null;
    }

    /** ¿Tiene huella de imagen pero aún no tiene la imagen? */
    public boolean missingImage() { return !ik.isEmpty() && img.isEmpty(); }

    public static String keyOf(String b64) {
        return b64 == null || b64.isEmpty() ? "" : b64.length() + "-" + Integer.toHexString(b64.hashCode());
    }

    /** Marca un cambio local. */
    public void touch() {
        rev++;
        u = System.currentTimeMillis();
    }

    public Bitmap bitmap() {
        if (bmp == null && img != null && !img.isEmpty()) {
            try {
                byte[] b = Base64.decode(img, Base64.DEFAULT);
                bmp = BitmapFactory.decodeByteArray(b, 0, b.length);
            } catch (IllegalArgumentException ignored) { }
        }
        return bmp;
    }
}
