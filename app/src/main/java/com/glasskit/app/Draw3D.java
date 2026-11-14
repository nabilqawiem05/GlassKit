package com.glasskit.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.EmbossMaskFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;

/**
 * Lukisan glyph tanpa bingkai. Tiga mod:
 *  GLASS  - kaca jernih lut sinar (wallpaper sebenar nampak di dalam huruf), tepi bercahaya, bayang
 *  DARK   - kaca gelap berasap
 *  SOLID  - putih timbul (emboss)
 * Hanya untuk kanvas perisian.
 */
final class Draw3D {
    private Draw3D() {}

    static final int LEFT = 0, CENTER = 1, RIGHT = 2;
    static final int GLASS = 0, DARK = 1, SOLID = 2;

    /** Mod semasa (ditetapkan sebelum melukis). */
    static int mode = GLASS;

    static void useWidgetTheme(Context c) {
        mode = Prefs.get(c).getInt(Prefs.WIDGET_THEME, GLASS);
    }

    private static Paint.Align al(int a) {
        return a == LEFT ? Paint.Align.LEFT : a == RIGHT ? Paint.Align.RIGHT : Paint.Align.CENTER;
    }

    private static EmbossMaskFilter emboss(float size) {
        return new EmbossMaskFilter(new float[]{-1f, -1.2f, 1.6f}, 0.62f, 10f, Math.max(1f, size * 0.045f));
    }

    // =================== Teras: laluan kaca ===================

    /**
     * Lukis laluan sebagai kaca. tint = 0 untuk kaca neutral, atau warna untuk kaca berwarna.
     */
    static void glass(Canvas c, Path path, float size, int tint) {
        if (mode == SOLID) {
            solid(c, path, size, tint == 0 ? Color.WHITE : tint);
            return;
        }
        boolean dark = mode == DARK;
        RectF b = new RectF();
        path.computeBounds(b, true);
        if (b.isEmpty()) return;
        float edge = Math.max(1.5f, size * 0.07f);

        // 1) bayang lembut di luar huruf sahaja
        c.save();
        c.clipOutPath(path);
        Paint sh = new Paint(Paint.ANTI_ALIAS_FLAG);
        sh.setColor(Color.argb(dark ? 120 : 70, 0, 0, 0));
        sh.setMaskFilter(new BlurMaskFilter(Math.max(2f, size * 0.07f), BlurMaskFilter.Blur.NORMAL));
        c.translate(0, size * 0.04f);
        c.drawPath(path, sh);
        c.restore();

        c.save();
        c.clipPath(path);
        // 2) isi lut sinar (wallpaper sebenar nampak di belakang)
        int tr = tint == 0 ? 255 : Color.red(tint), tg = tint == 0 ? 255 : Color.green(tint), tb = tint == 0 ? 255 : Color.blue(tint);
        Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
        if (dark) {
            int dr = tint == 0 ? 18 : tr / 3, dg = tint == 0 ? 18 : tg / 3, db = tint == 0 ? 24 : tb / 3;
            body.setShader(new LinearGradient(0, b.top, 0, b.bottom,
                    Color.argb(150, dr, dg, db), Color.argb(105, dr, dg, db), Shader.TileMode.CLAMP));
        } else {
            int a0 = tint == 0 ? 70 : 150, a1 = tint == 0 ? 26 : 95;
            body.setShader(new LinearGradient(0, b.top, 0, b.bottom,
                    Color.argb(a0, tr, tg, tb), Color.argb(a1, tr, tg, tb), Shader.TileMode.CLAMP));
        }
        c.drawRect(b, body);

        // 3) cahaya dalam di tepi (bevel): terang atas-kiri, lembut bawah-kanan
        Paint in = new Paint(Paint.ANTI_ALIAS_FLAG);
        in.setStyle(Paint.Style.STROKE);
        in.setStrokeWidth(edge * 2f);
        in.setMaskFilter(new BlurMaskFilter(edge * 0.7f, BlurMaskFilter.Blur.NORMAL));
        in.setShader(new LinearGradient(b.left, b.top, b.right, b.bottom,
                new int[]{Color.argb(dark ? 140 : 235, 255, 255, 255), Color.argb(dark ? 20 : 50, 255, 255, 255),
                        Color.argb(0, 255, 255, 255), Color.argb(dark ? 60 : 140, 255, 255, 255)},
                new float[]{0f, 0.38f, 0.62f, 1f}, Shader.TileMode.CLAMP));
        c.drawPath(path, in);

        // 4) bayang dalam halus di bawah supaya nampak tebal
        Paint low = new Paint(Paint.ANTI_ALIAS_FLAG);
        low.setShader(new LinearGradient(0, b.top + b.height() * 0.55f, 0, b.bottom,
                Color.argb(0, 0, 0, 0), Color.argb(dark ? 70 : 35, 0, 0, 0), Shader.TileMode.CLAMP));
        c.drawRect(b, low);
        c.restore();

        // 5) bingkai cahaya nipis (specular rim)
        Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(Math.max(1.1f, size * 0.014f));
        rim.setShader(new LinearGradient(b.left, b.top, b.right, b.bottom,
                new int[]{Color.argb(dark ? 170 : 250, 255, 255, 255), Color.argb(dark ? 40 : 80, 255, 255, 255),
                        Color.argb(dark ? 30 : 50, 255, 255, 255), Color.argb(dark ? 120 : 200, 255, 255, 255)},
                new float[]{0f, 0.35f, 0.65f, 1f}, Shader.TileMode.CLAMP));
        c.drawPath(path, rim);
    }

