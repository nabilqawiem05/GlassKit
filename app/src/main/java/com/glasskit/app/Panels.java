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
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.location.LocationManager;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.nfc.NfcAdapter;
import android.os.PowerManager;
import android.provider.MediaStore;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.telephony.TelephonyManager;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Tetapan Pantas & panel pemberitahuan gaya Pixel, versi liquid glass (jernih / gelap). */
final class Panels {
    private static final int NONE = 0, QS = 1, SHADE = 2;

    private final PillService s;
    private final WindowManager wm;

    private View edgeL, edgeR;
    private PanelRoot root;
    private WindowManager.LayoutParams plp;
    private View content;
    private ScrollView scroll;
    private int open = NONE;
    private boolean closing, blurOk, dark;

    private Tile tWifi, tBt, tData, tDnd, tPlane, tTorch, tDark, tRotate, tSaver, tLoc, tNfc;
    private Widgets.HSlider sBright;
    private VSlider vBright, vVolume;
    private LiquidSheet liquid;
    private TextView hBattery;
    private Icon hBatIcon, hWifi, hCell;
    private String ssid;

    private ImageView mArt;
    private TextView mTitle, mArtist;
    private Icon mPlay;
    private Widgets.Bar mBar;
    private View mediaCard;

    private boolean torchOn;
    private String torchId;
    private CameraManager.TorchCallback torchCb;
    private boolean askedWrite;

    private LinearLayout ncList;
    private LiquidGlass.Backdrop bd = new LiquidGlass.Backdrop();

    // ===================== Panel ikut jari =====================
    private float prog = 1f;            // 0 tertutup .. 1 terbuka (>1 = regang)
    private boolean dragging;
    private int pendingTarget = -1;
    private float pendingVel;
    private final Spring progSpring = Spring.panel((v, vel) -> applyProg(v)).range(1f);

    private static float clamp01(float v) { return Math.max(0f, Math.min(1f, v)); }

    private float dragRange() { return open == QS ? dp(360) : s.screenH * 0.5f; }

    private static float rubber(float over) { return 0.18f * (1f - 1f / (over * 1.8f + 1f)); }

    /** Lukis panel pada kemajuan p (0..1, >1 = regang dengan rintangan). */
    private void applyProg(float p) {
        prog = p;
        if (root == null || !root.isAttachedToWindow()) return;
        root.setAlpha(clamp01(p * 1.8f));
        if (liquid != null && open == QS) {
            liquid.setProgress(p);
        } else if (content != null) {
            content.setTranslationY(-(1f - p) * dp(240));
            content.setAlpha(clamp01(p * 1.4f));
        }
    }

    /** Jari mula menarik dari tepi atas. */
    void dragBegin(boolean left) {
        if (isOpen()) return;
        progSpring.stop();
        dragging = true;
        pendingTarget = -1;
        prog = 0f;
        if (left) openNotifications(); else openControl();
        if (!isOpen()) dragging = false;
    }

    void dragMove(float dy) {
        if (!dragging) return;
        float p = Math.max(0f, dy / dragRange());
        if (p > 1f) p = 1f + rubber(p - 1f);
        applyProg(p);
    }

    void dragEnd(float vy) {
        if (!dragging) return;
        dragging = false;
        float v = vy / dragRange();
        boolean openIt = v > 0.8f || (v > -0.8f && prog > 0.33f);
        if (root != null && root.isAttachedToWindow()) {
            settle(openIt, v);
        } else {
            pendingTarget = openIt ? 1 : 0;
            pendingVel = v;
        }
    }

    private void settle(boolean openIt, float v) {
        progSpring.value = prog;
        if (openIt) {
            progSpring.to(1f, v, () -> {
                if (liquid != null && open == QS) liquid.settle();
            });
        } else {
            progSpring.to(0f, Math.min(v, 0f), this::removeNow);
        }
    }

    /** Buang panel serta-merta (selepas animasi tutup ikut jari). */
    private void removeNow() {
        progSpring.stop();
        final PanelRoot r = root;
        if (r == null) return;
        closing = true;
        try { wm.removeViewImmediate(r); } catch (Exception ignored) {}
        if (liquid != null) liquid.stopTilt();
        root = null;
        open = NONE;
        closing = false;
        prog = 1f;
        stopTorchWatch();
        mediaCard = null;
        liquid = null;
        s.onPanelChanged();
    }

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
                // separuh skrin setiap satu: seluruh bar status dipegang GlassKit, panel stock tak terbuka
                // (island sentiasa di atas tetingkap ini, jadi masih boleh diketik)
                s.prefs.getBoolean(Prefs.BLOCK_STOCK_SHADE, true) ? (s.screenW + 1) / 2 : Math.round(s.screenW * 0.36f),
                s.statusH + Math.round(s.getResources().getDisplayMetrics().density * 6),
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
        final float[] downY = new float[1];
        final long[] downT = new long[1];
        final android.view.VelocityTracker[] vt = new android.view.VelocityTracker[1];
        v.setOnTouchListener((view, e) -> {
            if (vt[0] == null) vt[0] = android.view.VelocityTracker.obtain();
            MotionEvent me = MotionEvent.obtain(e);
            me.setLocation(e.getRawX(), e.getRawY());
            vt[0].addMovement(me);
            me.recycle();
            int act = e.getActionMasked();
            if (act == MotionEvent.ACTION_CANCEL) {
                // Android 17: SystemUI "merampas" leretan dari atas (pilfer) → kita dapat CANCEL.
                // Jangan biar panel GlassKit hilang: buka penuh terus.
                if (fired[0]) {
                    dragEnd(dp(4000));
                } else if (e.getRawY() >= downY[0] - dp(4)
                        && android.os.SystemClock.uptimeMillis() - downT[0] < 700) {
                    if (left) openNotifications(); else openControl();
                }
            } else if (act == MotionEvent.ACTION_UP && fired[0]) {
                vt[0].computeCurrentVelocity(1000);
                dragEnd(vt[0].getYVelocity());
            }
            if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) {
                vt[0].recycle();
                vt[0] = null;
            }
            switch (act) {
                case MotionEvent.ACTION_DOWN:
                    y0[0] = e.getRawY();
                    downY[0] = e.getRawY();
                    downT[0] = android.os.SystemClock.uptimeMillis();
                    fired[0] = false;
                    break;
                case MotionEvent.ACTION_UP:
                    // ketuk dua kali bar status: kunci skrin
                    if (!fired[0] && s.prefs.getBoolean(Prefs.DT_LOCK, false)) {
                        long now = android.os.SystemClock.uptimeMillis();
                        if (now - lastEdgeTap < 320) {
                            lastEdgeTap = 0;
                            s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN);
                        } else {
                            lastEdgeTap = now;
                        }
                    }
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (!fired[0] && e.getRawY() - y0[0] > dp(10)) {
                        fired[0] = true;
                        y0[0] = e.getRawY();
                        dragBegin(left);
                    }
                    if (fired[0]) dragMove(e.getRawY() - y0[0]);
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
        private boolean closeDrag;
        private android.view.VelocityTracker vt;

        PanelRoot(Context c) {
            super(c);
            setFocusableInTouchMode(true);
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent e) {
            if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                if (e.getAction() == KeyEvent.ACTION_UP) {
                    if (sub != null) closeSub(); else close();
                }
                return true;
            }
            return super.dispatchKeyEvent(e);
        }

