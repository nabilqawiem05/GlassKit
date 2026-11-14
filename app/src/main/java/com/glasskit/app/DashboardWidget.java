package com.glasskit.app;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.provider.AlarmClock;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Papan pemuka XL (gaya widget extra-large iOS 27), tanpa bingkai. */
public class DashboardWidget extends GlassWidget {
    @Override
    void render(Context c, Canvas cv, int w, int h, float d) {
        boolean dark = dark(c);
        float cx = w / 2f;
        float y = h * 0.04f;

        // tarikh
        String date = new SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(new Date());
        float small = Math.max(12 * d, Math.min(w * 0.05f, h * 0.035f));
        label(cv, date.toUpperCase(Locale.getDefault()), small, 700, cx, y + small, Ui.accent(c, false));
        y += small * 1.6f;

        // jam besar kaca
        float size = Math.min(w * 0.34f, h * 0.22f);
        Path p = textPath(time(c), DepthRenderer.font(Prefs.get(c).getInt(Prefs.CLOCK_FONT, 0)), size, cx, 0);
        RectF b = new RectF();
        p.computeBounds(b, true);
        p.offset(0, y - b.top);
        GlassArt.draw(cv, p, size, GlassArt.tint(c), dark, null, null);
        p.computeBounds(b, true);
        y = b.bottom + h * 0.05f;

        // tolok
        float r = Math.min(w * 0.095f, h * 0.065f);
        int[] t = {Gauges.BATTERY, Gauges.DATE, Gauges.ALARM, Gauges.NOTIFS};
        float gap = (w - t.length * 2 * r) / (t.length + 1);
        float gx = gap + r;
        for (int type : t) {
            Gauges.draw(c, cv, type, gx, y + r, r, dark);
            gx += 2 * r + gap;
        }
        y += 2 * r + h * 0.06f;

        // muzik
        PillService s = PillService.inst;
        float art = Math.min(w * 0.22f, h - y - h * 0.04f);
        if (art < 24 * d) return;
        float ax = w * 0.08f;
        Path ap = new Path();
        RectF ar = new RectF(ax, y, ax + art, y + art);
        ap.addRoundRect(ar, art * 0.22f, art * 0.22f, Path.Direction.CW);
        Bitmap bm = s != null ? s.art : null;
        if (bm != null) {
            Paint sh = new Paint(Paint.ANTI_ALIAS_FLAG);
            BitmapShader bs = new BitmapShader(bm, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            Matrix m = new Matrix();
            float sc = Math.max(art / bm.getWidth(), art / bm.getHeight());
            m.setScale(sc, sc);
            m.postTranslate(ax, y);
            bs.setLocalMatrix(m);
            Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
            shadow.setColor(Color.argb(90, 0, 0, 0));
            shadow.setMaskFilter(new android.graphics.BlurMaskFilter(art * 0.08f, android.graphics.BlurMaskFilter.Blur.NORMAL));
            cv.drawPath(ap, shadow);
            sh.setShader(bs);
            cv.drawPath(ap, sh);
        } else {
            GlassArt.draw(cv, ap, art, GlassArt.tint(c), dark, null, null);
            Icon ic = new Icon(c, Icon.NOTE, Color.WHITE);
            int is = Math.round(art * 0.42f);
            ic.layout(0, 0, is, is);
            cv.save();
            cv.translate(ar.centerX() - is / 2f, ar.centerY() - is / 2f);
            ic.draw(cv);
            cv.restore();
        }
        Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
        tp.setColor(Color.WHITE);
        tp.setTypeface(Ui.font(700));
        tp.setTextSize(Math.max(13 * d, art * 0.2f));
        tp.setShadowLayer(tp.getTextSize() * 0.2f, 0, tp.getTextSize() * 0.06f, Color.argb(120, 0, 0, 0));
        float tx = ax + art + w * 0.05f, maxW = w - tx - w * 0.06f;
        String title = s != null && s.title != null ? s.title : c.getString(R.string.not_playing);
        cv.drawText(ellipsize(title, tp, maxW), tx, y + art * 0.45f, tp);
        if (s != null && s.artist != null) {
            tp.setTypeface(Ui.font(500));
            tp.setTextSize(tp.getTextSize() * 0.82f);
            tp.setColor(Color.argb(220, 255, 255, 255));
            cv.drawText(ellipsize(s.artist, tp, maxW), tx, y + art * 0.78f, tp);
        }
    }

    private static String ellipsize(String s, Paint p, float max) {
        if (p.measureText(s) <= max) return s;
        while (s.length() > 1 && p.measureText(s + "…") > max) s = s.substring(0, s.length() - 1);
        return s + "…";
    }

    @Override
    Intent clickIntent(Context c) { return new Intent(AlarmClock.ACTION_SHOW_ALARMS); }
}
