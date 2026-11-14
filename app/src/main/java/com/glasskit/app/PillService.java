package com.glasskit.app;

import android.accessibilityservice.AccessibilityService;
import android.animation.ValueAnimator;
import android.app.ActivityOptions;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.os.PowerManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.view.accessibility.AccessibilityEvent;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Dynamic Island + hos untuk panel kaca. */
public class PillService extends AccessibilityService
        implements SharedPreferences.OnSharedPreferenceChangeListener {

    static PillService inst;

    private static final int IDLE = 0, MUSIC = 1, NOTI = 2;

    final Handler h = new Handler(Looper.getMainLooper());
    WindowManager wm;
    SharedPreferences prefs;
    Panels panels;

    private WindowManager.LayoutParams lp;
    private FrameLayout root, pill;
    private GradientDrawable pillBg;
    private FrameLayout compact;
    private LinearLayout expMusic, expNoti;
    private ImageView cArt, cAppIcon, mArt, nIcon;
    private TextView cText, mTitle, mArtist, mElapsed, mRemain, nTitle, nText;
    private Widgets.Wave cWave, mWave;
    private Widgets.Bar mBar;
    private com.glasskit.app.Icon mPlay;

    private boolean attached, expanded;
    private int mode = IDLE;
    private int curW, curH, compactW, compactH, topY, xOff;
    int screenW, screenH, statusH;
    private ValueAnimator anim;

    // ---- media (dikongsi dengan Pusat Kawalan) ----
    private MediaSessionManager msm;
    MediaController mc;
    private final MediaCb mcb = new MediaCb();
    boolean playing;
    String title, artist;
    Bitmap art;
    int artColor = Color.WHITE;
    private long holdMusicUntil, duration;

    private LockPatch patch;
    private boolean stepReg, calObs;
    private final android.hardware.SensorEventListener stepL = new android.hardware.SensorEventListener() {
        @Override
        public void onSensorChanged(android.hardware.SensorEvent e) {
            Data.onStepCounter(PillService.this, e.values[0]);
        }

        @Override
        public void onAccuracyChanged(android.hardware.Sensor s, int a) { }
    };
    private final android.database.ContentObserver calL = new android.database.ContentObserver(new Handler(Looper.getMainLooper())) {
        @Override
        public void onChange(boolean self) {
            h.removeCallbacks(calUpdate);
            h.postDelayed(calUpdate, 2000);
        }
    };
    private final Runnable calUpdate = () -> {
        GlassWidget.updateAll(this, ClockCalWidget.class);
        GlassWidget.updateAll(this, UpNextWidget.class);
        if (LockActivity.inst != null) LockActivity.inst.render();
    };
    private final Runnable dataTick = new Runnable() {
        @Override
        public void run() {
            onDataPermissions();
            h.postDelayed(this, 15 * 60_000);
        }
    };
    private long lastWx;
    private int lastBat = -2, lastPlug = -2;
    private StatusBar sbar;
    private boolean sbarShown;
    private final Runnable sbarTick = new Runnable() {
        @Override
        public void run() {
            if (sbar != null && sbarShown) sbar.refresh();
            h.postDelayed(this, 30_000);
        }
    };
    private final android.net.ConnectivityManager.NetworkCallback netCb =
            new android.net.ConnectivityManager.NetworkCallback() {
                @Override
                public void onCapabilitiesChanged(android.net.Network n, android.net.NetworkCapabilities nc) {
                    h.post(() -> { if (sbar != null && sbarShown) sbar.refresh(); });
                }

                @Override
                public void onLost(android.net.Network n) {
                    h.post(() -> { if (sbar != null && sbarShown) sbar.refresh(); });
                }
            };
    private boolean registered;
    private final BroadcastReceiver sysRx = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (a == null) return;
            switch (a) {
                case Intent.ACTION_SCREEN_ON:
                    if (patch != null) patch.screenOn();
                    break;
                case Intent.ACTION_SCREEN_OFF:
                    if (patch != null) patch.hide(false);
                    if (panels != null) panels.close();
                    if (prefs.getBoolean(Prefs.LOCK_ON, false)) h.postDelayed(PillService.this::showLock, 250);
                    break;
                case Intent.ACTION_USER_PRESENT:
                    if (patch != null) patch.hide(true);
                    if (!prefs.getBoolean(Prefs.LOCK_ON, false)) Sounds.play(PillService.this, Sounds.UNLOCK);
                    break;
                case Intent.ACTION_TIME_TICK:
                    if (patch != null) patch.tick();
                    if (sbar != null && sbarShown) sbar.refresh();
                    GlassWidget.tick(PillService.this);
                    break;
                case Intent.ACTION_BATTERY_CHANGED: {
                    int lv = i.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1);
                    int pl = i.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0);
                    if (lv != lastBat || pl != lastPlug) {
                        lastBat = lv;
                        lastPlug = pl;
                        GlassWidget.updateAll(PillService.this, BatteryWidget.class);
                    }
                    if (sbar != null && sbarShown) sbar.refresh();
                    break;
                }
                case Intent.ACTION_AIRPLANE_MODE_CHANGED:
                case android.app.NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED:
                    if (sbar != null && sbarShown) sbar.refresh();
                    break;
                case Intent.ACTION_POWER_CONNECTED:
                    Sounds.play(PillService.this, Sounds.CHARGE);
                    h.postDelayed(PillService.this::showCharging, 600);
                    break;
            }
        }
    };

    private PendingIntent notiIntent;
    private boolean notiActive;
    private final Set<String> seenOnce = new HashSet<>();

    private final Runnable endNoti = () -> {
        notiActive = false;
        expanded = false;
        refresh();
    };
    private final Runnable autoCollapse = () -> {
        if (expanded && mode == MUSIC) {
            expanded = false;
            refresh();
        }
    };
    private final Runnable progTick = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            if (expanded && mode == MUSIC) h.postDelayed(this, 500);
        }
    };
    private final Runnable refreshR = this::refresh;

    // ================= Kitaran hidup =================

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        inst = this;
        prefs = Prefs.get(this);
        prefs.registerOnSharedPreferenceChangeListener(this);
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        computeGeometry();
        buildViews();
        panels = new Panels(this);
        panels.updateEdges();
        patch = new LockPatch();
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_USER_PRESENT);
        f.addAction(Intent.ACTION_TIME_TICK);
        f.addAction(Intent.ACTION_POWER_CONNECTED);
        f.addAction(Intent.ACTION_BATTERY_CHANGED);
        f.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        f.addAction(android.app.NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED);
        registerReceiver(sysRx, f, Context.RECEIVER_EXPORTED);
        registered = true;
        try {
            getSystemService(android.net.ConnectivityManager.class).registerDefaultNetworkCallback(netCb, h);
        } catch (Exception ignored) {
        }
        h.postDelayed(sbarTick, 30_000);
        h.post(dataTick);
        updateStatusBar();
        initMedia();
        refresh();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) { }

    @Override
    public void onInterrupt() { }

    @Override
    public void onDestroy() {
        inst = null;
        h.removeCallbacksAndMessages(null);
        if (prefs != null) prefs.unregisterOnSharedPreferenceChangeListener(this);
        if (mc != null) mc.unregisterCallback(mcb);
        if (msm != null) {
            try { msm.removeOnActiveSessionsChangedListener(sessL); } catch (Exception ignored) {}
        }
        if (panels != null) panels.destroy();
        if (patch != null) patch.hide(false);
        if (registered) {
            try { unregisterReceiver(sysRx); } catch (Exception ignored) {}
            try { getSystemService(android.net.ConnectivityManager.class).unregisterNetworkCallback(netCb); } catch (Exception ignored) {}
            registered = false;
        }
        hideStatusBar();
        try { getSystemService(android.hardware.SensorManager.class).unregisterListener(stepL); } catch (Exception ignored) {}
        try { getContentResolver().unregisterContentObserver(calL); } catch (Exception ignored) {}
        detach();
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (wm == null || pill == null) return;
        computeGeometry();
        if (attached && lp != null) {
            lp.x = xOff;
            lp.y = topY;
            try { wm.updateViewLayout(root, lp); } catch (Exception ignored) {}
        }
        if (panels != null) panels.updateEdges();
        hideStatusBar();
        updateStatusBar();
        refresh();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
        if (panels != null) panels.updateEdges();
        updateStatusBar();
        refresh();
    }

    // ================= Geometri =================

    int dp(float v) { return Ui.dp(this, v); }

    private void computeGeometry() {
        WindowMetrics m = wm.getCurrentWindowMetrics();
        Rect b = m.getBounds();
        screenW = b.width();
        screenH = b.height();
        statusH = m.getWindowInsets().getInsets(android.view.WindowInsets.Type.statusBars()).top;
        if (statusH <= 0) statusH = dp(32);
        Rect r = null;
        DisplayCutout dc = m.getWindowInsets().getDisplayCutout();
        if (dc != null) r = dc.getBoundingRectTop();
        if (r != null && !r.isEmpty()) {
            compactH = Math.max(dp(30), Math.min(dp(36), r.height() + dp(4)));
            topY = Math.max(dp(4), r.centerY() - compactH / 2);
            xOff = r.centerX() - screenW / 2;
        } else {
            compactH = dp(34);
            topY = dp(10);
            xOff = 0;
        }
        compactW = dp(176);
    }

    private static void roundClip(View v, final float radius) {
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline o) {
                o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        v.setClipToOutline(true);
    }

    private FrameLayout iconBtn(int kind, int sizeDp, View.OnClickListener l) {
        FrameLayout f = new FrameLayout(this);
        com.glasskit.app.Icon ic = new com.glasskit.app.Icon(this, kind, Color.WHITE);
        f.addView(ic, new FrameLayout.LayoutParams(dp(sizeDp), dp(sizeDp), Gravity.CENTER));
        f.setOnClickListener(v -> {
            l.onClick(v);
            h.removeCallbacks(autoCollapse);
            h.postDelayed(autoCollapse, 7000);
        });
        Ui.press(f);
        return f;
    }

    // ================= Bina paparan island =================

    private void buildViews() {
        root = new FrameLayout(this);
        pill = new FrameLayout(this);
        pillBg = new GradientDrawable();
        pillBg.setColor(Color.BLACK);
        pillBg.setCornerRadius(compactH / 2f);
        pill.setBackground(pillBg);
        pill.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        pill.setClipToOutline(true);
        root.addView(pill, new FrameLayout.LayoutParams(compactW, compactH, Gravity.CENTER_HORIZONTAL));

        // ---- padat ----
        compact = new FrameLayout(this);
        int ic = compactH - dp(10);
        cArt = new ImageView(this);
        cArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        roundClip(cArt, dp(7));
        FrameLayout.LayoutParams al = new FrameLayout.LayoutParams(ic, ic, Gravity.START | Gravity.CENTER_VERTICAL);
        al.leftMargin = dp(6);
        compact.addView(cArt, al);
        cAppIcon = new ImageView(this);
        cAppIcon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        roundClip(cAppIcon, ic / 2f);
        FrameLayout.LayoutParams il = new FrameLayout.LayoutParams(ic, ic, Gravity.START | Gravity.CENTER_VERTICAL);
        il.leftMargin = dp(6);
        compact.addView(cAppIcon, il);
        cWave = new Widgets.Wave(this);
        FrameLayout.LayoutParams wl = new FrameLayout.LayoutParams(dp(22), dp(13), Gravity.END | Gravity.CENTER_VERTICAL);
        wl.rightMargin = dp(13);
        compact.addView(cWave, wl);
        cText = Ui.line(this, 12, 600, Color.WHITE);
        cText.setMaxWidth(dp(64));
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.CENTER_VERTICAL);
        tl.rightMargin = dp(13);
        compact.addView(cText, tl);
        pill.addView(compact, new FrameLayout.LayoutParams(-1, -1));

        // ---- muzik besar ----
        expMusic = new LinearLayout(this);
        expMusic.setOrientation(LinearLayout.VERTICAL);
        expMusic.setPadding(dp(20), dp(18), dp(20), dp(10));
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        mArt = new ImageView(this);
        mArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        mArt.setBackgroundColor(Color.rgb(44, 44, 46));
        roundClip(mArt, dp(14));
        row.addView(mArt, new LinearLayout.LayoutParams(dp(56), dp(56)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(14), 0, dp(8), 0);
        mTitle = Ui.line(this, 16, 600, Color.WHITE);
        mArtist = Ui.line(this, 14, 400, Color.argb(150, 235, 235, 245));
        mArtist.setPadding(0, dp(4), 0, 0);
        col.addView(mTitle);
        col.addView(mArtist);
        row.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        mWave = new Widgets.Wave(this);
        row.addView(mWave, new LinearLayout.LayoutParams(dp(30), dp(20)));
        expMusic.addView(row);

        LinearLayout prog = new LinearLayout(this);
        prog.setGravity(Gravity.CENTER_VERTICAL);
        mElapsed = Ui.text(this, 11, 500, Color.argb(150, 235, 235, 245));
        mRemain = Ui.text(this, 11, 500, Color.argb(150, 235, 235, 245));
        mBar = new Widgets.Bar(this);
        prog.addView(mElapsed);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(0, dp(5), 1f);
        bl.leftMargin = dp(10);
        bl.rightMargin = dp(10);
        prog.addView(mBar, bl);
        prog.addView(mRemain);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-1, -2);
        pl.topMargin = dp(16);
        expMusic.addView(prog, pl);

        LinearLayout ctr = new LinearLayout(this);
        ctr.setGravity(Gravity.CENTER);
        ctr.addView(iconBtn(com.glasskit.app.Icon.PREV, 28, v -> {
            if (mc != null) mc.getTransportControls().skipToPrevious();
        }), new LinearLayout.LayoutParams(dp(64), dp(54)));
        FrameLayout playBtn = iconBtn(com.glasskit.app.Icon.PAUSE, 34, v -> {
            if (mc == null) return;
            if (playing) mc.getTransportControls().pause();
            else mc.getTransportControls().play();
        });
        mPlay = (com.glasskit.app.Icon) playBtn.getChildAt(0);
        LinearLayout.LayoutParams ppl = new LinearLayout.LayoutParams(dp(72), dp(54));
        ppl.leftMargin = dp(18);
        ppl.rightMargin = dp(18);
        ctr.addView(playBtn, ppl);
        ctr.addView(iconBtn(com.glasskit.app.Icon.NEXT, 28, v -> {
            if (mc != null) mc.getTransportControls().skipToNext();
        }), new LinearLayout.LayoutParams(dp(64), dp(54)));
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2);
        cl.topMargin = dp(4);
        expMusic.addView(ctr, cl);
        expMusic.setOnClickListener(v -> openMusicApp());
        pill.addView(expMusic, new FrameLayout.LayoutParams(-1, -1));

        // ---- notifikasi besar ----
        expNoti = new LinearLayout(this);
        expNoti.setGravity(Gravity.CENTER_VERTICAL);
        expNoti.setPadding(dp(16), dp(12), dp(20), dp(12));
        nIcon = new ImageView(this);
        nIcon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        roundClip(nIcon, dp(21));
        expNoti.addView(nIcon, new LinearLayout.LayoutParams(dp(42), dp(42)));
        LinearLayout ncol = new LinearLayout(this);
        ncol.setOrientation(LinearLayout.VERTICAL);
        ncol.setPadding(dp(12), 0, dp(8), 0);
        nTitle = Ui.line(this, 15, 600, Color.WHITE);
        nText = Ui.text(this, 14, 400, Color.argb(170, 235, 235, 245));
        nText.setMaxLines(2);
        nText.setEllipsize(TextUtils.TruncateAt.END);
        nText.setPadding(0, dp(3), 0, 0);
        ncol.addView(nTitle);
        ncol.addView(nText);
        expNoti.addView(ncol, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView now = Ui.text(this, 12, 500, Color.argb(130, 235, 235, 245));
        now.setText(R.string.now);
        expNoti.addView(now);
        pill.addView(expNoti, new FrameLayout.LayoutParams(-1, -1));

        // ---- sentuhan ----
        pill.setOnClickListener(v -> onPillTap());
        pill.setOnLongClickListener(v -> {
            if (mode == MUSIC) {
                openMusicApp();
                return true;
            }
            return false;
        });
        root.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_OUTSIDE && expanded) {
                expanded = false;
                if (mode == NOTI) notiActive = false;
                refresh();
            }
            return false;
        });
    }

    private void attach() {
        if (attached) return;
        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.x = xOff;
        lp.y = topY;
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        lp.setTitle("GlassKitIsland");
        try {
            wm.addView(root, lp);
            attached = true;
            setSize(compactH, compactH); // mula sebesar lubang kamera, kemudian kembang
        } catch (Exception ignored) {
        }
    }

    private void detach() {
        if (!attached) return;
        if (anim != null) anim.cancel();
        try { wm.removeViewImmediate(root); } catch (Exception ignored) {}
        attached = false;
        curW = curH = 0;
    }

    private void setSize(int w, int hgt) {
        curW = Math.max(w, 1);
        curH = Math.max(hgt, 1);
        FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) pill.getLayoutParams();
        p.width = curW;
        p.height = curH;
        pill.setLayoutParams(p);
        pillBg.setCornerRadius(Math.min(curH / 2f, dp(38)));
        // turun sedikit bila kembang supaya tak rapat ke bingkai phone
        if (attached && lp != null) {
            int want = topY + (curH > compactH + dp(8) ? dp(6) : 0);
            if (lp.y != want) {
                lp.y = want;
                try { wm.updateViewLayout(root, lp); } catch (Exception ignored) {}
            }
        }
    }

    private void animateTo(final int tw, final int th, boolean grow) {
        if (anim != null) anim.cancel();
        final int sw = curW, sh = curH;
        if (sw == tw && sh == th) return;
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(grow ? 560 : 380);
        anim.setInterpolator(grow ? Ui.SPRING : new DecelerateInterpolator(2.2f));
        anim.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            setSize(Math.round(sw + (tw - sw) * f), Math.round(sh + (th - sh) * f));
        });
        anim.start();
    }

    private void fadeIn(View v) {
        v.setAlpha(0f);
        v.setScaleX(0.96f);
        v.setScaleY(0.96f);
        v.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(110).setDuration(260).start();
    }

    private void refresh() {
        if (pill == null) return;
        boolean on = prefs.getBoolean(Prefs.PILL_ON, true);
        boolean land = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        boolean musicOn = prefs.getBoolean(Prefs.PILL_MUSIC, true)
                && (playing || SystemClock.uptimeMillis() < holdMusicUntil);

        int newMode = IDLE;
        if (on && !land && (panels == null || !panels.isOpen())) {
            if (notiActive) newMode = NOTI;
            else if (musicOn) newMode = MUSIC;
        }
        boolean wasExpanded = compact.getVisibility() == View.GONE && attached;
        mode = newMode;
        if (mode == IDLE) {
            if (sbar != null && sbarShown) sbar.setGeometry(topY + compactH / 2f, screenW / 2f + xOff, compactH / 2f + dp(4));
            expanded = false;
            cWave.setRunning(false);
            mWave.setRunning(false);
            detach();
            return;
        }
        attach();

        compact.setVisibility(expanded ? View.GONE : View.VISIBLE);
        boolean showMusic = expanded && mode == MUSIC;
        boolean showNoti = expanded && mode == NOTI;
        if (showMusic && expMusic.getVisibility() != View.VISIBLE) fadeIn(expMusic);
        if (showNoti && expNoti.getVisibility() != View.VISIBLE) fadeIn(expNoti);
        if (!expanded && wasExpanded) fadeIn(compact);
        expMusic.setVisibility(showMusic ? View.VISIBLE : View.GONE);
        expNoti.setVisibility(showNoti ? View.VISIBLE : View.GONE);

        if (mode == MUSIC) {
            cArt.setVisibility(View.VISIBLE);
            cAppIcon.setVisibility(View.GONE);
            if (art != null) cArt.setImageBitmap(art);
            else cArt.setImageDrawable(Ui.round(Color.rgb(44, 44, 46), dp(7)));
            cText.setVisibility(View.GONE);
            cWave.setVisibility(View.VISIBLE);
            cWave.setColor(artColor);
            cWave.setRunning(playing);
            mWave.setColor(artColor);
            mWave.setRunning(playing && expanded);
        } else {
            cArt.setVisibility(View.GONE);
            cAppIcon.setVisibility(View.VISIBLE);
            cText.setVisibility(View.VISIBLE);
            cWave.setVisibility(View.GONE);
            cWave.setRunning(false);
            mWave.setRunning(false);
        }

        int tw = expanded ? Math.min(screenW - dp(44), dp(400)) : compactW;
        int th = expanded ? (mode == MUSIC ? dp(172) : dp(76)) : compactH;
        animateTo(tw, th, tw >= curW);

        h.removeCallbacks(progTick);
        if (expanded && mode == MUSIC) h.post(progTick);
        if (sbar != null && sbarShown) sbar.setGeometry(topY + compactH / 2f, screenW / 2f + xOff, compactW / 2f);
    }

    private void onPillTap() {
        if (mode == NOTI) {
            sendIntent(notiIntent);
            h.removeCallbacks(endNoti);
            endNoti.run();
        } else if (mode == MUSIC) {
            if (expanded) {
                openMusicApp();
                return;
            }
            expanded = true;
            refresh();
            h.removeCallbacks(autoCollapse);
            h.postDelayed(autoCollapse, 7000);
        }
    }

    void sendIntent(PendingIntent pi) {
        if (pi == null) return;
        try {
            ActivityOptions o = ActivityOptions.makeBasic();
            if (Build.VERSION.SDK_INT >= 34) {
                o.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
            }
            pi.send(this, 0, null, null, null, null, o.toBundle());
        } catch (Exception ignored) {
        }
    }

    void openMusicApp() {
        if (mc != null) sendIntent(mc.getSessionActivity());
        expanded = false;
        refresh();
    }

    // ================= Notifikasi =================

    void onNotification(StatusBarNotification sbn) {
        if (prefs == null || !prefs.getBoolean(Prefs.PILL_ON, true)
                || !prefs.getBoolean(Prefs.PILL_NOTI, true)) return;
        if (panels != null && panels.isOpen()) return;
        Notification n = sbn.getNotification();
        if ((n.flags & Notification.FLAG_ONLY_ALERT_ONCE) != 0) {
            if (seenOnce.size() > 500) seenOnce.clear();
            if (!seenOnce.add(sbn.getKey())) return;
        }
        Bundle ex = n.extras;
        CharSequence t = ex == null ? null : ex.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence body = ex == null ? null : ex.getCharSequence(Notification.EXTRA_TEXT);
        if (TextUtils.isEmpty(t) && TextUtils.isEmpty(body)) return;

        Drawable appIcon = appIcon(sbn.getPackageName());
        Drawable big = null;
        Icon li = n.getLargeIcon();
        if (li != null) {
            try { big = li.loadDrawable(this); } catch (Exception ignored) {}
        }
        show(appIcon, big != null ? big : appIcon, t, body, n.contentIntent);
    }

    Drawable appIcon(String pkg) {
        try { return getPackageManager().getApplicationIcon(pkg); } catch (Exception e) { return null; }
    }

    private void show(Drawable small, Drawable big, CharSequence t, CharSequence body, PendingIntent pi) {
        if (pill == null) return;
        cAppIcon.setImageDrawable(small);
        cText.setText(t);
        nIcon.setImageDrawable(big);
        nTitle.setText(t);
        nText.setText(body);
        notiIntent = pi;
        notiActive = true;
        expanded = true;
        refresh();
        h.removeCallbacks(endNoti);
        h.postDelayed(endNoti, 4200);
    }

    void test() {
        Drawable d = getApplicationInfo().loadIcon(getPackageManager());
        show(d, d, getString(R.string.test_title), getString(R.string.test_text), null);
    }

    // ================= Media =================

    private final MediaSessionManager.OnActiveSessionsChangedListener sessL = controllers -> pick(controllers);

    void initMedia() {
        if (msm != null) return;
        try {
            MediaSessionManager m = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
            ComponentName cn = new ComponentName(this, NotifListener.class);
            m.addOnActiveSessionsChangedListener(sessL, cn, h);
            msm = m;
            pick(m.getActiveSessions(cn));
        } catch (SecurityException ignored) {
        }
    }

    void repickMedia() {
        if (msm == null) return;
        try {
            pick(msm.getActiveSessions(new ComponentName(this, NotifListener.class)));
        } catch (SecurityException ignored) {
        }
    }

    private void pick(List<MediaController> list) {
        MediaController best = null;
        if (list != null) {
            for (MediaController c : list) {
                PlaybackState s = c.getPlaybackState();
                if (s != null && s.getState() == PlaybackState.STATE_PLAYING) {
                    best = c;
                    break;
                }
            }
            if (best == null && !list.isEmpty()) best = list.get(0);
        }
        if (mc != null && best != null && mc.getSessionToken().equals(best.getSessionToken())) {
            refreshMedia();
            return;
        }
        if (mc != null) mc.unregisterCallback(mcb);
        mc = best;
        if (mc != null) mc.registerCallback(mcb, h);
        refreshMedia();
    }

    private class MediaCb extends MediaController.Callback {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) { refreshMedia(); }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) { refreshMedia(); }

        @Override
        public void onSessionDestroyed() {
            h.post(() -> {
                if (mc != null) mc.unregisterCallback(this);
                mc = null;
                repickMedia();
                refreshMedia();
            });
        }
    }

    private void refreshMedia() {
        boolean was = playing;
        Bitmap oldArt = art;
        playing = false;
        title = null;
        artist = null;
        art = null;
        duration = 0;
        if (mc != null) {
            PlaybackState s = mc.getPlaybackState();
            playing = s != null && s.getState() == PlaybackState.STATE_PLAYING;
            MediaMetadata md = mc.getMetadata();
            if (md != null) {
                title = md.getString(MediaMetadata.METADATA_KEY_TITLE);
                artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
                art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
                if (art == null) art = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
                if (art == null) art = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
                duration = md.getLong(MediaMetadata.METADATA_KEY_DURATION);
            }
        }
        if (art != oldArt) artColor = Ui.artColor(art);
        if (was && !playing) {
            holdMusicUntil = SystemClock.uptimeMillis() + 5000;
            h.removeCallbacks(refreshR);
            h.postDelayed(refreshR, 5100);
        }
        if (mTitle != null) {
            mTitle.setText(title != null ? title : getString(R.string.no_title));
            mArtist.setText(artist != null ? artist : "");
            if (art != null) mArt.setImageBitmap(art);
            else mArt.setImageDrawable(null);
            mPlay.setKind(playing ? com.glasskit.app.Icon.PAUSE : com.glasskit.app.Icon.PLAY);
            mBar.setColor(Color.WHITE);
        }
        if (panels != null) panels.onMediaChanged();
        MusicWidget.push(this);
        GlassWidget.updateAll(this, ClockCalWidget.class);
        if (LockActivity.inst != null) LockActivity.inst.refreshMusic();
        refresh();
    }

    long duration() { return duration; }

    long position() {
        if (mc == null) return 0;
        PlaybackState s = mc.getPlaybackState();
        if (s == null) return 0;
        long pos = s.getPosition();
        if (s.getState() == PlaybackState.STATE_PLAYING) {
            pos += (long) ((SystemClock.elapsedRealtime() - s.getLastPositionUpdateTime()) * s.getPlaybackSpeed());
        }
        return Math.max(0, duration > 0 ? Math.min(pos, duration) : pos);
    }

    private void updateProgress() {
        if (mBar == null) return;
        long pos = position();
        if (duration > 0) {
            mBar.setValue((float) pos / duration);
            mElapsed.setText(Ui.mmss(pos));
            mRemain.setText("-" + Ui.mmss(duration - pos));
        } else {
            mBar.setValue(0);
            mElapsed.setText("");
            mRemain.setText("");
        }
    }

    /** Daftar sensor langkah, pemerhati kalendar dan ambil cuaca bila kebenaran ada. */
    void onDataPermissions() {
        if (!stepReg && Data.has(this, android.Manifest.permission.ACTIVITY_RECOGNITION)) {
            try {
                android.hardware.SensorManager sm = getSystemService(android.hardware.SensorManager.class);
                android.hardware.Sensor sc = sm.getDefaultSensor(android.hardware.Sensor.TYPE_STEP_COUNTER);
                if (sc != null) {
                    stepReg = sm.registerListener(stepL, sc, android.hardware.SensorManager.SENSOR_DELAY_NORMAL, 120_000_000);
                }
            } catch (Exception ignored) {
            }
        }
        if (!calObs && Data.has(this, android.Manifest.permission.READ_CALENDAR)) {
            try {
                getContentResolver().registerContentObserver(android.provider.CalendarContract.CONTENT_URI, true, calL);
                calObs = true;
            } catch (Exception ignored) {
            }
        }
        if (System.currentTimeMillis() - lastWx > 40 * 60_000L && Data.has(this, android.Manifest.permission.ACCESS_COARSE_LOCATION)) {
            lastWx = System.currentTimeMillis();
            new Thread(() -> {
                if (Data.fetchWeather(this)) {
                    h.post(() -> {
                        GlassWidget.updateAll(this, WeatherWidget.class);
                        if (LockActivity.inst != null) LockActivity.inst.render();
                    });
                }
            }).start();
        }
        GlassWidget.updateAll(this, StepsWidget.class);
    }

    void showLock() {
        try {
            Intent i = new Intent(this, LockActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION
                    | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
            ActivityOptions o = ActivityOptions.makeBasic();
            if (Build.VERSION.SDK_INT >= 34) {
                o.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
            }
            startActivity(i, o.toBundle());
        } catch (Exception ignored) {
        }
    }

    private void showCharging() {
        if (prefs == null || !prefs.getBoolean(Prefs.PILL_ON, true)) return;
        int lvl = Ui.battery(this);
        Drawable d = Ui.iconDrawable(this, com.glasskit.app.Icon.BOLT, Color.BLACK, 0xFF30D158, dp(42));
        show(d, d, getString(R.string.charging), lvl >= 0 ? lvl + "%" : "", null);
    }

    /**
     * Tutup jam stock lock screen dengan salinan wallpaper GlassKit (piksel sama),
     * hanya ketika lock screen dipaparkan. Hilang bila skrin disentuh.
     */
    private class LockPatch {
        private View v;
        private Bitmap frame;
        private boolean dismissed;

        void screenOn() {
            dismissed = false;
            h.postDelayed(this::maybeShow, 120);
        }

        private boolean onLock() {
            KeyguardManager km = getSystemService(KeyguardManager.class);
            PowerManager pm = getSystemService(PowerManager.class);
            return km != null && km.isKeyguardLocked() && pm != null && pm.isInteractive();
        }

        void maybeShow() {
            if (dismissed || v != null || !prefs.getBoolean(Prefs.HIDE_STOCK, false)
                    || prefs.getBoolean(Prefs.LOCK_ON, false) || !onLock()) return;
            render();
            if (frame == null) return;
            final int hgt = statusH + dp(prefs.getInt(Prefs.HIDE_H, 130));
            final int dimA = Math.round(255 * prefs.getInt(Prefs.PATCH_DIM, 22) / 100f);
            final int feather = dp(44);
            v = new View(PillService.this) {
                final android.graphics.Paint mask = new android.graphics.Paint();
                final android.graphics.Paint dimP = new android.graphics.Paint();

                @Override
                protected void onDraw(Canvas c) {
                    if (frame == null) return;
                    int w = getWidth(), hh = getHeight();
                    int sc = c.saveLayer(0, 0, w, hh, null);
                    c.drawBitmap(frame, 0, 0, null);
                    dimP.setColor(Color.argb(dimA, 0, 0, 0));
                    c.drawRect(0, 0, w, hh, dimP);
                    // pudarkan tepi bawah supaya tiada garisan
                    mask.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN));
                    mask.setShader(new android.graphics.LinearGradient(0, hh - feather, 0, hh,
                            Color.BLACK, Color.TRANSPARENT, android.graphics.Shader.TileMode.CLAMP));
                    c.drawRect(0, hh - feather, w, hh, mask);
                    c.restoreToCount(sc);
                }
            };
            WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, hgt,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                    PixelFormat.TRANSLUCENT);
            p.gravity = Gravity.TOP | Gravity.LEFT;
            p.x = 0;
            p.y = 0;
            p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            p.setTitle("GlassKitLockPatch");
            v.setOnTouchListener((x, e) -> {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN || e.getActionMasked() == MotionEvent.ACTION_OUTSIDE) {
                    dismissed = true;
                    hide(true);
                }
                return false;
            });
            try {
                wm.addView(v, p);
                // status bar & island mesti di atas tampalan
                if (sbarShown) {
                    hideStatusBar();
                    updateStatusBar();
                }
                detach();
                refresh();
            } catch (Exception e) {
                v = null;
            }
        }

        private void render() {
            try {
                Bitmap bg = DepthRenderer.load(DepthRenderer.bgFile(PillService.this));
                if (bg == null) { frame = null; return; }
                Bitmap fg = DepthRenderer.load(DepthRenderer.fgFile(PillService.this));
                if (frame == null || frame.getWidth() != screenW || frame.getHeight() != screenH) {
                    frame = Bitmap.createBitmap(screenW, screenH, Bitmap.Config.ARGB_8888);
                }
                DepthRenderer.draw(PillService.this, new Canvas(frame), screenW, screenH, bg, fg, prefs, true);
            } catch (Throwable t) {
                frame = null;
            }
        }

        void tick() {
            if (v != null) {
                render();
                v.invalidate();
            }
        }

        void hide(boolean fade) {
            final View old = v;
            v = null;
            if (old == null) return;
            if (fade) {
                old.animate().alpha(0f).setDuration(180).withEndAction(() -> {
                    try { wm.removeViewImmediate(old); } catch (Exception ignored) {}
                }).start();
            } else {
                try { wm.removeViewImmediate(old); } catch (Exception ignored) {}
            }
        }
    }

    void onPanelChanged() {
        updateStatusBar();
        refresh();
    }

    void onNotifsChanged() {
        if (LockActivity.inst != null) h.post(() -> { if (LockActivity.inst != null) LockActivity.inst.refreshNotifs(); });
        if (sbar != null && sbarShown) h.post(() -> { if (sbar != null) sbar.refresh(); });
    }

    // ================= Status bar tersuai =================

    void updateStatusBar() {
        if (wm == null || prefs == null) return;
        boolean want = prefs.getBoolean(Prefs.SBAR_ON, false)
                && getResources().getConfiguration().orientation != Configuration.ORIENTATION_LANDSCAPE
                && (panels == null || !panels.isOpen());
        if (!want) {
            hideStatusBar();
            return;
        }
        if (sbar == null) sbar = new StatusBar(this);
        float gap = mode != IDLE ? compactW / 2f : compactH / 2f + dp(4);
        sbar.setGeometry(topY + compactH / 2f, screenW / 2f + xOff, gap);
        if (sbarShown) {
            sbar.refresh();
            return;
        }
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, Math.max(statusH, topY + compactH + dp(2)),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.LEFT;
        p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        p.setTitle("GlassKitStatusBar");
        try {
            wm.addView(sbar, p);
            sbarShown = true;
            sbar.refresh();
            detach(); // pastikan island sentiasa di atas status bar (refresh() akan pasang semula)
        } catch (Exception ignored) {
        }
    }

    private void hideStatusBar() {
        if (sbar != null && sbarShown) {
            try { wm.removeViewImmediate(sbar); } catch (Exception ignored) {}
        }
        sbarShown = false;
    }

    void togglePlay() {
        if (mc == null) return;
        if (playing) mc.getTransportControls().pause();
        else mc.getTransportControls().play();
    }
}
