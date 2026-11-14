package com.glasskit.app;

import android.accessibilityservice.AccessibilityService;
import android.animation.ValueAnimator;
import android.app.ActivityOptions;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
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
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Pil gaya Dynamic Island di sekeliling kamera depan. */
public class PillService extends AccessibilityService
        implements SharedPreferences.OnSharedPreferenceChangeListener {

    static PillService inst;

    private static final int IDLE = 0, MUSIC = 1, NOTI = 2;

    private final Handler h = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private WindowManager.LayoutParams lp;
    private SharedPreferences prefs;

    private FrameLayout root, pill;
    private GradientDrawable pillBg;
    private LinearLayout compact, expMusic, expNoti;
    private ImageView cIcon, mArt, nIcon;
    private TextView cText, mTitle, mArtist, nTitle, nText;
    private EqView cEq;
    private ProgressBar mProg;
    private ImageButton bPlay;

    private boolean attached, expanded;
    private int mode = IDLE;
    private int curW, curH, compactW, compactH, topY, xOff, screenW;
    private ValueAnimator anim;

    private MediaSessionManager msm;
    private MediaController mc;
    private final MediaCb mcb = new MediaCb();
    private boolean playing;
    private long holdMusicUntil, duration;
    private Bitmap art;

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

    // ---------------- Kitaran hidup ----------------

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        inst = this;
        prefs = Prefs.get(this);
        prefs.registerOnSharedPreferenceChangeListener(this);
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        computeGeometry();
        buildViews();
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
        refresh();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
        refresh();
    }

    // ---------------- Geometri & paparan ----------------

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void computeGeometry() {
        WindowMetrics m = wm.getCurrentWindowMetrics();
        Rect b = m.getBounds();
        screenW = b.width();
        Rect r = null;
        DisplayCutout dc = m.getWindowInsets().getDisplayCutout();
        if (dc != null) r = dc.getBoundingRectTop();
        if (r != null && !r.isEmpty()) {
            compactH = Math.max(r.height() + dp(10), dp(32));
            topY = Math.max(0, r.centerY() - compactH / 2);
            xOff = r.centerX() - screenW / 2;
        } else {
            compactH = dp(32);
            topY = dp(8);
            xOff = 0;
        }
        compactW = dp(200);
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

    private TextView text(float sp, boolean bold, int color) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        if (bold) t.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, 600, false));
        return t;
    }

    private ImageButton ctrl(int res, View.OnClickListener l) {
        ImageButton b = new ImageButton(this);
        b.setImageResource(res);
        b.setImageTintList(ColorStateList.valueOf(Color.WHITE));
        b.setBackground(null);
        b.setScaleType(ImageView.ScaleType.FIT_CENTER);
        b.setPadding(dp(10), dp(10), dp(10), dp(10));
        b.setOnClickListener(v -> {
            l.onClick(v);
            h.removeCallbacks(autoCollapse);
            h.postDelayed(autoCollapse, 6000);
        });
        return b;
    }

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

        // --- padat ---
        compact = new LinearLayout(this);
        compact.setOrientation(LinearLayout.HORIZONTAL);
        compact.setGravity(Gravity.CENTER_VERTICAL);
        compact.setPadding(dp(8), 0, dp(12), 0);
        int ic = Math.max(compactH - dp(12), dp(18));
        cIcon = new ImageView(this);
        cIcon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        roundClip(cIcon, dp(6));
        compact.addView(cIcon, new LinearLayout.LayoutParams(ic, ic));
        compact.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        cText = text(12, true, Color.WHITE);
        cText.setMaxWidth(dp(70));
        compact.addView(cText, new LinearLayout.LayoutParams(-2, -2));
        cEq = new EqView(this);
        compact.addView(cEq, new LinearLayout.LayoutParams(dp(20), dp(16)));
        pill.addView(compact, new FrameLayout.LayoutParams(-1, -1));

        // --- muzik besar ---
        expMusic = new LinearLayout(this);
        expMusic.setOrientation(LinearLayout.VERTICAL);
        expMusic.setPadding(dp(18), dp(16), dp(18), dp(8));
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        mArt = new ImageView(this);
        mArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        mArt.setBackgroundColor(Color.rgb(50, 50, 50));
        roundClip(mArt, dp(12));
        row.addView(mArt, new LinearLayout.LayoutParams(dp(56), dp(56)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(12), 0, 0, 0);
        mTitle = text(15, true, Color.WHITE);
        mArtist = text(13, false, Color.rgb(170, 170, 170));
        col.addView(mTitle);
        col.addView(mArtist);
        row.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        expMusic.addView(row);
        mProg = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        mProg.setMax(1000);
        mProg.setProgressTintList(ColorStateList.valueOf(Color.WHITE));
        mProg.setProgressBackgroundTintList(ColorStateList.valueOf(Color.rgb(80, 80, 80)));
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-1, dp(6));
        pl.topMargin = dp(14);
        expMusic.addView(mProg, pl);
        LinearLayout ctr = new LinearLayout(this);
        ctr.setGravity(Gravity.CENTER);
        ctr.addView(ctrl(android.R.drawable.ic_media_previous, v -> {
            if (mc != null) mc.getTransportControls().skipToPrevious();
        }), new LinearLayout.LayoutParams(dp(56), dp(52)));
        bPlay = ctrl(android.R.drawable.ic_media_pause, v -> {
            if (mc == null) return;
            if (playing) mc.getTransportControls().pause();
            else mc.getTransportControls().play();
        });
        ctr.addView(bPlay, new LinearLayout.LayoutParams(dp(64), dp(52)));
        ctr.addView(ctrl(android.R.drawable.ic_media_next, v -> {
            if (mc != null) mc.getTransportControls().skipToNext();
        }), new LinearLayout.LayoutParams(dp(56), dp(52)));
        expMusic.addView(ctr, new LinearLayout.LayoutParams(-1, -2));
        pill.addView(expMusic, new FrameLayout.LayoutParams(-1, -1));

        // --- notifikasi besar ---
        expNoti = new LinearLayout(this);
        expNoti.setGravity(Gravity.CENTER_VERTICAL);
        expNoti.setPadding(dp(16), dp(10), dp(18), dp(10));
        nIcon = new ImageView(this);
        nIcon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        roundClip(nIcon, dp(20));
        expNoti.addView(nIcon, new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout ncol = new LinearLayout(this);
        ncol.setOrientation(LinearLayout.VERTICAL);
        ncol.setPadding(dp(12), 0, 0, 0);
        nTitle = text(14, true, Color.WHITE);
        nText = text(13, false, Color.rgb(200, 200, 200));
        ncol.addView(nTitle);
        ncol.addView(nText);
        expNoti.addView(ncol, new LinearLayout.LayoutParams(0, -2, 1f));
        pill.addView(expNoti, new FrameLayout.LayoutParams(-1, -1));

        pill.setOnClickListener(v -> onPillTap());
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
        lp.setTitle("GlassKitPill");
        try {
            wm.addView(root, lp);
            attached = true;
            setSize(compactH, compactH); // mula kecil, kembang keluar
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
        pillBg.setCornerRadius(Math.min(curH / 2f, dp(30)));
    }

    private void animateTo(final int tw, final int th) {
        if (anim != null) anim.cancel();
        final int sw = curW, sh = curH;
        if (sw == tw && sh == th) return;
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(380);
        anim.setInterpolator(new OvershootInterpolator(0.9f));
        anim.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            setSize(Math.round(sw + (tw - sw) * f), Math.round(sh + (th - sh) * f));
        });
        anim.start();
    }

    private void refresh() {
        if (pill == null) return;
        boolean on = prefs.getBoolean(Prefs.PILL_ON, true);
        boolean land = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        boolean musicOn = prefs.getBoolean(Prefs.PILL_MUSIC, true)
                && (playing || SystemClock.uptimeMillis() < holdMusicUntil);

        int newMode = IDLE;
        if (on && !land) {
            if (notiActive) newMode = NOTI;
            else if (musicOn) newMode = MUSIC;
        }
        mode = newMode;
        if (mode == IDLE) {
            expanded = false;
            cEq.setRunning(false);
            detach();
            return;
        }
        attach();

        compact.setVisibility(expanded ? View.GONE : View.VISIBLE);
        expMusic.setVisibility(expanded && mode == MUSIC ? View.VISIBLE : View.GONE);
        expNoti.setVisibility(expanded && mode == NOTI ? View.VISIBLE : View.GONE);

        if (mode == MUSIC) {
            if (art != null) cIcon.setImageBitmap(art);
            else cIcon.setImageResource(android.R.drawable.ic_media_play);
            cText.setVisibility(View.GONE);
            cEq.setVisibility(View.VISIBLE);
            cEq.setRunning(playing);
        } else {
            cText.setVisibility(View.VISIBLE);
            cEq.setVisibility(View.GONE);
            cEq.setRunning(false);
        }

        int tw = expanded ? screenW - dp(20) : compactW;
        int th = expanded ? (mode == MUSIC ? dp(180) : dp(84)) : compactH;
        animateTo(tw, th);

        h.removeCallbacks(progTick);
        if (expanded && mode == MUSIC) h.post(progTick);
    }

    private void onPillTap() {
        if (mode == NOTI) {
            if (notiIntent != null) {
                try {
                    ActivityOptions o = ActivityOptions.makeBasic();
                    if (Build.VERSION.SDK_INT >= 34) {
                        o.setPendingIntentBackgroundActivityStartMode(
                                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
                    }
                    notiIntent.send(this, 0, null, null, null, null, o.toBundle());
                } catch (Exception ignored) {
                }
            }
            h.removeCallbacks(endNoti);
            endNoti.run();
        } else if (mode == MUSIC) {
            expanded = !expanded;
            refresh();
            h.removeCallbacks(autoCollapse);
            if (expanded) h.postDelayed(autoCollapse, 6000);
        }
    }

    // ---------------- Notifikasi ----------------

    void onNotification(StatusBarNotification sbn) {
        if (prefs == null || !prefs.getBoolean(Prefs.PILL_ON, true)
                || !prefs.getBoolean(Prefs.PILL_NOTI, true)) return;
        Notification n = sbn.getNotification();
        if ((n.flags & Notification.FLAG_ONLY_ALERT_ONCE) != 0) {
            if (seenOnce.size() > 500) seenOnce.clear();
            if (!seenOnce.add(sbn.getKey())) return;
        }
        Bundle ex = n.extras;
        CharSequence title = ex == null ? null : ex.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence body = ex == null ? null : ex.getCharSequence(Notification.EXTRA_TEXT);
        if (TextUtils.isEmpty(title) && TextUtils.isEmpty(body)) return;

        Drawable appIcon = null;
        try { appIcon = getPackageManager().getApplicationIcon(sbn.getPackageName()); } catch (Exception ignored) {}
        Drawable big = null;
        Icon li = n.getLargeIcon();
        if (li != null) {
            try { big = li.loadDrawable(this); } catch (Exception ignored) {}
        }
        show(appIcon, big != null ? big : appIcon, title, body, n.contentIntent);
    }

    private void show(Drawable small, Drawable big, CharSequence title, CharSequence body, PendingIntent pi) {
        if (pill == null) return;
        cIcon.setImageDrawable(small);
        cText.setText(title);
        nIcon.setImageDrawable(big);
        nTitle.setText(title);
        nText.setText(body);
        notiIntent = pi;
        notiActive = true;
        expanded = true;
        refresh();
        h.removeCallbacks(endNoti);
        h.postDelayed(endNoti, 4000);
    }

    void test() {
        show(getDrawable(android.R.drawable.sym_action_chat), getDrawable(android.R.drawable.sym_action_chat),
                "GlassKit", "Pil berfungsi! Tekan untuk tutup.", null);
    }

    // ---------------- Media ----------------

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
            // Notification access belum diberi
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
        playing = false;
        String title = null, artist = null;
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
        if (was && !playing) {
            holdMusicUntil = SystemClock.uptimeMillis() + 5000;
            h.removeCallbacks(refreshR);
            h.postDelayed(refreshR, 5100);
        }
        if (mTitle != null) {
            mTitle.setText(title != null ? title : "Tiada tajuk");
            mArtist.setText(artist != null ? artist : "");
            if (art != null) mArt.setImageBitmap(art);
            else mArt.setImageResource(android.R.drawable.ic_media_play);
            bPlay.setImageResource(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);
        }
        refresh();
    }

    private void updateProgress() {
        if (mc == null || mProg == null || duration <= 0) return;
        PlaybackState s = mc.getPlaybackState();
        if (s == null) return;
        long pos = s.getPosition();
        if (s.getState() == PlaybackState.STATE_PLAYING) {
            pos += (long) ((SystemClock.elapsedRealtime() - s.getLastPositionUpdateTime()) * s.getPlaybackSpeed());
        }
        mProg.setProgress((int) Math.max(0, Math.min(1000, 1000L * pos / duration)));
    }

    // ---------------- Bar equalizer kecil ----------------

    static class EqView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean running;

        EqView(Context c) {
            super(c);
            p.setColor(Color.rgb(138, 180, 248));
        }

        void setRunning(boolean r) {
            running = r;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), hh = getHeight();
            float bw = w / 5f;
            long t = SystemClock.uptimeMillis();
            for (int i = 0; i < 3; i++) {
                float f = running ? (float) (0.35 + 0.65 * Math.abs(Math.sin(t / 180.0 + i * 1.3))) : 0.3f;
                float bh = hh * f;
                float x = bw * (i * 2);
                c.drawRoundRect(x, (hh - bh) / 2f, x + bw, (hh + bh) / 2f, bw / 2f, bw / 2f, p);
            }
            if (running && isShown()) postInvalidateOnAnimation();
        }
    }
}
