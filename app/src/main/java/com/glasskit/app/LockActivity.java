package com.glasskit.app;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
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
import android.provider.MediaStore;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Lock screen GlassKit: dipaparkan di atas kunci sistem (showWhenLocked).
 * Membuka kunci tetap melalui sistem (wajah / cap jari / PIN) dengan requestDismissKeyguard.
 */
public class LockActivity extends Activity {
    static LockActivity inst;

    private final Handler h = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private KeyguardManager km;
    private int W, H, statusH;

    private ImageView wall;
    private FrameLayout ui;
    private GaugeRow gauges;
    private LinearLayout bottom, notifList, musicBox;
    private Icon lockIcon;
    private TextView hint;
    private Bitmap frame, bg, fg;
    private long imgVer = Long.MIN_VALUE;

    private String torchId;
    private boolean torchOn;
    private CameraManager.TorchCallback torchCb;
    private Runnable afterUnlock;
    private boolean leaving;

    private final BroadcastReceiver rx = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            render();
            if (gauges != null) gauges.invalidate();
        }
    };

    private final Runnable lockPoll = new Runnable() {
        @Override
        public void run() {
            boolean locked = km.isDeviceLocked();
            lockIcon.setColor(locked ? Color.WHITE : 0xFF30D158);
            hint.setText(locked ? R.string.swipe_open : R.string.unlocked_swipe);
            h.postDelayed(this, 600);
        }
    };

    private int dp(float v) { return Ui.dp(this, v); }

    // ======================= Kitaran hidup =======================

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        inst = this;
        prefs = Prefs.get(this);
        km = getSystemService(KeyguardManager.class);
        setShowWhenLocked(true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        getWindow().getAttributes().layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        getWindow().setDecorFitsSystemWindows(false);

        Rect bounds = getSystemService(WindowManager.class).getCurrentWindowMetrics().getBounds();
        W = bounds.width();
        H = bounds.height();
        statusH = getSystemService(WindowManager.class).getCurrentWindowMetrics().getWindowInsets()
                .getInsets(WindowInsets.Type.statusBars()).top;
        if (statusH <= 0) statusH = dp(32);

        Root root = new Root(this);
        root.setBackgroundColor(Color.BLACK);
        wall = new ImageView(this);
        wall.setScaleType(ImageView.ScaleType.FIT_XY);
        root.addView(wall, new FrameLayout.LayoutParams(-1, -1));

        ui = new FrameLayout(this);
        root.addView(ui, new FrameLayout.LayoutParams(-1, -1));

        lockIcon = new Icon(this, Icon.LOCK, Color.WHITE);
        FrameLayout.LayoutParams ll = new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        ll.topMargin = statusH + dp(14);
        ui.addView(lockIcon, ll);

        gauges = new GaugeRow(this);
        ui.addView(gauges, new FrameLayout.LayoutParams(-1, dp(130)));

        bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(dp(14), 0, dp(14), dp(22));
        musicBox = new LinearLayout(this);
        musicBox.setOrientation(LinearLayout.VERTICAL);
        bottom.addView(musicBox, new LinearLayout.LayoutParams(-1, -2));
        notifList = new LinearLayout(this);
        notifList.setOrientation(LinearLayout.VERTICAL);
        bottom.addView(notifList, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(circle(Icon.TORCH, v -> toggleTorch()), new LinearLayout.LayoutParams(dp(54), dp(54)));
        hint = Ui.text(this, 14, 600, Color.WHITE);
        hint.setGravity(Gravity.CENTER);
        hint.setShadowLayer(dp(4), 0, dp(1), Color.argb(130, 0, 0, 0));
        bar.addView(hint, new LinearLayout.LayoutParams(0, -2, 1f));
        bar.addView(circle(Icon.CAMERA, v -> {
            Intent i = new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { startActivity(i); } catch (Exception ignored) {}
        }), new LinearLayout.LayoutParams(dp(54), dp(54)));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, -2);
        bl.topMargin = dp(16);
        bottom.addView(bar, bl);
        ui.addView(bottom, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        setContentView(root);
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
        hideBars();
        resetUi();
        h.post(lockPoll);
    }

    @Override
    protected void onPause() {
        super.onPause();
        h.removeCallbacks(lockPoll);
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
        // sengaja tiada tindakan: lock screen tidak boleh ditutup dengan butang kembali
    }

    private void hideBars() {
        WindowInsetsController c = getWindow().getInsetsController();
        if (c != null) {
            c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
            c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    }

    private void resetUi() {
        leaving = false;
        afterUnlock = null;
        ui.setTranslationY(0);
        ui.setAlpha(1f);
        wall.setTranslationY(0);
        wall.setScaleX(1f);
        wall.setScaleY(1f);
        render();
        refreshNotifs();
        refreshMusic();
    }

    // ======================= Lukisan =======================

    void render() {
        long v = prefs.getLong(Prefs.IMG_VER, 0);
        if (v != imgVer) {
            imgVer = v;
            bg = DepthRenderer.load(DepthRenderer.bgFile(this));
            fg = DepthRenderer.load(DepthRenderer.fgFile(this));
        }
        try {
            if (frame == null) frame = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
            DepthRenderer.draw(this, new Canvas(frame), W, H, bg, fg, prefs, true);
            wall.setImageBitmap(frame);
            wall.invalidate();
        } catch (Throwable ignored) {
        }
        // letak tolok di bawah jam
        Path p = DepthRenderer.clockPath(this, prefs, W, H);
        RectF r = new RectF();
        p.computeBounds(r, true);
        float scale = (float) getResources().getDisplayMetrics().widthPixels / W;
        FrameLayout.LayoutParams gl = (FrameLayout.LayoutParams) gauges.getLayoutParams();
        gl.topMargin = Math.round(r.bottom * scale) + dp(16);
        gauges.setLayoutParams(gl);
        gauges.invalidate();
    }

    /** Barisan tolok kaca bulat. */
    private class GaugeRow extends View {
        GaugeRow(Context c) {
            super(c);
            setLayerType(LAYER_TYPE_SOFTWARE, null);
        }

        @Override
        protected void onDraw(Canvas c) {
            Context ctx = getContext();
            float w = getWidth(), cx = w / 2f;
            float u = dp(1);
            // baris 1: acara seterusnya
            List<Data.Event> ev = Data.events(ctx, 2, 1);
            String line;
            int col = Ui.accent(ctx, false);
            if (ev.isEmpty()) {
                line = Data.dayLabel(ctx, System.currentTimeMillis()) + " · " + getString(R.string.no_events);
            } else {
                Data.Event e = ev.get(0);
                col = e.color;
                line = (e.allDay ? getString(R.string.all_day) : Data.eventTime(ctx, e).split(" – ")[0]) + "  " + e.title;
            }
            line = Draw3D.fit(line, 16 * u, 700, w - 60 * u);
            float tw = Draw3D.measure(line, 16 * u, 700);
            Path bar = new Path();
            bar.addRoundRect(new RectF(cx - tw / 2f - 12 * u, 8 * u, cx - tw / 2f - 7 * u, 26 * u), 3 * u, 3 * u, Path.Direction.CW);
            Draw3D.shape(c, bar, 10 * u, col);
            Draw3D.text(c, line, cx + 2 * u, 23 * u, 16 * u, 700, Color.WHITE, Draw3D.CENTER);

            // baris 2: cuaca, langkah, bateri
            float cy = 82 * u, gap = 96 * u;
            Data.Weather wx = Data.weather(ctx);
            if (wx != null) {
                Draw3D.weather(c, wx.code, wx.day, cx - gap, cy - 12 * u, 40 * u);
                Draw3D.text(c, Math.round(wx.temp) + "°", cx - gap, cy + 34 * u, 17 * u, 700, Color.WHITE, Draw3D.CENTER);
            } else {
                Draw3D.weather(c, 2, true, cx - gap, cy - 12 * u, 40 * u);
            }
            int steps = Data.steps(ctx);
            int goal = Math.max(1000, prefs.getInt("stepGoal", 10000));
            Draw3D.ring(c, cx, cy, 27 * u, 6 * u, steps / (float) goal, 0xFF30D158, Color.argb(70, 48, 209, 88));
            String st = steps >= 10000 ? (steps / 1000) + "k" : String.valueOf(steps);
            Draw3D.text(c, st, cx, cy + 5 * u, 14 * u, 800, Color.WHITE, Draw3D.CENTER);
            int lvl = Math.max(0, Ui.battery(ctx));
            boolean ch = Ui.charging(ctx);
            Draw3D.ring(c, cx + gap, cy, 27 * u, 6 * u, lvl / 100f, ch ? 0xFF30D158 : (lvl <= 20 ? 0xFFFF453A : Color.WHITE),
                    Color.argb(70, 255, 255, 255));
            Draw3D.text(c, lvl + "%", cx + gap, cy + 5 * u, 14 * u, 800, Color.WHITE, Draw3D.CENTER);
        }
    }

    private View circle(int icon, View.OnClickListener l) {
        FrameLayout f = new FrameLayout(this);
        f.setBackground(Ui.glassStyled(this, 27, true));
        f.addView(new Icon(this, icon, Color.WHITE), new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
        f.setOnClickListener(l);
        Ui.press(f);
        return f;
    }

    // ======================= Notifikasi & muzik =======================

    void refreshNotifs() {
        if (notifList == null) return;
        notifList.removeAllViews();
        NotifListener l = NotifListener.inst;
        if (l == null) return;
        List<StatusBarNotification> list = new ArrayList<>();
        try {
            StatusBarNotification[] arr = l.getActiveNotifications();
            if (arr != null) list.addAll(Arrays.asList(arr));
        } catch (Exception ignored) {
        }
        list.removeIf(x -> x.isOngoing()
                || (x.getNotification().flags & Notification.FLAG_GROUP_SUMMARY) != 0
                || x.getPackageName().equals(getPackageName()));
        list.sort((a, b) -> Long.compare(b.getPostTime(), a.getPostTime()));
        int shown = 0;
        for (StatusBarNotification sbn : list) {
            if (shown >= 3) break;
            View card = card(sbn);
            if (card == null) continue;
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
            p.topMargin = dp(8);
            notifList.addView(card, p);
            shown++;
        }
        if (list.size() > shown && shown > 0) {
            TextView more = Ui.text(this, 13, 600, Color.WHITE);
            more.setText(getString(R.string.more_notifs, list.size() - shown));
            more.setGravity(Gravity.CENTER);
            more.setPadding(0, dp(8), 0, 0);
            more.setShadowLayer(dp(3), 0, dp(1), Color.argb(130, 0, 0, 0));
            notifList.addView(more, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    private View card(StatusBarNotification sbn) {
        final Notification n = sbn.getNotification();
        CharSequence title = n.extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence text = n.extras.getCharSequence(Notification.EXTRA_TEXT);
        if (TextUtils.isEmpty(title) && TextUtils.isEmpty(text)) return null;
        LinearLayout c = new LinearLayout(this);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setBackground(Ui.glassStyled(this, 24, prefs.getBoolean(Prefs.GLASS_DARK, false)));
        c.setPadding(dp(14), dp(12), dp(16), dp(12));
        ImageView ic = new ImageView(this);
        try { ic.setImageDrawable(getPackageManager().getApplicationIcon(sbn.getPackageName())); } catch (Exception ignored) {}
        c.addView(ic, new LinearLayout.LayoutParams(dp(34), dp(34)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(12), 0, 0, 0);
        if (!TextUtils.isEmpty(title)) {
            TextView t = Ui.line(this, 15, 600, Color.WHITE);
            t.setText(title);
            col.addView(t);
        }
        if (!TextUtils.isEmpty(text)) {
            TextView t = Ui.text(this, 14, 400, Color.argb(225, 255, 255, 255));
            t.setText(text);
            t.setMaxLines(2);
            t.setEllipsize(TextUtils.TruncateAt.END);
            t.setPadding(0, dp(2), 0, 0);
            col.addView(t);
        }
        c.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        c.setOnClickListener(v -> unlock(() -> send(n.contentIntent)));
        Ui.press(c);
        return c;
    }

    void refreshMusic() {
        if (musicBox == null) return;
        musicBox.removeAllViews();
        PillService s = PillService.inst;
        if (s == null || s.mc == null || (s.title == null && !s.playing)) return;
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(Ui.glassStyled(this, 28, prefs.getBoolean(Prefs.GLASS_DARK, false)));
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

    private void send(PendingIntent pi) {
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
                    if (cameraId.equals(torchId)) torchOn = enabled;
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
        km.requestDismissKeyguard(this, new KeyguardManager.KeyguardDismissCallback() {
            @Override
            public void onDismissSucceeded() { done(); }

            @Override
            public void onDismissCancelled() { springBack(); }

            @Override
            public void onDismissError() { springBack(); }
        });
    }

    private void done() {
        leaving = true;
        final Runnable r = afterUnlock;
        afterUnlock = null;
        ui.animate().translationY(-H * 0.12f).alpha(0f).setDuration(240).start();
        wall.animate().scaleX(1.08f).scaleY(1.08f).alpha(0f).setDuration(260).withEndAction(() -> {
            if (r != null) r.run();
            finish();
            overridePendingTransition(0, 0);
        }).start();
    }

    private void springBack() {
        afterUnlock = null;
        ui.animate().translationY(0).alpha(1f).setInterpolator(Ui.SPRING).setDuration(500).start();
        wall.animate().translationY(0).setDuration(300).start();
    }

    /** Akar yang mengesan leretan ke atas di mana-mana. */
    private class Root extends FrameLayout {
        private float y0, x0;
        private boolean dragging;

        Root(Context c) { super(c); }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    y0 = e.getRawY();
                    x0 = e.getRawX();
                    dragging = false;
                    break;
                case MotionEvent.ACTION_MOVE:
                    float dy = e.getRawY() - y0;
                    if (dy < -dp(18) && Math.abs(dy) > Math.abs(e.getRawX() - x0)) {
                        dragging = true;
                        return true;
                    }
                    break;
            }
            return false;
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    y0 = e.getRawY();
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dy = Math.min(0, e.getRawY() - y0);
                    dragging = true;
                    ui.setTranslationY(dy);
                    ui.setAlpha(Math.max(0.2f, 1f + dy / (H * 0.5f)));
                    wall.setTranslationY(dy * 0.15f);
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    float dy = e.getRawY() - y0;
                    if (dragging && dy < -dp(120)) unlock(null);
                    else springBack();
                    dragging = false;
                    return true;
                }
            }
            return true;
        }
    }
}
