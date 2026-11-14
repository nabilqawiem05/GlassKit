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

    private final Handler h = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private KeyguardManager km;
    private int W, H, statusH;

    private ImageView wall;
    private FrameLayout ui;
    private GaugeRow gauges;
    private LinearLayout bottom, notifList, musicBox;
    private MaxScroll notifScroll;
    private UnlockAnim lockAnim;
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
    private final LiquidGlass.Backdrop bd = new LiquidGlass.Backdrop();

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
            hint.setText(locked ? R.string.swipe_open : R.string.unlocked_swipe);
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

        ui = new FrameLayout(this);
        root.addView(ui, new FrameLayout.LayoutParams(-1, -1));

        lockAnim = new UnlockAnim(this);
        FrameLayout.LayoutParams ll = new FrameLayout.LayoutParams(dp(56), dp(56), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        ll.topMargin = statusH + dp(2);
        ui.addView(lockAnim, ll);

        gauges = new GaugeRow(this);
        ui.addView(gauges, new FrameLayout.LayoutParams(-1, dp(130)));

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
        clearAll.setBackground(Ui.glassStyled(this, 16, true));
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
        visible = true;
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
        leaving = false;
        afterUnlock = null;
        replyKey = null;
        expanded.clear();
        ui.setTranslationY(0);
        ui.setAlpha(1f);
        wall.setAlpha(1f);
        wall.setTranslationY(0);
        wall.setScaleX(1f);
        wall.setScaleY(1f);
        if (km.isDeviceLocked()) {
            lockAnim.setLocked();
            soundPlayed = false;
        }
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
            bd.set(frame, W, H);
            LiquidGlass.invalidateTree(ui);
        } catch (Throwable ignored) {
        }
        Path p = DepthRenderer.clockPath(this, prefs, W, H);
        RectF r = new RectF();
        p.computeBounds(r, true);
        float scale = (float) getResources().getDisplayMetrics().widthPixels / W;
        FrameLayout.LayoutParams gl = (FrameLayout.LayoutParams) gauges.getLayoutParams();
        gl.topMargin = Math.round(r.bottom * scale) + dp(16);
        gauges.setLayoutParams(gl);
        gauges.invalidate();
    }

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

    // ======================= Notifikasi =======================

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
        clearAll.setVisibility(list.isEmpty() ? View.GONE : View.VISIBLE);
        for (StatusBarNotification sbn : list) {
            View card = card(sbn);
            if (card == null) continue;
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
            p.topMargin = dp(8);
            notifList.addView(card, p);
        }
    }

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
        LiquidGlass.apply(c, 24, prefs.getBoolean(Prefs.GLASS_DARK, false));
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
                et.setBackground(Ui.glassStyled(this, 20, true));
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

    private TextView pill(String s, boolean primary) {
        TextView t = Ui.line(this, 13, 650, primary ? 0xFF0B0B10 : Color.WHITE);
        t.setText(s);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(16), 0, dp(16), 0);
        t.setBackground(primary ? Ui.round(Color.WHITE, dp(18)) : Ui.glassStyled(this, 18, true));
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
        Runnable exit = () -> {
            if (!soundPlayed) {
                soundPlayed = true;
                Sounds.play(this, Sounds.UNLOCK);
            }
            ui.animate().translationY(-H * 0.12f).alpha(0f).setDuration(240).start();
            wall.animate().scaleX(1.08f).scaleY(1.08f).alpha(0f).setDuration(260).withEndAction(() -> {
                if (r != null) r.run();
                finish();
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

    private void springBack() {
        afterUnlock = null;
        ui.animate().translationY(0).alpha(1f).setInterpolator(Ui.SPRING).setDuration(500).start();
        wall.animate().translationY(0).setDuration(300).start();
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

        Root(Context c) { super(c); }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent e) {
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
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    y0 = e.getRawY();
                    if (e.getRawY() > H * 0.76f && Math.abs(e.getRawX() - W / 2f) < W * 0.14f) {
                        lastSensorTouch = System.currentTimeMillis();
                    }
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
