package com.glasskit.app;

import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;

public class StepsWidget extends GlassWidget {
    @Override
    void render(Context c, Canvas cv, int w, int h, float d) { Cards.steps(c, cv, w, h); }

    @Override
    Intent clickIntent(Context c) { return c.getPackageManager().getLaunchIntentForPackage("com.google.android.apps.fitness"); }
}
