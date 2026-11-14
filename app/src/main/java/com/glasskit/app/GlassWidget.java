package com.glasskit.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import android.widget.RemoteViews;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * Widget kaca tanpa bingkai: kandungan dilukis sebagai bitmap liquid glass.
 * Dikemas kini setiap minit oleh PillService (TIME_TICK).
 */
public abstract class GlassWidget extends AppWidgetProvider {

    abstract void render(Context c, Canvas cv, int w, int h, float density);

    Intent clickIntent(Context c) { return null; }

    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        for (int id : ids) update(c, m, id);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context c, AppWidgetManager m, int id, Bundle o) {
        update(c, m, id);
    }

    void update(Context c, AppWidgetManager m, int id) {
        try {
            float d = c.getResources().getDisplayMetrics().density;
            Bundle o = m.getAppWidgetOptions(id);
            int wdp = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 200);
            int hdp = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0);
            if (hdp <= 0) hdp = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 120);
            float scale = d;
            float maxSide = 900f;
            if (Math.max(wdp, hdp) * scale > maxSide) scale = maxSide / Math.max(wdp, hdp);
            int w = Math.max(40, Math.round(wdp * scale)), h = Math.max(40, Math.round(hdp * scale));
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            render(c, new Canvas(bmp), w, h, scale);

            RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_image);
            v.setImageViewBitmap(R.id.w_img, bmp);
            Intent ci = clickIntent(c);
            if (ci != null) {
                ci.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                v.setOnClickPendingIntent(R.id.w_img, PendingIntent.getActivity(c, getClass().getName().hashCode(), ci,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
            }
            m.updateAppWidget(id, v);
        } catch (Throwable ignored) {
        }
    }

    static void updateAll(Context c, Class<? extends GlassWidget> cls) {
        try {
            AppWidgetManager m = AppWidgetManager.getInstance(c);
            int[] ids = m.getAppWidgetIds(new ComponentName(c, cls));
            if (ids == null || ids.length == 0) return;
            GlassWidget g = cls.getDeclaredConstructor().newInstance();
            for (int id : ids) g.update(c, m, id);
        } catch (Throwable ignored) {
        }
    }

    /** Dipanggil setiap minit. */
    static void tick(Context c) {
        updateAll(c, ClockWidget.class);
        updateAll(c, StackWidget.class);
        updateAll(c, AnalogWidget.class);
        updateAll(c, DashboardWidget.class);
        Calendar now = Calendar.getInstance();
        if (now.get(Calendar.HOUR_OF_DAY) == 0 && now.get(Calendar.MINUTE) == 0) updateAll(c, DateWidget.class);
    }

    static void refreshAll(Context c) {
        tick(c);
        updateAll(c, DateWidget.class);
        updateAll(c, BatteryWidget.class);
        MusicWidget.push(c);
    }

    // ================= Pembantu lukisan =================

    static boolean dark(Context c) { return Prefs.get(c).getBoolean(Prefs.GLASS_DARK, false); }

    static String time(Context c) {
        boolean is24 = android.text.format.DateFormat.is24HourFormat(c);
        return new SimpleDateFormat(is24 ? "HH:mm" : "h:mm", Locale.getDefault()).format(new Date());
    }

    static Path textPath(String s, Typeface tf, float size, float cx, float baseline) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(tf);
        p.setTextSize(size);
        p.setTextAlign(Paint.Align.CENTER);
        p.setLetterSpacing(-0.03f);
        Path path = new Path();
        p.getTextPath(s, 0, s.length(), cx, baseline, path);
        return path;
    }

    /** Teks biasa putih dengan bayang (untuk label kecil). */
    static void label(Canvas cv, String s, float size, int weight, float cx, float baseline, int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(Ui.font(weight));
        p.setTextSize(size);
        p.setTextAlign(Paint.Align.CENTER);
        p.setColor(color);
        p.setShadowLayer(size * 0.18f, 0, size * 0.05f, Color.argb(120, 0, 0, 0));
        cv.drawText(s, cx, baseline, p);
    }

    /** Pusatkan laluan secara menegak dalam kawasan. */
    static void centerPath(Path p, float cy) {
        RectF b = new RectF();
        p.computeBounds(b, true);
        p.offset(0, cy - b.centerY());
    }
}