        private void track(MotionEvent e) {
            if (vt == null) vt = android.view.VelocityTracker.obtain();
            MotionEvent m = MotionEvent.obtain(e);
            m.setLocation(e.getRawX(), e.getRawY());
            vt.addMovement(m);
            m.recycle();
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent e) {
            track(e);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    x0 = e.getRawX();
                    y0 = e.getRawY();
                    closeDrag = false;
                    break;
                case MotionEvent.ACTION_MOVE:
                    float dy = e.getRawY() - y0, dx = e.getRawX() - x0;
                    if (!closing && sub == null && dy < -dp(14) && Math.abs(dy) > Math.abs(dx) * 1.5f
                            && (scroll == null || !scroll.canScrollVertically(1))) {
                        // tutup ikut jari
                        closeDrag = true;
                        progSpring.stop();
                        y0 = e.getRawY();
                        return true;
                    }
                    break;
            }
            return false;
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            track(e);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_MOVE:
                    if (closeDrag) applyProg(Math.max(0f, 1f + (e.getRawY() - y0) / dragRange()));
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (closeDrag) {
                        closeDrag = false;
                        vt.computeCurrentVelocity(1000);
                        float v = vt.getYVelocity() / dragRange();
                        settle(!(v < -0.8f || (v < 0.8f && prog < 0.7f)), v);
                    } else if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                        close();
                    }
                    if (vt != null) {
                        vt.recycle();
                        vt = null;
                    }
                    break;
            }
            return true;
        }
    }

    private int showToken;

    private void show(int which, View c, ScrollView sv) {
        if (root != null) {
            try { wm.removeViewImmediate(root); } catch (Exception ignored) {}
            root = null;
        }
        open = which;
        closing = false;
        content = c;
        scroll = sv;
        s.onPanelChanged();

        root = new PanelRoot(s);
        final PanelRoot r = root;
        final ImageView bgImg = new ImageView(s);
        bgImg.setScaleType(ImageView.ScaleType.FIT_XY);
        r.addView(bgImg, new FrameLayout.LayoutParams(-1, -1));
        View dimV = new View(s);
        dimV.setBackgroundColor(dark ? Color.argb(160, 6, 6, 10) : Color.argb(70, 10, 12, 18));
        r.addView(dimV, new FrameLayout.LayoutParams(-1, -1));
        r.addView(c, new FrameLayout.LayoutParams(-1, -1));

        plp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        plp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        plp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
        plp.setTitle("GlassKitPanel");

        final int token = ++showToken;
        // Ambil gambar skrin dan kaburkan sendiri: kaca sentiasa kabur walaupun blur sistem tak jalan
        try {
            s.takeScreenshot(android.view.Display.DEFAULT_DISPLAY, s.getMainExecutor(),
                    new AccessibilityService.TakeScreenshotCallback() {
                        @Override
                        public void onSuccess(AccessibilityService.ScreenshotResult res) {
                            // kerja kabur di benang latar (GPU, resolusi rendah): panel muncul tanpa tersekat
                            final LiquidGlass.Backdrop b0 = bd;
                            GpuBlur.bg().post(() -> {
                                Bitmap blurred = null;
                                try {
                                    Bitmap hw = Bitmap.wrapHardwareBuffer(res.getHardwareBuffer(), res.getColorSpace());
                                    if (hw != null) {
                                        blurred = GpuBlur.blurOrCpu(hw, 200, 9);
                                        b0.set(hw, s.screenW, s.screenH);
                                    }
                                } catch (Throwable ignored) {
                                } finally {
                                    try { res.getHardwareBuffer().close(); } catch (Throwable ignored) {}
                                }
                                final Bitmap fb = blurred;
                                s.h.post(() -> attach(token, r, bgImg, fb));
                            });
                        }

                        @Override
                        public void onFailure(int errorCode) {
                            attach(token, r, bgImg, null);
                        }
                    });
        } catch (Throwable t) {
            attach(token, r, bgImg, null);
        }
    }

    private void attach(int token, PanelRoot r, ImageView bgImg, Bitmap blurred) {
        if (token != showToken || root != r || closing) return;
        if (blurred != null) {
            bgImg.setImageBitmap(blurred);
            if (!dark) {
                ColorMatrix cm = new ColorMatrix();
                cm.setSaturation(1.25f);
                bgImg.setColorFilter(new ColorMatrixColorFilter(cm));
            }
        } else {
            blurOk = wm.isCrossWindowBlurEnabled();
            if (blurOk) {
                plp.flags |= WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
                plp.setBlurBehindRadius(dp(dark ? 44 : 36));
            } else {
                bgImg.setImageDrawable(new ColorDrawable(dark ? Color.argb(235, 12, 12, 16) : Color.argb(225, 52, 54, 64)));
            }
        }
        try {
            wm.addView(r, plp);
        } catch (Exception e) {
            root = null;
            open = NONE;
            s.onPanelChanged();
            return;
        }
        r.requestFocus();
        LiquidGlass.invalidateTree(r);
        if (dragging || pendingTarget >= 0) {
            r.setAlpha(0f);
            r.post(() -> {
                applyProg(prog);
                if (pendingTarget >= 0) {
                    boolean o = pendingTarget == 1;
                    pendingTarget = -1;
                    settle(o, pendingVel);
                }
            });
            return;
        }
        prog = 1f;
        r.setAlpha(0f);
        r.animate().alpha(1f).setDuration(180).start();
        if (liquid != null && open == QS) {
            final LiquidSheet ls = liquid;
            ls.post(ls::enter);
            return;
        }
        content.setTranslationY(-dp(48));
        content.animate().translationY(0).setInterpolator(Ui.SPRING).setDuration(Ui.SPRING_MS).start();
    }

    void close() {
        if (root == null || closing) return;
        progSpring.stop();
        dragging = false;
        pendingTarget = -1;
        if (prog < 0.999f) {
            // sedang ditarik/dilepaskan: tutup dengan spring yang sama
            progSpring.value = prog;
            progSpring.to(0f, 0f, this::removeNow);
            return;
        }
        closing = true;
        final PanelRoot r = root;
        if (!r.isAttachedToWindow()) {
            root = null;
            open = NONE;
            closing = false;
            s.onPanelChanged();
            return;
        }
        if (liquid != null && open == QS) {
            final LiquidSheet ls = liquid;
            ls.exit(() -> r.animate().alpha(0f).setDuration(120).withEndAction(() -> finishClose(r)).start());
            return;
        }
        content.animate().translationY(-dp(56)).setDuration(220).start();
        r.animate().alpha(0f).setDuration(220).withEndAction(() -> {
            try { wm.removeViewImmediate(r); } catch (Exception ignored) {}
            if (root == r) {
                root = null;
                open = NONE;
                closing = false;
                stopTorchWatch();
                mediaCard = null;
                s.onPanelChanged();
            }
        }).start();
    }

    private void finishClose(PanelRoot r) {
        try { wm.removeViewImmediate(r); } catch (Exception ignored) {}
        if (liquid != null) liquid.stopTilt();
        if (root == r) {
            root = null;
            open = NONE;
            closing = false;
            stopTorchWatch();
            mediaCard = null;
            liquid = null;
            s.onPanelChanged();
        }
    }

    private void launch(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { s.startActivity(i); } catch (Exception ignored) {}
        close();
    }

    // ===================== Tile gaya Pixel =====================

    private class Tile {
        final View v;
        final Icon ic;
        final TextView label, sub;
        final int rOffDp, rOnPx;
        boolean round;
        int onColor, kind;
        String name;
        FrameLayout disc;

        /** Tile bulat berwarna (gaya Island Cecair): bulatan + label kecil di bawah. */
        Tile(int kind, String text, int onColor, View.OnClickListener tap, Intent detail) {
            this.kind = kind;
            this.name = text;
            this.round = true;
            this.onColor = onColor;
            rOffDp = 0;
            rOnPx = 0;
            ic = new Icon(s, kind, Color.WHITE);
            LinearLayout col = new LinearLayout(s);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            disc = new FrameLayout(s);
            disc.addView(ic, new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
            disc.setOnClickListener(tap);
            disc.setOnLongClickListener(x -> {
                if (detail != null) launch(detail);
                else Toast.makeText(s, text, Toast.LENGTH_SHORT).show();
                return true;
            });
            Ui.press(disc);
            col.addView(disc, new LinearLayout.LayoutParams(dp(58), dp(58)));
            label = Ui.line(s, 11, 500, Color.argb(200, 255, 255, 255));
            label.setText(text);
            label.setGravity(Gravity.CENTER);
            label.setPadding(0, dp(5), 0, 0);
            col.addView(label, new LinearLayout.LayoutParams(dp(76), -2));
            sub = null;
            v = col;
            set(false, null);
        }

        Tile(int kind, String text, boolean big, int height, View.OnClickListener tap, Intent detail) {
            rOffDp = big ? 22 : 20;
            rOnPx = big ? height / 2 : dp(30);
            ic = new Icon(s, kind, Color.WHITE);
            if (big) {
                LinearLayout l = new LinearLayout(s);
                l.setGravity(Gravity.CENTER_VERTICAL);
                l.setPadding(dp(18), 0, dp(14), 0);
                l.addView(ic, new LinearLayout.LayoutParams(dp(22), dp(22)));
                LinearLayout col = new LinearLayout(s);
                col.setOrientation(LinearLayout.VERTICAL);
                col.setPadding(dp(12), 0, 0, 0);
                label = Ui.line(s, 15, 600, Color.WHITE);
                label.setText(text);
                sub = Ui.line(s, 13, 400, Color.WHITE);
                sub.setPadding(0, dp(3), 0, 0);
                col.addView(label);
                col.addView(sub);
                l.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
                v = l;
            } else {
                FrameLayout f = new FrameLayout(s);
                f.addView(ic, new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
                label = null;
                sub = null;
                v = f;
            }
            v.setOnClickListener(tap);
            v.setOnLongClickListener(x -> {
                if (detail != null) launch(detail);
                else Toast.makeText(s, text, Toast.LENGTH_SHORT).show();
                return true;
            });
            Ui.press(v);
            set(false, null);
        }

        void set(boolean on, String state) {
            if (round) {
                android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
                g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                if (on) {
                    g.setColors(new int[]{lighten(onColor), onColor});
                    g.setGradientType(android.graphics.drawable.GradientDrawable.RADIAL_GRADIENT);
                    g.setGradientRadius(dp(40));
                    g.setGradientCenter(0.35f, 0.3f);
                } else {
                    g.setColor(Color.argb(42, 255, 255, 255));
                }
                g.setStroke(Math.max(1, dp(0.8f)), on ? Color.argb(110, 255, 255, 255) : Color.argb(45, 255, 255, 255));
                disc.setBackground(g);
                boolean darkIcon = on && Color.luminance(onColor) > 0.5f;
                ic.setColor(darkIcon ? 0xFF2A1B00 : Color.WHITE);
                return;
            }
            if (on) v.setBackground(new LiquidGlass.Surface(s, rOnPx, dark, bd, Ui.accent(s, true)));
            else v.setBackground(new LiquidGlass.Surface(s, dp(rOffDp), dark, bd, 0));
            int fg = Color.WHITE;
            ic.setColor(fg);
            if (label != null) {
                label.setTextColor(fg);
                sub.setTextColor(on ? fg : Color.argb(185, 255, 255, 255));
                sub.setText(state != null ? state : s.getString(on ? R.string.st_on : R.string.st_off));
            }
        }
    }

    private Intent act(String a) { return new Intent(a); }

    private static int lighten(int c) {
        return Color.rgb(Math.min(255, Color.red(c) + 60), Math.min(255, Color.green(c) + 60), Math.min(255, Color.blue(c) + 60));
    }

    // ===================== Header (jam, tarikh, status) =====================

    private View header() {
        LinearLayout h = new LinearLayout(s);
        h.setOrientation(LinearLayout.VERTICAL);
        h.setPadding(dp(6), 0, dp(6), 0);
        h.setClickable(true);

        LinearLayout r1 = new LinearLayout(s);
        r1.setGravity(Gravity.BOTTOM);
        Date now = new Date();
        boolean is24 = android.text.format.DateFormat.is24HourFormat(s);
        TextView time = Ui.text(s, 44, 500, Color.WHITE);
        time.setText(new SimpleDateFormat(is24 ? "HH:mm" : "h:mm", Locale.getDefault()).format(now));
        r1.addView(time, new LinearLayout.LayoutParams(0, -2, 1f));

        LinearLayout st = new LinearLayout(s);
        st.setGravity(Gravity.CENTER_VERTICAL);
        st.setPadding(0, 0, 0, dp(8));
        hWifi = new Icon(s, Icon.WIFI, Color.WHITE);
        st.addView(hWifi, new LinearLayout.LayoutParams(dp(17), dp(17)));
        hCell = new Icon(s, Icon.CELL, Color.WHITE);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(dp(16), dp(16));
        cl.leftMargin = dp(8);
        st.addView(hCell, cl);
        hBatIcon = new Icon(s, Icon.BATTERY, Color.WHITE);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(dp(20), dp(20));
        bl.leftMargin = dp(10);
        st.addView(hBatIcon, bl);
        hBattery = Ui.text(s, 14, 600, Color.WHITE);
        hBattery.setPadding(dp(4), 0, 0, 0);
        st.addView(hBattery);
        r1.addView(st);
        h.addView(r1);

        LinearLayout r2 = new LinearLayout(s);
        r2.setGravity(Gravity.CENTER_VERTICAL);
        TextView date = Ui.text(s, 15, 500, Color.argb(220, 255, 255, 255));
        date.setText(new SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(now));
        r2.addView(date, new LinearLayout.LayoutParams(0, -2, 1f));
        FrameLayout gear = new FrameLayout(s);
        gear.setBackground(Ui.glassStyled(s, 18, dark));
        gear.addView(new Icon(s, Icon.GEAR, Color.WHITE), new FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER));
        gear.setOnClickListener(v -> launch(new Intent(Settings.ACTION_SETTINGS)));
        Ui.press(gear);
        r2.addView(gear, new LinearLayout.LayoutParams(dp(36), dp(36)));
        LinearLayout.LayoutParams r2l = new LinearLayout.LayoutParams(-1, -2);
        r2l.topMargin = dp(2);
        h.addView(r2, r2l);
        updateHeader();
        return h;
    }

    private void updateHeader() {
        if (hBattery == null) return;
        int lvl = Ui.battery(s);
        boolean ch = Ui.charging(s);
        hBattery.setText(lvl >= 0 ? lvl + "%" : "");
        hBatIcon.setKind(ch ? Icon.BOLT : Icon.BATTERY);
        hBatIcon.setColor(ch ? 0xFF30D158 : (lvl >= 0 && lvl <= 15 ? 0xFFFF453A : Color.WHITE));
        hWifi.setColor(wifiConnected() ? Color.WHITE : Color.argb(90, 255, 255, 255));
        hCell.setColor(global("mobile_data") ? Color.WHITE : Color.argb(90, 255, 255, 255));
    }

    // ===================== Tetapan Pantas (kanan) =====================

    void openControl() {
        if (open == QS) return;
        if (s.prefs.getInt(Prefs.PANEL_STYLE, 1) == 1) {
            openLiquid();
            return;
        }
        sBright = null;
        vBright = vVolume = null;
        liquid = null;
        bd = new LiquidGlass.Backdrop();
        LiquidGlass.current = bd;
        dark = Ui.darkGlass(s);
        int m = dp(14), gap = dp(10);
        int w = Math.min(s.screenW, dp(460)) - 2 * m;

        ScrollView sv = new ScrollView(s);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout col = new LinearLayout(s);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(m, s.statusH + dp(10), m, dp(40));
        col.setClickable(true);
        col.setOnClickListener(v -> close());
        sv.addView(col, new ScrollView.LayoutParams(-1, -2));

        col.addView(header(), new LinearLayout.LayoutParams(w, -2));

        sBright = new Widgets.HSlider(s, Icon.SUN, Ui.glassStyled(s, 26, dark), Ui.accent(s, dark), Ui.onAccent(s));
        sBright.setCb(this::setBrightness);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(w, dp(52));
        sl.topMargin = dp(18);
        col.addView(sBright, sl);

        int bw = (w - gap) / 2, bh = dp(72);
        tWifi = new Tile(Icon.WIFI, s.getString(R.string.tg_wifi), true, bh, v -> toggleWifi(), act(Settings.Panel.ACTION_WIFI));
        tBt = new Tile(Icon.BT, s.getString(R.string.tg_bt), true, bh, v -> toggleBt(), act(Settings.ACTION_BLUETOOTH_SETTINGS));
        tData = new Tile(Icon.CELL, s.getString(R.string.tg_data), true, bh, v -> toggleData(), act(Settings.Panel.ACTION_INTERNET_CONNECTIVITY));
        tDnd = new Tile(Icon.MOON, s.getString(R.string.tg_dnd), true, bh, v -> toggleDnd(), act("android.settings.ZEN_MODE_SETTINGS"));
        col.addView(pair(w, gap, bw, bh, tWifi.v, tBt.v, gap));
        col.addView(pair(w, gap, bw, bh, tData.v, tDnd.v, gap));

        int sw = (w - 3 * gap) / 4, shh = dp(64);
        tPlane = new Tile(Icon.PLANE, s.getString(R.string.tg_plane), false, shh, v -> togglePlane(), act(Settings.ACTION_AIRPLANE_MODE_SETTINGS));
        tTorch = new Tile(Icon.TORCH, s.getString(R.string.tg_torch), false, shh, v -> toggleTorch(), null);
        tDark = new Tile(Icon.DARK, s.getString(R.string.tg_dark), false, shh, v -> toggleDark(), act(Settings.ACTION_DISPLAY_SETTINGS));
        tRotate = new Tile(Icon.ROTATE, s.getString(R.string.tg_rotate), false, shh, v -> toggleRotate(), act(Settings.ACTION_DISPLAY_SETTINGS));
        tSaver = new Tile(Icon.BATTERY, s.getString(R.string.tg_saver), false, shh, v -> toggleSaver(), act(Settings.ACTION_BATTERY_SAVER_SETTINGS));
        tLoc = new Tile(Icon.PIN, s.getString(R.string.tg_location), false, shh, v -> toggleLocation(), act(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
        tNfc = new Tile(Icon.NFC, s.getString(R.string.tg_nfc), false, shh, v -> toggleNfc(), act(Settings.ACTION_NFC_SETTINGS));
        Tile shot = new Tile(Icon.SHOT, s.getString(R.string.tg_shot), false, shh, v -> {
            close();
            s.h.postDelayed(() -> s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT), 520);
        }, null);
        Tile lock = new Tile(Icon.LOCK, s.getString(R.string.tg_lock), false, shh, v -> {
            close();
            s.h.postDelayed(() -> s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN), 300);
        }, act(Settings.ACTION_SECURITY_SETTINGS));
        Tile cam = new Tile(Icon.CAMERA, s.getString(R.string.tg_camera), false, shh,
                v -> launch(new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)), null);
        Tile vol = new Tile(Icon.REC, s.getString(R.string.tg_rec), false, shh, v -> {
            if (Recorder.recording()) {
                Recorder.stop(s);
                close();
            } else {
                close();
                s.h.postDelayed(() -> Recorder.start(s), 700);
            }
        }, null);
        vol.set(Recorder.recording(), null);
        Tile set = new Tile(Icon.GEAR, s.getString(R.string.tg_settings), false, shh,
                v -> launch(new Intent(Settings.ACTION_SETTINGS)), null);
        Tile[] small = {tPlane, tTorch, tDark, tRotate, tSaver, tLoc, tNfc, shot, lock, cam, vol, set};
        for (int i = 0; i < small.length; i += 4) {
            LinearLayout r = new LinearLayout(s);
            for (int j = 0; j < 4; j++) {
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(sw, shh);
                if (j > 0) p.leftMargin = gap;
                r.addView(small[i + j].v, p);
            }
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(w, -2);
            rl.topMargin = gap;
            col.addView(r, rl);
        }

        View media = mediaCard();
        if (media != null) {
            LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(w, dp(160));
            ml.topMargin = dp(14);
            col.addView(media, ml);
        }

        TextView hint = Ui.text(s, 12, 500, Color.argb(170, 255, 255, 255));
        hint.setText(R.string.tile_hint);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(14), 0, 0);
        col.addView(hint, new LinearLayout.LayoutParams(w, -2));

        LinearLayout r4 = new LinearLayout(s);
        r4.addView(chip(s.getString(R.string.system_panel), v -> {
            close();
            s.h.postDelayed(() -> s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS), 300);
        }), new LinearLayout.LayoutParams(0, dp(44), 1f));
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(0, dp(44), 1f);
        gl.leftMargin = gap;
        r4.addView(chip(s.getString(R.string.cc_settings), v -> launch(new Intent(s, MainActivity.class))), gl);
        LinearLayout.LayoutParams r4l = new LinearLayout.LayoutParams(w, -2);
        r4l.topMargin = dp(12);
        col.addView(r4, r4l);
        col.addView(handle());

        LiquidGlass.current = null;
        show(QS, sv, sv);
        refreshControl();
        startTorchWatch();
        fetchSsid();
    }

    // ===================== Gaya Island Cecair (E + I + L) =====================

    /** Bar kaca tegak (kecerahan / volume). */
    private class VSlider extends View {
        float v = 0.5f;
        final int kind;
        interface Cb { void on(float v, boolean done); }
        Cb cb;
        private final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final Icon icon;

        VSlider(int kind, Cb cb) {
            super(s);
            this.kind = kind;
            this.cb = cb;
            icon = new Icon(s, kind, 0xFF2C2C2E);
        }

        void setValue(float x) {
            v = Math.max(0f, Math.min(1f, x));
            invalidate();
        }

        @Override
        protected void onDraw(android.graphics.Canvas c) {
            float w = getWidth(), h = getHeight(), rad = w / 2.6f;
            android.graphics.RectF r = new android.graphics.RectF(0, 0, w, h);
            p.setShader(null);
            p.setColor(Color.argb(46, 255, 255, 255));
            c.drawRoundRect(r, rad, rad, p);
            c.save();
            android.graphics.Path clip = new android.graphics.Path();
            clip.addRoundRect(r, rad, rad, android.graphics.Path.Direction.CW);
            c.clipPath(clip);
            float top = h * (1f - v);
            p.setShader(new android.graphics.LinearGradient(0, top, 0, h, Color.WHITE, Color.rgb(222, 226, 234),
                    android.graphics.Shader.TileMode.CLAMP));
            c.drawRect(0, top, w, h, p);
            c.restore();
            p.setShader(null);
            p.setStyle(android.graphics.Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1f, dp(0.8f)));
            p.setColor(Color.argb(60, 255, 255, 255));
            c.drawRoundRect(r, rad, rad, p);
            p.setStyle(android.graphics.Paint.Style.FILL);
            int is = Math.round(w * 0.5f);
            icon.setColor(v > 0.12f ? 0xFF2C2C2E : Color.WHITE);
            icon.layout(0, 0, is, is);
            c.save();
            c.translate((w - is) / 2f, h - is - w * 0.3f);
            icon.draw(c);
            c.restore();
        }

        Runnable onLong;
        private float downY, downV;
        private boolean longFired;
        private final Runnable longCheck = () -> {
            longFired = true;
            // tekan lama: kembalikan nilai asal, bergetar, buka pilihan
            setValue(downV);
            if (cb != null) cb.on(downV, true);
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            animate().scaleX(1.06f).scaleY(1.06f).setDuration(120).withEndAction(() ->
                    animate().scaleX(1f).scaleY(1f).setInterpolator(Ui.SPRING).setDuration(Ui.SPRING_MS).start()).start();
            if (onLong != null) onLong.run();
        };

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            int act = e.getActionMasked();
            if (act == MotionEvent.ACTION_DOWN) {
                getParent().requestDisallowInterceptTouchEvent(true);
                downY = e.getY();
                downV = v;
                longFired = false;
                if (onLong != null) postDelayed(longCheck, 480);
            }
            if (longFired) {
                if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) longFired = false;
                return true;
            }
            if (act == MotionEvent.ACTION_MOVE && Math.abs(e.getY() - downY) > dp(8)) removeCallbacks(longCheck);
            setValue(1f - e.getY() / getHeight());
            boolean done = act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL;
            if (done) removeCallbacks(longCheck);
            if (cb != null) cb.on(v, done);
            return true;
        }
    }

    /**
     * Helaian hitam yang tumbuh dari Dynamic Island dan "mencair" ke bawah: tepi bawah beralun seperti
     * cecair sebelum mendap, tile menitis keluar satu demi satu, dan kilauan kaca bergerak ikut condongan telefon.
     */
    private class LiquidSheet extends FrameLayout implements android.hardware.SensorEventListener {
        final LinearLayout body;
        final java.util.List<View> drops = new java.util.ArrayList<>();
        float melt = 0f;          // 0 = island, 1 = penuh
        float wobble = 0f;        // ayunan tepi bawah semasa mendap
        float tx, ty;             // condongan (-1..1)
        private final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path path = new android.graphics.Path();
        private android.hardware.SensorManager sm;
        private final float rad = dp(38);
        private float shW, shH;
        private android.graphics.Shader shBody, shGlint, shRim;

        LiquidSheet() {
            super(s);
            setWillNotDraw(false);
            body = new LinearLayout(s);
            body.setOrientation(LinearLayout.VERTICAL);
            body.setPadding(dp(18), dp(16), dp(18), dp(20));
            addView(body, new FrameLayout.LayoutParams(-1, -2));
            setClipToOutline(false);
            setClickable(true);
        }

        @Override
        protected void dispatchDraw(android.graphics.Canvas c) {
            float w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            float hh = Math.max(dp(36), h * melt);
            // bentuk cecair: segi empat bucu bulat dengan tepi bawah beralun (3 titisan)
            path.reset();
            float r = Math.min(rad, hh / 2f);
            path.moveTo(0, r);
            path.quadTo(0, 0, r, 0);
            path.lineTo(w - r, 0);
            path.quadTo(w, 0, w, r);
            float base = hh - r;
            path.lineTo(w, base);
            float amp = wobble * dp(26);
            path.quadTo(w, hh, w - r, hh);
            float seg = (w - 2 * r) / 3f;
            for (int i = 0; i < 3; i++) {
                float x0 = w - r - seg * i, x1 = x0 - seg;
                float a = amp * (i == 1 ? 1.25f : 0.8f) * (i % 2 == 0 ? 1 : -0.6f);
                path.cubicTo(x0 - seg * 0.3f, hh + a, x1 + seg * 0.3f, hh + a, x1, hh);
            }
            path.quadTo(0, hh, 0, base);
            path.close();
            // shader dicipta sekali bagi setiap saiz (bukan setiap bingkai)
            if (shW != w || shH != h) {
                shW = w;
                shH = h;
                shBody = new android.graphics.LinearGradient(0, 0, 0, h, Color.argb(246, 6, 6, 8), Color.argb(236, 14, 14, 18),
                        android.graphics.Shader.TileMode.CLAMP);
                shGlint = new android.graphics.RadialGradient(w * 0.3f, h * 0.15f, Math.max(w, h) * 0.75f,
                        Color.argb(38, 255, 255, 255), Color.argb(0, 255, 255, 255), android.graphics.Shader.TileMode.CLAMP);
                shRim = new android.graphics.LinearGradient(0, 0, w, h,
                        new int[]{Color.argb(120, 255, 255, 255), Color.argb(20, 255, 255, 255), Color.argb(70, 255, 255, 255)},
                        null, android.graphics.Shader.TileMode.CLAMP);
            }
            // badan kaca hitam
            p.setShader(shBody);
            c.drawPath(path, p);
            // kilauan
            p.setShader(shGlint);
            c.drawPath(path, p);
            // bingkai kaca
            p.setShader(shRim);
            p.setStyle(android.graphics.Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1f, dp(1f)));
            c.drawPath(path, p);
            p.setStyle(android.graphics.Paint.Style.FILL);
            p.setShader(null);
            c.save();
            c.clipRect(0, 0, w, hh + Math.abs(amp));
            super.dispatchDraw(c);
            c.restore();
        }

        /** Kemajuan ikut jari: 0 = sebesar island, 1 = penuh, >1 = regang. */
        void setProgress(float p) {
            float q = clamp01(p);
            setPivotX(getWidth() / 2f);
            setPivotY(0);
            melt = Math.max(0f, Math.min(1.15f, p));
            wobble = p > 1f ? -(p - 1f) * 2.2f : 0f;
            setScaleX(0.35f + 0.65f * clamp01(p * 1.5f));
            body.setAlpha(clamp01((p - 0.2f) / 0.45f));
            for (int i = 0; i < drops.size(); i++) {
                View d = drops.get(i);
                float t = clamp01((q - 0.28f - i * 0.012f) / 0.45f);
                d.setAlpha(t);
                d.setScaleX(0.5f + 0.5f * t);
                d.setScaleY(0.5f + 0.5f * t);
                d.setTranslationY(-dp(26) * (1f - t));
            }
            invalidate();
        }

        void enter() {
            setPivotX(getWidth() / 2f);
            setPivotY(0);
            melt = 0f;
            body.setAlpha(0f);
            for (View d : drops) {
                d.setAlpha(0f);
                d.setScaleX(0.2f);
                d.setScaleY(0.2f);
                d.setTranslationY(-dp(36));
            }
            setScaleX(0.35f);
            animate().scaleX(1f).setInterpolator(Ui.SPRING).setDuration(Ui.SPRING_MS).start();
            android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(0f, 1f);
            a.setDuration(620);
            a.setInterpolator(Ui.EASE);
            a.addUpdateListener(x -> {
                melt = (float) x.getAnimatedValue();
                wobble = (float) Math.sin(melt * Math.PI) * 0.9f;
                invalidate();
            });
            a.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(android.animation.Animator an) {
                    settle();
                }
            });
            a.start();
            body.animate().alpha(1f).setStartDelay(160).setDuration(260).start();
            for (int i = 0; i < drops.size(); i++) {
                View d = drops.get(i);
                d.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0)
                        .setInterpolator(Ui.SPRING)
                        .setStartDelay(180 + i * 28L).setDuration(Ui.SPRING_MS).start();
            }
        }

        /** Tepi bawah berayun sekejap sebelum tenang, macam cecair mendap. */
        void settle() {
            android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(0f, 1f);
            a.setDuration(700);
            a.addUpdateListener(x -> {
                float t = (float) x.getAnimatedValue();
                wobble = (float) (Math.sin(t * Math.PI * 2) * Math.exp(-5 * t)) * 0.3f;
                invalidate();
            });
            a.start();
        }

        void exit(Runnable end) {
            for (int i = 0; i < drops.size(); i++) {
                View d = drops.get(drops.size() - 1 - i);
                d.animate().alpha(0f).scaleX(0.3f).scaleY(0.3f).translationY(-dp(30))
                        .setInterpolator(Ui.EXIT)
                        .setStartDelay(i * 12L).setDuration(200).start();
            }
            body.animate().alpha(0f).setStartDelay(120).setDuration(180).start();
            android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(1f, 0f);
            a.setStartDelay(120);
            a.setDuration(340);
            a.setInterpolator(Ui.EXIT);
            a.addUpdateListener(x -> {
                melt = (float) x.getAnimatedValue();
                wobble = -(float) Math.sin(melt * Math.PI) * 0.5f;
                invalidate();
            });
            a.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(android.animation.Animator an) {
                    stopTilt();
                    end.run();
                }
            });
            a.start();
            animate().scaleX(0.35f).setStartDelay(200).setDuration(300).start();
        }

        void startTilt() {
            try {
                sm = s.getSystemService(android.hardware.SensorManager.class);
                android.hardware.Sensor g = sm.getDefaultSensor(android.hardware.Sensor.TYPE_GRAVITY);
                if (g == null) g = sm.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER);
                if (g != null) sm.registerListener(this, g, android.hardware.SensorManager.SENSOR_DELAY_GAME, s.h);
            } catch (Exception ignored) {
            }
        }

        void stopTilt() {
            if (sm != null) {
                try { sm.unregisterListener(this); } catch (Exception ignored) {}
                sm = null;
            }
        }

        @Override
        public void onSensorChanged(android.hardware.SensorEvent e) {
            float nx = Math.max(-1f, Math.min(1f, -e.values[0] / 4.5f));
            float ny = Math.max(-1f, Math.min(1f, (e.values[1] - 6.5f) / 4.5f));
            tx += (nx - tx) * 0.12f;
            ty += (ny - ty) * 0.12f;
            invalidate();
        }

        @Override
        public void onAccuracyChanged(android.hardware.Sensor sensor, int accuracy) { }
    }

    private void openLiquid() {
        bd = new LiquidGlass.Backdrop();
        LiquidGlass.current = bd;
        dark = true;
        sBright = null;
        int m = dp(10), gap = dp(8);
        int w = Math.min(s.screenW, dp(460)) - 2 * m;

        ScrollView sv = new ScrollView(s);
        sv.setVerticalScrollBarEnabled(false);
        sv.setClipChildren(false);
        LinearLayout col = new LinearLayout(s);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(m, Math.max(dp(6), s.statusH - dp(30)), m, dp(60));
        col.setClipChildren(false);
        col.setClickable(true);
        col.setOnClickListener(v -> close());
        sv.addView(col, new ScrollView.LayoutParams(-1, -2));

        final LiquidSheet sheet = new LiquidSheet();
        liquid = sheet;
        sheet.body.setAlpha(0f);
        sheet.setScaleX(0.35f);
        sheet.setCameraDistance(dp(1200) * s.getResources().getDisplayMetrics().density);
        col.addView(sheet, new LinearLayout.LayoutParams(w, -2));
        LinearLayout b = sheet.body;

        fillLiquid(sheet, gap);

        LiquidGlass.current = null;
        show(QS, sv, sv);
        refreshControl();
        startTorchWatch();
        fetchSsid();
    }

    /** Isi badan panel cecair (boleh dibina semula selepas sunting tile). */
    private void fillLiquid(LiquidSheet sheet, int gap) {
        LinearLayout b = sheet.body;
        // kepala: jam, tarikh, cuaca, bateri
        View hd = header();
        b.addView(hd, new LinearLayout.LayoutParams(-1, -2));

        // tile bulat berwarna + dua bar tegak
        tWifi = new Tile(Icon.WIFI, s.getString(R.string.tg_wifi), 0xFF0A84FF, v -> toggleWifi(), act(Settings.Panel.ACTION_WIFI));
        tBt = new Tile(Icon.BT, s.getString(R.string.tg_bt), 0xFF0A84FF, v -> toggleBt(), act(Settings.ACTION_BLUETOOTH_SETTINGS));
        tData = new Tile(Icon.CELL, s.getString(R.string.tg_data), 0xFF30D158, v -> toggleData(), act(Settings.Panel.ACTION_INTERNET_CONNECTIVITY));
        tDnd = new Tile(Icon.MOON, s.getString(R.string.tg_dnd), 0xFF5E5CE6, v -> toggleDnd(), act("android.settings.ZEN_MODE_SETTINGS"));
        tPlane = new Tile(Icon.PLANE, s.getString(R.string.tg_plane), 0xFFFF9F0A, v -> togglePlane(), act(Settings.ACTION_AIRPLANE_MODE_SETTINGS));
        tTorch = new Tile(Icon.TORCH, s.getString(R.string.tg_torch), 0xFFFFD60A, v -> toggleTorch(), null);
        tDark = new Tile(Icon.DARK, s.getString(R.string.tg_dark), 0xFF8E8E93, v -> toggleDark(), act(Settings.ACTION_DISPLAY_SETTINGS));
        tRotate = new Tile(Icon.ROTATE, s.getString(R.string.tg_rotate), 0xFFFF375F, v -> toggleRotate(), act(Settings.ACTION_DISPLAY_SETTINGS));
        tSaver = new Tile(Icon.BATTERY, s.getString(R.string.tg_saver), 0xFFFFCC00, v -> toggleSaver(), act(Settings.ACTION_BATTERY_SAVER_SETTINGS));
        tLoc = new Tile(Icon.PIN, s.getString(R.string.tg_location), 0xFF0A84FF, v -> toggleLocation(), act(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
        tNfc = new Tile(Icon.NFC, s.getString(R.string.tg_nfc), 0xFF64D2FF, v -> toggleNfc(), act(Settings.ACTION_NFC_SETTINGS));
        Tile shot = new Tile(Icon.SHOT, s.getString(R.string.tg_shot), Color.WHITE, v -> {
            close();
            s.h.postDelayed(() -> s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT), 700);
        }, null);
        Tile rec = new Tile(Icon.REC, s.getString(R.string.tg_rec), 0xFFFF3B30, v -> {
            if (Recorder.recording()) {
                Recorder.stop(s);
                close();
            } else {
                close();
                s.h.postDelayed(() -> Recorder.start(s), 800);
            }
        }, null);
        rec.set(Recorder.recording(), null);
        Tile cam = new Tile(Icon.CAMERA, s.getString(R.string.tg_camera), Color.WHITE,
                v -> launch(new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)), null);
        Tile lock = new Tile(Icon.LOCK, s.getString(R.string.tg_lock), Color.WHITE, v -> {
            close();
            s.h.postDelayed(() -> s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN), 500);
        }, act(Settings.ACTION_SECURITY_SETTINGS));
        Tile set = new Tile(Icon.GEAR, s.getString(R.string.tg_settings), Color.WHITE,
                v -> launch(new Intent(Settings.ACTION_SETTINGS)), null);
        final Tile[] awk = new Tile[1];
        awk[0] = new Tile(Icon.SUN, s.getString(R.string.tg_awake), 0xFFFFD60A, v -> {
            s.setKeepAwake(!s.keepAwake());
            awk[0].set(s.keepAwake(), null);
            Toast.makeText(s, s.keepAwake() ? R.string.awake_on : R.string.awake_off, Toast.LENGTH_SHORT).show();
        }, null);
        awk[0].set(s.keepAwake(), null);
        java.util.LinkedHashMap<String, Tile> map = new java.util.LinkedHashMap<>();
        map.put("wifi", tWifi);
        map.put("bt", tBt);
        map.put("data", tData);
        map.put("dnd", tDnd);
        final Tile rng = new Tile(Icon.BELL, s.getString(R.string.rg_sound), 0xFFFF375F, null, null);
        rng.disc.setOnClickListener(x -> {
            cycleRinger();
            s.h.postDelayed(() -> paintRinger(rng), 150);
        });
        rng.disc.setOnLongClickListener(x -> {
            x.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            showSoundSheet(rng);
            return true;
        });
        paintRinger(rng);
        map.put("ringer", rng);
        final Tile hs = new Tile(Icon.HOTSPOT, s.getString(R.string.tg_hotspot), 0xFF30D158, null, null);
        hs.disc.setOnClickListener(x -> openHotspot());
        hs.disc.setOnLongClickListener(x -> {
            openHotspot();
            return true;
        });
        hs.set(hotspotOn(), null);
        hotspotTile = hs;
        map.put("hotspot", hs);
        map.put("plane", tPlane);
        map.put("torch", tTorch);
        map.put("dark", tDark);
        map.put("rotate", tRotate);
        map.put("saver", tSaver);
        map.put("loc", tLoc);
        map.put("nfc", tNfc);
        map.put("shot", shot);
        map.put("rec", rec);
        map.put("awake", awk[0]);
        map.put("cam", cam);
        map.put("lock", lock);
        map.put("set", set);
        tileMap = map;
        // tekan lama Wi-Fi / Bluetooth: senarai terus dalam panel
        tWifi.disc.setOnLongClickListener(x -> {
            x.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            showWifiList();
            return true;
        });
        tBt.disc.setOnLongClickListener(x -> {
            x.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            showBtList();
            return true;
        });
        List<String> keys = tileKeys(map.keySet());
        java.util.Set<String> hiddenT = hiddenTiles();
        List<Tile> shown = new ArrayList<>();
        for (String k : keys) if (!hiddenT.contains(k)) shown.add(map.get(k));
        int nGrid = Math.min(9, shown.size());

        LinearLayout mid = new LinearLayout(s);
        mid.setClipChildren(false);
        LinearLayout grid = new LinearLayout(s);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        int cols = 3;
        int gridRows = Math.max(1, (nGrid + cols - 1) / cols);
        for (int i = 0; i < nGrid; i += cols) {
            LinearLayout row = new LinearLayout(s);
            row.setClipChildren(false);
            for (int j = 0; j < cols; j++) {
                if (i + j < nGrid) {
                    row.addView(shown.get(i + j).v, new LinearLayout.LayoutParams(0, -2, 1f));
                    sheet.drops.add(shown.get(i + j).v);
                } else {
                    row.addView(new View(s), new LinearLayout.LayoutParams(0, 1, 1f));
                }
            }
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
            rl.topMargin = dp(14);
            grid.addView(row, rl);
        }
        mid.addView(grid, new LinearLayout.LayoutParams(0, -2, 1f));
        vBright = new VSlider(Icon.SUN, this::setBrightness);
        vBright.onLong = this::showDisplaySheet;
        vVolume = new VSlider(Icon.SPEAKER, (v, done) -> {
            android.media.AudioManager am = s.getSystemService(android.media.AudioManager.class);
            int max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
            am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, Math.round(v * max), 0);
        });
        int sliderH = Math.max(2, gridRows) * (dp(58) + dp(22) + dp(14)) - dp(14);
        LinearLayout.LayoutParams vl = new LinearLayout.LayoutParams(dp(60), sliderH);
        vl.leftMargin = dp(8);
        vl.topMargin = dp(14);
        mid.addView(vBright, vl);
        LinearLayout.LayoutParams vl2 = new LinearLayout.LayoutParams(dp(60), sliderH);
        vl2.leftMargin = dp(10);
        vl2.topMargin = dp(14);
        mid.addView(vVolume, vl2);
        sheet.drops.add(vBright);
        sheet.drops.add(vVolume);
        b.addView(mid, new LinearLayout.LayoutParams(-1, -2));

        // muzik: sentiasa ada (cantik untuk screenshot); tiada lagu = tekan main untuk sambung
        View media = mediaCard(true);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-1, dp(150));
        mlp.topMargin = dp(18);
        b.addView(media, mlp);
        sheet.drops.add(media);

        // tile lain: barisan ikon kecil tanpa label (tekan lama untuk nama / tetapan)
        for (int i = nGrid; i < shown.size(); i += 8) {
            LinearLayout row2 = new LinearLayout(s);
            row2.setGravity(Gravity.CENTER_VERTICAL);
            for (int j = i; j < i + 8; j++) {
                if (j >= shown.size()) {
                    row2.addView(new View(s), new LinearLayout.LayoutParams(0, 1, 1f));
                    continue;
                }
                Tile t = shown.get(j);
                if (t.label != null) t.label.setVisibility(View.GONE);
                ViewGroup.LayoutParams dl = t.disc.getLayoutParams();
                dl.width = dp(46);
                dl.height = dp(46);
                t.disc.setLayoutParams(dl);
                row2.addView(t.v, new LinearLayout.LayoutParams(0, -2, 1f));
                sheet.drops.add(t.v);
            }
            LinearLayout.LayoutParams r2 = new LinearLayout.LayoutParams(-1, -2);
            r2.topMargin = dp(18);
            b.addView(row2, r2);
        }

        LinearLayout foot = new LinearLayout(s);
        foot.addView(chip(s.getString(R.string.system_panel), v -> {
            close();
            s.h.postDelayed(() -> s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS), 450);
        }), new LinearLayout.LayoutParams(0, dp(42), 1f));
        LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(0, dp(42), 1f);
        fl.leftMargin = gap;
        foot.addView(chip(s.getString(R.string.cc_settings), v -> launch(new Intent(s, MainActivity.class))), fl);
        LinearLayout.LayoutParams fe = new LinearLayout.LayoutParams(dp(84), dp(42));
        fe.leftMargin = gap;
        foot.addView(chip(s.getString(R.string.tile_edit), v -> showTileEditor()), fe);
        LinearLayout.LayoutParams fll = new LinearLayout.LayoutParams(-1, -2);
        fll.topMargin = dp(16);
        b.addView(foot, fll);
        sheet.drops.add(foot);

    }

    // ===================== Susunan tile =====================

    private java.util.Map<String, Tile> tileMap;

    private List<String> tileKeys(java.util.Set<String> all) {
        List<String> out = new ArrayList<>();
        String csv = s.prefs.getString(Prefs.TILE_ORDER, "");
        for (String k : csv.split(",")) if (all.contains(k) && !out.contains(k)) out.add(k);
        for (String k : all) if (!out.contains(k)) out.add(k);
        return out;
    }

    private java.util.Set<String> hiddenTiles() {
        java.util.Set<String> h = new java.util.HashSet<>();
        for (String k : s.prefs.getString(Prefs.TILE_HIDDEN, "").split(",")) if (!k.isEmpty()) h.add(k);
        return h;
    }

    // ===================== Lapisan senarai di atas panel =====================

    private FrameLayout sub;

    private void showSub(String title, View body, View headerExtra) {
        if (root == null) return;
        if (sub != null) {
            try { root.removeView(sub); } catch (Exception ignored) {}
            sub = null;
        }
        final FrameLayout ov = new FrameLayout(s);
        ov.setBackgroundColor(Color.argb(120, 0, 0, 0));
        ov.setClickable(true);
        ov.setOnClickListener(v -> closeSub());
        LinearLayout card = new LinearLayout(s);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.round(Color.argb(246, 20, 20, 24), dp(30)));
        card.setPadding(dp(18), dp(16), dp(18), dp(16));
        card.setClickable(true);
        card.setElevation(dp(12));
        LinearLayout hd = new LinearLayout(s);
        hd.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = Ui.line(s, 18, 700, Color.WHITE);
        t.setText(title);
        hd.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        if (headerExtra != null) hd.addView(headerExtra);
        ImageView x = new ImageView(s);
        x.setImageDrawable(Ui.iconDrawable(s, Icon.CLOSE, Color.WHITE, Color.argb(60, 255, 255, 255), dp(30)));
        x.setOnClickListener(v -> closeSub());
        LinearLayout.LayoutParams xl = new LinearLayout.LayoutParams(dp(30), dp(30));
        xl.leftMargin = dp(10);
        hd.addView(x, xl);
        card.addView(hd, new LinearLayout.LayoutParams(-1, -2));
        final int maxH = Math.round(s.screenH * 0.62f);
        ScrollView sc = new ScrollView(s) {
            @Override
            protected void onMeasure(int w, int h) {
                super.onMeasure(w, MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST));
            }
        };
        sc.setVerticalScrollBarEnabled(false);
        sc.addView(body, new ScrollView.LayoutParams(-1, -2));
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2);
        sl.topMargin = dp(10);
        card.addView(sc, sl);
        ov.addView(card, new FrameLayout.LayoutParams(Math.min(s.screenW - dp(28), dp(440)), -2, Gravity.CENTER));
        root.addView(ov, new FrameLayout.LayoutParams(-1, -1));
        sub = ov;
        ov.setAlpha(0f);
        ov.animate().alpha(1f).setInterpolator(Ui.EASE).setDuration(200).start();
        card.setScaleX(0.9f);
        card.setScaleY(0.9f);
        card.setTranslationY(dp(24));
        card.animate().scaleX(1f).scaleY(1f).translationY(0).setInterpolator(Ui.SPRING).setDuration(Ui.SPRING_MS).start();
    }

    private void closeSub() {
        final FrameLayout ov = sub;
        if (ov == null) return;
        sub = null;
        ov.animate().alpha(0f).setInterpolator(Ui.EXIT).setDuration(160).withEndAction(() -> {
            ViewGroup p = (ViewGroup) ov.getParent();
            if (p != null) p.removeView(ov);
        }).start();
    }

    private View subRow(int icon, int iconBg, String title, String subtitle, View right, View.OnClickListener tap) {
        LinearLayout r = new LinearLayout(s);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(4), dp(9), dp(4), dp(9));
        if (icon != 0) {
            ImageView iv = new ImageView(s);
            iv.setImageDrawable(Ui.iconDrawable(s, icon, Color.WHITE, iconBg, dp(36)));
            r.addView(iv, new LinearLayout.LayoutParams(dp(36), dp(36)));
        }
        LinearLayout col = new LinearLayout(s);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(12), 0, dp(8), 0);
        TextView a = Ui.line(s, 15, 600, Color.WHITE);
        a.setText(title);
        col.addView(a);
        if (subtitle != null) {
            TextView b = Ui.line(s, 12, 500, Color.argb(150, 235, 235, 245));
            b.setText(subtitle);
            col.addView(b);
        }
        r.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        if (right != null) r.addView(right);
        if (tap != null) {
            r.setOnClickListener(tap);
            r.setBackground(Ui.round(Color.TRANSPARENT, dp(14)));
            Ui.press(r);
        }
        return r;
    }

    private TextView subNote(String txt) {
        TextView t = Ui.text(s, 13, 500, Color.argb(160, 235, 235, 245));
        t.setText(txt);
        t.setPadding(dp(4), dp(8), dp(4), dp(8));
        return t;
    }

    // ===================== Sunting tile =====================

    private void showTileEditor() {
        if (tileMap == null) return;
        final List<String> order = tileKeys(tileMap.keySet());
        final java.util.Set<String> hid = hiddenTiles();
        final LinearLayout list = new LinearLayout(s);
        list.setOrientation(LinearLayout.VERTICAL);
        list.addView(subNote(s.getString(R.string.tile_edit_note)));
        final LinearLayout rows = new LinearLayout(s);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setClipChildren(false);
        for (String k : order) rows.addView(editRow(k, rows, order, hid), new LinearLayout.LayoutParams(-1, dp(58)));
        list.addView(rows);
        TextView done = Ui.line(s, 15, 700, 0xFF0B0B10);
        done.setText(R.string.done);
        done.setGravity(Gravity.CENTER);
        done.setBackground(Ui.round(Color.WHITE, dp(23)));
        Ui.press(done);
        done.setOnClickListener(v -> {
            s.prefs.edit().putString(Prefs.TILE_ORDER, String.join(",", order))
                    .putString(Prefs.TILE_HIDDEN, String.join(",", hid)).apply();
            closeSub();
            rebuildLiquid();
        });
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(-1, dp(46));
        dl.topMargin = dp(12);
        list.addView(done, dl);
        TextView reset = Ui.line(s, 13, 600, Color.argb(170, 235, 235, 245));
        reset.setText(R.string.tile_reset);
        reset.setGravity(Gravity.CENTER);
        reset.setPadding(0, dp(12), 0, dp(2));
        reset.setOnClickListener(v -> {
            s.prefs.edit().remove(Prefs.TILE_ORDER).remove(Prefs.TILE_HIDDEN).apply();
            closeSub();
            rebuildLiquid();
        });
        list.addView(reset, new LinearLayout.LayoutParams(-1, -2));
        showSub(s.getString(R.string.tile_edit_title), list, null);
    }

    private View editRow(final String key, final LinearLayout rows, final List<String> order, final java.util.Set<String> hid) {
        Tile t = tileMap.get(key);
        final LinearLayout r = new LinearLayout(s);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setBackground(Ui.round(Color.argb(255, 28, 28, 33), dp(16)));
        r.setPadding(dp(6), 0, dp(10), 0);
        final TextView hnd = Ui.line(s, 22, 500, Color.argb(170, 235, 235, 245));
        hnd.setText("≡");
        hnd.setGravity(Gravity.CENTER);
        r.addView(hnd, new LinearLayout.LayoutParams(dp(40), -1));
        ImageView iv = new ImageView(s);
        iv.setImageDrawable(Ui.iconDrawable(s, t.kind, t.onColor == Color.WHITE ? 0xFF111114 : Color.WHITE,
                t.onColor, dp(32)));
        r.addView(iv, new LinearLayout.LayoutParams(dp(32), dp(32)));
        TextView n = Ui.line(s, 15, 600, Color.WHITE);
        n.setText(t.name);
        n.setPadding(dp(12), 0, 0, 0);
        r.addView(n, new LinearLayout.LayoutParams(0, -2, 1f));
        android.widget.Switch sw = new android.widget.Switch(s);
        sw.setChecked(!hid.contains(key));
        sw.setOnCheckedChangeListener((b, on) -> {
            if (on) hid.remove(key); else hid.add(key);
            r.animate().alpha(on ? 1f : 0.55f).setDuration(150).start();
        });
        r.setAlpha(hid.contains(key) ? 0.55f : 1f);
        r.addView(sw);
        // seret pemegang untuk susun semula
        final float[] y0 = new float[1];
        hnd.setOnTouchListener((v, e) -> {
            int idx = rows.indexOfChild(r);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    y0[0] = e.getRawY();
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                    r.setElevation(dp(10));
                    r.animate().scaleX(1.03f).scaleY(1.03f).setInterpolator(Ui.EASE).setDuration(120).start();
                    r.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dy = e.getRawY() - y0[0];
                    int rh = r.getHeight();
                    if (rh > 0 && dy > rh * 0.55f && idx < rows.getChildCount() - 1) {
                        View nb = rows.getChildAt(idx + 1);
                        rows.removeView(nb);
                        rows.addView(nb, idx);
                        java.util.Collections.swap(order, idx, idx + 1);
                        y0[0] += rh;
                        dy -= rh;
                        nb.setTranslationY(rh);
                        nb.animate().translationY(0).setInterpolator(Ui.SPRING).setDuration(Ui.SPRING_MS).start();
                        r.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
                    } else if (rh > 0 && dy < -rh * 0.55f && idx > 0) {
                        View nb = rows.getChildAt(idx - 1);
                        rows.removeView(nb);
                        rows.addView(nb, idx);
                        java.util.Collections.swap(order, idx, idx - 1);
                        y0[0] -= rh;
                        dy += rh;
                        nb.setTranslationY(-rh);
                        nb.animate().translationY(0).setInterpolator(Ui.SPRING).setDuration(Ui.SPRING_MS).start();
                        r.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
                    }
                    r.setTranslationY(dy);
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    r.setElevation(0);
                    r.animate().translationY(0).scaleX(1f).scaleY(1f).setInterpolator(Ui.SPRING).setDuration(Ui.SPRING_MS).start();
                    return true;
            }
            return true;
        });
        return r;
    }

    /** Bina semula isi panel di tempat (tanpa tutup / tangkap skrin semula). */
    private void rebuildLiquid() {
        if (liquid == null || open != QS) return;
        LiquidSheet sh = liquid;
        sh.body.removeAllViews();
        sh.drops.clear();
        LiquidGlass.current = bd;
        fillLiquid(sh, dp(8));
        LiquidGlass.current = null;
        refreshControl();
        sh.setProgress(1f);
        if (root != null) LiquidGlass.invalidateTree(root);
    }

    // ===================== Mod bunyi (Bunyi / Getar / Senyap) & kelantangan =====================

    private android.media.AudioManager audio() { return s.getSystemService(android.media.AudioManager.class); }

    private void paintRinger(Tile t) {
        if (t == null) return;
        int m = audio().getRingerMode();
        // warna ikut mod: Bunyi = biru, Getar = oren, Senyap = merah
        t.onColor = m == android.media.AudioManager.RINGER_MODE_SILENT ? 0xFFFF3B30
                : m == android.media.AudioManager.RINGER_MODE_VIBRATE ? 0xFFFF9F0A : 0xFF0A84FF;
        t.set(true, null);
        if (t.label != null) t.label.setText(m == android.media.AudioManager.RINGER_MODE_SILENT ? R.string.rg_silent
                : m == android.media.AudioManager.RINGER_MODE_VIBRATE ? R.string.rg_vibrate : R.string.rg_sound);
    }

    private void cycleRinger() {
        int m = audio().getRingerMode();
        int next = m == android.media.AudioManager.RINGER_MODE_NORMAL ? android.media.AudioManager.RINGER_MODE_VIBRATE
                : m == android.media.AudioManager.RINGER_MODE_VIBRATE ? android.media.AudioManager.RINGER_MODE_SILENT
                : android.media.AudioManager.RINGER_MODE_NORMAL;
        setRinger(next);
    }

    /** Tukar mod; jika Android minta akses Jangan Ganggu, beri melalui Shizuku dan cuba lagi. */
    void setRinger(final int mode) {
        try {
            audio().setRingerMode(mode);
            ringerFeedback(mode);
        } catch (SecurityException e) {
            if (!Shell.granted()) {
                Toast.makeText(s, R.string.need_shizuku, Toast.LENGTH_SHORT).show();
                return;
            }
            Shell.run("cmd notification allow_dnd " + s.getPackageName(), (ok, out) -> s.h.postDelayed(() -> {
                try {
                    audio().setRingerMode(mode);
                    ringerFeedback(mode);
                } catch (Exception ex) {
                    Toast.makeText(s, R.string.t_fail, Toast.LENGTH_SHORT).show();
                }
            }, 250));
        }
    }

    private void ringerFeedback(int mode) {
        if (mode == android.media.AudioManager.RINGER_MODE_VIBRATE) {
            // getaran jelas (dua denyut) sebagai tanda mod Getar sudah hidup
            s.h.postDelayed(() -> {
                try {
                    android.os.Vibrator vb = s.getSystemService(android.os.Vibrator.class);
                    if (vb == null || !vb.hasVibrator()) return;
                    android.os.VibrationEffect ef = android.os.VibrationEffect.createWaveform(new long[]{0, 220, 110, 220}, -1);
                    if (android.os.Build.VERSION.SDK_INT >= 33) {
                        vb.vibrate(ef, android.os.VibrationAttributes.createForUsage(android.os.VibrationAttributes.USAGE_NOTIFICATION));
                    } else {
                        vb.vibrate(ef);
                    }
                } catch (Throwable ignored) {
                }
            }, 120);
        }
        Toast.makeText(s, mode == android.media.AudioManager.RINGER_MODE_SILENT ? R.string.rg_silent
                : mode == android.media.AudioManager.RINGER_MODE_VIBRATE ? R.string.rg_vibrate : R.string.rg_sound, Toast.LENGTH_SHORT).show();
    }

    private void showSoundSheet(final Tile tile) {
        final LinearLayout list = new LinearLayout(s);
        list.setOrientation(LinearLayout.VERTICAL);
        // pilihan mod
        final LinearLayout modes = new LinearLayout(s);
        modes.setPadding(0, dp(4), 0, dp(8));
        final int[] mv = {android.media.AudioManager.RINGER_MODE_NORMAL, android.media.AudioManager.RINGER_MODE_VIBRATE,
                android.media.AudioManager.RINGER_MODE_SILENT};
        final int[] mn = {R.string.rg_sound, R.string.rg_vibrate, R.string.rg_silent};
        final TextView[] chips = new TextView[3];
        final Runnable[] paint = new Runnable[1];
        final List<Runnable> sliderRefresh = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            final int m = mv[i];
            TextView c = Ui.line(s, 14, 650, Color.WHITE);
            c.setText(mn[i]);
            c.setGravity(Gravity.CENTER);
            Ui.press(c);
            c.setOnClickListener(v -> {
                setRinger(m);
                s.h.postDelayed(() -> {
                    paint[0].run();
                    for (Runnable r : sliderRefresh) r.run();
                    paintRinger(tile);
                }, 350);
            });
            chips[i] = c;
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, dp(40), 1f);
            if (i > 0) cl.leftMargin = dp(8);
            modes.addView(c, cl);
        }
        paint[0] = () -> {
            int cur = audio().getRingerMode();
            for (int i = 0; i < 3; i++) {
                boolean on = mv[i] == cur;
                chips[i].setBackground(Ui.round(on ? Color.WHITE : Color.argb(40, 255, 255, 255), dp(20)));
                chips[i].setTextColor(on ? 0xFF0B0B10 : Color.WHITE);
            }
        };
        paint[0].run();
        list.addView(modes, new LinearLayout.LayoutParams(-1, -2));
        int[] streams = {android.media.AudioManager.STREAM_RING, android.media.AudioManager.STREAM_NOTIFICATION,
                android.media.AudioManager.STREAM_ALARM, android.media.AudioManager.STREAM_VOICE_CALL};
        int[] names = {R.string.vol_ring, R.string.vol_notif, R.string.vol_alarm, R.string.vol_call};
        int[] icons = {Icon.BELL, Icon.NOTE, Icon.MOON, Icon.PHONE};
        for (int i = 0; i < streams.length; i++) {
            list.addView(volRow(streams[i], names[i], icons[i], () -> {
                paint[0].run();
                paintRinger(tile);
            }, sliderRefresh));
        }
        list.addView(subNote(s.getString(R.string.vol_note)));
        showSub(s.getString(R.string.rg_title), list, null);
    }

    private View volRow(final int stream, int name, int icon, final Runnable onChange, List<Runnable> refreshers) {
        final android.media.AudioManager am = audio();
        LinearLayout col = new LinearLayout(s);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, dp(8), 0, dp(4));
        LinearLayout top = new LinearLayout(s);
        top.setGravity(Gravity.CENTER_VERTICAL);
        ImageView iv = new ImageView(s);
        iv.setImageDrawable(Ui.iconDrawable(s, icon, Color.WHITE, Color.argb(60, 255, 255, 255), dp(28)));
        top.addView(iv, new LinearLayout.LayoutParams(dp(28), dp(28)));
        TextView t = Ui.line(s, 14, 600, Color.WHITE);
        t.setText(name);
        t.setPadding(dp(10), 0, 0, 0);
        top.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        final TextView pct = Ui.line(s, 13, 600, Color.argb(170, 235, 235, 245));
        top.addView(pct);
        col.addView(top);
        final android.widget.SeekBar sb = new android.widget.SeekBar(s);
        final int min = am.getStreamMinVolume(stream), max = am.getStreamMaxVolume(stream);
        sb.setMin(min);
        sb.setMax(max);
        sb.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
        sb.setThumbTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
        sb.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.argb(90, 255, 255, 255)));
        final Runnable refresh = () -> {
            int v = am.getStreamVolume(stream);
            sb.setProgress(v);
            pct.setText(Math.round(100f * v / Math.max(1, max)) + "%");
        };
        refresh.run();
        refreshers.add(refresh);
        sb.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar b, int v, boolean user) {
                if (!user) return;
                pct.setText(Math.round(100f * v / Math.max(1, max)) + "%");
                setVol(stream, v, 0);
            }

            @Override
            public void onStartTrackingTouch(android.widget.SeekBar b) { }

            @Override
            public void onStopTrackingTouch(android.widget.SeekBar b) {
                // dengar contoh bunyi pada tahap baharu
                setVol(stream, b.getProgress(), stream == android.media.AudioManager.STREAM_VOICE_CALL ? 0
                        : android.media.AudioManager.FLAG_PLAY_SOUND);
                s.h.postDelayed(() -> {
                    refresh.run();
                    onChange.run();
                }, 250);
            }
        });
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, dp(36));
        col.addView(sb, sl);
        return col;
    }

    private void setVol(int stream, int v, int flags) {
        try {
            audio().setStreamVolume(stream, v, flags);
        } catch (SecurityException e) {
            if (Shell.granted()) Shell.run("cmd notification allow_dnd " + s.getPackageName());
        }
    }

    // ===================== Hotspot =====================

    private Tile hotspotTile;

    /** Keadaan hotspot (WIFI_AP_STATE_ENABLED = 13). */
    private boolean hotspotOn() {
        try {
            android.net.wifi.WifiManager wmg = s.getSystemService(android.net.wifi.WifiManager.class);
            java.lang.reflect.Method m = wmg.getClass().getMethod("getWifiApState");
            return ((Integer) m.invoke(wmg)) == 13;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Android tidak benarkan app hidupkan hotspot terus: buka halaman Hotspot sistem (satu ketikan lagi). */
    private void openHotspot() {
        String[][] targets = {
                {"com.android.settings", "com.android.settings.Settings$WifiTetherSettingsActivity"},
                {"com.android.settings", "com.android.settings.Settings$TetherSettingsActivity"},
                {"com.android.settings", "com.android.settings.TetherSettings"}};
        for (String[] t : targets) {
            Intent i = new Intent().setClassName(t[0], t[1]);
            if (s.getPackageManager().resolveActivity(i, 0) != null) {
                launch(i);
                return;
            }
        }
        launch(new Intent(Settings.ACTION_WIRELESS_SETTINGS));
    }

    void onHotspotChanged() {
        if (hotspotTile != null) hotspotTile.set(hotspotOn(), null);
    }

    // ===================== Kecerahan: auto & Cahaya Malam =====================

    private void showDisplaySheet() {
        final android.content.ContentResolver cr = s.getContentResolver();
        LinearLayout list = new LinearLayout(s);
        list.setOrientation(LinearLayout.VERTICAL);
        android.widget.Switch auto = new android.widget.Switch(s);
        int mode = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, 0);
        auto.setChecked(mode == 1);
        list.addView(subRow(Icon.SUN, 0xFFFF9F0A, s.getString(R.string.disp_auto), s.getString(R.string.disp_auto_sub), auto, null));
        auto.setOnCheckedChangeListener((b, on) -> {
            boolean ok = false;
            try {
                if (Settings.System.canWrite(s)) ok = Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, on ? 1 : 0);
            } catch (Exception ignored) {
            }
            if (!ok && Shell.granted()) Shell.run("settings put system screen_brightness_mode " + (on ? 1 : 0));
            b.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
        });
        android.widget.Switch night = new android.widget.Switch(s);
        night.setChecked(Settings.Secure.getInt(cr, "night_display_activated", 0) == 1);
        list.addView(subRow(Icon.MOON, 0xFFFF9500, s.getString(R.string.disp_night), s.getString(R.string.disp_night_sub), night, null));
        // kekuatan warna kuning (suhu warna: lebih rendah = lebih kuning)
        LinearLayout sl = new LinearLayout(s);
        sl.setOrientation(LinearLayout.VERTICAL);
        sl.setPadding(dp(4), dp(4), dp(4), dp(8));
        TextView lt = Ui.line(s, 13, 600, Color.argb(190, 235, 235, 245));
        lt.setText(R.string.disp_night_str);
        sl.addView(lt);
        final android.widget.SeekBar sb = new android.widget.SeekBar(s);
        final int minK = 2600, maxK = 4300;
        sb.setMax(100);
        int cur = Settings.Secure.getInt(cr, "night_display_color_temperature", 3300);
        sb.setProgress(Math.round(100f * (maxK - Math.max(minK, Math.min(maxK, cur))) / (maxK - minK)));
        sb.setProgressTintList(android.content.res.ColorStateList.valueOf(0xFFFFB340));
        sb.setThumbTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
        sb.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.argb(90, 255, 255, 255)));
        sb.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar b, int v, boolean user) { }

            @Override
            public void onStartTrackingTouch(android.widget.SeekBar b) { }

            @Override
            public void onStopTrackingTouch(android.widget.SeekBar b) {
                int k = maxK - Math.round((maxK - minK) * b.getProgress() / 100f);
                if (Shell.granted()) {
                    Shell.run("settings put secure night_display_color_temperature " + k
                            + (night.isChecked() ? "" : "; settings put secure night_display_activated 1"));
                    night.setChecked(true);
                }
            }
        });
        sl.addView(sb, new LinearLayout.LayoutParams(-1, dp(36)));
        list.addView(sl);
        night.setOnCheckedChangeListener((b, on) -> {
            if (!b.isPressed()) return;
            if (!Shell.granted()) {
                Toast.makeText(s, R.string.need_shizuku, Toast.LENGTH_SHORT).show();
                b.setChecked(!on);
                return;
            }
            Shell.run("settings put secure night_display_activated " + (on ? 1 : 0));
            b.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
        });
        list.addView(subNote(s.getString(R.string.disp_note)));
        showSub(s.getString(R.string.disp_title), list, null);
    }

    // ===================== Senarai Wi-Fi =====================

    private static String q(String v) { return "'" + v.replace("'", "'\\''") + "'"; }

    private static final class Net {
        String ssid, sec;
        int rssi = -127;
        boolean saved, current;
    }

    private void showWifiList() {
        final LinearLayout list = new LinearLayout(s);
        list.setOrientation(LinearLayout.VERTICAL);
        list.addView(subNote(s.getString(R.string.wifi_scanning)));
        View settingsBtn = chip(s.getString(R.string.cc_settings), v -> launch(new Intent(Settings.Panel.ACTION_WIFI)));
        settingsBtn.setPadding(dp(14), dp(7), dp(14), dp(7));
        showSub(s.getString(R.string.tg_wifi), list, settingsBtn);
        if (!Shell.granted()) {
            list.removeAllViews();
            list.addView(subNote(s.getString(R.string.need_shizuku)));
            return;
        }
        final FrameLayout mySub = sub;
        Shell.run("cmd wifi start-scan >/dev/null 2>&1; sleep 2; cmd wifi list-scan-results; echo '@@@'; cmd wifi list-networks; echo '@@@'; cmd wifi status",
                (ok, out) -> {
                    if (sub != mySub || out == null) return;
                    fillWifi(list, out);
                });
    }

    private void fillWifi(LinearLayout list, String out) {
        String[] parts = out.split("@@@");
        java.util.Map<String, Net> nets = new java.util.LinkedHashMap<>();
        java.util.regex.Pattern row = java.util.regex.Pattern.compile(
                "^\\s*([0-9a-fA-F:]{17})\\s+(\\d+)\\s+(-?\\d+)\\s+(\\S+)\\s+(.*?)\\s*(\\[.*\\])?\\s*$");
        for (String l : parts[0].split("\n")) {
            Matcher m = row.matcher(l);
            if (!m.find()) continue;
            String ssid = m.group(5).trim();
            if (ssid.isEmpty()) continue;
            int rssi = Integer.parseInt(m.group(3));
            String fl = m.group(6) == null ? "" : m.group(6);
            Net n = nets.get(ssid);
            if (n == null) {
                n = new Net();
                n.ssid = ssid;
                nets.put(ssid, n);
            }
            if (rssi > n.rssi) n.rssi = rssi;
            n.sec = fl.contains("EAP") ? "eap" : fl.contains("PSK") ? "wpa2" : fl.contains("SAE") ? "wpa3"
                    : fl.contains("OWE") ? "owe" : fl.contains("WEP") ? "wep" : "open";
        }
        if (parts.length > 1) {
            java.util.regex.Pattern sv = java.util.regex.Pattern.compile("^\\s*\\d+\\s+(.+?)\\s{2,}\\S+.*$");
            for (String l : parts[1].split("\n")) {
                Matcher m = sv.matcher(l);
                if (m.find() && nets.containsKey(m.group(1).trim())) nets.get(m.group(1).trim()).saved = true;
            }
        }
        String cur = null;
        if (parts.length > 2) {
            Matcher m = Pattern.compile("SSID: \"([^\"]+)\"").matcher(parts[2]);
            if (m.find()) cur = m.group(1);
            else {
                Matcher m2 = Pattern.compile("connected to \"([^\"]+)\"").matcher(parts[2]);
                if (m2.find()) cur = m2.group(1);
            }
        }
        if (cur != null && nets.containsKey(cur)) nets.get(cur).current = true;
        List<Net> l = new ArrayList<>(nets.values());
        l.sort((a, b) -> a.current != b.current ? (a.current ? -1 : 1) : a.saved != b.saved ? (a.saved ? -1 : 1) : b.rssi - a.rssi);
        list.removeAllViews();
        if (l.isEmpty()) {
            list.addView(subNote(s.getString(R.string.wifi_none)));
            return;
        }
        for (final Net n : l) {
            int bars = n.rssi >= -55 ? 4 : n.rssi >= -66 ? 3 : n.rssi >= -77 ? 2 : 1;
            String st = n.current ? s.getString(R.string.wifi_connected) : n.saved ? s.getString(R.string.wifi_saved)
                    : n.sec.equals("open") || n.sec.equals("owe") ? s.getString(R.string.wifi_open) : s.getString(R.string.wifi_secured);
            st += " · " + bars + "/4";
            list.addView(subRow(Icon.WIFI, n.current ? 0xFF0A84FF : Color.argb(60, 255, 255, 255), n.ssid, st, null, v -> {
                if (n.current) {
                    closeSub();
                } else if (n.saved || n.sec.equals("eap") || n.sec.equals("wep")) {
                    // rangkaian disimpan: Android tak benarkan sambung terus tanpa kata laluan
                    Toast.makeText(s, R.string.wifi_saved_hint, Toast.LENGTH_LONG).show();
                    launch(new Intent(Settings.Panel.ACTION_WIFI));
                } else if (n.sec.equals("open") || n.sec.equals("owe")) {
                    connectWifi(n.ssid, n.sec, null);
                } else {
                    askWifiPass(list, n);
                }
            }));
        }
    }

    private void askWifiPass(LinearLayout list, Net n) {
        list.removeAllViews();
        list.addView(subNote(s.getString(R.string.wifi_pass_for, n.ssid)));
        final android.widget.EditText e = new android.widget.EditText(s);
        e.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        e.setTextColor(Color.WHITE);
        e.setHintTextColor(Color.argb(120, 255, 255, 255));
        e.setHint(R.string.wifi_pass);
        e.setSingleLine(true);
        e.setBackground(Ui.round(Color.argb(40, 255, 255, 255), dp(14)));
        e.setPadding(dp(14), dp(12), dp(14), dp(12));
        list.addView(e, new LinearLayout.LayoutParams(-1, -2));
        TextView go = Ui.line(s, 15, 700, 0xFF0B0B10);
        go.setText(R.string.wifi_join);
        go.setGravity(Gravity.CENTER);
        go.setBackground(Ui.round(Color.WHITE, dp(23)));
        Ui.press(go);
        go.setOnClickListener(v -> {
            String p = e.getText().toString();
            if (p.length() < 8) {
                e.setError(s.getString(R.string.wifi_pass_short));
                return;
            }
            connectWifi(n.ssid, n.sec, p);
        });
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(-1, dp(46));
        gl.topMargin = dp(12);
        list.addView(go, gl);
        e.requestFocus();
        e.postDelayed(() -> {
            android.view.inputmethod.InputMethodManager im = s.getSystemService(android.view.inputmethod.InputMethodManager.class);
            if (im != null) im.showSoftInput(e, 0);
        }, 200);
    }

    private void connectWifi(final String ssid, String sec, String pass) {
        Toast.makeText(s, s.getString(R.string.wifi_joining, ssid), Toast.LENGTH_SHORT).show();
        closeSub();
        Shell.run("cmd wifi connect-network " + q(ssid) + " " + sec + (pass != null ? " " + q(pass) : ""), (ok, out) ->
                s.h.postDelayed(() -> Shell.run("cmd wifi status", (ok2, st) -> {
                    boolean on = st != null && st.contains("\"" + ssid + "\"");
                    Toast.makeText(s, s.getString(on ? R.string.wifi_joined : R.string.wifi_join_fail, ssid), Toast.LENGTH_SHORT).show();
                    fetchSsid();
                }), 7000));
    }

    // ===================== Senarai Bluetooth =====================

    private void showBtList() {
        final LinearLayout list = new LinearLayout(s);
        list.setOrientation(LinearLayout.VERTICAL);
        View settingsBtn = chip(s.getString(R.string.cc_settings), v -> launch(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)));
        settingsBtn.setPadding(dp(14), dp(7), dp(14), dp(7));
        showSub(s.getString(R.string.tg_bt), list, settingsBtn);
        final FrameLayout mySub = sub;
        android.bluetooth.BluetoothManager bm = s.getSystemService(android.bluetooth.BluetoothManager.class);
        final android.bluetooth.BluetoothAdapter ad = bm == null ? null : bm.getAdapter();
        if (ad == null) {
            list.addView(subNote(s.getString(R.string.bt_none)));
            return;
        }
        if (!ad.isEnabled()) {
            list.addView(subRow(Icon.BT, Color.argb(60, 255, 255, 255), s.getString(R.string.bt_off), s.getString(R.string.bt_tap_on), null, v -> {
                toggleBt();
                closeSub();
            }));
            return;
        }
        final java.util.Set<String> connected = new java.util.HashSet<>();
        final int[] pending = {0};
        final Runnable render = () -> {
            if (sub != mySub) return;
            list.removeAllViews();
            java.util.Set<android.bluetooth.BluetoothDevice> bonded;
            try {
                bonded = ad.getBondedDevices();
            } catch (SecurityException e) {
                list.addView(subNote(s.getString(R.string.bt_perm)));
                return;
            }
            if (bonded == null || bonded.isEmpty()) {
                list.addView(subNote(s.getString(R.string.bt_none)));
                return;
            }
            List<android.bluetooth.BluetoothDevice> ds = new ArrayList<>(bonded);
            ds.sort((a, b) -> Boolean.compare(connected.contains(b.getAddress()), connected.contains(a.getAddress())));
            for (android.bluetooth.BluetoothDevice d : ds) {
                String nm;
                try {
                    nm = d.getAlias() != null ? d.getAlias() : d.getName();
                } catch (SecurityException e) {
                    nm = d.getAddress();
                }
                boolean on = connected.contains(d.getAddress());
                int major = 0;
                try {
                    android.bluetooth.BluetoothClass bc = d.getBluetoothClass();
                    if (bc != null) major = bc.getMajorDeviceClass();
                } catch (SecurityException ignored) {
                }
                int icon = major == android.bluetooth.BluetoothClass.Device.Major.AUDIO_VIDEO ? Icon.SPEAKER : Icon.BT;
                list.addView(subRow(icon, on ? 0xFF0A84FF : Color.argb(60, 255, 255, 255), nm == null ? d.getAddress() : nm,
                        s.getString(on ? R.string.wifi_connected : R.string.bt_paired), null,
                        v -> {
                            Toast.makeText(s, R.string.bt_hint, Toast.LENGTH_LONG).show();
                            launch(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
                        }));
            }
        };
        int[] profiles = {android.bluetooth.BluetoothProfile.A2DP, android.bluetooth.BluetoothProfile.HEADSET, 22 /* LE_AUDIO */};
        for (int pf : profiles) {
            try {
                boolean okp = ad.getProfileProxy(s, new android.bluetooth.BluetoothProfile.ServiceListener() {
                    @Override
                    public void onServiceConnected(int profile, android.bluetooth.BluetoothProfile proxy) {
                        try {
                            for (android.bluetooth.BluetoothDevice d : proxy.getConnectedDevices()) connected.add(d.getAddress());
                        } catch (SecurityException ignored) {
                        }
                        try { ad.closeProfileProxy(profile, proxy); } catch (Exception ignored) {}
                        if (--pending[0] == 0) s.h.post(render);
                    }

                    @Override
                    public void onServiceDisconnected(int profile) { }
                }, pf);
                if (okp) pending[0]++;
            } catch (Exception ignored) {
            }
        }
        list.addView(subNote(s.getString(R.string.wifi_scanning)));
        if (pending[0] == 0) render.run();
        else s.h.postDelayed(render, 900); // jaga-jaga jika profil lambat
    }

    private LinearLayout pair(int w, int gap, int bw, int bh, View a, View b, int top) {
        LinearLayout r = new LinearLayout(s);
        r.addView(a, new LinearLayout.LayoutParams(bw, bh));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(bw, bh);
        p.leftMargin = gap;
        r.addView(b, p);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(w, -2);
        rl.topMargin = top;
        r.setLayoutParams(rl);
        return r;
    }

    private View handle() {
        View hnd = new View(s);
        hnd.setBackground(Ui.round(Color.argb(140, 255, 255, 255), dp(3)));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(36), dp(5));
        p.topMargin = dp(18);
        p.gravity = Gravity.CENTER_HORIZONTAL;
        hnd.setLayoutParams(p);
        return hnd;
    }

    private View chip(String label, View.OnClickListener l) {
        TextView t = Ui.line(s, 13, 600, Color.WHITE);
        t.setText(label);
        t.setGravity(Gravity.CENTER);
        t.setBackground(Ui.glassStyled(s, 22, dark));
        t.setOnClickListener(l);
        Ui.press(t);
        return t;
    }

    // ===================== Kad media gaya Pixel =====================

    private View mediaCard() { return mediaCard(false); }

    private View mediaCard(boolean always) {
        if (!always && (s.mc == null || (s.title == null && !s.playing))) {
            mediaCard = null;
            return null;
        }
        FrameLayout card = new FrameLayout(s);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline o) {
                o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(28));
            }
        });
        card.setClipToOutline(true);
        card.setBackground(Ui.glassStyled(s, 28, dark));
        mArt = new ImageView(s);
        mArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        mArt.setAlpha(0.55f);
        card.addView(mArt, new FrameLayout.LayoutParams(-1, -1));
        View shade = new View(s);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Color.argb(200, 0, 0, 0), Color.argb(90, 0, 0, 0)}));
        card.addView(shade, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout col = new LinearLayout(s);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(18), dp(16), dp(14), dp(14));
        mTitle = Ui.line(s, 18, 600, Color.WHITE);
        mArtist = Ui.line(s, 14, 400, Color.argb(200, 255, 255, 255));
        mArtist.setPadding(0, dp(4), 0, 0);
        col.addView(mTitle);
        col.addView(mArtist);
        col.addView(new View(s), new LinearLayout.LayoutParams(1, 0, 1f));
        LinearLayout ctr = new LinearLayout(s);
        ctr.setGravity(Gravity.CENTER_VERTICAL);
        mBar = new Widgets.Bar(s);
        ctr.addView(mBar, new LinearLayout.LayoutParams(0, dp(4), 1f));
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(dp(48), dp(48));
        pl.leftMargin = dp(10);
        ctr.addView(btn(Icon.PREV, 20, 0, v -> { if (s.mc != null) s.mc.getTransportControls().skipToPrevious(); }), pl);
        FrameLayout pb = btn(Icon.PLAY, 22, Ui.accent(s, true), v -> s.togglePlay());
        mPlay = (Icon) pb.getChildAt(0);
        mPlay.setColor(Ui.onAccent(s));
        ctr.addView(pb, new LinearLayout.LayoutParams(dp(54), dp(48)));
        ctr.addView(btn(Icon.NEXT, 20, 0, v -> { if (s.mc != null) s.mc.getTransportControls().skipToNext(); }),
                new LinearLayout.LayoutParams(dp(48), dp(48)));
        col.addView(ctr, new LinearLayout.LayoutParams(-1, -2));
        card.addView(col, new FrameLayout.LayoutParams(-1, -1));
        card.setOnClickListener(v -> {
            if (s.mc != null) {
                s.sendIntent(s.mc.getSessionActivity());
                close();
            }
        });
        mediaCard = card;
        updateMedia();
        return card;
    }

    private FrameLayout btn(int kind, int size, int bg, View.OnClickListener l) {
        FrameLayout f = new FrameLayout(s);
        if (bg != 0) f.setBackground(Ui.round(bg, dp(18)));
        f.addView(new Icon(s, kind, Color.WHITE), new FrameLayout.LayoutParams(dp(size), dp(size), Gravity.CENTER));
        f.setOnClickListener(l);
        Ui.press(f);
        return f;
    }

    void onMediaChanged() {
        if (mediaCard != null) updateMedia();
    }

    private void updateMedia() {
        if (mTitle == null) return;
        boolean none = s.mc == null || (s.title == null && !s.playing);
        mTitle.setText(none ? s.getString(R.string.not_playing) : s.title != null ? s.title : s.getString(R.string.no_title));
        mArtist.setText(none ? s.getString(R.string.tap_to_resume) : s.artist != null ? s.artist : "");
        if (s.art != null) {
            mArt.setImageBitmap(s.art);
        } else {
            // tiada kulit album: kecerunan warna lembut supaya kad tetap cantik
            mArt.setImageDrawable(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{0xFF5E5CE6, 0xFFBF5AF2, 0xFFFF375F}));
        }
        mPlay.setKind(s.playing ? Icon.PAUSE : Icon.PLAY);
        long d = s.duration();
        mBar.setValue(d > 0 ? (float) s.position() / d : 0);
    }

    // ===================== Keadaan =====================

    private boolean global(String key) {
        return Settings.Global.getInt(s.getContentResolver(), key, 0) != 0;
    }

    private boolean wifiConnected() {
        try {
            ConnectivityManager cm = s.getSystemService(ConnectivityManager.class);
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities c = cm.getNetworkCapabilities(n);
                if (c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private String carrier() {
        try {
            String n = s.getSystemService(TelephonyManager.class).getNetworkOperatorName();
            return TextUtils.isEmpty(n) ? null : n;
        } catch (Exception e) {
            return null;
        }
    }

    private void fetchSsid() {
        if (!Shell.granted()) return;
        Shell.run("cmd wifi status", (ok, out) -> {
            Matcher m = Pattern.compile("SSID: \"([^\"]+)\"").matcher(out);
            ssid = m.find() ? m.group(1) : null;
            refreshControl();
        });
    }

    private void refreshControl() {
        updateHeader();
        if (open != QS || tWifi == null || tData == null) return;
        boolean wifi = global("wifi_on");
        boolean conn = wifiConnected();
        tWifi.set(wifi, !wifi ? s.getString(R.string.st_off)
                : conn ? (ssid != null ? ssid : s.getString(R.string.st_connected)) : s.getString(R.string.st_not_connected));
        tBt.set(global("bluetooth_on"), null);
        boolean data = global("mobile_data");
        String car = carrier();
        tData.set(data, data && car != null ? car : null);
        NotificationManager nm = s.getSystemService(NotificationManager.class);
        tDnd.set(nm.getCurrentInterruptionFilter() > NotificationManager.INTERRUPTION_FILTER_ALL, null);
        tPlane.set(global(Settings.Global.AIRPLANE_MODE_ON), null);
        tTorch.set(torchOn, null);
        tDark.set(isDark(), null);
        tRotate.set(Settings.System.getInt(s.getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 1) == 0, null);
        tSaver.set(s.getSystemService(PowerManager.class).isPowerSaveMode(), null);
        tLoc.set(s.getSystemService(LocationManager.class).isLocationEnabled(), null);
        NfcAdapter nfc = NfcAdapter.getDefaultAdapter(s);
        tNfc.set(nfc != null && nfc.isEnabled(), null);
        int br = Settings.System.getInt(s.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, 128);
        if (sBright != null) sBright.setValue(br / 255f);
        if (vBright != null) vBright.setValue(br / 255f);
        if (vVolume != null) {
            android.media.AudioManager am = s.getSystemService(android.media.AudioManager.class);
            vVolume.setValue((float) am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                    / Math.max(1, am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)));
        }
        if (mediaCard != null) updateMedia();
    }

    private void later() {
        s.h.postDelayed(this::refreshAll, 700);
        s.h.postDelayed(this::refreshAll, 1800);
        s.h.postDelayed(this::fetchSsid, 3500);
    }

    private void refreshAll() {
        if (open == QS) refreshControl();
        else if (open == SHADE) refreshShadeTiles();
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
        tWifi.set(!on, null);
        shellOr("svc wifi " + (on ? "disable" : "enable"), new Intent(Settings.Panel.ACTION_WIFI));
    }

    private void toggleBt() {
        boolean on = global("bluetooth_on");
        tBt.set(!on, null);
        String a = on ? "disable" : "enable";
        shellOr("cmd bluetooth_manager " + a + " || svc bluetooth " + a, new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
    }

    private void toggleData() {
        boolean on = global("mobile_data");
        tData.set(!on, null);
        shellOr("svc data " + (on ? "disable" : "enable"), new Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY));
    }

    private void togglePlane() {
        boolean on = global(Settings.Global.AIRPLANE_MODE_ON);
        tPlane.set(!on, null);
        shellOr("cmd connectivity airplane-mode " + (on ? "disable" : "enable"),
                new Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS));
    }

    private void toggleDnd() {
        NotificationManager nm = s.getSystemService(NotificationManager.class);
        boolean on = nm.getCurrentInterruptionFilter() > NotificationManager.INTERRUPTION_FILTER_ALL;
        tDnd.set(!on, null);
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
        tDark.set(!on, null);
        shellOr("cmd uimode night " + (on ? "no" : "yes"), new Intent(Settings.ACTION_DISPLAY_SETTINGS));
    }

    private void toggleRotate() {
        boolean locked = Settings.System.getInt(s.getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 1) == 0;
        tRotate.set(!locked, null);
        if (Settings.System.canWrite(s)) {
            Settings.System.putInt(s.getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, locked ? 1 : 0);
            later();
        } else {
            shellOr("settings put system accelerometer_rotation " + (locked ? 1 : 0), null);
        }
    }

    private void toggleSaver() {
        boolean on = s.getSystemService(PowerManager.class).isPowerSaveMode();
        tSaver.set(!on, null);
        shellOr("cmd power set-mode " + (on ? 0 : 1), new Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS));
    }

    private void toggleLocation() {
        boolean on = s.getSystemService(LocationManager.class).isLocationEnabled();
        tLoc.set(!on, null);
        shellOr("cmd location set-location-enabled " + (!on), new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
    }

    private void toggleNfc() {
        NfcAdapter nfc = NfcAdapter.getDefaultAdapter(s);
        boolean on = nfc != null && nfc.isEnabled();
        tNfc.set(!on, null);
        shellOr("svc nfc " + (on ? "disable" : "enable"), new Intent(Settings.ACTION_NFC_SETTINGS));
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
                            if (tTorch != null && open != NONE) tTorch.set(enabled, null);
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

    // ===================== Panel pemberitahuan (kiri) =====================

    void openNotifications() {
        if (open == SHADE) return;
        bd = new LiquidGlass.Backdrop();
        LiquidGlass.current = bd;
        dark = Ui.darkGlass(s);
        tData = null;
        int m = dp(14), gap = dp(8);
        int w = Math.min(s.screenW, dp(460)) - 2 * m;

        ScrollView sv = new ScrollView(s);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout col = new LinearLayout(s);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(m, s.statusH + dp(10), m, dp(60));
        col.setClickable(true);
        col.setOnClickListener(v -> close());
        sv.addView(col, new ScrollView.LayoutParams(-1, -2));

        col.addView(header(), new LinearLayout.LayoutParams(w, -2));

        int tw = (w - 5 * gap) / 6, th = dp(52);
        tWifi = new Tile(Icon.WIFI, s.getString(R.string.tg_wifi), false, th, v -> toggleWifi(), act(Settings.Panel.ACTION_WIFI));
        tBt = new Tile(Icon.BT, s.getString(R.string.tg_bt), false, th, v -> toggleBt(), act(Settings.ACTION_BLUETOOTH_SETTINGS));
        tDnd = new Tile(Icon.MOON, s.getString(R.string.tg_dnd), false, th, v -> toggleDnd(), act("android.settings.ZEN_MODE_SETTINGS"));
        tTorch = new Tile(Icon.TORCH, s.getString(R.string.tg_torch), false, th, v -> toggleTorch(), null);
        tDark = new Tile(Icon.DARK, s.getString(R.string.tg_dark), false, th, v -> toggleDark(), act(Settings.ACTION_DISPLAY_SETTINGS));
        tRotate = new Tile(Icon.ROTATE, s.getString(R.string.tg_rotate), false, th, v -> toggleRotate(), act(Settings.ACTION_DISPLAY_SETTINGS));
        LinearLayout qr = new LinearLayout(s);
        Tile[] q = {tWifi, tBt, tDnd, tTorch, tDark, tRotate};
        for (int i = 0; i < q.length; i++) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(tw, th);
            if (i > 0) p.leftMargin = gap;
            qr.addView(q[i].v, p);
        }
        LinearLayout.LayoutParams ql = new LinearLayout.LayoutParams(w, -2);
        ql.topMargin = dp(16);
        col.addView(qr, ql);

        View media = mediaCard();
        if (media != null) {
            LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(w, dp(160));
            ml.topMargin = dp(14);
            col.addView(media, ml);
        }

        ncList = new LinearLayout(s);
        ncList.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(w, -2);
        nl.topMargin = dp(14);
        col.addView(ncList, nl);

        LinearLayout foot = new LinearLayout(s);
        foot.addView(chip(s.getString(R.string.manage), v -> launch(new Intent("android.settings.NOTIFICATION_SETTINGS"))),
                new LinearLayout.LayoutParams(dp(110), dp(40)));
        foot.addView(new View(s), new LinearLayout.LayoutParams(0, 1, 1f));
        foot.addView(chip(s.getString(R.string.clear_all), v -> {
            NotifListener l = NotifListener.inst;
            if (l != null) {
                try { l.cancelAllNotifications(); } catch (Exception ignored) {}
            }
            s.h.postDelayed(this::fillNotifications, 350);
        }), new LinearLayout.LayoutParams(dp(130), dp(40)));
        LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(w, -2);
        fl.topMargin = dp(12);
        col.addView(foot, fl);
        col.addView(handle());

        open = SHADE;
        fillNotifications();
        LiquidGlass.current = null;
        show(SHADE, sv, sv);
        refreshShadeTiles();
        startTorchWatch();
    }

    private void refreshShadeTiles() {
        updateHeader();
        if (open != SHADE || tWifi == null) return;
        tWifi.set(global("wifi_on"), null);
        tBt.set(global("bluetooth_on"), null);
        NotificationManager nm = s.getSystemService(NotificationManager.class);
        tDnd.set(nm.getCurrentInterruptionFilter() > NotificationManager.INTERRUPTION_FILTER_ALL, null);
        tTorch.set(torchOn, null);
        tDark.set(isDark(), null);
        tRotate.set(Settings.System.getInt(s.getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 1) == 0, null);
    }

    void onNotificationsChanged() {
        if (open == SHADE) s.h.post(this::fillNotifications);
    }

    private void fillNotifications() {
        if (ncList == null || open != SHADE) return;
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
        list.removeIf(x -> x.isOngoing()
                || (x.getNotification().flags & Notification.FLAG_GROUP_SUMMARY) != 0
                || x.getPackageName().equals(s.getPackageName()));
        list.sort((a, b) -> Long.compare(b.getPostTime(), a.getPostTime()));

        if (list.isEmpty()) {
            TextView e = Ui.text(s, 15, 500, Color.argb(190, 255, 255, 255));
            e.setText(R.string.no_notifications);
            e.setGravity(Gravity.CENTER);
            e.setPadding(0, dp(26), 0, dp(26));
            e.setBackground(Ui.glassStyled(s, 28, dark));
            ncList.addView(e, new LinearLayout.LayoutParams(-1, -2));
            addPromos();
            return;
        }
        // kumpul ikut app (kekal susunan terbaru dahulu)
        java.util.LinkedHashMap<String, List<StatusBarNotification>> groups = new java.util.LinkedHashMap<>();
        for (StatusBarNotification x : list) {
            List<StatusBarNotification> g = groups.get(x.getPackageName());
            if (g == null) groups.put(x.getPackageName(), g = new ArrayList<>());
            if (g.size() < 30) g.add(x);
        }
        for (java.util.Map.Entry<String, List<StatusBarNotification>> en : groups.entrySet()) {
            final String pkg = en.getKey();
            final List<StatusBarNotification> g = en.getValue();
            if (g.size() == 1) {
                View card = card(g.get(0), true, true);
                if (card == null) continue;
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
                p.bottomMargin = dp(10);
                ncList.addView(card, p);
            } else if (openGroups.contains(pkg)) {
                // kumpulan dibuka: tajuk + semua kad
                LinearLayout hdr = new LinearLayout(s);
                hdr.setGravity(Gravity.CENTER_VERTICAL);
                hdr.setPadding(dp(6), dp(4), dp(2), dp(8));
                TextView ht = Ui.line(s, 15, 650, Color.WHITE);
                ht.setText(appName(pkg));
                hdr.addView(ht, new LinearLayout.LayoutParams(0, -2, 1f));
                View less = chip(s.getString(R.string.show_less), v -> {
                    openGroups.remove(pkg);
                    fillNotifications();
                });
                less.setPadding(dp(16), 0, dp(16), 0);
                hdr.addView(less, new LinearLayout.LayoutParams(-2, dp(34)));
                ncList.addView(hdr, new LinearLayout.LayoutParams(-1, -2));
                for (StatusBarNotification x : g) {
                    View card = card(x, true, true);
                    if (card == null) continue;
                    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
                    p.bottomMargin = dp(8);
                    ncList.addView(card, p);
                }
            } else {
                View stack = stack(pkg, g);
                if (stack == null) continue;
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
                p.bottomMargin = dp(18);
                ncList.addView(stack, p);
            }
        }
        addPromos();
    }

    private final java.util.Set<String> openGroups = new java.util.HashSet<>();
    private long lastEdgeTap;

    /** Tindanan "Promosi" di bawah notifikasi biasa. */
    private void addPromos() {
        List<StatusBarNotification> pr = Promo.list();
        if (pr.isEmpty() || ncList == null) return;
        boolean opened = openGroups.contains("__promo");
        LinearLayout hdr = new LinearLayout(s);
        hdr.setGravity(Gravity.CENTER_VERTICAL);
        hdr.setPadding(dp(6), dp(12), dp(2), dp(8));
        TextView ht = Ui.line(s, 15, 650, Color.WHITE);
        ht.setText(s.getString(R.string.promo_title, pr.size()));
        hdr.addView(ht, new LinearLayout.LayoutParams(0, -2, 1f));
        View tog = chip(s.getString(opened ? R.string.show_less : R.string.show_all), v -> {
            if (opened) openGroups.remove("__promo"); else openGroups.add("__promo");
            fillNotifications();
        });
        tog.setPadding(dp(16), 0, dp(16), 0);
        hdr.addView(tog, new LinearLayout.LayoutParams(-2, dp(34)));
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(-2, dp(34));
        hl.leftMargin = dp(8);
        View clr = chip(s.getString(R.string.clear_all), v -> {
            Promo.clear();
            fillNotifications();
        });
        clr.setPadding(dp(16), 0, dp(16), 0);
        hdr.addView(clr, hl);
        ncList.addView(hdr, new LinearLayout.LayoutParams(-1, -2));
        if (!opened) {
            TextView sum = Ui.text(s, 13, 500, Color.argb(190, 255, 255, 255));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(3, pr.size()); i++) {
                if (i > 0) sb.append(" · ");
                sb.append(appName(pr.get(i).getPackageName()));
            }
            sum.setText(sb);
            sum.setPadding(dp(16), dp(12), dp(16), dp(12));
            sum.setBackground(GlassEngine.drawable(s, 22, true, 25));
            sum.setOnClickListener(v -> {
                openGroups.add("__promo");
                fillNotifications();
            });
            ncList.addView(sum, new LinearLayout.LayoutParams(-1, -2));
            return;
        }
        for (StatusBarNotification x : pr) {
            View card = card(x, true, true);
            if (card == null) continue;
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
            p.bottomMargin = dp(8);
            ncList.addView(card, p);
        }
    }
    private final java.util.Set<String> openCards = new java.util.HashSet<>();
    private String replyKey;

    /** Tindanan kad kaca untuk satu app: kad terbaru di depan, dua lapisan di belakang. */
    private View stack(final String pkg, final List<StatusBarNotification> g) {
        View front = card(g.get(0), true, true);
        if (front == null) return null;
        FrameLayout f = new FrameLayout(s);
        f.setClipChildren(false);
        for (int i = 2; i >= 1; i--) {
            if (g.size() <= i) continue;
            View ghost = new View(s);
            ghost.setBackground(GlassEngine.drawable(s, 24, true, 18 + i * 4));
            ghost.setScaleX(1f - 0.045f * i);
            FrameLayout.LayoutParams gl = new FrameLayout.LayoutParams(-1, dp(40), Gravity.BOTTOM);
            gl.bottomMargin = -dp(7) * i;
            ghost.setAlpha(1f - 0.25f * i);
            f.addView(ghost, gl);
        }
        f.addView(front, new FrameLayout.LayoutParams(-1, -2));
        // label "+N lagi" pada kad hadapan
        TextView more = Ui.line(s, 11, 700, Color.WHITE);
        more.setText(s.getString(R.string.n_more, g.size() - 1));
        more.setPadding(dp(9), dp(3), dp(9), dp(3));
        more.setBackground(Ui.round(Color.argb(70, 255, 255, 255), dp(10)));
        FrameLayout.LayoutParams ml = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        ml.bottomMargin = dp(10);
        ml.rightMargin = dp(14);
        f.addView(more, ml);
        // tekan: buka kumpulan; leret: padam semua notifikasi app ini
        front.setOnClickListener(v -> {
            openGroups.add(pkg);
            fillNotifications();
        });
        swipeGroup(front, f, g);
        return f;
    }

    private void swipeGroup(View handle, final View whole, final List<StatusBarNotification> g) {
        final float[] st = new float[2];
        final boolean[] drag = new boolean[1];
        handle.setOnTouchListener((view, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    st[0] = e.getRawX();
                    st[1] = e.getRawY();
                    drag[0] = false;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getRawX() - st[0], dy = e.getRawY() - st[1];
                    if (!drag[0] && Math.abs(dx) > dp(12) && Math.abs(dx) > Math.abs(dy)) {
                        drag[0] = true;
                        ViewGroup p = (ViewGroup) whole.getParent();
                        if (p != null) p.requestDisallowInterceptTouchEvent(true);
                    }
                    if (drag[0]) {
                        whole.setTranslationX(dx);
                        whole.setAlpha(1f - Math.min(1f, Math.abs(dx) / whole.getWidth()));
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP: {
                    float dx = e.getRawX() - st[0], dy = e.getRawY() - st[1];
                    if (drag[0]) {
                        if (Math.abs(dx) > whole.getWidth() * 0.33f) {
                            whole.animate().translationX(Math.signum(dx) * whole.getWidth()).alpha(0f).setDuration(200)
                                    .withEndAction(() -> {
                                        NotifListener l = NotifListener.inst;
                                        if (l != null) {
                                            for (StatusBarNotification x : g) {
                                                try { l.cancelNotification(x.getKey()); } catch (Exception ignored) {}
                                            }
                                        }
                                        ViewGroup p = (ViewGroup) whole.getParent();
                                        if (p != null) p.removeView(whole);
                                    }).start();
                        } else {
                            whole.animate().translationX(0).alpha(1f).setInterpolator(Ui.SPRING).setDuration(Ui.SPRING_MS).start();
                        }
                    } else if (Math.abs(dx) < dp(10) && Math.abs(dy) < dp(10)) {
                        view.performClick();
                    }
                    return true;
                }
                case MotionEvent.ACTION_CANCEL:
                    whole.animate().translationX(0).alpha(1f).setDuration(200).start();
                    return true;
            }
            return false;
        });
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

    /** Kad bertindan gaya Pixel: sudut besar di atas/bawah kumpulan, sudut kecil di tengah. */
    private Drawable stackBg(boolean first, boolean last) {
        float big = dp(28), small = dp(6);
        float t = first ? big : small, b = last ? big : small;
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadii(new float[]{t, t, t, t, b, b, b, b});
        if (dark) {
            g.setColor(Color.argb(125, 18, 18, 22));
            g.setStroke(Math.max(1, dp(0.8f)), Color.argb(40, 255, 255, 255));
        } else {
            g.setColor(Color.argb(48, 255, 255, 255));
            g.setStroke(Math.max(1, dp(0.8f)), Color.argb(70, 255, 255, 255));
        }
        return g;
    }

    private View card(final StatusBarNotification sbn, boolean first, boolean last) {
        final Notification n = sbn.getNotification();
        CharSequence title = n.extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence text = n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
        if (TextUtils.isEmpty(text)) text = n.extras.getCharSequence(Notification.EXTRA_TEXT);
        if (TextUtils.isEmpty(title) && TextUtils.isEmpty(text)) return null;

        LinearLayout c = new LinearLayout(s);
        c.setGravity(Gravity.TOP);
        LiquidGlass.Backdrop prevBd = LiquidGlass.current;
        LiquidGlass.current = bd;
        LiquidGlass.apply(c, 24, dark);
        LiquidGlass.current = prevBd;
        c.setPadding(dp(14), dp(14), dp(16), dp(14));

        ImageView ic = new ImageView(s);
        ic.setImageDrawable(s.appIcon(sbn.getPackageName()));
        c.addView(ic, new LinearLayout.LayoutParams(dp(36), dp(36)));

        LinearLayout tc = new LinearLayout(s);
        tc.setOrientation(LinearLayout.VERTICAL);
        tc.setPadding(dp(12), 0, 0, 0);
        TextView app = Ui.line(s, 12, 500, Color.argb(200, 255, 255, 255));
        long age = System.currentTimeMillis() - sbn.getPostTime();
        CharSequence when = age < 60_000 ? s.getString(R.string.now)
                : DateUtils.getRelativeTimeSpanString(sbn.getPostTime(), System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE);
        app.setText(appName(sbn.getPackageName()) + " • " + when);
        tc.addView(app);
        if (!TextUtils.isEmpty(title)) {
            TextView t = Ui.line(s, 15, 600, Color.WHITE);
            t.setText(title);
            t.setPadding(0, dp(4), 0, 0);
            tc.addView(t);
        }
        final boolean isOpen = openCards.contains(sbn.getKey());
        if (isOpen) {
            CharSequence full = NotifUtil.fullText(n);
            if (!TextUtils.isEmpty(full)) text = full;
        }
        if (!TextUtils.isEmpty(text)) {
            TextView t = Ui.text(s, 14, 400, Color.argb(220, 255, 255, 255));
            t.setText(text);
            t.setMaxLines(isOpen ? 30 : 3);
            t.setEllipsize(TextUtils.TruncateAt.END);
            t.setLineSpacing(0, 1.12f);
            t.setPadding(0, dp(3), 0, 0);
            tc.addView(t);
        }
        c.addView(tc, new LinearLayout.LayoutParams(0, -2, 1f));

        if (n.getLargeIcon() != null) {
            try {
                ImageView li = new ImageView(s);
                li.setImageDrawable(n.getLargeIcon().loadDrawable(s));
                li.setScaleType(ImageView.ScaleType.CENTER_CROP);
                li.setOutlineProvider(new ViewOutlineProvider() {
                    @Override
                    public void getOutline(View view, Outline o) {
                        o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(20));
                    }
                });
                li.setClipToOutline(true);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(40), dp(40));
                lp.leftMargin = dp(10);
                c.addView(li, lp);
            } catch (Exception ignored) {
            }
        }

        // kad dibuka: gambar, balas, butang tindakan
        View result = c;
        if (isOpen) {
            LinearLayout wrap = new LinearLayout(s);
            wrap.setOrientation(LinearLayout.VERTICAL);
            wrap.setBackground(c.getBackground());
            wrap.setOutlineProvider(c.getOutlineProvider());
            wrap.setElevation(c.getElevation());
            c.setBackground(null);
            c.setElevation(0);
            c.setPadding(dp(14), dp(14), dp(16), dp(6));
            wrap.addView(c, new LinearLayout.LayoutParams(-1, -2));
            LinearLayout ex = new LinearLayout(s);
            ex.setOrientation(LinearLayout.VERTICAL);
            ex.setPadding(dp(62), 0, dp(16), dp(14));
            wrap.addView(ex, new LinearLayout.LayoutParams(-1, -2));
            addPictures(ex, n);
            addActions(ex, sbn, n);
            wrap.setOnClickListener(v -> {
                openCards.remove(sbn.getKey());
                if (sbn.getKey().equals(replyKey)) replyKey = null;
                fillNotifications();
            });
            c.setOnClickListener(v -> wrap.performClick());
            swipeToDismiss(wrap, sbn);
            result = wrap;
        } else {
            // tekan: buka kad (teks penuh, gambar, balas); tekan lama: terus buka app
            c.setOnClickListener(v -> {
                openCards.add(sbn.getKey());
                fillNotifications();
            });
            c.setOnLongClickListener(v -> {
                openApp(sbn, n);
                return true;
            });
            swipeToDismiss(c, sbn);
        }
        return result;
    }

    private void openApp(StatusBarNotification sbn, Notification n) {
        s.sendIntent(n.contentIntent);
        if ((n.flags & Notification.FLAG_AUTO_CANCEL) != 0 && NotifListener.inst != null) {
            try { NotifListener.inst.cancelNotification(sbn.getKey()); } catch (Exception ignored) {}
        }
        close();
    }

    private void addPictures(LinearLayout ex, Notification n) {
        List<NotifUtil.Pic> pics = NotifUtil.pictures(s, n, dp(260));
        if (pics.isEmpty()) return;
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(s);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(s);
        for (NotifUtil.Pic p : pics) {
            ImageView iv = new ImageView(s);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageBitmap(p.bmp);
            iv.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline o) { o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(16)); }
            });
            iv.setClipToOutline(true);
            int h = dp(180);
            int w = pics.size() == 1 ? Math.min(dp(260), Math.round(h * (float) p.bmp.getWidth() / Math.max(1, p.bmp.getHeight())))
                    : dp(150);
            iv.setOnClickListener(v -> showPicture(p));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Math.max(dp(90), w), h);
            lp.rightMargin = dp(8);
            row.addView(iv, lp);
        }
        hs.addView(row);
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(-1, -2);
        hl.topMargin = dp(8);
        ex.addView(hs, hl);
    }

    /** Lihat gambar penuh di atas panel; tekan untuk tutup. */
    private void showPicture(NotifUtil.Pic p) {
        if (root == null) return;
        FrameLayout f = new FrameLayout(s);
        f.setBackgroundColor(Color.argb(235, 0, 0, 0));
        ImageView iv = new ImageView(s);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        Bitmap big = p.uri != null ? null : p.bmp;
        if (p.uri != null) {
            try {
                android.graphics.ImageDecoder.Source src = android.graphics.ImageDecoder.createSource(s.getContentResolver(), p.uri);
                big = android.graphics.ImageDecoder.decodeBitmap(src, (d, info, so) -> {
                    int m = Math.max(info.getSize().getWidth(), info.getSize().getHeight());
                    if (m > 2400) {
                        float sc = 2400f / m;
                        d.setTargetSize(Math.round(info.getSize().getWidth() * sc), Math.round(info.getSize().getHeight() * sc));
                    }
                });
            } catch (Exception e) {
                big = p.bmp;
            }
        }
        iv.setImageBitmap(big);
        f.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        f.setOnClickListener(v -> v.animate().alpha(0f).setDuration(180).withEndAction(() -> {
            if (root != null) root.removeView(f);
        }).start());
        f.setAlpha(0f);
        root.addView(f, new FrameLayout.LayoutParams(-1, -1));
        iv.setScaleX(0.85f);
        iv.setScaleY(0.85f);
        f.animate().alpha(1f).setDuration(200).start();
        iv.animate().scaleX(1f).scaleY(1f).setInterpolator(Ui.SPRING).setDuration(Ui.SPRING_MS).start();
    }

    private void addActions(LinearLayout ex, StatusBarNotification sbn, Notification n) {
        final Notification.Action reply = NotifUtil.replyAction(n);
        final String key = sbn.getKey();
        if (reply != null && key.equals(replyKey)) {
            LinearLayout rr = new LinearLayout(s);
            rr.setGravity(Gravity.CENTER_VERTICAL);
            final android.widget.EditText et = new android.widget.EditText(s);
            et.setHint(R.string.reply_hint);
            et.setTextColor(Color.WHITE);
            et.setHintTextColor(Color.argb(150, 255, 255, 255));
            et.setTextSize(15);
            et.setBackground(Ui.round(Color.argb(40, 255, 255, 255), dp(20)));
            et.setPadding(dp(14), dp(10), dp(14), dp(10));
            et.setMaxLines(4);
            rr.addView(et, new LinearLayout.LayoutParams(0, -2, 1f));
            TextView send = Ui.line(s, 14, 650, 0xFF0B0B10);
            send.setText(R.string.notif_send);
            send.setGravity(Gravity.CENTER);
            send.setPadding(dp(16), 0, dp(16), 0);
            send.setBackground(Ui.round(Color.WHITE, dp(20)));
            send.setOnClickListener(v -> {
                if (NotifUtil.reply(s, reply, et.getText().toString())) {
                    Toast.makeText(s, R.string.notif_sent, Toast.LENGTH_SHORT).show();
                    replyKey = null;
                    hideKeyboard(et);
                    s.h.postDelayed(this::fillNotifications, 500);
                }
            });
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, dp(40));
            sp.leftMargin = dp(8);
            rr.addView(send, sp);
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
            rl.topMargin = dp(10);
            ex.addView(rr, rl);
            et.requestFocus();
            s.h.postDelayed(() -> {
                android.view.inputmethod.InputMethodManager imm = s.getSystemService(android.view.inputmethod.InputMethodManager.class);
                if (imm != null) imm.showSoftInput(et, 0);
            }, 150);
            return;
        }
        LinearLayout acts = new LinearLayout(s);
        acts.addView(actChip(s.getString(R.string.notif_open), true, v -> openApp(sbn, n)));
        if (reply != null) {
            acts.addView(actChip(s.getString(R.string.notif_reply), false, v -> {
                replyKey = key;
                fillNotifications();
            }));
        }
        if (n.actions != null) {
            int extra = 0;
            for (final Notification.Action a : n.actions) {
                if (a == reply || a.actionIntent == null || TextUtils.isEmpty(a.title) || extra >= 2) continue;
                if (a.getRemoteInputs() != null && a.getRemoteInputs().length > 0) continue;
                acts.addView(actChip(a.title.toString(), false, v -> {
                    try { NotifUtil.send(s, a.actionIntent, null); } catch (Exception ignored) {}
                    s.h.postDelayed(this::fillNotifications, 400);
                }));
                extra++;
            }
        }
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(s);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(acts);
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(-1, -2);
        hl.topMargin = dp(10);
        ex.addView(hs, hl);
    }

    private TextView actChip(String label, boolean primary, View.OnClickListener l) {
        TextView t = Ui.line(s, 13, 650, primary ? 0xFF0B0B10 : Color.WHITE);
        t.setText(label);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(16), 0, dp(16), 0);
        t.setBackground(primary ? Ui.round(Color.WHITE, dp(18)) : Ui.round(Color.argb(46, 255, 255, 255), dp(18)));
        t.setOnClickListener(l);
        Ui.press(t);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, dp(36));
        p.rightMargin = dp(8);
        t.setLayoutParams(p);
        return t;
    }

    private void hideKeyboard(View v) {
        android.view.inputmethod.InputMethodManager imm = s.getSystemService(android.view.inputmethod.InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
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
                    if (drag[0]) {
                        if (Math.abs(dx) > view.getWidth() * 0.33f) {
                            view.animate().translationX(Math.signum(dx) * view.getWidth()).alpha(0f).setDuration(180)
                                    .withEndAction(() -> {
                                        Promo.remove(sbn.getKey());
                                        if (NotifListener.inst != null) {
                                            try { NotifListener.inst.cancelNotification(sbn.getKey()); } catch (Exception ignored) {}
                                        }
                                        ViewGroup p = (ViewGroup) view.getParent();
                                        if (p != null) p.removeView(view);
                                    }).start();
                        } else {
                            view.animate().translationX(0).alpha(1f).setInterpolator(Ui.SPRING).setDuration(Ui.SPRING_MS).start();
                        }
                    } else if (Math.abs(dx) < dp(10) && Math.abs(dy) < dp(10)) {
                        view.performClick();
                    }
                    return true;
                }
                case MotionEvent.ACTION_CANCEL:
                    view.animate().translationX(0).alpha(1f).setDuration(200).start();
                    return true;
            }
            return false;
        });
    }
}
