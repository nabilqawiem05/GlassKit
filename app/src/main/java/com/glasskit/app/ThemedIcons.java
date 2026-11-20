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
        Drawable mono = null;
        if (d instanceof AdaptiveIconDrawable) {
            AdaptiveIconDrawable a = (AdaptiveIconDrawable) d;
            if (Build.VERSION.SDK_INT >= 33) mono = a.getMonochrome();
            if (mono == null) mono = a.getForeground();
            if (mono != null) {
                Drawable m = mono.mutate();
                int ext = Math.round(g * 0.25f);
                m.setBounds(off - ext, off - ext, off + g + ext, off + g + ext);
                m.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
                m.draw(c);
                m.setColorFilter(null);
                return b;
            }
        }
        // ikon lama (bukan adaptif): kekalkan butiran, ubah kepada satu warna ikut kecerahan
        Drawable m = d.mutate();
        float r = Color.red(color) / 255f, gr = Color.green(color) / 255f, bl = Color.blue(color) / 255f;
        ColorMatrix cm = new ColorMatrix(new float[]{
                0.299f * r, 0.587f * r, 0.114f * r, 0, 0,
                0.299f * gr, 0.587f * gr, 0.114f * gr, 0, 0,
                0.299f * bl, 0.587f * bl, 0.114f * bl, 0, 0,
                0, 0, 0, 1, 0});
        m.setColorFilter(new ColorMatrixColorFilter(cm));
        int pad = Math.round(s * 0.14f);
        m.setBounds(pad, pad, s - pad, s - pad);
        m.draw(c);
        m.setColorFilter(null);
        return b;
    }
}
