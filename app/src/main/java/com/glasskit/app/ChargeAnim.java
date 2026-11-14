package com.glasskit.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.LinearInterpolator;

/**
 * Animasi cas Liquid Glass: sfera kaca dengan cecair beralun naik ke paras bateri,
 * peratus besar dan kilat. Tidak menyekat sentuhan; hilang sendiri selepas ~3 saat.
 */
final class ChargeAnim {
    private final PillService s;
    private View view;
    private ValueAnimator life;

    ChargeAnim(PillService s) { this.s = s; }

    void show() {
        if (view != null) return;
        final int lvl = Math.max(0, Ui.battery(s));
        final boolean fast = isFast();
        final float[] st = new float[2]; // [0] hayat 0..1, [1] fasa gelombang
        view = new View(s) {
            final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            final Path liquid = new Path(), ball = new Path();

            @Override
            protected void onDraw(Canvas c) {
                float w = getWidth(), h = getHeight();
                float life = st[0];
                float phase = st[1];
                float in = Math.min(1f, life / 0.18f), out = life > 0.82f ? Math.max(0f, (1f - life) / 0.18f) : 1f;
                float a = Math.min(in, out);
                // latar gelap lembut
                p.setShader(null);
                p.setColor(Color.argb(Math.round(150 * a), 0, 0, 0));
                c.drawRect(0, 0, w, h, p);

                float cx = w / 2f, cy = h * 0.44f;
                float r = Math.min(w, h) * 0.27f * (0.86f + 0.14f * (float) Math.sin(Math.min(1f, life / 0.25f) * Math.PI / 2));
                ball.reset();
                ball.addCircle(cx, cy, r, Path.Direction.CW);
                int green = 0xFF30D158, deep = 0xFF0A8F3C;
                int low = lvl <= 20 ? 0xFFFF9F0A : green;

                // cahaya luar
                p.setShader(new RadialGradient(cx, cy, r * 1.6f, Color.argb(Math.round(90 * a), Color.red(low), Color.green(low), Color.blue(low)),
                        Color.argb(0, 0, 0, 0), Shader.TileMode.CLAMP));
                c.drawCircle(cx, cy, r * 1.6f, p);

                c.save();
                c.clipPath(ball);
                // badan kaca
                p.setShader(new LinearGradient(0, cy - r, 0, cy + r, Color.argb(Math.round(70 * a), 255, 255, 255),
                        Color.argb(Math.round(25 * a), 255, 255, 255), Shader.TileMode.CLAMP));
                c.drawCircle(cx, cy, r, p);
                // cecair beralun naik ke paras bateri
                float fill = lvl / 100f * Math.min(1f, life / 0.45f);
                float top = cy + r - 2 * r * fill;
                for (int layer = 0; layer < 2; layer++) {
                    liquid.reset();
                    float amp = r * (layer == 0 ? 0.06f : 0.045f), len = r * (layer == 0 ? 1.3f : 0.9f);
                    float ph = phase * (layer == 0 ? 1f : -1.4f);
                    liquid.moveTo(cx - r, cy + r);
                    for (float x = cx - r; x <= cx + r + 1; x += 4) {
                        liquid.lineTo(x, top + amp * (float) Math.sin((x - cx) / len * Math.PI * 2 + ph));
                    }
                    liquid.lineTo(cx + r, cy + r);
                    liquid.close();
                    int c1 = layer == 0 ? low : (lvl <= 20 ? 0xFFFFC266 : 0xFF7CF0A0);
                    p.setShader(new LinearGradient(0, top, 0, cy + r, Color.argb(Math.round((layer == 0 ? 220 : 120) * a),
                            Color.red(c1), Color.green(c1), Color.blue(c1)),
                            Color.argb(Math.round(235 * a), Color.red(deep), Color.green(deep), Color.blue(deep)), Shader.TileMode.CLAMP));
                    c.drawPath(liquid, p);
                }
                // gelembung
                p.setShader(null);
                p.setColor(Color.argb(Math.round(110 * a), 255, 255, 255));
                for (int i = 0; i < 7; i++) {
                    float bx = cx - r * 0.6f + (i * 0.19f % 1f) * r * 1.2f;
                    float by = cy + r - ((phase * 0.25f + i * 0.37f) % 1f) * (cy + r - top);
                    c.drawCircle(bx, by, r * (0.015f + 0.01f * (i % 3)), p);
                }
                // kilauan spekular
                p.setShader(new RadialGradient(cx - r * 0.4f, cy - r * 0.5f, r * 0.7f, Color.argb(Math.round(120 * a), 255, 255, 255),
                        Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP));
                c.drawCircle(cx, cy, r, p);
                c.restore();
                // bingkai
                p.setShader(new LinearGradient(cx - r, cy - r, cx + r, cy + r,
                        new int[]{Color.argb(Math.round(220 * a), 255, 255, 255), Color.argb(Math.round(40 * a), 255, 255, 255),
                                Color.argb(Math.round(140 * a), 255, 255, 255)}, null, Shader.TileMode.CLAMP));
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Math.max(2f, r * 0.02f));
                c.drawCircle(cx, cy, r, p);
                p.setStyle(Paint.Style.FILL);
                p.setShader(null);

                // teks
                p.setTextAlign(Paint.Align.CENTER);
                p.setColor(Color.argb(Math.round(255 * a), 255, 255, 255));
                p.setTypeface(Ui.font(700));
                p.setTextSize(r * 0.5f);
                p.setShadowLayer(r * 0.05f, 0, r * 0.02f, Color.argb(Math.round(120 * a), 0, 0, 0));
                int shown = Math.round(lvl * Math.min(1f, life / 0.45f));
                c.drawText(shown + "%", cx, cy + r * 0.17f, p);
                p.setTextSize(r * 0.13f);
                p.setTypeface(Ui.font(600));
                c.drawText(s.getString(fast ? R.string.charging_fast : R.string.charging), cx, cy + r + r * 0.32f, p);
                p.clearShadowLayer();
                // kilat
                Path b = new Path();
                float bs = r * 0.16f, bx = cx, by = cy - r * 0.42f;
                b.moveTo(bx + bs * 0.15f, by - bs);
                b.lineTo(bx - bs * 0.55f, by + bs * 0.15f);
                b.lineTo(bx - bs * 0.05f, by + bs * 0.15f);
                b.lineTo(bx - bs * 0.2f, by + bs);
                b.lineTo(bx + bs * 0.55f, by - bs * 0.2f);
                b.lineTo(bx + bs * 0.05f, by - bs * 0.2f);
                b.close();
                p.setColor(Color.argb(Math.round(255 * a), 255, 255, 255));
                c.drawPath(b, p);
            }
        };
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        lp.setTitle("GlassKitCharge");
        try {
            s.wm.addView(view, lp);
        } catch (Exception e) {
            view = null;
            return;
        }
        final View v = view;
        life = ValueAnimator.ofFloat(0f, 1f);
        life.setDuration(3200);
        life.setInterpolator(new LinearInterpolator());
        life.addUpdateListener(an -> {
            st[0] = (float) an.getAnimatedValue();
            st[1] = st[0] * 3.2f * 4.5f;
            v.invalidate();
        });
        life.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) { hide(); }
        });
        life.start();
    }

    void hide() {
        if (life != null) life.removeAllListeners();
        if (life != null) life.cancel();
        life = null;
        if (view != null) {
            try { s.wm.removeViewImmediate(view); } catch (Exception ignored) {}
            view = null;
        }
    }

    private boolean isFast() {
        try {
            android.content.Intent i = s.registerReceiver(null, new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
            if (i == null) return false;
            int plugged = i.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0);
            if (plugged == android.os.BatteryManager.BATTERY_PLUGGED_WIRELESS) return false;
            android.os.BatteryManager bm = s.getSystemService(android.os.BatteryManager.class);
            int ua = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
            return Math.abs(ua) > 1_500_000; // > 1.5A
        } catch (Exception e) {
            return false;
        }
    }
}
