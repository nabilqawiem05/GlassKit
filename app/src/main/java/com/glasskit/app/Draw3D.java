package com.glasskit.app;

import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.EmbossMaskFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;

/**
 * Lukisan "3D timbul" tanpa bingkai: bayang lembut + emboss (cahaya dari atas kiri).
 * Hanya untuk kanvas perisian.
 */
final class Draw3D {
    private Draw3D() {}

    static final int LEFT = 0, CENTER = 1, RIGHT = 2;

    private static Paint.Align al(int a) {
        return a == LEFT ? Paint.Align.LEFT : a == RIGHT ? Paint.Align.RIGHT : Paint.Align.CENTER;
    }

    private static EmbossMaskFilter emboss(float size) {
        return new EmbossMaskFilter(new float[]{-1f, -1.2f, 1.6f}, 0.62f, 10f, Math.max(1f, size * 0.045f));
    }

    /** Teks timbul. Kembalikan lebar teks. */
    static float text(Canvas c, String s, float x, float y, float size, int weight, int color, int align) {
        return text(c, s, x, y, size, Ui.font(weight), color, align);
    }

    static float text(Canvas c, String s, float x, float y, float size, Typeface tf, int color, int align) {
        if (s == null || s.isEmpty()) return 0;
        Paint sh = new Paint(Paint.ANTI_ALIAS_FLAG);
        sh.setTypeface(tf);
        sh.setTextSize(size);
        sh.setTextAlign(al(align));
        sh.setColor(Color.argb(120, 0, 0, 0));
        sh.setMaskFilter(new BlurMaskFilter(Math.max(1.5f, size * 0.07f), BlurMaskFilter.Blur.NORMAL));
        c.drawText(s, x + size * 0.02f, y + size * 0.05f, sh);

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(tf);
        p.setTextSize(size);
        p.setTextAlign(al(align));
        p.setColor(color);
        if (size > 14) p.setMaskFilter(emboss(size));
        c.drawText(s, x, y, p);
        return p.measureText(s);
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

    /** Bentuk timbul (laluan berisi). */
    static void shape(Canvas c, Path path, float size, int color) {
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

    /** Cincin kemajuan timbul. */
    static void ring(Canvas c, float cx, float cy, float r, float thick, float frac, int color, int track) {
        RectF rf = new RectF(cx - r, cy - r, cx + r, cy + r);
        Paint sh = new Paint(Paint.ANTI_ALIAS_FLAG);
        sh.setStyle(Paint.Style.STROKE);
        sh.setStrokeWidth(thick);
        sh.setColor(Color.argb(90, 0, 0, 0));
        sh.setMaskFilter(new BlurMaskFilter(thick * 0.5f, BlurMaskFilter.Blur.NORMAL));
        c.save();
        c.translate(0, thick * 0.25f);
        c.drawArc(rf, 0, 360, false, sh);
        c.restore();

        Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
        t.setStyle(Paint.Style.STROKE);
        t.setStrokeWidth(thick);
        t.setColor(track);
        t.setMaskFilter(emboss(thick * 3));
        c.drawArc(rf, 0, 360, false, t);

        if (frac > 0) {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(thick);
            p.setColor(color);
            p.setMaskFilter(emboss(thick * 3));
            c.drawArc(rf, -90, 360f * Math.min(1f, frac), false, p);
        }
    }

    /** Ikon (kelas Icon) dilukis timbul. */
    static void icon(Context ctx, Canvas c, int kind, float cx, float cy, float size, int color) {
        android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(Math.max(1, (int) size), Math.max(1, (int) size),
                android.graphics.Bitmap.Config.ARGB_8888);
        Canvas bc = new Canvas(b);
        Icon ic = new Icon(ctx, kind, color);
        ic.layout(0, 0, (int) size, (int) size);
        ic.draw(bc);
        Paint sh = new Paint(Paint.ANTI_ALIAS_FLAG);
        sh.setMaskFilter(new BlurMaskFilter(Math.max(1.5f, size * 0.08f), BlurMaskFilter.Blur.NORMAL));
        sh.setColor(Color.argb(120, 0, 0, 0));
        int[] off = new int[2];
        android.graphics.Bitmap a = b.extractAlpha(sh, off);
        c.drawBitmap(a, cx - size / 2f + off[0] + size * 0.02f, cy - size / 2f + off[1] + size * 0.05f, sh);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        c.drawBitmap(b, cx - size / 2f, cy - size / 2f, p);
    }

    /** Ikon cuaca timbul mengikut kod WMO. */
    static void weather(Canvas c, int code, boolean day, float cx, float cy, float s) {
        int sun = 0xFFFFD60A, cloud = 0xFFF2F4F8, dark = 0xFFB8C0CC, drop = 0xFF64B5FF;
        boolean clear = code == 0 || code == 1;
        boolean part = code == 2;
        if (clear || part) {
            float scx = part ? cx - s * 0.16f : cx, scy = part ? cy - s * 0.14f : cy;
            Path body = new Path();
            if (day) {
                body.addCircle(scx, scy, s * (part ? 0.22f : 0.26f), Path.Direction.CW);
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI / 4 * i;
                    float r1 = s * (part ? 0.3f : 0.35f), r2 = s * (part ? 0.38f : 0.45f);
                    Path ray = new Path();
                    float w = s * 0.035f;
                    float x1 = scx + (float) Math.cos(a) * r1, y1 = scy + (float) Math.sin(a) * r1;
                    float x2 = scx + (float) Math.cos(a) * r2, y2 = scy + (float) Math.sin(a) * r2;
                    ray.addCircle(x1, y1, w, Path.Direction.CW);
                    ray.addCircle(x2, y2, w, Path.Direction.CW);
                    Path line = new Path();
                    line.moveTo(x1 + (float) Math.sin(a) * w, y1 - (float) Math.cos(a) * w);
                    line.lineTo(x2 + (float) Math.sin(a) * w, y2 - (float) Math.cos(a) * w);
                    line.lineTo(x2 - (float) Math.sin(a) * w, y2 + (float) Math.cos(a) * w);
                    line.lineTo(x1 - (float) Math.sin(a) * w, y1 + (float) Math.cos(a) * w);
                    line.close();
                    body.op(line, Path.Op.UNION);
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
        shape(c, cl, s, code == 3 || code == 45 || code == 48 || storm ? dark : cloud);
        if (rain) {
            Path d = new Path();
            for (int i = 0; i < 3; i++) {
                float x = cx - s * 0.16f + i * s * 0.16f, y = by + s * 0.28f;
                d.addRoundRect(new RectF(x - s * 0.025f, y, x + s * 0.025f, y + s * 0.14f), s * 0.025f, s * 0.025f, Path.Direction.CW);
            }
            shape(c, d, s * 0.6f, drop);
        }
        if (snow) {
            Path d = new Path();
            for (int i = 0; i < 3; i++) d.addCircle(cx - s * 0.16f + i * s * 0.16f, by + s * 0.32f, s * 0.04f, Path.Direction.CW);
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