    private static void solid(Canvas c, Path path, float size, int color) {
        Paint sh = new Paint(Paint.ANTI_ALIAS_FLAG);
        sh.setColor(Color.argb(115, 0, 0, 0));
        sh.setMaskFilter(new BlurMaskFilter(Math.max(1.5f, size * 0.07f), BlurMaskFilter.Blur.NORMAL));
        c.save();
        c.translate(size * 0.02f, size * 0.05f);
        c.drawPath(path, sh);
        c.restore();
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        p.setMaskFilter(emboss(size));
        c.drawPath(path, p);
    }

    // =================== Teks ===================

    static float text(Canvas c, String s, float x, float y, float size, int weight, int color, int align) {
        return text(c, s, x, y, size, Ui.font(weight), color, align);
    }

    /**
     * Teks: besar = kaca; kecil = label putih jelas dengan bayang (supaya mudah dibaca).
     * color putih bermaksud kaca neutral; warna lain jadi tint.
     */
    static float text(Canvas c, String s, float x, float y, float size, Typeface tf, int color, int align) {
        if (s == null || s.isEmpty()) return 0;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(tf);
        p.setTextSize(size);
        p.setTextAlign(al(align));
        float w = p.measureText(s);
        float density = android.content.res.Resources.getSystem().getDisplayMetrics().density;
        if (size >= 24 * density) {
            Path path = new Path();
            p.getTextPath(s, 0, s.length(), x, y, path);
            int tint = (color & 0x00FFFFFF) == 0x00FFFFFF ? 0 : (color | 0xFF000000);
            glass(c, path, size, tint);
        } else {
            p.setColor(mode == DARK && (color & 0x00FFFFFF) == 0x00FFFFFF ? Color.argb(235, 255, 255, 255) : color);
            p.setShadowLayer(Math.max(2f, size * 0.22f), 0, size * 0.06f, Color.argb(150, 0, 0, 0));
            c.drawText(s, x, y, p);
        }
        return w;
    }

