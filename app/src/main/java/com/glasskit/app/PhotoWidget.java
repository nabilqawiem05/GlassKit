package com.glasskit.app;

import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;

public class PhotoWidget extends GlassWidget {
    @Override
    void render(Context c, Canvas cv, int w, int h, float d) { Cards.photo(c, cv, w, h, currentId); }

    @Override
    Intent clickIntent(Context c) {
        return new Intent(c, MainActivity.class).putExtra(MainActivity.EXTRA_PICK_PHOTOS, true)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }

}
