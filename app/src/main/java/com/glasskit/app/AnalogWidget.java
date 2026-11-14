package com.glasskit.app;

import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.provider.AlarmClock;

import java.util.Calendar;

/** Jam analog: piring kaca, jarum putih, titik tengah warna tema. */
public class AnalogWidget extends GlassWidget {
    @Override
    void render(Context c, Canvas cv, int w, int h, float d) {
        float r = Math.min(w, h) * 0.44f, cx = w / 2f, cy = h / 2f;
        Path dial = new Path();
        dial.addCircle(cx, cy, r, Path.Direction.CW);
        int tint = GlassArt.tint(c);
        GlassArt.draw(cv, dial, r * 1.2f, tint, dark(c), null, null);

        Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG);
        tick.setColor(Color.argb(200, 255, 255, 255));
        for (int i = 0; i < 12; i++) {
            double a = Math.PI * 2 * i / 12;
            float rr = r * 0.84f;
            cv.drawCircle(cx + (float) (Math.sin(a) * rr), cy - (float) (Math.cos(a) * rr),
                    i % 3 == 0 ? r * 0.045f : r * 0.025f, tick);
        }

        Calendar now = Calendar.getInstance();
        float min = now.get(Calendar.MINUTE);
        float hr = now.get(Calendar.HOUR) + min / 60f;
        hand(cv, cx, cy, hr / 12f, r * 0.5f, r * 0.075f, Color.WHITE);
        hand(cv, cx, cy, min / 60f, r * 0.74f, r * 0.05f, Color.WHITE);
        Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        dot.setColor(Ui.accent(c, true));
        cv.drawCircle(cx, cy, r * 0.07f, dot);
    }

    private void hand(Canvas cv, float cx, float cy, float frac, float len, float wid, int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        p.setShadowLayer(wid * 1.2f, 0, wid * 0.4f, Color.argb(110, 0, 0, 0));
        cv.save();
        cv.rotate(frac * 360f, cx, cy);
        cv.drawRoundRect(new RectF(cx - wid / 2, cy - len, cx + wid / 2, cy + wid), wid / 2, wid / 2, p);
        cv.restore();
    }

    @Override
    Intent clickIntent(Context c) { return new Intent(AlarmClock.ACTION_SHOW_ALARMS); }
}
