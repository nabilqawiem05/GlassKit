package com.glasskit.app;

import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.service.notification.StatusBarNotification;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

/**
 * C · Cincin cahaya di keliling lubang kamera (bila island tidak digunakan):
 * - notifikasi belum dibaca: cincin lembut ikut warna app (berdenyut sekali bila tiba)
 * - pengecas dipasang: cincin hijau terisi ikut peratus bateri
 */
final class CameraRing {
    private final PillService s;
    private RingView v;
    private int pkgColor;
    private String pkg;
    private ValueAnimator anim;

    CameraRing(PillService s) { this.s = s; }

    private boolean on() { return s.prefs != null && s.prefs.getBoolean(Prefs.ISL_RING, true); }

    private void ensure(int cx, int cy, int rad) {
        if (v != null) return;
        v = new RingView(s, rad);
        int size = rad * 2 + Ui.dp(s, 22);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(size, size,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = cx - size / 2;
        lp.y = cy - size / 2;
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        lp.setTitle("GlassKitRing");
        try {
            s.wm.addView(v, lp);
        } catch (Exception e) {
            v = null;
        }
    }

    void remove() {
        if (anim != null) anim.cancel();
        if (v != null) {
            try { s.wm.removeViewImmediate(v); } catch (Exception ignored) {}
            v = null;
        }
    }

    /** Notifikasi baru: simpan warna app & denyut sekali. */
    void onNotification(StatusBarNotification sbn, boolean islandIdle, int cx, int cy, int rad) {
        if (!on() || sbn.isOngoing()) return;
        pkg = sbn.getPackageName();
        pkgColor = appColor(pkg);
        if (islandIdle) show(cx, cy, rad, true);
    }

    /** Island bebas: tunjuk cincin jika masih ada notifikasi app tersebut. */
    void update(boolean islandIdle, boolean screenOn, int cx, int cy, int rad) {
        boolean unread = pkg != null && hasUnread(pkg);
        if (!on() || !islandIdle || !screenOn || !unread) {
            if (v != null && (v.mode != RingView.CHARGE || !islandIdle || !screenOn)) remove();
            return;
        }
        show(cx, cy, rad, false);
    }

    private void show(int cx, int cy, int rad, boolean pulse) {
        ensure(cx, cy, rad);
        if (v == null) return;
        if (anim != null) anim.cancel();
        v.mode = RingView.GLOW;
        v.color = pkgColor;
        if (!pulse) {
            v.level = 0.55f;
            v.invalidate();
            return;
        }
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(1800);
        anim.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            // dua denyut kemudian tenang pada 55%
            float pulseV = (float) Math.abs(Math.sin(f * Math.PI * 2));
            v.level = 0.55f + 0.45f * pulseV * (1f - f * 0.6f);
            v.invalidate();
        });
        anim.start();
    }

    void onCharge(int level, int cx, int cy, int rad, boolean islandIdle) {
        if (!on() || !islandIdle) return;
        ensure(cx, cy, rad);
        if (v == null) return;
        if (anim != null) anim.cancel();
        v.mode = RingView.CHARGE;
        v.color = level <= 20 ? 0xFFFF453A : 0xFF30D158;
        final float target = Math.max(0.03f, level / 100f);
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(3200);
        anim.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            float grow = Math.min(1f, f / 0.35f);
            grow = 1f - (1f - grow) * (1f - grow);
            v.sweep = target * grow;
            v.level = f < 0.8f ? 1f : (1f - (f - 0.8f) / 0.2f);
            v.invalidate();
        });
        anim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator a) {
                if (v != null && v.mode == RingView.CHARGE) remove();
            }
        });
        anim.start();
    }

    private static boolean hasUnread(String pkg) {
        NotifListener l = NotifListener.inst;
        if (l == null) return false;
        try {
            for (StatusBarNotification x : l.getActiveNotifications()) {
                if (x.getPackageName().equals(pkg) && !x.isOngoing()) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private int appColor(String p) {
        switch (p) {
            case "com.whatsapp": case "com.whatsapp.w4b": return 0xFF25D366;
            case "org.telegram.messenger": return 0xFF2AABEE;
            case "com.instagram.android": return 0xFFE1306C;
            case "com.facebook.orca": return 0xFF0A7CFF;
            case "com.google.android.gm": return 0xFFEA4335;
            case "com.zhiliaoapp.musically": return 0xFFFE2C55;
        }
        try {
            Drawable d = s.getPackageManager().getApplicationIcon(p);
            Bitmap b = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            d.setBounds(0, 0, 24, 24);
            d.draw(c);
            float best = -1;
            int bestC = Color.WHITE;
            float[] hsv = new float[3];
            for (int y = 2; y < 22; y += 2) {
                for (int x = 2; x < 22; x += 2) {
                    int px = b.getPixel(x, y);
                    if (Color.alpha(px) < 200) continue;
                    Color.colorToHSV(px, hsv);
                    float score = hsv[1] * hsv[2];
                    if (score > best) {
                        best = score;
                        bestC = px;
                    }
                }
            }
            return best < 0.15f ? Color.WHITE : bestC | 0xFF000000;
        } catch (Throwable t) {
            return Color.WHITE;
        }
    }

    private static final class RingView extends View {
        static final int GLOW = 0, CHARGE = 1;
        int mode, color = Color.WHITE;
        float level, sweep;
        final int rad;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF r = new RectF();

        RingView(PillService s, int rad) {
            super(s);
            this.rad = rad;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
        }

        @Override
        protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f, d = getResources().getDisplayMetrics().density;
            float rr = rad + 3.5f * d;
            r.set(cx - rr, cy - rr, cx + rr, cy + rr);
            int a = Math.round(255 * Math.max(0f, Math.min(1f, level)));
            // halo lembut
            p.setStrokeWidth(7 * d);
            p.setColor((color & 0x00FFFFFF) | (Math.round(a * 0.22f) << 24));
            c.drawCircle(cx, cy, rr + 1.5f * d, p);
            p.setStrokeWidth(2.6f * d);
            if (mode == CHARGE) {
                p.setColor(Color.argb(Math.round(a * 0.25f), 255, 255, 255));
                c.drawCircle(cx, cy, rr, p);
                p.setColor((color & 0x00FFFFFF) | (a << 24));
                c.drawArc(r, -90, 360 * sweep, false, p);
            } else {
                p.setColor((color & 0x00FFFFFF) | (a << 24));
                c.drawCircle(cx, cy, rr, p);
            }
        }
    }
}
