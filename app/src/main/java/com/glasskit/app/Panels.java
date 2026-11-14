package com.glasskit.app;

import android.accessibilityservice.AccessibilityService;
import android.animation.ValueAnimator;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.os.PowerManager;
import android.provider.MediaStore;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Pusat Kawalan & Pusat Pemberitahuan kaca, serta zon leret di bahagian atas skrin. */
final class Panels {
    private static final int NONE = 0, CONTROL = 1, NOTIFS = 2;

    private final PillService s;
    private final WindowManager wm;

    private View edgeL, edgeR;
    private PanelRoot root;
    private WindowManager.LayoutParams plp;
    private ColorDrawable dim;
    private View content;
    private ScrollView scroll;
    private int open = NONE;
    private boolean closing;
    private ValueAnimator anim;
    private boolean blurOk;

    // Pusat Kawalan
    private Tg tWifi, tBt, tData, tPlane, tTorch, tDnd, tDark, tRotate, tSaver;
    private Widgets.VSlider sBright, sVol;
    private ImageView ccArt;
    private TextView ccTitle, ccArtist;
    private Icon ccPlay;
    private boolean torchOn;
    private String torchId;
    private CameraManager.TorchCallback torchCb;

    // Pusat Pemberitahuan
    private LinearLayout ncList;

    Panels(PillService s) {
        this.s = s;
        this.wm = s.wm;
    }

    private int dp(float v) { return Ui.dp(s, v); }

    boolean isOpen() { return open != NONE; }

    void destroy() {
        removeEdges();
        if (root != null) {
            try { wm.removeViewImmediate(root); } catch (Exception ignored) {}
            root = null;
        }
        open = NONE;
    }

    // ===================== Zon leret =====================

    void updateEdges() {
        boolean want = s.prefs.getBoolean(Prefs.PANELS_ON, true)
                && s.getResources().getConfiguration().orientation != Configuration.ORIENTATION_LANDSCAPE;
        removeEdges();
        if (!want) return;
        edgeL = addEdge(true);
        edgeR = addEdge(false);
    }

    private void removeEdges() {
        for (View v : new View[]{edgeL, edgeR}) {
            if (v != null) {
                try { wm.removeViewImmediate(v); } catch (Exception ignored) {}
            }
        }
        edgeL = edgeR = null;
    }