    static float measure(String s, float size, int weight) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(Ui.font(weight));
        p.setTextSize(size);
        return p.measureText(s);
    }

    static String fit(String s, float size, int weight, float max) {
        if (s == null) return "";
        if (measure(s, size, weight) <= max) return s;
        while (s.length() > 1 && measure(s + "…", size, weight) > max) s = s.substring(0, s.length() - 1);
        return s + "…";
    }

    // =================== Bentuk ===================

    /** Bentuk kaca. Warna putih = kaca neutral; warna lain = kaca berwarna. */
    static void shape(Canvas c, Path path, float size, int color) {
        int tint = (color & 0x00FFFFFF) == 0x00FFFFFF ? 0 : (color | 0xFF000000);
        glass(c, path, size, tint);
    }

    /** Cincin kemajuan kaca. */
    static void ring(Canvas c, float cx, float cy, float r, float thick, float frac, int color, int track) {
        Paint st = new Paint(Paint.ANTI_ALIAS_FLAG);
        st.setStyle(Paint.Style.STROKE);
        st.setStrokeWidth(thick);
        st.setStrokeCap(Paint.Cap.ROUND);
        RectF rf = new RectF(cx - r, cy - r, cx + r, cy + r);

        if (Color.alpha(track) > 0) {
            Path arc = new Path();
            arc.addCircle(cx, cy, r, Path.Direction.CW);
            Path tp = new Path();
            st.getFillPath(arc, tp);
            int saved = mode;
            glass(c, tp, thick * 2.2f, 0);
            mode = saved;
        }
        if (frac > 0) {
            Path arc = new Path();
            arc.addArc(rf, -90, 360f * Math.min(1f, frac));
            Path fp = new Path();
            st.getFillPath(arc, fp);
            int tint = (color & 0x00FFFFFF) == 0x00FFFFFF ? 0 : (color | 0xFF000000);
            if (tint == 0) {
                // kemajuan putih: kaca lebih padat
                int saved = mode;
                glass(c, fp, thick * 2.2f, 0xFFF4F6FA);
                mode = saved;
            } else {
                glass(c, fp, thick * 2.2f, tint);
            }
        }
    }

    /** Ikon (kelas Icon) sebagai kaca, guna topeng alfa. */
    static void icon(Context ctx, Canvas c, int kind, float cx, float cy, float size, int color) {
        int s = Math.max(2, Math.round(size));
        Bitmap mask = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas mc = new Canvas(mask);
        Icon ic = new Icon(ctx, kind, Color.WHITE);
        ic.layout(0, 0, s, s);
        ic.draw(mc);
        float x = cx - s / 2f, y = cy - s / 2f;

        if (mode == SOLID) {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            Paint sh = new Paint(Paint.ANTI_ALIAS_FLAG);
            sh.setMaskFilter(new BlurMaskFilter(Math.max(1.5f, size * 0.08f), BlurMaskFilter.Blur.NORMAL));
            sh.setColor(Color.argb(120, 0, 0, 0));
            int[] off = new int[2];
            Bitmap a = mask.extractAlpha(sh, off);
            c.drawBitmap(a, x + off[0], y + off[1] + size * 0.05f, sh);
            p.setColorFilter(new android.graphics.PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
            c.drawBitmap(mask, x, y, p);
            return;
        }
        boolean dark = mode == DARK;
        int tint = (color & 0x00FFFFFF) == 0x00FFFFFF ? 0 : (color | 0xFF000000);
        // bayang
        Paint sh = new Paint(Paint.ANTI_ALIAS_FLAG);
        sh.setMaskFilter(new BlurMaskFilter(Math.max(1.5f, size * 0.08f), BlurMaskFilter.Blur.NORMAL));
        sh.setColor(Color.argb(dark ? 120 : 80, 0, 0, 0));
        int[] off = new int[2];
        Bitmap a = mask.extractAlpha(sh, off);
        c.drawBitmap(a, x + off[0], y + off[1] + size * 0.05f, sh);
        // badan kaca + tepi dalam, dipotong ikut topeng
        Bitmap g = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas gc = new Canvas(g);
        int tr = tint == 0 ? 255 : Color.red(tint), tg = tint == 0 ? 255 : Color.green(tint), tb = tint == 0 ? 255 : Color.blue(tint);
        Paint body = new Paint();
        body.setShader(new LinearGradient(0, 0, 0, s,
                dark ? Color.argb(160, 20, 20, 26) : Color.argb(tint == 0 ? 120 : 190, tr, tg, tb),
                dark ? Color.argb(110, 20, 20, 26) : Color.argb(tint == 0 ? 60 : 130, tr, tg, tb), Shader.TileMode.CLAMP));
        gc.drawRect(0, 0, s, s, body);
        Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        glow.setShader(new LinearGradient(0, 0, s, s,
                Color.argb(dark ? 120 : 220, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP));
        gc.drawRect(0, 0, s * 0.6f, s * 0.6f, glow);
        Paint cut = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        cut.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        gc.drawBitmap(mask, 0, 0, cut);
        c.drawBitmap(g, x, y, new Paint(Paint.FILTER_BITMAP_FLAG));
    }

    // =================== Ikon cuaca ===================

    static void weather(Canvas c, int code, boolean day, float cx, float cy, float s) {
        int sun = 0xFFFFD60A, cloud = Color.WHITE, grey = 0xFFB8C0CC, drop = 0xFF64B5FF;
        boolean clear = code == 0 || code == 1;
        boolean part = code == 2;
        if (clear || part) {
            float scx = part ? cx - s * 0.16f : cx, scy = part ? cy - s * 0.14f : cy;
            if (day) {
                Path body = new Path();
                body.addCircle(scx, scy, s * (part ? 0.2f : 0.24f), Path.Direction.CW);
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI / 4 * i;
                    float r1 = s * (part ? 0.28f : 0.33f), r2 = s * (part ? 0.37f : 0.44f), w = s * 0.032f;
                    Path ray = new Path();
                    ray.addRoundRect(new RectF(-w, r1, w, r2), w, w, Path.Direction.CW);
                    android.graphics.Matrix m = new android.graphics.Matrix();
                    m.setRotate((float) Math.toDegrees(a));
                    m.postTranslate(scx, scy);
                    ray.transform(m);
                    body.op(ray, Path.Op.UNION);
                }
                shape(c, body, s, sun);
            } else {
                Path a = new Path();
                a.addCircle(scx, scy, s * 0.26f, Path.Direction.CW);
                Path b = new Path();
                b.addCircle(scx + s * 0.13f, scy - s * 0.1f, s * 0.22f, Path.Direction.CW);
                a.op(b, Path.Op.DIFFERENCE);
                shape(c, a, s, 0xFFFFF3B0);
            }
            if (clear) return;
        }
        boolean storm = code >= 95;
        boolean rain = (code >= 51 && code <= 67) || (code >= 80 && code <= 82) || storm;
        boolean snow = (code >= 71 && code <= 77) || code == 85 || code == 86;
        Path cl = new Path();
        float by = cy + (rain || snow ? -s * 0.06f : s * 0.06f);
        cl.addCircle(cx - s * 0.17f, by, s * 0.17f, Path.Direction.CW);
        cl.addCircle(cx + s * 0.04f, by - s * 0.1f, s * 0.22f, Path.Direction.CW);
        cl.addCircle(cx + s * 0.22f, by + s * 0.02f, s * 0.15f, Path.Direction.CW);
        Path base = new Path();
        base.addRoundRect(new RectF(cx - s * 0.34f, by, cx + s * 0.37f, by + s * 0.17f), s * 0.085f, s * 0.085f, Path.Direction.CW);
        cl.op(base, Path.Op.UNION);
        shape(c, cl, s, code == 3 || code == 45 || code == 48 || storm ? grey : cloud);
        if (rain) {
            Path d = new Path();
            for (int i = 0; i < 3; i++) {
                float x = cx - s * 0.16f + i * s * 0.16f, y = by + s * 0.28f;
                d.addRoundRect(new RectF(x - s * 0.03f, y, x + s * 0.03f, y + s * 0.15f), s * 0.03f, s * 0.03f, Path.Direction.CW);
            }
            shape(c, d, s * 0.6f, drop);
        }
        if (snow) {
            Path d = new Path();
            for (int i = 0; i < 3; i++) d.addCircle(cx - s * 0.16f + i * s * 0.16f, by + s * 0.32f, s * 0.045f, Path.Direction.CW);
            shape(c, d, s * 0.6f, Color.WHITE);
        }
        if (storm) {
            Path b = new Path();
            b.moveTo(cx + s * 0.02f, by + s * 0.2f);
            b.lineTo(cx - s * 0.1f, by + s * 0.38f);
            b.lineTo(cx, by + s * 0.38f);
            b.lineTo(cx - s * 0.04f, by + s * 0.52f);
            b.lineTo(cx + s * 0.12f, by + s * 0.32f);
            b.lineTo(cx + s * 0.02f, by + s * 0.32f);
            b.close();
            shape(c, b, s * 0.6f, 0xFFFFD60A);
        }
    }
}
