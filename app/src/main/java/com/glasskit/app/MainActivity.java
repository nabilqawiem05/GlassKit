package com.glasskit.app;

import android.app.Activity;
import android.app.NotificationManager;
import android.app.WallpaperManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation;
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenter;
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions;

import java.io.File;
import java.io.FileOutputStream;

import rikka.shizuku.Shizuku;

public class MainActivity extends Activity {

    private static final int REQ_BG = 1, REQ_FG = 2;
    private static final int[] COLORS = {
            Color.WHITE, 0xFF8AB4F8, 0xFFC8B6FF, 0xFFF5D06F, 0xFFFF8A80, 0xFF101418
    };
    private static final int TEXT2 = Color.argb(165, 235, 235, 245);

    private SharedPreferences prefs;
    private ImageView preview;
    private TextView status;
    private TextView stShizuku, stAcc, stNotif;
    private Bitmap cacheBg, cacheFg;
    private StatusBar statusPreview;
    private long cacheVer = Long.MIN_VALUE;

    private final Shizuku.OnRequestPermissionResultListener shizukuL = (code, result) -> {
        if (code == Shell.REQ && result == PackageManager.PERMISSION_GRANTED) grantAll();
        refreshStatus();
    };

    private int dp(float v) { return Ui.dp(this, v); }

    // ======================= Susun atur =======================

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = Prefs.get(this);
        getWindow().setStatusBarColor(0xFF07080F);
        getWindow().setNavigationBarColor(0xFF0B1424);

