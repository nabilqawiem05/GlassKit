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
                            Bitmap blurred = null;
                            try {
                                Bitmap hw = Bitmap.wrapHardwareBuffer(res.getHardwareBuffer(), res.getColorSpace());
                                if (hw != null) {
                                    Bitmap sw = hw.copy(Bitmap.Config.ARGB_8888, false);
                                    blurred = GlassArt.blur(sw, 200, 9);
                                    bd.set(sw, s.screenW, s.screenH);
                                }
                            } catch (Throwable ignored) {
                            } finally {
                                try { res.getHardwareBuffer().close(); } catch (Throwable ignored) {}
                            }
                            attach(token, r, bgImg, blurred);
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
        r.setAlpha(0f);
        r.animate().alpha(1f).setDuration(180).start();
        if (liquid != null && open == QS) {
            final LiquidSheet ls = liquid;
            ls.post(ls::enter);
            return;
        }
        content.setTranslationY(-dp(48));
        content.animate().translationY(0).setInterpolator(Ui.SPRING).setDuration(560).start();
    }

    void close() {
        if (root == null || closing) return;
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
        int onColor;
        FrameLayout disc;

        /** Tile bulat berwarna (gaya Island Cecair): bulatan + label kecil di bawah. */
        Tile(int kind, String text, int onColor, View.OnClickListener tap, Intent detail) {
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

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) getParent().requestDisallowInterceptTouchEvent(true);
            setValue(1f - e.getY() / getHeight());
            boolean done = e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL;
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
            // badan kaca hitam
            p.setShader(new android.graphics.LinearGradient(0, 0, 0, hh, Color.argb(246, 6, 6, 8), Color.argb(236, 14, 14, 18),
                    android.graphics.Shader.TileMode.CLAMP));
            c.drawPath(path, p);
            // kilauan yang bergerak ikut condongan (L)
            float gx = w * (0.3f + 0.25f * tx), gy = hh * (0.15f + 0.2f * ty);
            p.setShader(new android.graphics.RadialGradient(gx, gy, Math.max(w, hh) * 0.75f,
                    Color.argb(38, 255, 255, 255), Color.argb(0, 255, 255, 255), android.graphics.Shader.TileMode.CLAMP));
            c.drawPath(path, p);
            // bingkai kaca
            p.setShader(new android.graphics.LinearGradient(0, 0, w, hh,
                    new int[]{Color.argb(120, 255, 255, 255), Color.argb(20, 255, 255, 255), Color.argb(70, 255, 255, 255)},
                    null, android.graphics.Shader.TileMode.CLAMP));
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
            animate().scaleX(1f).setInterpolator(Ui.SPRING).setDuration(520).start();
            android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(0f, 1f);
            a.setDuration(620);
            a.setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f));
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
                        .setInterpolator(new android.view.animation.OvershootInterpolator(2.2f))
                        .setStartDelay(180 + i * 28L).setDuration(460).start();
            }
        }

        /** Tepi bawah berayun sekejap sebelum tenang, macam cecair mendap. */
        private void settle() {
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
                        .setInterpolator(new android.view.animation.AccelerateInterpolator())
                        .setStartDelay(i * 12L).setDuration(200).start();
            }
            body.animate().alpha(0f).setStartDelay(120).setDuration(180).start();
            android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(1f, 0f);
            a.setStartDelay(120);
            a.setDuration(340);
            a.setInterpolator(new android.view.animation.AccelerateInterpolator(1.4f));
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
        Tile[] all = {tWifi, tBt, tData, tDnd, tPlane, tTorch, tDark, tRotate, tSaver, tLoc, tNfc, shot, rec, awk[0], cam, lock, set};

        LinearLayout mid = new LinearLayout(s);
        mid.setClipChildren(false);
        LinearLayout grid = new LinearLayout(s);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        int cols = 3;
        for (int i = 0; i < 9; i += cols) {
            LinearLayout row = new LinearLayout(s);
            row.setClipChildren(false);
            for (int j = 0; j < cols; j++) {
                row.addView(all[i + j].v, new LinearLayout.LayoutParams(0, -2, 1f));
                sheet.drops.add(all[i + j].v);
            }
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
            rl.topMargin = dp(14);
            grid.addView(row, rl);
        }
        mid.addView(grid, new LinearLayout.LayoutParams(0, -2, 1f));
        vBright = new VSlider(Icon.SUN, this::setBrightness);
        vVolume = new VSlider(Icon.SPEAKER, (v, done) -> {
            android.media.AudioManager am = s.getSystemService(android.media.AudioManager.class);
            int max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
            am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, Math.round(v * max), 0);
        });
        int sliderH = 3 * (dp(58) + dp(22) + dp(14)) - dp(14);
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
        LinearLayout row2 = new LinearLayout(s);
        row2.setGravity(Gravity.CENTER_VERTICAL);
        for (int i = 9; i < all.length; i++) {
            Tile t = all[i];
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

        LinearLayout foot = new LinearLayout(s);
        foot.addView(chip(s.getString(R.string.system_panel), v -> {
            close();
            s.h.postDelayed(() -> s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS), 450);
        }), new LinearLayout.LayoutParams(0, dp(42), 1f));
        LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(0, dp(42), 1f);
        fl.leftMargin = gap;
        foot.addView(chip(s.getString(R.string.cc_settings), v -> launch(new Intent(s, MainActivity.class))), fl);
        LinearLayout.LayoutParams fll = new LinearLayout.LayoutParams(-1, -2);
        fll.topMargin = dp(16);
        b.addView(foot, fll);
        sheet.drops.add(foot);

        LiquidGlass.current = null;
        show(QS, sv, sv);
        refreshControl();
        startTorchWatch();
        fetchSsid();
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
                            whole.animate().translationX(0).alpha(1f).setInterpolator(Ui.SPRING).setDuration(420).start();
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
        iv.animate().scaleX(1f).scaleY(1f).setInterpolator(Ui.SPRING).setDuration(450).start();
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
                            view.animate().translationX(0).alpha(1f).setInterpolator(Ui.SPRING).setDuration(420).start();
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
