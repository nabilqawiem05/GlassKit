package com.glasskit.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.widget.RemoteViews;

/** Widget muzik kaca. Dikemas kini oleh PillService bila lagu berubah. */
public class MusicWidget extends AppWidgetProvider {
    private static final String A_PREV = "com.glasskit.app.W_PREV";
    private static final String A_PLAY = "com.glasskit.app.W_PLAY";
    private static final String A_NEXT = "com.glasskit.app.W_NEXT";
    private static final String A_OPEN = "com.glasskit.app.W_OPEN";

    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        push(c);
    }

    @Override
    public void onReceive(Context c, Intent i) {
        super.onReceive(c, i);
        String a = i.getAction();
        PillService s = PillService.inst;
        if (a == null || s == null || s.mc == null) return;
        switch (a) {
            case A_PREV: s.mc.getTransportControls().skipToPrevious(); break;
            case A_NEXT: s.mc.getTransportControls().skipToNext(); break;
            case A_PLAY: s.togglePlay(); break;
            case A_OPEN: s.sendIntent(s.mc.getSessionActivity()); break;
        }
    }

    private static PendingIntent pi(Context c, String action, int code) {
        Intent i = new Intent(c, MusicWidget.class).setAction(action);
        return PendingIntent.getBroadcast(c, code, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static void push(Context c) {
        try {
            AppWidgetManager m = AppWidgetManager.getInstance(c);
            int[] ids = m.getAppWidgetIds(new ComponentName(c, MusicWidget.class));
            if (ids == null || ids.length == 0) return;
            PillService s = PillService.inst;
            RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_music);
            boolean dark = Prefs.get(c).getBoolean(Prefs.GLASS_DARK, false);
            v.setInt(R.id.w_root, "setBackgroundResource", dark ? R.drawable.widget_glass_dark : R.drawable.widget_glass_clear);
            boolean has = s != null && s.mc != null && (s.title != null || s.playing);
            v.setTextViewText(R.id.w_title, has && s.title != null ? s.title : c.getString(R.string.not_playing));
            v.setTextViewText(R.id.w_artist, has && s.artist != null ? s.artist : "");
            Bitmap art = has ? s.art : null;
            if (art != null) {
                int max = 256;
                if (art.getWidth() > max || art.getHeight() > max) {
                    art = Bitmap.createScaledBitmap(art, max, Math.max(1, max * art.getHeight() / art.getWidth()), true);
                }
                v.setImageViewBitmap(R.id.w_art, art);
            } else {
                v.setImageViewResource(R.id.w_art, R.drawable.ic_w_play);
            }
            v.setImageViewResource(R.id.w_play, has && s.playing ? R.drawable.ic_w_pause : R.drawable.ic_w_play);
            v.setOnClickPendingIntent(R.id.w_prev, pi(c, A_PREV, 11));
            v.setOnClickPendingIntent(R.id.w_play, pi(c, A_PLAY, 12));
            v.setOnClickPendingIntent(R.id.w_next, pi(c, A_NEXT, 13));
            v.setOnClickPendingIntent(R.id.w_root, pi(c, A_OPEN, 14));
            m.updateAppWidget(ids, v);
        } catch (Exception ignored) {
        }
    }
}
