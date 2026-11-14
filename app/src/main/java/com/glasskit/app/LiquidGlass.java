package com.glasskit.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;

/**
 * Bahan "Liquid Glass" yang dikongsi oleh seluruh GlassKit.
 *
 * Permukaan (kad, tile, panel) dan glyph (nombor jam, teks widget) guna resipi yang sama:
 *  1. biasan  - latar belakang di belakang kaca dibesarkan sedikit (kanta) dan lebih kuat di tepi
 *  2. kabur adaptif - permukaan guna latar kabur, glyph guna latar hampir tajam
 *  3. ketelusan dinamik - warna tint ikut kecerahan latar
 *  4. kilauan spekular di atas, cahaya dalam di bawah
 *  5. bingkai bercahaya (luminous rim) dan bayang terapung
 */
final class LiquidGlass {
    private LiquidGlass() {}

    /** Latar belakang skrin yang dibiaskan oleh kaca. Boleh diisi kemudian (cth. selepas tangkapan skrin). */
    static final class Backdrop {
        Bitmap sharp;   // hampir tajam (untuk glyph)
        Bitmap soft;    // kabur (untuk permukaan)
        int screenW, screenH;
        float luminance = 0.5f;

        void set(Bitmap screen, int sw, int sh) {
            screenW = sw;
            screenH = sh;
            if (screen == null) {
                sharp = soft = null;
                return;
            }
            try {
                Bitmap sh2 = GlassArt.blur(screen, 540, 1);
                Bitmap so = GlassArt.blur(screen, 220, 7);
                sharp = sh2;
                soft = so;
                luminance = lum(so);
            } catch (Throwable t) {
                sharp = soft = null;
            }
        }

        boolean ready() { return soft != null; }
    }

    /** Backdrop untuk tetingkap yang sedang dibina (diambil semasa drawable dicipta). */
    static Backdrop current;

    static float lum(Bitmap b) {
        try {
            Bitmap s = Bitmap.createScaledBitmap(b, 6, 6, true);
            float t = 0;
            for (int x = 0; x < 6; x++) {
                for (int y = 0; y < 6; y++) {
                    int p = s.getPixel(x, y);
                    t += (0.299f * Color.red(p) + 0.587f * Color.green(p) + 0.114f * Color.blue(p)) / 255f;
                }
            }
            return t / 36f;
        } catch (Exception e) {
            return 0.5f;
        }
    }

