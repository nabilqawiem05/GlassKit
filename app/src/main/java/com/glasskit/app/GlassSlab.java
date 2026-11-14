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
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;

/**
 * Kepingan liquid glass 3D untuk latar widget: bayang, isi kabur dari wallpaper,
 * tepi tebal (bevel), kilauan spekular dan bingkai cahaya. Tema jernih atau gelap.
 */
final class GlassSlab {
    private GlassSlab() {}

    private static Bitmap src, blur;
    private static long ver = Long.MIN_VALUE;

    private static synchronized Bitmap wallBlur(Context c) {
        long v = Prefs.get(c).getLong(Prefs.IMG_VER, 0);
        if (v != ver || blur == null) {
            ver = v;
            src = DepthRenderer.load(DepthRenderer.bgFile(c));
            blur = src != null ? GlassArt.blur(src, 160, 8) : null;
        }
        return blur;
    }

    static void draw(Context c, Canvas cv, RectF r, boolean dark) {
        float rad = Math.min(r.width(), r.height()) * 0.2f;
        rad = Math.min(rad, Math.min(r.width(), r.height()) / 2f);
        float thick = Math.max(3f, Math.min(r.width(), r.height()) * 0.035f);
        Path slab = new Path();
        slab.addRoundRect(r, rad, rad, Path.Direction.CW);

        // 1) bayang di bawah kepingan
        Paint sh = new Paint(Paint.ANTI_ALIAS_FLAG);
        sh.setColor(Color.argb(dark ? 150 : 95, 0, 0, 0));
        sh.setMaskFilter(new BlurMaskFilter(thick * 3.2f, BlurMaskFilter.Blur.NORMAL));
        cv.save();
        cv.translate(0, thick * 1.4f);
        cv.drawPath(slab, sh);
        cv.restore();

        cv.save();
        cv.clipPath(slab);
        // 2) isi: wallpaper kabur (biasan) atau warna tema
        Bitmap b = wallBlur(c);
        if (b != null) {
            Paint fp = new Paint(Paint.FILTER_BITMAP_FLAG);
            ColorMatrix cm = new ColorMatrix();
            float k = dark ? 0.55f : 1.08f, t = dark ? -12 : 26;
            cm.set(new float[]{k, 0, 0, 0, t, 0, k, 0, 0, t, 0, 0, k, 0, t, 0, 0, 0, 1, 0});
            ColorMatrix sat = new ColorMatrix();
            sat.setSaturation(dark ? 0.9f : 1.35f);
            cm.postConcat(sat);
            fp.setColorFilter(new ColorMatrixColorFilter(cm));
            float sc = Math.max(r.width() / b.getWidth(), r.height() / b.getHeight()) * 1.15f;
            float bw = b.getWidth() * sc, bh = b.getHeight() * sc;
            cv.drawBitmap(b, null, new RectF(r.centerX() - bw / 2, r.centerY() - bh / 2, r.centerX() + bw / 2, r.centerY() + bh / 2), fp);
        } else {
            Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
            bg.setShader(new LinearGradient(0, r.top, 0, r.bottom,
                    dark ? 0xFF2A2C34 : Ui.accent(c, false), dark ? 0xFF121318 : Ui.accent(c, true), Shader.TileMode.CLAMP));
            cv.drawRect(r, bg);
        }
        Paint tint = new Paint();
        tint.setColor(dark ? Color.argb(120, 8, 8, 12) : Color.argb(46, 255, 255, 255));
        cv.drawRect(r, tint);

        // 3) kilauan spekular atas kiri
        Paint spec = new Paint(Paint.ANTI_ALIAS_FLAG);
        spec.setShader(new RadialGradient(r.left + r.width() * 0.28f, r.top, r.width() * 0.75f,
                Color.argb(dark ? 55 : 110, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP));
        cv.drawRect(r, spec);
        // 4) bayang dalam di bawah (rasa tebal)
        Paint low = new Paint(Paint.ANTI_ALIAS_FLAG);
        low.setShader(new LinearGradient(0, r.bottom - r.height() * 0.3f, 0, r.bottom,
                Color.argb(0, 0, 0, 0), Color.argb(dark ? 90 : 45, 0, 0, 0), Shader.TileMode.CLAMP));
        cv.drawRect(r, low);
        // 5) bevel: tepi dalam terang atas-kiri, gelap bawah-kanan
        RectF in = new RectF(r);
        in.inset(thick * 0.55f, thick * 0.55f);
        Paint bev = new Paint(Paint.ANTI_ALIAS_FLAG);
        bev.setStyle(Paint.Style.STROKE);
        bev.setStrokeWidth(thick);
        bev.setMaskFilter(new BlurMaskFilter(thick * 0.6f, BlurMaskFilter.Blur.NORMAL));
        bev.setShader(new LinearGradient(r.left, r.top, r.right, r.bottom,
                new int[]{Color.argb(dark ? 90 : 150, 255, 255, 255), Color.argb(0, 255, 255, 255), Color.argb(dark ? 70 : 40, 0, 0, 0)},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        cv.drawRoundRect(in, rad - thick * 0.5f, rad - thick * 0.5f, bev);
        cv.restore();

        // 6) bingkai cahaya luar
        Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(Math.max(1.2f, thick * 0.32f));
        rim.setShader(new LinearGradient(r.left, r.top, r.right, r.bottom,
                new int[]{Color.argb(dark ? 160 : 235, 255, 255, 255), Color.argb(dark ? 30 : 60, 255, 255, 255),
                        Color.argb(dark ? 20 : 40, 255, 255, 255), Color.argb(dark ? 110 : 170, 255, 255, 255)},
                new float[]{0f, 0.4f, 0.6f, 1f}, Shader.TileMode.CLAMP));
        RectF rr = new RectF(r);
        rr.inset(rim.getStrokeWidth() / 2, rim.getStrokeWidth() / 2);
        cv.drawRoundRect(rr, rad, rad, rim);
    }
}
