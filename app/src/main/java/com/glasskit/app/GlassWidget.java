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

    /** Lukis kandungan dalam kawasan r (di dalam kad kaca). */
    abstract void render(Context c, Canvas cv, RectF r, GlassEngine.Theme t, float d, int size);

    Intent clickIntent(Context c) { return null; }

    /** Ubah tema untuk widget ini (contoh: warna biasan ikut cuaca). */
    void tune(Context c, GlassEngine.Theme t) {}

    /** Id widget yang sedang dilukis. */
    int currentId;

    static Intent alarms() {
        return new Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS);
    }

    static Intent calendar() {
        android.net.Uri u = android.provider.CalendarContract.CONTENT_URI.buildUpon().appendPath("time")
                .appendPath(String.valueOf(System.currentTimeMillis())).build();
        return new Intent(Intent.ACTION_VIEW).setData(u);
    }

    @Override
    public void onDeleted(Context c, int[] ids) {
        for (int id : ids) {
            java.io.File d = Data.widgetPhotoDir(c, id);
            java.io.File[] fs = d.listFiles();
            if (fs != null) for (java.io.File f : fs) f.delete();
            d.delete();
        }
    }

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
            currentId = id;
            Bitmap bmp = draw(c, w, h, scale);

            RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_image);
            v.setImageViewBitmap(R.id.w_img, bmp);
            Intent ci = clickIntent(c);
            if (ci != null) {
                ci.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ci.putExtra(MainActivity.EXTRA_WIDGET_ID, id);
                v.setOnClickPendingIntent(R.id.w_img, PendingIntent.getActivity(c, getClass().getName().hashCode() + id, ci,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
            }
            m.updateAppWidget(id, v);
        } catch (Throwable ignored) {
        }
    }

    /** Lukis widget lengkap (kad kaca + kandungan) pada saiz piksel tertentu. */
    Bitmap draw(Context c, int w, int h, float d) {
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bmp);
        GlassEngine.Theme t = GlassEngine.theme(c);
        tune(c, t);
        int ws = wStyle(c);
        if (ws == 3) {
            // tanah liat: palet dari wallpaper + mod gelap/terang
            BentoViews.palette(c);
            t.primary = BentoViews.CLAY_INK;
            t.secondary = (BentoViews.CLAY_INK & 0x00FFFFFF) | 0xC0000000;
            t.muted = BentoViews.CLAY_SOFT;
            t.accent = BentoViews.CLAY_ACCENT;
            t.hairline = (BentoViews.CLAY_SOFT & 0x00FFFFFF) | 0x40000000;
            t.chip = (BentoViews.CLAY_ACCENT & 0x00FFFFFF) | 0x50000000;
            t.dark = BentoViews.clayDark;
        } else if (ws == 1) {
            // Liquid island: monokrom, tiada warna aksen
            t.primary = Color.WHITE;
            t.secondary = Color.argb(205, 255, 255, 255);
            t.muted = Color.argb(150, 255, 255, 255);
            t.accent = 0xFFD1D1D6;
            t.dark = true;
        } else if (ws == 2) {
            t.primary = Color.WHITE;
            t.secondary = Color.argb(200, 255, 255, 255);
            t.muted = Color.argb(150, 255, 255, 255);
            t.hairline = Color.argb(40, 255, 255, 255);
            t.chip = Color.argb(36, 255, 255, 255);
            t.dark = true;
        }
        GlassEngine.cur = t;
        GlassEngine.skin = ws;
        GlassEngine.skinCtx = c.getApplicationContext();
        float m = GlassEngine.shadowPad(d);
        RectF card = new RectF(m, m, w - m, h - m);
        if (bare()) {
            // widget melukis kad-kad kecilnya sendiri (contoh: bento)
            try {
                render(c, cv, card, t, d, GlassEngine.size(card.width(), card.height(), d));
            } catch (Throwable e) {
                CrashLog.write(c, e);
            }
            return bmp;
        }
        GlassEngine.card(cv, card, t, d);
        float pad = Math.min(15 * d, Math.min(card.width(), card.height()) * 0.12f);
        RectF in = new RectF(card.left + pad, card.top + pad, card.right - pad, card.bottom - pad);
        int size = GlassEngine.size(card.width(), card.height(), d);
        try {
            cv.save();
            if (fullBleed()) render(c, cv, card, t, d, size);
            else render(c, cv, in, t, d, size);
            cv.restore();
        } catch (Throwable e) {
            CrashLog.write(c, e);
        }
        return bmp;
    }

    /** Gaya widget: ikut tetapan, atau ikut gaya panel sistem (0/1 = kaca, 2 = Bento, 3 = Tanah liat). */
    static int wStyle(Context c) {
        android.content.SharedPreferences p = Prefs.get(c);
        int s = p.getInt(Prefs.W_STYLE, -1);
        if (s < 0) s = p.getInt(Prefs.PANEL_STYLE, 1);
        return s;
    }

    /** Kad tanah liat: badan lembut timbul, bayang lembut, kilauan dalam. */
    static void clayCard(Context c, Canvas cv, RectF r) {
        BentoViews.Clay cd = new BentoViews.Clay(c, BentoViews.CLAY_BASE, 26, false);
        cd.setBounds(Math.round(r.left) - Ui.dp(c, 4), Math.round(r.top) - Ui.dp(c, 4), Math.round(r.right) + Ui.dp(c, 4), Math.round(r.bottom) + Ui.dp(c, 4));
        cd.draw(cv);
    }

    /** Kad Bento: gelap dengan cahaya warna lembut di sudut dan tepi halus. */
    static void bentoCard(Canvas cv, RectF r, float d) {
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        float rad = 24 * d;
        p.setColor(0xF014131C);
        cv.drawRoundRect(r, rad, rad, p);
        cv.save();
        android.graphics.Path clip = new android.graphics.Path();
        clip.addRoundRect(r, rad, rad, android.graphics.Path.Direction.CW);
        cv.clipPath(clip);
        p.setShader(new android.graphics.RadialGradient(r.left + r.width() * 0.15f, r.top + r.height() * 0.1f, Math.max(r.width(), r.height()) * 0.7f,
                Color.argb(120, 58, 74, 107), Color.argb(0, 58, 74, 107), android.graphics.Shader.TileMode.CLAMP));
        cv.drawRect(r, p);
        p.setShader(new android.graphics.RadialGradient(r.right - r.width() * 0.1f, r.bottom - r.height() * 0.1f, Math.max(r.width(), r.height()) * 0.7f,
                Color.argb(110, 90, 42, 68), Color.argb(0, 90, 42, 68), android.graphics.Shader.TileMode.CLAMP));
        cv.drawRect(r, p);
        cv.restore();
        p.setShader(null);
        p.setStyle(android.graphics.Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1, d));
        p.setColor(Color.argb(40, 255, 255, 255));
        cv.drawRoundRect(r, rad, rad, p);
    }

    /** Tiada kad besar: widget melukis jubin sendiri. */
    boolean bare() { return false; }

    /** Kandungan memenuhi seluruh kad (contoh: gambar). */
    boolean fullBleed() { return false; }

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
        updateAll(c, AnalogWidget.class);
        updateAll(c, ClockCalWidget.class);
        updateAll(c, WorldWidget.class);
        Calendar now = Calendar.getInstance();
        int m = now.get(Calendar.MINUTE);
        updateAll(c, UpNextWidget.class); // kira detik
        updateAll(c, BentoWidget.class);
        if (m % 5 == 0) SmartStack.refresh(c);
        if (m % 15 == 0) {
            updateAll(c, StepsWidget.class);
        }
        if (m == 0) {
            updateAll(c, PhotoWidget.class);
            if (now.get(Calendar.HOUR_OF_DAY) == 0) updateAll(c, MonthWidget.class);
        }
    }

    static void refreshAll(Context c) {
        tick(c);
        updateAll(c, MonthWidget.class);
        updateAll(c, UpNextWidget.class);
        updateAll(c, PhotoWidget.class);
        updateAll(c, StepsWidget.class);
        updateAll(c, WeatherWidget.class);
        updateAll(c, BatteryWidget.class);
        updateAll(c, WorldWidget.class);
        updateAll(c, PrayerWidget.class);
        SmartStack.refresh(c);
        MusicWidget.push(c);
    }

    // ================= Pembantu lukisan =================

    static boolean dark(Context c) { return Ui.darkGlass(c); }

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
