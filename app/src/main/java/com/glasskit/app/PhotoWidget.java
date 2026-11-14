package com.glasskit.app;

import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.RectF;

public class PhotoWidget extends GlassWidget {
    @Override
    void render(Context c, Canvas cv, RectF r, GlassEngine.Theme t, float d, int size) { Cards.photo(c, cv, r, t, d, currentId); }

    @Override
    boolean fullBleed() { return true; }

    @Override
    Intent clickIntent(Context c) {
        return new Intent(c, MainActivity.class).putExtra(MainActivity.EXTRA_PICK_PHOTOS, true)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }
}
