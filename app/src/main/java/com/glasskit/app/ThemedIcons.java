package com.glasskit.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;

import java.util.HashMap;
import java.util.Map;

/**
 * Ikon app ikut tema launcher.
 * 0 Classic: warna asal dalam bentuk bucu iOS + kilauan kaca.
 * 1 Liquid / 2 Bento / 3 Clay: logo sahaja (lapisan "themed icon" Android 13+, atau gantian) diwarnakan ikut tema.
 */
final class ThemedIcons {
    private ThemedIcons() {}

    /** Pasangan Bento: kotak gelap + logo cerah sewarna. */
    static final int[] BENTO_BG = {0xFF2A4A3F, 0xFF5A2A44, 0xFF4A3A6B, 0xFF3A4A6B, 0xFF6B4A2A, 0xFF2A3F5A};
    static final int[] BENTO_FG = {0xFF9FE1CB, 0xFFF4C0D1, 0xFFCECBF6, 0xFFB5D4F4, 0xFFFAC775, 0xFF85B7EB};

    private static final Map<String, Bitmap> cache = new HashMap<>();
    private static String cacheSig = "";

    static int bentoIdx(String key) { return Math.abs(key.hashCode()) % BENTO_BG.length; }

    static synchronized Bitmap get(Drawable icon, String key, int theme, int size, int glyphColor) {
        String sig = theme + ":" + size + ":" + glyphColor;
        String k = sig + "|" + key;
        Bitmap b = cache.get(k);
        if (b != null && !b.isRecycled()) return b;
        b = theme == 0 ? classic(icon, size) : glyph(icon, size, glyphColor);
        cache.put(k, b);
        return b;
    }

    /** Buang cache bila tema / palet bertukar. */
    static synchronized void reset(String sig) {
        if (sig.equals(cacheSig)) return;
        cacheSig = sig;
        cache.clear();
    }

