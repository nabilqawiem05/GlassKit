package com.glasskit.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.text.TextUtils;
import android.view.View;
import android.view.animation.Interpolator;
import android.widget.TextView;

/** Pembantu gaya "liquid glass". */
final class Ui {
    private Ui() {}

    static final int BLUE = 0xFF0A84FF;
    static final int GREEN = 0xFF30D158;
    static final int ORANGE = 0xFFFF9F0A;
    static final int PURPLE = 0xFF5E5CE6;

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    /** Permukaan kaca: isi lut sinar + kilauan atas + garis tepi halus. */
    static Drawable glass(Context c, float radiusDp, int fillAlpha) {
        float r = dp(c, radiusDp);
        GradientDrawable fill = new GradientDrawable();
        fill.setCornerRadius(r);
        fill.setColor(Color.argb(fillAlpha, 255, 255, 255));
        fill.setStroke(Math.max(1, dp(c, 0.8f)), Color.argb(60, 255, 255, 255));

        GradientDrawable sheen = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.argb(46, 255, 255, 255), Color.argb(0, 255, 255, 255), Color.argb(0, 255, 255, 255)});
        sheen.setCornerRadius(r);
        return new LayerDrawable(new Drawable[]{fill, sheen});
    }

    static GradientDrawable round(int color, float radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    static GradientDrawable circle(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        return g;
    }

    static Typeface font(int weight) {
        return Typeface.create(Typeface.SANS_SERIF, weight, false);
    }

    static TextView text(Context c, float sp, int weight, int color) {
        TextView t = new TextView(c);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(font(weight));
        t.setIncludeFontPadding(false);
        return t;
    }

    static TextView line(Context c, float sp, int weight, int color) {
        TextView t = text(c, sp, weight, color);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    /** Warna purata album art, dicerahkan supaya nampak cantik atas hitam. */
    static int artColor(Bitmap b) {
        if (b == null) return 0xFFFFFFFF;
        try {
            Bitmap s = Bitmap.createScaledBitmap(b, 8, 8, true);
            long r = 0, g = 0, bl = 0;
            int n = 0;
            for (int x = 0; x < 8; x++) {
                for (int y = 0; y < 8; y++) {
                    int p = s.getPixel(x, y);
                    float[] hsv = new float[3];
                    Color.colorToHSV(p, hsv);
                    int w = hsv[1] > 0.25f ? 3 : 1; // utamakan warna yang hidup
                    r += Color.red(p) * w;
                    g += Color.green(p) * w;
                    bl += Color.blue(p) * w;
                    n += w;
                }
            }
            int avg = Color.rgb((int) (r / n), (int) (g / n), (int) (bl / n));
            float[] hsv = new float[3];
            Color.colorToHSV(avg, hsv);
            hsv[1] = Math.min(1f, hsv[1] * 1.35f);
            hsv[2] = Math.max(0.82f, hsv[2]);
            return Color.HSVToColor(hsv);
        } catch (Exception e) {
            return 0xFFFFFFFF;
        }
    }

    static String mmss(long ms) {
        if (ms < 0) ms = 0;
        long s = ms / 1000;
        return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
    }

    /** Spring lembut ala iOS. */
    static final Interpolator SPRING = t -> (float) (1 - Math.exp(-6.5 * t) * Math.cos(9.5 * t));

    static void press(View v) {
        v.setOnTouchListener((view, e) -> {
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    view.animate().scaleX(0.93f).scaleY(0.93f).setDuration(90).start();
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    view.animate().scaleX(1f).scaleY(1f).setInterpolator(SPRING).setDuration(380).start();
                    break;
            }
            return false;
        });
    }
}
