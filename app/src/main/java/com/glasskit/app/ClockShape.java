package com.glasskit.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;

/**
 * Jam yang ikut bentuk wallpaper. Wallpaper dianalisis sekali (grid kecil):
 * kontur subjek, garis ufuk (laluan paling kontras, dicari dengan DP supaya licin),
 * kawasan langit dan kedudukan kepala. Setiap gaya bina laluan jam dari data ini.
 */
final class ClockShape {
    private ClockShape() {}

    static final int NONE = 0, CONTOUR = 1, LINE = 2, SKY = 3, HALO = 4, REFLECT = 5, SAND = 6;

    static final class Scene {
        int gw, gh;
        float cx, cy;          // saiz sel grid dalam piksel skrin
        float[] top;           // y skrin bahagian atas subjek bagi setiap lajur, -1 = tiada
        float[] horizon;       // y skrin garis ufuk bagi setiap lajur
        float[] rawHz;         // ufuk mentah (sebelum dilicinkan)
        boolean[] valid;       // lajur yang ufuknya jelas & tidak dilindungi subjek
        float horizonMid;
        boolean horizonOk;
        Bitmap skyMask;        // ALPHA_8 gw x gh, 255 = langit
        boolean head;
        float headX, headTop, headR;

        float hzAt(float x) {
            int i = Math.max(0, Math.min(gw - 1, (int) (x / cx)));
            return horizon[i];
        }

        /** Titik subjek paling tinggi dalam julat x, atau -1. */
        float topIn(float x0, float x1) {
            int a = Math.max(0, (int) (x0 / cx)), b = Math.min(gw - 1, (int) (x1 / cx));
            float best = -1;
            for (int i = a; i <= b; i++) {
                if (top[i] >= 0 && (best < 0 || top[i] < best)) best = top[i];
            }
            return best;
        }
    }

    private static volatile Bitmap kBg, kFg;
    private static volatile int kW, kH;
    private static volatile Scene last;
    private static final Object L = new Object();

