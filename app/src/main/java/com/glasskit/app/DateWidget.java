package com.glasskit.app;

import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.RectF;
import android.net.Uri;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Tarikh: hari (warna tema), nombor besar kaca, bulan. */
public class DateWidget extends GlassWidget {
    @Override
    void render(Context c, Canvas cv, int w, int h, float d) {
        Date now = new Date();
        String day = new SimpleDateFormat("EEEE", Locale.getDefault()).format(now);
        String num = new SimpleDateFormat("d", Locale.getDefault()).format(now);
        String mon = new SimpleDateFormat("MMMM", Locale.getDefault()).format(now);
        float size = Math.min(h * 0.52f, w * 0.56f);
        Path p = textPath(num, DepthRenderer.font(0), size, w / 2f, 0);
        centerPath(p, h * 0.52f);
        RectF b = new RectF();
        p.computeBounds(b, true);
        float small = Math.max(11 * d, size * 0.2f);
        label(cv, day, small, 700, w / 2f, b.top - small * 0.55f, Ui.accent(c, false));
        GlassArt.draw(cv, p, size, GlassArt.tint(c), dark(c), null, null);
        label(cv, mon, small, 600, w / 2f, b.bottom + small * 1.3f, Color.WHITE);
    }

    @Override
    Intent clickIntent(Context c) {
        return new Intent(Intent.ACTION_VIEW, Uri.parse("content://com.android.calendar/time/" + System.currentTimeMillis()));
    }
}
