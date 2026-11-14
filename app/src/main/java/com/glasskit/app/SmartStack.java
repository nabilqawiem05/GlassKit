package com.glasskit.app;

import android.app.Activity;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Bundle;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * Smart Stack: satu widget yang boleh dileret (StackView) dan bertukar sendiri.
 * Kad paling berkaitan diletak di depan: bateri rendah, acara tidak lama lagi, muzik dimainkan,
 * waktu solat hampir, cuaca waktu pagi, kemudian aktiviti dan jam.
 */
public class SmartStack extends AppWidgetProvider {
    static final int T_CLOCK = 0, T_WEATHER = 1, T_EVENT = 2, T_BATTERY = 3, T_STEPS = 4, T_PRAYER = 5, T_MUSIC = 6;

    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        for (int id : ids) update(c, m, id);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context c, AppWidgetManager m, int id, Bundle o) {
        update(c, m, id);
        m.notifyAppWidgetViewDataChanged(id, R.id.stack);
    }

    static void update(Context c, AppWidgetManager m, int id) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_stack);
        Intent svc = new Intent(c, Service.class).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
        svc.setData(Uri.parse(svc.toUri(Intent.URI_INTENT_SCHEME)));
        v.setRemoteAdapter(R.id.stack, svc);
        v.setEmptyView(R.id.stack, R.id.stack_empty);
        Intent click = new Intent(c, Click.class);
        v.setPendingIntentTemplate(R.id.stack, PendingIntent.getActivity(c, 900 + id, click,
                PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        m.updateAppWidget(id, v);
    }

    /** Susun semula / lukis semula semua Smart Stack. */
    static void refresh(Context c) {
        try {
            AppWidgetManager m = AppWidgetManager.getInstance(c);
            int[] ids = m.getAppWidgetIds(new ComponentName(c, SmartStack.class));
            if (ids != null && ids.length > 0) m.notifyAppWidgetViewDataChanged(ids, R.id.stack);
        } catch (Exception ignored) {
        }
    }

    /** Urutan kad mengikut konteks semasa. */
    static List<Integer> order(Context c) {
        List<int[]> scored = new ArrayList<>();
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        long now = System.currentTimeMillis();
        int lvl = Ui.battery(c);
        boolean ch = Ui.charging(c);
        scored.add(new int[]{T_BATTERY, (!ch && lvl >= 0 && lvl <= 20) ? 100 : 10});
        int ev = 20;
        try {
            List<Data.Event> e = Data.events(c, 1, 1);
            if (!e.isEmpty() && !e.get(0).allDay) {
                long d = e.get(0).begin - now;
                if (d <= 60 * 60_000L) ev = 90;
                else if (d <= 3 * 3600_000L) ev = 60;
            }
        } catch (Exception ignored) {
        }
        scored.add(new int[]{T_EVENT, ev});
        PillService s = PillService.inst;
        boolean playing = s != null && s.playing;
        scored.add(new int[]{T_MUSIC, playing ? 85 : 5});
        long[] p = Prayer.next(c);
        int pr = 25;
        if (p != null && Prefs.get(c).getBoolean(Prefs.PRAYER_ON, true)) {
            long d = p[1] - now;
            if (d <= 30 * 60_000L) pr = 80;
        } else {
            pr = 0;
        }
        scored.add(new int[]{T_PRAYER, pr});
        scored.add(new int[]{T_WEATHER, hour >= 5 && hour < 11 ? 70 : 40});
        scored.add(new int[]{T_STEPS, hour >= 17 && hour < 22 ? 50 : 15});
        scored.add(new int[]{T_CLOCK, 30});
        scored.sort((a, b) -> b[1] - a[1]);
        List<Integer> out = new ArrayList<>();
        for (int[] x : scored) if (x[1] > 0) out.add(x[0]);
        return out;
    }

    static Bitmap render(Context c, int type, int w, int h, float d) {
        GlassWidget g;
        switch (type) {
            case T_WEATHER: g = new WeatherWidget(); break;
            case T_EVENT: g = new UpNextWidget(); break;
            case T_BATTERY: g = new BatteryWidget(); break;
            case T_STEPS: g = new StepsWidget(); break;
            case T_PRAYER: g = new PrayerWidget(); break;
            case T_MUSIC: g = new GlassWidget() {
                @Override
                void render(Context c1, Canvas cv, RectF r, GlassEngine.Theme t, float d1, int size) {
                    Cards.music(c1, cv, r, t, d1, size);
                }
            };
                break;
            default: g = new ClockWidget();
        }
        return g.draw(c, w, h, d);
    }

    // ======================= Penyesuai StackView =======================

    public static class Service extends RemoteViewsService {
        @Override
        public RemoteViewsFactory onGetViewFactory(Intent i) {
            return new Factory(getApplicationContext(), i.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 0));
        }
    }

    static class Factory implements RemoteViewsService.RemoteViewsFactory {
        private final Context c;
        private final int id;
        private List<Integer> items = new ArrayList<>();

        Factory(Context c, int id) {
            this.c = c;
            this.id = id;
        }

        @Override public void onCreate() { }

        @Override
        public void onDataSetChanged() {
            try {
                items = order(c);
            } catch (Throwable t) {
                items = new ArrayList<>();
                items.add(T_CLOCK);
            }
        }

        @Override public void onDestroy() { }

        @Override public int getCount() { return items.size(); }

        @Override
        public RemoteViews getViewAt(int pos) {
            RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_stack_item);
            if (pos >= items.size()) return v;
            int type = items.get(pos);
            try {
                float d = c.getResources().getDisplayMetrics().density;
                Bundle o = AppWidgetManager.getInstance(c).getAppWidgetOptions(id);
                int wdp = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 320);
                int hdp = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0);
                if (hdp <= 0) hdp = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 160);
                float sc = Math.min(d, 900f / Math.max(wdp, hdp));
                v.setImageViewBitmap(R.id.w_img, render(c, type, Math.round(wdp * sc), Math.round(hdp * sc), sc));
            } catch (Throwable t) {
                CrashLog.write(c, t);
            }
            v.setOnClickFillInIntent(R.id.w_img, new Intent().putExtra("type", type));
            return v;
        }

        @Override public RemoteViews getLoadingView() { return null; }

        @Override public int getViewTypeCount() { return 1; }

        @Override public long getItemId(int pos) { return pos < items.size() ? items.get(pos) : pos; }

        @Override public boolean hasStableIds() { return true; }
    }

    // ======================= Tindakan tekan =======================

    /** Aktiviti trampolin telus: buka app yang sesuai mengikut kad yang ditekan. */
    public static class Click extends Activity {
        @Override
        protected void onCreate(Bundle b) {
            super.onCreate(b);
            int type = getIntent().getIntExtra("type", T_CLOCK);
            Intent go;
            switch (type) {
                case T_WEATHER: go = new WeatherWidget().clickIntent(this); break;
                case T_EVENT: go = GlassWidget.calendar(); break;
                case T_BATTERY: go = new Intent(Intent.ACTION_POWER_USAGE_SUMMARY); break;
                case T_PRAYER:
                case T_STEPS: go = new Intent(this, MainActivity.class); break;
                case T_MUSIC:
                    PillService s = PillService.inst;
                    if (s != null && s.mc != null && s.mc.getSessionActivity() != null) {
                        s.sendIntent(s.mc.getSessionActivity());
                        finish();
                        return;
                    }
                    go = new Intent(this, MainActivity.class);
                    break;
                default: go = GlassWidget.alarms();
            }
            try {
                startActivity(go.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) {
            }
            finish();
            overridePendingTransition(0, 0);
        }
    }
}
