package com.glasskit.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * Penutup segera untuk lock screen stock. Dipasang ketika skrin dipadam, jadi bila skrin dihidupkan
 * yang kelihatan terus ialah wallpaper + jam GlassKit, bukan lock screen stock, walaupun LockActivity
 * mengambil masa untuk muncul. Ditanggalkan sebaik LockActivity siap dilukis.
 * Tidak memintas keselamatan: lock screen stock masih ada di bawahnya; jika ada app lain di atas
 * lock screen (penggera, panggilan, kamera) penutup ditanggalkan serta-merta.
 */
final class LockCover {
    private final PillService s;
    private FrameLayout root;
    private ImageView img;
    private boolean shown;
    private long shownAt;
    private final Runnable timeout = new Runnable() {
        @Override
        public void run() {
            // jangan sekali-kali tersekat: selepas 6 s tanpa LockActivity, serah kepada pengawas
            if (shown && !LockActivity.visible) hide();
        }
    };

    LockCover(PillService s) { this.s = s; }

    boolean shown() { return shown; }

    private int dp(float v) { return Ui.dp(s, v); }

    private Rect size() {
        try {
            return s.wm.getMaximumWindowMetrics().getBounds();
        } catch (Exception e) {
            return new Rect(0, 0, s.screenW, s.screenH);
        }
    }

    /** Dipanggil ketika skrin dipadam. */
    void arm() {
        if (root == null) build();
        if (root == null) return;
        Bitmap f = LockActivity.frameFor(s);
        if (f != null) img.setImageBitmap(f);
        // lukis bingkai segar di latar (skrin padam, jadi tiada tergesa-gesa)
        LockActivity.renderInBackground(s, b -> s.h.post(() -> { if (root != null && b != null) img.setImageBitmap(b); }));
        root.setVisibility(View.VISIBLE);
        root.setAlpha(1f);
        shown = true;
        shownAt = System.currentTimeMillis();
    }

    /** Dipanggil ketika skrin dihidupkan. */
    void onScreenOn() {
        if (!shown) return;
        if (s.alarmActive()) {
            hide(); // penggera berbunyi: jangan tutup skrin penggera
            return;
        }
        s.h.removeCallbacks(timeout);
        s.h.postDelayed(timeout, 6000);
        if (LockActivity.visible) s.h.postDelayed(this::hide, 180);
    }

    void hide() {
        s.h.removeCallbacks(timeout);
        if (!shown || root == null) return;
        shown = false;
        root.setVisibility(View.GONE);
    }

    void destroy() {
        hide();
        if (root != null) {
            try { s.wm.removeViewImmediate(root); } catch (Exception ignored) {}
            root = null;
        }
    }

    private void build() {
        root = new FrameLayout(s);
        root.setBackgroundColor(Color.BLACK);
        img = new ImageView(s);
        img.setScaleType(ImageView.ScaleType.FIT_XY);
        root.addView(img, new FrameLayout.LayoutParams(-1, -1));
        TextView hint = Ui.text(s, 13, 600, Color.argb(200, 255, 255, 255));
        hint.setText(R.string.swipe_open);
        hint.setShadowLayer(dp(4), 0, dp(1), Color.argb(120, 0, 0, 0));
        FrameLayout.LayoutParams hl = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        hl.bottomMargin = dp(40);
        root.addView(hint, hl);
        root.setOnTouchListener(new View.OnTouchListener() {
            float y0;
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) y0 = e.getRawY();
                if (e.getActionMasked() == MotionEvent.ACTION_UP && y0 - e.getRawY() > dp(80)) {
                    hide();
                    if (!LockActivity.visible) {
                        if (Shell.granted()) Shell.run("wm dismiss-keyguard");
                        else s.showLock();
                    }
                }
                return true;
            }
        });
        Rect r = size();
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(r.width(), r.height(),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.OPAQUE);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        lp.setTitle("GlassKitLockCover");
        root.setVisibility(View.GONE);
        try {
            s.wm.addView(root, lp);
        } catch (Exception e) {
            root = null;
        }
    }
}
