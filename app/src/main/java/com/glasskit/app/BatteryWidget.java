package com.glasskit.app;

import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.RectF;

public class BatteryWidget extends GlassWidget {
    @Override
    void render(Context c, Canvas cv, RectF r, GlassEngine.Theme t, float d, int size) { Cards.battery(c, cv, r, t, d, size); }

    @Override
    Intent clickIntent(Context c) { return new Intent(Intent.ACTION_POWER_USAGE_SUMMARY); }
}
