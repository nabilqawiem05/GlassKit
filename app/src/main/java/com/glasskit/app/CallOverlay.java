package com.glasskit.app;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.app.ActivityOptions;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Person;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/**
 * Skrin panggilan masuk kaca untuk SEMUA app panggilan (Telefon, WhatsApp, Truecaller, Telegram...).
 * Dikesan dari notifikasi panggilan (CallStyle / kategori CALL) yang ada tindakan jawab & tolak.
 */
final class CallOverlay {
    private final PillService s;
    private FrameLayout root;
    private String key;
    private PendingIntent answer, decline;
    private TextView nameV, subV;
    private ImageView avatar;
    private final LiquidGlass.Backdrop bd = new LiquidGlass.Backdrop();

    CallOverlay(PillService s) { this.s = s; }

    private int dp(float v) { return Ui.dp(s, v); }

    boolean showing() { return root != null; }

    String key() { return key; }

    @SuppressWarnings("deprecation")
    private static PendingIntent pi(Bundle ex, String k) {
        try {
            Object o = ex.getParcelable(k);
            return o instanceof PendingIntent ? (PendingIntent) o : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Cari tindakan jawab/tolak. Kembalikan false jika ini bukan panggilan masuk. */
    private boolean resolve(Notification n) {
        Bundle ex = n.extras;
        answer = pi(ex, Notification.EXTRA_ANSWER_INTENT);
        decline = pi(ex, Notification.EXTRA_DECLINE_INTENT);
        if (decline == null && answer != null) decline = pi(ex, Notification.EXTRA_HANG_UP_INTENT);
        if (answer == null && n.actions != null) {
            for (Notification.Action a : n.actions) {
                if (a.title == null || a.actionIntent == null) continue;
                String t = a.title.toString().toLowerCase(Locale.ROOT);
                if (answer == null && (t.contains("answer") || t.contains("jawab") || t.contains("accept") || t.contains("terima")
                        || t.contains("angkat"))) answer = a.actionIntent;
                else if (decline == null && (t.contains("decline") || t.contains("tolak") || t.contains("reject")
                        || t.contains("abaikan") || t.contains("dismiss"))) decline = a.actionIntent;
            }
        }
        return answer != null;
    }

    static boolean isCall(Notification n) {
        return Notification.CATEGORY_CALL.equals(n.category)
                || (n.extras != null && n.extras.containsKey(Notification.EXTRA_ANSWER_INTENT));
    }

    @SuppressWarnings("deprecation")
    void onCallNotification(StatusBarNotification sbn) {
        if (!s.prefs.getBoolean(Prefs.CALL_ON, true)) return;
        Notification n = sbn.getNotification();
        int style = s.prefs.getInt(Prefs.CALL_STYLE, 0);
        if (style == 0) {
            islandRoute(sbn, n, false);
            return;
        }
        if (style == 2) {
            // kad kaca: kad terapung semasa berdering, island untuk panggilan berlangsung
            boolean ringing = resolve(n);
            islandRoute(sbn, n, ringing);
            if (!ringing) {
                if (sbn.getKey().equals(key)) hide(true);
                return;
            }
            if (root != null && sbn.getKey().equals(key)) return;
            hide(false);
            key = sbn.getKey();
            Bundle ex = n.extras;
            CharSequence name = null;
            Drawable photo = null;
            try {
                Object po = ex.getParcelable(Notification.EXTRA_CALL_PERSON);
                if (po instanceof Person) {
                    name = ((Person) po).getName();
                    if (((Person) po).getIcon() != null) photo = ((Person) po).getIcon().loadDrawable(s);
                }
            } catch (Exception ignored) {
            }
            if (TextUtils.isEmpty(name)) name = ex.getCharSequence(Notification.EXTRA_TITLE);
            if (photo == null && n.getLargeIcon() != null) {
                try { photo = n.getLargeIcon().loadDrawable(s); } catch (Exception ignored) {}
            }
            CharSequence sub = ex.getCharSequence(Notification.EXTRA_TEXT);
            buildCard(name, TextUtils.isEmpty(sub) ? s.getString(R.string.incoming_call) : sub.toString(), photo, sbn.getPackageName());
            return;
        }
        if (!resolve(n)) {
            // panggilan sudah dijawab / sedang berlangsung
            if (sbn.getKey().equals(key)) hide(true);
            return;
        }
        Bundle ex = n.extras;
        CharSequence name = null;
        Drawable photo = null;
        try {
            Object po = ex.getParcelable(Notification.EXTRA_CALL_PERSON);
            if (po instanceof Person) {
                Person p = (Person) po;
                name = p.getName();
                if (p.getIcon() != null) photo = p.getIcon().loadDrawable(s);
            }
        } catch (Exception ignored) {
        }
        if (TextUtils.isEmpty(name)) name = ex.getCharSequence(Notification.EXTRA_TITLE);
        if (photo == null && n.getLargeIcon() != null) {
            try { photo = n.getLargeIcon().loadDrawable(s); } catch (Exception ignored) {}
        }
        CharSequence sub = ex.getCharSequence(Notification.EXTRA_TEXT);
        String app = appName(sbn.getPackageName());
        String subtitle = (TextUtils.isEmpty(sub) ? s.getString(R.string.incoming_call) : sub.toString()) + " · " + app;

        if (root != null && sbn.getKey().equals(key)) {
            nameV.setText(name);
            subV.setText(subtitle);
            return;
        }
        hide(false);
        key = sbn.getKey();
        build(name, subtitle, photo, sbn.getPackageName());
    }

    void onRemoved(String k) {
        if (k.equals(key)) hide(true);
        s.islandCallEnded(k);
    }

    /** Hantar panggilan ke Dynamic Island (masuk: jawab/tolak, berlangsung: pemasa + tamatkan). */
    @SuppressWarnings("deprecation")
    private void islandRoute(StatusBarNotification sbn, Notification n, boolean quiet) {
        Bundle ex = n.extras;
        boolean ringing = resolve(n);
        PendingIntent hang = pi(ex, Notification.EXTRA_HANG_UP_INTENT);
        if (hang == null && n.actions != null) {
            for (Notification.Action a : n.actions) {
                if (a == null || a.title == null || a.actionIntent == null) continue;
                String t = a.title.toString().toLowerCase(java.util.Locale.ROOT);
                if (t.contains("hang") || t.contains("end") || t.contains("tamat") || t.contains("letak") || t.contains("putus")) {
                    hang = a.actionIntent;
                    break;
                }
            }
        }
        if (!ringing && hang == null) {
            s.islandCallEnded(sbn.getKey());
            return;
        }
        CharSequence name = null;
        Drawable photo = null;
        try {
            Object po = ex.getParcelable(Notification.EXTRA_CALL_PERSON);
            if (po instanceof Person) {
                Person p = (Person) po;
                name = p.getName();
                if (p.getIcon() != null) photo = p.getIcon().loadDrawable(s);
            }
        } catch (Exception ignored) {
        }
        if (TextUtils.isEmpty(name)) name = ex.getCharSequence(Notification.EXTRA_TITLE);
        if (photo == null && n.getLargeIcon() != null) {
            try { photo = n.getLargeIcon().loadDrawable(s); } catch (Exception ignored) {}
        }
        String label = appName(sbn.getPackageName());
        if ("com.google.android.dialer".equals(sbn.getPackageName()) || "com.android.server.telecom".equals(sbn.getPackageName())) {
            CharSequence sub = ex.getCharSequence(Notification.EXTRA_TEXT);
            label = TextUtils.isEmpty(sub) ? s.getString(R.string.incoming_call) : sub.toString();
        }
        long when = n.when;
        s.islandCall(sbn.getKey(), name, label, photo, answer, decline != null ? decline : hang, hang,
                n.contentIntent, ringing, ringing ? 0 : when, quiet);
    }

    private String appName(String pkg) {
        try {
            return s.getPackageManager().getApplicationLabel(s.getPackageManager().getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    private static Bitmap toBitmap(Drawable d, int size) {
        if (d == null) return null;
        if (d instanceof BitmapDrawable && ((BitmapDrawable) d).getBitmap() != null) return ((BitmapDrawable) d).getBitmap();
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        d.setBounds(0, 0, size, size);
        d.draw(c);
        return b;
    }

    // ======================= Paparan =======================

    private void build(CharSequence name, String subtitle, Drawable photo, String pkg) {
        root = new FrameLayout(s);
        root.setBackgroundColor(Color.BLACK);
        Bitmap ph = toBitmap(photo, dp(160));

        // latar: gambar pemanggil yang dikaburkan, atau wallpaper
        ImageView bg = new ImageView(s);
        bg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        Bitmap src = ph != null ? ph : DepthRenderer.load(DepthRenderer.bgFile(s));
        if (src != null) {
            Bitmap bl = GlassArt.blur(src, 120, 6);
            bg.setImageBitmap(bl);
            bd.set(src, s.screenW, s.screenH);
        }
        root.addView(bg, new FrameLayout.LayoutParams(-1, -1));
        View dim = new View(s);
        dim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.argb(120, 0, 0, 0), Color.argb(70, 0, 0, 0), Color.argb(170, 0, 0, 0)}));
        root.addView(dim, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout col = new LinearLayout(s);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(dp(24), s.statusH + dp(36), dp(24), dp(40));
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));

        // cip app
        LinearLayout chip = new LinearLayout(s);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPadding(dp(10), dp(6), dp(14), dp(6));
        LiquidGlass.Backdrop prev = LiquidGlass.current;
        LiquidGlass.current = bd;
        LiquidGlass.apply(chip, 16, false);
        ImageView ai = new ImageView(s);
        try { ai.setImageDrawable(s.getPackageManager().getApplicationIcon(pkg)); } catch (Exception ignored) {}
        chip.addView(ai, new LinearLayout.LayoutParams(dp(20), dp(20)));
        TextView at = Ui.text(s, 13, 600, Color.WHITE);
        at.setText(appName(pkg));
        at.setPadding(dp(8), 0, 0, 0);
        chip.addView(at);
        col.addView(chip, new LinearLayout.LayoutParams(-2, -2));

        nameV = Ui.text(s, 34, 700, Color.WHITE);
        nameV.setText(name);
        nameV.setGravity(Gravity.CENTER);
        nameV.setMaxLines(2);
        nameV.setShadowLayer(dp(6), 0, dp(1), Color.argb(120, 0, 0, 0));
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(-1, -2);
        nl.topMargin = dp(22);
        col.addView(nameV, nl);
        subV = Ui.text(s, 16, 500, Color.argb(220, 255, 255, 255));
        subV.setText(subtitle);
        subV.setGravity(Gravity.CENTER);
        subV.setPadding(0, dp(8), 0, 0);
        col.addView(subV, new LinearLayout.LayoutParams(-1, -2));

        // avatar + cincin berdenyut
        FrameLayout av = new FrameLayout(s);
        int as = dp(136);
        for (int i = 0; i < 3; i++) {
            View ring = new View(s);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setStroke(dp(2), Color.argb(150, 255, 255, 255));
            ring.setBackground(g);
            av.addView(ring, new FrameLayout.LayoutParams(as, as, Gravity.CENTER));
            ObjectAnimator a = ObjectAnimator.ofPropertyValuesHolder(ring,
                    PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.9f),
                    PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.9f),
                    PropertyValuesHolder.ofFloat(View.ALPHA, 0.7f, 0f));
            a.setDuration(2400);
            a.setStartDelay(i * 800L);
            a.setRepeatCount(ValueAnimator.INFINITE);
            a.setInterpolator(new android.view.animation.DecelerateInterpolator());
            a.start();
        }
        avatar = new ImageView(s);
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        if (ph != null) avatar.setImageBitmap(ph);
        else {
            avatar.setImageDrawable(Ui.iconDrawable(s, Icon.PHONE, Color.WHITE, 0, dp(56)));
            avatar.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        }
        LiquidGlass.apply(avatar, 68, false);
        avatar.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline o) {
                o.setOval(0, 0, view.getWidth(), view.getHeight());
            }
        });
        avatar.setClipToOutline(true);
        av.addView(avatar, new FrameLayout.LayoutParams(as, as, Gravity.CENTER));
        LinearLayout.LayoutParams avl = new LinearLayout.LayoutParams(-1, 0, 1f);
        col.addView(av, avl);

        // butang tolak
        LinearLayout dcol = new LinearLayout(s);
        dcol.setOrientation(LinearLayout.VERTICAL);
        dcol.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout dec = new FrameLayout(s);
        dec.setBackground(new LiquidGlass.Surface(s, dp(36), false, bd, 0xFFFF3B30));
        Icon hang = new Icon(s, Icon.PHONE, Color.WHITE);
        hang.setRotation(135);
        dec.addView(hang, new FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER));
        dec.setElevation(dp(4));
        dec.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline o) {
                o.setOval(0, 0, view.getWidth(), view.getHeight());
            }
        });
        dec.setOnClickListener(v -> act(false));
        Ui.press(dec);
        dcol.addView(dec, new LinearLayout.LayoutParams(dp(72), dp(72)));
        TextView dl = Ui.text(s, 13, 600, Color.WHITE);
        dl.setText(R.string.call_decline);
        dl.setPadding(0, dp(8), 0, 0);
        dcol.addView(dl);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-2, -2);
        dlp.bottomMargin = dp(26);
        col.addView(dcol, dlp);

        // leret untuk jawab
        col.addView(slider(), new LinearLayout.LayoutParams(-1, dp(76)));
        LiquidGlass.current = prev;

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        lp.setTitle("GlassKitCall");
        try {
            s.wm.addView(root, lp);
        } catch (Exception e) {
            root = null;
            key = null;
            return;
        }
        root.setAlpha(0f);
        root.animate().alpha(1f).setDuration(260).start();
        av.setScaleX(0.6f);
        av.setScaleY(0.6f);
        av.animate().scaleX(1f).scaleY(1f).setInterpolator(Ui.SPRING).setDuration(700).start();
        col.setTranslationY(dp(40));
        col.animate().translationY(0).setInterpolator(Ui.SPRING).setDuration(700).start();
    }

    // ======================= Kad kaca terapung =======================

    private View card;

    private void buildCard(CharSequence name, String subtitle, Drawable photo, String pkg) {
        final boolean dark = Ui.darkGlass(s);
        final int ink = dark ? Color.WHITE : 0xFF14161A, ink2 = dark ? Color.argb(190, 235, 235, 245) : 0xFF4B5059;
        root = new FrameLayout(s);
        Bitmap ph = toBitmap(photo, dp(160));
        Bitmap wall = DepthRenderer.load(DepthRenderer.bgFile(s));
        if (wall != null) bd.set(wall, s.screenW, s.screenH);

        LinearLayout c = new LinearLayout(s);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        c.setPadding(dp(20), dp(16), dp(20), dp(20));
        c.setBackground(new LiquidGlass.Surface(s, dp(34), dark, bd, 0));
        c.setElevation(dp(10));
        c.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline o) { o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(34)); }
        });
        card = c;

        // baris app
        LinearLayout top = new LinearLayout(s);
        top.setGravity(Gravity.CENTER_VERTICAL);
        ImageView ai = new ImageView(s);
        try { ai.setImageDrawable(s.getPackageManager().getApplicationIcon(pkg)); } catch (Exception ignored) {}
        top.addView(ai, new LinearLayout.LayoutParams(dp(18), dp(18)));
        TextView at = Ui.line(s, 13, 600, ink2);
        at.setText(appName(pkg) + " · " + s.getString(R.string.incoming_call));
        at.setPadding(dp(7), 0, 0, 0);
        top.addView(at);
        c.addView(top);

        // avatar dengan cincin
        FrameLayout av = new FrameLayout(s);
        int as = dp(84);
        for (int i = 0; i < 2; i++) {
            View ring = new View(s);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setStroke(dp(1.5f), dark ? Color.argb(150, 255, 255, 255) : Color.argb(120, 20, 22, 26));
            ring.setBackground(g);
            av.addView(ring, new FrameLayout.LayoutParams(as, as, Gravity.CENTER));
            ObjectAnimator a = ObjectAnimator.ofPropertyValuesHolder(ring,
                    PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.45f),
                    PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.45f),
                    PropertyValuesHolder.ofFloat(View.ALPHA, 0.8f, 0f));
            a.setDuration(2000);
            a.setStartDelay(i * 1000L);
            a.setRepeatCount(ValueAnimator.INFINITE);
            a.start();
        }
        ImageView avatar = new ImageView(s);
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        if (ph != null) avatar.setImageBitmap(ph);
        else avatar.setImageDrawable(Ui.iconDrawable(s, Icon.PHONE, Color.WHITE, 0xFF636366, dp(84)));
        avatar.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline o) { o.setOval(0, 0, view.getWidth(), view.getHeight()); }
        });
        avatar.setClipToOutline(true);
        av.addView(avatar, new FrameLayout.LayoutParams(as, as, Gravity.CENTER));
        LinearLayout.LayoutParams avl = new LinearLayout.LayoutParams(dp(124), dp(124));
        avl.topMargin = dp(4);
        c.addView(av, avl);

        nameV = Ui.text(s, 24, 700, ink);
        nameV.setText(name);
        nameV.setGravity(Gravity.CENTER);
        nameV.setMaxLines(1);
        nameV.setEllipsize(TextUtils.TruncateAt.END);
        c.addView(nameV, new LinearLayout.LayoutParams(-1, -2));
        subV = Ui.text(s, 14, 500, ink2);
        subV.setText(subtitle);
        subV.setGravity(Gravity.CENTER);
        subV.setMaxLines(1);
        subV.setPadding(0, dp(3), 0, 0);
        c.addView(subV, new LinearLayout.LayoutParams(-1, -2));

        // butang
        LinearLayout btns = new LinearLayout(s);
        btns.setGravity(Gravity.CENTER);
        btns.addView(cardBtn(0xFFFF3B30, true, R.string.call_decline, ink2, v -> act(false)));
        View gap = new View(s);
        btns.addView(gap, new LinearLayout.LayoutParams(dp(64), 1));
        LinearLayout acc = cardBtn(0xFF30D158, false, R.string.call_accept, ink2, v -> act(true));
        btns.addView(acc);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, -2);
        bl.topMargin = dp(18);
        c.addView(btns, bl);
        pulse(acc.getChildAt(0));

        TextView hint = Ui.text(s, 11, 500, ink2);
        hint.setText(R.string.call_card_hint);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(12), 0, 0);
        c.addView(hint, new LinearLayout.LayoutParams(-1, -2));

        // leret ke atas: kecilkan ke island
        c.setOnTouchListener(new View.OnTouchListener() {
            float y0;
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN: y0 = e.getRawY(); return true;
                    case MotionEvent.ACTION_MOVE:
                        v.setTranslationY(Math.min(0, e.getRawY() - y0));
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (e.getRawY() - y0 < -dp(60)) minimize();
                        else v.animate().translationY(0).setInterpolator(Ui.SPRING).setDuration(400).start();
                        return true;
                }
                return false;
            }
        });

        int w = Math.min(s.screenW - dp(28), dp(420));
        root.addView(c, new FrameLayout.LayoutParams(w, -2, Gravity.CENTER_HORIZONTAL));
        root.setPadding(0, dp(12), 0, dp(24));
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP;
        lp.y = s.statusH + dp(8);
        lp.setTitle("GlassKitCallCard");
        try {
            s.wm.addView(root, lp);
        } catch (Exception e) {
            root = null;
            key = null;
            card = null;
            return;
        }
        c.setAlpha(0f);
        c.setScaleX(0.85f);
        c.setScaleY(0.85f);
        c.setTranslationY(-dp(50));
        c.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0).setInterpolator(Ui.SPRING).setDuration(600).start();
        refractFromScreen();
    }

    private LinearLayout cardBtn(int color, boolean hang, int label, int ink, View.OnClickListener l) {
        LinearLayout col = new LinearLayout(s);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout b = new FrameLayout(s);
        b.setBackground(Ui.circle(color));
        Icon ic = new Icon(s, Icon.PHONE, Color.WHITE);
        if (hang) ic.setRotation(135);
        b.addView(ic, new FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER));
        b.setElevation(dp(4));
        b.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline o) { o.setOval(0, 0, view.getWidth(), view.getHeight()); }
        });
        b.setOnClickListener(l);
        Ui.press(b);
        col.addView(b, new LinearLayout.LayoutParams(dp(64), dp(64)));
        TextView t = Ui.text(s, 12, 600, ink);
        t.setText(label);
        t.setPadding(0, dp(6), 0, 0);
        col.addView(t);
        return col;
    }

    private void pulse(View v) {
        if (v == null || root == null) return;
        v.animate().scaleX(1.1f).scaleY(1.1f).setDuration(520).withEndAction(() ->
                v.animate().scaleX(1f).scaleY(1f).setDuration(520).withEndAction(() -> pulse(v)).start()).start();
    }

    /** Biasan sebenar: tangkap skrin di belakang kad (jika boleh). */
    private void refractFromScreen() {
        try {
            s.takeScreenshot(android.view.Display.DEFAULT_DISPLAY, s.getMainExecutor(),
                    new android.accessibilityservice.AccessibilityService.TakeScreenshotCallback() {
                        @Override
                        public void onSuccess(android.accessibilityservice.AccessibilityService.ScreenshotResult r) {
                            new Thread(() -> {
                                Bitmap sw = null;
                                try {
                                    Bitmap hw = Bitmap.wrapHardwareBuffer(r.getHardwareBuffer(), r.getColorSpace());
                                    if (hw != null) sw = hw.copy(Bitmap.Config.ARGB_8888, false);
                                } catch (Throwable ignored) {
                                } finally {
                                    try { r.getHardwareBuffer().close(); } catch (Throwable ignored) {}
                                }
                                if (sw == null) return;
                                LiquidGlass.Backdrop nb = new LiquidGlass.Backdrop();
                                nb.set(sw, s.screenW, s.screenH);
                                s.h.post(() -> {
                                    bd.sharp = nb.sharp;
                                    bd.soft = nb.soft;
                                    bd.screenW = nb.screenW;
                                    bd.screenH = nb.screenH;
                                    bd.luminance = nb.luminance;
                                    if (card != null) card.invalidate();
                                });
                            }, "gk-callshot").start();
                        }

                        @Override
                        public void onFailure(int code) { }
                    });
        } catch (Throwable ignored) {
        }
    }

    /** Kecilkan kad ke Dynamic Island (panggilan masih berdering di island). */
    private void minimize() {
        s.expandCallIsland();
        hide(true);
    }

    /** Trek kaca "leret untuk jawab" dengan tombol hijau dan teks berkilau. */
    private View slider() {
        final FrameLayout track = new FrameLayout(s);
        track.setBackground(new LiquidGlass.Surface(s, dp(38), false, bd, 0));
        final TextView label = Ui.text(s, 17, 600, Color.WHITE);
        label.setText(R.string.call_slide);
        label.setGravity(Gravity.CENTER);
        label.setPadding(dp(70), 0, dp(10), 0);
        track.addView(label, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        ObjectAnimator shimmer = ObjectAnimator.ofFloat(label, View.ALPHA, 1f, 0.35f, 1f);
        shimmer.setDuration(1800);
        shimmer.setRepeatCount(ValueAnimator.INFINITE);
        shimmer.setInterpolator(new LinearInterpolator());
        shimmer.start();

        final FrameLayout knob = new FrameLayout(s);
        knob.setBackground(new LiquidGlass.Surface(s, dp(32), false, bd, 0xFF34C759));
        knob.addView(new Icon(s, Icon.PHONE, Color.WHITE), new FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER));
        knob.setElevation(dp(4));
        knob.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline o) {
                o.setOval(0, 0, view.getWidth(), view.getHeight());
            }
        });
        FrameLayout.LayoutParams kl = new FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER_VERTICAL | Gravity.START);
        kl.leftMargin = dp(6);
        track.addView(knob, kl);
        // isyarat kecil: tombol bergoyang ke kanan
        ObjectAnimator nudge = ObjectAnimator.ofFloat(knob, View.TRANSLATION_X, 0f, dp(14), 0f);
        nudge.setDuration(900);
        nudge.setStartDelay(600);
        nudge.setRepeatCount(ValueAnimator.INFINITE);
        nudge.setRepeatMode(ValueAnimator.RESTART);
        nudge.start();

        final float[] x0 = new float[1];
        knob.setOnTouchListener((v, e) -> {
            float max = track.getWidth() - knob.getWidth() - dp(12);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    nudge.cancel();
                    x0[0] = e.getRawX() - v.getTranslationX();
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float t = Math.max(0, Math.min(max, e.getRawX() - x0[0]));
                    v.setTranslationX(t);
                    label.setAlpha(1f - t / Math.max(1, max));
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (v.getTranslationX() > max * 0.8f) {
                        v.animate().translationX(max).setDuration(120).withEndAction(() -> act(true)).start();
                    } else {
                        v.animate().translationX(0).setInterpolator(Ui.SPRING).setDuration(500).start();
                        label.animate().alpha(1f).setDuration(300).start();
                    }
                    return true;
            }
            return false;
        });
        return track;
    }

    private void act(boolean accept) {
        PendingIntent pi = accept ? answer : decline;
        if (pi != null) {
            try {
                ActivityOptions o = ActivityOptions.makeBasic();
                if (Build.VERSION.SDK_INT >= 34) {
                    o.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
                }
                pi.send(s, 0, null, null, null, null, o.toBundle());
            } catch (Exception ignored) {
            }
        }
        if (accept && LockActivity.inst != null) LockActivity.inst.finish();
        hide(true);
    }

    void hide(boolean fade) {
        final FrameLayout r = root;
        root = null;
        key = null;
        final View cd = card;
        card = null;
        if (r == null) return;
        if (fade && cd != null) {
            cd.animate().alpha(0f).scaleX(0.4f).scaleY(0.25f).translationY(-dp(120)).setDuration(320).withEndAction(() -> {
                try { s.wm.removeViewImmediate(r); } catch (Exception ignored) {}
            }).start();
        } else if (fade) {
            r.animate().alpha(0f).setDuration(220).withEndAction(() -> {
                try { s.wm.removeViewImmediate(r); } catch (Exception ignored) {}
            }).start();
        } else {
            try { s.wm.removeViewImmediate(r); } catch (Exception ignored) {}
        }
    }
}
