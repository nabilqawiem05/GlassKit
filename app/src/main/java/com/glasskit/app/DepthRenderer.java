package com.glasskit.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.format.DateFormat;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Lukis: wallpaper -> jam -> subjek (supaya jam nampak di belakang subjek). */
public final class DepthRenderer {
    private DepthRenderer() {}

    public static File bgFile(Context c) { return new File(c.getFilesDir(), "bg.jpg"); }
    public static File fgFile(Context c) { return new File(c.getFilesDir(), "fg.png"); }

    public static Bitmap load(File f) {
        if (!f.exists()) return null;
        try {
            return BitmapFactory.decodeFile(f.getAbsolutePath());
        } catch (Throwable t) {
            return null;
        }
    }

    public static Typeface font(int idx) {
        switch (idx) {
            case 1: return Typeface.create(Typeface.SANS_SERIF, 200, false);
            case 2: return Typeface.create(Typeface.SERIF, Typeface.BOLD);
            case 3: return Typeface.create(Typeface.MONOSPACE, 700, false);
            default: return Typeface.create(Typeface.SANS_SERIF, 900, false);
        }
    }

    private static RectF centerCrop(Bitmap b, int w, int h) {
        float s = Math.max((float) w / b.getWidth(), (float) h / b.getHeight());
        float dw = b.getWidth() * s, dh = b.getHeight() * s;
        float l = (w - dw) / 2f, t = (h - dh) / 2f;
        return new RectF(l, t, l + dw, t + dh);
    }

    public static void draw(Context ctx, Canvas c, int w, int h, Bitmap bg, Bitmap fg,
                            SharedPreferences p, boolean showClock) {
        Paint bmp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        if (bg != null) {
            c.drawColor(Color.BLACK);
            c.drawBitmap(bg, null, centerCrop(bg, w, h), bmp);
        } else {
            c.drawColor(Color.rgb(28, 36, 48));
        }

        if (showClock) {
            int color = p.getInt(Prefs.CLOCK_COLOR, Color.WHITE);
            float size = w * p.getInt(Prefs.CLOCK_SIZE, 30) / 100f;
            float y = h * p.getInt(Prefs.CLOCK_Y, 28) / 100f;

            Date now = new Date();
            boolean is24 = DateFormat.is24HourFormat(ctx);
            String time = new SimpleDateFormat(is24 ? "HH:mm" : "h:mm", Locale.getDefault()).format(now);
            String date = new SimpleDateFormat("EEEE, d MMMM", new Locale("ms", "MY")).format(now);

            Paint dp = new Paint(Paint.ANTI_ALIAS_FLAG);
            dp.setColor(color);
            dp.setTextAlign(Paint.Align.CENTER);
            dp.setTextSize(size * 0.16f);
            dp.setTypeface(Typeface.create(Typeface.SANS_SERIF, 500, false));
            c.drawText(date, w / 2f, y - size * 0.82f, dp);

            Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
            tp.setColor(color);
            tp.setTextAlign(Paint.Align.CENTER);
            tp.setTextSize(size);
            tp.setLetterSpacing(-0.03f);
            tp.setTypeface(font(p.getInt(Prefs.CLOCK_FONT, 0)));
            c.drawText(time, w / 2f, y, tp);
        }

        if (fg != null) {
            c.drawBitmap(fg, null, centerCrop(fg, w, h), bmp);
        }
    }
}