    static void invalidateTree(View v) {
        v.invalidate();
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) invalidateTree(g.getChildAt(i));
        }
    }

    /** Pasang permukaan kaca pada View, dengan bayang terapung. */
    static void apply(View v, float radiusDp, boolean dark) {
        float r = Ui.dp(v.getContext(), radiusDp);
        v.setBackground(new Surface(v.getContext(), r, dark, current, 0));
        v.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline o) {
                o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
            }
        });
        v.setElevation(Ui.dp(v.getContext(), 3));
        v.setOutlineAmbientShadowColor(Color.argb(60, 0, 0, 0));
        v.setOutlineSpotShadowColor(Color.argb(90, 0, 0, 0));
    }

    // ======================= Permukaan =======================

    static final class Surface extends Drawable {
        private final float radius, dp;
        private final boolean dark;
        private final Backdrop bd;
        private final int tint;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Path rr = new Path();
        private final int[] loc = new int[2];

        Surface(Context c, float radiusPx, boolean dark, Backdrop bd, int tint) {
            this.radius = radiusPx;
            this.dark = dark;
            this.bd = bd;
            this.tint = tint;
            this.dp = c.getResources().getDisplayMetrics().density;
        }

        @Override
        public void draw(Canvas c) {
            int sc = c.save();
            try {
                drawGlass(c);
            } catch (Throwable t) {
                c.restoreToCount(sc);
                Paint f = new Paint(Paint.ANTI_ALIAS_FLAG);
                f.setColor(dark ? Color.argb(170, 20, 20, 24) : Color.argb(70, 255, 255, 255));
                RectF r = new RectF(getBounds());
                c.drawRoundRect(r, radius, radius, f);
                return;
            }
            c.restoreToCount(sc);
        }

        private void drawGlass(Canvas c) {
            Rect b = getBounds();
            if (b.isEmpty()) return;
            RectF r = new RectF(b);
            float rad = Math.min(radius, Math.min(r.width(), r.height()) / 2f);
            rr.reset();
            rr.addRoundRect(r, rad, rad, Path.Direction.CW);

            c.save();
            c.clipPath(rr);
            boolean refr = bd != null && bd.ready() && getCallback() instanceof View;
            float lum = refr ? bd.luminance : 0.4f;
            if (refr) {
                View host = (View) getCallback();
                host.getLocationOnScreen(loc);
                float sx = (float) bd.soft.getWidth() / Math.max(1, bd.screenW);
                float sy = (float) bd.soft.getHeight() / Math.max(1, bd.screenH);
                // kanta: badan dibesarkan 4%, tepi dibesarkan 14%
                drawLens(c, bd.soft, r, loc[0], loc[1], sx, sy, 1.04f, null);
                float e = Math.min(r.width(), r.height()) * 0.12f;
                Path band = new Path(rr);
                Path inner = new Path();
                RectF ir = new RectF(r);
                ir.inset(e, e);
                inner.addRoundRect(ir, Math.max(0, rad - e), Math.max(0, rad - e), Path.Direction.CW);
                band.op(inner, Path.Op.DIFFERENCE);
                c.save();
                c.clipPath(band);
                ColorMatrix cm = new ColorMatrix();
                cm.setScale(1.08f, 1.08f, 1.08f, 1f);
                drawLens(c, bd.soft, r, loc[0], loc[1], sx, sy, 1.14f, new ColorMatrixColorFilter(cm));
                c.restore();
            }
            // ketelusan dinamik: latar cerah -> tint lebih gelap, latar gelap -> lebih jernih
            int base;
            if (dark) base = Color.argb(refr ? Math.round(110 + 60 * lum) : 170, 14, 14, 18);
            else base = refr ? Color.argb(Math.round(12 + 40 * lum), lum > 0.62f ? 30 : 255, lum > 0.62f ? 30 : 255, lum > 0.62f ? 36 : 255)
                    : Color.argb(70, 255, 255, 255);
            p.setShader(null);
            p.setColorFilter(null);
            p.setColor(base);
            c.drawRect(r, p);
            if (tint != 0) {
                p.setColor((tint & 0x00FFFFFF) | 0x8C000000);
                c.drawRect(r, p);
            }
            // kilauan atas
            p.setShader(new LinearGradient(0, r.top, 0, r.top + r.height() * 0.5f,
                    Color.argb(dark ? 40 : 75, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP));
            c.drawRect(r, p);
            // pantulan pepenjuru
            p.setShader(new LinearGradient(r.left, r.top, r.right, r.bottom,
                    new int[]{0, Color.argb(dark ? 10 : 22, 255, 255, 255), 0, 0},
                    new float[]{0.15f, 0.3f, 0.45f, 1f}, Shader.TileMode.CLAMP));
            c.drawRect(r, p);
            // cahaya dalam bawah
            p.setShader(new LinearGradient(0, r.bottom - r.height() * 0.3f, 0, r.bottom,
                    Color.argb(0, 255, 255, 255), Color.argb(dark ? 18 : 40, 255, 255, 255), Shader.TileMode.CLAMP));
            c.drawRect(r, p);
            c.restore();

            // cahaya dalam di tepi (2 lapis)
            p.setShader(null);
            p.setStyle(Paint.Style.STROKE);
            for (int i = 0; i < 2; i++) {
                float in = dp * (1.6f + i * 1.8f);
                RectF ir = new RectF(r);
                ir.inset(in, in);
                p.setStrokeWidth(dp * 1.6f);
                p.setShader(new LinearGradient(r.left, r.top, r.right, r.bottom,
                        new int[]{Color.argb(dark ? 40 - i * 15 : 80 - i * 35, 255, 255, 255), 0,
                                Color.argb(dark ? 20 : 40 - i * 15, 255, 255, 255)},
                        new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
                c.drawRoundRect(ir, Math.max(0, rad - in), Math.max(0, rad - in), p);
            }
            // bingkai bercahaya
            p.setStrokeWidth(Math.max(1f, dp * 0.9f));
            p.setShader(new LinearGradient(r.left, r.top, r.right, r.bottom,
                    new int[]{Color.argb(dark ? 150 : 235, 255, 255, 255), Color.argb(dark ? 35 : 60, 255, 255, 255),
                            Color.argb(dark ? 25 : 45, 255, 255, 255), Color.argb(dark ? 110 : 170, 255, 255, 255)},
                    new float[]{0f, 0.35f, 0.65f, 1f}, Shader.TileMode.CLAMP));
            RectF rr2 = new RectF(r);
            rr2.inset(p.getStrokeWidth() / 2, p.getStrokeWidth() / 2);
            c.drawRoundRect(rr2, rad, rad, p);
            p.setStyle(Paint.Style.FILL);
            p.setShader(null);
        }

        @Override public void setAlpha(int a) { }
        @Override public void setColorFilter(ColorFilter cf) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** Lukis kawasan latar di belakang rect (koordinat skrin x,y), dibesarkan 'mag' kali dari pusat. */
    private static void drawLens(Canvas c, Bitmap img, RectF r, float screenX, float screenY,
                                 float sx, float sy, float mag, ColorFilter cf) {
        float cx = (screenX + r.width() / 2f) * sx, cy = (screenY + r.height() / 2f) * sy;
        float hw = r.width() / 2f * sx / mag, hh = r.height() / 2f * sy / mag;
        Rect src = new Rect(Math.round(cx - hw), Math.round(cy - hh), Math.round(cx + hw), Math.round(cy + hh));
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        p.setColorFilter(cf);
        c.drawBitmap(img, src, r, p);
    }

    // ======================= Glyph (jam, teks besar) =======================

    /**
     * Lukis laluan glyph sebagai kanta kaca.
     * @param bg      gambar latar (dalam koordinat kanvas: dipetakan ke bgRect), atau null = telus sebenar
     * @param tint    0 = kaca neutral, atau warna
     */
    static void glyph(Canvas c, Path path, float size, Bitmap bg, RectF bgRect, boolean dark, int tint) {
        int sc = c.save();
        try {
            glyphImpl(c, path, size, bg, bgRect, dark, tint);
        } catch (Throwable t) {
            c.restoreToCount(sc);
            Paint f = new Paint(Paint.ANTI_ALIAS_FLAG);
            f.setColor(dark ? Color.argb(150, 20, 20, 24) : Color.argb(150, 255, 255, 255));
            c.drawPath(path, f);
            return;
        }
        c.restoreToCount(sc);
    }

    private static void glyphImpl(Canvas c, Path path, float size, Bitmap bg, RectF bgRect, boolean dark, int tint) {
        RectF b = new RectF();
        path.computeBounds(b, true);
        if (b.isEmpty()) return;

        // bayang terapung di luar glyph sahaja
        c.save();
        c.clipOutPath(path);
        Paint sh = new Paint(Paint.ANTI_ALIAS_FLAG);
        sh.setColor(Color.argb(dark ? 110 : 60, 0, 0, 0));
        sh.setMaskFilter(new BlurMaskFilter(Math.max(2f, size * 0.06f), BlurMaskFilter.Blur.NORMAL));
        c.translate(0, size * 0.03f);
        c.drawPath(path, sh);
        c.restore();

        c.save();
        c.clipPath(path);
        Paint fp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        if (bg != null && bgRect != null) {
            // biasan: latar dibesarkan dari pusat glyph
            ColorMatrix cm = new ColorMatrix();
            float k = dark ? 0.72f : 1.04f, t = dark ? -8 : 22;
            cm.set(new float[]{k, 0, 0, 0, t, 0, k, 0, 0, t, 0, 0, k, 0, t, 0, 0, 0, 1, 0});
            fp.setColorFilter(new ColorMatrixColorFilter(cm));
            c.drawBitmap(bg, null, scaleAbout(bgRect, b.centerX(), b.centerY(), 1.07f), fp);
            // tepi: biasan lebih kuat dalam jalur di sepanjang garis luar glyph
            Paint st = new Paint(Paint.ANTI_ALIAS_FLAG);
            st.setStyle(Paint.Style.STROKE);
            st.setStrokeWidth(size * 0.13f);
            Path band = new Path();
            st.getFillPath(path, band);
            band.op(path, Path.Op.INTERSECT);
            c.save();
            c.clipPath(band);
            ColorMatrix cm2 = new ColorMatrix();
            float k2 = dark ? 0.8f : 1.12f, t2 = dark ? 0 : 34;
            cm2.set(new float[]{k2, 0, 0, 0, t2, 0, k2, 0, 0, t2, 0, 0, k2, 0, t2, 0, 0, 0, 1, 0});
            fp.setColorFilter(new ColorMatrixColorFilter(cm2));
            c.drawBitmap(bg, null, scaleAbout(bgRect, b.centerX(), b.centerY(), 1.2f), fp);
            c.restore();
        }
        // isi lut sinar (lebih ringan jika ada biasan)
        int tr = tint == 0 ? 255 : Color.red(tint), tg = tint == 0 ? 255 : Color.green(tint), tb = tint == 0 ? 255 : Color.blue(tint);
        Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
        if (dark) {
            body.setShader(new LinearGradient(0, b.top, 0, b.bottom,
                    Color.argb(bg != null ? 70 : 140, tr / 6, tg / 6, tb / 6 + 4), Color.argb(bg != null ? 50 : 100, tr / 6, tg / 6, tb / 6 + 4),
                    Shader.TileMode.CLAMP));
        } else {
            int a0 = tint != 0 ? 140 : (bg != null ? 42 : 60), a1 = tint != 0 ? 90 : (bg != null ? 14 : 22);
            body.setShader(new LinearGradient(0, b.top, 0, b.bottom,
                    Color.argb(a0, tr, tg, tb), Color.argb(a1, tr, tg, tb), Shader.TileMode.CLAMP));
        }
        c.drawRect(b, body);
        // kilauan atas
        Paint gloss = new Paint(Paint.ANTI_ALIAS_FLAG);
        gloss.setShader(new LinearGradient(0, b.top, 0, b.top + b.height() * 0.42f,
                Color.argb(dark ? 50 : 95, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP));
        c.drawRect(b, gloss);
        // cahaya dalam bawah
        Paint low = new Paint(Paint.ANTI_ALIAS_FLAG);
        low.setShader(new LinearGradient(0, b.bottom - b.height() * 0.22f, 0, b.bottom,
                Color.argb(0, 255, 255, 255), Color.argb(dark ? 30 : 70, 255, 255, 255), Shader.TileMode.CLAMP));
        c.drawRect(b, low);
        // cahaya dalam tepi
        Paint in = new Paint(Paint.ANTI_ALIAS_FLAG);
        in.setStyle(Paint.Style.STROKE);
        in.setStrokeWidth(Math.max(2f, size * 0.05f));
        in.setMaskFilter(new BlurMaskFilter(Math.max(1f, size * 0.018f), BlurMaskFilter.Blur.NORMAL));
        in.setShader(new LinearGradient(b.left, b.top, b.right, b.bottom,
                new int[]{Color.argb(dark ? 120 : 210, 255, 255, 255), Color.argb(0, 255, 255, 255),
                        Color.argb(0, 255, 255, 255), Color.argb(dark ? 60 : 130, 255, 255, 255)},
                new float[]{0f, 0.4f, 0.6f, 1f}, Shader.TileMode.CLAMP));
        c.drawPath(path, in);
        c.restore();

        // bingkai bercahaya nipis
        Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(Math.max(1f, size * 0.009f));
        rim.setShader(new LinearGradient(b.left, b.top, b.right, b.bottom,
                new int[]{Color.argb(dark ? 160 : 245, 255, 255, 255), Color.argb(dark ? 30 : 70, 255, 255, 255),
                        Color.argb(dark ? 20 : 45, 255, 255, 255), Color.argb(dark ? 110 : 190, 255, 255, 255)},
                new float[]{0f, 0.35f, 0.65f, 1f}, Shader.TileMode.CLAMP));
        c.drawPath(path, rim);
    }

    private static RectF scaleAbout(RectF r, float cx, float cy, float s) {
        return new RectF(cx + (r.left - cx) * s, cy + (r.top - cy) * s, cx + (r.right - cx) * s, cy + (r.bottom - cy) * s);
    }
}
