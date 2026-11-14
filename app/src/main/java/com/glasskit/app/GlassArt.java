package com.glasskit.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;

/**
 * Pelukis "liquid glass" untuk laluan (teks, bulatan, bentuk).
 * Guna kanvas perisian sahaja (BlurMaskFilter).
 */
final class GlassArt {
    private GlassArt() {}

    /** Kabur kotak 3 laluan pada salinan kecil. Hasil: bitmap kecil yang licin. */
    static Bitmap blur(Bitmap src, int maxW, int radius) {
        if (src == null) return null;
        float sc = Math.min(1f, (float) maxW / src.getWidth());
        int w = Math.max(2, Math.round(src.getWidth() * sc)), h = Math.max(2, Math.round(src.getHeight() * sc));
        Bitmap small = Bitmap.createScaledBitmap(src, w, h, true);
        if (small.getConfig() != Bitmap.Config.ARGB_8888 || !small.isMutable()) {
            small = small.copy(Bitmap.Config.ARGB_8888, true);
        }
        int[] a = new int[w * h], b = new int[w * h];
        small.getPixels(a, 0, w, 0, 0, w, h);
        for (int pass = 0; pass < 3; pass++) {
            boxH(a, b, w, h, radius);
            boxV(b, a, w, h, radius);
        }
        small.setPixels(a, 0, w, 0, 0, w, h);
        return small;
    }

    private static void boxH(int[] in, int[] out, int w, int h, int r) {
        int div = r * 2 + 1;
        for (int y = 0; y < h; y++) {
            int row = y * w, ta = 0, tr = 0, tg = 0, tb = 0;
            for (int i = -r; i <= r; i++) {
                int p = in[row + Math.min(w - 1, Math.max(0, i))];
                ta += p >>> 24; tr += (p >> 16) & 0xff; tg += (p >> 8) & 0xff; tb += p & 0xff;
            }
            for (int x = 0; x < w; x++) {
                out[row + x] = ((ta / div) << 24) | ((tr / div) << 16) | ((tg / div) << 8) | (tb / div);
                int pOut = in[row + Math.max(0, x - r)], pIn = in[row + Math.min(w - 1, x + r + 1)];
                ta += (pIn >>> 24) - (pOut >>> 24);
                tr += ((pIn >> 16) & 0xff) - ((pOut >> 16) & 0xff);
                tg += ((pIn >> 8) & 0xff) - ((pOut >> 8) & 0xff);
                tb += (pIn & 0xff) - (pOut & 0xff);
            }
        }
    }

    private static void boxV(int[] in, int[] out, int w, int h, int r) {
        int div = r * 2 + 1;
        for (int x = 0; x < w; x++) {
            int ta = 0, tr = 0, tg = 0, tb = 0;
            for (int i = -r; i <= r; i++) {
                int p = in[Math.min(h - 1, Math.max(0, i)) * w + x];
                ta += p >>> 24; tr += (p >> 16) & 0xff; tg += (p >> 8) & 0xff; tb += p & 0xff;
            }
            for (int y = 0; y < h; y++) {
                out[y * w + x] = ((ta / div) << 24) | ((tr / div) << 16) | ((tg / div) << 8) | (tb / div);
                int pOut = in[Math.max(0, y - r) * w + x], pIn = in[Math.min(h - 1, y + r + 1) * w + x];
                ta += (pIn >>> 24) - (pOut >>> 24);
                tr += ((pIn >> 16) & 0xff) - ((pOut >> 16) & 0xff);
                tg += ((pIn >> 8) & 0xff) - ((pOut >> 8) & 0xff);
                tb += (pIn & 0xff) - (pOut & 0xff);
            }
        }
    }

    private static int mix(int a, int b, float t) {
        return Color.argb(
                Math.round(Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t),
                Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    /**
     * Lukis laluan sebagai kaca.
     * @param bgBlur  wallpaper kabur untuk biasan (boleh null)
     * @param bgRect  di mana bgBlur dipetakan pada kanvas
     */
    static void draw(Canvas c, Path path, float size, int tint, boolean dark, Bitmap bgBlur, RectF bgRect) {
        RectF b = new RectF();
        path.computeBounds(b, true);
        if (b.height() <= 0) return;

        // 1) bayang lembut di bawah
        Paint sh = new Paint(Paint.ANTI_ALIAS_FLAG);
        sh.setColor(Color.argb(dark ? 120 : 80, 0, 0, 0));
        sh.setMaskFilter(new BlurMaskFilter(Math.max(2f, size * 0.06f), BlurMaskFilter.Blur.NORMAL));
        c.save();
        c.translate(0, size * 0.03f);
        c.drawPath(path, sh);
        c.restore();

        c.save();
        c.clipPath(path);
        // 2) biasan: wallpaper kabur, dianjak sedikit & dicerahkan
        if (bgBlur != null && bgRect != null) {
            Paint fp = new Paint(Paint.FILTER_BITMAP_FLAG);
            ColorMatrix cm = new ColorMatrix();
            float k = dark ? 0.7f : 1.05f, t = dark ? -6 : 70;
            cm.set(new float[]{k, 0, 0, 0, t, 0, k, 0, 0, t, 0, 0, k, 0, t, 0, 0, 0, 1, 0});
            ColorMatrix sat = new ColorMatrix();
            sat.setSaturation(1.3f);
            cm.postConcat(sat);
            fp.setColorFilter(new ColorMatrixColorFilter(cm));
            RectF r = new RectF(bgRect);
            r.offset(size * 0.025f, size * 0.035f);
            c.drawBitmap(bgBlur, null, r, fp);
        }
        // 3) isi beku (frosted) dengan sedikit warna tema
        int top = dark ? mix(Color.argb(120, 40, 40, 46), tint, 0.18f) : mix(Color.argb(bgBlur != null ? 120 : 175, 255, 255, 255), tint, 0.22f);
        int bot = dark ? mix(Color.argb(80, 20, 20, 24), tint, 0.10f) : mix(Color.argb(bgBlur != null ? 70 : 115, 255, 255, 255), tint, 0.30f);
        Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
        body.setShader(new LinearGradient(0, b.top, 0, b.bottom, top, bot, Shader.TileMode.CLAMP));
        c.drawRect(b, body);
        // 4) kilauan atas & cahaya dalam bawah
        Paint gl = new Paint(Paint.ANTI_ALIAS_FLAG);
        gl.setShader(new LinearGradient(0, b.top, 0, b.bottom,
                new int[]{Color.argb(dark ? 70 : 140, 255, 255, 255), Color.argb(0, 255, 255, 255),
                        Color.argb(0, 255, 255, 255), Color.argb(dark ? 40 : 90, 255, 255, 255)},
                new float[]{0f, 0.38f, 0.75f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(b, gl);
        c.restore();

        // 5) bingkai cahaya
        Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(Math.max(1.4f, size * 0.016f));
        rim.setShader(new LinearGradient(b.left, b.top, b.right, b.bottom,
                new int[]{Color.argb(245, 255, 255, 255), Color.argb(70, 255, 255, 255),
                        Color.argb(40, 255, 255, 255), Color.argb(200, 255, 255, 255)},
                new float[]{0f, 0.35f, 0.65f, 1f}, Shader.TileMode.CLAMP));
        c.drawPath(path, rim);
    }

    static int tint(Context c) {
        return Ui.accent(c, false);
    }
}
