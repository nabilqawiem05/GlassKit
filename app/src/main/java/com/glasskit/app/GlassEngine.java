package com.glasskit.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.text.TextUtils;

/**
 * Enjin bahan Liquid Glass untuk widget. Semua widget guna enjin ini supaya rupa, bucu,
 * tipografi, pantulan dan bayang sentiasa sama. Ada tema Cerah, Gelap dan Auto (ikut sistem).
 */
final class GlassEngine {
    private GlassEngine() {}

    static final int SMALL = 0, MEDIUM = 1, LARGE = 2, XL = 3;

    /** Tetapan + palet yang sedang digunakan. */
    static final class Theme {
        boolean dark;
        int primary, secondary, muted, accent, hairline, chip;
        int alpha;       // 0..100 kelegapan kaca
        int reflect;     // 0..100 kekuatan pantulan
        float radiusDp;  // jejari bucu
        int glowTint;    // warna biasan halus (cuaca, wallpaper)
    }

    static Theme cur = new Theme();

    static int appearance(Context c) { return Prefs.get(c).getInt(Prefs.W_APPEAR, 2); }

    static boolean isDark(Context c) {
        int a = appearance(c);
        if (a == 2) {
            int n = c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
            return n == Configuration.UI_MODE_NIGHT_YES;
        }
        return a == 1;
    }

    static Theme theme(Context c) {
        SharedPreferences p = Prefs.get(c);
        Theme t = new Theme();
        t.dark = isDark(c);
        t.alpha = p.getInt(Prefs.W_ALPHA, 55);
        t.reflect = p.getInt(Prefs.W_REFLECT, 60);
        t.radiusDp = p.getInt(Prefs.W_RADIUS, 24);
        int acc = p.getInt(Prefs.W_ACCENT, 0);
        if (t.dark) {
            t.primary = 0xFFFFFFFF;
            t.secondary = 0xFFB8BBC3;
            t.muted = 0xFF858994;
            t.hairline = Color.argb(40, 255, 255, 255);
            t.chip = Color.argb(28, 255, 255, 255);
        } else {
            t.primary = 0xFF14161A;
            t.secondary = 0xFF4B5059;
            t.muted = 0xFF7D838D;
            t.hairline = Color.argb(34, 20, 22, 26);
            t.chip = Color.argb(150, 255, 255, 255);
        }
        switch (acc) {
            case 1: t.accent = t.dark ? 0xFF5AA9FF : 0xFF4A90E2; break;
            case 2: t.accent = t.dark ? 0xFFBF8CFF : 0xFF8E5BE0; break;
            case 3: t.accent = t.dark ? 0xFF5CE1F0 : 0xFF0FA3B8; break;
            default:
                try {
                    t.accent = c.getColor(t.dark ? android.R.color.system_accent1_200 : android.R.color.system_accent1_600);
                } catch (Exception e) {
                    t.accent = t.dark ? 0xFF5AA9FF : 0xFF4A90E2;
                }
        }
        t.glowTint = t.accent;
        return t;
    }

    static int size(float wPx, float hPx, float d) {
        float w = wPx / d, h = hPx / d;
        if (h >= 220 && w >= 220) return LARGE;
        if (w >= 220 && w >= h * 1.55f) return MEDIUM; // sederhana hanya bila jelas lebar
        return SMALL;
    }

    static int a(int color, int alpha) { return (color & 0x00FFFFFF) | (Math.max(0, Math.min(255, alpha)) << 24); }

    static int mix(int x, int y, float f) {
        return Color.argb(
                Math.round(Color.alpha(x) + (Color.alpha(y) - Color.alpha(x)) * f),
                Math.round(Color.red(x) + (Color.red(y) - Color.red(x)) * f),
                Math.round(Color.green(x) + (Color.green(y) - Color.green(x)) * f),
                Math.round(Color.blue(x) + (Color.blue(y) - Color.blue(x)) * f));
    }

    // ======================= Bahan kaca =======================

    /** Margin yang perlu dikosongkan di sekeliling kad untuk bayang. */
    static float shadowPad(float d) { return 7 * d; }

    /**
     * Lukis permukaan kaca kristal: bayang lembut, badan lut sinar, biasan warna halus,
     * kilauan spekular, cahaya dalam dan bingkai perak bercahaya.
     */
    static void card(Canvas c, RectF r, Theme t, float d) { card(c, r, t, d, true); }

