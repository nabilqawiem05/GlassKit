package com.glasskit.app;

import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;

public class PhotoWidget extends GlassWidget {
    @Override
    void render(Context c, Canvas cv, int w, int h, float d) { Cards.photo(c, cv, w, h); }

    @Override
    Intent clickIntent(Context c) { return new Intent(c, MainActivity.class); }
}