    // ---- Classic: lapisan latar + depan dipotong bucu iOS, kilauan kaca di atas ----
    private static Bitmap classic(Drawable d, int s) {
        Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        float r = s * 0.235f;
        RectF box = new RectF(0, 0, s, s);
        Path clip = new Path();
        clip.addRoundRect(box, r, r, Path.Direction.CW);
        if (d instanceof AdaptiveIconDrawable) {
            AdaptiveIconDrawable a = (AdaptiveIconDrawable) d;
            c.save();
            c.clipPath(clip);
            // lapisan adaptif ialah 108dp dengan 72dp kelihatan: besarkan 1.5x dan pusatkan
            int ext = Math.round(s * 0.25f);
            if (a.getBackground() != null) {
                a.getBackground().setBounds(-ext, -ext, s + ext, s + ext);
                a.getBackground().draw(c);
            }
            if (a.getForeground() != null) {
                a.getForeground().setBounds(-ext, -ext, s + ext, s + ext);
                a.getForeground().draw(c);
            }
            c.restore();
        } else {
            d.setBounds(0, 0, s, s);
            d.draw(c);
        }
        // kilauan kaca: cahaya lembut di separuh atas + tepi halus
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        c.save();
        c.clipPath(clip);
        p.setShader(new LinearGradient(0, 0, 0, s * 0.55f, Color.argb(70, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP));
        c.drawRect(box, p);
        c.restore();
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1f, s / 60f));
        p.setColor(Color.argb(90, 255, 255, 255));
        RectF in = new RectF(box);
        in.inset(p.getStrokeWidth() / 2f, p.getStrokeWidth() / 2f);
        c.drawRoundRect(in, r, r, p);
        return b;
    }

    // ---- Logo sahaja, diwarnakan ----
    private static Bitmap glyph(Drawable d, int s, int color) {
        Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        int g = Math.round(s * 0.6f);              // kotak logo
        int off = (s - g) / 2;
        if (d instanceof AdaptiveIconDrawable) {
            AdaptiveIconDrawable a = (AdaptiveIconDrawable) d;
            Drawable mono = Build.VERSION.SDK_INT >= 33 ? a.getMonochrome() : null;
            boolean trusted = mono != null;
            if (mono == null && a.getForeground() != null && coverage(a.getForeground()) < 0.55f) mono = a.getForeground();
            if (mono != null) {
                Drawable m = mono.mutate();
                int ext = Math.round(g * 0.25f);
                m.setBounds(off - ext, off - ext, off + g + ext, off + g + ext);
                m.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
                m.draw(c);
                m.setColorFilter(null);
                if (trusted || coverageOf(b) > 0.02f) return b;
                b.eraseColor(0);
            }
        }
        // ikon penuh gambar (lapisan depan menutup semua) atau ikon lama: kekalkan butiran ikut kecerahan
        return detailed(d, s, color);
    }

    /** Peratus kawasan kelihatan (72/108 tengah) yang legap. */
    private static float coverage(Drawable d) {
        Bitmap t = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888);
        d.setBounds(0, 0, 48, 48);
        d.draw(new Canvas(t));
        int on = 0, n = 0;
        for (int y = 8; y < 40; y += 2)
            for (int x = 8; x < 40; x += 2) {
                n++;
                if (Color.alpha(t.getPixel(x, y)) > 160) on++;
            }
        t.recycle();
        return n == 0 ? 0 : on / (float) n;
    }

    private static float coverageOf(Bitmap b) {
        int on = 0, n = 0;
        for (int y = 0; y < b.getHeight(); y += 4)
            for (int x = 0; x < b.getWidth(); x += 4) {
                n++;
                if (Color.alpha(b.getPixel(x, y)) > 60) on++;
            }
        return n == 0 ? 0 : on / (float) n;
    }

    /**
     * Lukis ikon penuh (bucu bulat), kemudian tukar kepada satu warna: bahagian yang berbeza kecerahan
     * dari purata ikon menjadi logo. Elak kotak kosong untuk ikon bergambar penuh.
     */
    private static Bitmap detailed(Drawable d, int s, int color) {
        int g = Math.round(s * 0.66f);
        Bitmap src = Bitmap.createBitmap(g, g, Bitmap.Config.ARGB_8888);
        Canvas sc = new Canvas(src);
        Path clip = new Path();
        clip.addRoundRect(new RectF(0, 0, g, g), g * 0.24f, g * 0.24f, Path.Direction.CW);
        sc.clipPath(clip);
        if (d instanceof AdaptiveIconDrawable) {
            AdaptiveIconDrawable a = (AdaptiveIconDrawable) d;
            int ext = Math.round(g * 0.25f);
            if (a.getBackground() != null) { a.getBackground().setBounds(-ext, -ext, g + ext, g + ext); a.getBackground().draw(sc); }
            if (a.getForeground() != null) { a.getForeground().setBounds(-ext, -ext, g + ext, g + ext); a.getForeground().draw(sc); }
        } else {
            d.setBounds(0, 0, g, g);
            d.draw(sc);
        }
        // purata kecerahan
        double sum = 0;
        int n = 0;
        for (int y = 0; y < g; y += 3)
            for (int x = 0; x < g; x += 3) {
                int p = src.getPixel(x, y);
                if (Color.alpha(p) < 128) continue;
                sum += (0.299 * Color.red(p) + 0.587 * Color.green(p) + 0.114 * Color.blue(p)) / 255.0;
                n++;
            }
        float avg = n == 0 ? 0.5f : (float) (sum / n);
        float k = avg > 0.5f ? -2.2f : 2.2f;                 // logo = bahagian lebih gelap / lebih cerah dari latar
        float o = -k * avg * 255f;
        float r = Color.red(color), gr = Color.green(color), bl = Color.blue(color);
        ColorMatrix cm = new ColorMatrix(new float[]{
                0, 0, 0, 0, r,
                0, 0, 0, 0, gr,
                0, 0, 0, 0, bl,
                k * 0.299f, k * 0.587f, k * 0.114f, 0, o});
        Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        p.setColorFilter(new ColorMatrixColorFilter(cm));
        int off = (s - g) / 2;
        new Canvas(b).drawBitmap(src, off, off, p);
        src.recycle();
        // jika hampir kosong (ikon satu warna rata), tunjuk bentuk ikon sebagai siluet lembut
        if (coverageOf(b) < 0.03f) {
            b.eraseColor(0);
            Canvas bc = new Canvas(b);
            Paint sp = new Paint(Paint.ANTI_ALIAS_FLAG);
            sp.setColor(color);
            sp.setAlpha(200);
            float rr = g * 0.24f;
            bc.drawRoundRect(new RectF(off, off, off + g, off + g), rr, rr, sp);
        }
        return b;
    }
}
