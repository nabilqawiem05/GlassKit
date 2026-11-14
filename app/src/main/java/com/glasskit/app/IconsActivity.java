package com.glasskit.app;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Pembuat ikon kaca: pin pintasan app ke home screen dengan ikon liquid glass ikut tema. */
public class IconsActivity extends Activity {
    private static final int CLEAR = 0, DARK = 1, TINT = 2;

    private final ExecutorService ex = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<ResolveInfo> apps = new ArrayList<>();
    private final List<ImageView> previews = new ArrayList<>();
    private int style = CLEAR;
    private int gen;

    private int dp(float v) { return Ui.dp(this, v); }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(0xFF07080F);
        getWindow().setNavigationBarColor(0xFF0B1424);
        ScrollView sv = new ScrollView(this);
        sv.setFitsSystemWindows(true);
        sv.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF07080F, 0xFF15102A, 0xFF0B1424}));
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(24), dp(18), dp(40));
        sv.addView(box);
        setContentView(sv);

        TextView h = Ui.text(this, 32, 800, Color.WHITE);
        h.setText(R.string.sec_icons);
        box.addView(h);
        TextView hint = Ui.text(this, 14, 400, Color.argb(170, 235, 235, 245));
        hint.setText(R.string.icons_hint);
        hint.setLineSpacing(0, 1.2f);
        hint.setPadding(0, dp(10), 0, dp(14));
        box.addView(hint);

        LinearLayout seg = new LinearLayout(this);
        seg.setBackground(Ui.glass(this, 22, 26));
        seg.setPadding(dp(4), dp(4), dp(4), dp(4));
        int[] labels = {R.string.glass_clear, R.string.glass_dark, R.string.icon_style_tint};
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            TextView t = Ui.text(this, 14, 600, Color.WHITE);
            t.setText(labels[i]);
            t.setGravity(Gravity.CENTER);
            t.setOnClickListener(v -> {
                style = idx;
                paint(seg);
                regenerate();
            });
            seg.addView(t, new LinearLayout.LayoutParams(0, dp(38), 1f));
        }
        box.addView(seg, new LinearLayout.LayoutParams(-1, -2));
        paint(seg);

        PackageManager pm = getPackageManager();
        Intent li = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        apps.addAll(pm.queryIntentActivities(li, 0));
        apps.removeIf(r -> r.activityInfo.packageName.equals(getPackageName()));
        apps.sort((x, y) -> x.loadLabel(pm).toString().compareToIgnoreCase(y.loadLabel(pm).toString()));

        LinearLayout row = null;
        for (int i = 0; i < apps.size(); i++) {
            if (i % 4 == 0) {
                row = new LinearLayout(this);
                LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
                rl.topMargin = dp(16);
                box.addView(row, rl);
            }
            final ResolveInfo ri = apps.get(i);
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            ImageView iv = new ImageView(this);
            iv.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View view, android.graphics.Outline o) {
                    o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), view.getWidth() * 0.3f);
                }
            });
            iv.setClipToOutline(true);
            cell.addView(iv, new LinearLayout.LayoutParams(dp(60), dp(60)));
            previews.add(iv);
            TextView lab = Ui.line(this, 12, 500, Color.WHITE);
            lab.setText(ri.loadLabel(pm));
            lab.setGravity(Gravity.CENTER);
            lab.setPadding(dp(2), dp(6), dp(2), 0);
            cell.addView(lab, new LinearLayout.LayoutParams(-1, -2));
            cell.setOnClickListener(v -> pin(ri));
            Ui.press(cell);
            row.addView(cell, new LinearLayout.LayoutParams(0, -2, 1f));
        }
        if (row != null) {
            while (row.getChildCount() < 4) row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        }
        regenerate();
    }

    private void paint(LinearLayout seg) {
        for (int i = 0; i < seg.getChildCount(); i++) {
            TextView t = (TextView) seg.getChildAt(i);
            boolean on = i == style;
            t.setBackground(on ? Ui.round(Color.WHITE, dp(19)) : null);
            t.setTextColor(on ? 0xFF0B0B10 : Color.WHITE);
        }
    }

    private void regenerate() {
        final int g = ++gen;
        final int st = style;
        final PackageManager pm = getPackageManager();
        ex.execute(() -> {
            for (int i = 0; i < apps.size(); i++) {
                if (g != gen) return;
                Bitmap bmp;
                try {
                    bmp = glassIcon(this, apps.get(i).loadIcon(pm), st, dp(60));
                } catch (Throwable t) {
                    continue;
                }
                final int idx = i;
                final Bitmap fb = bmp;
                main.post(() -> { if (g == gen) previews.get(idx).setImageBitmap(fb); });
            }
        });
    }

    private void pin(ResolveInfo ri) {
        try {
            ShortcutManager sm = getSystemService(ShortcutManager.class);
            if (sm == null || !sm.isRequestPinShortcutSupported()) {
                Toast.makeText(this, R.string.icon_pin_fail, Toast.LENGTH_SHORT).show();
                return;
            }
            PackageManager pm = getPackageManager();
            ComponentName cn = new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name);
            Intent launch = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(cn)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            Bitmap icon = glassIcon(this, ri.loadIcon(pm), style, 432);
            ShortcutInfo info = new ShortcutInfo.Builder(this, "gk_" + cn.flattenToShortString() + "_" + style)
                    .setShortLabel(ri.loadLabel(pm))
                    .setIcon(Icon.createWithAdaptiveBitmap(icon))
                    .setIntent(launch)
                    .build();
            sm.requestPinShortcut(info, null);
            Toast.makeText(this, R.string.icon_added, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, R.string.icon_pin_fail, Toast.LENGTH_SHORT).show();
        }
    }

    private static int mix(int a, int b, float t) {
        return Color.rgb(
                Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    /**
     * Ikon kaca persegi penuh (launcher akan potong ikut bentuk ikon).
     * Glyph: ikon monokrom app jika ada (sama seperti Themed icons), jika tiada: ikon kelabu.
     */
    static Bitmap glassIcon(Context c, Drawable app, int style, int size) {
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        int light, deep;
        try {
            light = c.getColor(android.R.color.system_accent1_100);
            deep = c.getColor(android.R.color.system_accent1_700);
        } catch (Exception e) {
            light = 0xFFD3E3FD;
            deep = 0xFF0B57D0;
        }
        int top, bot, glyph;
        switch (style) {
            case DARK:
                top = mix(0xFF34343C, deep, 0.15f);
                bot = mix(0xFF101014, deep, 0.10f);
                glyph = light;
                break;
            case TINT:
                top = mix(deep, Color.WHITE, 0.25f);
                bot = deep;
                glyph = Color.WHITE;
                break;
            default:
                top = mix(Color.WHITE, light, 0.35f);
                bot = mix(Color.WHITE, light, 0.85f);
                glyph = mix(deep, Color.BLACK, 0.25f);
        }
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, 0, size, top, bot, Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, size, size, p);

        // kilauan cecair: cahaya atas kiri & pantulan bawah
        Paint hi = new Paint(Paint.ANTI_ALIAS_FLAG);
        hi.setShader(new RadialGradient(size * 0.32f, size * 0.18f, size * 0.55f,
                Color.argb(style == DARK ? 70 : 150, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, size, size, hi);
        Paint lo = new Paint(Paint.ANTI_ALIAS_FLAG);
        lo.setShader(new LinearGradient(0, size * 0.7f, 0, size,
                Color.argb(0, 255, 255, 255), Color.argb(style == DARK ? 40 : 90, 255, 255, 255), Shader.TileMode.CLAMP));
        cv.drawRect(0, size * 0.7f, size, size, lo);

        Drawable mono = null;
        if (app instanceof AdaptiveIconDrawable) mono = ((AdaptiveIconDrawable) app).getMonochrome();
        if (mono != null) {
            mono = mono.mutate();
            // bayang lembut di bawah glyph
            Bitmap g = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas gc = new Canvas(g);
            mono.setBounds(0, 0, size, size);
            mono.setTint(glyph);
            mono.draw(gc);
            Paint sh = new Paint(Paint.ANTI_ALIAS_FLAG);
            sh.setMaskFilter(new BlurMaskFilter(size * 0.03f, BlurMaskFilter.Blur.NORMAL));
            sh.setColor(Color.argb(style == CLEAR ? 50 : 110, 0, 0, 0));
            int[] off = new int[2];
            Bitmap alpha = g.extractAlpha(sh, off);
            cv.drawBitmap(alpha, off[0], off[1] + size * 0.015f, sh);
            cv.drawBitmap(g, 0, 0, null);
        } else if (app != null) {
            Drawable d = app.mutate();
            int in = Math.round(size * 0.24f);
            d.setBounds(in, in, size - in, size - in);
            ColorMatrix cm = new ColorMatrix();
            cm.setSaturation(style == TINT ? 0f : 0.15f);
            d.setColorFilter(new ColorMatrixColorFilter(cm));
            d.draw(cv);
            d.setColorFilter(null);
        }
        return b;
    }
}
