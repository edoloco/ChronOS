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
    public static final String NOTE = "note", IMG = "img", LINK = "link";

    public String id = UUID.randomUUID().toString();
    public String kind = NOTE;
    public String text = "";
    public String url = "";
    public String title = "";
    public String img = "";      // JPEG en base64
    public int color = 0xFFF6E6A2;
    public double x, y, w = 0.62;
    public long z = System.currentTimeMillis();
    public long u = System.currentTimeMillis();   // última modificación (solo para desempatar)
    public int rev = 1;                           // versión: sube con cada cambio
    public float rot = 0f;

    // Estado solo local (no se envía)
    public float dx = Float.NaN, dy = Float.NaN;  // posición mostrada, animada
    public float hPx = 0;                         // alto medido en píxeles
    public final RectF sendBtn = new RectF(), delBtn = new RectF(), openBtn = new RectF();
    public Bitmap bmp;
    public StaticLayout layout;
    public String layoutKey = "";

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id); o.put("kind", kind); o.put("text", text); o.put("url", url);
            o.put("title", title); o.put("img", img); o.put("color", color);
            o.put("x", x); o.put("y", y); o.put("w", w); o.put("z", z); o.put("u", u); o.put("rev", rev); o.put("rot", (double) rot);
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
        it.color = o.optInt("color", 0xFFF6E6A2);
        it.x = o.optDouble("x", 0.2);
        it.y = o.optDouble("y", 0.2);
        it.w = o.optDouble("w", 0.62);
        it.z = o.optLong("z", 0);
        it.u = o.optLong("u", 0);
        it.rev = o.optInt("rev", 1);
        it.rot = (float) o.optDouble("rot", 0);
        // nunca dejar un elemento fuera de ambas pantallas
        if (Double.isNaN(it.w) || it.w <= 0.05 || it.w > 1) it.w = 0.62;
        if (Double.isNaN(it.x)) it.x = 0.2;
        if (Double.isNaN(it.y)) it.y = 0.2;
        it.x = Math.min(Math.max(it.x, 0.0), 2.0 - it.w);
        it.y = Math.min(Math.max(it.y, 0.0), 0.9);
        return it;
    }

    /** Copia los datos compartidos de otro elemento, conservando la animación local. */
    public void copyFrom(Item o) {
        boolean imgChanged = !img.equals(o.img);
        kind = o.kind; text = o.text; url = o.url; title = o.title; img = o.img; color = o.color;
        x = o.x; y = o.y; w = o.w; z = o.z; u = o.u; rev = o.rev; rot = o.rot;
        if (imgChanged) bmp = null;
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
