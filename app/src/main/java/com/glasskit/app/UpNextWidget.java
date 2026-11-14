package com.glasskit.app;

import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.RectF;

public class UpNextWidget extends GlassWidget {
    @Override
    void render(Context c, Canvas cv, RectF r, GlassEngine.Theme t, float d, int size) { Cards.upNext(c, cv, r, t, d, size); }

    @Override
    Intent clickIntent(Context c) { return calendar(); }
}
