package com.glasskit.app;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Widget Peringatan: kotak semak berfungsi terus di home screen. */
public class TasksWidget extends AppWidgetProvider {

    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        for (int id : ids) update(c, m, id);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context c, AppWidgetManager m, int id, Bundle o) { update(c, m, id); }

    static void update(Context c, AppWidgetManager m, int id) {
        int[] n = Notes.counts(c);
        RemoteViews v = ListWidgets.base(c, m, id, R.string.t_tab_tasks, String.valueOf(n[0]), Service.class, TasksWidget.class,
                new Intent(c, NotesActivity.class).putExtra(NotesActivity.EXTRA_TAB, 0).putExtra(NotesActivity.EXTRA_NEW, true),
                new Intent(c, NotesActivity.class).putExtra(NotesActivity.EXTRA_TAB, 0));
        v.setTextViewText(R.id.w_empty, c.getString(R.string.t_empty_tasks));
        m.updateAppWidget(id, v);
    }

    static void refresh(Context c) {
        try {
            AppWidgetManager m = AppWidgetManager.getInstance(c);
            int[] ids = m.getAppWidgetIds(new ComponentName(c, TasksWidget.class));
            if (ids == null) return;
            for (int id : ids) update(c, m, id);
            ListWidgets.notifyData(c, TasksWidget.class);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onReceive(Context c, Intent i) {
        super.onReceive(c, i);
        if (!"item".equals(i.getAction())) return;
        long id = i.getLongExtra("id", 0);
        if (i.hasExtra(RemoteViews.EXTRA_CHECKED)) {
            Notes.setDone(c, id, i.getBooleanExtra(RemoteViews.EXTRA_CHECKED, false));
        } else {
            ListWidgets.startFromWidget(c, new Intent(c, NotesActivity.class).putExtra(NotesActivity.EXTRA_TAB, 0));
        }
    }

    public static class Service extends RemoteViewsService {
        @Override
        public RemoteViewsFactory onGetViewFactory(Intent intent) { return new Factory(getApplicationContext()); }
    }

    static class Factory implements RemoteViewsService.RemoteViewsFactory {
        private final Context c;
        private List<Notes.Task> items = new ArrayList<>();
        private GlassEngine.Theme t;

        Factory(Context c) { this.c = c; }

        @Override public void onCreate() { }

        @Override
        public void onDataSetChanged() {
            items = Notes.tasks(c);
            t = GlassEngine.theme(c);
        }

        @Override public void onDestroy() { }

        @Override public int getCount() { return items.size(); }

        @Override
        public RemoteViews getViewAt(int pos) {
            RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_task_item);
            if (pos >= items.size()) return v;
            Notes.Task k = items.get(pos);
            android.text.SpannableString txt = new android.text.SpannableString((k.important ? "★ " : "") + k.text);
            if (k.done) txt.setSpan(new android.text.style.StrikethroughSpan(), 0, txt.length(), 0);
            v.setTextViewText(R.id.w_text, txt);
            v.setTextColor(R.id.w_text, k.done ? t.muted : t.primary);
            if (k.due > 0) {
                boolean is24 = android.text.format.DateFormat.is24HourFormat(c);
                String d = new SimpleDateFormat(is24 ? "d MMM, HH:mm" : "d MMM, h:mm a", Locale.getDefault()).format(new Date(k.due));
                v.setTextViewText(R.id.w_sub, d);
                v.setTextColor(R.id.w_sub, k.due < System.currentTimeMillis() && !k.done ? 0xFFFF453A : t.secondary);
                v.setViewVisibility(R.id.w_sub, android.view.View.VISIBLE);
            } else {
                v.setViewVisibility(R.id.w_sub, android.view.View.GONE);
            }
            v.setCompoundButtonChecked(R.id.w_check, k.done);
            v.setOnCheckedChangeResponse(R.id.w_check,
                    RemoteViews.RemoteResponse.fromFillInIntent(new Intent().putExtra("id", k.id)));
            v.setOnClickFillInIntent(R.id.w_text, new Intent().putExtra("id", k.id));
            return v;
        }

        @Override public RemoteViews getLoadingView() { return null; }
        @Override public int getViewTypeCount() { return 1; }
        @Override public long getItemId(int pos) { return pos < items.size() ? items.get(pos).id : pos; }
        @Override public boolean hasStableIds() { return true; }
    }
}
