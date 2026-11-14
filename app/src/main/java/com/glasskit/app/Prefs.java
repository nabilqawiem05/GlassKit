package com.glasskit.app;

import android.content.Context;
import android.content.SharedPreferences;

public final class Prefs {
    public static final String NAME = "glasskit";

    public static final String CLOCK_SIZE = "clockSize";   // % lebar skrin
    public static final String CLOCK_Y = "clockY";         // % tinggi skrin (garis dasar)
    public static final String CLOCK_COLOR = "clockColor";
    public static final String CLOCK_FONT = "clockFont";   // 0 tebal, 1 nipis, 2 serif, 3 bulat
    public static final String LOCK_ONLY = "lockOnly";
    public static final String IMG_VER = "imgVer";

    public static final String PILL_ON = "pillOn";
    public static final String PILL_MUSIC = "pillMusic";
    public static final String PILL_NOTI = "pillNoti";

    private Prefs() {}

    public static SharedPreferences get(Context c) {
        return c.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }
}