    /** Drawable kaca bersih (tanpa kabur / kanta) untuk paparan biasa seperti kad notifikasi lock screen. */
    static android.graphics.drawable.Drawable drawable(Context ctx, float radiusDp, boolean dark, int alpha) {
        final Theme t = theme(ctx);
        t.dark = dark;
        t.alpha = alpha;
        t.radiusDp = radiusDp;
        final float d = ctx.getResources().getDisplayMetrics().density;
        return new android.graphics.drawable.Drawable() {
            @Override
            public void draw(Canvas c) {
                card(c, new RectF(getBounds()), t, d, false);
            }

            @Override public void setAlpha(int a) { }
            @Override public void setColorFilter(android.graphics.ColorFilter cf) { }
            @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
        };
    }

    static void card(Canvas c, RectF r, Theme t, float d, boolean shadow) {
        float rad = Math.min(t.radiusDp * d, Math.min(r.width(), r.height()) / 2f);
        Path shape = new Path();
        shape.addRoundRect(r, rad, rad, Path.Direction.CW);
        float refl = t.reflect / 100f;
        float op = t.alpha / 100f;

        // 1) bayang terapung (dua lapis: lembut jauh + rapat)
        if (shadow) {
        Paint sh = new Paint(Paint.ANTI_ALIAS_FLAG);
        sh.setColor(Color.argb(t.dark ? 110 : 45, 0, 0, 0));
        sh.setMaskFilter(new BlurMaskFilter(6 * d, BlurMaskFilter.Blur.NORMAL));
        c.save();
        c.translate(0, 3 * d);
        c.drawPath(shape, sh);
        c.restore();
        sh.setColor(Color.argb(t.dark ? 70 : 25, 0, 0, 0));
        sh.setMaskFilter(new BlurMaskFilter(1.5f * d, BlurMaskFilter.Blur.NORMAL));
        c.save();
        c.translate(0, 0.8f * d);
        c.drawPath(shape, sh);
        c.restore();
        }

        c.save();
        c.clipPath(shape);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        // 2) badan kaca
        if (t.dark) {
            int top = a(0xFF191B20, Math.round(70 + 150 * op));
            int bot = a(0xFF050505, Math.round(90 + 150 * op));
            p.setShader(new LinearGradient(0, r.top, 0, r.bottom, top, bot, Shader.TileMode.CLAMP));
        } else {
            int top = a(0xFFFFFFFF, Math.round(60 + 150 * op));
            int bot = a(0xFFDDE1E8, Math.round(50 + 140 * op));
            p.setShader(new LinearGradient(0, r.top, 0, r.bottom, top, bot, Shader.TileMode.CLAMP));
        }
        c.drawRect(r, p);

        // 3) biasan warna halus (cahaya terbias dari sudut)
        p.setShader(new RadialGradient(r.right - r.width() * 0.1f, r.bottom, Math.max(r.width(), r.height()) * 0.9f,
                a(t.glowTint, t.dark ? 46 : 34), a(t.glowTint, 0), Shader.TileMode.CLAMP));
        c.drawRect(r, p);
        p.setShader(new RadialGradient(r.left, r.top, Math.max(r.width(), r.height()) * 0.7f,
                Color.argb(t.dark ? 22 : 60, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP));
        c.drawRect(r, p);

        // 4) kilauan spekular atas (lembut, bukan plastik)
        float hh = Math.min(r.height() * 0.5f, 90 * d);
        p.setShader(new LinearGradient(0, r.top, 0, r.top + hh,
                Color.argb(Math.round((t.dark ? 34 : 90) * refl), 255, 255, 255), Color.argb(0, 255, 255, 255),
                Shader.TileMode.CLAMP));
        c.drawRect(r, p);
        // 5) jalur pantulan pepenjuru
        p.setShader(new LinearGradient(r.left, r.top, r.left + r.width() * 0.9f, r.top + r.height() * 0.9f,
                new int[]{0, Color.argb(Math.round((t.dark ? 16 : 34) * refl), 255, 255, 255), 0, 0},
                new float[]{0.18f, 0.3f, 0.42f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(r, p);
        // 6) cahaya dalam bawah (kaustik)
        p.setShader(new LinearGradient(0, r.bottom - 26 * d, 0, r.bottom,
                Color.argb(0, 255, 255, 255), Color.argb(Math.round((t.dark ? 22 : 50) * refl), 255, 255, 255),
                Shader.TileMode.CLAMP));
        c.drawRect(r, p);
        c.restore();

        // 7) cahaya dalam di tepi
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        RectF in = new RectF(r);
        in.inset(2.2f * d, 2.2f * d);
        p.setStrokeWidth(2.4f * d);
        p.setMaskFilter(new BlurMaskFilter(1.6f * d, BlurMaskFilter.Blur.NORMAL));
        p.setShader(new LinearGradient(r.left, r.top, r.right, r.bottom,
                new int[]{Color.argb(Math.round((t.dark ? 46 : 110) * refl), 255, 255, 255), Color.argb(0, 255, 255, 255),
                        Color.argb(Math.round((t.dark ? 24 : 60) * refl), 255, 255, 255)},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        c.drawRoundRect(in, Math.max(0, rad - 2.2f * d), Math.max(0, rad - 2.2f * d), p);
        p.setMaskFilter(null);

        // 8) bingkai perak bercahaya (tidak terlalu terang)
        float sw = Math.max(1f, 0.9f * d);
        p.setStrokeWidth(sw);
        int hiA = t.dark ? 120 : 230, loA = t.dark ? 22 : 70;
        int silver = t.dark ? 0xFFDDE1E8 : 0xFFFFFFFF;
        p.setShader(new LinearGradient(r.left, r.top, r.right, r.bottom,
                new int[]{a(silver, hiA), a(silver, loA), a(silver, loA), a(silver, Math.round(hiA * 0.7f))},
                new float[]{0f, 0.35f, 0.7f, 1f}, Shader.TileMode.CLAMP));
        RectF b = new RectF(r);
        b.inset(sw / 2, sw / 2);
        c.drawRoundRect(b, rad, rad, p);
        if (!t.dark) {
            // garis luar gelap nipis supaya kaca cerah kelihatan pada wallpaper cerah
            p.setShader(null);
            p.setColor(Color.argb(26, 0, 0, 0));
            RectF o = new RectF(r);
            o.inset(-sw / 2, -sw / 2);
            c.drawRoundRect(o, rad + sw / 2, rad + sw / 2, p);
        }
        p.setStyle(Paint.Style.FILL);
    }

    /** Cip/sub-permukaan kecil di dalam kad (contoh: butiran cuaca). */
    static void chip(Canvas c, RectF r, float rad, Theme t) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(t.chip);
        c.drawRoundRect(r, rad, rad, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1f, rad * 0.06f));
        p.setColor(t.dark ? Color.argb(30, 255, 255, 255) : Color.argb(170, 255, 255, 255));
        c.drawRoundRect(r, rad, rad, p);
    }

    // ======================= Tipografi =======================

    static Typeface font(int weight) { return Ui.font(weight); }

    static Paint tp(float size, int weight, int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        p.setTypeface(font(weight));
        p.setTextSize(size);
        p.setColor(color);
        if (size > 30) p.setLetterSpacing(-0.03f);
        return p;
    }

    static final int AL = 0, AC = 1, AR = 2;

    /** Lukis teks; dipendekkan dengan "…" jika lebih lebar daripada max (0 = tiada had). Pulangkan lebar. */
    static float text(Canvas c, String s, float x, float y, float size, int weight, int color, int align, float max) {
        if (s == null) return 0;
        Paint p = tp(size, weight, color);
        if (max > 0 && p.measureText(s) > max) {
            s = TextUtils.ellipsize(s, new android.text.TextPaint(p), max, TextUtils.TruncateAt.END).toString();
        }
        p.setTextAlign(align == AL ? Paint.Align.LEFT : align == AR ? Paint.Align.RIGHT : Paint.Align.CENTER);
        c.drawText(s, x, y, p);
        return p.measureText(s);
    }

    static float text(Canvas c, String s, float x, float y, float size, int weight, int color, int align) {
        return text(c, s, x, y, size, weight, color, align, 0);
    }

    /** Saiz fon terbesar (≤ max) supaya teks muat dalam lebar. */
    static float fit(String s, int weight, float maxSize, float width) {
        Paint p = tp(maxSize, weight, 0);
        float w = p.measureText(s);
        return w <= width ? maxSize : maxSize * width / w;
    }

    // ======================= Grafik =======================

    static void ring(Canvas c, float cx, float cy, float r, float th, float frac, int color, Theme t) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(th);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setColor(a(color, t.dark ? 55 : 45));
        RectF o = new RectF(cx - r, cy - r, cx + r, cy + r);
        c.drawOval(o, p);
        frac = Math.max(0f, Math.min(1f, frac));
        if (frac > 0.002f) {
            p.setShader(new android.graphics.SweepGradient(cx, cy,
                    new int[]{mix(color, Color.WHITE, 0.15f), color, color}, new float[]{0f, 0.5f, 1f}));
            c.save();
            c.rotate(-90, cx, cy);
            c.drawArc(o, 0, 360 * frac, false, p);
            c.restore();
            // kilau kecil pada hujung
            p.setShader(null);
            p.setStyle(Paint.Style.FILL);
            double ang = Math.toRadians(-90 + 360 * frac);
            p.setColor(Color.argb(90, 255, 255, 255));
            c.drawCircle(cx + (float) Math.cos(ang) * r, cy + (float) Math.sin(ang) * r, th * 0.22f, p);
        }
    }

    static void bar(Canvas c, RectF r, float frac, int color, Theme t) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float rad = r.height() / 2f;
        p.setColor(a(t.primary, t.dark ? 40 : 28));
        c.drawRoundRect(r, rad, rad, p);
        frac = Math.max(0f, Math.min(1f, frac));
        if (frac > 0) {
            RectF f = new RectF(r.left, r.top, r.left + Math.max(r.height(), r.width() * frac), r.bottom);
            p.setColor(color);
            c.drawRoundRect(f, rad, rad, p);
        }
    }

    static void icon(Context ctx, Canvas c, int kind, float cx, float cy, float size, int color) {
        int s = Math.max(2, Math.round(size));
        Icon ic = new Icon(ctx, kind, color);
        ic.layout(0, 0, s, s);
        c.save();
        c.translate(cx - s / 2f, cy - s / 2f);
        ic.draw(c);
        c.restore();
    }

    // ======================= Ikon cuaca (berbilang warna) =======================

    static void weather(Canvas c, int code, boolean day, float cx, float cy, float s, Theme t) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        int sun = 0xFFFFC21A, moon = t.dark ? 0xFFE8EAF0 : 0xFF8E95A3;
        int cloud = t.dark ? 0xFFF2F4F8 : 0xFFFFFFFF;
        int cloudEdge = t.dark ? Color.argb(0, 0, 0, 0) : Color.argb(60, 60, 70, 90);
        int rain = 0xFF4FA8FF, bolt = 0xFFFFD60A;
        boolean clear = code == 0 || code == 1, part = code == 2;
        boolean rainy = (code >= 51 && code <= 67) || (code >= 80 && code <= 82);
        boolean snow = (code >= 71 && code <= 77) || code == 85 || code == 86;
        boolean storm = code >= 95, fog = code == 45 || code == 48;

        if (clear || part) {
            float sx = part ? cx - s * 0.14f : cx, sy = part ? cy - s * 0.12f : cy, sr = s * (part ? 0.2f : 0.25f);
            if (day) {
                p.setColor(sun);
                p.setStrokeWidth(s * 0.06f);
                p.setStrokeCap(Paint.Cap.ROUND);
                for (int i = 0; i < 8; i++) {
                    double ang = Math.PI / 4 * i;
                    float r1 = sr * 1.35f, r2 = sr * 1.75f;
                    c.drawLine(sx + (float) Math.cos(ang) * r1, sy + (float) Math.sin(ang) * r1,
                            sx + (float) Math.cos(ang) * r2, sy + (float) Math.sin(ang) * r2, p);
                }
                p.setShader(new RadialGradient(sx - sr * 0.3f, sy - sr * 0.3f, sr * 1.3f, 0xFFFFE680, sun, Shader.TileMode.CLAMP));
                c.drawCircle(sx, sy, sr, p);
                p.setShader(null);
            } else {
                Path m = new Path();
                m.addCircle(sx, sy, sr, Path.Direction.CW);
                Path cut = new Path();
                cut.addCircle(sx + sr * 0.55f, sy - sr * 0.35f, sr * 0.85f, Path.Direction.CW);
                m.op(cut, Path.Op.DIFFERENCE);
                p.setColor(moon);
                c.drawPath(m, p);
            }
            if (clear) return;
        }
        // awan
        float ccx = part ? cx + s * 0.06f : cx, ccy = (rainy || snow || storm) ? cy - s * 0.1f : cy + (part ? s * 0.06f : 0);
        Path cl = new Path();
        float w = s * 0.8f, h = s * 0.3f;
        RectF base = new RectF(ccx - w / 2, ccy, ccx + w / 2, ccy + h);
        cl.addRoundRect(base, h / 2, h / 2, Path.Direction.CW);
        cl.addCircle(ccx - w * 0.15f, ccy + h * 0.05f, h * 0.75f, Path.Direction.CW);
        cl.addCircle(ccx + w * 0.14f, ccy - h * 0.05f, h * 0.95f, Path.Direction.CW);
        Path u = new Path();
        u.op(cl, Path.Op.UNION);
        Path un = new Path();
        un.addPath(cl);
        Paint sh = new Paint(Paint.ANTI_ALIAS_FLAG);
        sh.setColor(Color.argb(t.dark ? 90 : 40, 0, 0, 0));
        sh.setMaskFilter(new BlurMaskFilter(s * 0.04f, BlurMaskFilter.Blur.NORMAL));
        c.save();
        c.translate(0, s * 0.03f);
        c.drawPath(un, sh);
        c.restore();
        int top = fog || storm || rainy ? mix(cloud, 0xFFB7BECB, 0.45f) : cloud;
        p.setShader(new LinearGradient(0, ccy - h, 0, ccy + h, top, mix(top, 0xFFC9D0DB, 0.5f), Shader.TileMode.CLAMP));
        c.drawPath(un, p);
        p.setShader(null);
        if (Color.alpha(cloudEdge) > 0) {
            Paint e = new Paint(Paint.ANTI_ALIAS_FLAG);
            e.setStyle(Paint.Style.STROKE);
            e.setStrokeWidth(Math.max(1f, s * 0.012f));
            e.setColor(cloudEdge);
            Path o = new Path();
            o.op(cl, new Path(), Path.Op.UNION);
            c.drawPath(o, e);
        }
        float dy = ccy + h + s * 0.08f;
        if (rainy) {
            p.setColor(rain);
            p.setStrokeWidth(s * 0.055f);
            p.setStrokeCap(Paint.Cap.ROUND);
            for (int i = -1; i <= 1; i++) {
                float x = ccx + i * s * 0.2f;
                c.drawLine(x, dy, x - s * 0.05f, dy + s * 0.14f, p);
            }
        } else if (snow) {
            p.setColor(t.dark ? 0xFFFFFFFF : 0xFF9FB6D6);
            for (int i = -1; i <= 1; i++) c.drawCircle(ccx + i * s * 0.2f, dy + s * 0.06f, s * 0.04f, p);
        } else if (storm) {
            Path b = new Path();
            b.moveTo(ccx + s * 0.04f, dy - s * 0.04f);
            b.lineTo(ccx - s * 0.1f, dy + s * 0.12f);
            b.lineTo(ccx, dy + s * 0.12f);
            b.lineTo(ccx - s * 0.06f, dy + s * 0.27f);
            b.lineTo(ccx + s * 0.12f, dy + s * 0.07f);
            b.lineTo(ccx + s * 0.02f, dy + s * 0.07f);
            b.close();
            p.setColor(bolt);
            c.drawPath(b, p);
        } else if (fog) {
            p.setColor(t.dark ? Color.argb(200, 255, 255, 255) : Color.argb(200, 140, 148, 160));
            p.setStrokeWidth(s * 0.045f);
            p.setStrokeCap(Paint.Cap.ROUND);
            c.drawLine(ccx - w * 0.4f, dy, ccx + w * 0.3f, dy, p);
            c.drawLine(ccx - w * 0.25f, dy + s * 0.09f, ccx + w * 0.4f, dy + s * 0.09f, p);
        }
    }

    /** Warna biasan kaca mengikut cuaca (latar "hidup" yang halus). */
    static int weatherTint(int code, boolean day) {
        if (!day) return 0xFF5B5BD6;
        if (code <= 1) return 0xFF3A9BFF;
        if (code == 2) return 0xFF6AA8E8;
        if (code >= 95) return 0xFF7A6BD0;
        if ((code >= 51 && code <= 67) || (code >= 80 && code <= 82)) return 0xFF5C86B8;
        return 0xFF8C9BB0;
    }
}
