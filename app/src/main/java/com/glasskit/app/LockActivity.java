package com.glasskit.app;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.provider.MediaStore;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Lock screen GlassKit: dipaparkan di atas kunci sistem (showWhenLocked).
 * Membuka kunci tetap melalui sistem (wajah / cap jari / PIN) dengan requestDismissKeyguard.
 */
public class LockActivity extends Activity {
    static LockActivity inst;
    static boolean visible;
    /** Jangan dipaksa semula sehingga masa ini (bouncer PIN / kamera sedang dibuka). */
    static volatile long holdUntil;
    private static final java.util.concurrent.ExecutorService DATA =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> new Thread(r, "gk-lock-data"));
    /** Bingkai terakhir dikongsi antara instance: lock screen muncul serta-merta, tiada skrin hitam. */
    private static Bitmap lastFrame;
    private static String lastKey;
    private static final LiquidGlass.Backdrop lastBd = new LiquidGlass.Backdrop();
    private static final java.util.concurrent.ExecutorService RENDER =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "gk-lock-render");
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            });
    private boolean renderBusy, renderAgain;
    private int lastBatt = -2;
    private boolean lastPlug;
    // data tolok (dikira di latar, dilukis di UI)
    private volatile String gLine;
    private volatile int gCol = 0xFF0A84FF, gSteps, gLvl;
    private volatile boolean gCharging;
    private volatile Data.Weather gWx;

    private final Handler h = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private KeyguardManager km;
    private int W, H, statusH;

    private ImageView wall;
    private ImageView wallBlur; // salinan kabur (dikira sekali) — dipudar masuk semasa slide
    private FrameLayout ui;
    private GaugeRow gauges;
    private LinearLayout bottom, notifList, musicBox;
    private MaxScroll notifScroll;
    private View notifHead;

    /** Hadkan senarai notifikasi supaya tidak menindih jam & tolok (ia boleh skrol). */
    private void fitNotifs() {
        if (notifScroll == null || gauges == null) return;
        notifScroll.post(() -> {
            try {
                int[] g = new int[2], sc = new int[2];
                View above = gauges.getVisibility() == View.VISIBLE && gauges.getHeight() > 0 ? gauges : null;
                int topLimit;
                if (above != null) {
                    above.getLocationInWindow(g);
                    topLimit = g[1] + above.getHeight() + dp(6);
                } else {
                    topLimit = Math.round(getResources().getDisplayMetrics().heightPixels * 0.42f);
                }
                notifScroll.getLocationInWindow(sc);
                int scrollBottom = sc[1] + notifScroll.getHeight();
                int head = notifHead != null ? notifHead.getHeight() + dp(8) : 0;
                int max = Math.max(dp(90), scrollBottom - topLimit - head);
                if (Math.abs(max - notifScroll.max) > dp(2)) {
                    notifScroll.max = max;
                    notifScroll.requestLayout();
                }
            } catch (Throwable ignored) {
            }
        });
    }
    private UnlockAnim lockAnim;
    private RevealView reveal;
    private static Bitmap lastPlain;
    /** Kotak orang dalam gambar (piksel skrin) untuk elak widget bertindih. */
    private static volatile RectF subjScreen;
    private static String plainKey = "";

    private static String imgKey(SharedPreferences p) {
        return p.getLong(Prefs.IMG_VER, 0) + "|" + p.getBoolean(Prefs.WALL_MOOD, false);
    }
    private static RectF lastPopFrame;

    /** Lukis bitmap asal dengan potongan bulat yang membesar dari "dari" ke skrin penuh. */
    private static final class RevealView extends View {
        Bitmap bmp;
        final RectF from = new RectF(), cur = new RectF();
        float t, radius;
        final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG | android.graphics.Paint.ANTI_ALIAS_FLAG);
        final android.graphics.Path clip = new android.graphics.Path();

        RevealView(Context c) { super(c); }

        void set(float v) {
            t = v;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            if (bmp == null) return;
            float w = getWidth(), h = getHeight();
            float e = Math.max(0f, Math.min(1f, t));
            cur.set(from.left + (0 - from.left) * e, from.top + (0 - from.top) * e,
                    from.right + (w - from.right) * e, from.bottom + (h - from.bottom) * e);
            float r = radius * (1f - e);
            clip.reset();
            clip.addRoundRect(cur, r, r, android.graphics.Path.Direction.CW);
            c.save();
            c.clipPath(clip);
            c.drawBitmap(bmp, null, new RectF(0, 0, w, h), p);
            c.restore();
        }
    }
    private TextView hint, clearAll;
    private Bitmap frame, bg, fg;
    private long imgVer = Long.MIN_VALUE;
    private final Set<String> expanded = new HashSet<>();
    private String replyKey;

    private String torchId;
    private boolean torchOn;
    private CameraManager.TorchCallback torchCb;
    private Runnable afterUnlock;
    private boolean leaving, soundPlayed;
    private long lastSensorTouch;
    private int hintRes;
    private long hintHold;
    final LiquidGlass.Backdrop bd = new LiquidGlass.Backdrop();

    private final BroadcastReceiver rx = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (Intent.ACTION_BATTERY_CHANGED.equals(i.getAction())) {
                int lv = i.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1);
                boolean pl = i.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) != 0;
                if (lv == lastBatt && pl == lastPlug) return;
                lastBatt = lv;
                lastPlug = pl;
                refreshGauges();
                return;
            }
            render();
            refreshGauges();
        }
    };

    private final Runnable lockPoll = new Runnable() {
        @Override
        public void run() {
            boolean locked = km.isDeviceLocked();
            if (!locked && lockAnim.state() == UnlockAnim.LOCKED && !leaving) {
                // dibuka tanpa bouncer: cap jari (jika baru sentuh kawasan sensor) atau wajah
                boolean finger = System.currentTimeMillis() - lastSensorTouch < 2500;
                lockAnim.play(finger ? UnlockAnim.FINGER : UnlockAnim.FACE, () -> {
                    if (!soundPlayed) {
                        soundPlayed = true;
                        Sounds.play(LockActivity.this, Sounds.UNLOCK);
                    }
                });
            } else if (locked && lockAnim.state() != UnlockAnim.LOCKED && !leaving) {
                lockAnim.setLocked();
                soundPlayed = false;
            }
            int want = locked ? R.string.swipe_open : R.string.unlocked_swipe;
            if (hintRes != want && System.currentTimeMillis() > hintHold) {
                hintRes = want;
                hint.setText(want);
            }
            h.postDelayed(this, 300);
        }
    };

    private int dp(float v) { return Ui.dp(this, v); }

    /** ScrollView dengan ketinggian maksimum. */
    private static class MaxScroll extends ScrollView {
        int max;

        MaxScroll(Context c) { super(c); }

        @Override
        protected void onMeasure(int w, int hs) {
            super.onMeasure(w, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST));
        }
    }

    // ======================= Kitaran hidup =======================

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        CrashLog.install(this);
        inst = this;
        prefs = Prefs.get(this);
        km = getSystemService(KeyguardManager.class);
        setShowWhenLocked(true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        getWindow().getAttributes().layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        getWindow().setDecorFitsSystemWindows(false);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN);

        Rect bounds = getSystemService(WindowManager.class).getCurrentWindowMetrics().getBounds();
        W = bounds.width();
        H = bounds.height();
        statusH = getSystemService(WindowManager.class).getCurrentWindowMetrics().getWindowInsets()
                .getInsets(WindowInsets.Type.statusBars()).top;
        if (statusH <= 0) statusH = dp(32);

        LiquidGlass.current = bd;
        Root root = new Root(this);
        root.setBackgroundColor(Color.BLACK);
        wall = new ImageView(this);
        wall.setScaleType(ImageView.ScaleType.FIT_XY);
        root.addView(wall, new FrameLayout.LayoutParams(-1, -1));
        wallBlur = new ImageView(this);
        wallBlur.setScaleType(ImageView.ScaleType.FIT_XY);
        wallBlur.setAlpha(0f);
        wallBlur.setVisibility(View.INVISIBLE);
        root.addView(wallBlur, new FrameLayout.LayoutParams(-1, -1));
        synchronized (lastBd) {
            if (lastFrame != null && lastFrame.getWidth() == W && lastFrame.getHeight() == H) {
                frame = lastFrame;
                wall.setImageBitmap(frame);
                copyBd(lastBd, bd);
                wallBlur.setImageBitmap(bd.soft);
            }
        }

        // gambar asal (tanpa gaya timbul) yang "mekar" ke skrin penuh bila buka kunci
        reveal = new RevealView(this);
        reveal.setVisibility(View.GONE);
        root.addView(reveal, new FrameLayout.LayoutParams(-1, -1));

        ui = new FrameLayout(this);
        root.addView(ui, new FrameLayout.LayoutParams(-1, -1));

        lockAnim = new UnlockAnim(this);
        FrameLayout.LayoutParams ll = new FrameLayout.LayoutParams(dp(56), dp(56), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        ll.topMargin = statusH + dp(2);
        ui.addView(lockAnim, ll);

        gauges = new GaugeRow(this);
        ui.addView(gauges, new FrameLayout.LayoutParams(-1, dp(prefs.getBoolean(Prefs.LOCK_PRAYER, false) ? 168 : 130)));

        bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(dp(14), 0, dp(14), dp(22));
        musicBox = new LinearLayout(this);
        musicBox.setOrientation(LinearLayout.VERTICAL);
        bottom.addView(musicBox, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout nh = new LinearLayout(this);
        nh.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        clearAll = Ui.text(this, 13, 650, Color.WHITE);
        clearAll.setText(R.string.clear_all);
        clearAll.setPadding(dp(14), dp(7), dp(14), dp(7));
        clearAll.setBackground(GlassEngine.drawable(this, 16, true, 30));
        clearAll.setOnClickListener(v -> {
            NotifListener l = NotifListener.inst;
            if (l != null) {
                try { l.cancelAllNotifications(); } catch (Exception ignored) {}
            }
            h.postDelayed(this::refreshNotifs, 300);
        });
        Ui.press(clearAll);
        nh.addView(clearAll);
        LinearLayout.LayoutParams nhl = new LinearLayout.LayoutParams(-1, -2);
        nhl.topMargin = dp(8);
        bottom.addView(nh, nhl);

        notifHead = nh;
        notifScroll = new MaxScroll(this);
        notifScroll.max = Math.round(H * 0.38f);
        notifScroll.setVerticalScrollBarEnabled(false);
        notifScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        notifList = new LinearLayout(this);
        notifList.setOrientation(LinearLayout.VERTICAL);
        notifScroll.addView(notifList, new ScrollView.LayoutParams(-1, -2));
        bottom.addView(notifScroll, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        torchBtn = holdCircle(Icon.TORCH, this::toggleTorch);
        bar.addView(torchBtn, new LinearLayout.LayoutParams(dp(54), dp(54)));
        hint = Ui.text(this, 14, 600, Color.WHITE);
        hint.setGravity(Gravity.CENTER);
        hint.setShadowLayer(dp(4), 0, dp(1), Color.argb(130, 0, 0, 0));
        bar.addView(hint, new LinearLayout.LayoutParams(0, -2, 1f));
        bar.addView(holdCircle(Icon.CAMERA, () -> {
            Intent i = new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            holdUntil = System.currentTimeMillis() + 10 * 60_000L;
            try { startActivity(i); } catch (Exception ignored) {}
        }), new LinearLayout.LayoutParams(dp(54), dp(54)));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, -2);
        bl.topMargin = dp(14);
        bottom.addView(bar, bl);
        ui.addView(bottom, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        setContentView(root);
        LiquidGlass.current = null;
        hideBars();

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_TIME_TICK);
        f.addAction(Intent.ACTION_TIME_CHANGED);
        f.addAction(Intent.ACTION_BATTERY_CHANGED);
        registerReceiver(rx, f, Context.RECEIVER_EXPORTED);
        watchTorch();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        resetUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (PillService.inst != null && PillService.inst.alarmActive()) {
            // penggera sedang berbunyi: beri laluan kepada skrin penggera
            moveTaskToBack(true);
            return;
        }
        visible = true;
        holdUntil = 0;
        // tanggalkan penutup selepas bingkai pertama aktiviti ini dilukis
        getWindow().getDecorView().postDelayed(() -> {
            if (visible && PillService.inst != null) PillService.inst.lockCoverReady();
        }, 120);
        hideBars();
        try {
            resetUi();
        } catch (Throwable t) {
            CrashLog.write(this, t);
        }
        if (PillService.inst != null) PillService.inst.updateStatusBar();
        h.removeCallbacks(lockPoll);
        h.post(lockPoll);
    }

    @Override
    protected void onPause() {
        super.onPause();
        visible = false;
        if (clockEdit != null) endClockEdit();
        if (stackOpen) {
            stackOpen = false; // tindan semula bila skrin dimatikan / dibuka kunci
            refreshNotifs();
        }
        if (leaving) holdUntil = System.currentTimeMillis() + 5000;
        if (PillService.inst != null) PillService.inst.hideBanner(false);
        h.removeCallbacks(lockPoll);
        if (PillService.inst != null) PillService.inst.updateStatusBar();
    }

    @Override
    protected void onDestroy() {
        if (inst == this) inst = null;
        h.removeCallbacksAndMessages(null);
        try { unregisterReceiver(rx); } catch (Exception ignored) {}
        if (torchCb != null) {
            try { getSystemService(CameraManager.class).unregisterTorchCallback(torchCb); } catch (Exception ignored) {}
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (replyKey != null) {
            replyKey = null;
            refreshNotifs();
        }
    }

    private void hideBars() {
        WindowInsetsController c = getWindow().getInsetsController();
        if (c != null) {
            c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
            c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    }

    private void resetUi() {
        ui.animate().cancel();
        wall.animate().cancel();
        leaving = false;
        afterUnlock = null;
        replyKey = null;
        expanded.clear();
        ui.setTranslationY(0);
        ui.setAlpha(1f);
        wall.setAlpha(1f);
        if (reveal != null) {
            reveal.setVisibility(View.GONE);
            reveal.setScaleX(1f);
            reveal.setScaleY(1f);
            reveal.setRenderEffect(null);
        }
        wall.setTranslationY(0);
        wall.setScaleX(1f);
        wall.setScaleY(1f);
        wall.setRenderEffect(null);
        wallBlur.animate().cancel();
        wallBlur.setAlpha(0f);
        wallBlur.setScaleX(1f);
        wallBlur.setScaleY(1f);
        unlockSpring.stop();
        // masuk gaya iPhone: wallpaper bermula sedikit zoom & kabur, kemudian "zoom out" ke tempatnya
        if (prefs.getBoolean(Prefs.LOCK_ZOOM, true)) {
            applyUnlock(0.32f, 0f);
            unlockSpring.value = 0.32f;
            unlockSpring.to(0f, 0f, null);
        } else {
            applyUnlock(0f, 0f);
        }
        if (km.isDeviceLocked()) {
            lockAnim.setLocked();
            soundPlayed = false;
        }
        render();
        refreshGauges();
        refreshNotifs();
        refreshMusic();
    }

    // ======================= Lukisan =======================

    // ---------- leret kiri/kanan di kawasan jam: tukar muka ----------
    private float fx0, fy0;
    private boolean fTrack;

    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        try {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    fx0 = e.getRawX();
                    fy0 = e.getRawY();
                    fTrack = prefs.getBoolean(Prefs.FACE_SWIPE, true)
                            && fy0 < getResources().getDisplayMetrics().heightPixels * 0.5f;
                    break;
                case MotionEvent.ACTION_UP:
                    if (fTrack) {
                        float dx = e.getRawX() - fx0, dy = e.getRawY() - fy0;
                        if (Math.abs(dx) > dp(90) && Math.abs(dy) < dp(70)) {
                            fTrack = false;
                            switchFace(dx < 0 ? 1 : -1);
                            MotionEvent c = MotionEvent.obtain(e);
                            c.setAction(MotionEvent.ACTION_CANCEL);
                            super.dispatchTouchEvent(c);
                            c.recycle();
                            return true;
                        }
                    }
                    break;
            }
        } catch (Throwable ignored) {
        }
        return super.dispatchTouchEvent(e);
    }

    private TextView faceLabel;

    private void switchFace(int dir) {
        int next = (Faces.active(this) + dir + Faces.COUNT) % Faces.COUNT;
        if (faceLabel == null) {
            faceLabel = Ui.text(this, 22, 700, Color.WHITE);
            faceLabel.setGravity(Gravity.CENTER);
            faceLabel.setPadding(dp(22), dp(10), dp(22), dp(10));
            faceLabel.setBackground(GlassEngine.drawable(this, 24, true, 30));
            ((android.view.ViewGroup) wall.getParent()).addView(faceLabel, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        }
        faceLabel.setText(Faces.NAMES[next]);
        faceLabel.animate().cancel();
        faceLabel.setAlpha(1f);
        faceLabel.setScaleX(0.8f);
        faceLabel.setScaleY(0.8f);
        faceLabel.animate().scaleX(1f).scaleY(1f).setInterpolator(Ui.SPRING).setDuration(Ui.SPRING_MS).start();
        wall.animate().alpha(0.35f).translationX(dir < 0 ? dp(30) : -dp(30)).setDuration(180).start();
        Faces.switchTo(this, next, () -> h.post(() -> {
            render();
            wall.setTranslationX(dir < 0 ? -dp(30) : dp(30));
            wall.animate().alpha(1f).translationX(0).setInterpolator(Ui.SPRING).setDuration(Ui.SPRING_MS).start();
            faceLabel.animate().alpha(0f).setStartDelay(700).setDuration(400).start();
        }));
    }

    interface FrameCb { void done(Bitmap b); }

    /** Bingkai terakhir jika saiznya sepadan dengan skrin. */
    static Bitmap frameFor(android.content.Context c) {
        synchronized (lastBd) {
            return lastFrame;
        }
    }

    /** Lukis wallpaper + jam (tanpa aktiviti) di thread latar; digunakan oleh penutup lock screen. */
    static void renderInBackground(android.content.Context c, FrameCb cb) {
        final android.content.Context app = c.getApplicationContext();
        RENDER.execute(() -> {
            Bitmap out = null;
            try {
                android.graphics.Rect b = app.getSystemService(android.view.WindowManager.class).getMaximumWindowMetrics().getBounds();
                int w = b.width(), hh = b.height();
                Bitmap bgB = DepthRenderer.load(DepthRenderer.bgFile(app));
                Bitmap fgB = DepthRenderer.load(DepthRenderer.fgFile(app));
                out = Bitmap.createBitmap(w, hh, Bitmap.Config.ARGB_8888);
                DepthRenderer.draw(app, new Canvas(out), w, hh, bgB, fgB, Prefs.get(app), true);
            } catch (Throwable t) {
                CrashLog.write(app, t);
            }
            cb.done(out);
        });
    }

    private static void copyBd(LiquidGlass.Backdrop from, LiquidGlass.Backdrop to) {
        to.sharp = from.sharp;
        to.soft = from.soft;
        to.screenW = from.screenW;
        to.screenH = from.screenH;
        to.luminance = from.luminance;
    }

    private static final String[] RENDER_KEYS = {Prefs.CLOCK_SIZE, Prefs.CLOCK_Y, Prefs.CLOCK_COLOR, Prefs.CLOCK_FONT,
            Prefs.CLOCK_W, Prefs.CLOCK_H, Prefs.CLOCK_GLASS, Prefs.WALL_MOOD, Prefs.CLOCK_LAYOUT, Prefs.CLOCK_SHAPE,
            Prefs.POP_STYLE, Prefs.CLOCK_AUTO, Prefs.LOCK_HIJRI, Prefs.HIJRI_ADJ};

    /** Tandatangan tetapan yang mempengaruhi lukisan sahaja (bukan semua prefs). */
    private String renderSig() {
        StringBuilder sb = new StringBuilder();
        java.util.Map<String, ?> all = prefs.getAll();
        for (String k : RENDER_KEYS) sb.append(all.get(k)).append(',');
        sb.append(Ui.darkGlass(this));
        return Integer.toHexString(sb.toString().hashCode());
    }

    private String frameKey() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        return prefs.getLong(Prefs.IMG_VER, 0) + "|" + c.get(java.util.Calendar.DAY_OF_YEAR) + "|"
                + c.get(java.util.Calendar.HOUR_OF_DAY) + ":" + c.get(java.util.Calendar.MINUTE) + "|"
                + renderSig() + "|" + W + "x" + H + "|" + java.util.Locale.getDefault();
    }

    /** Lukis bingkai wallpaper + jam di thread latar; UI tidak tersekat. Bingkai lama kekal sehingga yang baru siap. */
    void render() {
        try {
            placeGauges();
        } catch (Throwable ignored) {
        }
        final String key = frameKey();
        if (key.equals(lastKey) && frame != null) return;
        if (renderBusy) {
            renderAgain = true;
            return;
        }
        renderBusy = true;
        final android.content.Context app = getApplicationContext();
        final int w = W, hh = H;
        RENDER.execute(() -> {
            Bitmap out = null;
            LiquidGlass.Backdrop nb = new LiquidGlass.Backdrop();
            try {
                Bitmap bgB = DepthRenderer.load(DepthRenderer.bgFile(app));
                Bitmap fgB = DepthRenderer.load(DepthRenderer.fgFile(app));
                out = Bitmap.createBitmap(w, hh, Bitmap.Config.ARGB_8888);
                DepthRenderer.draw(app, new Canvas(out), w, hh, bgB, fgB, prefs, true);
                nb.set(out, w, hh);
                if (fgB != null) {
                    RectF sbx = DepthRenderer.subjectBox(fgB, DepthRenderer.centerCrop(fgB, w, hh));
                    float sc = (float) app.getResources().getDisplayMetrics().widthPixels / w;
                    subjScreen = new RectF(sbx.left * sc, sbx.top * sc, sbx.right * sc, sbx.bottom * sc);
                } else {
                    subjScreen = null;
                }
                int pop = prefs.getInt(Prefs.POP_STYLE, 0);
                if (pop != 0 && bgB != null && fgB != null) {
                    Bitmap pl = lastPlain;
                    if (pl == null || pl.getWidth() != w || pl.getHeight() != hh || !imgKey(prefs).equals(plainKey)) {
                        pl = Bitmap.createBitmap(w, hh, Bitmap.Config.ARGB_8888);
                        DepthRenderer.draw(app, new Canvas(pl), w, hh, bgB, fgB, prefs, false); // sama seperti home
                        plainKey = imgKey(prefs);
                    }
                    lastPlain = pl;
                    lastPopFrame = (pop == 1 || pop == 3) ? DepthRenderer.popFrame(fgB, w, hh) : null;
                } else {
                    lastPlain = null;
                }
            } catch (Throwable t) {
                CrashLog.write(app, t);
            }
            final Bitmap done = out;
            h.post(() -> {
                renderBusy = false;
                if (done != null) {
                    frame = done;
                    synchronized (lastBd) {
                        lastFrame = done;
                        lastKey = key;
                        copyBd(nb, lastBd);
                    }
                    if (!isDestroyed()) {
                        wall.setImageBitmap(done);
                        if (gauges != null) gauges.invalidate();
                        copyBd(nb, bd);
                        wallBlur.setImageBitmap(bd.soft);
                        LiquidGlass.invalidateTree(ui);
                        gauges.invalidate();
                    }
                }
                if (renderAgain) {
                    renderAgain = false;
                    if (!isDestroyed()) render();
                }
            });
        });
    }

    private void placeGauges() {
        float bottom = DepthRenderer.gaugeTop(this, prefs, W, H);
        float scale = (float) getResources().getDisplayMetrics().widthPixels / W;
        FrameLayout.LayoutParams gl = (FrameLayout.LayoutParams) gauges.getLayoutParams();
        int top = Math.round(bottom * scale) + dp(16);
        if (gl.topMargin != top) {
            gl.topMargin = top;
            gauges.setLayoutParams(gl);
            fitNotifs();
        }
    }

    /** Kira data tolok (kalendar, cuaca, langkah, bateri) di latar. */
    void refreshGauges() {
        final android.content.Context ctx = getApplicationContext();
        DATA.execute(() -> {
            try {
                List<Data.Event> ev = Data.events(ctx, 2, 1);
                String line;
                int col = Ui.accent(ctx, false);
                if (ev.isEmpty()) {
                    line = ""; // tiada acara: baris disorok supaya kemas
                } else {
                    Data.Event e = ev.get(0);
                    col = e.color;
                    line = (e.allDay ? ctx.getString(R.string.all_day) : Data.eventTime(ctx, e).split(" – ")[0]) + "  " + e.title;
                }
                gLine = line;
                gPrayer = prefs.getBoolean(Prefs.LOCK_PRAYER, false) ? Cards.nextPrayerInfo(ctx) : null;
                gCol = col;
                gWx = Data.weather(ctx);
                gSteps = Data.steps(ctx);
                gLvl = Math.max(0, Ui.battery(ctx));
                gCharging = Ui.charging(ctx);
            } catch (Throwable t) {
                CrashLog.write(ctx, t);
            }
            h.post(() -> { if (gauges != null) gauges.invalidate(); });
        });
    }

    // ======================= Sunting saiz jam terus di lock screen =======================

    private ClockEdit clockEdit;
    private final Runnable clockHold = () -> {
        if (clockEdit != null || isFinishing()) return;
        Haptics.thud(this);
        clockEdit = new ClockEdit(this);
        ((FrameLayout) ui.getParent()).addView(clockEdit, new FrameLayout.LayoutParams(-1, -1));
        ui.animate().alpha(0.25f).setDuration(200).start();
        clockEdit.setAlpha(0f);
        clockEdit.animate().alpha(1f).setDuration(200).start();
    };

    private boolean clockHit(float x, float y) {
        try {
            RectF r = DepthRenderer.clockRect(this, prefs, W, H);
            r.inset(-dp(16), -dp(16));
            return r.contains(x, y);
        } catch (Throwable t) {
            return false;
        }
    }

    private void endClockEdit() {
        final ClockEdit ce = clockEdit;
        if (ce == null) return;
        clockEdit = null;
        Haptics.tap(this);
        ui.animate().alpha(1f).setDuration(220).start();
        ce.animate().alpha(0f).setDuration(180).withEndAction(() -> {
            ViewGroup p = (ViewGroup) ce.getParent();
            if (p != null) p.removeView(ce);
        }).start();
        render();
    }

    /** Bingkai sunting: pemegang bawah = tinggi, pemegang kanan = lebar. Ketik di luar = simpan. */
    private final class ClockEdit extends View {
        private final android.graphics.Paint line = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint knob = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private RectF box = new RectF();
        private int mode; // 0 tiada, 1 tinggi, 2 lebar, 3 alih atas/bawah
        private float sx, sy, h0, w0, bh0, bw0, y0;
        private int lastStep = -1;
        private long lastApply;

        int hMax() { return 600; }

        ClockEdit(Context c) {
            super(c);
            line.setStyle(android.graphics.Paint.Style.STROKE);
            line.setStrokeWidth(dp(1.6f));
            line.setColor(Color.argb(200, 255, 255, 255));
            line.setPathEffect(new android.graphics.DashPathEffect(new float[]{dp(7), dp(5)}, 0));
            knob.setColor(Color.WHITE);
            update();
        }

        void update() {
            box = DepthRenderer.clockRect(getContext(), prefs, W, H);
            box.inset(-dp(10), -dp(8));
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float r = dp(18);
            c.drawRoundRect(box, r, r, line);
            // pemegang bawah (tinggi)
            float bx = box.centerX(), by = box.bottom;
            c.drawRoundRect(new RectF(bx - dp(26), by - dp(5), bx + dp(26), by + dp(5)), dp(5), dp(5), knob);
            // pemegang kanan (lebar)
            float rx = box.right, ry = box.centerY();
            c.drawRoundRect(new RectF(rx - dp(5), ry - dp(22), rx + dp(5), ry + dp(22)), dp(5), dp(5), knob);
            android.graphics.Paint tp = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            tp.setColor(Color.WHITE);
            tp.setTextSize(dp(13));
            tp.setTypeface(Ui.font(650));
            tp.setTextAlign(android.graphics.Paint.Align.CENTER);
            c.drawText(getString(R.string.clock_edit_hint), W / 2f, Math.min(H - dp(60), box.bottom + dp(44)), tp);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    float x = e.getX(), y = e.getY();
                    boolean nearBottom = Math.abs(y - box.bottom) < dp(36) && x > box.left && x < box.right;
                    boolean nearRight = Math.abs(x - box.right) < dp(36) && y > box.top && y < box.bottom;
                    boolean inside = box.contains(x, y);
                    mode = nearBottom ? 1 : nearRight ? 2 : inside ? 3 : 0;
                    if (mode == 0) {
                        endClockEdit();
                        return true;
                    }
                    sx = e.getRawX();
                    sy = e.getRawY();
                    h0 = prefs.getInt(Prefs.CLOCK_H, 100);
                    w0 = prefs.getInt(Prefs.CLOCK_W, 100);
                    y0 = prefs.getInt(Prefs.CLOCK_Y, 28);
                    bh0 = box.height();
                    bw0 = box.width();
                    Haptics.tap(getContext());
                    return true;
                }
                case MotionEvent.ACTION_MOVE: {
                    int v;
                    String k;
                    if (mode == 1) {
                        float f = Math.max(0.3f, (bh0 + (e.getRawY() - sy)) / bh0);
                        v = Math.round(Math.max(60, Math.min(hMax(), h0 * f)));
                        k = Prefs.CLOCK_H;
                    } else if (mode == 2) {
                        float f = Math.max(0.3f, (bw0 + 2 * (e.getRawX() - sx)) / bw0);
                        v = Math.round(Math.max(60, Math.min(170, w0 * f)));
                        k = Prefs.CLOCK_W;
                    } else if (mode == 3) {
                        // alih jam: ikut jari, dalam % tinggi skrin
                        float scale = (float) getResources().getDisplayMetrics().widthPixels / W;
                        float dPct = (e.getRawY() - sy) / scale / H * 100f;
                        v = Math.round(Math.max(8, Math.min(70, y0 + dPct)));
                        k = Prefs.CLOCK_Y;
                    } else return true;
                    int step = mode == 3 ? v : v / 5;
                    if (step != lastStep) {
                        lastStep = step;
                        if ((mode != 3 && (v == 60 || v == hMax())) || (mode == 3 && (v == 8 || v == 70)) || (mode == 2 && v == 170)) Haptics.edge(getContext());
                        else Haptics.tick(getContext());
                    }
                    long now = System.currentTimeMillis();
                    if (now - lastApply > 90) {
                        lastApply = now;
                        prefs.edit().putInt(k, v).apply();
                        update();
                        render();
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    update();
                    render();
                    mode = 0;
                    return true;
            }
            return true;
        }
    }

    private volatile Object[] gPrayer;

    private class GaugeRow extends View {
        GaugeRow(Context c) {
            super(c);
            setLayerType(LAYER_TYPE_SOFTWARE, null);
        }

        @Override
        protected void onDraw(Canvas c) {
            try {
                drawRow(c);
            } catch (Throwable t) {
                CrashLog.write(getContext(), t);
            }
        }

        private void drawRow(Canvas c) {
            Context ctx = getContext();
            Draw3D.useWidgetTheme(ctx);
            int[] lc = new int[2];
            getLocationOnScreen(lc);
            Draw3D.backdrop = bd.sharp;
            Draw3D.backdropRect = new RectF(-lc[0], -lc[1], W - lc[0], H - lc[1]);
            float w = getWidth(), cx = w / 2f;
            float u = dp(1);
            // baris 1: acara seterusnya (data dari refreshGauges)
            String line = gLine;
            if (line == null) return;
            int col = gCol;
            boolean hasLine = !line.isEmpty();
            Object[] np = gPrayer;
            float cardH = 0;
            if (np != null) {
                // D · kad solat seterusnya dengan bar masa
                cardH = 66 * u;
                RectF cr = new RectF(cx - Math.min(w / 2f - 16 * u, 170 * u), 2 * u, cx + Math.min(w / 2f - 16 * u, 170 * u), 2 * u + cardH - 6 * u);
                android.graphics.Paint pp = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                pp.setColor(Color.argb(150, 8, 40, 34));
                c.drawRoundRect(cr, 20 * u, 20 * u, pp);
                pp.setStyle(android.graphics.Paint.Style.STROKE);
                pp.setStrokeWidth(1f * u);
                pp.setColor(Color.argb(60, 255, 255, 255));
                c.drawRoundRect(cr, 20 * u, 20 * u, pp);
                Draw3D.text(c, getString(R.string.next_prayer_cap, ((String) np[0]).toUpperCase(java.util.Locale.ROOT)),
                        cr.left + 16 * u, cr.top + 18 * u, 10.5f * u, 700, 0xFF9FE1CB, Draw3D.LEFT);
                Draw3D.text(c, (String) np[1], cr.left + 16 * u, cr.top + 44 * u, 22 * u, 800, Color.WHITE, Draw3D.LEFT);
                Draw3D.text(c, (String) np[2], cr.right - 16 * u, cr.top + 44 * u, 12 * u, 700, 0xFF5DCAA5, Draw3D.RIGHT);
                float bl = cr.left + 16 * u, br = cr.right - 16 * u, by = cr.bottom - 9 * u;
                android.graphics.Paint bp = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                bp.setStrokeCap(android.graphics.Paint.Cap.ROUND);
                bp.setStrokeWidth(3.5f * u);
                bp.setColor(Color.argb(70, 255, 255, 255));
                c.drawLine(bl, by, br, by, bp);
                bp.setColor(0xFF5DCAA5);
                c.drawLine(bl, by, bl + (br - bl) * (float) np[3], by, bp);
                hasLine = false; // kad solat menggantikan baris acara
            }
            if (hasLine) {
                line = Draw3D.fit(line, 16 * u, 700, w - 60 * u);
                float tw = Draw3D.measure(line, 16 * u, 700);
                Path bar = new Path();
                bar.addRoundRect(new RectF(cx - tw / 2f - 12 * u, 8 * u, cx - tw / 2f - 7 * u, 26 * u), 3 * u, 3 * u, Path.Direction.CW);
                Draw3D.shape(c, bar, 10 * u, col);
                Draw3D.text(c, line, cx + 2 * u, 23 * u, 16 * u, 700, Color.WHITE, Draw3D.CENTER);
            }

            // baris 2: cuaca, langkah, bateri — dialih ke tepi jika bertindih dengan orang dalam gambar
            float cy = (hasLine ? 82 : (cardH > 0 ? 40 : 50)) * u + cardH, gap = 96 * u;
            float xW = cx - gap, xS = cx, xB = cx + gap;
            boolean showW = true;
            RectF sb = subjScreen;
            if (sb != null && prefs.getBoolean(Prefs.LOCK_STACK, true)) {
                float sl = sb.left - lc[0], sr = sb.right - lc[0], rowTop = cy - 35 * u, rowBot = cy + 40 * u;
                boolean overlapY = sb.top - lc[1] < rowBot && sb.bottom - lc[1] > rowTop;
                boolean overlapX = sl < xB + 30 * u && sr > xW - 30 * u;
                if (overlapY && overlapX) {
                    java.util.List<Float> slots = new java.util.ArrayList<>();
                    for (float x = 38 * u; x + 32 * u <= sl; x += 70 * u) slots.add(x);
                    java.util.List<Float> right = new java.util.ArrayList<>();
                    for (float x = w - 38 * u; x - 32 * u >= sr; x -= 70 * u) right.add(0, x);
                    slots.addAll(right);
                    if (slots.size() >= 3) {
                        xW = slots.get(0);
                        xS = slots.get(slots.size() >= 4 || right.size() < 2 ? 1 : slots.size() - 2);
                        xB = slots.get(slots.size() - 1);
                        if (xS == xB) xS = slots.get(1);
                    } else if (slots.size() == 2) {
                        showW = false;
                        xS = slots.get(0);
                        xB = slots.get(1);
                    }
                }
            }
            Data.Weather wx = gWx;
            if (showW) {
                if (wx != null) {
                    Draw3D.weather(c, wx.code, wx.day, xW, cy - 12 * u, 40 * u);
                    Draw3D.text(c, Math.round(wx.temp) + "°", xW, cy + 34 * u, 17 * u, 700, Color.WHITE, Draw3D.CENTER);
                } else {
                    Draw3D.weather(c, 2, true, xW, cy - 12 * u, 40 * u);
                }
            }
            int steps = gSteps;
            int goal = Math.max(1000, prefs.getInt("stepGoal", 10000));
            Draw3D.ring(c, xS, cy, 27 * u, 6 * u, steps / (float) goal, 0xFF30D158, Color.argb(70, 48, 209, 88));
            String st = steps >= 10000 ? (steps / 1000) + "k" : String.valueOf(steps);
            Draw3D.text(c, st, xS, cy + 5 * u, 14 * u, 800, Color.WHITE, Draw3D.CENTER);
            int lvl = gLvl;
            boolean ch = gCharging;
            Draw3D.ring(c, xB, cy, 27 * u, 6 * u, lvl / 100f, ch ? 0xFF30D158 : (lvl <= 20 ? 0xFFFF453A : Color.WHITE),
                    Color.argb(70, 255, 255, 255));
            Draw3D.text(c, lvl + "%", xB, cy + 5 * u, 14 * u, 800, Color.WHITE, Draw3D.CENTER);
        }
    }

    private FrameLayout torchBtn;

    /**
     * Butang gaya iPhone: perlu ditekan lama (≈0.35 s). Membesar perlahan semasa ditekan,
     * bergetar dan "pop" bila aktif. Ketik biasa hanya tunjuk petunjuk.
     */
    private FrameLayout holdCircle(int icon, Runnable action) {
        final FrameLayout f = new FrameLayout(this);
        f.setBackground(GlassEngine.drawable(this, 27, true, 30));
        final Icon ic = new Icon(this, icon, Color.WHITE);
        f.addView(ic, new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
        final boolean[] fired = new boolean[1];
        final Runnable fire = () -> {
            fired[0] = true;
            f.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            f.animate().scaleX(1.32f).scaleY(1.32f).setInterpolator(Ui.SPRING).setDuration(Ui.SPRING_MS).start();
            action.run();
            if (icon == Icon.TORCH) h.postDelayed(this::paintTorch, 120);
        };
        f.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    fired[0] = false;
                    f.animate().scaleX(1.12f).scaleY(1.12f).setInterpolator(Ui.EASE).setDuration(350).start();
                    h.postDelayed(fire, 350);
                    return true;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    h.removeCallbacks(fire);
                    f.animate().scaleX(1f).scaleY(1f).setInterpolator(Ui.SPRING).setDuration(Ui.SPRING_MS).start();
                    if (!fired[0] && e.getActionMasked() == android.view.MotionEvent.ACTION_UP) {
                        f.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
                        if (hint != null) {
                            hint.setText(R.string.hold_hint);
                            hintRes = R.string.hold_hint;
                            hintHold = System.currentTimeMillis() + 1800;
                        }
                    }
                    return true;
            }
            return true;
        });
        return f;
    }

    /** Butang lampu suluh putih bila menyala (macam iPhone). */
    private void paintTorch() {
        if (torchBtn == null) return;
        torchBtn.setBackground(torchOn ? Ui.round(Color.WHITE, dp(27)) : GlassEngine.drawable(this, 27, true, 30));
        View ic = torchBtn.getChildAt(0);
        if (ic instanceof Icon) ((Icon) ic).setColor(torchOn ? 0xFF111114 : Color.WHITE);
    }

    private View circle(int icon, View.OnClickListener l) {
        FrameLayout f = new FrameLayout(this);
        f.setBackground(GlassEngine.drawable(this, 27, true, 30));
        f.addView(new Icon(this, icon, Color.WHITE), new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
        f.setOnClickListener(l);
        Ui.press(f);
        return f;
    }

    // ======================= Notifikasi =======================

    /** Kembangkan notifikasi (dari banner) dan skrol ke atas. */
    void expand(String key) {
        expanded.add(key);
        stackOpen = true;
        refreshNotifs();
        if (notifScroll != null) notifScroll.post(() -> notifScroll.smoothScrollTo(0, 0));
    }

    void refreshNotifs() {
        if (notifList == null) return;
        LiquidGlass.Backdrop prevBd = LiquidGlass.current;
        LiquidGlass.current = bd;
        try {
            buildNotifs();
        } catch (Throwable t) {
            CrashLog.write(this, t);
        } finally {
            LiquidGlass.current = prevBd;
        }
    }

    private void buildNotifs() {
        notifList.removeAllViews();
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
                || x.getPackageName().equals(getPackageName()));
        list.sort((a, b) -> Long.compare(b.getPostTime(), a.getPostTime()));
        boolean stack = prefs.getBoolean(Prefs.LOCK_STACK, true) && list.size() > 1 && !stackOpen;
        clearAll.setVisibility(list.isEmpty() || stack ? View.GONE : View.VISIBLE);
        fitNotifs();
        if (stack) {
            // tindanan gaya iOS: satu pil kecil supaya gambar kelihatan penuh
            boolean fresh = false;
            java.util.Set<String> nowKeys = new java.util.HashSet<>();
            for (StatusBarNotification x : list) {
                nowKeys.add(x.getKey());
                if (seenNotifs != null && !seenNotifs.contains(x.getKey())) fresh = true;
            }
            seenNotifs = nowKeys;
            View pill = stackPill(list);
            FrameLayout wrap = new FrameLayout(this);
            wrap.addView(pill, new FrameLayout.LayoutParams(-2, dp(44), Gravity.CENTER_HORIZONTAL));
            LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(-1, -2);
            wl.topMargin = dp(8);
            notifList.addView(wrap, wl);
            if (fresh) {
                pill.setScaleX(0.85f);
                pill.setScaleY(0.85f);
                pill.setTranslationY(-dp(14));
                pill.animate().scaleX(1f).scaleY(1f).translationY(0).setInterpolator(Ui.SPRING).setDuration(Ui.SPRING_MS).start();
                pill.postDelayed(() -> pill.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM), 90);
            }
            return;
        }
        boolean bumped = false;
        java.util.Set<String> now = new java.util.HashSet<>();
        for (StatusBarNotification sbn : list) {
            View card = card(sbn);
            if (card == null) continue;
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
            p.topMargin = dp(8);
            notifList.addView(card, p);
            now.add(sbn.getKey());
            if (seenNotifs != null && !seenNotifs.contains(sbn.getKey())) {
                // notifikasi baru: jatuh masuk dengan lantunan + getaran seiring
                card.setAlpha(0f);
                card.setTranslationY(-dp(26));
                card.setScaleX(0.92f);
                card.setScaleY(0.92f);
                card.animate().alpha(1f).translationY(0).scaleX(1f).scaleY(1f)
                        .setInterpolator(Ui.SPRING).setDuration(Ui.SPRING_MS).start();
                if (!bumped) {
                    bumped = true;
                    card.postDelayed(() -> card.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM), 90);
                }
            }
        }
        seenNotifs = now;
        if (stackOpen && prefs.getBoolean(Prefs.LOCK_STACK, true) && list.size() > 1) {
            TextView less = pill(getString(R.string.show_less), false);
            FrameLayout wrap = new FrameLayout(this);
            wrap.addView(less, new FrameLayout.LayoutParams(-2, dp(36), Gravity.CENTER_HORIZONTAL));
            less.setOnClickListener(v -> {
                stackOpen = false;
                refreshNotifs();
            });
            LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(-1, -2);
            wl.topMargin = dp(10);
            notifList.addView(wrap, wl);
        }
    }

    private boolean stackOpen;

    /** Pil "N notifikasi" dengan ikon app bertindih. */
    private View stackPill(List<StatusBarNotification> list) {
        LinearLayout p = new LinearLayout(this);
        p.setGravity(Gravity.CENTER_VERTICAL);
        p.setPadding(dp(10), 0, dp(18), 0);
        p.setBackground(GlassEngine.drawable(this, 22, true, 30));
        java.util.LinkedHashSet<String> pk = new java.util.LinkedHashSet<>();
        for (StatusBarNotification x : list) {
            pk.add(x.getPackageName());
            if (pk.size() >= 3) break;
        }
        int i = 0;
        for (String k : pk) {
            ImageView iv = new ImageView(this);
            try {
                iv.setImageDrawable(getPackageManager().getApplicationIcon(k));
            } catch (Exception e) {
                continue;
            }
            LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(dp(26), dp(26));
            if (i > 0) il.leftMargin = -dp(9);
            iv.setElevation(dp(3 - i));
            p.addView(iv, il);
            i++;
        }
        TextView t = Ui.line(this, 14, 650, Color.WHITE);
        t.setText(getString(R.string.stack_count, list.size()));
        t.setPadding(dp(10), 0, 0, 0);
        p.addView(t);
        p.setOnClickListener(v -> {
            stackOpen = true;
            refreshNotifs();
        });
        Ui.press(p);
        return p;
    }

    private java.util.Set<String> seenNotifs;

    private String appName(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            return pm.getApplicationLabel(ai).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    /** Teks penuh: mesej chat (MessagingStyle) atau teks besar. */
    private CharSequence fullText(Notification n) {
        Parcelable[] msgs = n.extras.getParcelableArray(Notification.EXTRA_MESSAGES);
        if (msgs != null && msgs.length > 0) {
            StringBuilder sb = new StringBuilder();
            int from = Math.max(0, msgs.length - 8);
            for (int i = from; i < msgs.length; i++) {
                if (!(msgs[i] instanceof Bundle)) continue;
                Bundle m = (Bundle) msgs[i];
                CharSequence sender = m.getCharSequence("sender");
                CharSequence text = m.getCharSequence("text");
                if (text == null) continue;
                if (sb.length() > 0) sb.append("\n");
                if (!TextUtils.isEmpty(sender)) sb.append(sender).append(": ");
                sb.append(text);
            }
            if (sb.length() > 0) return sb;
        }
        CharSequence big = n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
        if (!TextUtils.isEmpty(big)) return big;
        CharSequence[] lines = n.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
        if (lines != null && lines.length > 0) return TextUtils.join("\n", lines);
        return n.extras.getCharSequence(Notification.EXTRA_TEXT);
    }

    private Notification.Action replyAction(Notification n) {
        if (n.actions == null) return null;
        for (Notification.Action a : n.actions) {
            RemoteInput[] ri = a.getRemoteInputs();
            if (ri != null && ri.length > 0 && a.actionIntent != null) return a;
        }
        return null;
    }

    private View card(final StatusBarNotification sbn) {
        final Notification n = sbn.getNotification();
        final String key = sbn.getKey();
        CharSequence title = n.extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence text = n.extras.getCharSequence(Notification.EXTRA_TEXT);
        if (TextUtils.isEmpty(title) && TextUtils.isEmpty(text)) return null;
        boolean open = expanded.contains(key);

        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        // kaca bersih: tiada kanta/kabur yang menghasilkan bingkai segi empat di dalam kad
        c.setBackground(GlassEngine.drawable(this, 24, true, 30));
        c.setPadding(dp(14), dp(12), dp(14), dp(12));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        ImageView ic = new ImageView(this);
        try { ic.setImageDrawable(getPackageManager().getApplicationIcon(sbn.getPackageName())); } catch (Exception ignored) {}
        top.addView(ic, new LinearLayout.LayoutParams(dp(36), dp(36)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(12), 0, 0, 0);
        TextView app = Ui.line(this, 12, 600, Color.argb(200, 255, 255, 255));
        long age = System.currentTimeMillis() - sbn.getPostTime();
        app.setText(appName(sbn.getPackageName()) + " • " + (age < 60_000 ? getString(R.string.now)
                : DateUtils.getRelativeTimeSpanString(sbn.getPostTime(), System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE)));
        col.addView(app);
        if (!TextUtils.isEmpty(title)) {
            TextView t = Ui.line(this, 15, 650, Color.WHITE);
            t.setText(title);
            t.setPadding(0, dp(2), 0, 0);
            col.addView(t);
        }
        TextView body = Ui.text(this, 14, 400, Color.argb(230, 255, 255, 255));
        body.setText(open ? fullText(n) : text);
        body.setMaxLines(open ? 14 : 2);
        body.setEllipsize(TextUtils.TruncateAt.END);
        body.setLineSpacing(0, 1.12f);
        body.setPadding(0, dp(2), 0, 0);
        col.addView(body);
        top.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        Icon chev = new Icon(this, open ? Icon.CLOSE : Icon.NEXT, Color.argb(170, 255, 255, 255));
        top.addView(chev, new LinearLayout.LayoutParams(dp(14), dp(14)));
        c.addView(top);

        if (open) {
            final Notification.Action reply = replyAction(n);
            if (key.equals(replyKey) && reply != null) {
                LinearLayout rr = new LinearLayout(this);
                rr.setGravity(Gravity.CENTER_VERTICAL);
                rr.setPadding(0, dp(10), 0, 0);
                final EditText et = new EditText(this);
                et.setHint(R.string.reply_hint);
                et.setTextColor(Color.WHITE);
                et.setHintTextColor(Color.argb(150, 255, 255, 255));
                et.setTextSize(15);
                et.setBackground(GlassEngine.drawable(this, 20, true, 30));
                et.setPadding(dp(14), dp(10), dp(14), dp(10));
                et.setImeOptions(EditorInfo.IME_ACTION_SEND);
                et.setSingleLine(false);
                et.setMaxLines(4);
                rr.addView(et, new LinearLayout.LayoutParams(0, -2, 1f));
                TextView send = pill(getString(R.string.notif_send), true);
                send.setOnClickListener(v -> sendReply(reply, et.getText().toString()));
                LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, dp(40));
                sp.leftMargin = dp(8);
                rr.addView(send, sp);
                c.addView(rr);
                et.requestFocus();
                h.postDelayed(() -> {
                    android.view.inputmethod.InputMethodManager imm = getSystemService(android.view.inputmethod.InputMethodManager.class);
                    if (imm != null) imm.showSoftInput(et, 0);
                }, 150);
            } else {
                LinearLayout acts = new LinearLayout(this);
                acts.setPadding(0, dp(10), 0, 0);
                TextView o = pill(getString(R.string.notif_open), true);
                o.setOnClickListener(v -> openNotif(sbn));
                acts.addView(o, new LinearLayout.LayoutParams(-2, dp(36)));
                if (reply != null) {
                    TextView r = pill(getString(R.string.notif_reply), false);
                    r.setOnClickListener(v -> {
                        replyKey = key;
                        refreshNotifs();
                    });
                    LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-2, dp(36));
                    rp.leftMargin = dp(8);
                    acts.addView(r, rp);
                }
                if (n.actions != null) {
                    int extra = 0;
                    for (final Notification.Action a : n.actions) {
                        if (a == reply || a.actionIntent == null || TextUtils.isEmpty(a.title) || extra >= 2) continue;
                        if (a.getRemoteInputs() != null && a.getRemoteInputs().length > 0) continue;
                        TextView b = pill(a.title.toString(), false);
                        b.setOnClickListener(v -> {
                            send(a.actionIntent, null);
                            h.postDelayed(this::refreshNotifs, 400);
                        });
                        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-2, dp(36));
                        ap.leftMargin = dp(8);
                        acts.addView(b, ap);
                        extra++;
                    }
                }
                android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
                hs.setHorizontalScrollBarEnabled(false);
                hs.addView(acts);
                c.addView(hs);
            }
        }

        c.setOnClickListener(v -> {
            if (expanded.contains(key)) expanded.remove(key);
            else expanded.add(key);
            if (!expanded.contains(key) && key.equals(replyKey)) replyKey = null;
            refreshNotifs();
        });
        swipe(c, key);
        return c;
    }

    /** Kad notifikasi gaya lock screen untuk banner di atas (ketik = buka dalam senarai, leret atas = tutup). */
    View bannerCard(StatusBarNotification sbn, Runnable onTap, Runnable onDismiss) {
        View c = card(sbn);
        if (c == null) return null;
        final float[] y0 = new float[1];
        c.setOnClickListener(v -> onTap.run());
        c.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    y0[0] = e.getRawY();
                    v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(100).start();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    v.setTranslationY(Math.min(0, e.getRawY() - y0[0]));
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    float dy = e.getRawY() - y0[0];
                    v.animate().scaleX(1f).scaleY(1f).setInterpolator(Ui.SPRING).setDuration(Ui.SPRING_MS).start();
                    if (dy < -dp(28)) onDismiss.run();
                    else {
                        v.animate().translationY(0).setInterpolator(Ui.SPRING).setDuration(Ui.SPRING_MS).start();
                        if (e.getActionMasked() == MotionEvent.ACTION_UP && Math.abs(dy) < dp(10)) v.performClick();
                    }
                    return true;
                }
            }
            return true;
        });
        return c;
    }

    private TextView pill(String s, boolean primary) {
        TextView t = Ui.line(this, 13, 650, primary ? 0xFF0B0B10 : Color.WHITE);
        t.setText(s);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(16), 0, dp(16), 0);
        t.setBackground(primary ? Ui.round(Color.WHITE, dp(18)) : GlassEngine.drawable(this, 18, true, 30));
        Ui.press(t);
        return t;
    }

    private void openNotif(StatusBarNotification sbn) {
        final Notification n = sbn.getNotification();
        final String key = sbn.getKey();
        unlock(() -> {
            send(n.contentIntent, null);
            if ((n.flags & Notification.FLAG_AUTO_CANCEL) != 0 && NotifListener.inst != null) {
                try { NotifListener.inst.cancelNotification(key); } catch (Exception ignored) {}
            }
        });
    }

    private void sendReply(Notification.Action a, String text) {
        if (TextUtils.isEmpty(text.trim())) return;
        try {
            Intent i = new Intent();
            Bundle res = new Bundle();
            for (RemoteInput r : a.getRemoteInputs()) res.putCharSequence(r.getResultKey(), text);
            RemoteInput.addResultsToIntent(a.getRemoteInputs(), i, res);
            send(a.actionIntent, i);
            Toast.makeText(this, R.string.notif_sent, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, String.valueOf(e.getMessage()), Toast.LENGTH_SHORT).show();
        }
        replyKey = null;
        android.view.inputmethod.InputMethodManager imm = getSystemService(android.view.inputmethod.InputMethodManager.class);
        if (imm != null && getCurrentFocus() != null) imm.hideSoftInputFromWindow(getCurrentFocus().getWindowToken(), 0);
        h.postDelayed(this::refreshNotifs, 500);
    }

    private void swipe(final View v, final String key) {
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
                                        if (NotifListener.inst != null) {
                                            try { NotifListener.inst.cancelNotification(key); } catch (Exception ignored) {}
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

    // ======================= Muzik =======================

    void refreshMusic() {
        if (musicBox == null) return;
        LiquidGlass.Backdrop prevBd = LiquidGlass.current;
        LiquidGlass.current = bd;
        try {
            buildMusic();
        } catch (Throwable t) {
            CrashLog.write(this, t);
        } finally {
            LiquidGlass.current = prevBd;
        }
    }

    private void buildMusic() {
        musicBox.removeAllViews();
        PillService s = PillService.inst;
        if (s == null || s.mc == null || (s.title == null && !s.playing)) return;
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(GlassEngine.drawable(this, 28, true, 30));
        c.setPadding(dp(16), dp(14), dp(16), dp(8));
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        ImageView art = new ImageView(this);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setImageBitmap(s.art);
        art.setBackground(Ui.round(Color.argb(60, 255, 255, 255), dp(12)));
        art.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline o) {
                o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(12));
            }
        });
        art.setClipToOutline(true);
        top.addView(art, new LinearLayout.LayoutParams(dp(50), dp(50)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(12), 0, 0, 0);
        TextView t = Ui.line(this, 15, 600, Color.WHITE);
        t.setText(s.title != null ? s.title : getString(R.string.no_title));
        TextView a = Ui.line(this, 13, 400, Color.argb(200, 255, 255, 255));
        a.setText(s.artist != null ? s.artist : "");
        col.addView(t);
        col.addView(a);
        top.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        c.addView(top);
        LinearLayout ctr = new LinearLayout(this);
        ctr.setGravity(Gravity.CENTER);
        ctr.addView(btn(Icon.PREV, 24, v -> { if (s.mc != null) s.mc.getTransportControls().skipToPrevious(); }),
                new LinearLayout.LayoutParams(dp(70), dp(48)));
        ctr.addView(btn(s.playing ? Icon.PAUSE : Icon.PLAY, 30, v -> s.togglePlay()),
                new LinearLayout.LayoutParams(dp(80), dp(48)));
        ctr.addView(btn(Icon.NEXT, 24, v -> { if (s.mc != null) s.mc.getTransportControls().skipToNext(); }),
                new LinearLayout.LayoutParams(dp(70), dp(48)));
        c.addView(ctr, new LinearLayout.LayoutParams(-1, -2));
        musicBox.addView(c, new LinearLayout.LayoutParams(-1, -2));
    }

    private View btn(int kind, int size, View.OnClickListener l) {
        FrameLayout f = new FrameLayout(this);
        f.addView(new Icon(this, kind, Color.WHITE), new FrameLayout.LayoutParams(dp(size), dp(size), Gravity.CENTER));
        f.setOnClickListener(l);
        Ui.press(f);
        return f;
    }

    private void send(PendingIntent pi, Intent fill) {
        if (pi == null) return;
        try {
            ActivityOptions o = ActivityOptions.makeBasic();
            if (Build.VERSION.SDK_INT >= 34) {
                o.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
            }
            pi.send(this, 0, fill, null, null, null, o.toBundle());
        } catch (Exception ignored) {
        }
    }

    // ======================= Lampu suluh =======================

    private void watchTorch() {
        try {
            CameraManager cm = getSystemService(CameraManager.class);
            for (String id : cm.getCameraIdList()) {
                CameraCharacteristics ch = cm.getCameraCharacteristics(id);
                Boolean fl = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                Integer face = ch.get(CameraCharacteristics.LENS_FACING);
                if (Boolean.TRUE.equals(fl) && face != null && face == CameraCharacteristics.LENS_FACING_BACK) {
                    torchId = id;
                    break;
                }
            }
            torchCb = new CameraManager.TorchCallback() {
                @Override
                public void onTorchModeChanged(String cameraId, boolean enabled) {
                    if (cameraId.equals(torchId)) {
                        torchOn = enabled;
                        paintTorch();
                    }
                }
            };
            cm.registerTorchCallback(torchCb, h);
        } catch (Exception ignored) {
        }
    }

    private void toggleTorch() {
        if (torchId == null) return;
        try { getSystemService(CameraManager.class).setTorchMode(torchId, !torchOn); } catch (Exception ignored) {}
    }

    // ======================= Buka kunci =======================

    private void unlock(Runnable then) {
        if (leaving) return;
        afterUnlock = then;
        if (!km.isKeyguardLocked()) {
            done();
            return;
        }
        holdUntil = System.currentTimeMillis() + 90_000L; // bouncer PIN mungkin dipaparkan
        km.requestDismissKeyguard(this, new KeyguardManager.KeyguardDismissCallback() {
            @Override
            public void onDismissSucceeded() { done(); }

            @Override
            public void onDismissCancelled() { holdUntil = 0; springBack(); }

            @Override
            public void onDismissError() { holdUntil = 0; springBack(); }
        });
    }

    private void done() {
        leaving = true;
        final Runnable r = afterUnlock;
        afterUnlock = null;
        Runnable exit = () -> {
            if (!soundPlayed) {
                soundPlayed = true;
                Sounds.play(this, Sounds.UNLOCK);
                Haptics.thud(this);
            }
            if (lastPlain != null && prefs.getInt(Prefs.POP_STYLE, 0) != 0) {
                revealExit(r);
                return;
            }
            unlockSpring.stop();
            applyUnlock(Math.max(uProg, 0.6f), Math.min(uDy, -H * 0.2f));
            ui.animate().translationY(-H * 0.3f).alpha(0f).setInterpolator(Ui.EXIT).setDuration(170).start();
            wallBlur.setVisibility(View.VISIBLE);
            wallBlur.animate().scaleX(1.24f).scaleY(1.24f).alpha(0f).setInterpolator(Ui.EASE).setDuration(240).start();
            wall.animate().scaleX(1.24f).scaleY(1.24f).alpha(0f).setInterpolator(Ui.EASE).setDuration(240).withEndAction(() -> {
                if (r != null) r.run();
                // simpan aktiviti di belakang supaya dibuka semula serta-merta (tiada skrin hitam)
                boolean moved = false;
                try { moved = moveTaskToBack(true); } catch (Throwable ignored) {}
                if (!moved) finish();
                overridePendingTransition(0, 0);
            }).start();
        };
        if (lockAnim.state() == UnlockAnim.LOCKED) {
            // dibuka melalui bouncer (cap jari / PIN): main animasi cap jari dulu
            ui.animate().translationY(0).alpha(1f).setDuration(150).start();
            lockAnim.play(UnlockAnim.FINGER, () -> h.postDelayed(exit, 250));
        } else {
            exit.run();
        }
    }

    // ======================= Kesan zoom wallpaper ikut jari (gaya iPhone) =======================

    private float uProg, uDy;
    private float lastBlur = -1;
    private final Spring unlockSpring = Spring.panel((v, vel) -> applyUnlock(v, v * -H * 0.45f)).range(1f);

    /** p 0..1: 0 = lock screen biasa, 1 = wallpaper zoom penuh & kabur, UI hilang ke atas. */
    private void applyUnlock(float p, float dy) {
        uProg = p;
        float q = Math.max(0f, Math.min(1.2f, p));
        boolean zoom = prefs == null || prefs.getBoolean(Prefs.LOCK_ZOOM, true);
        float sc = 1f + (zoom ? 0.16f : 0.04f) * q;
        wall.setScaleX(sc);
        wall.setScaleY(sc);
        wall.setTranslationY(0);
        // kabur murah: pudar masuk salinan kabur yang sudah dikira (tiada kabur GPU penuh setiap bingkai)
        float ba = zoom ? Math.min(1f, q * 1.5f) : 0f;
        wallBlur.setScaleX(sc);
        wallBlur.setScaleY(sc);
        wallBlur.setAlpha(ba);
        wallBlur.setVisibility(ba > 0.003f ? View.VISIBLE : View.INVISIBLE);
        // UI dilukis sebagai satu lapisan GPU semasa bergerak (alpha/translate murah)
        int lt = q > 0.001f ? View.LAYER_TYPE_HARDWARE : View.LAYER_TYPE_NONE;
        if (ui.getLayerType() != lt) ui.setLayerType(lt, null);
        ui.setTranslationY(dy);
        ui.setAlpha(Math.max(0f, 1f - q * 1.35f));
        float us = 1f - 0.05f * Math.min(1f, q);
        ui.setScaleX(us);
        ui.setScaleY(us);
    }

    /**
     * Gaya timbul → gambar asal: wallpaper kembali ke saiz & ketajaman asal, gambar asal "mekar"
     * dari bingkai ke skrin penuh, UI terbang ke atas; kemudian home (wallpaper sama) dipaparkan.
     */
    private void revealExit(Runnable after) {
        unlockSpring.stop();
        ui.animate().translationY(-H * 0.3f).alpha(0f).setInterpolator(Ui.EXIT).setDuration(220).start();
        reveal.bmp = lastPlain;
        if (lastPopFrame != null) reveal.from.set(lastPopFrame);
        else reveal.from.set(0, 0, W, H);
        reveal.radius = dp(28);
        reveal.setAlpha(lastPopFrame != null ? 1f : 0f);
        reveal.set(0f);
        reveal.setVisibility(View.VISIBLE);
        final float p0 = uProg;
        android.animation.ValueAnimator va = android.animation.ValueAnimator.ofFloat(0f, 1f);
        va.setDuration(520);
        va.setInterpolator(Ui.EASE);
        va.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            // kembalikan zoom & kabur ke 0 (gambar tajam seperti di home)
            float q = p0 * (1f - f);
            float sc = 1f + 0.16f * q;
            wall.setScaleX(sc);
            wall.setScaleY(sc);
            wallBlur.setScaleX(sc);
            wallBlur.setScaleY(sc);
            wallBlur.setAlpha(Math.min(1f, q * 1.5f));
            reveal.setScaleX(sc);
            reveal.setScaleY(sc);
            reveal.set(f);
            if (lastPopFrame == null) reveal.setAlpha(f);
        });
        va.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator a) {
                if (after != null) after.run();
                boolean moved = false;
                try { moved = moveTaskToBack(true); } catch (Throwable ignored) {}
                if (!moved) finish();
                overridePendingTransition(0, 0);
            }
        });
        va.start();
    }

    private void springBack() {
        afterUnlock = null;
        ui.animate().cancel();
        wall.animate().cancel();
        wall.setAlpha(1f);
        unlockSpring.value = uProg;
        unlockSpring.to(0f, 0f, null);
    }

    private boolean inside(View v, MotionEvent e) {
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        return e.getRawX() >= loc[0] && e.getRawX() <= loc[0] + v.getWidth()
                && e.getRawY() >= loc[1] && e.getRawY() <= loc[1] + v.getHeight();
    }

    /** Akar yang mengesan leretan ke atas di mana-mana. */
    private class Root extends FrameLayout {
        private float y0, x0;
        private boolean dragging, fromList;
        private android.view.VelocityTracker vt;

        Root(Context c) { super(c); }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent e) {
            if (clockEdit != null) return false; // mod sunting: bingkai sunting yang pegang sentuhan
            // tekan lama pada jam: mod sunting saiz (gaya iPhone)
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    removeCallbacks(clockHold);
                    if (clockEdit == null && clockHit(e.getRawX(), e.getRawY())) postDelayed(clockHold, 520);
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (Math.hypot(e.getRawX() - x0, e.getRawY() - y0) > dp(10)) removeCallbacks(clockHold);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    removeCallbacks(clockHold);
                    break;
            }
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    y0 = e.getRawY();
                    x0 = e.getRawX();
                    dragging = false;
                    fromList = inside(notifScroll, e) && notifScroll.canScrollVertically(1);
                    if (e.getRawY() > H * 0.76f && Math.abs(e.getRawX() - W / 2f) < W * 0.14f) {
                        lastSensorTouch = System.currentTimeMillis();
                    }
                    break;
                case MotionEvent.ACTION_MOVE:
                    float dy = e.getRawY() - y0;
                    if (!fromList && replyKey == null && dy < -dp(18) && Math.abs(dy) > Math.abs(e.getRawX() - x0)) {
                        dragging = true;
                        return true;
                    }
                    break;
            }
            return false;
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (vt == null) vt = android.view.VelocityTracker.obtain();
            MotionEvent me = MotionEvent.obtain(e);
            me.setLocation(e.getRawX(), e.getRawY());
            vt.addMovement(me);
            me.recycle();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    unlockSpring.stop();
                    y0 = e.getRawY();
                    if (e.getRawY() > H * 0.76f && Math.abs(e.getRawX() - W / 2f) < W * 0.14f) {
                        lastSensorTouch = System.currentTimeMillis();
                    }
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dy = Math.min(0, e.getRawY() - y0);
                    if (!dragging) unlockSpring.stop();
                    dragging = true;
                    uDy = dy;
                    // wallpaper zoom & kabur ikut jari; UI naik dan pudar
                    applyUnlock(-dy / (H * 0.45f), dy);
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    float dy = e.getRawY() - y0;
                    vt.computeCurrentVelocity(1000);
                    float vy = vt.getYVelocity();
                    vt.recycle();
                    vt = null;
                    boolean go = dragging && e.getActionMasked() == MotionEvent.ACTION_UP
                            && (dy < -dp(120) || (vy < -dp(900) && dy < -dp(40)));
                    if (go) unlock(null);
                    else springBack();
                    dragging = false;
                    return true;
                }
            }
            return true;
        }
    }
}
