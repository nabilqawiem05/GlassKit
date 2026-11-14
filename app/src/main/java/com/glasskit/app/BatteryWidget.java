package com.glasskit.app;

import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/** Bateri: cincin kaca dengan peratus di tengah. */
public class BatteryWidget extends GlassWidget {
    @Override
    void render(Context c, Canvas cv, int w, int h, float d) {
        int lvl = Math.max(0, Ui.battery(c));
        boolean ch = Ui.charging(c);
        float r = Math.min(w, h) * 0.42f, cx = w / 2f, cy = h / 2f, th = r * 0.24f;

        Path ring = new Path();
        ring.addCircle(cx, cy, r, Path.Direction.CW);
        ring.addCircle(cx, cy, r - th, Path.Direction.CCW);
        GlassArt.draw(cv, ring, r, GlassArt.tint(c), dark(c), null, null);

        Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeCap(Paint.Cap.ROUND);
        arc.setStrokeWidth(th * 0.62f);
        arc.setColor(ch ? 0xFF30D158 : (lvl <= 15 ? 0xFFFF453A : Ui.accent(c, true)));
        float m = r - th / 2f;
        cv.drawArc(new RectF(cx - m, cy - m, cx + m, cy + m), -90, 360f * lvl / 100f, false, arc);

        float size = r * 0.62f;
        Path p = textPath(String.valueOf(lvl), DepthRenderer.font(0), size, cx, 0);
        centerPath(p, cy - (ch ? size * 0.1f : 0));
        GlassArt.draw(cv, p, size, GlassArt.tint(c), dark(c), null, null);
        if (ch) {
            Icon bolt = new Icon(c, Icon.BOLT, Color.WHITE);
            int bs = Math.round(size * 0.42f);
            bolt.layout(0, 0, bs, bs);
            cv.save();
            cv.translate(cx - bs / 2f, cy + size * 0.42f);
            bolt.draw(cv);
            cv.restore();
        }
    }

    @Override
    Intent clickIntent(Context c) { return new Intent(Intent.ACTION_POWER_USAGE_SUMMARY); }
}
