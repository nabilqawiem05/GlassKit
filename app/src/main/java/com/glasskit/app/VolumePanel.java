package com.glasskit.app;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.media.AudioManager;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.FrameLayout;

/** Panel volume kaca di sisi kanan (gantikan panel volume sistem untuk media). */
final class VolumePanel {
    private final PillService s;
    private final LiquidGlass.Backdrop bd = new LiquidGlass.Backdrop();
    private FrameLayout root;
    private Level level;
    private final Runnable autoHide = this::hide;
    private long lastShot;
    /** Salinan tangkapan skrin & kabur dibuat di latar (bukan thread UI) supaya tiada sangkut. */
    private static final java.util.concurrent.Executor SHOT = java.util.concurrent.Executors.newSingleThreadExecutor();

    VolumePanel(PillService s) { this.s = s; }

    private int dp(float v) { return Ui.dp(s, v); }

    private AudioManager am() { return s.getSystemService(AudioManager.class); }

    /** Dipanggil dari onKeyEvent. Kembalikan true jika kekunci digunakan. */
    boolean onKey(boolean up) {
        AudioManager am = am();
        int mode = am.getMode();
        if (mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION
                || mode == AudioManager.MODE_RINGTONE) return false;
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, up ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, 0);
        show();
        return true;
    }

    private float value() {
        AudioManager am = am();
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        return max > 0 ? (float) am.getStreamVolume(AudioManager.STREAM_MUSIC) / max : 0;
    }

    void show() {
        s.h.removeCallbacks(autoHide);
        s.h.postDelayed(autoHide, 2200);
        if (root != null) {
            level.animateTo(value());
            return;
        }
        root = new FrameLayout(s);
        level = new Level();
        level.v = value();
        level.shown = level.v;
        FrameLayout pill = new FrameLayout(s);
        pill.setBackground(new LiquidGlass.Surface(s, dp(27), Ui.darkGlass(s), bd, 0));
        pill.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline o) {
                o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(27));
            }
        });
        pill.setClipToOutline(true);
        pill.addView(level, new FrameLayout.LayoutParams(-1, -1));
        root.addView(pill, new FrameLayout.LayoutParams(dp(54), dp(190), Gravity.CENTER));

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(dp(70), dp(206),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        lp.x = dp(4);
        lp.y = -dp(70);
        lp.setTitle("GlassKitVolume");
        try {
            s.wm.addView(root, lp);
        } catch (Exception e) {
            root = null;
            return;
        }
        pill.setAlpha(0f);
        pill.setTranslationX(dp(40));
        pill.setScaleY(0.9f);
        pill.animate().alpha(1f).translationX(0).scaleY(1f).setInterpolator(Ui.SPRING).setDuration(420).withLayer().start();
        // biasan latar: tangkap skrin sekali (dihadkan)
        if (System.currentTimeMillis() - lastShot > 1500) {
            lastShot = System.currentTimeMillis();
            try {
                s.takeScreenshot(android.view.Display.DEFAULT_DISPLAY, SHOT,
                        new AccessibilityService.TakeScreenshotCallback() {
                            @Override
                            public void onSuccess(AccessibilityService.ScreenshotResult r) {
                                Bitmap sw = null;
                                try {
                                    Bitmap hw = Bitmap.wrapHardwareBuffer(r.getHardwareBuffer(), r.getColorSpace());
                                    if (hw != null) sw = hw.copy(Bitmap.Config.ARGB_8888, false);
                                } catch (Throwable ignored) {
                                } finally {
                                    try { r.getHardwareBuffer().close(); } catch (Throwable ignored) {}
                                }
                                final Bitmap src = sw;
                                if (src == null) return;
                                {
                                    final LiquidGlass.Backdrop nb = new LiquidGlass.Backdrop();
                                    nb.set(src, s.screenW, s.screenH);
                                    s.h.post(() -> {
                                        bd.sharp = nb.sharp;
                                        bd.soft = nb.soft;
                                        bd.screenW = nb.screenW;
                                        bd.screenH = nb.screenH;
                                        bd.luminance = nb.luminance;
                                        // jangan lukis semula ketika sedang animasi keluar
                                        if (root != null && !hiding) LiquidGlass.invalidateTree(root);
                                    });
                                }
                            }

                            @Override
                            public void onFailure(int code) { }
                        });
            } catch (Throwable ignored) {
            }
        }
    }

    private boolean hiding;

    void hide() {
        final FrameLayout r = root;
        root = null;
        if (r == null) return;
        if (level != null) level.stop();
        hiding = true;
        View pill = r.getChildAt(0);
        pill.animate().cancel();
        pill.animate().translationX(dp(28)).alpha(0f).scaleY(0.96f)
                .setInterpolator(new android.view.animation.PathInterpolator(0.3f, 0f, 0.2f, 1f)).setDuration(260)
                .withLayer()
                .withEndAction(() -> {
                    hiding = false;
                    r.setVisibility(View.GONE);
                    // buang tetingkap pada bingkai seterusnya, bukan di tengah lukisan
                    s.h.post(() -> { try { s.wm.removeView(r); } catch (Exception ignored) {} });
                }).start();
    }

    /** Isi aras volume + ikon, boleh diseret. */
    private class Level extends View {
        float v, shown;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Icon ic = new Icon(s, Icon.SPEAKER, Color.WHITE);

        private final android.graphics.LinearGradient grad;

        Level() {
            super(s);
            grad = new android.graphics.LinearGradient(0, 0, 0, dp(190),
                    Color.WHITE, Color.argb(255, 228, 232, 240), android.graphics.Shader.TileMode.CLAMP);
        }

        private android.animation.ValueAnimator anim;

        void stop() { if (anim != null) anim.cancel(); }

        void animateTo(float t) {
            v = t;
            if (anim != null) anim.cancel();
            android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(shown, t);
            anim = a;
            a.setInterpolator(new android.view.animation.DecelerateInterpolator());
            a.setDuration(160);
            a.addUpdateListener(x -> {
                shown = (float) x.getAnimatedValue();
                invalidate();
            });
            a.start();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            float top = h * (1 - shown);
            p.setShader(grad);
            c.drawRect(0, top, w, h, p);
            p.setShader(null);
            int is = dp(22);
            ic.setKind(shown <= 0.001f ? Icon.CLOSE : Icon.SPEAKER);
            ic.layout(0, 0, is, is);
            ic.setColor(top < h - dp(16) - is ? Color.rgb(36, 36, 42) : Color.WHITE);
            c.save();
            c.translate((w - is) / 2f, h - is - dp(16));
            ic.draw(c);
            c.restore();
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            s.h.removeCallbacks(autoHide);
            float t = 1f - Math.max(0, Math.min(1, e.getY() / getHeight()));
            AudioManager am = am();
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(t * max), 0);
            v = shown = t;
            invalidate();
            if (e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                s.h.postDelayed(autoHide, 1600);
            }
            return true;
        }
    }
}
