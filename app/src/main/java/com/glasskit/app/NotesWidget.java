package com.glasskit.app;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import java.util.ArrayList;
import java.util.List;

/** Widget Nota: nota disemat dahulu, tekan untuk sunting. */
public class NotesWidget extends AppWidgetProvider {

    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        for (int id : ids) update(c, m, id);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context c, AppWidgetManager m, int id, Bundle o) { update(c, m, id); }

    static void update(Context c, AppWidgetManager m, int id) {
        RemoteViews v = ListWidgets.base(c, m, id, R.string.t_tab_notes, String.valueOf(Notes.notes(c).size()), Service.class,
                NotesWidget.class,
                new Intent(c, NotesActivity.class).putExtra(NotesActivity.EXTRA_TAB, 1).putExtra(NotesActivity.EXTRA_NEW, true),
                new Intent(c, NotesActivity.class).putExtra(NotesActivity.EXTRA_TAB, 1));
        v.setTextViewText(R.id.w_empty, c.getString(R.string.t_empty_notes));
        m.updateAppWidget(id, v);
    }

    static void refresh(Context c) {
        try {
            AppWidgetManager m = AppWidgetManager.getInstance(c);
            int[] ids = m.getAppWidgetIds(new ComponentName(c, NotesWidget.class));
            if (ids == null) return;
            for (int id : ids) update(c, m, id);
            ListWidgets.notifyData(c, NotesWidget.class);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onReceive(Context c, Intent i) {
        super.onReceive(c, i);
        if (!"item".equals(i.getAction())) return;
        ListWidgets.startFromWidget(c, new Intent(c, NotesActivity.class).putExtra(NotesActivity.EXTRA_TAB, 1)
                .putExtra(NotesActivity.EXTRA_NOTE, i.getLongExtra("id", 0)));
    }

    public static class Service extends RemoteViewsService {
        @Override
        public RemoteViewsFactory onGetViewFactory(Intent intent) { return new Factory(getApplicationContext()); }
    }

    static class Factory implements RemoteViewsService.RemoteViewsFactory {
        private final Context c;
        private List<Notes.Note> items = new ArrayList<>();
        private GlassEngine.Theme t;

        Factory(Context c) { this.c = c; }

        @Override public void onCreate() { }

        @Override
        public void onDataSetChanged() {
            items = Notes.notes(c);
            t = GlassEngine.theme(c);
        }

        @Override public void onDestroy() { }

        @Override public int getCount() { return items.size(); }

        @Override
        public RemoteViews getViewAt(int pos) {
            RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_note_item);
            if (pos >= items.size()) return v;
            Notes.Note n = items.get(pos);
            v.setTextViewText(R.id.w_text, (n.pinned ? "📌 " : "") + Notes.firstLine(n.text));
            v.setTextColor(R.id.w_text, t.primary);
            String rest = Notes.rest(n.text);
            v.setTextViewText(R.id.w_sub, rest);
            v.setTextColor(R.id.w_sub, t.secondary);
            v.setViewVisibility(R.id.w_sub, rest.isEmpty() ? android.view.View.GONE : android.view.View.VISIBLE);
            v.setInt(R.id.w_div, "setBackgroundColor", t.hairline);
            v.setOnClickFillInIntent(R.id.w_row, new Intent().putExtra("id", n.id));
            return v;
        }

        @Override public RemoteViews getLoadingView() { return null; }
        @Override public int getViewTypeCount() { return 1; }
        @Override public long getItemId(int pos) { return pos < items.size() ? items.get(pos).id : pos; }
        @Override public boolean hasStableIds() { return true; }
    }
}
