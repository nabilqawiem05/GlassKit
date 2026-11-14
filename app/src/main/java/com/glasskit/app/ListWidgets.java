package com.glasskit.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Bundle;
import android.widget.RemoteViews;

/** Pembantu bersama untuk widget senarai (Peringatan & Nota): latar kad kaca + pengepala. */
final class ListWidgets {
    private ListWidgets() {}

    static Bitmap cardBg(Context c, AppWidgetManager m, int id) {
        float d = c.getResources().getDisplayMetrics().density;
        Bundle o = m.getAppWidgetOptions(id);
        int wdp = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250);
        int hdp = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0);
        if (hdp <= 0) hdp = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 180);
        float sc = Math.min(d, 900f / Math.max(wdp, hdp));
        int w = Math.max(40, Math.round(wdp * sc)), h = Math.max(40, Math.round(hdp * sc));
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        float mg = GlassEngine.shadowPad(sc);
        GlassEngine.card(new Canvas(b), new RectF(mg, mg, w - mg, h - mg), GlassEngine.theme(c), sc);
        return b;
    }

    static RemoteViews base(Context c, AppWidgetManager m, int id, int title, String count, Class<?> svc,
                            Class<?> receiver, Intent addIntent, Intent openIntent) {
        GlassEngine.Theme t = GlassEngine.theme(c);
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_list);
        v.setImageViewBitmap(R.id.w_bg, cardBg(c, m, id));
        v.setTextViewText(R.id.w_head, c.getString(title));
        v.setTextColor(R.id.w_head, t.accent);
        v.setTextViewText(R.id.w_count, count);
        v.setTextColor(R.id.w_count, t.primary);
        v.setTextColor(R.id.w_empty, t.muted);
        v.setColorStateList(R.id.w_add, "setImageTintList", ColorStateList.valueOf(t.accent));
        v.setOnClickPendingIntent(R.id.w_add, PendingIntent.getActivity(c, title * 10 + 1,
                addIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        PendingIntent open = PendingIntent.getActivity(c, title * 10 + 2, openIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        v.setOnClickPendingIntent(R.id.w_head, open);
        v.setOnClickPendingIntent(R.id.w_empty, open);
        Intent s = new Intent(c, svc).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
        s.setData(Uri.parse(s.toUri(Intent.URI_INTENT_SCHEME)));
        v.setRemoteAdapter(R.id.w_list, s);
        v.setEmptyView(R.id.w_list, R.id.w_empty);
        Intent tpl = new Intent(c, receiver).setAction("item");
        v.setPendingIntentTemplate(R.id.w_list, PendingIntent.getBroadcast(c, title * 10 + 3, tpl,
                PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        return v;
    }

    static void notifyData(Context c, Class<?> provider) {
        try {
            AppWidgetManager m = AppWidgetManager.getInstance(c);
            int[] ids = m.getAppWidgetIds(new ComponentName(c, provider));
            if (ids != null && ids.length > 0) m.notifyAppWidgetViewDataChanged(ids, R.id.w_list);
        } catch (Exception ignored) {
        }
    }

    /** Buka aktiviti dari penerima siaran (dibenarkan kerana dicetuskan oleh tekanan pada widget). */
    static void startFromWidget(Context c, Intent i) {
        try {
            android.app.ActivityOptions o = android.app.ActivityOptions.makeBasic();
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                o.setPendingIntentBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
            }
            PendingIntent.getActivity(c, (int) (System.currentTimeMillis() % 100000), i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_ONE_SHOT).send(c, 0, null, null, null, null, o.toBundle());
        } catch (Exception e) {
            try { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception ignored) {}
        }
    }
}
