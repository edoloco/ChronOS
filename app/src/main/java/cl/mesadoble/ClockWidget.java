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

    @Override public void onReceive(Context c, Intent i) {
        super.onReceive(c, i);
        if (AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED.equals(i.getAction())) refresh(c);
    }

    static void refresh(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        int[] ids = m.getAppWidgetIds(new ComponentName(c, ClockWidget.class));
        if (ids.length > 0) for (int id : ids) m.updateAppWidget(id, views(c));
    }

    private static RemoteViews views(Context c) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_reloj);
        Intent alarms = new Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (alarms.resolveActivity(c.getPackageManager()) == null) {
            // sin "ver alarmas": abrir la app de reloj que haya
            for (String pkg : new String[]{"com.android.deskclock", "com.google.android.deskclock", "com.sec.android.app.clockpackage"}) {
                Intent l = c.getPackageManager().getLaunchIntentForPackage(pkg);
                if (l != null) { alarms = l; break; }
            }
        }
        PendingIntent pi = PendingIntent.getActivity(c, 11, alarms, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        v.setOnClickPendingIntent(R.id.reloj_raiz, pi);
        String next = "";
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            AlarmManager.AlarmClockInfo info = am != null ? am.getNextAlarmClock() : null;
            if (info != null) {
                boolean is24 = android.text.format.DateFormat.is24HourFormat(c);
                next = "⏰ " + new SimpleDateFormat(is24 ? "EEE HH:mm" : "EEE h:mm a", new Locale("es")).format(new Date(info.getTriggerTime()));
            }
        } catch (Exception ignored) { }
        v.setTextViewText(R.id.reloj_alarma, next);
        return v;
    }
}
