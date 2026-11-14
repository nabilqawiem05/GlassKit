package com.glasskit.app;

import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.RectF;

public class WeatherWidget extends GlassWidget {
    @Override
    void render(Context c, Canvas cv, RectF r, GlassEngine.Theme t, float d, int size) { Cards.weather(c, cv, r, t, d, size); }

    @Override
    void tune(Context c, GlassEngine.Theme t) {
        Data.Weather w = Data.weather(c);
        if (w != null) t.glowTint = GlassEngine.weatherTint(w.code, w.day);
    }

    @Override
    Intent clickIntent(Context c) {
        Intent i = c.getPackageManager().getLaunchIntentForPackage("com.google.android.apps.weather");
        if (i != null) return i;
        return new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.google.com/search?q=weather"));
    }
}