    static Scene of(Context ctx, int w, int h) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            // thread UI: jangan sekali-kali muat fail / analisis (elak ANR); guna hasil terakhir sahaja
            Scene s = last;
            return s != null && kW == w && kH == h ? s : null;
        }
        synchronized (L) {
        try {
            Bitmap bg = DepthRenderer.load(DepthRenderer.bgFile(ctx));
            Bitmap fg = DepthRenderer.load(DepthRenderer.fgFile(ctx));
            if (bg == null || w <= 0 || h <= 0) return null;
            if (last != null && kBg == bg && kFg == fg && kW == w && kH == h) return last;
            Scene s = analyse(bg, fg, w, h);
            kBg = bg;
            kFg = fg;
            kW = w;
            kH = h;
            last = s;
            return s;
        } catch (Throwable t) {
            return null;
        }
        }
    }

    private static Scene analyse(Bitmap bg, Bitmap fg, int w, int h) {
        Scene s = new Scene();
        int gw = 96, gh = Math.max(24, Math.round(gw * (float) h / w));
        s.gw = gw;
        s.gh = gh;
        s.cx = (float) w / gw;
        s.cy = (float) h / gh;
        Paint fp = new Paint(Paint.FILTER_BITMAP_FLAG);

        Bitmap gb = Bitmap.createBitmap(gw, gh, Bitmap.Config.ARGB_8888);
        Canvas gc = new Canvas(gb);
        gc.scale((float) gw / w, (float) gh / h);
        gc.drawBitmap(bg, null, DepthRenderer.centerCrop(bg, w, h), fp);
        int[] px = new int[gw * gh];
        gb.getPixels(px, 0, gw, 0, 0, gw, gh);

        int[] al = new int[gw * gh];
        if (fg != null) {
            Bitmap ab = Bitmap.createBitmap(gw, gh, Bitmap.Config.ARGB_8888);
            Canvas ac = new Canvas(ab);
            ac.scale((float) gw / w, (float) gh / h);
            ac.drawBitmap(fg, null, DepthRenderer.centerCrop(fg, w, h), fp);
            int[] ap = new int[gw * gh];
            ab.getPixels(ap, 0, gw, 0, 0, gw, gh);
            for (int i = 0; i < ap.length; i++) al[i] = ap[i] >>> 24;
            ab.recycle();
        }
        gb.recycle();

        // ---- kontur subjek ----
        s.top = new float[gw];
        for (int x = 0; x < gw; x++) {
            s.top[x] = -1;
            for (int y = 0; y < gh; y++) {
                if (al[y * gw + x] > 128) {
                    s.top[x] = (y + 0.5f) * s.cy;
                    break;
                }
            }
        }

        // ---- garis ufuk: skor kontras menegak, laluan licin melalui DP ----
        int y0 = Math.round(gh * 0.12f), y1 = Math.round(gh * 0.86f);
        int rows = y1 - y0;
        float[][] score = new float[gw][rows];
        boolean[] blocked = new boolean[gw];
        for (int x = 0; x < gw; x++) {
            int subj = 0;
            for (int k = 0; k < rows; k++) {
                int y = y0 + k;
                if (y < 3 || y + 3 > gh) continue;
                boolean hit = false;
                float ar = 0, ag = 0, ab2 = 0, br = 0, bgc = 0, bb = 0;
                for (int d = 1; d <= 3; d++) {
                    int pa = px[(y - d) * gw + x], pb = px[(y + d - 1) * gw + x];
                    if (al[(y - d) * gw + x] > 60 || al[(y + d - 1) * gw + x] > 60) hit = true;
                    ar += (pa >> 16) & 255; ag += (pa >> 8) & 255; ab2 += pa & 255;
                    br += (pb >> 16) & 255; bgc += (pb >> 8) & 255; bb += pb & 255;
                }
                if (hit) { subj++; continue; }
                score[x][k] = (Math.abs(ar - br) + Math.abs(ag - bgc) + Math.abs(ab2 - bb)) / 3f;
            }
            blocked[x] = subj > rows * 0.7f;
        }
        float lam = 9f;
        float[][] cost = new float[gw][rows];
        int[][] from = new int[gw][rows];
        for (int k = 0; k < rows; k++) cost[0][k] = -score[0][k];
        for (int x = 1; x < gw; x++) {
            for (int k = 0; k < rows; k++) {
                float best = Float.MAX_VALUE;
                int bk = k;
                for (int d = -3; d <= 3; d++) {
                    int j = k + d;
                    if (j < 0 || j >= rows) continue;
                    float v = cost[x - 1][j] + lam * Math.abs(d);
                    if (v < best) { best = v; bk = j; }
                }
                cost[x][k] = best - score[x][k];
                from[x][k] = bk;
            }
        }
        int end = 0;
        for (int k = 1; k < rows; k++) if (cost[gw - 1][k] < cost[gw - 1][end]) end = k;
        int[] path = new int[gw];
        path[gw - 1] = end;
        for (int x = gw - 1; x > 0; x--) path[x - 1] = from[x][path[x]];
        float sum = 0;
        int n = 0;
        for (int x = 0; x < gw; x++) {
            if (blocked[x]) continue;
            sum += score[x][path[x]];
            n++;
        }
        float conf = n > 0 ? sum / n : 0;
        s.valid = new boolean[gw];
        s.rawHz = new float[gw];
        for (int x = 0; x < gw; x++) {
            s.rawHz[x] = (y0 + path[x]) * s.cy;
            s.valid[x] = !blocked[x] && score[x][path[x]] > 22f;
        }
        s.horizonOk = n >= gw * 0.3f && conf > 30f;
        float[] hz = new float[gw];
        for (int x = 0; x < gw; x++) hz[x] = (y0 + path[x]) * s.cy;
        hz = smooth(smooth(hz, 3), 3);
        if (!s.horizonOk) java.util.Arrays.fill(hz, h * 0.45f);
        s.horizon = hz;
        float[] sorted = hz.clone();
        java.util.Arrays.sort(sorted);
        s.horizonMid = sorted[gw / 2];

        // ---- topeng langit: atas garis ufuk & bukan subjek ----
        if (s.horizonOk) {
            int[] mp = new int[gw * gh];
            for (int x = 0; x < gw; x++) {
                float hy = hz[x] / s.cy;
                for (int y = 0; y < gh; y++) {
                    float a = Math.max(0f, Math.min(1f, hy - y + 0.5f)); // tepi ufuk lembut
                    mp[y * gw + x] = Math.round(a * 255) << 24;
                }
            }
            Bitmap argb = Bitmap.createBitmap(mp, gw, gh, Bitmap.Config.ARGB_8888);
            s.skyMask = argb.extractAlpha();
            argb.recycle();
        }

        // ---- kepala: baris subjek paling atas ----
        int hy0 = -1;
        for (int y = 0; y < gh && hy0 < 0; y++) {
            int hits = 0;
            for (int x = 0; x < gw; x++) if (al[y * gw + x] > 140) hits++;
            if (hits >= 2) hy0 = y;
        }
        if (hy0 >= 0) {
            int k = Math.max(2, Math.round(gh * 0.05f));
            float sx = 0;
            int cnt = 0, l = gw, r = -1;
            for (int y = hy0; y < Math.min(gh, hy0 + k); y++) {
                for (int x = 0; x < gw; x++) {
                    if (al[y * gw + x] > 140) {
                        sx += x;
                        cnt++;
                        if (y == Math.min(gh, hy0 + k) - 1) { l = Math.min(l, x); r = Math.max(r, x); }
                    }
                }
            }
            if (cnt > 0) {
                s.headX = (sx / cnt + 0.5f) * s.cx;
                s.headTop = hy0 * s.cy;
                float half = r >= l ? (r - l + 1) * s.cx / 2f : w * 0.1f;
                s.headR = Math.max(w * 0.08f, Math.min(w * 0.22f, half));
                s.head = s.headTop > h * 0.12f;
            }
        }
        return s;
    }

    private static float[] smooth(float[] a, int r) {
        float[] o = new float[a.length];
        for (int i = 0; i < a.length; i++) {
            float t = 0;
            int c = 0;
            for (int j = Math.max(0, i - r); j <= Math.min(a.length - 1, i + r); j++) { t += a[j]; c++; }
            o[i] = t / c;
        }
        return o;
    }

    /** Padanan kuadratik teguh y = c0 + c1·u + c2·4u² (u = (x - w/2)/w), buang titik terpencil. */
    private static double[] robustQuad(Scene s, int w) {
        boolean[] use = s.valid.clone();
        double[] c = null;
        for (int it = 0; it < 4; it++) {
            double[][] A = new double[3][3];
            double[] B = new double[3];
            int cnt = 0;
            for (int i = 0; i < s.gw; i++) {
                if (!use[i]) continue;
                double u = ((i + 0.5) * s.cx - w / 2.0) / w;
                double[] f = {1, u, 4 * u * u};
                for (int a = 0; a < 3; a++) {
                    for (int b = 0; b < 3; b++) A[a][b] += f[a] * f[b];
                    B[a] += f[a] * s.rawHz[i];
                }
                cnt++;
            }
            if (cnt < s.gw * 0.2) return c;
            double[] sol = solve3(A, B);
            if (sol == null) return c;
            c = sol;
            // buang titik dengan baki besar (contoh: dahan, orang, awan)
            double[] res = new double[s.gw];
            java.util.ArrayList<Double> rs = new java.util.ArrayList<>();
            for (int i = 0; i < s.gw; i++) {
                double u = ((i + 0.5) * s.cx - w / 2.0) / w;
                res[i] = Math.abs(s.rawHz[i] - (c[0] + c[1] * u + c[2] * 4 * u * u));
                if (use[i]) rs.add(res[i]);
            }
            java.util.Collections.sort(rs);
            double med = rs.get(rs.size() / 2);
            double lim = Math.max(s.cy * 1.5, med * 2.0);
            for (int i = 0; i < s.gw; i++) if (use[i] && res[i] > lim) use[i] = false;
        }
        return c;
    }

    private static double[] solve3(double[][] A, double[] B) {
        double[][] m = new double[3][4];
        for (int i = 0; i < 3; i++) { System.arraycopy(A[i], 0, m[i], 0, 3); m[i][3] = B[i]; }
        for (int col = 0; col < 3; col++) {
            int piv = col;
            for (int r = col + 1; r < 3; r++) if (Math.abs(m[r][col]) > Math.abs(m[piv][col])) piv = r;
            if (Math.abs(m[piv][col]) < 1e-9) return null;
            double[] t = m[col]; m[col] = m[piv]; m[piv] = t;
            for (int r = 0; r < 3; r++) {
                if (r == col) continue;
                double k = m[r][col] / m[col][col];
                for (int q = col; q < 4; q++) m[r][q] -= k * m[col][q];
            }
        }
        return new double[]{m[0][3] / m[0][0], m[1][3] / m[1][1], m[2][3] / m[2][2]};
    }

    private static Path glyph(Paint tp, String ch, float sx, float sy) {
        Path g = new Path();
        tp.getTextPath(ch, 0, ch.length(), 0, 0, g); // Align.CENTER: tengah di x=0, garis dasar y=0
        Matrix m = new Matrix();
        m.setScale(sx, sy);
        g.transform(m);
        return g;
    }

    /** Bina laluan jam bagi gaya bentuk. Pulang null jika wallpaper tidak sesuai (guna susunan biasa). */
    static Path build(int mode, Scene s, Paint tp, String time, float size, float y,
                      float sx, float sy, int w, int h) {
        if (s == null || mode == NONE) return null;
        int n = time.length();
        float[] wd = new float[n];
        tp.getTextWidths(time, wd);
        float total = 0;
        for (float v : wd) total += v;
        float capH = size * 0.72f * sy;
        Path out = new Path();
        switch (mode) {
            case CONTOUR: {
                // A · setiap digit duduk di atas kontur subjek; bahagian bawahnya tersorok di belakang
                float x = w / 2f - total * sx / 2f;
                float[] base = new float[n];
                float lowest = -1;
                for (int i = 0; i < n; i++) {
                    float x0 = x, x1 = x + wd[i] * sx;
                    float t = s.topIn(x0 + wd[i] * sx * 0.15f, x1 - wd[i] * sx * 0.15f);
                    if (t > 0) {
                        base[i] = Math.max(h * 0.12f + capH, Math.min(h * 0.64f, t + capH * 0.40f));
                        lowest = Math.max(lowest, base[i]);
                    } else base[i] = -1;
                    x = x1;
                }
                if (lowest < 0) return null;
                x = w / 2f - total * sx / 2f;
                for (int i = 0; i < n; i++) {
                    Path g = glyph(tp, time.substring(i, i + 1), sx, sy);
                    g.offset(x + wd[i] * sx / 2f, base[i] < 0 ? lowest : base[i]);
                    out.addPath(g);
                    x += wd[i] * sx;
                }
                return out;
            }
            case LINE: {
                // B · jam melengkung lembut mengikut garisan utama gambar.
                // Lengkung kuadratik (padanan teguh, buang titik terpencil seperti dahan) + lengkung pelangi
                // kecil, dengan had kecuraman: tiada lipatan tajam, digit tidak bertindih.
                boolean ok = s.horizonOk && s.horizonMid > h * 0.16f && s.horizonMid < h * 0.74f;
                double c0 = y, c1 = 0, c2 = 0;
                if (ok) {
                    double[] fit = robustQuad(s, w);
                    if (fit != null) { c0 = fit[0] - size * 0.06f; c1 = fit[1]; c2 = fit[2]; }
                }
                c1 = Math.max(-0.12 * w, Math.min(0.12 * w, c1));
                c2 = Math.max(-0.6 * size, Math.min(0.6 * size, c2)) + size * 0.9; // + lengkung pelangi
                float sp = 1.05f; // jarak tambahan supaya digit tidak bersentuh pada lengkung
                float half = total * sx * sp / 2f;
                // lalukan sepanjang lengkung; anggaran panjang lengkok dengan langkah kecil
                Path line = new Path();
                int steps = 160;
                float[] xs = new float[steps + 1], ys = new float[steps + 1], len = new float[steps + 1];
                float xa = w / 2f - half * 1.6f, xb = w / 2f + half * 1.6f;
                for (int i = 0; i <= steps; i++) {
                    xs[i] = xa + (xb - xa) * i / steps;
                    double u = (xs[i] - w / 2f) / w;
                    ys[i] = (float) (c0 + c1 * u + c2 * u * u * 4);
                    if (i == 0) line.moveTo(xs[i], ys[i]);
                    else {
                        line.lineTo(xs[i], ys[i]);
                        len[i] = len[i - 1] + (float) Math.hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1]);
                    }
                }
                float center = len[steps / 2];
                PathMeasure pm = new PathMeasure(line, false);
                float d = center - half;
                float[] pos = new float[2], tan = new float[2];
                for (int i = 0; i < n; i++) {
                    float dd = d + wd[i] * sx * sp / 2f;
                    pm.getPosTan(Math.max(0, Math.min(pm.getLength(), dd)), pos, tan);
                    double ang = Math.max(-0.38, Math.min(0.38, Math.atan2(tan[1], tan[0])));
                    Path g = glyph(tp, time.substring(i, i + 1), sx, sy);
                    Matrix m = new Matrix();
                    m.setRotate((float) Math.toDegrees(ang));
                    m.postTranslate(pos[0], pos[1]);
                    g.transform(m);
                    out.addPath(g);
                    d += wd[i] * sx * sp;
                }
                return out;
            }
            case SKY: {
                // C · jam besar memenuhi langit; bahagian bawah dipotong garis ufuk (topeng di drawClock)
                Path p = new Path();
                tp.getTextPath(time, 0, n, w / 2f, 0, p);
                Matrix m = new Matrix();
                m.setScale(sx, sy, w / 2f, 0);
                p.transform(m);
                RectF pb = new RectF();
                p.computeBounds(pb, true);
                float hz = s.horizonOk ? s.horizonMid : y;
                float textH = -pb.top;
                if (textH <= 0 || pb.width() <= 0) return null;
                float k = Math.min(w * 0.94f / pb.width(), Math.max(h * 0.08f, hz - h * 0.13f) * 1.35f / textH);
                k = Math.max(0.6f, k);
                m.reset();
                m.setScale(k, k, w / 2f, 0);
                m.postTranslate(0, hz + textH * k * 0.28f);
                p.transform(m);
                return p;
            }
            case HALO: {
                // D · jam melengkung seperti halo di atas kepala
                if (!s.head) return null;
                float cx = s.headX, cy = s.headTop + s.headR;
                float rb = s.headR + size * 0.2f;
                float f = Math.min(1f, 2.4f * rb / (total * sx));
                if (f < 0.5f) {
                    f = 0.5f;
                    rb = total * sx * f / 2.4f;
                }
                float span = total * sx * f / rb;
                // jangan terkeluar skrin di atas
                float topY = cy - rb - capH * f;
                if (topY < h * 0.08f) cy += h * 0.08f - topY;
                Path arc = new Path();
                double spanDeg = Math.toDegrees(span);
                arc.addArc(new RectF(cx - rb, cy - rb, cx + rb, cy + rb), (float) (-90 - spanDeg / 2), (float) spanDeg);
                PathMeasure pm = new PathMeasure(arc, false);
                float d = 0;
                float[] pos = new float[2], tan = new float[2];
                for (int i = 0; i < n; i++) {
                    pm.getPosTan(d + wd[i] * sx * f / 2f, pos, tan);
                    Path g = glyph(tp, time.substring(i, i + 1), sx * f, sy * f);
                    Matrix m = new Matrix();
                    m.setRotate((float) Math.toDegrees(Math.atan2(tan[1], tan[0])));
                    m.postTranslate(pos[0], pos[1]);
                    g.transform(m);
                    out.addPath(g);
                    d += wd[i] * sx * f;
                }
                return out;
            }
            case REFLECT: {
                // E · jam berdiri di garis ufuk; pantulan dilukis di drawClock
                boolean ok = s.horizonOk && s.horizonMid > h * 0.2f && s.horizonMid < h * 0.72f;
                float base = ok ? s.hzAt(w / 2f) - size * 0.02f : y;
                tp.getTextPath(time, 0, n, w / 2f, base, out);
                Matrix m = new Matrix();
                m.setScale(sx, sy, w / 2f, base);
                out.transform(m);
                return out;
            }
            case SAND: {
                // F · jam terbaring di atas tanah, ikut perspektif
                tp.getTextPath(time, 0, n, 0, 0, out);
                RectF tb = new RectF();
                out.computeBounds(tb, true);
                if (tb.width() <= 0) return null;
                float g0 = s.horizonOk ? Math.max(h * 0.52f, Math.min(h * 0.70f, s.horizonMid + h * 0.07f)) : h * 0.60f;
                float botW = Math.max(w * 0.5f, Math.min(w * 0.98f, w * 0.94f * (size / (w * 0.30f)) * sx));
                float topW = botW * 0.58f;
                float depth = botW * (tb.height() / tb.width()) * 0.62f * sy;
                float g1 = Math.min(h * 0.86f, g0 + depth);
                float cx = w / 2f;
                Matrix m = new Matrix();
                m.setPolyToPoly(new float[]{tb.left, tb.top, tb.right, tb.top, tb.right, tb.bottom, tb.left, tb.bottom}, 0,
                        new float[]{cx - topW / 2, g0, cx + topW / 2, g0, cx + botW / 2, g1, cx - botW / 2, g1}, 0, 4);
                out.transform(m);
                return out;
            }
        }
        return null;
    }

    /** Lapisan bawah (sebelum jam): bayang untuk jam di pasir. */
    static void under(int mode, Canvas c, Path path, float size) {
        if (mode != SAND) return;
        Paint sp = new Paint(Paint.ANTI_ALIAS_FLAG);
        sp.setColor(Color.argb(95, 40, 25, 10));
        sp.setMaskFilter(new BlurMaskFilter(Math.max(2f, size * 0.025f), BlurMaskFilter.Blur.NORMAL));
        Path sh = new Path(path);
        sh.offset(size * 0.01f, size * 0.035f);
        c.drawPath(sh, sp);
    }

    /** Lapisan atas (selepas jam): pantulan beralun untuk gaya air. */
    static void over(int mode, Canvas c, Path path, RectF b, int color, float size, int w, int h) {
        if (mode != REFLECT || b.width() <= 0) return;
        float axis = b.bottom + size * 0.02f;
        float pad = size * 0.12f;
        int rw = (int) Math.ceil(b.width() + pad * 2), rh = (int) Math.ceil(Math.min(h - axis, axis - b.top));
        if (rw <= 0 || rh <= 2) return;
        Bitmap rb = Bitmap.createBitmap(rw, rh, Bitmap.Config.ARGB_8888);
        Canvas rc = new Canvas(rb);
        rc.translate(-b.left + pad, axis);
        rc.scale(1, -1);
        Paint fp = new Paint(Paint.ANTI_ALIAS_FLAG);
        fp.setColor(color | 0xFF000000);
        fp.setMaskFilter(new BlurMaskFilter(Math.max(1.5f, size * 0.012f), BlurMaskFilter.Blur.NORMAL));
        rc.drawPath(path, fp);
        rc.setMatrix(null);
        Paint gp = new Paint();
        gp.setShader(new LinearGradient(0, 0, 0, rh, Color.argb(130, 0, 0, 0), Color.argb(0, 0, 0, 0), Shader.TileMode.CLAMP));
        gp.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        rc.drawRect(0, 0, rw, rh, gp);

        int strip = Math.max(2, Math.round(size * 0.022f));
        Paint bp = new Paint(Paint.FILTER_BITMAP_FLAG);
        c.save();
        c.clipRect(0, axis, w, h);
        Rect src = new Rect();
        RectF dst = new RectF();
        for (int yb = 0; yb < rh; yb += strip) {
            float t = yb / (float) rh;
            float amp = size * (0.008f + 0.035f * t);
            float off = (float) Math.sin(yb / (size * 0.045f)) * amp;
            int y2 = Math.min(rh, yb + strip);
            src.set(0, yb, rw, y2);
            float l = b.left - pad + off;
            dst.set(l, axis + yb, l + rw, axis + y2);
            c.drawBitmap(rb, src, dst, bp);
        }
        // garis kilauan nipis di permukaan air
        Paint lp = new Paint(Paint.ANTI_ALIAS_FLAG);
        lp.setStrokeWidth(Math.max(1f, size * 0.006f));
        lp.setShader(new LinearGradient(b.left - pad, 0, b.right + pad, 0,
                new int[]{0, Color.argb(120, 255, 255, 255), 0}, null, Shader.TileMode.CLAMP));
        c.drawLine(b.left - pad, axis + strip, b.right + pad, axis + strip, lp);
        c.restore();
        rb.recycle();
    }

    /** Topeng langit untuk gaya C: panggil selepas jam dilukis dalam saveLayer. */
    static void maskSky(Canvas c, Scene s, int w, int h) {
        if (s == null || s.skyMask == null) return;
        Paint mp = new Paint(Paint.FILTER_BITMAP_FLAG);
        mp.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        c.drawBitmap(s.skyMask, null, new RectF(0, 0, w, h), mp);
    }
}
