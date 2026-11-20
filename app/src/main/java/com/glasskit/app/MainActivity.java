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

    static final String EXTRA_PICK_PHOTOS = "pickPhotos";
    static final String EXTRA_WIDGET_ID = "widgetId";
    private int pickWidgetId = -1;
    private boolean pickFromWidget;
    private static final int REQ_BG = 1, REQ_FG = 2, REQ_PHOTOS = 3, REQ_PERMS = 77, REQ_AZAN = 4, REQ_SHUF = 5, REQ_POSTER = 6, REQ_BK_OUT = 7, REQ_BK_IN = 8;
    private TextView prayerNote;

    private String prayerSummary() {
        Prayer.Day d = Prayer.today(this);
        if (d == null) return getString(R.string.p_need_loc);
        StringBuilder sb = new StringBuilder(getString(R.string.p_today)).append("\n");
        for (int i = 0; i < 6; i++) {
            sb.append(getString(Prayer.NAMES[i])).append("  ").append(Prayer.fmt(this, d.t[i]));
            if (i < 5) sb.append("   ·   ");
        }
        sb.append("\n").append(getString(R.string.p_note));
        return sb.toString();
    }
    private static final int[] COLORS = {
            Color.WHITE, 0xFF8AB4F8, 0xFFC8B6FF, 0xFFF5D06F, 0xFFFF8A80, 0xFF101418
    };
    ToolsPage tools;
    static final int TEXT2 = Color.argb(165, 235, 235, 245);

    private SharedPreferences prefs;
    private ImageView preview;
    private TextView status;
    private TextView stShizuku, stAcc, stNotif;
    private Bitmap cacheBg, cacheFg;
    private StatusBar statusPreview;
    private LinearLayout tabs;
    private ScrollView[] pages;
    private int curTab = -1;

    private void selectTab(int i) {
        if (i == curTab) return;
        for (int k = 0; k < tabs.getChildCount(); k++) {
            LinearLayout pill = (LinearLayout) tabs.getChildAt(k);
            boolean on = k == i;
            pill.setBackground(on ? Ui.round(Color.WHITE, dp(20)) : Ui.glass(this, 20, 26));
            ((Icon) pill.getChildAt(0)).setColor(on ? 0xFF0B0B10 : Color.WHITE);
            ((TextView) pill.getChildAt(1)).setTextColor(on ? 0xFF0B0B10 : Color.WHITE);
        }
        for (int k = 0; k < pages.length; k++) {
            ScrollView p = pages[k];
            if (k == i) {
                p.setVisibility(View.VISIBLE);
                p.setAlpha(0f);
                p.setTranslationY(dp(18));
                p.animate().alpha(1f).translationY(0).setInterpolator(Ui.SPRING).setDuration(Ui.SPRING_MS).start();
            } else {
                p.setVisibility(View.GONE);
            }
        }
        curTab = i;
    }

    /** Latar belakang premium: cahaya warna yang bergerak perlahan. */
    private static class Aurora extends View {
        private final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final long start = android.os.SystemClock.uptimeMillis();
        private final int[] cols;

        Aurora(android.content.Context c) {
            super(c);
            cols = new int[]{Ui.accent(c, true), 0xFF5E5CE6, 0xFF0A84FF, 0xFFFF375F};
        }

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth(), hh = getHeight();
            c.drawColor(0xFF06070C);
            double t = (android.os.SystemClock.uptimeMillis() - start) / 1000.0;
            float[][] orb = {
                    {0.2f + 0.15f * (float) Math.sin(t / 7), 0.12f + 0.06f * (float) Math.cos(t / 9), 0.75f},
                    {0.85f + 0.1f * (float) Math.cos(t / 8), 0.35f + 0.08f * (float) Math.sin(t / 6), 0.6f},
                    {0.3f + 0.12f * (float) Math.cos(t / 10), 0.8f + 0.06f * (float) Math.sin(t / 7), 0.7f},
                    {0.8f + 0.08f * (float) Math.sin(t / 11), 0.95f, 0.5f}};
            for (int i = 0; i < orb.length; i++) {
                float cx = orb[i][0] * w, cy = orb[i][1] * hh, r = orb[i][2] * w;
                int col = cols[i];
                p.setShader(new android.graphics.RadialGradient(cx, cy, r,
                        new int[]{(col & 0x00FFFFFF) | 0x5A000000, (col & 0x00FFFFFF) | 0x1E000000, col & 0x00FFFFFF},
                        new float[]{0f, 0.5f, 1f}, android.graphics.Shader.TileMode.CLAMP));
                c.drawCircle(cx, cy, r, p);
            }
            postInvalidateDelayed(50);
        }
    }
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
        CrashLog.install(this);
        prefs = Prefs.get(this);
        // buang model 3D lama (66 MB) jika pernah dimuat turun
        try {
            new java.io.File(getFilesDir(), "midas.tflite").delete();
            new java.io.File(getFilesDir(), "depth.png").delete();
        } catch (Exception ignored) {
        }
        getWindow().setStatusBarColor(0xFF07080F);
        getWindow().setNavigationBarColor(0xFF0B1424);

        FrameLayout rootF = new FrameLayout(this);
        rootF.addView(new Aurora(this), new FrameLayout.LayoutParams(-1, -1));
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setFitsSystemWindows(true);
        rootF.addView(shell, new FrameLayout.LayoutParams(-1, -1));
        setContentView(rootF);

        // ---- header ----
        LinearLayout hdr = new LinearLayout(this);
        hdr.setGravity(Gravity.CENTER_VERTICAL);
        hdr.setPadding(dp(20), dp(18), dp(20), dp(6));
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.mipmap.ic_launcher);
        hdr.addView(logo, new LinearLayout.LayoutParams(dp(52), dp(52)));
        LinearLayout ht = new LinearLayout(this);
        ht.setOrientation(LinearLayout.VERTICAL);
        ht.setPadding(dp(14), 0, 0, 0);
        TextView h = Ui.text(this, 30, 800, Color.WHITE);
        h.setText(R.string.app_name);
        h.setLetterSpacing(-0.02f);
        ht.addView(h);
        TextView tag = Ui.text(this, 13, 500, TEXT2);
        String ver = "";
        try { ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) {}
        tag.setText(getString(R.string.app_tagline) + "  ·  v" + ver);
        tag.setPadding(0, dp(4), 0, 0);
        ht.addView(tag);
        hdr.addView(ht, new LinearLayout.LayoutParams(0, -2, 1f));
        shell.addView(hdr);

        // ---- tab ----
        android.widget.HorizontalScrollView tabsSv = new android.widget.HorizontalScrollView(this);
        tabsSv.setHorizontalScrollBarEnabled(false);
        tabs = new LinearLayout(this);
        tabs.setPadding(dp(16), dp(10), dp(16), dp(10));
        tabsSv.addView(tabs);
        shell.addView(tabsSv);

        FrameLayout pagesF = new FrameLayout(this);
        shell.addView(pagesF, new LinearLayout.LayoutParams(-1, 0, 1f));
        int[] tabNames = {R.string.tab_home, R.string.tab_lock, R.string.tab_island, R.string.tab_panels, R.string.tab_widgets, R.string.tab_tools};
        int[] tabIcons = {Icon.GEAR, Icon.LOCK, Icon.NOTE, Icon.DARK, Icon.SUN, Icon.BOLT};
        pages = new ScrollView[tabNames.length];
        LinearLayout[] boxes = new LinearLayout[tabNames.length];
        for (int i = 0; i < tabNames.length; i++) {
            ScrollView sv = new ScrollView(this);
            sv.setVerticalScrollBarEnabled(false);
            sv.setClipToPadding(false);
            LinearLayout bx = new LinearLayout(this);
            bx.setOrientation(LinearLayout.VERTICAL);
            bx.setPadding(dp(16), dp(2), dp(16), dp(48));
            sv.addView(bx);
            pagesF.addView(sv, new FrameLayout.LayoutParams(-1, -1));
            sv.setVisibility(i == 0 ? View.VISIBLE : View.GONE);
            pages[i] = sv;
            boxes[i] = bx;
            final int idx = i;
            LinearLayout pill = new LinearLayout(this);
            pill.setGravity(Gravity.CENTER_VERTICAL);
            pill.setPadding(dp(14), 0, dp(16), 0);
            Icon pi = new Icon(this, tabIcons[i], Color.WHITE);
            pill.addView(pi, new LinearLayout.LayoutParams(dp(16), dp(16)));
            TextView pt = Ui.text(this, 14, 650, Color.WHITE);
            pt.setText(tabNames[i]);
            pt.setPadding(dp(8), 0, 0, 0);
            pill.addView(pt);
            pill.setOnClickListener(v -> selectTab(idx));
            Ui.press(pill);
            LinearLayout.LayoutParams pl2 = new LinearLayout.LayoutParams(-2, dp(40));
            pl2.rightMargin = dp(8);
            tabs.addView(pill, pl2);
        }
        LinearLayout pHome = boxes[0], pLock = boxes[1], pIsland = boxes[2], pPanels = boxes[3], pWidgets = boxes[4];
        tools = new ToolsPage(this, boxes[5]);
        selectTab(0);

        // ---------- Kebenaran ----------
        LinearLayout perm = card(pHome, R.string.sec_perm, Icon.LOCK, 0xFF34C759);
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

        // ---------- Kemas kini & sandaran ----------
        LinearLayout up = card(pHome, R.string.sec_update, Icon.ROTATE, 0xFF0A84FF);
        updNote = note(up, getString(R.string.upd_current, Updater.current(this)));
        updBtn = (TextView) button(up, getString(R.string.upd_check), true, v -> checkUpdate(true));
        LinearLayout bk = new LinearLayout(this);
        up.addView(bk);
        addPair(bk, button(null, getString(R.string.bk_save), false, v -> {
                    Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                            .setType("application/zip")
                            .putExtra(Intent.EXTRA_TITLE, "GlassKit-sandaran-"
                                    + new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(new java.util.Date()) + ".zip");
                    startActivityForResult(i, REQ_BK_OUT);
                }),
                button(null, getString(R.string.bk_restore), false, v -> {
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                            .setType("application/zip");
                    startActivityForResult(i, REQ_BK_IN);
                }));
        note(up, getString(R.string.bk_note));
        if (System.currentTimeMillis() - prefs.getLong("updChecked", 0) > 6 * 3600_000L) checkUpdate(false);

        final String crash = CrashLog.read(this);
        if (crash != null) {
            LinearLayout cr0 = card(pHome, R.string.sec_crash, Icon.CLOSE, 0xFFFF453A);
            TextView ct = note(cr0, crash.length() > 900 ? crash.substring(0, 900) + "…" : crash);
            ct.setTypeface(android.graphics.Typeface.MONOSPACE);
            ct.setTextSize(11);
            LinearLayout crr = new LinearLayout(this);
            cr0.addView(crr);
            addPair(crr, button(null, getString(R.string.btn_copy_crash), true, v -> {
                        getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("GlassKit", crash));
                        toast(getString(R.string.copied));
                    }),
                    button(null, getString(R.string.btn_clear_crash), false, v -> {
                        CrashLog.file(this).delete();
                        cr0.setVisibility(View.GONE);
                    }));
        }

        LinearLayout sys = card(pHome, R.string.sec_system, Icon.PHONE, 0xFF34C759);
        toggle(sys, R.string.bank_pause_on, Prefs.BANK_PAUSE, true);
        note(sys, getString(R.string.bank_pause_hint));
        toggle(sys, R.string.call_on, Prefs.CALL_ON, true);
        label(sys, R.string.call_style);
        segmented(sys, new int[]{R.string.call_style_island, R.string.call_style_full, R.string.call_style_card, R.string.call_style_ios},
                prefs.getInt(Prefs.CALL_STYLE, 0), i -> {
                    prefs.edit().putInt(Prefs.CALL_STYLE, i).apply();
                    // iOS 27: baca tarikh lahir kenalan untuk bunga api hari jadi
                    if (i == 3 && checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                        requestPermissions(new String[]{android.Manifest.permission.READ_CONTACTS}, 427);
                });
        label(sys, R.string.poster_title);
        posterList = new LinearLayout(this);
        posterList.setOrientation(LinearLayout.VERTICAL);
        sys.addView(posterList);
        buildPosterList();
        button(sys, getString(R.string.poster_add), false, v -> addPosterDialog());
        note(sys, getString(R.string.poster_note));
        toggle(sys, R.string.vol_on, Prefs.VOLUME_ON, true);
        note(sys, getString(R.string.system_note));

        LinearLayout snd = card(pHome, R.string.sec_sound, Icon.SPEAKER, 0xFFFF375F);
        note(snd, getString(R.string.snd_note));
        toggle(snd, R.string.sound_lock, Prefs.SOUND_LOCK, true);
        toggle(snd, R.string.sound_unlock, Prefs.SOUND_UNLOCK, true);
        toggle(snd, R.string.sound_charge, Prefs.SOUND_CHARGE, true);
        toggle(snd, R.string.sound_shot, Prefs.SOUND_SHOT, true);
        button(snd, getString(R.string.btn_test_sound), false, v -> {
            Sounds.play(this, Sounds.LOCK);
            stAcc.postDelayed(() -> Sounds.play(this, Sounds.UNLOCK), 700);
            stAcc.postDelayed(() -> Sounds.play(this, Sounds.SHOT), 1500);
            stAcc.postDelayed(() -> Sounds.play(this, Sounds.CHARGE), 2300);
        });
        label(snd, R.string.snd_tones);
        for (int ti = 0; ti < Sounds.TONE_NAMES.length; ti++) {
            final int k = ti;
            LinearLayout tr = new LinearLayout(this);
            snd.addView(tr);
            addPair(tr, button(null, "▶  " + Sounds.TONE_NAMES[k].replace("GlassKit ", ""), false,
                            v -> Sounds.playPcm(Sounds.tone(k))),
                    button(null, getString(R.string.snd_use), true, v -> new Thread(() -> {
                        String msg;
                        try {
                            Sounds.installTone(this, k, true);
                            msg = getString(R.string.snd_set, Sounds.TONE_NAMES[k]);
                        } catch (Exception e) {
                            CrashLog.write(this, e);
                            msg = getString(R.string.t_fail);
                        }
                        final String m = msg;
                        runOnUiThread(() -> toast(m));
                    }).start()));
        }
        note(snd, getString(R.string.snd_tones_note));

        // ---------- Island ----------
        LinearLayout isl = card(pIsland, R.string.sec_island, Icon.NOTE, 0xFFFF375F);
        toggle(isl, R.string.island_on, Prefs.PILL_ON, true);
        toggle(isl, R.string.island_music, Prefs.PILL_MUSIC, true);
        toggle(isl, R.string.island_noti, Prefs.PILL_NOTI, true);
        toggle(isl, R.string.live_on, Prefs.LIVE_ON, true);
        toggle(isl, R.string.isl_bt_on, Prefs.ISL_BT, true);
        toggle(isl, R.string.isl_ringer_on, Prefs.ISL_RINGER, true);
        toggle(isl, R.string.isl_privacy_on, Prefs.ISL_PRIVACY, true);
        note(isl, getString(R.string.isl_ring_note));
        note(isl, getString(R.string.live_note));
        toggle(isl, R.string.charge_anim, Prefs.CHARGE_ANIM, true);
        button(isl, getString(R.string.btn_test), false, v -> {
            if (PillService.inst != null) PillService.inst.test();
            else toast(getString(R.string.need_acc));
        });

        // ---------- Mod Fokus ----------
        LinearLayout fc = card(pIsland, R.string.sec_focus, Icon.MOON, 0xFF5E5CE6);
        segmented(fc, new int[]{R.string.f_off, R.string.f_work, R.string.f_home, R.string.f_sleep, R.string.f_drive},
                Focus.current(this), i -> Focus.set(this, i));
        note(fc, getString(R.string.f_note));
        toggle(fc, R.string.nightstand_on, Prefs.NIGHTSTAND, false);
        note(fc, getString(R.string.nightstand_note));
        toggle(fc, R.string.wall_mood, Prefs.WALL_MOOD, false);
        note(fc, getString(R.string.wall_mood_note));

        // ---------- Waktu solat ----------
        LinearLayout prayCard = card(pIsland, R.string.sec_prayer, Icon.BELL, 0xFF0A84FF);
        toggle(prayCard, R.string.prayer_on, Prefs.PRAYER_ON, true);
        prayerNote = note(prayCard, prayerSummary());
        LinearLayout prayRow1 = new LinearLayout(this);
        prayCard.addView(prayRow1);
        addPair(prayRow1, button(null, getString(R.string.p_pick_sound), true, v -> {
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("audio/*");
                    startActivityForResult(i, REQ_AZAN);
                }),
                button(null, getString(R.string.p_test), false, v -> Prayer.play(this)));
        LinearLayout prayRow2 = new LinearLayout(this);
        prayCard.addView(prayRow2);
        addPair(prayRow2, button(null, getString(R.string.p_sound_default), false, v -> {
                    prefs.edit().putString(Prefs.PRAYER_SOUND, "default").apply();
                    toast(getString(R.string.p_sound_set));
                }),
                button(null, getString(R.string.p_sound_none), false, v -> {
                    prefs.edit().putString(Prefs.PRAYER_SOUND, "none").apply();
                    Prayer.stopSound();
                    toast(getString(R.string.p_sound_set));
                }));

        // ---------- Mod Solat: senyap automatik ----------
        LinearLayout sol = card(pIsland, R.string.solat_title, Icon.MOON, 0xFF30D158);
        note(sol, getString(R.string.solat_note));
        Switch solSw = new Switch(this);
        solSw.setText(R.string.solat_on);
        solSw.setTextSize(16);
        solSw.setTextColor(Color.WHITE);
        solSw.setChecked(prefs.getBoolean(Prefs.SOLAT_SILENT, false));
        solSw.setPadding(0, Ui.dp(this, 12), 0, 0);
        solSw.setOnCheckedChangeListener((cb, on) -> {
            prefs.edit().putBoolean(Prefs.SOLAT_SILENT, on).apply();
            if (on && !Prayer.hasLocation(this)) toast(getString(R.string.solat_need_loc));
            if (on) {
                // akses "Jangan Ganggu" diperlukan untuk mod Senyap
                android.app.NotificationManager nmg = getSystemService(android.app.NotificationManager.class);
                if (!nmg.isNotificationPolicyAccessGranted() && Shell.granted())
                    Shell.run("cmd notification allow_dnd " + getPackageName());
            }
            SolatMode.schedule(this);
            toast(on ? solatNext() : getString(R.string.t_done));
        });
        sol.addView(solSw, new LinearLayout.LayoutParams(-1, -2));
        label(sol, R.string.solat_mode);
        segmented(sol, new int[]{R.string.rg_silent, R.string.rg_vibrate}, prefs.getInt(Prefs.SOLAT_MODE, 0),
                i -> prefs.edit().putInt(Prefs.SOLAT_MODE, i).apply());
        label(sol, R.string.solat_delay);
        segmented(sol, new int[]{R.string.solat_d0, R.string.solat_d5, R.string.solat_d10, R.string.solat_d15},
                idxOf(SolatMode.DELAYS, prefs.getInt(Prefs.SOLAT_DELAY, 5)), i -> {
                    prefs.edit().putInt(Prefs.SOLAT_DELAY, SolatMode.DELAYS[i]).apply();
                    SolatMode.schedule(this);
                });
        label(sol, R.string.solat_dur);
        segmented(sol, new int[]{R.string.solat_m15, R.string.solat_m20, R.string.solat_m30, R.string.solat_m45},
                idxOf(SolatMode.DURS, prefs.getInt(Prefs.SOLAT_DUR, 30)), i -> {
                    prefs.edit().putInt(Prefs.SOLAT_DUR, SolatMode.DURS[i]).apply();
                    SolatMode.schedule(this);
                });
        note(sol, getString(R.string.solat_note2));

        // ---------- Lock screen ----------
        LinearLayout lk = card(pLock, R.string.sec_lock, Icon.LOCK, 0xFFFF9F0A);
        toggle(lk, R.string.lock_on, Prefs.LOCK_ON, false);
        toggle(lk, R.string.lock_zoom, Prefs.LOCK_ZOOM, true);
        toggle(lk, R.string.lock_stack, Prefs.LOCK_STACK, true);
        toggle(lk, R.string.lock_prayer, Prefs.LOCK_PRAYER, false);
        toggle(lk, R.string.lock_hijri, Prefs.LOCK_HIJRI, false);
        label(lk, R.string.hijri_adj);
        segmented(lk, new int[]{R.string.hijri_m1, R.string.hijri_0, R.string.hijri_p1}, prefs.getInt(Prefs.HIJRI_ADJ, 0) + 1,
                i -> prefs.edit().putInt(Prefs.HIJRI_ADJ, i - 1).apply());
        label(lk, R.string.pop_title);
        segmented(lk, new int[]{R.string.pop_0, R.string.pop_1, R.string.pop_2, R.string.pop_3},
                prefs.getInt(Prefs.POP_STYLE, 0), i -> prefs.edit().putInt(Prefs.POP_STYLE, i).apply());
        note(lk, getString(R.string.pop_note));
        note(lk, getString(R.string.lock_note));
        button(lk, getString(R.string.btn_preview_lock), false, v -> startActivity(new Intent(this, LockActivity.class)));

        // ---------- Status bar ----------
        LinearLayout sb = card(pIsland, R.string.sec_sbar, Icon.BATTERY, 0xFF30D158);
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
        // cip boleh skrol (6 gaya tidak muat dalam satu baris segmen)
        int[] sbOrder = {5, 0, 1, 2, 3, 4};
        int[] sbNames = {R.string.sbar_iphone, R.string.sbar_capsule, R.string.sbar_minimal, R.string.sbar_island,
                R.string.sbar_chips, R.string.sbar_tint};
        choiceChips(sb, sbOrder, sbNames, prefs.getInt(Prefs.SBAR_STYLE, 0), i -> prefs.edit().putInt(Prefs.SBAR_STYLE, i).apply());
        LinearLayout sr = new LinearLayout(this);
        sb.addView(sr);
        addPair(sr, button(null, getString(R.string.btn_hide_sys), false, v -> {
                    prefs.edit().putBoolean(Prefs.HIDE_SYS_ICONS, true).apply();
                    if (!Shell.granted()) {
                        Shell.request();
                        toast(getString(R.string.need_shizuku));
                        return;
                    }
                    if (PillService.inst != null) PillService.inst.applyStock();
                    else Tweaks.hideStockBar(this, true, true);
                    toast(getString(R.string.t_done));
                }),
                button(null, getString(R.string.btn_restore_sys), false, v -> {
                    prefs.edit().putBoolean(Prefs.HIDE_SYS_ICONS, false).apply();
                    runShell("settings delete secure icon_blacklist; " + Tweaks.ENABLE_FLAGS);
                }));
        note(sb, getString(R.string.sbar_note));

        // ---------- Panel ----------
        LinearLayout pan = card(pPanels, R.string.sec_panels, Icon.GEAR, Ui.BLUE);
        toggle(pan, R.string.panels_on, Prefs.PANELS_ON, true);
        label(pan, R.string.glass_style);
        segmented(pan, new int[]{R.string.glass_clear, R.string.glass_tinted, R.string.glass_auto},
                Ui.glassMode(this), i -> {
                    prefs.edit().putInt(Prefs.GLASS_MODE, i).putBoolean(Prefs.GLASS_DARK, i == 1).apply();
                    GlassWidget.refreshAll(this);
                    if (PillService.inst != null) PillService.inst.updateStatusBar();
                });
        note(pan, getString(R.string.glass_mode_note));
        label(pan, R.string.panel_style);
        segmented(pan, new int[]{R.string.panel_classic, R.string.panel_liquid, R.string.panel_bento},
                prefs.getInt(Prefs.PANEL_STYLE, 1), i -> prefs.edit().putInt(Prefs.PANEL_STYLE, i).apply());
        note(pan, getString(R.string.panel_style_note));
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

        LinearLayout rc = card(pPanels, R.string.sec_rec, Icon.REC, 0xFFFF3B30);
        label(rc, R.string.rec_audio);
        segmented(rc, new int[]{R.string.rec_none, R.string.rec_internal, R.string.rec_mic, R.string.rec_both},
                prefs.getInt(Prefs.REC_AUDIO, 1), i -> prefs.edit().putInt(Prefs.REC_AUDIO, i).apply());
        note(rc, getString(R.string.rec_note));

        // ---------- Jam depth ----------
        buildFacesCard(pLock);
        LinearLayout clk = card(pLock, R.string.sec_clock, Icon.SUN, Ui.ORANGE);
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
        slider(clk, 8, 70, prefs.getInt(Prefs.CLOCK_Y, 28), val -> prefs.edit().putInt(Prefs.CLOCK_Y, val).apply());

        label(clk, R.string.lbl_color);
        LinearLayout cr = new LinearLayout(this);
        cr.setGravity(Gravity.CENTER_VERTICAL);
        clk.addView(cr);
        {
            // "Auto": warna lembut dari wallpaper
            View chip = new View(this);
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFFF5D06F, 0xFF8AB4F8, 0xFFC8B6FF});
            g.setShape(GradientDrawable.OVAL);
            g.setStroke(dp(1.5f), Color.argb(90, 255, 255, 255));
            chip.setBackground(g);
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(dp(38), dp(38));
            l.rightMargin = dp(12);
            l.topMargin = dp(4);
            chip.setOnClickListener(v -> {
                prefs.edit().putBoolean(Prefs.CLOCK_AUTO, true).apply();
                toast(getString(R.string.clock_auto));
            });
            Ui.press(chip);
            cr.addView(chip, l);
        }
        for (int c : COLORS) {
            View chip = new View(this);
            GradientDrawable g = Ui.circle(c);
            g.setStroke(dp(1.5f), Color.argb(90, 255, 255, 255));
            chip.setBackground(g);
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(dp(38), dp(38));
            l.rightMargin = dp(12);
            l.topMargin = dp(4);
            chip.setOnClickListener(v -> prefs.edit().putInt(Prefs.CLOCK_COLOR, c).putBoolean(Prefs.CLOCK_AUTO, false).apply());
            Ui.press(chip);
            cr.addView(chip, l);
        }

        label(clk, R.string.lbl_font);
        fontChips(clk);
        label(clk, R.string.clock_layout);
        segmented(clk, new int[]{R.string.clock_inline, R.string.clock_stacked, R.string.clock_tall}, prefs.getInt(Prefs.CLOCK_LAYOUT, 0),
                i -> prefs.edit().putInt(Prefs.CLOCK_LAYOUT, i).apply());
        label(clk, R.string.clock_shape);
        shapeChips(clk);

        label(clk, R.string.clock_style);
        segmented(clk, new int[]{R.string.style_glass, R.string.style_solid},
                prefs.getBoolean(Prefs.CLOCK_GLASS, true) ? 0 : 1,
                i -> prefs.edit().putBoolean(Prefs.CLOCK_GLASS, i == 0).apply());

        label(clk, R.string.lbl_width);
        slider(clk, 60, 170, prefs.getInt(Prefs.CLOCK_W, 100), val -> prefs.edit().putInt(Prefs.CLOCK_W, val).apply());
        label(clk, R.string.lbl_height);
        slider(clk, 60, 600, prefs.getInt(Prefs.CLOCK_H, 100), val -> prefs.edit().putInt(Prefs.CLOCK_H, val).apply());
        button(clk, getString(R.string.btn_auto_pos), false, v -> autoPlace(true));

        toggle(clk, R.string.lock_top_blur, Prefs.LOCK_TOP_BLUR, true);
        toggle(clk, R.string.hide_stock, Prefs.HIDE_STOCK, false);
        label(clk, R.string.lbl_hide_h);
        slider(clk, 60, 260, prefs.getInt(Prefs.HIDE_H, 130), val -> prefs.edit().putInt(Prefs.HIDE_H, val).apply());
        label(clk, R.string.lbl_patch_dim);
        slider(clk, 0, 70, prefs.getInt(Prefs.PATCH_DIM, 22), val -> prefs.edit().putInt(Prefs.PATCH_DIM, val).apply());
        note(clk, getString(R.string.hide_stock_note));

        toggle(clk, R.string.lock_only, Prefs.LOCK_ONLY, true);
        button(clk, getString(R.string.btn_set_wp), true, v -> setWallpaper());
        note(clk, getString(R.string.clock_tip));

        LinearLayout wg = card(pWidgets, R.string.sec_widgets, Icon.NOTE, Ui.PURPLE);
        // pratonton langsung
        wPrev = new ImageView(this);
        wPrev.setScaleType(ImageView.ScaleType.FIT_CENTER);
        wPrev.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline o) { o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(22)); }
        });
        wPrev.setClipToOutline(true);
        LinearLayout.LayoutParams wpl = new LinearLayout.LayoutParams(-1, dp(300));
        wpl.bottomMargin = dp(12);
        wg.addView(wPrev, wpl);
        label(wg, R.string.w_appearance);
        segmented(wg, new int[]{R.string.w_light, R.string.w_dark, R.string.glass_auto},
                prefs.getInt(Prefs.W_APPEAR, 2), i -> { prefs.edit().putInt(Prefs.W_APPEAR, i).apply(); widgetsChanged(); });
        label(wg, R.string.w_accent);
        segmented(wg, new int[]{R.string.w_acc_wall, R.string.w_acc_blue, R.string.w_acc_purple, R.string.w_acc_cyan},
                prefs.getInt(Prefs.W_ACCENT, 0), i -> { prefs.edit().putInt(Prefs.W_ACCENT, i).apply(); widgetsChanged(); });
        label(wg, R.string.w_opacity);
        slider(wg, 0, 100, prefs.getInt(Prefs.W_ALPHA, 55), v -> { prefs.edit().putInt(Prefs.W_ALPHA, v).apply(); widgetsChanged(); });
        label(wg, R.string.w_reflect);
        slider(wg, 0, 100, prefs.getInt(Prefs.W_REFLECT, 60), v -> { prefs.edit().putInt(Prefs.W_REFLECT, v).apply(); widgetsChanged(); });
        label(wg, R.string.w_radius);
        slider(wg, 8, 36, prefs.getInt(Prefs.W_RADIUS, 24), v -> { prefs.edit().putInt(Prefs.W_RADIUS, v).apply(); widgetsChanged(); });
        button(wg, getString(R.string.w_pick_zones), false, v -> pickZones());
        note(wg, getString(R.string.widgets_hint));
        button(wg, getString(R.string.btn_refresh_widgets), false, v -> GlassWidget.refreshAll(this));

        button(wg, getString(R.string.btn_open_notes), true, v -> startActivity(new Intent(this, NotesActivity.class)));

        LinearLayout dc = card(pWidgets, R.string.sec_data, Icon.PIN, 0xFF34C759);
        note(dc, getString(R.string.data_hint));
        button(dc, getString(R.string.btn_allow_data), true, v -> requestPermissions(new String[]{
                android.Manifest.permission.READ_CALENDAR, android.Manifest.permission.ACCESS_COARSE_LOCATION,
                android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACTIVITY_RECOGNITION}, REQ_PERMS));
        LinearLayout pr2 = new LinearLayout(this);
        dc.addView(pr2);
        addPair(pr2, button(null, getString(R.string.btn_pick_photos), false, v -> {
                    pickWidgetId = -1;
                    pickFromWidget = false;
                    openPhotoPicker();
                }),
                button(null, getString(R.string.btn_clear_photos), false, v -> {
                    File[] fs = Data.photoDir(this).listFiles(File::isFile);
                    if (fs != null) for (File f : fs) f.delete();
                    GlassWidget.updateAll(this, PhotoWidget.class);
                }));
        label(dc, R.string.lbl_step_goal);
        slider(dc, 2, 30, prefs.getInt("stepGoal", 10000) / 1000, val -> {
            prefs.edit().putInt("stepGoal", val * 1000).apply();
            GlassWidget.updateAll(this, StepsWidget.class);
        });

        prefs.registerOnSharedPreferenceChangeListener(prefL);
        try { Shizuku.addRequestPermissionResultListener(shizukuL); } catch (Throwable ignored) {}
        renderPreview();
        handlePick(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handlePick(intent);
    }

    private void handlePick(Intent i) {
        if (i != null && i.getBooleanExtra(EXTRA_PICK_PHOTOS, false)) {
            i.removeExtra(EXTRA_PICK_PHOTOS);
            pickWidgetId = i.getIntExtra(EXTRA_WIDGET_ID, -1);
            pickFromWidget = pickWidgetId >= 0;
            selectTab(4);
            openPhotoPicker();
        }
    }

    /** Pemilih gambar Android (ada tab Album untuk galeri). */
    private void openPhotoPicker() {
        Intent p;
        try {
            p = new Intent(android.provider.MediaStore.ACTION_PICK_IMAGES);
            p.setType("image/*");
            int max = Math.min(20, android.provider.MediaStore.getPickImagesMaxLimit());
            p.putExtra(android.provider.MediaStore.EXTRA_PICK_IMAGES_MAX, max);
        } catch (Throwable t) {
            p = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            p.addCategory(Intent.CATEGORY_OPENABLE);
            p.setType("image/*");
            p.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        }
        try {
            startActivityForResult(p, REQ_PHOTOS);
        } catch (Exception e) {
            Intent q = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            q.addCategory(Intent.CATEGORY_OPENABLE);
            q.setType("image/*");
            q.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(q, REQ_PHOTOS);
        }
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
        renderWidgetPreview();
        refreshStatus();
        if (tools != null) tools.refresh();
    }

    // ======================= Komponen =======================

    LinearLayout card(LinearLayout parent, int title, int icon, int color) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(Ui.glass(this, 30, 24));
        c.setElevation(dp(2));
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

    View button(LinearLayout parent, String text, boolean primary, View.OnClickListener l) {
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

    void addPair(LinearLayout row, View a, View b) {
        LinearLayout.LayoutParams l1 = new LinearLayout.LayoutParams(0, dp(50), 1f);
        l1.topMargin = dp(12);
        LinearLayout.LayoutParams l2 = new LinearLayout.LayoutParams(0, dp(50), 1f);
        l2.topMargin = dp(12);
        l2.leftMargin = dp(10);
        row.addView(a, l1);
        row.addView(b, l2);
    }

    TextView note(LinearLayout parent, String s) {
        TextView t = Ui.text(this, 14, 400, TEXT2);
        t.setText(s);
        t.setLineSpacing(0, 1.2f);
        t.setPadding(0, dp(10), 0, 0);
        parent.addView(t);
        return t;
    }

    void label(LinearLayout parent, int res) {
        TextView t = Ui.text(this, 13, 600, TEXT2);
        t.setText(res);
        t.setPadding(dp(2), dp(18), 0, dp(6));
        parent.addView(t);
    }

    void toggle(LinearLayout parent, int label, String key, boolean def) {
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

    void slider(LinearLayout parent, int min, int max, int cur, IntCb cb) {
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

    void segmented(LinearLayout parent, int[] labels, int selected, IntCb cb) {
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

    private ImageView wPrev;
    private final android.os.Handler wh = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable wApply = () -> GlassWidget.refreshAll(this);
    private int wGen;

    /** Tetapan widget berubah: kemas kini pratonton serta-merta, widget sebenar selepas seketika. */
    private void widgetsChanged() {
        renderWidgetPreview();
        wh.removeCallbacks(wApply);
        wh.postDelayed(wApply, 600);
    }

    private void renderWidgetPreview() {
        if (wPrev == null) return;
        final int g = ++wGen;
        final android.content.Context app = getApplicationContext();
        final float d = getResources().getDisplayMetrics().density;
        new Thread(() -> {
            try {
                int W = Math.round(340 * d), H = Math.round(330 * d);
                Bitmap out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(out);
                Bitmap bg = DepthRenderer.load(DepthRenderer.bgFile(app));
                if (bg != null) {
                    android.graphics.RectF dst = DepthRenderer.centerCrop(bg, W, H);
                    c.drawBitmap(bg, null, dst, new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG));
                } else {
                    android.graphics.Paint p = new android.graphics.Paint();
                    p.setShader(new android.graphics.LinearGradient(0, 0, W, H, new int[]{0xFF2B5876, 0xFF4E4376, 0xFFE0A386},
                            null, android.graphics.Shader.TileMode.CLAMP));
                    c.drawRect(0, 0, W, H, p);
                }
                int s = Math.round(170 * d), m = Math.round(160 * d);
                c.drawBitmap(new ClockWidget().draw(app, s, s, d), 0, 0, null);
                c.drawBitmap(new BatteryWidget().draw(app, s, s, d), W - s, 0, null);
                c.drawBitmap(new WeatherWidget().draw(app, W, m, d), 0, s - Math.round(4 * d), null);
                wh.post(() -> { if (g == wGen) wPrev.setImageBitmap(out); });
            } catch (Throwable t) {
                CrashLog.write(app, t);
            }
        }, "gk-wprev").start();
    }

    private void pickZones() {
        final String[] ids = {"Asia/Riyadh", "Asia/Dubai", "Europe/London", "Europe/Paris", "Europe/Istanbul",
                "America/New_York", "America/Los_Angeles", "Asia/Tokyo", "Asia/Seoul", "Asia/Shanghai", "Asia/Singapore",
                "Asia/Jakarta", "Asia/Bangkok", "Asia/Kolkata", "Australia/Sydney", "Asia/Kuala_Lumpur"};
        String[] names = new String[ids.length];
        boolean[] on = new boolean[ids.length];
        java.util.List<String> cur = Cards.zones(this);
        for (int i = 0; i < ids.length; i++) {
            names[i] = Cards.city(ids[i]);
            on[i] = cur.contains(ids[i]);
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.w_pick_zones)
                .setMultiChoiceItems(names, on, (dlg, w, checked) -> on[w] = checked)
                .setPositiveButton(android.R.string.ok, (dlg, w) -> {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < ids.length; i++) if (on[i]) sb.append(sb.length() > 0 ? "," : "").append(ids[i]);
                    prefs.edit().putString(Prefs.W_ZONES, sb.toString()).apply();
                    widgetsChanged();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Pilihan fon jam dengan pratonton "12:30" dalam setiap gaya. */
    /** Baris cip pilihan (boleh skrol mendatar). values[k] disimpan, names[k] dipaparkan. */
    private void choiceChips(LinearLayout parent, int[] values, int[] names, int selected, IntCb cb) {
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        for (int k = 0; k < values.length; k++) {
            final int val = values[k];
            TextView t = Ui.text(this, 14, 600, Color.WHITE);
            t.setText(names[k]);
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(16), dp(10), dp(16), dp(10));
            t.setTag(val);
            t.setBackground(val == selected ? Ui.round(Color.argb(235, 255, 255, 255), dp(20)) : Ui.round(Color.argb(40, 255, 255, 255), dp(20)));
            t.setTextColor(val == selected ? Color.BLACK : Color.WHITE);
            t.setOnClickListener(v -> {
                for (int j = 0; j < row.getChildCount(); j++) {
                    TextView o = (TextView) row.getChildAt(j);
                    boolean on = (int) o.getTag() == val;
                    o.setBackground(on ? Ui.round(Color.argb(235, 255, 255, 255), dp(20)) : Ui.round(Color.argb(40, 255, 255, 255), dp(20)));
                    o.setTextColor(on ? Color.BLACK : Color.WHITE);
                }
                cb.on(val);
            });
            Ui.press(t);
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-2, -2);
            l.rightMargin = dp(8);
            row.addView(t, l);
        }
        hs.addView(row);
        parent.addView(hs, new LinearLayout.LayoutParams(-1, -2));
    }

    /** Pilihan jam ikut bentuk wallpaper: Biasa, A–F. */
    private void shapeChips(LinearLayout parent) {
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        String[] marks = {"—", "A", "B", "C", "D", "E", "F"};
        int[] names = {R.string.shape_none, R.string.shape_contour, R.string.shape_line, R.string.shape_sky,
                R.string.shape_halo, R.string.shape_reflect, R.string.shape_sand};
        for (int k = 0; k < marks.length; k++) {
            final int idx = k;
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER);
            col.setPadding(dp(14), dp(8), dp(14), dp(8));
            TextView mk = Ui.text(this, 22, 700, Color.WHITE);
            mk.setText(marks[k]);
            mk.setGravity(Gravity.CENTER);
            col.addView(mk);
            TextView nm = Ui.text(this, 11, 600, Color.argb(190, 235, 235, 245));
            nm.setText(names[k]);
            col.addView(nm);
            col.setTag(idx);
            col.setOnClickListener(v -> {
                prefs.edit().putInt(Prefs.CLOCK_SHAPE, idx).apply();
                for (int j = 0; j < row.getChildCount(); j++) {
                    View cv = row.getChildAt(j);
                    cv.setBackground((int) cv.getTag() == idx ? Ui.round(Color.argb(60, 255, 255, 255), dp(16)) : null);
                }
            });
            col.setBackground(prefs.getInt(Prefs.CLOCK_SHAPE, 0) == idx ? Ui.round(Color.argb(60, 255, 255, 255), dp(16)) : null);
            Ui.press(col);
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-2, -2);
            l.rightMargin = dp(6);
            row.addView(col, l);
        }
        hs.addView(row);
        parent.addView(hs, new LinearLayout.LayoutParams(-1, -2));
        TextView hint = Ui.text(this, 12, 400, Color.argb(160, 235, 235, 245));
        hint.setText(R.string.shape_hint);
        hint.setPadding(dp(4), dp(4), dp(4), dp(4));
        parent.addView(hint);
    }

    private void fontChips(LinearLayout parent) {
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        int[] order = {0, 7, 6, 1, 4, 5, 2, 3};
        int[] names = {R.string.font_bold, R.string.font_black, R.string.font_soft, R.string.font_thin,
                R.string.font_ultra, R.string.font_narrow, R.string.font_serif, R.string.font_mono};
        final java.util.List<TextView> all = new java.util.ArrayList<>();
        for (int k = 0; k < order.length; k++) {
            final int idx = order[k];
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER);
            col.setPadding(dp(14), dp(8), dp(14), dp(8));
            TextView sample = new TextView(this);
            sample.setText("12:30");
            sample.setTextSize(24);
            sample.setTypeface(DepthRenderer.font(idx));
            sample.setTextColor(Color.WHITE);
            col.addView(sample);
            TextView nm = Ui.text(this, 11, 600, Color.argb(190, 235, 235, 245));
            nm.setText(names[k]);
            col.addView(nm);
            col.setTag(idx);
            all.add(sample);
            col.setOnClickListener(v -> {
                prefs.edit().putInt(Prefs.CLOCK_FONT, idx).apply();
                for (int j = 0; j < row.getChildCount(); j++) {
                    View cv = row.getChildAt(j);
                    cv.setBackground((int) cv.getTag() == idx ? Ui.round(Color.argb(60, 255, 255, 255), dp(16)) : null);
                }
            });
            col.setBackground(prefs.getInt(Prefs.CLOCK_FONT, 0) == idx ? Ui.round(Color.argb(60, 255, 255, 255), dp(16)) : null);
            Ui.press(col);
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-2, -2);
            l.rightMargin = dp(6);
            row.addView(col, l);
        }
        hs.addView(row);
        parent.addView(hs, new LinearLayout.LayoutParams(-1, -2));
    }

    private void buildFacesCard(LinearLayout parent) {
        LinearLayout fc = card(parent, R.string.sec_faces, Icon.LOCK, 0xFFBF5AF2);
        segmented(fc, new int[]{Faces.NAMES[0], Faces.NAMES[1], Faces.NAMES[2], Faces.NAMES[3]},
                Faces.active(this), i -> {
                    toast(getString(R.string.face_switching));
                    Faces.switchTo(this, i, () -> runOnUiThread(this::recreate));
                });
        note(fc, getString(R.string.faces_note));
        toggle(fc, R.string.face_swipe, Prefs.FACE_SWIPE, true);
        toggle(fc, R.string.face_focus, Prefs.FACE_FOCUS, false);

        label(fc, R.string.shuffle_title);
        toggle(fc, R.string.shuffle_on, Prefs.SHUFFLE_ON, false);
        shuffleNote = note(fc, getString(R.string.shuffle_note, Shuffle.count(this)));
        LinearLayout r = new LinearLayout(this);
        fc.addView(r);
        addPair(r, button(null, getString(R.string.shuffle_pick), true, v -> {
                    Intent p = new Intent(android.provider.MediaStore.ACTION_PICK_IMAGES);
                    p.putExtra(android.provider.MediaStore.EXTRA_PICK_IMAGES_MAX, 30);
                    try {
                        startActivityForResult(p, REQ_SHUF);
                    } catch (Exception e) {
                        Intent q = new Intent(Intent.ACTION_GET_CONTENT).setType("image/*")
                                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                        startActivityForResult(q, REQ_SHUF);
                    }
                }),
                button(null, getString(R.string.shuffle_now), false, v -> {
                    if (Shuffle.count(this) == 0) {
                        toast(getString(R.string.shuffle_empty));
                        return;
                    }
                    prefs.edit().putBoolean(Prefs.SHUFFLE_ON, true).apply();
                    Shuffle.daily(this, true);
                    toast(getString(R.string.shuffle_working));
                }));
    }

    private TextView shuffleNote;
    private TextView updNote, updBtn;
    private Updater.Info updInfo;
    private boolean updBusy;

    private void checkUpdate(boolean manual) {
        if (updBusy) return;
        if (updInfo != null) {
            doUpdate();
            return;
        }
        if (manual) updNote.setText(R.string.upd_checking);
        Updater.check(this, (info, err) -> runOnUiThread(() -> {
            if (isFinishing()) return;
            if (info != null) {
                updInfo = info;
                updNote.setText(getString(R.string.upd_available, info.version, Updater.current(this)));
                updBtn.setText(getString(R.string.upd_install, info.version));
            } else if (manual) {
                updNote.setText(err != null ? getString(R.string.upd_error, err) : getString(R.string.upd_latest, Updater.current(this)));
            }
        }));
    }

    private void doUpdate() {
        updBusy = true;
        updBtn.setText(R.string.upd_downloading);
        Updater.install(this, updInfo, pct -> runOnUiThread(() -> updNote.setText(getString(R.string.upd_progress, pct))),
                (x, err) -> runOnUiThread(() -> {
                    updBusy = false;
                    if (err != null) {
                        updNote.setText(getString(R.string.upd_error, err));
                        updBtn.setText(getString(R.string.upd_install, updInfo.version));
                    } else {
                        updNote.setText(R.string.upd_installing);
                    }
                }));
    }
    private LinearLayout posterList;
    private String posterName;
    private int posterColor = Posters.COLORS[0];

    private void buildPosterList() {
        if (posterList == null) return;
        posterList.removeAllViews();
        for (Posters.Poster p : Posters.all(this)) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(6), 0, dp(6));
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            Bitmap b = Posters.photo(this, p);
            if (b != null) iv.setImageBitmap(b);
            iv.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, android.graphics.Outline o) { o.setOval(0, 0, view.getWidth(), view.getHeight()); }
            });
            iv.setClipToOutline(true);
            row.addView(iv, new LinearLayout.LayoutParams(dp(40), dp(40)));
            TextView t = Ui.line(this, 15, 600, Color.WHITE);
            t.setText(p.name);
            t.setPadding(dp(12), 0, dp(8), 0);
            row.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
            View dot = new View(this);
            dot.setBackground(Ui.circle(p.color));
            row.addView(dot, new LinearLayout.LayoutParams(dp(18), dp(18)));
            TextView del = Ui.text(this, 18, 600, Color.argb(170, 255, 255, 255));
            del.setText("✕");
            del.setPadding(dp(16), dp(4), dp(4), dp(4));
            del.setOnClickListener(v -> {
                Posters.delete(this, p.id);
                buildPosterList();
            });
            row.addView(del);
            posterList.addView(row);
        }
    }

    private void addPosterDialog() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(dp(22), dp(8), dp(22), 0);
        final android.widget.EditText et = new android.widget.EditText(this);
        et.setHint(R.string.poster_name_hint);
        v.addView(et);
        LinearLayout cr = new LinearLayout(this);
        cr.setPadding(0, dp(12), 0, 0);
        final View[] chips = new View[Posters.COLORS.length];
        for (int k = 0; k < Posters.COLORS.length; k++) {
            final int col = Posters.COLORS[k];
            View ch = new View(this);
            GradientDrawable g = Ui.circle(col);
            if (col == posterColor) g.setStroke(dp(3), Color.WHITE);
            ch.setBackground(g);
            ch.setOnClickListener(x -> {
                posterColor = col;
                for (int j = 0; j < chips.length; j++) {
                    GradientDrawable gg = Ui.circle(Posters.COLORS[j]);
                    if (Posters.COLORS[j] == col) gg.setStroke(dp(3), Color.WHITE);
                    chips[j].setBackground(gg);
                }
            });
            chips[k] = ch;
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(dp(30), dp(30));
            l.rightMargin = dp(8);
            cr.addView(ch, l);
        }
        v.addView(cr);
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.poster_add)
                .setView(v)
                .setPositiveButton(R.string.poster_pick_photo, (d, w) -> {
                    String n = et.getText().toString().trim();
                    if (n.isEmpty()) {
                        toast(getString(R.string.poster_need_name));
                        return;
                    }
                    posterName = n;
                    Intent p = new Intent(android.provider.MediaStore.ACTION_PICK_IMAGES);
                    try {
                        startActivityForResult(p, REQ_POSTER);
                    } catch (Exception e) {
                        startActivityForResult(new Intent(Intent.ACTION_GET_CONTENT).setType("image/*"), REQ_POSTER);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    void toast(String s) {
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

    /** Pilih gambar terus dari galeri / album (Photo Picker Android), bukan pengurus fail. */
    private void pick(int req) {
        try {
            Intent p = new Intent(android.provider.MediaStore.ACTION_PICK_IMAGES);
            p.setType("image/*");
            startActivityForResult(p, req);
            return;
        } catch (Exception ignored) {
            // tiada Photo Picker: guna pemilih fail
        }
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        startActivityForResult(i, req);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (PillService.inst != null) PillService.inst.onDataPermissions();
        GlassWidget.refreshAll(this);
    }

    private void addPhotos(Intent data) {
        java.util.List<Uri> uris = new java.util.ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        File dir = pickWidgetId >= 0 ? Data.widgetPhotoDir(this, pickWidgetId) : Data.photoDir(this);
        if (pickWidgetId >= 0 && !uris.isEmpty()) {
            File[] old = dir.listFiles();
            if (old != null) for (File f : old) f.delete();
        }
        int n = 0;
        for (Uri u : uris) {
            try {
                Bitmap b = decode(u);
                float sc = Math.min(1f, 1200f / Math.max(b.getWidth(), b.getHeight()));
                if (sc < 1f) b = Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * sc), Math.round(b.getHeight() * sc), true);
                save(b, new File(dir, System.currentTimeMillis() + "_" + n + ".jpg"), false);
                n++;
            } catch (Exception ignored) {
            }
        }
        toast(getString(R.string.photos_added, n));
        GlassWidget.updateAll(this, PhotoWidget.class);
        if (pickFromWidget) finish();
        pickWidgetId = -1;
        pickFromWidget = false;
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if ((req == REQ_BK_OUT || req == REQ_BK_IN) && res == RESULT_OK && data != null && data.getData() != null) {
            final Uri u = data.getData();
            toast(getString(R.string.bk_working));
            new Thread(() -> {
                try {
                    if (req == REQ_BK_OUT) {
                        Backup.export(this, u);
                        runOnUiThread(() -> toast(getString(R.string.bk_saved)));
                    } else {
                        int n = Backup.restore(this, u);
                        runOnUiThread(() -> {
                            toast(getString(R.string.bk_restored, n));
                            recreate();
                        });
                    }
                } catch (Exception e) {
                    runOnUiThread(() -> toast(getString(R.string.upd_error, String.valueOf(e.getMessage()))));
                }
            }).start();
            return;
        }
        if (req == REQ_POSTER) {
            if (res != RESULT_OK || data == null || data.getData() == null || posterName == null) return;
            final Uri u = data.getData();
            final String n = posterName;
            final int col = posterColor;
            new Thread(() -> {
                try {
                    Posters.add(this, n, col, decode(u));
                    runOnUiThread(() -> {
                        buildPosterList();
                        toast(getString(R.string.poster_saved, n));
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> toast(String.valueOf(e.getMessage())));
                }
            }).start();
            return;
        }
        if (req == REQ_SHUF) {
            if (res != RESULT_OK || data == null) return;
            final java.util.List<Uri> us = new java.util.ArrayList<>();
            if (data.getClipData() != null) {
                for (int k = 0; k < data.getClipData().getItemCount(); k++) us.add(data.getClipData().getItemAt(k).getUri());
            } else if (data.getData() != null) {
                us.add(data.getData());
            }
            toast(getString(R.string.shuffle_working));
            new Thread(() -> {
                int n = Shuffle.importUris(this, us);
                runOnUiThread(() -> {
                    if (shuffleNote != null) shuffleNote.setText(getString(R.string.shuffle_note, n));
                    toast(getString(R.string.shuffle_saved, n));
                    if (n > 0) {
                        prefs.edit().putBoolean(Prefs.SHUFFLE_ON, true).apply();
                        Shuffle.daily(this, true);
                    }
                });
            }).start();
            return;
        }
        if (req == REQ_AZAN) {
            if (res == RESULT_OK && data != null && data.getData() != null) {
                try {
                    getContentResolver().takePersistableUriPermission(data.getData(), Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Exception ignored) {
                }
                prefs.edit().putString(Prefs.PRAYER_SOUND, data.getData().toString()).apply();
                toast(getString(R.string.p_sound_set));
            }
            return;
        }
        if (req == REQ_PHOTOS) {
            if (res == RESULT_OK && data != null) addPhotos(data);
            else if (pickFromWidget) finish();
            return;
        }
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
        Faces.saveCurrent(this);
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

    private static final java.util.concurrent.ExecutorService PREVIEW =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> new Thread(r, "gk-preview"));
    private boolean pvBusy, pvAgain;

    /** Pratonton dilukis di thread latar (dulu di thread utama: punca "GlassKit isn't responding"). */
    private void renderPreview() {
        if (preview == null) return;
        if (TextUtils.isEmpty(status.getText()) && !DepthRenderer.bgFile(this).exists()) {
            status.setText(R.string.st_start);
        }
        if (pvBusy) {
            pvAgain = true;
            return;
        }
        pvBusy = true;
        final int w = 540, h = Math.round(540f * getResources().getDisplayMetrics().heightPixels
                / getResources().getDisplayMetrics().widthPixels);
        final android.content.Context app = getApplicationContext();
        PREVIEW.execute(() -> {
            Bitmap out = null;
            try {
                long ver = prefs.getLong(Prefs.IMG_VER, 0);
                if (ver != cacheVer) {
                    cacheVer = ver;
                    cacheBg = DepthRenderer.load(DepthRenderer.bgFile(app));
                    cacheFg = DepthRenderer.load(DepthRenderer.fgFile(app));
                }
                out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                DepthRenderer.draw(app, new Canvas(out), w, h, cacheBg, cacheFg, prefs, true);
            } catch (Throwable t) {
                CrashLog.write(app, t);
            }
            final Bitmap done = out;
            runOnUiThread(() -> {
                pvBusy = false;
                if (done != null && preview != null && !isDestroyed()) preview.setImageBitmap(done);
                if (pvAgain) {
                    pvAgain = false;
                    if (!isDestroyed()) renderPreview();
                }
            });
        });
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

    private static int idxOf(int[] a, int v) {
        for (int i = 0; i < a.length; i++) if (a[i] == v) return i;
        return 0;
    }

    private String solatNext() {
        long[] w = SolatMode.window(this);
        if (w == null) return getString(R.string.solat_need_loc);
        if (w[0] <= System.currentTimeMillis()) return getString(R.string.solat_now);
        return getString(R.string.solat_next, getString(Prayer.NAMES[(int) w[2]]), Prayer.fmt(this, w[0]));
    }

}
