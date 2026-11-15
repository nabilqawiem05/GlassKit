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
        sharpOut = GlassArt.blur(bg, 480, 5); // kaca beku: latar dalam digit dikaburkan lembut
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
            case 4: return Typeface.create(Typeface.create("google-sans", Typeface.NORMAL), 200, false); // sangat nipis
            case 5: return Typeface.create(Typeface.create("sans-serif-condensed", Typeface.NORMAL), 700, false); // sempit
            case 6: return Typeface.create(Typeface.create("google-sans", Typeface.NORMAL), 500, false); // lembut
            case 7: return Typeface.create(Typeface.SANS_SERIF, 900, false); // hitam tebal
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
        tp.setLetterSpacing(0.01f); // jarak kecil: digit tidak bersentuh / bertindih
        tp.setTypeface(font(p.getInt(Prefs.CLOCK_FONT, 0)));
        Path path = new Path();
        if (p.getInt(Prefs.CLOCK_LAYOUT, 0) == 1) {
            // A · jam bertindan: jam di atas, minit di bawah, lebih besar & rapat
            String[] hm = time.split(":");
            tp.setTextSize(size * 1.45f);
            tp.setLetterSpacing(-0.02f);
            Paint.FontMetrics fm = tp.getFontMetrics();
            float lineH = (fm.descent - fm.ascent) * 0.78f;
            Path a = new Path(), b2 = new Path();
            tp.getTextPath(hm[0], 0, hm[0].length(), w / 2f, y, a);
            tp.getTextPath(hm[1], 0, hm[1].length(), w / 2f, y + lineH, b2);
            path.addPath(a);
            path.addPath(b2);
        } else {
            tp.getTextPath(time, 0, time.length(), w / 2f, y, path);
        }
        Matrix m = new Matrix();
        m.setScale(sx, sy, w / 2f, y);
        path.transform(m);
        return path;
    }

    public static void draw(Context ctx, Canvas c, int w, int h, Bitmap bg, Bitmap fg,
                            SharedPreferences p, boolean showClock) {
        Paint bmp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        RectF bgRect = null;
        // gaya timbul: 0 biasa, 1 bingkai pecah, 2 bayang timbul, 3 gabung
        // hanya untuk lock screen (dengan jam); home screen kekal gambar asal
        int pop = showClock && fg != null && bg != null ? p.getInt(Prefs.POP_STYLE, 0) : 0;
        boolean frameOn = pop == 1 || pop == 3, shadowOn = pop == 2 || pop == 3;
        float density = ctx.getResources().getDisplayMetrics().density;
        RectF frame = null;
        if (bg != null) {
            c.drawColor(Color.BLACK);
            bgRect = centerCrop(bg, w, h);
            if (frameOn) {
                // luar bingkai: wallpaper sama, sangat kabur & gelap
                Bitmap soft = cached(bg, "soft", () -> GlassArt.blur(bg, 160, 6));
                c.drawBitmap(soft, null, bgRect, bmp);
                c.drawColor(Color.argb(150, 0, 0, 0));
                frame = popFrame(fg, w, h);
                float rr = 28 * density;
                c.save();
                Path clip = new Path();
                clip.addRoundRect(frame, rr, rr, Path.Direction.CW);
                c.clipPath(clip);
                c.drawBitmap(bg, null, bgRect, bmp);
                if (shadowOn) c.drawColor(Color.argb(40, 0, 0, 0));
                c.restore();
                Paint st = new Paint(Paint.ANTI_ALIAS_FLAG);
                st.setStyle(Paint.Style.STROKE);
                st.setStrokeWidth(2.5f * density);
                st.setColor(Color.argb(225, 255, 255, 255));
                c.drawRoundRect(frame, rr, rr, st);
            } else {
                c.drawBitmap(bg, null, bgRect, bmp);
                if (shadowOn) {
                    // latar sedikit kabur & gelap supaya subjek "terangkat"
                    Paint sp = new Paint(bmp);
                    sp.setAlpha(170);
                    c.drawBitmap(cached(bg, "mild", () -> GlassArt.blur(bg, 420, 2)), null, bgRect, sp);
                    c.drawColor(Color.argb(55, 0, 0, 0));
                }
            }
        } else {
            c.drawColor(Color.rgb(28, 36, 48));
        }

        if (showClock) drawClock(ctx, c, w, h, bg, bgRect, p);

        if (fg != null) {
            RectF fr = centerCrop(fg, w, h);
            c.save();
            if (frame != null) c.clipRect(0, 0, w, frame.bottom); // keluar bingkai ke atas & sisi sahaja
            if (shadowOn) {
                // bayang lembut di belakang subjek
                final int[] off = new int[2];
                Object[] so = shadowOf(fg);
                Bitmap sh = (Bitmap) so[0];
                off[0] = ((int[]) so[1])[0];
                off[1] = ((int[]) so[1])[1];
                Paint shp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
                shp.setColor(Color.argb(150, 0, 0, 0));
                float sx = fr.width() / fg.getWidth(), sy = fr.height() / fg.getHeight();
                RectF sr = new RectF(fr.left + off[0] * sx, fr.top + off[1] * sy,
                        fr.left + (off[0] + sh.getWidth()) * sx, fr.top + (off[1] + sh.getHeight()) * sy);
                sr.offset(w * 0.022f, h * 0.012f);
                c.drawBitmap(sh, null, sr, shp);
                // subjek dibesarkan sedikit dari tengahnya & diangkat
                RectF bb = subjectBox(fg, fr);
                float cx = bb.centerX(), cy = bb.centerY();
                c.scale(1.035f, 1.035f, cx, cy);
                c.translate(-w * 0.006f, -h * 0.004f);
            }
            c.drawBitmap(fg, null, fr, bmp);
            c.restore();
        }
        if (p.getBoolean(Prefs.WALL_MOOD, false)) {
            try { Mood.apply(ctx, c, w, h); } catch (Throwable ignored) {}
        }
    }

    /** Lukis tarikh + jam sahaja (digunakan juga oleh lapisan jam 3D). */
    static void drawClock(Context ctx, Canvas c, int w, int h, Bitmap bg, RectF bgRect, SharedPreferences p) {
            int color = p.getInt(Prefs.CLOCK_COLOR, Color.WHITE);
            if (p.getBoolean(Prefs.CLOCK_AUTO, false) && bg != null) color = autoColor(bg);
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
            float dy = b.top - size * 0.16f;
            if (p.getBoolean(Prefs.LOCK_HIJRI, false)) {
                // D · tarikh Hijri di bawah tarikh Masihi
                Paint hp = new Paint(dp);
                hp.setTextSize(dp.getTextSize() * 0.82f);
                hp.setColor(glass ? Color.argb(215, 200, 255, 230) : color);
                c.drawText(Hijri.today(ctx), w / 2f, dy, hp);
                dy -= hp.getTextSize() * 1.25f;
            }
            c.drawText(date, w / 2f, dy, dp);

            if (glass) {
                int tint = (color & 0x00FFFFFF) == 0x00FFFFFF ? 0 : (color | 0xFF000000);
                LiquidGlass.glyph(c, path, size, bg != null ? sharp(bg) : null, bgRect, dark, tint);
            } else {
                Paint fp = new Paint(Paint.ANTI_ALIAS_FLAG);
                fp.setColor(color);
                c.drawPath(path, fp);
            }
    }

    /** Sempadan menegak (atas, bawah) kawasan tarikh + jam pada skrin. */
    static float[] clockBounds(Context ctx, SharedPreferences p, int w, int h) {
        float size = w * p.getInt(Prefs.CLOCK_SIZE, 30) / 100f;
        Path path = clockPath(ctx, p, w, h);
        RectF b = new RectF();
        path.computeBounds(b, true);
        float top = b.top - size * 0.16f - Math.max(28f, size * 0.15f) * 1.4f;
        return new float[]{Math.max(0, top - size * 0.1f), Math.min(h, b.bottom + size * 0.12f)};
    }

    private static Bitmap autoSrc;
    private static int autoCol = Color.WHITE;

    /** Warna jam lembut diambil dari wallpaper (pastel, cukup terang untuk dibaca). */
    static synchronized int autoColor(Bitmap bg) {
        if (bg == autoSrc) return autoCol;
        int c = Color.WHITE;
        try {
            android.app.WallpaperColors wc = android.app.WallpaperColors.fromBitmap(bg);
            android.graphics.Color pc = wc.getPrimaryColor();
            float[] hsl = new float[3];
            androidx_hsl(pc.toArgb(), hsl);
            hsl[1] = Math.min(0.55f, Math.max(0.25f, hsl[1]));
            hsl[2] = 0.86f;
            c = hslToRgb(hsl);
        } catch (Throwable ignored) {
        }
        autoSrc = bg;
        autoCol = c;
        return c;
    }

    private static void androidx_hsl(int rgb, float[] out) {
        float r = Color.red(rgb) / 255f, g = Color.green(rgb) / 255f, b = Color.blue(rgb) / 255f;
        float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b)), l = (max + min) / 2f, h = 0, s = 0;
        if (max != min) {
            float d = max - min;
            s = l > 0.5f ? d / (2f - max - min) : d / (max + min);
            if (max == r) h = ((g - b) / d + (g < b ? 6 : 0));
            else if (max == g) h = (b - r) / d + 2;
            else h = (r - g) / d + 4;
            h *= 60f;
        }
        out[0] = h;
        out[1] = s;
        out[2] = l;
    }

    private static int hslToRgb(float[] hsl) {
        float h = hsl[0], s = hsl[1], l = hsl[2];
        float c = (1 - Math.abs(2 * l - 1)) * s, x = c * (1 - Math.abs((h / 60f) % 2 - 1)), m = l - c / 2f;
        float r, g, b;
        if (h < 60) { r = c; g = x; b = 0; } else if (h < 120) { r = x; g = c; b = 0; } else if (h < 180) { r = 0; g = c; b = x; }
        else if (h < 240) { r = 0; g = x; b = c; } else if (h < 300) { r = x; g = 0; b = c; } else { r = c; g = 0; b = x; }
        return Color.rgb(Math.round((r + m) * 255), Math.round((g + m) * 255), Math.round((b + m) * 255));
    }

    /** Kedudukan bingkai gaya "Bingkai" (di bawah kepala subjek). */
    static RectF popFrame(Bitmap fg, int w, int h) {
        float top = subjectTop(fg, w, h);
        float ft = top < 0 ? h * 0.36f : Math.min(h * 0.55f, Math.max(h * 0.30f, top + h * 0.11f));
        return new RectF(w * 0.06f, ft, w * 0.94f, h * 0.88f);
    }

    // ---- cache: dikira sekali bagi setiap wallpaper, bukan setiap minit ----
    private static final java.util.HashMap<String, Object[]> popCache = new java.util.HashMap<>();

    interface Make { Bitmap make(); }

    private static synchronized Bitmap cached(Bitmap src, String tag, Make m) {
        Object[] e = popCache.get(tag);
        if (e != null && e[0] == src) return (Bitmap) e[1];
        Bitmap b = m.make();
        popCache.put(tag, new Object[]{src, b});
        return b;
    }

    /** Bayang kabur subjek (dikira pada separuh saiz supaya ringan). */
    private static synchronized Object[] shadowOf(Bitmap fg) {
        Object[] e = popCache.get("shadow");
        if (e != null && e[0] == fg) return new Object[]{e[1], e[2]};
        float k = Math.min(1f, 900f / fg.getWidth());
        Bitmap small = k < 1f ? Bitmap.createScaledBitmap(fg, Math.round(fg.getWidth() * k), Math.round(fg.getHeight() * k), true) : fg;
        Paint bp = new Paint();
        bp.setMaskFilter(new android.graphics.BlurMaskFilter(Math.max(4f, small.getWidth() * 0.02f),
                android.graphics.BlurMaskFilter.Blur.NORMAL));
        int[] off = new int[2];
        Bitmap a = small.extractAlpha(bp, off);
        // simpan dalam koordinat imej asal (skala balik)
        Bitmap full = Bitmap.createScaledBitmap(a, Math.round(a.getWidth() / k), Math.round(a.getHeight() / k), true);
        int[] offFull = {Math.round(off[0] / k), Math.round(off[1] / k)};
        popCache.put("shadow", new Object[]{fg, full, offFull});
        return new Object[]{full, offFull};
    }

    /** Kotak sempadan subjek pada skrin (anggaran dari imej kecil). */
    static RectF subjectBox(Bitmap fg, RectF r) {
        int sw = 72, sh = Math.max(1, Math.round(72f * fg.getHeight() / fg.getWidth()));
        Bitmap s = Bitmap.createScaledBitmap(fg, sw, sh, true);
        int x0 = sw, y0 = sh, x1 = -1, y1 = -1;
        for (int y = 0; y < sh; y++) {
            for (int x = 0; x < sw; x++) {
                if (Color.alpha(s.getPixel(x, y)) > 110) {
                    if (x < x0) x0 = x;
                    if (x > x1) x1 = x;
                    if (y < y0) y0 = y;
                    if (y > y1) y1 = y;
                }
            }
        }
        if (x1 < 0) return new RectF(r);
        return new RectF(r.left + x0 / (float) sw * r.width(), r.top + y0 / (float) sh * r.height(),
                r.left + (x1 + 1) / (float) sw * r.width(), r.top + (y1 + 1) / (float) sh * r.height());
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
