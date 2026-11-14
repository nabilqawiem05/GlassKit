package com.glasskit.app;

import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;

public class MonthWidget extends GlassWidget {
    @Override
    void render(Context c, Canvas cv, int w, int h, float d) { Cards.month(c, cv, w, h); }

    @Override
    Intent clickIntent(Context c) { return new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("content://com.android.calendar/time/" + System.currentTimeMillis())); }
}
