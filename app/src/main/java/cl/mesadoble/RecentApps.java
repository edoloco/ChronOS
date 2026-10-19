package cl.mesadoble;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Process;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Apps recientes del PRINCIPAL (las que están abiertas o se usaron hace poco), para el widget "Apps recientes".
 * Android solo lo permite con el "Acceso a datos de uso", que se activa a mano en Ajustes del principal.
 *   recReq  (extendido → principal) {sig}: pide la lista; si no cambió no se responde
 *   recList (principal → extendido) {sig, access, active, apps:[{p, l, i}]}  (active "" = la mesa)
 */
final class RecentApps {

    static final int MAX = 10;
    private static final Map<String, String> icons = new HashMap<>(), labels = new HashMap<>();
    static final ExecutorService worker = Executors.newSingleThreadExecutor();

    private RecentApps() { }

    static boolean hasAccess(Context c) {
        try {
            AppOpsManager ao = (AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
            return ao != null && ao.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.getPackageName())
                    == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) { return false; }
    }

    static Intent accessIntent() { return new Intent("android.settings.USAGE_ACCESS_SETTINGS"); }

    // uso acumulado: se consulta solo lo nuevo desde la última vez (es más liviano)
    private static final Map<String, Long> last = new HashMap<>();
    private static long queriedTo;
    private static String lastResumed = "";
    // apps cerradas desde la barra de tareas: no vuelven a la lista hasta que se abran de nuevo
    private static final Map<String, Long> closed = new HashMap<>();

    static synchronized void close(String pkg) { closed.put(pkg, System.currentTimeMillis()); }
    // última notificación que sonó/vibró/apareció de cada app (evento 12 de Android: NOTIFICATION_INTERRUPTION)
    private static final Map<String, Long> notifAt = new HashMap<>();

    private static synchronized void refresh(Context c) {
        UsageStatsManager um = (UsageStatsManager) c.getSystemService(Context.USAGE_STATS_SERVICE);
        if (um == null) return;
        long now = System.currentTimeMillis();
        long from = queriedTo == 0 ? now - 2L * 24 * 3600 * 1000 : queriedTo - 2000;
        long newest = 0;
        try {
            UsageEvents ev = um.queryEvents(from, now);
            UsageEvents.Event e = new UsageEvents.Event();
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e);
                if (e.getEventType() == 12) notifAt.put(e.getPackageName(), e.getTimeStamp());
                if (e.getEventType() == 1 /* ACTIVITY_RESUMED / MOVE_TO_FOREGROUND */) {
                    last.put(e.getPackageName(), e.getTimeStamp());
                    if (e.getTimeStamp() >= newest) { newest = e.getTimeStamp(); lastResumed = e.getPackageName(); }
                }
            }
            queriedTo = now;
        } catch (Exception ignored) { }
    }

    /** Apps con notificaciones nuevas: avisaron después de la última vez que se abrieron (hasta 1 día). */
    static synchronized List<String> unseen(Context c) {
        refresh(c);
        List<String> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Long> e : notifAt.entrySet()) {
            String p = e.getKey();
            if (p.equals(c.getPackageName()) || "android".equals(p) || "com.android.systemui".equals(p)) continue;
            Long opened = last.get(p);
            if (now - e.getValue() < 24L * 3600 * 1000 && (opened == null || e.getValue() > opened + 1500)) out.add(p);
        }
        Collections.sort(out);
        return out;
    }

    private static Set<String> homes(Context c) {
        Set<String> skip = new HashSet<>();
        for (ResolveInfo ri : c.getPackageManager().queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0))
            skip.add(ri.activityInfo.packageName);   // pantallas de inicio
        return skip;
    }

    /** App que está al frente en el principal ("" = la mesa de ChronOS, "@inicio" = la pantalla de inicio). */
    static synchronized String active(Context c) {
        refresh(c);
        String p = lastResumed;
        if (p.isEmpty() || p.equals(c.getPackageName())) return "";      // la mesa de ChronOS
        if (homes(c).contains(p) || "com.android.systemui".equals(p)) return "@inicio";   // pantalla de inicio del teléfono: no es "Desktop"
        return p;
    }

    /** Paquetes recientes, el más reciente primero. */
    static synchronized List<String> packages(Context c) {
        refresh(c);
        List<String> out = new ArrayList<>();
        PackageManager pm = c.getPackageManager();
        Set<String> skip = homes(c);
        skip.add(c.getPackageName());
        skip.add("com.android.systemui");
        skip.add("android");
        List<String> pk = new ArrayList<>(last.keySet());
        Collections.sort(pk, (a, b) -> Long.compare(last.get(b), last.get(a)));
        for (String p : pk) {
            if (skip.contains(p) || pm.getLaunchIntentForPackage(p) == null) continue;
            Long cl = closed.get(p);
            if (cl != null && last.get(p) <= cl) continue;   // la cerraron y no se ha vuelto a abrir
            out.add(p);
            if (out.size() >= MAX) break;
        }
        return out;
    }

    static String sig(List<String> pk) { return android.text.TextUtils.join(",", pk); }

    /** Mensaje con la lista (íconos incluidos). */
    static JSONObject message(Context c) {
        boolean access = hasAccess(c);
        List<String> pk = access ? packages(c) : new ArrayList<>();
        String act = access ? active(c) : "";
        JSONObject m = new JSONObject();
        JSONArray arr = new JSONArray();
        PackageManager pm = c.getPackageManager();
        int size = Math.round(64 * c.getResources().getDisplayMetrics().density);
        for (String p : pk) {
            try {
                if (!icons.containsKey(p)) {
                    android.content.pm.ApplicationInfo ai = pm.getApplicationInfo(p, 0);
                    icons.put(p, AppsDialog.iconPng(pm.getApplicationIcon(ai), Math.min(144, size)));
                    labels.put(p, String.valueOf(pm.getApplicationLabel(ai)));
                }
                JSONObject o = new JSONObject();
                o.put("p", p); o.put("l", labels.get(p)); o.put("i", icons.get(p));
                arr.put(o);
            } catch (Exception ignored) { }
        }
        try { m.put("t", "recList"); m.put("sig", (access ? "" : "-") + act + "|" + sig(pk)); m.put("active", act); m.put("access", access); m.put("apps", arr); } catch (JSONException ignored) { }
        return m;
    }
}
