package com.glasskit.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.provider.AlarmClock;
import android.util.TypedValue;
import android.widget.RemoteViews;

/** Widget jam kaca: ikut warna wallpaper, saiz huruf ikut saiz widget. */
public class ClockWidget extends AppWidgetProvider {

    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        for (int id : ids) update(c, m, id);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context c, AppWidgetManager m, int id, Bundle options) {
        update(c, m, id);
    }

    static void updateAll(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        for (int id : m.getAppWidgetIds(new ComponentName(c, ClockWidget.class))) update(c, m, id);
    }

    static void update(Context c, AppWidgetManager m, int id) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_clock);
        boolean dark = Prefs.get(c).getBoolean(Prefs.GLASS_DARK, false);
        v.setInt(R.id.w_root, "setBackgroundResource", dark ? R.drawable.widget_glass_dark : R.drawable.widget_glass_clear);

        Bundle o = m.getAppWidgetOptions(id);
        int w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0);
        int h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0);
        if (w <= 0) w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 220);
        if (h <= 0) h = 110;
        float size = Math.max(22f, Math.min(h * 0.52f, w * 0.27f));
        v.setTextViewTextSize(R.id.w_time, TypedValue.COMPLEX_UNIT_DIP, size);
        v.setTextViewTextSize(R.id.w_date, TypedValue.COMPLEX_UNIT_DIP, Math.max(11f, Math.min(18f, size * 0.24f)));

        Intent open = new Intent(AlarmClock.ACTION_SHOW_ALARMS);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        v.setOnClickPendingIntent(R.id.w_root, PendingIntent.getActivity(c, 1, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        m.updateAppWidget(id, v);
    }
}
