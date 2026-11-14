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

    private static final int IDLE = 0, MUSIC = 1, NOTI = 2, REC = 3, CALL = 4, LIVE = 5, PRIV = 6;

    final Handler h = new Handler(Looper.getMainLooper());
    WindowManager wm;
    SharedPreferences prefs;
    Panels panels;

    private WindowManager.LayoutParams lp;
    private FrameLayout root, pill;
    private GradientDrawable pillBg;
    private FrameLayout compact;
    private LinearLayout expMusic, expNoti, expCall, nActions;
    // ---- panggilan dalam island ----
    private String callKey;
    private boolean callRinging;
    private long callStart;
    private CharSequence callName;
    private String callLabel;
    private PendingIntent callAnswer, callDecline, callHang;
    private ImageView kAvatar;
    private TextView kLabel, kName;
    private Widgets.Wave kWave;
    private FrameLayout kDecline, kAccept;
    private final Runnable callTick = new Runnable() {
        @Override
        public void run() {
            if (callKey == null || callRinging) return;
            String t = Ui.mmss(System.currentTimeMillis() - callStart);
            if (mode == CALL) {
                cText.setText(t);
                kLabel.setText(t);
            }
            h.postDelayed(this, 1000);
        }
    };
    private final Runnable callCollapse = new Runnable() {
        @Override
        public void run() {
            if (mode == CALL && !callRinging && expanded) {
                expanded = false;
                refresh();
            }
        }
    };
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
    private int lastNight = -1;
    IslandExtras extras;
    AppTools.ProfileRunner profiles;
    private View awakeView;
    private boolean awakeTile;
    private long lastHot;
    private long rx0 = -1, t0;

    /** Skrin kekal hidup (tile atau tetapan ikut app). */
    void setKeepAwake(boolean on) {
        awakeTile = on;
        applyAwake();
    }

    boolean keepAwake() { return awakeTile; }

    void onProfileChanged() { applyAwake(); }

    private void applyAwake() {
        boolean want = awakeTile || (profiles != null && profiles.keepAwake());
        if (want && awakeView == null) {
            awakeView = new View(this);
            awakeView.setKeepScreenOn(true);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(1, 1,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                    android.graphics.PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            try { wm.addView(awakeView, lp); } catch (Exception e) { awakeView = null; }
        } else if (!want && awakeView != null) {
            try { wm.removeViewImmediate(awakeView); } catch (Exception ignored) {}
            awakeView = null;
        }
    }

    /** Meter kelajuan internet setiap 2 saat (hanya bila skrin hidup & dihidupkan). */
    private final Runnable netTick = new Runnable() {
        @Override
        public void run() {
            h.removeCallbacks(this);
            if (prefs == null || !prefs.getBoolean(Prefs.NET_SPEED, false) || sbar == null) {
                if (sbar != null) sbar.setSpeed(null);
                rx0 = -1;
                return;
            }
            long rx = android.net.TrafficStats.getTotalRxBytes(), now = SystemClock.elapsedRealtime();
            if (rx0 >= 0 && now > t0) {
                double bps = (rx - rx0) * 1000.0 / (now - t0);
                String txt = null;
                if (bps > 40 * 1024) txt = bps > 1024 * 1024 ? String.format(java.util.Locale.US, "↓%.1fM", bps / 1048576.0)
                        : String.format(java.util.Locale.US, "↓%dK", Math.round(bps / 1024));
                sbar.setSpeed(txt);
            }
            rx0 = rx;
            t0 = now;
            PowerManager pm = getSystemService(PowerManager.class);
            if (pm != null && pm.isInteractive()) h.postDelayed(this, 2000);
        }
    };

    /** Amaran suhu bateri. */
    private void checkTemp(Intent i) {
        if (prefs == null || !prefs.getBoolean(Prefs.TEMP_WARN, true)) return;
        float t = i.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0) / 10f;
        if (t < 43f || System.currentTimeMillis() - lastHot < 20 * 60_000L) return;
        lastHot = System.currentTimeMillis();
        Drawable ic = Ui.iconDrawable(this, com.glasskit.app.Icon.BATTERY, Color.WHITE, t >= 46 ? 0xFFFF3B30 : 0xFFFF9F0A, dp(42));
        showIsland(ic, ic, getString(R.string.hot_title, Math.round(t)), getString(R.string.hot_body), null);
    }
    private View privDot;

    /** Tunjuk mesej dalam island (dipanggil oleh IslandExtras). */
    void showIsland(Drawable small, Drawable big, CharSequence t, CharSequence body, PendingIntent pi) {
        if (prefs == null || !prefs.getBoolean(Prefs.PILL_ON, true) || islandCallActive()) return;
        show(small, big, t, body, pi);
    }

    /** Goyang ikon loceng (mod bunyi). */
    void shakeIslandIcon(boolean ring) {
        for (View v : new View[]{cAppIcon, nIcon}) {
            if (v == null) continue;
            v.animate().cancel();
            v.setRotation(0);
            android.animation.ObjectAnimator a = android.animation.ObjectAnimator.ofFloat(v, View.ROTATION,
                    0, ring ? 22 : 12, ring ? -20 : -10, ring ? 16 : 6, ring ? -12 : -4, 6, 0);
            a.setDuration(ring ? 900 : 600);
            a.start();
        }
    }

    /** Titik privasi: hijau = kamera, oren = mikrofon. */
    private int lastPriv = -1;

    void onPrivacy() {
        if (sbar != null) sbar.setSolid(Recorder.recording() || (extras != null && extras.busy()));
        if (privDot == null || extras == null) return;
        int k = extras.privacy();
        if (k != lastPriv) {
            lastPriv = k;
            h.post(this::refresh);
        }
        privDot.setVisibility(k == 0 || expanded ? View.GONE : View.VISIBLE);
        if (k != 0) privDot.setBackground(Ui.circle(k == 1 ? 0xFF30D158 : 0xFFFF9F0A));
    }

    CallOverlay call;
    VolumePanel volume;
    private boolean volRepeatUp;
    private int volRepeatN;
    private final Runnable volRepeat = new Runnable() {
        @Override
        public void run() {
            if (volume == null || !volume.onKey(volRepeatUp)) return;
            volRepeatN++;
            h.postDelayed(this, volRepeatN < 4 ? 140 : 70); // makin lama makin laju
        }
    };
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
                    if (lockCover != null) lockCover.onScreenOn();
                    h.post(netTick);
                    if (patch != null) patch.screenOn();
                    if (prefs.getBoolean(Prefs.LOCK_ON, false)) {
                        // cuba serta-merta, kemudian ulang jika sistem lambat (elak lock screen stock terpapar)
                        h.post(ensureLock);
                        lockMiss = 0;
                        h.postDelayed(lockWatch, 250);
                    }
                    updateStatusBar();
                    break;
                case Intent.ACTION_SCREEN_OFF:
                    AppTools.rehideAll(PillService.this);
                    h.removeCallbacks(netTick);
                    if (patch != null) patch.hide(false);
                    if (panels != null) panels.close();
                    if (prefs.getBoolean(Prefs.LOCK_ON, false) && !islandCallActive()) {
                        if (lockCover == null) lockCover = new LockCover(PillService.this);
                        lockCover.arm();
                        h.removeCallbacks(ensureLock);
                        h.removeCallbacks(lockWatch);
                        LockActivity.holdUntil = 0;
                        h.post(PillService.this::showLock);
                        h.postDelayed(ensureLock, 450);
                    }
                    break;
                case Intent.ACTION_USER_PRESENT:
                    h.removeCallbacks(lockWatch);
                    if (lockCover != null) lockCover.hide();
                    if (patch != null) patch.hide(true);
                    hideBanner(false);
                    h.postDelayed(PillService.this::updateStatusBar, 300);
                    if (!prefs.getBoolean(Prefs.LOCK_ON, false)) Sounds.play(PillService.this, Sounds.UNLOCK);
                    break;
                case android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED:
                case "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED":
                    if (extras != null) extras.onBluetooth(i);
                    if (android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED.equals(i.getAction())) {
                        try {
                            android.bluetooth.BluetoothDevice bd = i.getParcelableExtra(android.bluetooth.BluetoothDevice.EXTRA_DEVICE,
                                    android.bluetooth.BluetoothDevice.class);
                            String nm = bd == null ? "" : (bd.getAlias() != null ? bd.getAlias() : bd.getName());
                            Automation.fire(PillService.this, Automation.T_BT, nm == null ? "" : nm);
                        } catch (SecurityException ignored) {
                        }
                    }
                    break;
                case android.media.AudioManager.RINGER_MODE_CHANGED_ACTION:
                    if (extras != null) extras.onRinger(i);
                    break;
                case Intent.ACTION_TIME_TICK:
                    prayerTick();
                    Automation.tick(PillService.this);
                    if (patch != null) patch.tick();
                    if (sbar != null && sbarShown) sbar.refresh();
                    GlassWidget.tick(PillService.this);
                    break;
                case Intent.ACTION_BATTERY_CHANGED: {
                    int lv = i.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1);
                    int pl = i.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0);
                    checkTemp(i);
                    if (lv != lastBat) Automation.fire(PillService.this, Automation.T_BATT, String.valueOf(lv));
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
                case Intent.ACTION_POWER_DISCONNECTED:
                    Automation.fire(PillService.this, Automation.T_UNPLUG, "");
                    if (chargeAnim != null) chargeAnim.hide();
                    if (sbar != null && sbarShown) sbar.refresh();
                    GlassWidget.updateAll(PillService.this, BatteryWidget.class);
                    break;
                case Intent.ACTION_POWER_CONNECTED:
                    Automation.fire(PillService.this, Automation.T_CHARGE, "");
                    if (sbar != null && sbarShown) sbar.refresh();
                    Sounds.play(PillService.this, Sounds.CHARGE);
                    h.postDelayed(PillService.this::showCharging, 600);
                    if (prefs.getBoolean(Prefs.NIGHTSTAND, false)) {
                        KeyguardManager kg = getSystemService(KeyguardManager.class);
                        if (kg != null && kg.isKeyguardLocked()) h.postDelayed(PillService.this::showNightstand, 4200);
                    }
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
    private final Runnable liveCollapse = new Runnable() {
        @Override
        public void run() {
            if (mode == LIVE && expanded) {
                expanded = false;
                refresh();
            }
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
        CrashLog.install(this);
        inst = this;
        prefs = Prefs.get(this);
        prefs.registerOnSharedPreferenceChangeListener(this);
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        computeGeometry();
        buildViews();
        panels = new Panels(this);
        panels.updateEdges();
        patch = new LockPatch();
        call = new CallOverlay(this);
        volume = new VolumePanel(this);
        extras = new IslandExtras(this);
        profiles = new AppTools.ProfileRunner(this);
        h.postDelayed(() -> Tweaks.reapply(this), 3000);
        try {
            android.net.ConnectivityManager cm = getSystemService(android.net.ConnectivityManager.class);
            cm.registerNetworkCallback(new android.net.NetworkRequest.Builder()
                    .addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI).build(),
                    new android.net.ConnectivityManager.NetworkCallback() {
                        @Override
                        public void onAvailable(android.net.Network n) {
                            h.postDelayed(() -> Shell.run("cmd wifi status", (ok, out) -> {
                                java.util.regex.Matcher m = java.util.regex.Pattern.compile("SSID: \"([^\"]+)\"").matcher(out == null ? "" : out);
                                Automation.fire(PillService.this, Automation.T_WIFI, m.find() ? m.group(1) : "");
                            }), 2500);
                        }
                    });
        } catch (Exception ignored) {
        }
        h.post(() -> { try { extras.start(); } catch (Throwable t) { CrashLog.write(this, t); } });
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_USER_PRESENT);
        f.addAction(Intent.ACTION_TIME_TICK);
        f.addAction(Intent.ACTION_POWER_CONNECTED);
        f.addAction(Intent.ACTION_POWER_DISCONNECTED);
        f.addAction(Intent.ACTION_BATTERY_CHANGED);
        f.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        f.addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED);
        f.addAction("android.bluetooth.device.action.BATTERY_LEVEL_CHANGED");
        f.addAction(android.media.AudioManager.RINGER_MODE_CHANGED_ACTION);
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
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event != null && event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && extras != null
                && event.getPackageName() != null) {
            String fp = event.getPackageName().toString();
            if (!fp.equals("com.android.systemui") && !fp.equals(getPackageName()) && !fp.equals("android")) {
                extras.fgPkg = fp;
                if (profiles != null) profiles.onForeground(fp);
            }
        }
        if (lockCover == null || !lockCover.shown() || event == null) return;
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence p = event.getPackageName();
        if (p == null) return;
        String pk = p.toString();
        // app lain (penggera, panggilan, kamera) di atas lock screen: jangan sekali-kali tutupnya
        if (!pk.equals(getPackageName()) && !pk.equals("com.android.systemui") && !pk.equals("android")) {
            lockCover.hide();
        }
    }

    @Override
    protected boolean onKeyEvent(android.view.KeyEvent e) {
        int k = e.getKeyCode();
        if (volume == null || prefs == null || !prefs.getBoolean(Prefs.VOLUME_ON, true)) return false;
        if (k != android.view.KeyEvent.KEYCODE_VOLUME_UP && k != android.view.KeyEvent.KEYCODE_VOLUME_DOWN) return false;
        if ((call != null && call.showing()) || islandCallActive()) return false;
        boolean up = k == android.view.KeyEvent.KEYCODE_VOLUME_UP;
        if (e.getAction() == android.view.KeyEvent.ACTION_DOWN) {
            if (e.getRepeatCount() > 0) return true; // ulangan dikawal sendiri
            boolean used = volume.onKey(up);
            if (used) {
                volRepeatUp = up;
                volRepeatN = 0;
                h.removeCallbacks(volRepeat);
                h.postDelayed(volRepeat, 380);
            }
            return used;
        }
        if (e.getAction() == android.view.KeyEvent.ACTION_UP) h.removeCallbacks(volRepeat);
        return true;
    }

    @Override
    public void onInterrupt() { }

    @Override
    public void onDestroy() {
        if (extras != null) extras.stop();
        if (lockCover != null) lockCover.destroy();
        inst = null;
        h.removeCallbacksAndMessages(null);
        if (prefs != null) prefs.unregisterOnSharedPreferenceChangeListener(this);
        if (mc != null) mc.unregisterCallback(mcb);
        if (msm != null) {
            try { msm.removeOnActiveSessionsChangedListener(sessL); } catch (Exception ignored) {}
        }
        if (panels != null) panels.destroy();
        if (patch != null) patch.hide(false);
        if (call != null) call.hide(false);
        hideBanner(false);
        if (Recorder.recording()) Recorder.stop(this);
        if (volume != null) volume.hide();
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
        int night = newConfig.uiMode & Configuration.UI_MODE_NIGHT_MASK;
        if (night != lastNight) {
            lastNight = night;
            if (prefs != null) GlassWidget.refreshAll(this); // tema Auto: kaca cerah/gelap ikut sistem
        }
        hideStatusBar();
        updateStatusBar();
        refresh();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
        if (Prefs.PRAYER_ON.equals(key)) {
            if (sp.getBoolean(Prefs.PRAYER_ON, true)) Prayer.schedule(this);
            else Prayer.cancel(this);
            h.post(this::prayerTick);
        }
        if (Prefs.LIVE_ON.equals(key)) h.post(this::refresh);
        if (Prefs.NET_SPEED.equals(key)) h.post(netTick);
        if (Prefs.LOCK_ON.equals(key) && !sp.getBoolean(Prefs.LOCK_ON, false) && lockCover != null) {
            lockCover.destroy();
            lockCover = null;
        }
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

        privDot = new View(this);
        privDot.setVisibility(View.GONE);
        FrameLayout.LayoutParams pdl = new FrameLayout.LayoutParams(dp(7), dp(7), Gravity.END | Gravity.CENTER_VERTICAL);
        pdl.rightMargin = dp(5);

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
        pill.addView(privDot, pdl);

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
        mBar.setOnTouchListener((v, e) -> {
            if (mc == null || duration <= 0) return false;
            float f = Math.max(0f, Math.min(1f, e.getX() / v.getWidth()));
            mBar.setValue(f);
            if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                mc.getTransportControls().seekTo((long) (f * duration));
                h.removeCallbacks(autoCollapse);
                h.postDelayed(autoCollapse, 7000);
            }
            return true;
        });
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

        // tindakan notifikasi (contoh: Tandai dibaca)
        nActions = new LinearLayout(this);
        nActions.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout notiWrap = new LinearLayout(this);
        notiWrap.setOrientation(LinearLayout.VERTICAL);
        pill.removeView(expNoti);
        notiWrap.addView(expNoti, new LinearLayout.LayoutParams(-1, dp(76)));
        LinearLayout.LayoutParams nal = new LinearLayout.LayoutParams(-1, dp(40));
        nal.leftMargin = dp(14);
        nal.rightMargin = dp(14);
        notiWrap.addView(nActions, nal);
        expNoti = notiWrap;
        pill.addView(expNoti, new FrameLayout.LayoutParams(-1, -1));

        // ---- panggilan ----
        expCall = new LinearLayout(this);
        expCall.setGravity(Gravity.CENTER_VERTICAL);
        expCall.setPadding(dp(12), dp(10), dp(12), dp(10));
        kAvatar = new ImageView(this);
        kAvatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        roundClip(kAvatar, dp(24));
        expCall.addView(kAvatar, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout kcol = new LinearLayout(this);
        kcol.setOrientation(LinearLayout.VERTICAL);
        kcol.setPadding(dp(12), 0, dp(6), 0);
        kLabel = Ui.line(this, 12, 500, Color.argb(150, 235, 235, 245));
        kName = Ui.line(this, 16, 650, Color.WHITE);
        kcol.addView(kLabel);
        kcol.addView(kName);
        expCall.addView(kcol, new LinearLayout.LayoutParams(0, -2, 1f));
        kWave = new Widgets.Wave(this);
        kWave.setColor(0xFF30D158);
        LinearLayout.LayoutParams kwl = new LinearLayout.LayoutParams(dp(26), dp(16));
        kwl.rightMargin = dp(10);
        expCall.addView(kWave, kwl);
        kDecline = callBtn(0xFFFF3B30, true, v -> callAct(false));
        kAccept = callBtn(0xFF30D158, false, v -> callAct(true));
        expCall.addView(kDecline, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout.LayoutParams kal = new LinearLayout.LayoutParams(dp(48), dp(48));
        kal.leftMargin = dp(12);
        expCall.addView(kAccept, kal);
        expCall.setOnClickListener(v -> {
            // tekan kawasan nama: buka skrin panggilan app
            if (!callRinging) sendIntent(callHangOpen);
        });
        pill.addView(expCall, new FrameLayout.LayoutParams(-1, -1));
        buildLive();

        // ---- sentuhan ----
        pill.setOnClickListener(v -> onPillTap());
        pill.setOnTouchListener(new View.OnTouchListener() {
            float y0;
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) y0 = e.getRawY();
                if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                    float dy = e.getRawY() - y0;
                    if (dy < -dp(18)) { onPillSwipe(false); return true; }
                    if (dy > dp(18)) { onPillSwipe(true); return true; }
                }
                return false;
            }
        });
        pill.setOnLongClickListener(v -> {
            if (mode == MUSIC) {
                openMusicApp();
                return true;
            }
            return false;
        });
        root.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_OUTSIDE && expanded && !(mode == CALL && callRinging)) {
                expanded = false;
                if (mode == NOTI) notiActive = false;
                refresh();
            }
            return false;
        });
    }

    private PendingIntent callHangOpen;

    // ================= Aktiviti langsung =================

    /** Cincin kemajuan kecil. */
    static class Ring extends View {
        float v = -1;
        int color = 0xFF0A84FF;
        String label;
        private final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        Ring(Context c) { super(c); }

        void set(float frac, int col, String lbl) { v = frac; color = col; label = lbl; invalidate(); }

        @Override
        protected void onDraw(Canvas c) {
            float s = Math.min(getWidth(), getHeight()), th = s * 0.12f, r = s / 2f - th;
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            p.setStyle(android.graphics.Paint.Style.STROKE);
            p.setStrokeWidth(th);
            p.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            p.setColor(Color.argb(60, 255, 255, 255));
            c.drawCircle(cx, cy, r, p);
            if (v >= 0) {
                p.setColor(color);
                c.drawArc(cx - r, cy - r, cx + r, cy + r, -90, 360 * Math.min(1f, v), false, p);
            }
            if (label != null) {
                p.setStyle(android.graphics.Paint.Style.FILL);
                p.setColor(Color.WHITE);
                p.setTextAlign(android.graphics.Paint.Align.CENTER);
                p.setTypeface(Ui.font(700));
                p.setTextSize(s * (label.length() > 3 ? 0.26f : 0.32f));
                c.drawText(label, cx, cy + p.getTextSize() * 0.36f, p);
            }
        }
    }

    private LinearLayout expLive;
    private ImageView lIcon;
    private TextView lTitle, lText, lRight;
    private Ring lRing, cRing;
    private Widgets.Bar lBar;
    private Live.Item liveShown;
    private final Runnable liveTick = new Runnable() {
        @Override
        public void run() {
            if (mode != LIVE || liveShown == null) return;
            bindLive(liveShown);
            h.postDelayed(this, 1000);
        }
    };

    private void buildLive() {
        expLive = new LinearLayout(this);
        expLive.setOrientation(LinearLayout.VERTICAL);
        expLive.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        lIcon = new ImageView(this);
        lIcon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        roundClip(lIcon, dp(12));
        row.addView(lIcon, new LinearLayout.LayoutParams(dp(46), dp(46)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(12), 0, dp(8), 0);
        lTitle = Ui.line(this, 16, 650, Color.WHITE);
        lText = Ui.text(this, 13, 450, Color.argb(175, 235, 235, 245));
        lText.setMaxLines(2);
        lText.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(lTitle);
        col.addView(lText);
        row.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        lRight = Ui.line(this, 20, 700, Color.WHITE);
        row.addView(lRight);
        lRing = new Ring(this);
        row.addView(lRing, new LinearLayout.LayoutParams(dp(44), dp(44)));
        expLive.addView(row);
        lBar = new Widgets.Bar(this);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, dp(5));
        bl.topMargin = dp(12);
        expLive.addView(lBar, bl);
        pill.addView(expLive, new FrameLayout.LayoutParams(-1, -1));
        // cincin kecil dalam island padat
        cRing = new Ring(this);
        FrameLayout.LayoutParams rl = new FrameLayout.LayoutParams(compactH - dp(12), compactH - dp(12), Gravity.END | Gravity.CENTER_VERTICAL);
        rl.rightMargin = dp(9);
        compact.addView(cRing, rl);
        cRing.setVisibility(View.GONE);
    }

    private static int liveColor(Live.Item i) {
        switch (i.kind) {
            case Live.K_NAV: return 0xFF34C759;
            case Live.K_RIDE: return 0xFF30D158;
            case Live.K_TIMER: return 0xFFFF9F0A;
            case Live.K_PRAYER: return 0xFF64D2FF;
            case Live.K_PROGRESS: return 0xFF0A84FF;
            default: return Color.WHITE;
        }
    }

    private void bindLive(Live.Item i) {
        int col = liveColor(i);
        Drawable ic = i.kind == Live.K_NAV && i.big != null ? i.big : (i.big != null && i.kind == Live.K_RIDE ? i.big : i.icon);
        cAppIcon.setImageDrawable(ic != null ? ic : i.icon);
        lIcon.setImageDrawable(ic != null ? ic : i.icon);
        lIcon.setBackgroundColor(i.kind == Live.K_NAV ? 0xFF1C7C3C : Color.TRANSPARENT);
        String ct = Live.compactText(this, i);
        boolean ring = i.kind == Live.K_PROGRESS && (i.progress >= 0 || i.indeterminate);
        cRing.setVisibility(ring ? View.VISIBLE : View.GONE);
        cText.setVisibility(ring ? View.GONE : View.VISIBLE);
        cText.setTextColor(col);
        cText.setText(ct);
        if (ring) cRing.set(i.progress >= 0 ? i.progress / 100f : -1, col, null);

        lTitle.setText(i.title != null ? i.title : "");
        CharSequence body = i.text;
        if (i.sub != null && i.kind == Live.K_RIDE) body = (body == null ? "" : body + " · ") + i.sub;
        lText.setText(body);
        lText.setVisibility(TextUtils.isEmpty(body) ? View.GONE : View.VISIBLE);
        boolean timed = (i.kind == Live.K_TIMER || i.kind == Live.K_STOPWATCH || i.kind == Live.K_PRAYER) && i.when > 0;
        lRing.setVisibility(ring ? View.VISIBLE : View.GONE);
        if (ring) lRing.set(i.progress >= 0 ? i.progress / 100f : -1, col, i.progress >= 0 ? String.valueOf(i.progress) : null);
        String right = timed ? ct : (i.kind == Live.K_RIDE ? Live.findMinutes(i) : null);
        lRight.setVisibility(right != null && !ring ? View.VISIBLE : View.GONE);
        lRight.setTextColor(col);
        if (right != null) lRight.setText(right);
        lBar.setVisibility(i.progress >= 0 ? View.VISIBLE : View.GONE);
        if (i.progress >= 0) {
            lBar.setColor(col);
            lBar.setValue(i.progress / 100f);
        }
    }

    /** Dipanggil oleh NotifListener untuk notifikasi berterusan. Pulangkan true jika dikendalikan. */
    boolean onLive(StatusBarNotification sbn) {
        if (prefs == null || !prefs.getBoolean(Prefs.LIVE_ON, true)) return false;
        Live.Item it = Live.from(this, sbn);
        if (it == null) {
            if (Live.items.remove(sbn.getKey()) != null) h.post(this::refresh);
            return false;
        }
        Live.Item old = Live.items.get(it.key);
        // kemas kini kemajuan tidak dikira "baru" (elak lompat ke depan setiap peratus)
        if (old != null && old.kind == it.kind) it.updated = old.updated;
        Live.items.put(it.key, it);
        h.post(this::refresh);
        return true;
    }

    void onLiveRemoved(String key) {
        if (Live.items.remove(key) != null) h.post(this::refresh);
    }

    private FrameLayout callBtn(int color, boolean hang, View.OnClickListener l) {
        FrameLayout f = new FrameLayout(this);
        f.setBackground(Ui.circle(color));
        com.glasskit.app.Icon ic = new com.glasskit.app.Icon(this, com.glasskit.app.Icon.PHONE, Color.WHITE);
        if (hang) ic.setRotation(135);
        f.addView(ic, new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
        f.setOnClickListener(l);
        Ui.press(f);
        return f;
    }

    /** Panggilan masuk / sedang berlangsung dipaparkan dalam island. */
    void islandCall(String key, CharSequence name, String label, Drawable photo, PendingIntent answer,
                    PendingIntent decline, PendingIntent hang, PendingIntent open, boolean ringing, long when, boolean quiet) {
        if (pill == null) return;
        if (lockCover != null) lockCover.hide();
        boolean isNew = !key.equals(callKey);
        boolean wasRinging = callRinging;
        callKey = key;
        callName = name;
        callLabel = label;
        callAnswer = answer;
        callDecline = decline;
        callHang = hang;
        callHangOpen = open;
        callRinging = ringing;
        if (photo != null) kAvatar.setImageDrawable(photo);
        else kAvatar.setImageDrawable(Ui.iconDrawable(this, com.glasskit.app.Icon.PHONE, Color.WHITE, 0xFF636366, dp(48)));
        cAppIcon.setImageDrawable(kAvatar.getDrawable());
        kName.setText(name);
        if (ringing) {
            kLabel.setText(label);
            expanded = !quiet; // gaya kad: island kekal kecil di belakang kad
            pulse(kAccept);
        } else {
            if (isNew || wasRinging || callStart == 0) callStart = when > 0 && when < System.currentTimeMillis() ? when : System.currentTimeMillis();
            if (wasRinging) expanded = false; // selepas dijawab: kecilkan
            h.removeCallbacks(callTick);
            h.post(callTick);
        }
        refresh();
    }

    void islandCallEnded(String key) {
        if (key == null || !key.equals(callKey)) return;
        callKey = null;
        callRinging = false;
        callStart = 0;
        h.removeCallbacks(callTick);
        expanded = false;
        refresh();
    }

    boolean islandCallActive() { return callKey != null; }

    /** Dari kad panggilan: besarkan island (jawab / tolak di island). */
    void expandCallIsland() {
        if (callKey == null) return;
        expanded = true;
        refresh();
    }

    private void pulse(View v) {
        v.animate().cancel();
        v.setScaleX(1f);
        v.setScaleY(1f);
        v.animate().scaleX(1.12f).scaleY(1.12f).setDuration(520).withEndAction(() ->
                v.animate().scaleX(1f).scaleY(1f).setDuration(520).withEndAction(() -> {
                    if (callRinging && callKey != null) pulse(v);
                }).start()).start();
    }

    private void callAct(boolean accept) {
        PendingIntent pi = callRinging ? (accept ? callAnswer : callDecline) : callHang;
        sendIntent(pi);
        if (callRinging && accept) {
            callRinging = false;
            callStart = System.currentTimeMillis();
            expanded = false;
            h.removeCallbacks(callTick);
            h.post(callTick);
            refresh();
        } else {
            String k = callKey;
            islandCallEnded(k);
        }
    }

    private void onPillSwipe(boolean down) {
        if (down && expanded && mode != CALL && panels != null && prefs.getBoolean(Prefs.PANELS_ON, true)) {
            // tarik island yang sudah besar: ia "mencair" menjadi panel kawalan
            expanded = false;
            refresh();
            panels.openControl();
            return;
        }
        if (down) {
            if (!expanded && (mode == MUSIC || mode == CALL || mode == LIVE)) {
                expanded = true;
                refresh();
                h.removeCallbacks(autoCollapse);
                h.postDelayed(autoCollapse, 7000);
                if (mode == CALL) {
                    h.removeCallbacks(callCollapse);
                    h.postDelayed(callCollapse, 6000);
                }
            }
            return;
        }
        if (mode == CALL && callRinging) return; // jangan sorok panggilan masuk
        if (mode == NOTI) {
            h.removeCallbacks(endNoti);
            endNoti.run();
            return;
        }
        if (expanded) {
            expanded = false;
            refresh();
        }
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
        if (callKey != null && (panels == null || !panels.isOpen())) {
            newMode = CALL;
        } else if (Recorder.recording() && !land && (panels == null || !panels.isOpen())) {
            newMode = REC;
        } else if (on && !land && (panels == null || !panels.isOpen())) {
            if (notiActive) newMode = NOTI;
            else if (Live.current() != null && prefs.getBoolean(Prefs.LIVE_ON, true)) newMode = LIVE;
            else if (musicOn) newMode = MUSIC;
        }
        if (newMode == IDLE && on && !land && extras != null && extras.privacy() != 0
                && (panels == null || !panels.isOpen())) newMode = PRIV;
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
        boolean showCall = expanded && mode == CALL;
        onPrivacy();
        boolean showLive = expanded && mode == LIVE;
        if (showLive && expLive.getVisibility() != View.VISIBLE) fadeIn(expLive);
        expLive.setVisibility(showLive ? View.VISIBLE : View.GONE);
        if (mode != LIVE) {
            cRing.setVisibility(View.GONE);
            h.removeCallbacks(liveTick);
            liveShown = null;
        }
        if (showMusic && expMusic.getVisibility() != View.VISIBLE) fadeIn(expMusic);
        if (showNoti && expNoti.getVisibility() != View.VISIBLE) fadeIn(expNoti);
        if (showCall && expCall.getVisibility() != View.VISIBLE) fadeIn(expCall);
        if (!expanded && wasExpanded) fadeIn(compact);
        expMusic.setVisibility(showMusic ? View.VISIBLE : View.GONE);
        expNoti.setVisibility(showNoti ? View.VISIBLE : View.GONE);
        expCall.setVisibility(showCall ? View.VISIBLE : View.GONE);
        kWave.setRunning(showCall && !callRinging);

        if (mode == PRIV) {
            int k = extras.privacy();
            cArt.setVisibility(View.GONE);
            cAppIcon.setVisibility(View.VISIBLE);
            cAppIcon.setImageDrawable(Ui.iconDrawable(this, k == 1 ? com.glasskit.app.Icon.CAMERA : com.glasskit.app.Icon.NOTE,
                    Color.WHITE, k == 1 ? 0xFF248A3D : 0xFFC93400, dp(30)));
            cText.setVisibility(View.GONE);
            cWave.setVisibility(View.GONE);
            cWave.setRunning(false);
            mWave.setRunning(false);
        } else if (mode == LIVE) {
            Live.Item li = Live.current();
            cArt.setVisibility(View.GONE);
            cAppIcon.setVisibility(View.VISIBLE);
            cWave.setVisibility(View.GONE);
            cWave.setRunning(false);
            mWave.setRunning(false);
            if (li != liveShown) {
                liveShown = li;
                h.removeCallbacks(liveTick);
                h.post(liveTick);
            }
            bindLive(li);
        } else if (mode == CALL) {
            cArt.setVisibility(View.GONE);
            cAppIcon.setVisibility(View.VISIBLE);
            cText.setVisibility(View.VISIBLE);
            cText.setTextColor(0xFF30D158);
            if (!callRinging) cText.setText(Ui.mmss(System.currentTimeMillis() - callStart));
            else cText.setText(R.string.call_ringing_short);
            cWave.setVisibility(View.GONE);
            cWave.setRunning(false);
            mWave.setRunning(false);
            kAccept.setVisibility(callRinging ? View.VISIBLE : View.GONE);
            kWave.setVisibility(callRinging ? View.GONE : View.VISIBLE);
            if (!callRinging) kLabel.setTextColor(0xFF30D158);
            else kLabel.setTextColor(Color.argb(150, 235, 235, 245));
        } else if (mode == REC) {
            expanded = false;
            compact.setVisibility(View.VISIBLE);
            expMusic.setVisibility(View.GONE);
            expNoti.setVisibility(View.GONE);
            cArt.setVisibility(View.GONE);
            cAppIcon.setVisibility(View.VISIBLE);
            cAppIcon.setImageDrawable(Ui.circle(0xFFFF3B30));
            cText.setVisibility(View.VISIBLE);
            cText.setTextColor(0xFFFF453A);
            cText.setText("● " + Ui.mmss(Recorder.elapsed()));
            cWave.setVisibility(View.GONE);
            cWave.setRunning(false);
            mWave.setRunning(false);
        } else if (mode == MUSIC) {
            cText.setTextColor(Color.WHITE);
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
            cText.setTextColor(Color.WHITE);
            cArt.setVisibility(View.GONE);
            cAppIcon.setVisibility(View.VISIBLE);
            cText.setVisibility(View.VISIBLE);
            cWave.setVisibility(View.GONE);
            cWave.setRunning(false);
            mWave.setRunning(false);
        }

        int tw = expanded ? Math.min(screenW - dp(44), dp(400)) : compactW;
        int th = expanded ? (mode == MUSIC ? dp(172)
                : mode == LIVE ? (liveShown != null && liveShown.progress >= 0 ? dp(96) : dp(78))
                : mode == NOTI && nActions.getChildCount() > 0 ? dp(122) : dp(76)) : compactH;
        animateTo(tw, th, tw >= curW);

        h.removeCallbacks(progTick);
        if (expanded && mode == MUSIC) h.post(progTick);
        if (sbar != null && sbarShown) sbar.setGeometry(topY + compactH / 2f, screenW / 2f + xOff, compactW / 2f);
    }

    private void onPillTap() {
        if (mode == PRIV && extras != null) {
            extras.showPrivacyInfo();
            return;
        }
        if (mode == LIVE) {
            if (!expanded) {
                expanded = true;
                refresh();
                h.removeCallbacks(autoCollapse);
                h.postDelayed(liveCollapse, 7000);
            } else if (liveShown != null) {
                sendIntent(liveShown.open);
                expanded = false;
                refresh();
            }
            return;
        }
        if (mode == CALL) {
            if (!expanded) {
                expanded = true;
                refresh();
                h.removeCallbacks(callCollapse);
                h.postDelayed(callCollapse, 6000);
            } else if (!callRinging) {
                sendIntent(callHangOpen);
            }
            return;
        }
        if (mode == REC) {
            Recorder.stop(this);
            return;
        }
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
        if (prefs == null) return;
        if (LockActivity.visible) {
            lockBanner(sbn);
            return;
        }
        if (!prefs.getBoolean(Prefs.PILL_ON, true) || !prefs.getBoolean(Prefs.PILL_NOTI, true)) return;
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
        // butang tindakan (tanpa input teks), maksimum 2
        nActions.removeAllViews();
        if (n.actions != null) {
            int added = 0;
            for (Notification.Action a : n.actions) {
                if (added >= 2 || a == null || a.actionIntent == null || a.title == null) continue;
                if (a.getRemoteInputs() != null && a.getRemoteInputs().length > 0) continue;
                TextView b = Ui.line(this, 13, 600, Color.WHITE);
                b.setText(a.title);
                b.setGravity(Gravity.CENTER);
                b.setBackground(Ui.round(Color.argb(46, 255, 255, 255), dp(17)));
                final PendingIntent ai = a.actionIntent;
                b.setOnClickListener(v -> {
                    sendIntent(ai);
                    h.removeCallbacks(endNoti);
                    endNoti.run();
                });
                Ui.press(b);
                LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(0, dp(34), 1f);
                if (added > 0) bl.leftMargin = dp(8);
                nActions.addView(b, bl);
                added++;
            }
        }
        if (nActions.getChildCount() > 0) {
            refresh();
            h.removeCallbacks(endNoti);
            h.postDelayed(endNoti, 6500);
        }
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
        if (nActions != null) nActions.removeAllViews();
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
        if (extras != null) h.post(() -> extras.restartScreenshotsIfNeeded());
        h.post(this::prayerTick);
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

    LockCover lockCover;

    void lockCoverReady() {
        if (lockCover != null) lockCover.hide();
    }

    private final Runnable ensureLock = () -> {
        if (!lockNeeded()) return;
        showLock();
        // sandaran yang pasti: lancar melalui Shizuku (shell tidak disekat oleh had pelancaran latar)
        if (Shell.granted()) {
            h.postDelayed(() -> {
                if (lockNeeded()) Shell.run("am start -n " + getPackageName() + "/.LockActivity -f 0x10030000");
            }, 250);
        }
    };
    private int lockMiss;
    /** Pengawas: selagi skrin hidup & berkunci, pastikan lock screen GlassKit di depan (bukan stock). */
    private final Runnable lockWatch = new Runnable() {
        @Override
        public void run() {
            h.removeCallbacks(this);
            if (prefs == null || !prefs.getBoolean(Prefs.LOCK_ON, false)) return;
            PowerManager pm = getSystemService(PowerManager.class);
            KeyguardManager km = getSystemService(KeyguardManager.class);
            if (pm == null || !pm.isInteractive() || km == null || !km.isKeyguardLocked()) {
                lockMiss = 0;
                return; // skrin padam atau sudah dibuka: berhenti
            }
            if (lockNeeded()) {
                lockMiss++;
                if (lockMiss == 1 || lockMiss == 3) showLock();
                else if (lockMiss >= 2 && Shell.granted()) {
                    // sistem sekat pelancaran dari latar: lancar melalui Shizuku (shell dibenarkan)
                    Shell.run("am start -n " + getPackageName() + "/.LockActivity -f 0x10030000");
                }
                if (lockMiss > 8) lockMiss = 0;
            } else {
                lockMiss = 0;
            }
            h.postDelayed(this, lockMiss > 0 ? 450 : 800);
        }
    };

    void focusChanged(int mode) {
        Drawable ic = Ui.iconDrawable(this, mode == Focus.SLEEP ? com.glasskit.app.Icon.MOON : com.glasskit.app.Icon.BELL,
                Color.WHITE, mode == Focus.OFF ? 0xFF636366 : 0xFF5E5CE6, dp(42));
        show(ic, ic, getString(mode == Focus.OFF ? R.string.f_off_now : R.string.f_on_now, getString(Focus.NAMES[mode])), "", null);
        updateStatusBar();
    }

    private void showNightstand() {
        try {
            Intent i = new Intent(this, NightstandActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            ActivityOptions o = ActivityOptions.makeBasic();
            if (Build.VERSION.SDK_INT >= 34) {
                o.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
            }
            startActivity(i, o.toBundle());
        } catch (Exception ignored) {
        }
    }

    private boolean lockNeeded() {
        if (prefs == null || !prefs.getBoolean(Prefs.LOCK_ON, false) || LockActivity.visible) return false;
        if (NightstandActivity.visible) return false;
        if (System.currentTimeMillis() < LockActivity.holdUntil) return false;
        KeyguardManager km = getSystemService(KeyguardManager.class);
        if (km == null || !km.isKeyguardLocked()) return false;
        if ((call != null && call.showing()) || islandCallActive()) return false;
        android.media.AudioManager am = getSystemService(android.media.AudioManager.class);
        if (am != null) {
            int m = am.getMode();
            if (m == android.media.AudioManager.MODE_RINGTONE || m == android.media.AudioManager.MODE_IN_CALL
                    || m == android.media.AudioManager.MODE_IN_COMMUNICATION) return false;
        }
        return true;
    }

    void showLock() {
        try {
            Intent i = new Intent(this, LockActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION
                    | Intent.FLAG_ACTIVITY_NO_USER_ACTION | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            ActivityOptions o = ActivityOptions.makeBasic();
            if (Build.VERSION.SDK_INT >= 34) {
                o.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
            }
            startActivity(i, o.toBundle());
        } catch (Exception ignored) {
        }
    }

    private ChargeAnim chargeAnim;
    private String prayDay;

    /** Setiap minit: kira detik solat dalam island (15 min sebelum), sandaran azan, kira semula harian. */
    void prayerTick() {
        if (prefs == null) return;
        boolean on = prefs.getBoolean(Prefs.PRAYER_ON, true);
        if (!on || !Prayer.hasLocation(this)) {
            if (Live.items.remove("gk_prayer") != null) refresh();
            return;
        }
        String day = new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(new java.util.Date());
        if (!day.equals(prayDay)) {
            prayDay = day;
            Prayer.fetch(this);
            Prayer.schedule(this);
            Shuffle.daily(this, false);
            Updater.check(this, (info, err) -> {
                if (info == null) return;
                h.post(() -> {
                    Intent oi = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    PendingIntent op = PendingIntent.getActivity(this, 95, oi, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                    Drawable ic = getApplicationInfo().loadIcon(getPackageManager());
                    showIsland(ic, ic, getString(R.string.upd_island, info.version), getString(R.string.upd_island_body), op);
                });
            });
            GlassWidget.updateAll(this, PrayerWidget.class);
        }
        Prayer.Day d = Prayer.today(this);
        long now = System.currentTimeMillis();
        if (d != null) {
            for (int i = 0; i < 6; i++) {
                if (i == Prayer.SUNRISE) continue;
                if (now >= d.t[i] && now - d.t[i] < 3 * 60_000L) {
                    Prayer.fire(this, i, d.t[i]);
                    if (prefs.getLong("prayIsland", 0) != d.t[i]) {
                        prefs.edit().putLong("prayIsland", d.t[i]).apply();
                        Drawable ic = Ui.iconDrawable(this, com.glasskit.app.Icon.BELL, Color.WHITE, 0xFF0A84FF, dp(42));
                        show(ic, ic, getString(R.string.p_time_now, getString(Prayer.NAMES[i])), Prayer.fmt(this, d.t[i]), null);
                    }
                }
            }
        }
        long[] n = Prayer.next(this);
        if (n != null && n[1] - now <= 15 * 60_000L && n[1] > now) {
            Live.Item it = new Live.Item();
            it.key = "gk_prayer";
            it.kind = Live.K_PRAYER;
            it.title = getString(Prayer.NAMES[(int) n[0]]);
            it.text = getString(R.string.p_at, Prayer.fmt(this, n[1]));
            it.when = n[1];
            it.countDown = true;
            it.icon = Ui.iconDrawable(this, com.glasskit.app.Icon.BELL, Color.WHITE, 0xFF0A84FF, dp(42));
            it.updated = now;
            Live.items.put(it.key, it);
            refresh();
        } else if (Live.items.remove("gk_prayer") != null) {
            refresh();
        }
        if (now / 60000 % 15 == 0) GlassWidget.updateAll(this, PrayerWidget.class);
    }

    private void showCharging() {
        if (prefs == null) return;
        PowerManager pm = getSystemService(PowerManager.class);
        if (prefs.getBoolean(Prefs.CHARGE_ANIM, true) && pm != null && pm.isInteractive() && !islandCallActive()) {
            if (chargeAnim == null) chargeAnim = new ChargeAnim(this);
            chargeAnim.show();
            return;
        }
        if (!prefs.getBoolean(Prefs.PILL_ON, true)) return;
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

    // ================= Banner kaca lock screen (tutup heads-up stock) =================

    private FrameLayout banner;
    private final Runnable bannerHide = () -> hideBanner(true);

    private void lockBanner(StatusBarNotification sbn) {
        Notification n = sbn.getNotification();
        CharSequence t = n.extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence body = n.extras.getCharSequence(Notification.EXTRA_TEXT);
        if (TextUtils.isEmpty(t) && TextUtils.isEmpty(body)) return;
        hideBanner(false);
        LiquidGlass.Backdrop prev = LiquidGlass.current;
        LiquidGlass.current = LockActivity.inst != null ? LockActivity.inst.bd : null;
        FrameLayout win = new FrameLayout(this);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(12));
        LiquidGlass.apply(card, 26, false);
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        ImageView ic = new ImageView(this);
        ic.setImageDrawable(appIcon(sbn.getPackageName()));
        top.addView(ic, new LinearLayout.LayoutParams(dp(22), dp(22)));
        TextView app = Ui.line(this, 13, 600, Color.argb(220, 255, 255, 255));
        String an;
        try {
            an = getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(sbn.getPackageName(), 0)).toString();
        } catch (Exception e) {
            an = sbn.getPackageName();
        }
        app.setText(an + " • " + getString(R.string.now));
        app.setPadding(dp(8), 0, 0, 0);
        top.addView(app, new LinearLayout.LayoutParams(0, -2, 1f));
        card.addView(top);
        if (!TextUtils.isEmpty(t)) {
            TextView tv = Ui.line(this, 16, 650, Color.WHITE);
            tv.setText(t);
            tv.setPadding(0, dp(8), 0, 0);
            card.addView(tv);
        }
        if (!TextUtils.isEmpty(body)) {
            TextView bv = Ui.text(this, 14, 400, Color.argb(230, 255, 255, 255));
            bv.setText(body);
            bv.setMaxLines(2);
            bv.setEllipsize(TextUtils.TruncateAt.END);
            bv.setPadding(0, dp(3), 0, 0);
            card.addView(bv);
        }
        final String key = sbn.getKey();
        card.setOnClickListener(v -> {
            hideBanner(true);
            if (LockActivity.inst != null) LockActivity.inst.expand(key);
        });
        LiquidGlass.current = prev;
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(-1, -1);
        cl.leftMargin = dp(10);
        cl.rightMargin = dp(10);
        cl.topMargin = dp(4);
        cl.bottomMargin = dp(8);
        win.addView(card, cl);
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, dp(168),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP;
        p.y = Math.max(0, statusH - dp(2));
        p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        p.setTitle("GlassKitLockBanner");
        try {
            wm.addView(win, p);
        } catch (Exception e) {
            return;
        }
        banner = win;
        card.setTranslationY(-dp(170));
        card.animate().translationY(0).setInterpolator(Ui.SPRING).setDuration(560).start();
        h.removeCallbacks(bannerHide);
        h.postDelayed(bannerHide, 6500);
        if (LockActivity.inst != null) h.postDelayed(() -> { if (LockActivity.inst != null) LockActivity.inst.refreshNotifs(); }, 200);
    }

    void hideBanner(boolean anim) {
        final FrameLayout b = banner;
        banner = null;
        h.removeCallbacks(bannerHide);
        if (b == null) return;
        if (anim && b.getChildCount() > 0) {
            b.getChildAt(0).animate().translationY(-dp(170)).alpha(0f).setDuration(240).withEndAction(() -> {
                try { wm.removeViewImmediate(b); } catch (Exception ignored) {}
            }).start();
        } else {
            try { wm.removeViewImmediate(b); } catch (Exception ignored) {}
        }
    }

    // ================= Rakaman skrin pada island =================

    private final Runnable recTick = new Runnable() {
        @Override
        public void run() {
            if (!Recorder.recording()) return;
            if (cText != null) cText.setText("● " + Ui.mmss(Recorder.elapsed()));
            h.postDelayed(this, 1000);
        }
    };

    void onRecChanged() {
        if (sbar != null) sbar.setSolid(Recorder.recording() || (extras != null && extras.busy()));
        h.removeCallbacks(recTick);
        if (Recorder.recording()) h.post(recTick);
        refresh();
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
        KeyguardManager kgm = getSystemService(KeyguardManager.class);
        boolean stockKeyguard = kgm != null && kgm.isKeyguardLocked() && !LockActivity.visible;
        boolean want = prefs.getBoolean(Prefs.SBAR_ON, false)
                && getResources().getConfiguration().orientation != Configuration.ORIENTATION_LANDSCAPE
                && (panels == null || !panels.isOpen())
                && !stockKeyguard;
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
        if (mc == null) {
            // tiada sesi aktif: sambung pemain muzik terakhir
            try {
                android.media.AudioManager am = getSystemService(android.media.AudioManager.class);
                long t = SystemClock.uptimeMillis();
                am.dispatchMediaKeyEvent(new android.view.KeyEvent(t, t, android.view.KeyEvent.ACTION_DOWN,
                        android.view.KeyEvent.KEYCODE_MEDIA_PLAY, 0));
                am.dispatchMediaKeyEvent(new android.view.KeyEvent(t, t, android.view.KeyEvent.ACTION_UP,
                        android.view.KeyEvent.KEYCODE_MEDIA_PLAY, 0));
            } catch (Exception ignored) {
            }
            return;
        }
        if (playing) mc.getTransportControls().pause();
        else mc.getTransportControls().play();
    }
}
