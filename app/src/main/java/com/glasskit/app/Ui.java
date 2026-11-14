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

    /** Kaca ikut gaya pilihan: jernih (putih lut sinar) atau gelap (hitam lut sinar). */
    /** Mod kaca dipilih pengguna: 0 cerah, 1 gelap tinted, 2 ikut mod gelap sistem. */
    static int glassMode(Context c) {
        android.content.SharedPreferences p = Prefs.get(c);
        return p.getInt(Prefs.GLASS_MODE, p.getBoolean(Prefs.GLASS_DARK, false) ? 1 : 0);
    }

    static boolean darkGlass(Context c) {
        int m = glassMode(c);
        if (m == 2) {
            int night = c.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            return night == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        }
        return m == 1;
    }

    static Drawable glassStyled(Context c, float radiusDp, boolean dark) {
        if (LiquidGlass.current != null) return new LiquidGlass.Surface(c, dp(c, radiusDp), dark, LiquidGlass.current, 0);
        if (!dark) return glass(c, radiusDp, 44);
        float r = dp(c, radiusDp);
        GradientDrawable fill = new GradientDrawable();
        fill.setCornerRadius(r);
        fill.setColor(Color.argb(120, 18, 18, 22));
        fill.setStroke(Math.max(1, dp(c, 0.8f)), Color.argb(46, 255, 255, 255));
        GradientDrawable sheen = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.argb(26, 255, 255, 255), Color.argb(0, 255, 255, 255), Color.argb(0, 255, 255, 255)});
        sheen.setCornerRadius(r);
        return new LayerDrawable(new Drawable[]{fill, sheen});
    }

    /** Warna aksen dari wallpaper (Material You). */
    static int accent(Context c, boolean dark) {
        try {
            return c.getColor(dark ? android.R.color.system_accent1_200 : android.R.color.system_accent1_100);
        } catch (Exception e) {
            return 0xFFA8C7FA;
        }
    }

    static int onAccent(Context c) {
        try {
            return c.getColor(android.R.color.system_accent1_900);
        } catch (Exception e) {
            return 0xFF062E6F;
        }
    }

    static android.graphics.drawable.BitmapDrawable iconDrawable(Context c, int kind, int color, int bg, int sizePx) {
        Bitmap b = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas cv = new android.graphics.Canvas(b);
        if (bg != 0) {
            android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            p.setColor(bg);
            cv.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f, p);
        }
        Icon ic = new Icon(c, kind, color);
        int pad = bg != 0 ? sizePx / 4 : 0;
        ic.layout(0, 0, sizePx - 2 * pad, sizePx - 2 * pad);
        cv.translate(pad, pad);
        ic.draw(cv);
        return new android.graphics.drawable.BitmapDrawable(c.getResources(), b);
    }

    static int battery(Context c) {
        android.os.BatteryManager bm = c.getSystemService(android.os.BatteryManager.class);
        return bm == null ? -1 : bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY);
    }

    /** Mengecas = charger bersambung (termasuk pengecasan pintar yang ditahan) atau status mengecas/penuh. */
    static boolean charging(Context c) {
        try {
            android.content.Intent i = c.getApplicationContext().registerReceiver(null,
                    new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
            if (i != null) {
                int plugged = i.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0);
                int st = i.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1);
                if (plugged != 0 || st == android.os.BatteryManager.BATTERY_STATUS_CHARGING
                        || st == android.os.BatteryManager.BATTERY_STATUS_FULL) return true;
            }
        } catch (Exception ignored) {
        }
        android.os.BatteryManager bm = c.getSystemService(android.os.BatteryManager.class);
        return bm != null && bm.isCharging();
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
