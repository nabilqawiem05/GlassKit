package com.glasskit.app;

import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/**
 * Jam tinggi gaya iPhone: digit dibina dari rangka garisan dengan ketebalan tetap,
 * jadi bila ditarik panjang, digit jadi tinggi tetapi garis tidak menebal atau herot.
 */
final class TallClock {
    private TallClock() {}

    /** @param top bahagian atas digit (kekal), digit memanjang ke bawah. */
    static Path build(String time, float cx, float top, float wd, float hd, float t) {
        hd = Math.max(hd, wd * 0.9f);
        float gap = wd * 0.14f, colonGap = wd * 0.38f;
        String digits = time.replace(":", "");
        int split = time.indexOf(':');
        float total = digits.length() * wd + (digits.length() - 1) * gap + (split > 0 ? colonGap - gap : 0);
        float x = cx - total / 2f;
        Path skel = new Path();
        for (int i = 0; i < time.length(); i++) {
            char ch = time.charAt(i);
            if (ch == ':') continue;
            digit(skel, ch, x, top, wd, hd, t);
            x += wd + (i + 1 == split ? colonGap : gap);
        }
        Paint sp = new Paint(Paint.ANTI_ALIAS_FLAG);
        sp.setStyle(Paint.Style.STROKE);
        sp.setStrokeWidth(t);
        sp.setStrokeCap(Paint.Cap.BUTT);
        sp.setStrokeJoin(Paint.Join.ROUND);
        Path fill = new Path();
        sp.getFillPath(skel, fill);
        Path clean = new Path();
        // satukan bahagian bertindih supaya tepi kaca tiada garis sambungan
        if (!clean.op(fill, new Path(), Path.Op.UNION)) return fill;
        return clean;
    }

    private static void rr(Path p, float l, float t, float r, float b, float rad) {
        rad = Math.min(rad, Math.min((r - l) / 2f, (b - t) / 2f));
        p.addRoundRect(new RectF(l, t, r, b), rad, rad, Path.Direction.CW);
    }

    private static void digit(Path p, char ch, float x, float y, float wd, float hd, float t) {
        float L = x + t / 2, R = x + wd - t / 2, T = y + t / 2, B = y + hd - t / 2;
        float cw = R - L, r = cw / 2f, H = B - T, cx = L + r;
        switch (ch) {
            case '0':
                rr(p, L, T, R, B, r);
                break;
            case '1': {
                float sx = L + cw * 0.62f;
                p.moveTo(L + cw * 0.05f, T + Math.min(H * 0.16f, r * 1.2f));
                p.lineTo(sx, T);
                p.lineTo(sx, B + t / 2);
                break;
            }
            case '2': {
                p.moveTo(L, T + r);
                p.arcTo(new RectF(L, T, R, T + 2 * r), 180, 180);
                float q = Math.max(T + r, T + H * 0.36f);
                p.lineTo(R, q);
                p.cubicTo(R, q + (B - q) * 0.45f, L, B - (B - q) * 0.35f, L, B);
                p.lineTo(R + t / 2, B);
                break;
            }
            case '3': {
                float m = Math.max(T + H * 0.47f, T + 2 * r);
                p.moveTo(L, T + r);
                p.arcTo(new RectF(L, T, R, T + 2 * r), 180, 180);
                p.lineTo(R, Math.max(T + r, m - r));
                p.arcTo(new RectF(L, m - 2 * r, R, m), 0, 90);
                p.lineTo(L + cw * 0.28f, m);
                float mb = Math.min(m, B - 2 * r);
                p.moveTo(L + cw * 0.28f, mb);
                p.lineTo(cx, mb);
                p.arcTo(new RectF(L, mb, R, mb + 2 * r), 270, 90);
                p.lineTo(R, B - r);
                p.arcTo(new RectF(L, B - 2 * r, R, B), 0, 180);
                break;
            }
            case '4': {
                float sx = L + cw * 0.8f, my = T + H * 0.70f;
                p.moveTo(sx, B + t / 2);
                p.lineTo(sx, T);
                p.lineTo(L, my);
                p.lineTo(R + t / 2, my);
                break;
            }
            case '5': {
                float m = Math.min(T + H * 0.40f, B - 2 * r);
                p.moveTo(R + t / 2, T);
                p.lineTo(L, T);
                p.lineTo(L, m);
                p.lineTo(cx, m);
                p.arcTo(new RectF(L, m, R, m + 2 * r), 270, 90);
                p.lineTo(R, B - r);
                p.arcTo(new RectF(L, B - 2 * r, R, B), 0, 180);
                break;
            }
            case '6': {
                float m = Math.min(T + H * 0.38f, B - 2 * r);
                p.moveTo(R, T + r);
                p.arcTo(new RectF(L, T, R, T + 2 * r), 0, -180);
                p.lineTo(L, B - r);
                rr(p, L, m, R, B, r);
                break;
            }
            case '7':
                p.moveTo(L - t / 2, T);
                p.lineTo(R, T);
                p.cubicTo(R, T + H * 0.2f, L + cw * 0.25f, T + H * 0.5f, L + cw * 0.25f, B + t / 2);
                break;
            case '8': {
                float m = T + H * 0.46f;
                rr(p, L + cw * 0.04f, T, R - cw * 0.04f, m, r * 0.96f);
                rr(p, L, m, R, B, r);
                break;
            }
            case '9': {
                float m = Math.max(B - H * 0.38f, T + 2 * r);
                rr(p, L, T, R, m, r);
                p.moveTo(R, T + r);
                p.lineTo(R, B - r);
                p.arcTo(new RectF(L, B - 2 * r, R, B), 0, 180);
                break;
            }
            default:
                break;
        }
    }
}
