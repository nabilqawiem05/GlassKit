package com.glasskit.app;

import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.RectF;
import android.provider.AlarmClock;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Jam kaca besar + tarikh. */
public class ClockWidget extends GlassWidget {
    @Override
    void render(Context c, Canvas cv, int w, int h, float d) {
        boolean showDate = h > 70 * d;
        float size = Math.min(h * (showDate ? 0.62f : 0.82f), w * 0.31f);
        Path p = textPath(time(c), DepthRenderer.font(Prefs.get(c).getInt(Prefs.CLOCK_FONT, 0)), size, w / 2f, 0);
        centerPath(p, showDate ? h * 0.44f : h / 2f);
        GlassArt.draw(cv, p, size, GlassArt.tint(c), dark(c), null, null);
        if (showDate) {
            RectF b = new RectF();
            p.computeBounds(b, true);
            String date = new SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(new Date());
            label(cv, date, Math.max(11 * d, size * 0.17f), 600, w / 2f, b.bottom + size * 0.3f, Color.WHITE);
        }
    }

    @Override
    Intent clickIntent(Context c) { return new Intent(AlarmClock.ACTION_SHOW_ALARMS); }

    /** Untuk keserasian dengan panggilan lama. */
    static void updateAll(Context c) { GlassWidget.refreshAll(c); }
}
