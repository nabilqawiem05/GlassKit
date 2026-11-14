package com.glasskit.app;

import android.app.ActivityOptions;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Build;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Pratonton screenshot berkaca yang keluar dari Dynamic Island. Leret ke tepi untuk tutup. */
final class ScreenshotThumb {
    private final PillService s;
    private final Uri uri;
    private final Bitmap bmp;
    private FrameLayout root;
    private LinearLayout card;
    private final Runnable auto = () -> dismiss(true);

    ScreenshotThumb(PillService s, Uri uri, Bitmap bmp) {
        this.s = s;
        this.uri = uri;
        this.bmp = bmp;
    }

    private int dp(float v) { return Ui.dp(s, v); }

    void show() {
        root = new FrameLayout(s);
        card = new LinearLayout(s);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(8), dp(8), dp(8), dp(8));
        card.setBackground(GlassEngine.drawable(s, 22, true, 40));
        card.setElevation(dp(8));
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline o) { o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(22)); }
        });
        int w = Math.round(s.screenW * 0.3f);
        int h = bmp != null ? Math.round(w * (float) bmp.getHeight() / Math.max(1, bmp.getWidth())) : w * 2;
        h = Math.min(h, Math.round(s.screenH * 0.36f));
        ImageView iv = new ImageView(s);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        if (bmp != null) iv.setImageBitmap(bmp);
        iv.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline o) { o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(14)); }
        });
        iv.setClipToOutline(true);
        iv.setOnClickListener(v -> {
            open(new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
            dismiss(true);
        });
        card.addView(iv, new LinearLayout.LayoutParams(w, h));
        LinearLayout row = new LinearLayout(s);
        row.setPadding(0, dp(8), 0, 0);
        row.addView(btn(R.string.isl_share, v -> {
            Intent send = new Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            open(Intent.createChooser(send, null));
            dismiss(true);
        }), new LinearLayout.LayoutParams(0, dp(32), 1f));
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(0, dp(32), 1f);
        dl.leftMargin = dp(6);
        row.addView(btn(R.string.isl_delete, v -> {
            delete();
            dismiss(true);
        }), dl);
        card.addView(row, new LinearLayout.LayoutParams(w, -2));

        // leret ke tepi untuk tutup
        card.setOnTouchListener(new View.OnTouchListener() {
            float x0;
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN: x0 = e.getRawX(); s.h.removeCallbacks(auto); return true;
                    case MotionEvent.ACTION_MOVE: v.setTranslationX(e.getRawX() - x0); return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        float dx = e.getRawX() - x0;
                        if (Math.abs(dx) > dp(60)) {
                            v.animate().translationX(dx > 0 ? s.screenW : -s.screenW).alpha(0f).setDuration(220)
                                    .withEndAction(() -> dismiss(false)).start();
                        } else {
                            v.animate().translationX(0).setInterpolator(Ui.SPRING).setDuration(350).start();
                            s.h.postDelayed(auto, 6000);
                        }
                        return true;
                }
                return false;
            }
        });

        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        root.addView(card, cl);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = dp(16);
        lp.y = s.statusH + dp(56);
        lp.setTitle("GlassKitShot");
        try {
            s.wm.addView(root, lp);
        } catch (Exception e) {
            root = null;
            return;
        }
        // keluar dari island (atas tengah) ke kedudukannya
        card.setPivotX(0);
        card.setPivotY(0);
        card.setScaleX(0.15f);
        card.setScaleY(0.15f);
        card.setAlpha(0f);
        card.setTranslationX(s.screenW / 2f - dp(16) - w * 0.075f);
        card.setTranslationY(-dp(50));
        card.animate().scaleX(1f).scaleY(1f).alpha(1f).translationX(0).translationY(0)
                .setInterpolator(Ui.SPRING).setDuration(650).start();
        s.h.postDelayed(auto, 6000);
    }

    private TextView btn(int label, View.OnClickListener l) {
        TextView t = Ui.text(s, 12, 600, Color.WHITE);
        t.setText(label);
        t.setGravity(Gravity.CENTER);
        t.setBackground(Ui.round(Color.argb(50, 255, 255, 255), dp(16)));
        t.setOnClickListener(l);
        Ui.press(t);
        return t;
    }

    private void open(Intent i) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ActivityOptions o = ActivityOptions.makeBasic();
            if (Build.VERSION.SDK_INT >= 34) {
                o.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
            }
            s.startActivity(i, o.toBundle());
        } catch (Exception ignored) {
        }
    }

    private void delete() {
        if (Shell.granted()) {
            Shell.run("content delete --uri " + uri);
            return;
        }
        try {
            android.app.PendingIntent pi = android.provider.MediaStore.createDeleteRequest(s.getContentResolver(),
                    java.util.Collections.singletonList(uri));
            s.sendIntent(pi);
        } catch (Exception ignored) {
        }
    }

    void dismiss(boolean anim) {
        s.h.removeCallbacks(auto);
        final FrameLayout r = root;
        root = null;
        if (r == null) return;
        if (anim && card != null) {
            card.animate().alpha(0f).scaleX(0.85f).scaleY(0.85f).setDuration(200)
                    .withEndAction(() -> { try { s.wm.removeViewImmediate(r); } catch (Exception ignored) {} }).start();
        } else {
            try { s.wm.removeViewImmediate(r); } catch (Exception ignored) {}
        }
        if (s.extras != null && s.extras.thumb == this) s.extras.thumb = null;
    }
}
