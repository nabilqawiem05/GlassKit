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
        float gap = wd * 0.16f, colonGap = wd * 0.42f;
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
        sp.setStrokeJoin(Paint.Join.MITER);
        Path fill = new Path();
        sp.getFillPath(skel, fill);
        Path clean = new Path();
        // satukan bahagian bertindih supaya tepi kaca tiada garis sambungan
        if (!clean.op(fill, new Path(), Path.Op.UNION)) return fill;
        return clean;
    }

    private static void digit(Path p, char ch, float x, float y, float wd, float hd, float t) {
        float L = x + t / 2, R = x + wd - t / 2, T = y + t / 2, B = y + hd - t / 2;
        float cw = R - L, r = cw / 2f, H = B - T;
        switch (ch) {
            case '0':
                p.addRoundRect(new RectF(L, T, R, B), r, r, Path.Direction.CW);
                break;
            case '1': {
                float sx = L + cw * 0.62f;
                p.moveTo(L, T + Math.min(H * 0.18f, r * 1.3f));
                p.lineTo(sx, T);
                p.lineTo(sx, B + t / 2);
                break;
            }
            case '2': {
                p.moveTo(L, T + r);
                p.arcTo(new RectF(L, T, R, T + 2 * r), 180, 180);
                p.lineTo(R, T + r + Math.max(0, H * 0.12f));
                p.lineTo(L, B);
                p.lineTo(R + t / 2, B);
                break;
            }
            case '3': {
                float m = T + H * 0.44f;
                p.moveTo(L - t / 2, T);
                p.lineTo(R, T);
                p.lineTo(L + cw * 0.3f, m);
                p.moveTo(L + cw * 0.3f, m);
                p.lineTo(R - r, m);
                p.arcTo(new RectF(L, m, R, m + 2 * r), 270, 90);
                p.lineTo(R, Math.max(m + r, B - r));
                p.arcTo(new RectF(L, B - 2 * r, R, B), 0, 180);
                p.lineTo(L, B - r * 1.25f);
                break;
            }
            case '4': {
                float sx = L + cw * 0.78f, my = T + H * 0.68f;
                p.moveTo(sx, B + t / 2);
                p.lineTo(sx, T);
                p.lineTo(L, my);
                p.lineTo(R + t / 2, my);
                break;
            }
            case '5': {
                float m = T + H * 0.40f;
                p.moveTo(R + t / 2, T);
                p.lineTo(L, T);
                p.lineTo(L, m);
                p.lineTo(L + r, m);
                p.arcTo(new RectF(L, m, R, m + 2 * r), 270, 90);
                p.lineTo(R, Math.max(m + r, B - r));
                p.arcTo(new RectF(L, B - 2 * r, R, B), 0, 180);
                p.lineTo(L, B - r * 1.25f);
                break;
            }
            case '6': {
                float m = T + H * 0.36f;
                p.moveTo(R, T + r);
                p.arcTo(new RectF(L, T, R, T + 2 * r), 0, -180);
                p.lineTo(L, B - r);
                p.addRoundRect(new RectF(L, Math.min(m, B - 2 * r), R, B), r, r, Path.Direction.CW);
                break;
            }
            case '7':
                p.moveTo(L - t / 2, T);
                p.lineTo(R, T);
                p.lineTo(L + cw * 0.28f, B + t / 2);
                break;
            case '8': {
                float m = T + H * 0.46f;
                p.addRoundRect(new RectF(L + cw * 0.04f, T, R - cw * 0.04f, m), r, r, Path.Direction.CW);
                p.addRoundRect(new RectF(L, m, R, B), r, r, Path.Direction.CW);
                break;
            }
            case '9': {
                float m = B - H * 0.36f;
                p.addRoundRect(new RectF(L, T, R, Math.max(m, T + 2 * r)), r, r, Path.Direction.CW);
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
