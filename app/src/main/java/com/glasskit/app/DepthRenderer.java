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
    private static String pathKey;
    private static Path pathCache;

    /** Laluan jam dengan cache (dipanggil berkali-kali di UI thread: tolok, sentuhan, sunting). */
    static Path clockPath(Context ctx, SharedPreferences p, int w, int h) {
        String time = new SimpleDateFormat(DateFormat.is24HourFormat(ctx) ? "HH:mm" : "h:mm", Locale.getDefault()).format(new Date());
        int shape = p.getInt(Prefs.CLOCK_SHAPE, 0);
        String key = time + "|" + w + "x" + h + "|" + p.getInt(Prefs.CLOCK_SIZE, 30) + "|" + p.getInt(Prefs.CLOCK_Y, 28)
                + "|" + p.getInt(Prefs.CLOCK_W, 100) + "|" + p.getInt(Prefs.CLOCK_H, 100) + "|" + p.getInt(Prefs.CLOCK_FONT, 0)
                + "|" + p.getInt(Prefs.CLOCK_LAYOUT, 0) + "|" + shape
                + "|" + (shape != 0 ? System.identityHashCode(ClockShape.of(ctx, w, h)) : 0);
        synchronized (DepthRenderer.class) {
            if (key.equals(pathKey) && pathCache != null) return new Path(pathCache);
        }
        Path made = buildClockPath(ctx, p, w, h);
        synchronized (DepthRenderer.class) {
            pathKey = key;
            pathCache = new Path(made);
        }
        return made;
    }

    private static Path buildClockPath(Context ctx, SharedPreferences p, int w, int h) {
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
        int shape = p.getInt(Prefs.CLOCK_SHAPE, 0);
        if (shape != ClockShape.NONE) {
            // jam ikut bentuk wallpaper (A–F); jika tidak sesuai, guna susunan biasa
            // kedudukan asal ikut gambar; slider/seretan "atas-bawah" mengalih dari situ (28% = asal)
            float base = h * 0.28f;
            Path sp = ClockShape.build(shape, ClockShape.of(ctx, w, h), tp, time, size, base, sx, sy, w, h);
            if (sp != null) {
                sp.offset(0, y - base);
                return sp;
            }
        }
        if (p.getInt(Prefs.CLOCK_LAYOUT, 0) == 2) {
            // Tinggi · gaya iPhone: atas digit kekal, tarik ke bawah = digit memanjang, garis tidak menebal
            float wd = size * 0.62f * sx;
            float top = y - size * 0.72f;
            return TallClock.build(time, w / 2f, top, wd, size * 0.72f * sy, size * 0.62f * 0.27f * Math.min(1.3f, sx));
        }
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
        m.setScale(sx, sy, w / 2f, y - size * 0.72f); // atas kekal: tarik tinggi memanjang ke bawah
        path.transform(m);
        return path;
    }

    public static void draw(Context ctx, Canvas c, int w, int h, Bitmap bg, Bitmap fg,
                            SharedPreferences p, boolean showClock) {
        draw(ctx, c, w, h, bg, fg, p, showClock, showClock);
    }

    /** @param clockOn false = gaya lock screen sama, tetapi tanpa jam (untuk animasi jam larut semasa slide). */
    public static void draw(Context ctx, Canvas c, int w, int h, Bitmap bg, Bitmap fg,
                            SharedPreferences p, boolean showClock, boolean clockOn) {
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

        if (showClock && clockOn) drawClock(ctx, c, w, h, bg, bgRect, p);

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
            }
            Bitmap clean = bg != null ? cached(fg, "edge", () -> refineEdge(fg, bg)) : fg;
            c.drawBitmap(clean, null, fr, bmp);
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
            int shape = p.getInt(Prefs.CLOCK_SHAPE, 0);
            float gsize = size; // saiz untuk ketebalan kaca: ikut saiz sebenar digit
            if (shape == ClockShape.SKY || shape == ClockShape.HALO)
                gsize = Math.max(size * 0.4f, Math.min(size * 4f, b.height() / 0.75f));

            // Tarikh di atas jam
            Paint dp = new Paint(Paint.ANTI_ALIAS_FLAG);
            dp.setColor(glass ? Color.WHITE : color);
            dp.setTextAlign(Paint.Align.CENTER);
            dp.setTextSize(Math.max(28f, size * 0.15f));
            dp.setTypeface(Ui.font(600));
            dp.setShadowLayer(size * 0.03f, 0, size * 0.008f, Color.argb(70, 0, 0, 0));
            String date = new SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(new Date());
            float dy = b.top - size * 0.16f;
            if (shape == ClockShape.SAND) dy = h * 0.14f; // jam di tanah: tarikh kekal di atas
            if (dy - dp.getTextSize() * 2.4f < h * 0.05f && shape != ClockShape.NONE)
                dy = b.bottom + dp.getTextSize() * 1.4f; // tiada ruang di atas: tarikh di bawah jam
            if (p.getBoolean(Prefs.LOCK_HIJRI, false)) {
                // D · tarikh Hijri di bawah tarikh Masihi
                Paint hp = new Paint(dp);
                hp.setTextSize(dp.getTextSize() * 0.82f);
                hp.setColor(glass ? Color.argb(215, 200, 255, 230) : color);
                c.drawText(Hijri.today(ctx), w / 2f, dy, hp);
                dy -= hp.getTextSize() * 1.25f;
            }
            c.drawText(date, w / 2f, dy, dp);

            ClockShape.Scene scene = shape == ClockShape.SKY ? ClockShape.of(ctx, w, h) : null;
            boolean masked = scene != null && scene.skyMask != null;
            if (masked) c.saveLayer(null, null);
            ClockShape.under(shape, c, path, size);
            if (glass) {
                int tint = (color & 0x00FFFFFF) == 0x00FFFFFF ? 0 : (color | 0xFF000000);
                LiquidGlass.glyph(c, path, gsize, bg != null ? sharp(bg) : null, bgRect, dark, tint);
            } else {
                Paint fp = new Paint(Paint.ANTI_ALIAS_FLAG);
                fp.setColor(color);
                c.drawPath(path, fp);
            }
            if (masked) {
                ClockShape.maskSky(c, scene, w, h);
                c.restore();
            }
            try {
                ClockShape.over(shape, c, path, b, color, size, w, h);
            } catch (Throwable ignored) {
            }
    }

    /** Kedudukan atas baris tolok (di bawah jam, atau di bawah tarikh/pantulan untuk gaya bentuk). */
    static float gaugeTop(Context ctx, SharedPreferences p, int w, int h) {
        RectF b = clockRect(ctx, p, w, h);
        int shape = p.getInt(Prefs.CLOCK_SHAPE, 0);
        float size = w * p.getInt(Prefs.CLOCK_SIZE, 30) / 100f;
        if (shape == ClockShape.SAND) return h * 0.14f + size * 0.14f;
        if (shape == ClockShape.REFLECT) return Math.min(h * 0.8f, b.bottom + b.height() * 0.75f);
        if (shape == ClockShape.SKY) {
            ClockShape.Scene s = ClockShape.of(ctx, w, h);
            if (s != null && s.horizonOk) return Math.min(b.bottom, s.horizonMid + size * 0.1f);
        }
        return b.bottom;
    }

    /** Kotak jam (piksel skrin). */
    static RectF clockRect(Context ctx, SharedPreferences p, int w, int h) {
        RectF b = new RectF();
        clockPath(ctx, p, w, h).computeBounds(b, true);
        return b;
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

    /**
     * Tepi subjek bersih: topeng dikaburkan sedikit lalu ditajamkan semula (smoothstep) supaya tepi
     * "tangga" jadi licin, dan warna diambil dari gambar asal (tiada pinggir gelap/putih).
     */
    static Bitmap refineEdge(Bitmap fg, Bitmap bg) {
        try {
            int fw = fg.getWidth(), fh = fg.getHeight();
            Bitmap src = bg;
            if (bg.getWidth() != fw || bg.getHeight() != fh) src = Bitmap.createScaledBitmap(bg, fw, fh, true);
            int[] col = new int[fw * fh];
            src.getPixels(col, 0, fw, 0, 0, fw, fh);
            int[] fp = new int[fw * fh];
            fg.getPixels(fp, 0, fw, 0, 0, fw, fh);
            float[] a = new float[fw * fh], t = new float[fw * fh];
            for (int i = 0; i < a.length; i++) a[i] = (fp[i] >>> 24) / 255f;
            fp = null;
            int r = Math.max(1, Math.round(fw * 0.0016f));
            // kabur kotak dua laluan (mendatar, menegak), dua kali ≈ gaussian
            for (int pass = 0; pass < 2; pass++) {
                for (int y = 0; y < fh; y++) {
                    int row = y * fw;
                    float sum = 0;
                    for (int x = -r; x <= r; x++) sum += a[row + Math.max(0, Math.min(fw - 1, x))];
                    for (int x = 0; x < fw; x++) {
                        t[row + x] = sum / (2 * r + 1);
                        sum += a[row + Math.min(fw - 1, x + r + 1)] - a[row + Math.max(0, x - r)];
                    }
                }
                for (int x = 0; x < fw; x++) {
                    float sum = 0;
                    for (int y = -r; y <= r; y++) sum += t[Math.max(0, Math.min(fh - 1, y)) * fw + x];
                    for (int y = 0; y < fh; y++) {
                        a[y * fw + x] = sum / (2 * r + 1);
                        sum += t[Math.min(fh - 1, y + r + 1) * fw + x] - t[Math.max(0, y - r) * fw + x];
                    }
                }
            }
            for (int i = 0; i < a.length; i++) {
                float v = (a[i] - 0.42f) / 0.26f; // ambang sedikit ke dalam: buang sisa latar di tepi
                v = v <= 0 ? 0 : v >= 1 ? 1 : v * v * (3 - 2 * v);
                int al = Math.round(v * 255);
                col[i] = al == 0 ? 0 : (al << 24) | (col[i] & 0x00FFFFFF);
            }
            Bitmap out = Bitmap.createBitmap(fw, fh, Bitmap.Config.ARGB_8888);
            out.setPixels(col, 0, fw, 0, 0, fw, fh);
            return out;
        } catch (Throwable e) {
            return fg;
        }
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
        // atas digit = y - 0.72·size, bawah = atas + capH; 42% bawah digit tersorok di belakang kepala
        float baseline = top + capH * 0.42f - capH + size * 0.72f;
        int pct = Math.round(baseline / h * 100f);
        pct = Math.max(12, Math.min(70, pct));
        p.edit().putInt(Prefs.CLOCK_Y, pct).apply();
        return true;
    }
}
