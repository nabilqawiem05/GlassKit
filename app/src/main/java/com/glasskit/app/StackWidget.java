package com.glasskit.app;

import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Path;
import android.provider.AlarmClock;

/** Jam bertindan: jam di atas, minit di bawah. */
public class StackWidget extends GlassWidget {
    @Override
    void render(Context c, Canvas cv, int w, int h, float d) {
        String[] t = time(c).split(":");
        float size = Math.min(h * 0.5f, w * 0.62f);
        android.graphics.Typeface tf = DepthRenderer.font(Prefs.get(c).getInt(Prefs.CLOCK_FONT, 0));
        Path a = textPath(t[0].length() == 1 ? "0" + t[0] : t[0], tf, size, w / 2f, 0);
        Path b = textPath(t.length > 1 ? t[1] : "", tf, size, w / 2f, 0);
        centerPath(a, h * 0.28f);
        centerPath(b, h * 0.74f);
        int tint = GlassArt.tint(c);
        GlassArt.draw(cv, a, size, tint, dark(c), null, null);
        GlassArt.draw(cv, b, size, tint, dark(c), null, null);
    }

    @Override
    Intent clickIntent(Context c) { return new Intent(AlarmClock.ACTION_SHOW_ALARMS); }
}