    private View addEdge(final boolean left) {
        View v = new View(s);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                Math.round(s.screenW * 0.36f), s.statusH,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | (left ? Gravity.LEFT : Gravity.RIGHT);
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        lp.setTitle(left ? "GlassKitEdgeL" : "GlassKitEdgeR");
        final float[] y0 = new float[1];
        final boolean[] fired = new boolean[1];
        v.setOnTouchListener((view, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    y0[0] = e.getRawY();
                    fired[0] = false;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (!fired[0] && e.getRawY() - y0[0] > dp(14)) {
                        fired[0] = true;
                        if (left) openNotifications(); else openControl();
                    }
                    break;
            }
            return true;
        });
        try {
            wm.addView(v, lp);
            return v;
        } catch (Exception e) {
            return null;
        }
    }

    // ===================== Tetingkap panel =====================

    private class PanelRoot extends FrameLayout {
        private float x0, y0;

        PanelRoot(Context c) {
            super(c);
            setFocusableInTouchMode(true);
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent e) {
            if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                if (e.getAction() == KeyEvent.ACTION_UP) close();
                return true;
            }
            return super.dispatchKeyEvent(e);
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    x0 = e.getRawX();
                    y0 = e.getRawY();
                    break;
                case MotionEvent.ACTION_MOVE:
                    float dy = e.getRawY() - y0, dx = e.getRawX() - x0;
                    if (dy < -dp(70) && Math.abs(dy) > Math.abs(dx) * 1.5f
                            && (scroll == null || !scroll.canScrollVertically(1))) {
                        close();
                        return true;
                    }
                    break;
            }
            return false;
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (e.getActionMasked() == MotionEvent.ACTION_UP) close();
            return true;
        }
    }

    private void show(int which, View c, ScrollView sv) {
        if (root != null) {
            try { wm.removeViewImmediate(root); } catch (Exception ignored) {}
            root = null;
        }
        if (anim != null) anim.cancel();
        open = which;
        closing = false;
        content = c;
        scroll = sv;
        s.onPanelChanged();

        blurOk = wm.isCrossWindowBlurEnabled();
        root = new PanelRoot(s);
        dim = new ColorDrawable(blurOk ? Color.argb(70, 0, 0, 0) : Color.argb(225, 14, 14, 18));
        dim.setAlpha(0);
        root.setBackground(dim);
        root.addView(c, new FrameLayout.LayoutParams(-1, -1));

        plp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        plp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        plp.setTitle("GlassKitPanel");
        if (blurOk) {
            plp.flags |= WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
            plp.setBlurBehindRadius(1);
        }
        try {
            wm.addView(root, plp);
        } catch (Exception e) {
            root = null;
            open = NONE;
            return;
        }
        root.requestFocus();

        c.setAlpha(0f);
        c.setTranslationY(-dp(36));
        c.setScaleX(0.94f);
        c.setScaleY(0.94f);
        final int maxBlur = dp(34);
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(420);
        anim.setInterpolator(new DecelerateInterpolator(2.4f));
        anim.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            dim.setAlpha(Math.round(255 * f));
            if (blurOk && root != null) {
                plp.setBlurBehindRadius(Math.max(1, Math.round(maxBlur * f)));
                try { wm.updateViewLayout(root, plp); } catch (Exception ignored) {}
            }
        });
        anim.start();
        c.animate().alpha(1f).translationY(0).scaleX(1f).scaleY(1f)
                .setInterpolator(Ui.SPRING).setDuration(620).start();
    }

    void close() {
        if (root == null || closing) return;
        closing = true;
        final PanelRoot r = root;
        if (anim != null) anim.cancel();
        final int from = blurOk ? dp(34) : 0;
        anim = ValueAnimator.ofFloat(1f, 0f);
        anim.setDuration(260);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            dim.setAlpha(Math.round(255 * f));
            if (blurOk) {
                plp.setBlurBehindRadius(Math.max(1, Math.round(from * f)));
                try { wm.updateViewLayout(r, plp); } catch (Exception ignored) {}
            }
        });
        anim.start();
        content.animate().alpha(0f).translationY(-dp(40)).scaleX(0.95f).scaleY(0.95f)
                .setDuration(240).withEndAction(() -> {
                    try { wm.removeViewImmediate(r); } catch (Exception ignored) {}
                    if (root == r) {
                        root = null;
                        open = NONE;
                        closing = false;
                        stopTorchWatch();
                        s.onPanelChanged();
                    }
                }).start();
    }

    private void launch(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { s.startActivity(i); } catch (Exception ignored) {}
        close();
    }

    // ===================== Togol bulat =====================

    private class Tg {
        final FrameLayout v;
        final GradientDrawable bg;
        final Icon ic;
        final int onBg, onIcon;

        Tg(int kind, int onBg, int onIcon, String label, int size, View.OnClickListener l) {
            this.onBg = onBg;
            this.onIcon = onIcon;
            v = new FrameLayout(s);
            bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            bg.setStroke(Math.max(1, dp(0.8f)), Color.argb(60, 255, 255, 255));
            v.setBackground(bg);
            ic = new Icon(s, kind, Color.WHITE);
            int is = Math.round(size * 0.42f);
            v.addView(ic, new FrameLayout.LayoutParams(is, is, Gravity.CENTER));
            v.setOnClickListener(l);
            v.setOnLongClickListener(x -> {
                Toast.makeText(s, label, Toast.LENGTH_SHORT).show();
                return true;
            });
            Ui.press(v);
            set(false);
        }

        void set(boolean on) {
            bg.setColor(on ? onBg : Color.argb(52, 255, 255, 255));
            ic.setColor(on ? onIcon : Color.WHITE);
        }
    }

    // ===================== Pusat Kawalan =====================

    void openControl() {
        if (open == CONTROL) return;
        int m = dp(22), gap = dp(16);
        int w = Math.min(s.screenW, dp(440)) - 2 * m;
        int sq = (w - gap) / 2;
        int small = (sq - gap) / 2;

        LinearLayout col = new LinearLayout(s);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(m, s.statusH + dp(48), m, dp(24));
        col.setClickable(true);
        col.setOnClickListener(v -> close());

        // Baris 1: rangkaian + media
        LinearLayout r1 = row(w);
        FrameLayout conn = block(sq, sq);
        int pad = dp(14), c = (sq - 3 * pad) / 2;
        tWifi = new Tg(Icon.WIFI, Ui.BLUE, Color.WHITE, s.getString(R.string.tg_wifi), c, v -> toggleWifi());
        tBt = new Tg(Icon.BT, Ui.BLUE, Color.WHITE, s.getString(R.string.tg_bt), c, v -> toggleBt());
        tData = new Tg(Icon.CELL, Ui.GREEN, Color.WHITE, s.getString(R.string.tg_data), c, v -> toggleData());
        tPlane = new Tg(Icon.PLANE, Ui.ORANGE, Color.WHITE, s.getString(R.string.tg_plane), c, v -> togglePlane());
        place(conn, tPlane.v, pad, pad, c);
        place(conn, tData.v, pad * 2 + c, pad, c);
        place(conn, tWifi.v, pad, pad * 2 + c, c);
        place(conn, tBt.v, pad * 2 + c, pad * 2 + c, c);
        r1.addView(conn, lpw(sq, sq, 0));
        r1.addView(mediaBlock(sq), lpw(sq, sq, gap));
        col.addView(r1);

        // Baris 2: 4 togol + 2 slider
        LinearLayout r2 = row(w);
        FrameLayout grid = new FrameLayout(s);
        tTorch = new Tg(Icon.TORCH, Color.WHITE, Color.rgb(28, 28, 30), s.getString(R.string.tg_torch), small, v -> toggleTorch());
        tDnd = new Tg(Icon.MOON, Ui.PURPLE, Color.WHITE, s.getString(R.string.tg_dnd), small, v -> toggleDnd());
        tDark = new Tg(Icon.DARK, Color.WHITE, Color.rgb(28, 28, 30), s.getString(R.string.tg_dark), small, v -> toggleDark());
        tRotate = new Tg(Icon.ROTATE, Color.WHITE, 0xFFFF453A, s.getString(R.string.tg_rotate), small, v -> toggleRotate());
        place(grid, tTorch.v, 0, 0, small);
        place(grid, tDnd.v, small + gap, 0, small);
        place(grid, tDark.v, 0, small + gap, small);
        place(grid, tRotate.v, small + gap, small + gap, small);
        r2.addView(grid, lpw(sq, sq, 0));
        sBright = new Widgets.VSlider(s, Icon.SUN);
        sVol = new Widgets.VSlider(s, Icon.SPEAKER);
        sBright.setCb((v, done) -> setBrightness(v, done));
        sVol.setCb((v, done) -> setVolume(v));
        r2.addView(sBright, lpw(small, sq, gap));
        r2.addView(sVol, lpw(small, sq, gap));
        LinearLayout.LayoutParams r2l = (LinearLayout.LayoutParams) r2.getLayoutParams();
        r2l.topMargin = gap;
        col.addView(r2);

        // Baris 3: tindakan pantas
        LinearLayout r3 = row(w);
        tSaver = new Tg(Icon.BATTERY, Color.WHITE, 0xFFFFB800, s.getString(R.string.tg_saver), small, v -> toggleSaver());
        Tg shot = new Tg(Icon.SHOT, Color.WHITE, Color.BLACK, s.getString(R.string.tg_shot), small, v -> {
            close();
            s.h.postDelayed(() -> s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT), 520);
        });
        Tg lock = new Tg(Icon.LOCK, Color.WHITE, Color.BLACK, s.getString(R.string.tg_lock), small, v -> {
            close();
            s.h.postDelayed(() -> s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN), 300);
        });
        Tg cam = new Tg(Icon.CAMERA, Color.WHITE, Color.BLACK, s.getString(R.string.tg_camera), small,
                v -> launch(new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)));
        r3.addView(tSaver.v, lpw(small, small, 0));
        r3.addView(shot.v, lpw(small, small, gap));
        r3.addView(lock.v, lpw(small, small, gap));
        r3.addView(cam.v, lpw(small, small, gap));
        ((LinearLayout.LayoutParams) r3.getLayoutParams()).topMargin = gap;
        col.addView(r3);

        // Baris 4: pautan
        LinearLayout r4 = row(w);
        r4.addView(chip(Icon.GEAR, s.getString(R.string.system_panel), v -> {
            close();
            s.h.postDelayed(() -> s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS), 300);
        }), new LinearLayout.LayoutParams(0, dp(46), 1f));
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(0, dp(46), 1f);
        gl.leftMargin = gap;
        r4.addView(chip(Icon.GEAR, s.getString(R.string.cc_settings), v -> {
            Intent i = new Intent(s, MainActivity.class);
            launch(i);
        }), gl);
        ((LinearLayout.LayoutParams) r4.getLayoutParams()).topMargin = dp(22);
        col.addView(r4);

        show(CONTROL, col, null);
        refreshControl();
        startTorchWatch();
    }

    private LinearLayout row(int w) {
        LinearLayout r = new LinearLayout(s);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setLayoutParams(new LinearLayout.LayoutParams(w, -2));
        return r;
    }

    private LinearLayout.LayoutParams lpw(int w, int h, int left) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.leftMargin = left;
        return p;
    }

    private FrameLayout block(int w, int h) {
        FrameLayout f = new FrameLayout(s);
        f.setBackground(Ui.glass(s, 34, 34));
        f.setClickable(true);
        return f;
    }

    private void place(FrameLayout parent, View v, int x, int y, int size) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(size, size);
        p.leftMargin = x;
        p.topMargin = y;
        parent.addView(v, p);
    }

    private View chip(int icon, String label, View.OnClickListener l) {
        LinearLayout c = new LinearLayout(s);
        c.setGravity(Gravity.CENTER);
        c.setBackground(Ui.glass(s, 23, 34));
        Icon ic = new Icon(s, icon, Color.WHITE);
        c.addView(ic, new LinearLayout.LayoutParams(dp(16), dp(16)));
        TextView t = Ui.line(s, 13, 600, Color.WHITE);
        t.setText(label);
        t.setPadding(dp(8), 0, 0, 0);
        c.addView(t);
        c.setOnClickListener(l);
        Ui.press(c);
        return c;
    }

    private View mediaBlock(int sq) {
        LinearLayout b = new LinearLayout(s);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setBackground(Ui.glass(s, 34, 34));
        b.setPadding(dp(16), dp(16), dp(16), dp(10));
        b.setClickable(true);
        b.setOnClickListener(v -> {
            if (s.mc != null) {
                s.sendIntent(s.mc.getSessionActivity());
                close();
            }
        });
        ccArt = new ImageView(s);
        ccArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        ccArt.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline o) {
                o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(11));
            }
        });
        ccArt.setClipToOutline(true);
        b.addView(ccArt, new LinearLayout.LayoutParams(dp(46), dp(46)));
        ccTitle = Ui.line(s, 14, 600, Color.WHITE);
        ccTitle.setPadding(0, dp(10), 0, 0);
        ccArtist = Ui.line(s, 13, 400, Color.argb(160, 235, 235, 245));
        ccArtist.setPadding(0, dp(3), 0, 0);
        b.addView(ccTitle);
        b.addView(ccArtist);
        View spacer = new View(s);
        b.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));
        LinearLayout ctr = new LinearLayout(s);
        ctr.setGravity(Gravity.CENTER_VERTICAL);
        ctr.addView(mediaBtn(Icon.PREV, 20, v -> { if (s.mc != null) s.mc.getTransportControls().skipToPrevious(); }),
                new LinearLayout.LayoutParams(0, dp(40), 1f));
        FrameLayout pb = mediaBtn(Icon.PLAY, 26, v -> s.togglePlay());
        ccPlay = (Icon) pb.getChildAt(0);
        ctr.addView(pb, new LinearLayout.LayoutParams(0, dp(40), 1f));
        ctr.addView(mediaBtn(Icon.NEXT, 20, v -> { if (s.mc != null) s.mc.getTransportControls().skipToNext(); }),
                new LinearLayout.LayoutParams(0, dp(40), 1f));
        b.addView(ctr, new LinearLayout.LayoutParams(-1, -2));
        updateMedia();
        return b;
    }

    private FrameLayout mediaBtn(int kind, int size, View.OnClickListener l) {
        FrameLayout f = new FrameLayout(s);
        f.addView(new Icon(s, kind, Color.WHITE), new FrameLayout.LayoutParams(dp(size), dp(size), Gravity.CENTER));
        f.setOnClickListener(l);
        Ui.press(f);
        return f;
    }

    void onMediaChanged() {
        if (open == CONTROL) updateMedia();
    }

    private void updateMedia() {
        if (ccTitle == null) return;
        boolean has = s.mc != null && (s.title != null || s.playing);
        ccTitle.setText(has ? (s.title != null ? s.title : s.getString(R.string.no_title)) : s.getString(R.string.not_playing));
        ccArtist.setText(has && s.artist != null ? s.artist : "");
        if (s.art != null) {
            ccArt.setImageBitmap(s.art);
            ccArt.setBackground(null);
        } else {
            ccArt.setImageDrawable(null);
            ccArt.setBackground(Ui.round(Color.argb(60, 255, 255, 255), dp(11)));
        }
        ccPlay.setKind(s.playing ? Icon.PAUSE : Icon.PLAY);
    }

    // ---- keadaan ----

    private boolean global(String key) {
        return Settings.Global.getInt(s.getContentResolver(), key, 0) != 0;
    }

    private void refreshControl() {
        if (open != CONTROL || tWifi == null) return;
        tWifi.set(global("wifi_on"));
        tBt.set(global("bluetooth_on"));
        tData.set(global("mobile_data"));
        tPlane.set(global(Settings.Global.AIRPLANE_MODE_ON));
        tTorch.set(torchOn);
        NotificationManager nm = s.getSystemService(NotificationManager.class);
        tDnd.set(nm.getCurrentInterruptionFilter() > NotificationManager.INTERRUPTION_FILTER_ALL);
        tDark.set(isDark());
        tRotate.set(Settings.System.getInt(s.getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 1) == 0);
        tSaver.set(s.getSystemService(PowerManager.class).isPowerSaveMode());
        int br = Settings.System.getInt(s.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, 128);
        sBright.setValue(br / 255f);
        AudioManager am = s.getSystemService(AudioManager.class);
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        sVol.setValue(max > 0 ? (float) am.getStreamVolume(AudioManager.STREAM_MUSIC) / max : 0);
        updateMedia();
    }

    private void later() {
        s.h.postDelayed(this::refreshControl, 700);
        s.h.postDelayed(this::refreshControl, 1800);
    }

    private boolean isDark() {
        return (Resources.getSystem().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    /** Jalankan dengan Shizuku; jika tiada, buka tetapan yang berkaitan. */
    private void shellOr(String cmd, Intent fallback) {
        if (Shell.granted()) {
            Shell.run(cmd, (ok, out) -> later());
        } else if (fallback != null) {
            launch(fallback);
        } else {
            Toast.makeText(s, R.string.need_shizuku, Toast.LENGTH_SHORT).show();
        }
    }

    private void toggleWifi() {
        boolean on = global("wifi_on");
        tWifi.set(!on);
        shellOr("svc wifi " + (on ? "disable" : "enable"), new Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY));
    }

    private void toggleBt() {
        boolean on = global("bluetooth_on");
        tBt.set(!on);
        String a = on ? "disable" : "enable";
        shellOr("cmd bluetooth_manager " + a + " || svc bluetooth " + a, new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
    }

    private void toggleData() {
        boolean on = global("mobile_data");
        tData.set(!on);
        shellOr("svc data " + (on ? "disable" : "enable"), new Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY));
    }

    private void togglePlane() {
        boolean on = global(Settings.Global.AIRPLANE_MODE_ON);
        tPlane.set(!on);
        shellOr("cmd connectivity airplane-mode " + (on ? "disable" : "enable"),
                new Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS));
    }

    private void toggleDnd() {
        NotificationManager nm = s.getSystemService(NotificationManager.class);
        boolean on = nm.getCurrentInterruptionFilter() > NotificationManager.INTERRUPTION_FILTER_ALL;
        tDnd.set(!on);
        if (nm.isNotificationPolicyAccessGranted()) {
            nm.setInterruptionFilter(on ? NotificationManager.INTERRUPTION_FILTER_ALL
                    : NotificationManager.INTERRUPTION_FILTER_PRIORITY);
            later();
        } else {
            shellOr("cmd notification set_dnd " + (on ? "off" : "on"), null);
        }
    }

    private void toggleDark() {
        boolean on = isDark();
        tDark.set(!on);
        shellOr("cmd uimode night " + (on ? "no" : "yes"), new Intent(Settings.ACTION_DISPLAY_SETTINGS));
    }

    private void toggleRotate() {
        boolean locked = Settings.System.getInt(s.getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 1) == 0;
        tRotate.set(!locked);
        if (Settings.System.canWrite(s)) {
            Settings.System.putInt(s.getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, locked ? 1 : 0);
            later();
        } else {
            shellOr("settings put system accelerometer_rotation " + (locked ? 1 : 0), null);
        }
    }

    private void toggleSaver() {
        boolean on = s.getSystemService(PowerManager.class).isPowerSaveMode();
        tSaver.set(!on);
        shellOr("cmd power set-mode " + (on ? 0 : 1), new Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS));
    }

    private void toggleTorch() {
        if (torchId == null) return;
        try {
            s.getSystemService(CameraManager.class).setTorchMode(torchId, !torchOn);
        } catch (Exception ignored) {
        }
    }

    private void startTorchWatch() {
        try {
            CameraManager cm = s.getSystemService(CameraManager.class);
            if (torchId == null) {
                for (String id : cm.getCameraIdList()) {
                    CameraCharacteristics ch = cm.getCameraCharacteristics(id);
                    Boolean f = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                    Integer face = ch.get(CameraCharacteristics.LENS_FACING);
                    if (Boolean.TRUE.equals(f) && face != null && face == CameraCharacteristics.LENS_FACING_BACK) {
                        torchId = id;
                        break;
                    }
                }
            }
            if (torchCb == null) {
                torchCb = new CameraManager.TorchCallback() {
                    @Override
                    public void onTorchModeChanged(String cameraId, boolean enabled) {
                        if (cameraId.equals(torchId)) {
                            torchOn = enabled;
                            if (tTorch != null) tTorch.set(enabled);
                        }
                    }
                };
                cm.registerTorchCallback(torchCb, s.h);
            }
        } catch (Exception ignored) {
        }
    }

    private void stopTorchWatch() {
        if (torchCb != null) {
            try { s.getSystemService(CameraManager.class).unregisterTorchCallback(torchCb); } catch (Exception ignored) {}
            torchCb = null;
        }
    }

    private boolean askedWrite;

    private void setBrightness(float v, boolean done) {
        int val = Math.max(1, Math.round(v * 255));
        if (Settings.System.canWrite(s)) {
            Settings.System.putInt(s.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
            Settings.System.putInt(s.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, val);
        } else if (done && Shell.granted()) {
            String pkg = s.getPackageName();
            Shell.run((askedWrite ? "" : "appops set " + pkg + " WRITE_SETTINGS allow; ")
                    + "settings put system screen_brightness_mode 0; settings put system screen_brightness " + val);
            askedWrite = true;
        } else if (done) {
            Toast.makeText(s, R.string.need_shizuku, Toast.LENGTH_SHORT).show();
        }
    }

    private void setVolume(float v) {
        AudioManager am = s.getSystemService(AudioManager.class);
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        try { am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(v * max), 0); } catch (Exception ignored) {}
    }

    // ===================== Pusat Pemberitahuan =====================

    void openNotifications() {
        if (open == NOTIFS) return;
        int m = dp(14);
        ScrollView sv = new ScrollView(s);
        sv.setVerticalScrollBarEnabled(false);
        sv.setOverScrollMode(View.OVER_SCROLL_ALWAYS);
        sv.setClipToPadding(false);
        LinearLayout col = new LinearLayout(s);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(m, s.statusH + dp(30), m, dp(60));
        col.setClickable(true);
        col.setOnClickListener(v -> close());
        sv.addView(col, new ScrollView.LayoutParams(-1, -2));

        Date now = new Date();
        boolean is24 = android.text.format.DateFormat.is24HourFormat(s);
        TextView time = Ui.text(s, 84, 600, Color.WHITE);
        time.setText(new SimpleDateFormat(is24 ? "HH:mm" : "h:mm", Locale.getDefault()).format(now));
        time.setGravity(Gravity.CENTER);
        time.setLetterSpacing(-0.03f);
        TextView date = Ui.text(s, 18, 500, Color.argb(220, 255, 255, 255));
        date.setText(new SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(now));
        date.setGravity(Gravity.CENTER);
        date.setPadding(0, 0, 0, dp(6));
        col.addView(date, new LinearLayout.LayoutParams(-1, -2));
        col.addView(time, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout head = new LinearLayout(s);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(6), dp(30), 0, dp(12));
        TextView ht = Ui.text(s, 20, 700, Color.WHITE);
        ht.setText(R.string.notification_center);
        head.addView(ht, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView clear = Ui.text(s, 13, 600, Color.WHITE);
        clear.setText(R.string.clear_all);
        clear.setPadding(dp(14), dp(8), dp(14), dp(8));
        clear.setBackground(Ui.glass(s, 16, 40));
        clear.setOnClickListener(v -> {
            NotifListener l = NotifListener.inst;
            if (l != null) {
                try { l.cancelAllNotifications(); } catch (Exception ignored) {}
            }
            s.h.postDelayed(this::fillNotifications, 350);
        });
        Ui.press(clear);
        head.addView(clear);
        col.addView(head);

        ncList = new LinearLayout(s);
        ncList.setOrientation(LinearLayout.VERTICAL);
        col.addView(ncList, new LinearLayout.LayoutParams(-1, -2));
        fillNotifications();
        show(NOTIFS, sv, sv);
    }

    void onNotificationsChanged() {
        if (open == NOTIFS) s.h.post(this::fillNotifications);
    }

    private void fillNotifications() {
        if (ncList == null) return;
        ncList.removeAllViews();
        NotifListener l = NotifListener.inst;
        List<StatusBarNotification> list = new ArrayList<>();
        if (l != null) {
            try {
                StatusBarNotification[] arr = l.getActiveNotifications();
                if (arr != null) list.addAll(Arrays.asList(arr));
            } catch (Exception ignored) {
            }
        }
        list.removeIf(n -> n.isOngoing()
                || (n.getNotification().flags & Notification.FLAG_GROUP_SUMMARY) != 0
                || n.getPackageName().equals(s.getPackageName()));
        list.sort((a, b) -> Long.compare(b.getPostTime(), a.getPostTime()));

        if (list.isEmpty()) {
            TextView e = Ui.text(s, 15, 500, Color.argb(160, 255, 255, 255));
            e.setText(R.string.no_notifications);
            e.setGravity(Gravity.CENTER);
            e.setPadding(0, dp(40), 0, dp(40));
            ncList.addView(e, new LinearLayout.LayoutParams(-1, -2));
            return;
        }
        int count = 0;
        for (StatusBarNotification sbn : list) {
            if (++count > 40) break;
            View card = card(sbn);
            if (card == null) continue;
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
            p.bottomMargin = dp(10);
            ncList.addView(card, p);
        }
    }

    private String appName(String pkg) {
        try {
            PackageManager pm = s.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            return pm.getApplicationLabel(ai).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    private View card(final StatusBarNotification sbn) {
        Notification n = sbn.getNotification();
        CharSequence title = n.extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence text = n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
        if (TextUtils.isEmpty(text)) text = n.extras.getCharSequence(Notification.EXTRA_TEXT);
        if (TextUtils.isEmpty(title) && TextUtils.isEmpty(text)) return null;

        LinearLayout c = new LinearLayout(s);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(Ui.glass(s, 24, 40));
        c.setPadding(dp(14), dp(12), dp(14), dp(14));

        LinearLayout top = new LinearLayout(s);
        top.setGravity(Gravity.CENTER_VERTICAL);
        ImageView ic = new ImageView(s);
        Drawable d = s.appIcon(sbn.getPackageName());
        ic.setImageDrawable(d);
        top.addView(ic, new LinearLayout.LayoutParams(dp(20), dp(20)));
        TextView app = Ui.line(s, 13, 500, Color.argb(190, 255, 255, 255));
        app.setText(appName(sbn.getPackageName()));
        app.setPadding(dp(8), 0, dp(8), 0);
        top.addView(app, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView when = Ui.text(s, 12, 500, Color.argb(150, 255, 255, 255));
        long age = System.currentTimeMillis() - sbn.getPostTime();
        when.setText(age < 60_000 ? s.getString(R.string.now)
                : DateUtils.getRelativeTimeSpanString(sbn.getPostTime(), System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE));
        top.addView(when);
        c.addView(top);

        LinearLayout body = new LinearLayout(s);
        body.setGravity(Gravity.CENTER_VERTICAL);
        body.setPadding(0, dp(8), 0, 0);
        LinearLayout tc = new LinearLayout(s);
        tc.setOrientation(LinearLayout.VERTICAL);
        if (!TextUtils.isEmpty(title)) {
            TextView t = Ui.line(s, 15, 600, Color.WHITE);
            t.setText(title);
            tc.addView(t);
        }
        if (!TextUtils.isEmpty(text)) {
            TextView t = Ui.text(s, 14, 400, Color.argb(215, 255, 255, 255));
            t.setText(text);
            t.setMaxLines(4);
            t.setEllipsize(TextUtils.TruncateAt.END);
            t.setLineSpacing(0, 1.12f);
            t.setPadding(0, dp(3), 0, 0);
            tc.addView(t);
        }
        body.addView(tc, new LinearLayout.LayoutParams(0, -2, 1f));
        if (n.getLargeIcon() != null) {
            try {
                ImageView li = new ImageView(s);
                li.setImageDrawable(n.getLargeIcon().loadDrawable(s));
                li.setScaleType(ImageView.ScaleType.CENTER_CROP);
                li.setOutlineProvider(new ViewOutlineProvider() {
                    @Override
                    public void getOutline(View view, Outline o) {
                        o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(10));
                    }
                });
                li.setClipToOutline(true);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(40), dp(40));
                lp.leftMargin = dp(10);
                body.addView(li, lp);
            } catch (Exception ignored) {
            }
        }
        c.addView(body);

        c.setOnClickListener(v -> {
            s.sendIntent(n.contentIntent);
            if ((n.flags & Notification.FLAG_AUTO_CANCEL) != 0 && NotifListener.inst != null) {
                try { NotifListener.inst.cancelNotification(sbn.getKey()); } catch (Exception ignored) {}
            }
            close();
        });
        swipeToDismiss(c, sbn);
        return c;
    }

    private void swipeToDismiss(final View v, final StatusBarNotification sbn) {
        final float[] st = new float[2];
        final boolean[] drag = new boolean[1];
        v.setOnTouchListener((view, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    st[0] = e.getRawX();
                    st[1] = e.getRawY();
                    drag[0] = false;
                    view.animate().scaleX(0.98f).scaleY(0.98f).setDuration(100).start();
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getRawX() - st[0], dy = e.getRawY() - st[1];
                    if (!drag[0] && Math.abs(dx) > dp(12) && Math.abs(dx) > Math.abs(dy)) {
                        drag[0] = true;
                        ViewGroup p = (ViewGroup) view.getParent();
                        if (p != null) p.requestDisallowInterceptTouchEvent(true);
                    }
                    if (drag[0]) {
                        view.setTranslationX(dx);
                        view.setAlpha(1f - Math.min(1f, Math.abs(dx) / view.getWidth()));
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP: {
                    float dx = e.getRawX() - st[0], dy = e.getRawY() - st[1];
                    view.animate().scaleX(1f).scaleY(1f).setDuration(160).start();
                    if (drag[0]) {
                        if (Math.abs(dx) > view.getWidth() * 0.33f) {
                            view.animate().translationX(Math.signum(dx) * view.getWidth()).alpha(0f).setDuration(180)
                                    .withEndAction(() -> {
                                        if (NotifListener.inst != null) {
                                            try { NotifListener.inst.cancelNotification(sbn.getKey()); } catch (Exception ignored) {}
                                        }
                                        ViewGroup p = (ViewGroup) view.getParent();
                                        if (p != null) p.removeView(view);
                                    }).start();
                        } else {
                            view.animate().translationX(0).alpha(1f).setInterpolator(Ui.SPRING).setDuration(420).start();
                        }
                    } else if (Math.abs(dx) < dp(10) && Math.abs(dy) < dp(10)) {
                        view.performClick();
                    }
                    return true;
                }
                case MotionEvent.ACTION_CANCEL:
                    view.animate().translationX(0).alpha(1f).scaleX(1f).scaleY(1f).setDuration(200).start();
                    return true;
            }
            return false;
        });
    }
}
