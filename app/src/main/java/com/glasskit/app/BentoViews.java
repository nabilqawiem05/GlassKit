package com.glasskit.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Kotak-kotak beranimasi untuk panel Bento. Semua animasi berhenti bila tidak dipaparkan. */
final class BentoViews {
    private BentoViews() {}

    static int dp(Context c, float v) { return Ui.dp(c, v); }

    /** Latar kotak bento: kaca lembut; bila hidup diwarnakan dengan warna suis. */
    static GradientDrawable boxBg(Context c, boolean on, int color) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(c, 24));
        if (on) {
            g.setColors(new int[]{(color & 0x00FFFFFF) | 0x8C000000, (color & 0x00FFFFFF) | 0x55000000});
            g.setOrientation(GradientDrawable.Orientation.TL_BR);
            g.setStroke(Math.max(1, dp(c, 1)), (color & 0x00FFFFFF) | 0xA0000000);
        } else {
            g.setColor(Color.argb(26, 255, 255, 255));
            g.setStroke(Math.max(1, dp(c, 1)), Color.argb(38, 255, 255, 255));
        }
        return g;
    }

    /** Animasi berjalan ~30fps hanya bila kelihatan. */
    abstract static class Live extends View {
        Live(Context c) { super(c); }

        void tick() {
            if (isAttachedToWindow() && isShown() && !Power.low(getContext())) postInvalidateDelayed(33);
        }
    }

    // ===================== Langit cuaca =====================

    static final class Sky extends Live {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int code;
        private final boolean day;

        Sky(Context c, int code, boolean day) {
            super(c);
            this.code = code;
            this.day = day;
        }

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            if (w == 0) return;
            float t = (SystemClock.uptimeMillis() % 16000) / 16000f;
            float sh = (float) Math.sin(t * Math.PI * 2) * 0.5f + 0.5f;
            boolean rain = code >= 51;
            int a, b;
            if (!day) { a = 0xFF26215C; b = 0xFF0B0A1F; }
            else if (rain) { a = 0xFF5F5E5A; b = 0xFF2C2C2A; }
            else if (code <= 1) { a = 0xFF378ADD; b = 0xFF0C447C; }
            else { a = 0xFF85B7EB; b = 0xFF185FA5; }
            p.setShader(new LinearGradient(0, -h * sh * 0.4f, w, h * (1.2f - sh * 0.3f), a, b, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, p);
            p.setShader(null);
            if (code <= 1) {
                // matahari / bulan dengan cahaya bernafas
                float cx = w * 0.78f, cy = h * 0.26f, r = Math.min(w, h) * 0.12f;
                p.setColor(day ? Color.argb(70, 255, 214, 10) : Color.argb(50, 255, 255, 255));
                c.drawCircle(cx, cy, r * (1.6f + 0.25f * sh), p);
                p.setColor(day ? 0xFFFFD60A : 0xFFF1EFE8);
                c.drawCircle(cx, cy, r, p);
            }
            // awan berarak
            int n = code <= 1 ? 1 : 3;
            for (int i = 0; i < n; i++) {
                float sp = (SystemClock.uptimeMillis() / (9000f + i * 3500f) + i * 0.37f) % 1f;
                float cx = -w * 0.3f + sp * w * 1.6f, cy = h * (0.22f + i * 0.17f), s = w * (0.16f - i * 0.025f);
                p.setColor(Color.argb(rain ? 200 : 225, 255, 255, 255));
                c.drawCircle(cx, cy, s * 0.42f, p);
                c.drawCircle(cx + s * 0.45f, cy + s * 0.08f, s * 0.32f, p);
                c.drawRoundRect(cx - s * 0.6f, cy, cx + s * 0.85f, cy + s * 0.36f, s * 0.18f, s * 0.18f, p);
            }
            if (rain) {
                p.setColor(Color.argb(170, 100, 210, 255));
                p.setStrokeWidth(dp(getContext(), 1.4f));
                long now = SystemClock.uptimeMillis();
                for (int i = 0; i < 14; i++) {
                    float x = (i * 37 % 100) / 100f * w;
                    float y = ((now / 6f + i * 53) % (h * 0.7f)) + h * 0.3f;
                    c.drawLine(x, y, x - dp(getContext(), 2), y + dp(getContext(), 7), p);
                }
            }
            tick();
        }
    }

    // ===================== Bateri cecair =====================

    static final class LiquidBattery extends Live {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path wave = new Path();
        private float level, shown;
        private final boolean charging;

        LiquidBattery(Context c, int pct, boolean charging) {
            super(c);
            this.level = Math.max(0.03f, pct / 100f);
            this.charging = charging;
        }

        /** Air naik dari bawah bila kotak muncul. */
        void fill() {
            android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(0f, level);
            a.setDuration(1500);
            a.setStartDelay(250);
            a.setInterpolator(Ui.EASE);
            a.addUpdateListener(x -> shown = (float) x.getAnimatedValue());
            a.start();
        }

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            if (w == 0) return;
            int col = level <= 0.2f && !charging ? 0xFFE24B4A : 0xFF1D9E75;
            int col2 = level <= 0.2f && !charging ? 0xFFF09595 : 0xFF5DCAA5;
            float top = h * (1f - shown);
            float t = SystemClock.uptimeMillis() / 1000f;
            for (int k = 0; k < 2; k++) {
                wave.reset();
                wave.moveTo(0, h);
                float amp = dp(getContext(), k == 0 ? 5 : 3.5f), ph = t * (k == 0 ? 2.2f : -1.6f);
                for (int x = 0; x <= w; x += 6) {
                    wave.lineTo(x, top + amp * (float) Math.sin(x / (float) w * Math.PI * 2 + ph));
                }
                wave.lineTo(w, h);
                wave.close();
                p.setShader(new LinearGradient(0, top, 0, h, k == 0 ? col2 : col, col, Shader.TileMode.CLAMP));
                p.setAlpha(k == 0 ? 255 : 130);
                c.drawPath(wave, p);
            }
            p.setShader(null);
            p.setAlpha(255);
            tick();
        }
    }

    // ===================== Cincin (waktu solat / langkah) =====================

    static final class Ring extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float frac, shown;
        private final int color;

        Ring(Context c, float frac, int color) {
            super(c);
            this.frac = Math.max(0f, Math.min(1f, frac));
            this.color = color;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
        }

        void grow() {
            android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(0f, frac);
            a.setDuration(1200);
            a.setStartDelay(300);
            a.setInterpolator(Ui.EASE);
            a.addUpdateListener(x -> {
                shown = (float) x.getAnimatedValue();
                invalidate();
            });
            a.start();
        }

        @Override
        protected void onDraw(Canvas c) {
            float s = Math.min(getWidth(), getHeight()), sw = s * 0.11f;
            RectF r = new RectF(sw, sw, s - sw, s - sw);
            r.offset((getWidth() - s) / 2f, (getHeight() - s) / 2f);
            p.setStrokeWidth(sw);
            p.setColor(Color.argb(50, 255, 255, 255));
            c.drawOval(r, p);
            p.setColor(color);
            c.drawArc(r, -90, 360 * shown, false, p);
        }
    }

    // ===================== Kotak terbalik (bertukar isi) =====================

    /** Dua muka; bertukar dengan animasi terbalik setiap beberapa saat (hanya bila kelihatan). */
    static final class FlipBox extends FrameLayout {
        private final View a, b;
        private boolean showingB;
        private final long every, delay;
        private final Runnable flip = this::flip;

        FlipBox(Context c, View faceA, View faceB, long everyMs, long firstDelayMs) {
            super(c);
            a = faceA;
            b = faceB;
            every = everyMs;
            delay = firstDelayMs;
            addView(a, new LayoutParams(-1, -1));
            addView(b, new LayoutParams(-1, -1));
            b.setVisibility(INVISIBLE);
            setCameraDistance(dp(c, 900) * c.getResources().getDisplayMetrics().density);
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            removeCallbacks(flip);
            postDelayed(flip, delay);
        }

        @Override
        protected void onDetachedFromWindow() {
            removeCallbacks(flip);
            super.onDetachedFromWindow();
        }

        private void flip() {
            if (!isAttachedToWindow()) return;
            if (!isShown() || Power.low(getContext())) {
                postDelayed(flip, every);
                return;
            }
            animate().rotationY(90).setDuration(210).setInterpolator(Ui.EASE).withEndAction(() -> {
                showingB = !showingB;
                a.setVisibility(showingB ? INVISIBLE : VISIBLE);
                b.setVisibility(showingB ? VISIBLE : INVISIBLE);
                setRotationY(-90);
                animate().rotationY(0).setDuration(380).setInterpolator(Ui.SPRING).start();
            }).start();
            postDelayed(flip, every);
        }
    }

    /** Muka teks: label kecil di atas, nilai besar, keterangan. */
    static LinearLayout face(Context c, String small, String big, String sub) {
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.BOTTOM);
        col.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12));
        if (small != null) {
            TextView s = Ui.line(c, 11, 500, Color.argb(210, 255, 255, 255));
            s.setText(small);
            col.addView(s);
        }
        TextView b = Ui.line(c, 32, 650, Color.WHITE);
        b.setText(big);
        b.setLetterSpacing(-0.02f);
        b.setShadowLayer(dp(c, 6), 0, dp(c, 1), Color.argb(90, 0, 0, 0));
        col.addView(b);
        if (sub != null) {
            TextView d = Ui.line(c, 11, 500, Color.argb(220, 255, 255, 255));
            d.setText(sub);
            d.setShadowLayer(dp(c, 4), 0, dp(c, 1), Color.argb(90, 0, 0, 0));
            col.addView(d);
        }
        return col;
    }

    /** Nombor dikira naik (cth. suhu 0° → 27°). */
    static void countUp(TextView tv, int to, String suffix) {
        android.animation.ValueAnimator a = android.animation.ValueAnimator.ofInt(0, to);
        a.setDuration(900);
        a.setStartDelay(250);
        a.setInterpolator(Ui.EASE);
        a.addUpdateListener(x -> tv.setText(x.getAnimatedValue() + suffix));
        a.start();
    }
}
