package cl.mesadoble;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.provider.AlarmClock;
import android.widget.RemoteViews;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Widget propio de Mesa Doble: hora y fecha; al tocarlo abre las alarmas. Muestra la próxima alarma. */
public class ClockWidget extends AppWidgetProvider {

    @Override public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        for (int id : ids) m.updateAppWidget(id, views(c));
    }

    static final String OPEN = "cl.mesadoble.ABRIR_ALARMAS";

    @Override public void onReceive(Context c, Intent i) {
        if (OPEN.equals(i.getAction())) { Hub.get(c).openAlarms(); return; }   // se resuelve al tocar, aunque haya otra app al frente
        super.onReceive(c, i);
        if (AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED.equals(i.getAction())) refresh(c);
    }

    static void refresh(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        int[] ids = m.getAppWidgetIds(new ComponentName(c, ClockWidget.class));
        if (ids.length > 0) for (int id : ids) m.updateAppWidget(id, views(c));
    }

    /** "Ver alarmas"; si el teléfono no lo tiene, la app de reloj que haya. */
    static Intent alarmsIntent(Context c) {
        android.content.pm.PackageManager pm = c.getPackageManager();
        Intent alarms = new Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        android.content.pm.ResolveInfo ri = pm.resolveActivity(alarms, 0);
        if (ri != null && ri.activityInfo != null && !"android".equals(ri.activityInfo.packageName)) {
            alarms.setPackage(ri.activityInfo.packageName);
            return alarms;
        }
        // sin "ver alarmas": la app de reloj (Xiaomi, Google, Samsung, Oppo, OnePlus, Huawei...)
        for (String pkg : new String[]{"com.android.deskclock", "com.google.android.deskclock", "com.sec.android.app.clockpackage",
                "com.coloros.alarmclock", "com.oneplus.deskclock", "com.huawei.deskclock", "com.miui.clock"}) {
            Intent l = pm.getLaunchIntentForPackage(pkg);
            if (l != null) return l.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        for (android.content.pm.ResolveInfo r : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)) {
            String p = r.activityInfo.packageName.toLowerCase(Locale.ROOT);
            if (p.contains("clock") || p.contains("alarm")) {
                Intent l = pm.getLaunchIntentForPackage(r.activityInfo.packageName);
                if (l != null) return l.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
        }
        return alarms;
    }

    /** Próxima alarma de este teléfono, ya escrita ("⏰ lun 07:00"), o "". */
    static String nextAlarm(Context c) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            AlarmManager.AlarmClockInfo info = am != null ? am.getNextAlarmClock() : null;
            if (info == null) return "";
            boolean is24 = android.text.format.DateFormat.is24HourFormat(c);
            return "⏰ " + new SimpleDateFormat(is24 ? "EEE HH:mm" : "EEE h:mm a", new Locale("es")).format(new Date(info.getTriggerTime()));
        } catch (Exception e) { return ""; }
    }

    private static RemoteViews views(Context c) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_reloj);
        Intent open = new Intent(c, ClockWidget.class).setAction(OPEN);
        PendingIntent pi = PendingIntent.getBroadcast(c, 11, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        v.setOnClickPendingIntent(R.id.reloj_raiz, pi);
        v.setTextViewText(R.id.reloj_alarma, nextAlarm(c));
        return v;
    }
}
