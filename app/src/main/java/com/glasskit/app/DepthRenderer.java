package com.glasskit.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.text.format.DateFormat;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Lukis: wallpaper -> jam -> subjek (supaya jam nampak di belakang subjek).
 * Jam gaya liquid glass: isi kabur dari wallpaper, kilauan, dan bingkai cahaya.
 * Mesti dilukis atas kanvas perisian (Bitmap) kerana guna BlurMaskFilter.
 */
public final class DepthRenderer {
    private DepthRenderer() {}

    private static Bitmap blurSrc, blurOut, sharpSrc, sharpOut;

    private static synchronized Bitmap sharp(Bitmap bg) {
        if (bg == sharpSrc && sharpOut != null) return sharpOut;
        sharpOut = GlassArt.blur(bg, 720, 1);
        sharpSrc = bg;
        return sharpOut;
    }

    public static File bgFile(Context c) { return new File(c.getFilesDir(), "bg.jpg"); }
    public static File fgFile(Context c) { return new File(c.getFilesDir(), "fg.png"); }

    private static final java.util.HashMap<String, Object[]> loaded = new java.util.HashMap<>();

    /** Muat (dengan cache dikongsi, ikut masa ubah fail) supaya lock screen & wallpaper guna bitmap yang sama. */
    public static synchronized Bitmap load(File f) {
        if (!f.exists()) return null;
        try {
            Object[] e = loaded.get(f.getPath());
            long mt = f.lastModified() ^ f.length();
            if (e != null && (Long) e[0] == mt) return (Bitmap) e[1];
            Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath());
            loaded.put(f.getPath(), new Object[]{mt, b});
            return b;
        } catch (Throwable t) {
            return null;
        }
    }

    public static Typeface font(int idx) {
        switch (idx) {
            case 1: return Typeface.create(Typeface.SANS_SERIF, 300, false);
            case 2: return Typeface.create(Typeface.SERIF, Typeface.BOLD);
            case 3: return Typeface.create(Typeface.MONOSPACE, 700, false);
            default: return Typeface.create(Typeface.create("google-sans", Typeface.NORMAL), 800, false);
        }
    }

    static RectF centerCrop(Bitmap b, int w, int h) {
        float s = Math.max((float) w / b.getWidth(), (float) h / b.getHeight());
        float dw = b.getWidth() * s, dh = b.getHeight() * s;
        float l = (w - dw) / 2f, t = (h - dh) / 2f;
        return new RectF(l, t, l + dw, t + dh);
    }

    private static synchronized Bitmap blurred(Bitmap bg) {
        if (bg == blurSrc && blurOut != null) return blurOut;
        blurOut = GlassArt.blur(bg, 260, 7);
        blurSrc = bg;
        return blurOut;
    }

    /** Laluan jam (sudah diregang ikut lebar/tinggi). Garis dasar kekal di y. */
    static Path clockPath(Context ctx, SharedPreferences p, int w, int h) {
        float size = w * p.getInt(Prefs.CLOCK_SIZE, 30) / 100f;
        float y = h * p.getInt(Prefs.CLOCK_Y, 28) / 100f;
        float sx = p.getInt(Prefs.CLOCK_W, 100) / 100f;
        float sy = p.getInt(Prefs.CLOCK_H, 100) / 100f;
        boolean is24 = DateFormat.is24HourFormat(ctx);
        String time = new SimpleDateFormat(is24 ? "HH:mm" : "h:mm", Locale.getDefault()).format(new Date());

        Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
        tp.setTextAlign(Paint.Align.CENTER);
        tp.setTextSize(size);
        tp.setLetterSpacing(-0.03f);
        tp.setTypeface(font(p.getInt(Prefs.CLOCK_FONT, 0)));
        Path path = new Path();
        tp.getTextPath(time, 0, time.length(), w / 2f, y, path);
        Matrix m = new Matrix();
        m.setScale(sx, sy, w / 2f, y);
        path.transform(m);
        return path;
    }

    public static void draw(Context ctx, Canvas c, int w, int h, Bitmap bg, Bitmap fg,
                            SharedPreferences p, boolean showClock) {
        Paint bmp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        RectF bgRect = null;
        if (bg != null) {
            c.drawColor(Color.BLACK);
            bgRect = centerCrop(bg, w, h);
            c.drawBitmap(bg, null, bgRect, bmp);
        } else {
            c.drawColor(Color.rgb(28, 36, 48));
        }

        if (showClock) {
            int color = p.getInt(Prefs.CLOCK_COLOR, Color.WHITE);
            boolean glass = p.getBoolean(Prefs.CLOCK_GLASS, true);
            boolean dark = Ui.darkGlass(ctx);
            float size = w * p.getInt(Prefs.CLOCK_SIZE, 30) / 100f;

            Path path = clockPath(ctx, p, w, h);
            RectF b = new RectF();
            path.computeBounds(b, true);

            // Tarikh di atas jam
            Paint dp = new Paint(Paint.ANTI_ALIAS_FLAG);
            dp.setColor(glass ? Color.WHITE : color);
            dp.setTextAlign(Paint.Align.CENTER);
            dp.setTextSize(Math.max(28f, size * 0.15f));
            dp.setTypeface(Typeface.create(Typeface.SANS_SERIF, 600, false));
            dp.setShadowLayer(size * 0.03f, 0, size * 0.008f, Color.argb(70, 0, 0, 0));
            String date = new SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(new Date());
            c.drawText(date, w / 2f, b.top - size * 0.1f, dp);

            if (glass) {
                int tint = (color & 0x00FFFFFF) == 0x00FFFFFF ? 0 : (color | 0xFF000000);
                LiquidGlass.glyph(c, path, size, bg != null ? sharp(bg) : null, bgRect, dark, tint);
            } else {
                Paint fp = new Paint(Paint.ANTI_ALIAS_FLAG);
                fp.setColor(color);
                c.drawPath(path, fp);
            }
        }

        if (fg != null) {
            c.drawBitmap(fg, null, centerCrop(fg, w, h), bmp);
        }
        if (p.getBoolean(Prefs.WALL_MOOD, false)) {
            try { Mood.apply(ctx, c, w, h); } catch (Throwable ignored) {}
        }
    }

    /** Kedudukan Y (piksel skrin) bahagian paling atas subjek, atau -1. */
    static float subjectTop(Bitmap fg, int w, int h) {
        if (fg == null) return -1;
        int sw = 72, sh = Math.max(1, Math.round(72f * fg.getHeight() / fg.getWidth()));
        Bitmap s = Bitmap.createScaledBitmap(fg, sw, sh, true);
        RectF r = centerCrop(fg, w, h);
        for (int y = 0; y < sh; y++) {
            int hits = 0;
            for (int x = sw / 6; x < sw * 5 / 6; x++) {
                if (Color.alpha(s.getPixel(x, y)) > 140) hits++;
            }
            if (hits >= 2) return r.top + (y + 0.5f) / sh * r.height();
        }
        return -1;
    }

    /** Letak jam supaya bahagian bawah digit tersorok di belakang kepala subjek. */
    static boolean autoPlace(Context ctx, SharedPreferences p, Bitmap fg, int w, int h) {
        float top = subjectTop(fg, w, h);
        if (top < 0) return false;
        float size = w * p.getInt(Prefs.CLOCK_SIZE, 30) / 100f;
        float capH = size * 0.72f * p.getInt(Prefs.CLOCK_H, 100) / 100f;
        float baseline = top + capH * 0.42f;
        int pct = Math.round(baseline / h * 100f);
        pct = Math.max(12, Math.min(70, pct));
        p.edit().putInt(Prefs.CLOCK_Y, pct).apply();
        return true;
    }
}
