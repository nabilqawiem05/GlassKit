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

    private static Boolean lastPlaying;

    static void push(Context c) {
        PillService ps = PillService.inst;
        boolean pl = ps != null && ps.playing;
        if (lastPlaying == null || lastPlaying != pl) {
            lastPlaying = pl;
            SmartStack.refresh(c); // muzik mula / henti: susun semula Smart Stack
        }
        try {
            AppWidgetManager m = AppWidgetManager.getInstance(c);
            int[] ids = m.getAppWidgetIds(new ComponentName(c, MusicWidget.class));
            if (ids == null || ids.length == 0) return;
            for (int id : ids) push(c, m, id);
        } catch (Exception ignored) {
        }
    }

    private static void push(Context c, AppWidgetManager m, int id) {
        PillService s = PillService.inst;
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_music);
        GlassEngine.Theme t = GlassEngine.theme(c);
        boolean has = s != null && s.mc != null && (s.title != null || s.playing);
        Bitmap art = has ? s.art : null;
        if (art != null) t.glowTint = Ui.dominant(art, t.accent);
        // latar kad kaca ikut saiz widget
        float d = c.getResources().getDisplayMetrics().density;
        android.os.Bundle o = m.getAppWidgetOptions(id);
        int wdp = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 300);
        int hdp = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0);
        if (hdp <= 0) hdp = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 110);
        float sc = Math.min(d, 1000f / Math.max(wdp, hdp));
        int w = Math.max(40, Math.round(wdp * sc)), h = Math.max(40, Math.round(hdp * sc));
        Bitmap bg = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas cv = new android.graphics.Canvas(bg);
        float mg = GlassEngine.shadowPad(sc);
        GlassEngine.card(cv, new android.graphics.RectF(mg, mg, w - mg, h - mg), t, sc);
        v.setImageViewBitmap(R.id.w_bg, bg);

        v.setTextViewText(R.id.w_title, has && s.title != null ? s.title : c.getString(R.string.not_playing));
        v.setTextViewText(R.id.w_artist, has && s.artist != null ? s.artist : "");
        v.setTextColor(R.id.w_title, t.primary);
        v.setTextColor(R.id.w_artist, t.secondary);
        if (art != null) {
            int max = 256;
            if (art.getWidth() > max || art.getHeight() > max) {
                art = Bitmap.createScaledBitmap(art, max, Math.max(1, max * art.getHeight() / art.getWidth()), true);
            }
            v.setImageViewBitmap(R.id.w_art, art);
        } else {
            v.setImageViewResource(R.id.w_art, R.drawable.ic_w_play);
        }
        android.content.res.ColorStateList ink = android.content.res.ColorStateList.valueOf(t.primary);
        v.setColorStateList(R.id.w_prev, "setImageTintList", ink);
        v.setColorStateList(R.id.w_play, "setImageTintList", ink);
        v.setColorStateList(R.id.w_next, "setImageTintList", ink);
        int btnBg = t.dark ? R.drawable.widget_btn_glass : R.drawable.widget_btn_glass_light;
        v.setInt(R.id.w_prev, "setBackgroundResource", btnBg);
        v.setInt(R.id.w_play, "setBackgroundResource", btnBg);
        v.setInt(R.id.w_next, "setBackgroundResource", btnBg);
        // kemajuan main
        int prog = 0;
        try {
            if (has) {
                android.media.session.PlaybackState ps = s.mc.getPlaybackState();
                android.media.MediaMetadata md = s.mc.getMetadata();
                long dur = md != null ? md.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION) : 0;
                if (ps != null && dur > 0) {
                    long pos = ps.getPosition();
                    if (ps.getState() == android.media.session.PlaybackState.STATE_PLAYING) {
                        pos += (long) ((android.os.SystemClock.elapsedRealtime() - ps.getLastPositionUpdateTime()) * ps.getPlaybackSpeed());
                    }
                    prog = (int) Math.max(0, Math.min(1000, pos * 1000 / dur));
                }
            }
        } catch (Exception ignored) {
        }
        v.setProgressBar(R.id.w_prog, 1000, prog, false);
        v.setColorStateList(R.id.w_prog, "setProgressTintList", android.content.res.ColorStateList.valueOf(t.primary));
        v.setColorStateList(R.id.w_prog, "setProgressBackgroundTintList",
                android.content.res.ColorStateList.valueOf(GlassEngine.a(t.primary, 50)));
        v.setImageViewResource(R.id.w_play, has && s.playing ? R.drawable.ic_w_pause : R.drawable.ic_w_play);
        v.setOnClickPendingIntent(R.id.w_prev, pi(c, A_PREV, 11));
        v.setOnClickPendingIntent(R.id.w_play, pi(c, A_PLAY, 12));
        v.setOnClickPendingIntent(R.id.w_next, pi(c, A_NEXT, 13));
        v.setOnClickPendingIntent(R.id.w_root, pi(c, A_OPEN, 14));
        m.updateAppWidget(id, v);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context c, AppWidgetManager m, int id, android.os.Bundle o) {
        push(c);
    }
}