        ScrollView sv = new ScrollView(this);
        sv.setFitsSystemWindows(true);
        sv.setVerticalScrollBarEnabled(false);
        sv.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF07080F, 0xFF15102A, 0xFF0B1424}));
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(28), dp(18), dp(48));
        sv.addView(box);
        setContentView(sv);

        TextView h = Ui.text(this, 38, 800, Color.WHITE);
        h.setText(R.string.app_name);
        h.setLetterSpacing(-0.02f);
        box.addView(h);
        TextView tag = Ui.text(this, 15, 400, TEXT2);
        tag.setText(R.string.app_tagline);
        tag.setPadding(dp(2), dp(8), 0, dp(8));
        box.addView(tag);

        // ---------- Kebenaran ----------
        LinearLayout perm = card(box, R.string.sec_perm, Icon.LOCK, 0xFF34C759);
        stShizuku = statusRow(perm, getString(R.string.perm_shizuku));
        stAcc = statusRow(perm, getString(R.string.perm_acc));
        stNotif = statusRow(perm, getString(R.string.perm_notif));
        button(perm, getString(R.string.btn_grant_all), true, v -> {
            if (!Shell.running()) toast(getString(R.string.shizuku_hint));
            else if (!Shell.granted()) Shell.request();
            else grantAll();
        });
        button(perm, getString(R.string.copy_cmds), false, v -> {
            ClipboardManager cm = getSystemService(ClipboardManager.class);
            cm.setPrimaryClip(ClipData.newPlainText("aShell", Shell.grantAll(getPackageName())));
            toast(getString(R.string.copied));
        });

        // ---------- Island ----------
        LinearLayout isl = card(box, R.string.sec_island, Icon.NOTE, 0xFFFF375F);
        toggle(isl, R.string.island_on, Prefs.PILL_ON, true);
        toggle(isl, R.string.island_music, Prefs.PILL_MUSIC, true);
        toggle(isl, R.string.island_noti, Prefs.PILL_NOTI, true);
        button(isl, getString(R.string.btn_test), false, v -> {
            if (PillService.inst != null) PillService.inst.test();
            else toast(getString(R.string.need_acc));
        });

        // ---------- Lock screen ----------
        LinearLayout lk = card(box, R.string.sec_lock, Icon.LOCK, 0xFFFF9F0A);
        toggle(lk, R.string.lock_on, Prefs.LOCK_ON, false);
        note(lk, getString(R.string.lock_note));
        button(lk, getString(R.string.btn_preview_lock), false, v -> startActivity(new Intent(this, LockActivity.class)));

        // ---------- Status bar ----------
        LinearLayout sb = card(box, R.string.sec_sbar, Icon.BATTERY, 0xFF30D158);
        FrameLayout sbPrev = new FrameLayout(this);
        sbPrev.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xFF3A6EA5, 0xFF7A5C8E, 0xFFC98A5A}));
        sbPrev.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline o) {
                o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(18));
            }
        });
        sbPrev.setClipToOutline(true);
        statusPreview = new StatusBar(this);
        statusPreview.setGeometry(dp(26), -1, dp(16));
        sbPrev.addView(statusPreview, new FrameLayout.LayoutParams(-1, -1));
        View cam = new View(this);
        cam.setBackground(Ui.circle(Color.BLACK));
        sbPrev.addView(cam, new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
        LinearLayout.LayoutParams spl = new LinearLayout.LayoutParams(-1, dp(52));
        spl.topMargin = dp(14);
        sb.addView(sbPrev, spl);
        toggle(sb, R.string.sbar_on, Prefs.SBAR_ON, false);
        toggle(sb, R.string.sbar_pct, Prefs.SBAR_PCT, true);
        label(sb, R.string.sbar_style);
        segmented(sb, new int[]{R.string.sbar_capsule, R.string.sbar_minimal, R.string.sbar_island},
                prefs.getInt(Prefs.SBAR_STYLE, 0), i -> prefs.edit().putInt(Prefs.SBAR_STYLE, i).apply());
        LinearLayout sr = new LinearLayout(this);
        sb.addView(sr);
        addPair(sr, button(null, getString(R.string.btn_hide_sys), false, v -> runShell(
                "settings put secure icon_blacklist clock,battery,wifi,mobile,airplane,rotate,headset,alarm_clock,"
                        + "nfc,hotspot,zen,volume,cast,bluetooth,vpn,data_saver,location,managed_profile,ethernet,ims,"
                        + "call_strength,mobile_data,speakerphone,dnd")),
                button(null, getString(R.string.btn_restore_sys), false, v -> runShell("settings delete secure icon_blacklist")));
        note(sb, getString(R.string.sbar_note));

        // ---------- Panel ----------
        LinearLayout pan = card(box, R.string.sec_panels, Icon.GEAR, Ui.BLUE);
        toggle(pan, R.string.panels_on, Prefs.PANELS_ON, true);
        label(pan, R.string.glass_style);
        segmented(pan, new int[]{R.string.glass_clear, R.string.glass_dark},
                prefs.getBoolean(Prefs.GLASS_DARK, false) ? 1 : 0, i -> {
                    prefs.edit().putBoolean(Prefs.GLASS_DARK, i == 1).apply();
                    GlassWidget.refreshAll(this);
                });
        note(pan, getString(R.string.panels_hint));
        LinearLayout pr = new LinearLayout(this);
        pan.addView(pr);
        View b1 = button(null, getString(R.string.btn_open_cc), false, v -> {
            if (PillService.inst != null && PillService.inst.panels != null) PillService.inst.panels.openControl();
            else toast(getString(R.string.need_acc));
        });
        View b2 = button(null, getString(R.string.btn_open_nc), false, v -> {
            if (PillService.inst != null && PillService.inst.panels != null) PillService.inst.panels.openNotifications();
            else toast(getString(R.string.need_acc));
        });
        LinearLayout.LayoutParams l1 = new LinearLayout.LayoutParams(0, dp(50), 1f);
        l1.topMargin = dp(12);
        LinearLayout.LayoutParams l2 = new LinearLayout.LayoutParams(0, dp(50), 1f);
        l2.topMargin = dp(12);
        l2.leftMargin = dp(10);
        pr.addView(b1, l1);
        pr.addView(b2, l2);

        // ---------- Jam depth ----------
        LinearLayout clk = card(box, R.string.sec_clock, Icon.SUN, Ui.ORANGE);
        preview = new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        preview.setBackgroundColor(0xFF1C2230);
        preview.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline o) {
                o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(26));
            }
        });
        preview.setClipToOutline(true);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(dp(230), dp(480));
        pl.gravity = Gravity.CENTER_HORIZONTAL;
        pl.topMargin = dp(14);
        clk.addView(preview, pl);
        status = note(clk, "");
        status.setGravity(Gravity.CENTER);

        LinearLayout ir1 = new LinearLayout(this);
        clk.addView(ir1);
        addPair(ir1, button(null, getString(R.string.btn_pick_wp), false, v -> pick(REQ_BG)),
                button(null, getString(R.string.btn_auto_cut), false, v -> autoCut()));
        LinearLayout ir2 = new LinearLayout(this);
        clk.addView(ir2);
        addPair(ir2, button(null, getString(R.string.btn_own_png), false, v -> pick(REQ_FG)),
                button(null, getString(R.string.btn_remove_subject), false, v -> {
                    DepthRenderer.fgFile(this).delete();
                    bump();
                }));

        label(clk, R.string.lbl_size);
        slider(clk, 10, 45, prefs.getInt(Prefs.CLOCK_SIZE, 30), val -> prefs.edit().putInt(Prefs.CLOCK_SIZE, val).apply());
        label(clk, R.string.lbl_pos);
        slider(clk, 12, 70, prefs.getInt(Prefs.CLOCK_Y, 28), val -> prefs.edit().putInt(Prefs.CLOCK_Y, val).apply());

        label(clk, R.string.lbl_color);
        LinearLayout cr = new LinearLayout(this);
        cr.setGravity(Gravity.CENTER_VERTICAL);
        clk.addView(cr);
        for (int c : COLORS) {
            View chip = new View(this);
            GradientDrawable g = Ui.circle(c);
            g.setStroke(dp(1.5f), Color.argb(90, 255, 255, 255));
            chip.setBackground(g);
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(dp(38), dp(38));
            l.rightMargin = dp(12);
            l.topMargin = dp(4);
            chip.setOnClickListener(v -> prefs.edit().putInt(Prefs.CLOCK_COLOR, c).apply());
            Ui.press(chip);
            cr.addView(chip, l);
        }

        label(clk, R.string.lbl_font);
        segmented(clk, new int[]{R.string.font_bold, R.string.font_thin, R.string.font_serif, R.string.font_mono},
                prefs.getInt(Prefs.CLOCK_FONT, 0), i -> prefs.edit().putInt(Prefs.CLOCK_FONT, i).apply());

        label(clk, R.string.clock_style);
        segmented(clk, new int[]{R.string.style_glass, R.string.style_solid},
                prefs.getBoolean(Prefs.CLOCK_GLASS, true) ? 0 : 1,
                i -> prefs.edit().putBoolean(Prefs.CLOCK_GLASS, i == 0).apply());

        label(clk, R.string.lbl_width);
        slider(clk, 60, 170, prefs.getInt(Prefs.CLOCK_W, 100), val -> prefs.edit().putInt(Prefs.CLOCK_W, val).apply());
        label(clk, R.string.lbl_height);
        slider(clk, 60, 240, prefs.getInt(Prefs.CLOCK_H, 100), val -> prefs.edit().putInt(Prefs.CLOCK_H, val).apply());
        button(clk, getString(R.string.btn_auto_pos), false, v -> autoPlace(true));

        toggle(clk, R.string.hide_stock, Prefs.HIDE_STOCK, false);
        label(clk, R.string.lbl_hide_h);
        slider(clk, 60, 260, prefs.getInt(Prefs.HIDE_H, 130), val -> prefs.edit().putInt(Prefs.HIDE_H, val).apply());
        label(clk, R.string.lbl_patch_dim);
        slider(clk, 0, 70, prefs.getInt(Prefs.PATCH_DIM, 22), val -> prefs.edit().putInt(Prefs.PATCH_DIM, val).apply());
        note(clk, getString(R.string.hide_stock_note));

        toggle(clk, R.string.lock_only, Prefs.LOCK_ONLY, true);
        button(clk, getString(R.string.btn_set_wp), true, v -> setWallpaper());
        note(clk, getString(R.string.clock_tip));

        LinearLayout wg = card(box, R.string.sec_widgets, Icon.NOTE, Ui.PURPLE);
        note(wg, getString(R.string.widgets_hint));
        button(wg, getString(R.string.btn_refresh_widgets), false, v -> GlassWidget.refreshAll(this));
        note(wg, getString(R.string.themed_hint));
        button(wg, getString(R.string.btn_open_icons), true, v -> startActivity(new Intent(this, IconsActivity.class)));

        prefs.registerOnSharedPreferenceChangeListener(prefL);
        try { Shizuku.addRequestPermissionResultListener(shizukuL); } catch (Throwable ignored) {}
        renderPreview();
    }

    private final SharedPreferences.OnSharedPreferenceChangeListener prefL = (sp, k) -> {
        renderPreview();
        if (statusPreview != null) statusPreview.refresh();
    };

    private void runShell(String cmd) {
        if (!Shell.running()) { toast(getString(R.string.shizuku_hint)); return; }
        if (!Shell.granted()) { Shell.request(); return; }
        Shell.run(cmd, (ok, out) -> toast(ok ? getString(R.string.done) : out.trim()));
    }

    @Override
    protected void onDestroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefL);
        try { Shizuku.removeRequestPermissionResultListener(shizukuL); } catch (Throwable ignored) {}
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    // ======================= Komponen =======================

    private LinearLayout card(LinearLayout parent, int title, int icon, int color) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(Ui.glass(this, 28, 20));
        c.setPadding(dp(18), dp(18), dp(18), dp(20));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(16);
        parent.addView(c, lp);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout ib = new FrameLayout(this);
        ib.setBackground(Ui.round(color, dp(9)));
        ib.addView(new Icon(this, icon, Color.WHITE), new FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER));
        head.addView(ib, new LinearLayout.LayoutParams(dp(30), dp(30)));
        TextView t = Ui.text(this, 19, 700, Color.WHITE);
        t.setText(title);
        t.setPadding(dp(12), 0, 0, 0);
        head.addView(t);
        c.addView(head);
        return c;
    }

    private TextView statusRow(LinearLayout parent, String label) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(14), 0, 0);
        TextView l = Ui.text(this, 16, 500, Color.WHITE);
        l.setText(label);
        r.addView(l, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView st = Ui.text(this, 13, 600, Color.WHITE);
        st.setPadding(dp(12), dp(6), dp(12), dp(6));
        r.addView(st);
        parent.addView(r);
        return st;
    }

    private void setStatus(TextView t, String text, boolean ok) {
        t.setText(text);
        t.setTextColor(ok ? 0xFF30D158 : 0xFFFF9F0A);
        t.setBackground(Ui.round(ok ? Color.argb(40, 48, 209, 88) : Color.argb(40, 255, 159, 10), dp(14)));
    }

    private View button(LinearLayout parent, String text, boolean primary, View.OnClickListener l) {
        TextView bt = Ui.text(this, 15, 600, primary ? 0xFF0B0B10 : Color.WHITE);
        bt.setText(text);
        bt.setGravity(Gravity.CENTER);
        bt.setMaxLines(2);
        bt.setPadding(dp(12), 0, dp(12), 0);
        bt.setBackground(primary ? Ui.round(Color.WHITE, dp(25)) : Ui.glass(this, 25, 34));
        bt.setOnClickListener(l);
        Ui.press(bt);
        if (parent != null) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(50));
            lp.topMargin = dp(12);
            parent.addView(bt, lp);
        }
        return bt;
    }

    private void addPair(LinearLayout row, View a, View b) {
        LinearLayout.LayoutParams l1 = new LinearLayout.LayoutParams(0, dp(50), 1f);
        l1.topMargin = dp(12);
        LinearLayout.LayoutParams l2 = new LinearLayout.LayoutParams(0, dp(50), 1f);
        l2.topMargin = dp(12);
        l2.leftMargin = dp(10);
        row.addView(a, l1);
        row.addView(b, l2);
    }

    private TextView note(LinearLayout parent, String s) {
        TextView t = Ui.text(this, 14, 400, TEXT2);
        t.setText(s);
        t.setLineSpacing(0, 1.2f);
        t.setPadding(0, dp(10), 0, 0);
        parent.addView(t);
        return t;
    }

    private void label(LinearLayout parent, int res) {
        TextView t = Ui.text(this, 13, 600, TEXT2);
        t.setText(res);
        t.setPadding(dp(2), dp(18), 0, dp(6));
        parent.addView(t);
    }

    private void toggle(LinearLayout parent, int label, String key, boolean def) {
        Switch sw = new Switch(this);
        sw.setText(label);
        sw.setTextSize(16);
        sw.setTextColor(Color.WHITE);
        sw.setTypeface(Ui.font(500));
        sw.setChecked(prefs.getBoolean(key, def));
        int[][] st = {{android.R.attr.state_checked}, {}};
        sw.setThumbTintList(new ColorStateList(st, new int[]{Color.WHITE, Color.rgb(200, 200, 210)}));
        sw.setTrackTintList(new ColorStateList(st, new int[]{0xFF30D158, Color.argb(90, 255, 255, 255)}));
        sw.setPadding(0, dp(12), 0, 0);
        sw.setOnCheckedChangeListener((v, on) -> prefs.edit().putBoolean(key, on).apply());
        parent.addView(sw, new LinearLayout.LayoutParams(-1, -2));
    }

    interface IntCb { void on(int v); }

    private void slider(LinearLayout parent, int min, int max, int cur, IntCb cb) {
        SeekBar s = new SeekBar(this);
        s.setMin(min);
        s.setMax(max);
        s.setProgress(cur);
        s.setProgressTintList(ColorStateList.valueOf(Color.WHITE));
        s.setThumbTintList(ColorStateList.valueOf(Color.WHITE));
        s.setProgressBackgroundTintList(ColorStateList.valueOf(Color.argb(120, 255, 255, 255)));
        s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) { if (fromUser) cb.on(p); }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) { }
        });
        parent.addView(s, new LinearLayout.LayoutParams(-1, dp(36)));
    }

    private void segmented(LinearLayout parent, int[] labels, int selected, IntCb cb) {
        final LinearLayout seg = new LinearLayout(this);
        seg.setBackground(Ui.glass(this, 22, 26));
        seg.setPadding(dp(4), dp(4), dp(4), dp(4));
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            TextView t = Ui.text(this, 14, 600, Color.WHITE);
            t.setText(labels[i]);
            t.setGravity(Gravity.CENTER);
            t.setOnClickListener(v -> {
                paint(seg, idx);
                cb.on(idx);
            });
            seg.addView(t, new LinearLayout.LayoutParams(0, dp(38), 1f));
        }
        parent.addView(seg, new LinearLayout.LayoutParams(-1, -2));
        paint(seg, selected);
    }

    private void paint(LinearLayout seg, int sel) {
        for (int i = 0; i < seg.getChildCount(); i++) {
            TextView t = (TextView) seg.getChildAt(i);
            boolean on = i == sel;
            t.setBackground(on ? Ui.round(Color.WHITE, dp(19)) : null);
            t.setTextColor(on ? 0xFF0B0B10 : Color.WHITE);
        }
    }

    private void autoPlace(boolean say) {
        Bitmap fg = DepthRenderer.load(DepthRenderer.fgFile(this));
        android.graphics.Rect b = getSystemService(android.view.WindowManager.class).getCurrentWindowMetrics().getBounds();
        if (DepthRenderer.autoPlace(this, prefs, fg, b.width(), b.height())) {
            if (say) status.setText(R.string.st_auto_done);
        } else if (say) {
            status.setText(R.string.st_need_subject);
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ======================= Kebenaran =======================

    private void refreshStatus() {
        if (!Shell.running()) setStatus(stShizuku, getString(R.string.st_not_running), false);
        else if (!Shell.granted()) setStatus(stShizuku, getString(R.string.st_no_permission), false);
        else setStatus(stShizuku, getString(R.string.st_on), true);

        String acc = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        boolean a = acc != null && acc.contains(getPackageName() + "/");
        setStatus(stAcc, getString(a ? R.string.st_on : R.string.st_off), a);

        NotificationManager nm = getSystemService(NotificationManager.class);
        boolean n = nm.isNotificationListenerAccessGranted(new ComponentName(this, NotifListener.class));
        setStatus(stNotif, getString(n ? R.string.st_on : R.string.st_off), n);
    }

    private void grantAll() {
        Shell.run(Shell.grantAll(getPackageName()), (ok, out) -> {
            if (ok) toast(getString(R.string.grant_done));
            else toast(getString(R.string.grant_fail, out.trim()));
            stAcc.postDelayed(this::refreshStatus, 900);
        });
    }

    // ======================= Gambar =======================

    private void pick(int req) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        startActivityForResult(i, req);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            Bitmap bmp = decode(uri);
            if (req == REQ_BG) {
                save(bmp, DepthRenderer.bgFile(this), false);
                DepthRenderer.fgFile(this).delete();
                status.setText(R.string.st_wp_picked);
            } else {
                save(bmp, DepthRenderer.fgFile(this), true);
                status.setText(R.string.st_png_set);
            }
            bump();
        } catch (Exception e) {
            status.setText(getString(R.string.st_open_fail, String.valueOf(e.getMessage())));
        }
    }

    private Bitmap decode(Uri uri) throws Exception {
        ImageDecoder.Source src = ImageDecoder.createSource(getContentResolver(), uri);
        return ImageDecoder.decodeBitmap(src, (decoder, info, s) -> {
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            int w = info.getSize().getWidth(), h = info.getSize().getHeight();
            int max = 2400;
            if (Math.max(w, h) > max) {
                float sc = (float) max / Math.max(w, h);
                decoder.setTargetSize(Math.round(w * sc), Math.round(h * sc));
            }
        });
    }

    private void save(Bitmap bmp, File f, boolean png) throws Exception {
        try (FileOutputStream out = new FileOutputStream(f)) {
            bmp.compress(png ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG, png ? 100 : 95, out);
        }
    }

    private void bump() {
        prefs.edit().putLong(Prefs.IMG_VER, System.currentTimeMillis()).apply();
    }

    private void autoCut() {
        Bitmap bg = DepthRenderer.load(DepthRenderer.bgFile(this));
        if (bg == null) {
            toast(getString(R.string.st_pick_first));
            return;
        }
        status.setText(R.string.st_cutting);
        SubjectSegmenterOptions opts = new SubjectSegmenterOptions.Builder()
                .enableForegroundBitmap()
                .build();
        SubjectSegmenter seg = SubjectSegmentation.getClient(opts);
        Bitmap argb = bg.copy(Bitmap.Config.ARGB_8888, false);
        seg.process(InputImage.fromBitmap(argb, 0))
                .addOnSuccessListener(result -> {
                    Bitmap fg = result.getForegroundBitmap();
                    if (fg == null) {
                        status.setText(R.string.st_no_subject);
                        return;
                    }
                    try {
                        save(fg, DepthRenderer.fgFile(this), true);
                        status.setText(R.string.st_cut_done);
                        bump();
                        autoPlace(false);
                    } catch (Exception e) {
                        status.setText(getString(R.string.st_save_fail, String.valueOf(e.getMessage())));
                    }
                })
                .addOnFailureListener(e -> status.setText(getString(R.string.st_cut_fail, String.valueOf(e.getMessage()))));
    }

    private void renderPreview() {
        if (preview == null) return;
        int w = 540, h = Math.round(540f * getResources().getDisplayMetrics().heightPixels
                / getResources().getDisplayMetrics().widthPixels);
        long ver = prefs.getLong(Prefs.IMG_VER, 0);
        if (ver != cacheVer) {
            cacheVer = ver;
            cacheBg = DepthRenderer.load(DepthRenderer.bgFile(this));
            cacheFg = DepthRenderer.load(DepthRenderer.fgFile(this));
        }
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        DepthRenderer.draw(this, c, w, h, cacheBg, cacheFg, prefs, true);
        preview.setImageBitmap(out);
        if (TextUtils.isEmpty(status.getText()) && !DepthRenderer.bgFile(this).exists()) {
            status.setText(R.string.st_start);
        }
    }

    private void setWallpaper() {
        Intent i = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER);
        i.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                new ComponentName(this, DepthWallpaperService.class));
        try {
            startActivity(i);
        } catch (Exception e) {
            toast(getString(R.string.wp_picker_fail));
        }
    }
}
