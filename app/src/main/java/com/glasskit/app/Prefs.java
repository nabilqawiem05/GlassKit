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
    public static final String PANELS_ON = "panelsOn";
    public static final String GLASS_DARK = "glassDark";   // panel & widget: false jernih, true gelap
    public static final String CLOCK_W = "clockW";         // % skala lebar
    public static final String CLOCK_H = "clockH";         // % skala tinggi
    public static final String CLOCK_GLASS = "clockGlass"; // gaya liquid glass
    public static final String HIDE_STOCK = "hideStock";
    public static final String HIDE_H = "hideH";           // dp
    public static final String PATCH_DIM = "patchDim";     // % kegelapan tampalan
    public static final String LOCK_ON = "lockOn";
    public static final String SBAR_ON = "sbarOn";
    public static final String SBAR_STYLE = "sbarStyle";
    public static final String SBAR_PCT = "sbarPct";

    private Prefs() {}

    public static SharedPreferences get(Context c) {
        return c.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }
}
