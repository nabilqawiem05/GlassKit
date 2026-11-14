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
                    break;
                case Intent.ACTION_USER_PRESENT:
                    if (patch != null) patch.hide(true);
                    break;
                case Intent.ACTION_TIME_TICK:
                    if (patch != null) patch.tick();
                    break;
                case Intent.ACTION_POWER_CONNECTED:
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
        registerReceiver(sysRx, f, Context.RECEIVER_EXPORTED);
        registered = true;
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
            registered = false;
        }
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
        refresh();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
        if (panels != null) panels.updateEdges();
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
        pillBg.setCornerRadius(Math.min(curH / 2f, dp(44)));
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

        int tw = expanded ? screenW - dp(16) : compactW;
        int th = expanded ? (mode == MUSIC ? dp(176) : dp(82)) : compactH;
        animateTo(tw, th, tw >= curW);

        h.removeCallbacks(progTick);
        if (expanded && mode == MUSIC) h.post(progTick);
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
            if (dismissed || v != null || !prefs.getBoolean(Prefs.HIDE_STOCK, false) || !onLock()) return;
            render();
            if (frame == null) return;
            final int top = statusH;
            int hgt = dp(prefs.getInt(Prefs.HIDE_H, 130));
            v = new View(PillService.this) {
                @Override
                protected void onDraw(Canvas c) {
                    if (frame != null) c.drawBitmap(frame, 0, -top, null);
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
            p.y = top;
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
        refresh();
    }

    void togglePlay() {
        if (mc == null) return;
        if (playing) mc.getTransportControls().pause();
        else mc.getTransportControls().play();
    }
}
