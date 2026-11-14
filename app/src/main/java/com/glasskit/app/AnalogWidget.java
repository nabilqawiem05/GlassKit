package com.glasskit.app;

import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;

public class AnalogWidget extends GlassWidget {
    @Override
    void render(Context c, Canvas cv, int w, int h, float d) { Cards.analog(c, cv, w, h); }

    @Override
    Intent clickIntent(Context c) { return new Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS); }
}
